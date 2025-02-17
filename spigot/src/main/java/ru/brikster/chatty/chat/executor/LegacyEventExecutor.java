package ru.brikster.chatty.chat.executor;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;
import org.jetbrains.annotations.NotNull;
import ru.brikster.chatty.api.chat.Chat;
import ru.brikster.chatty.api.chat.ChatStyle;
import ru.brikster.chatty.api.chat.message.context.MessageContext;
import ru.brikster.chatty.api.chat.message.strategy.MessageTransformStrategy.Stage;
import ru.brikster.chatty.api.event.ChattyMessageEvent;
import ru.brikster.chatty.api.event.ChattyPreMessageEvent;
import ru.brikster.chatty.chat.construct.ComponentFromContextConstructor;
import ru.brikster.chatty.chat.message.context.MessageContextImpl;
import ru.brikster.chatty.chat.message.transform.intermediary.IntermediateMessageTransformer;
import ru.brikster.chatty.chat.message.transform.processor.MessageTransformStrategiesProcessor;
import ru.brikster.chatty.chat.selection.ChatSelector;
import ru.brikster.chatty.chat.style.ChatStylePlayerGrouper;
import ru.brikster.chatty.config.file.MessagesConfig;
import ru.brikster.chatty.config.file.SettingsConfig;
import ru.brikster.chatty.proxy.ProxyService;
import ru.brikster.chatty.util.EventUtil;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class LegacyEventExecutor implements Listener, EventExecutor {

    private final Map<Integer, MessageContext<String>> pendingMessages = new ConcurrentHashMap<>();

    @Inject private ChatSelector selector;
    @Inject private ComponentFromContextConstructor componentFromContextConstructor;
    @Inject private BukkitAudiences audiences;
    @Inject private SettingsConfig settings;
    @Inject private MessagesConfig messages;
    @Inject private MessageTransformStrategiesProcessor processor;
    @Inject private IntermediateMessageTransformer intermediateMessageTransformer;
    @Inject private Logger logger;
    @Inject private ProxyService proxyService;
    @Inject private ChatStylePlayerGrouper chatStylePlayerGrouper;

    @Override
    public void execute(@NotNull Listener listener, @NotNull Event event) {
        if (listener == this && event instanceof AsyncPlayerChatEvent) {
            this.onChat((AsyncPlayerChatEvent) event);
        }
    }

    private void onChat(AsyncPlayerChatEvent event) {
        int eventHashcode = System.identityHashCode(event);
        boolean processed = false;

        try {
            long millisStart = System.currentTimeMillis();

            MessageContext<String> unhandledEarlyContext = createEarlyContext(event);
            MessageContext<String> earlyContext = processor.handle(unhandledEarlyContext, Stage.EARLY).getNewContext();

            event.getRecipients().clear();

            if (!earlyContext.isCancelled()) {
                event.getRecipients().addAll(earlyContext.getRecipients());
                event.setMessage(earlyContext.getMessage());
            }

            pendingMessages.put(eventHashcode, earlyContext);

            long millisEnd = System.currentTimeMillis();
            long millisDelta = millisEnd - millisStart;

            if (settings.isDebug()) {
                logger.log(Level.INFO, "Early context processed for " + millisDelta + "ms (event: " + eventHashcode + ")");
            }

            processed = true;
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Cannot handle chat event", t);
        } finally {
            if (!processed) {
                event.setCancelled(true);
            }
        }
    }

    private MessageContext<String> createEarlyContext(AsyncPlayerChatEvent event) {
        Chat chat = selector.selectChat(event.getMessage(), chatCandidate ->
                !chatCandidate.isPermissionRequired() ||
                        chatCandidate.hasSymbolWritePermission(event.getPlayer()));

        List<Player> recipients;
        if (chat == null) {
            audiences.player(event.getPlayer().getUniqueId())
                    .sendMessage(messages.getChatNotFound());
            recipients = Collections.emptyList();
            event.setCancelled(true);
        } else {
            if (settings.isRespectForeignRecipients()) {
                Predicate<Player> playerPredicate = chat.getRecipientPredicate(event.getPlayer());
                recipients = event.getRecipients().stream()
                        .filter(playerPredicate)
                        .collect(Collectors.toList());
            } else {
                recipients = new ArrayList<>(chat.calculateRecipients(event.getPlayer()));
            }
        }

        return new MessageContextImpl<>(
                chat,
                event.getPlayer(),
                new HashMap<>(),
                event.isCancelled(),
                chat == null ? Component.text("") : chat.getFormat(),
                chat == null ? "{original-message}" : chat.getMessageFormat(),
                recipients,
                event.getMessage(),
                null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void handleFinishedEarlyContextEvent(AsyncPlayerChatEvent event) {
        int eventHashcode = System.identityHashCode(event);

        MessageContext<String> earlyContext = pendingMessages.remove(eventHashcode);
        if (earlyContext == null) {
            if (settings.isDebug()) {
                logger.log(Level.WARNING, "Cannot handle unprocessed chat event from \"{0}\" with format \"{1}\" and message \"{2}\"",
                        new Object[]{event.getPlayer().getName(), event.getFormat(), event.getMessage()});
            }
            return;
        }

        if (earlyContext.isCancelled()) {
            event.setCancelled(true);
        }

        if (event.isCancelled()) return;

        earlyContext.setRecipients(new ArrayList<>(event.getRecipients()));
        earlyContext.setMessage(event.getMessage());

        event.getRecipients().clear();

        boolean processed = false;

        try {
            long millisStart = System.currentTimeMillis();

            MessageContext<Component> earlyComponentContext = intermediateMessageTransformer.handle(earlyContext).getNewContext();

            if (PlainTextComponentSerializer.plainText().serialize(earlyComponentContext.getMessage()).isBlank()) {
                // will be cancelled in finally block
                return;
            }

            MessageContext<Component> middleContext = processor.handle(earlyComponentContext, Stage.MIDDLE).getNewContext();

            ChattyPreMessageEvent preMessageEvent = new ChattyPreMessageEvent(
                    middleContext.getSender(),
                    middleContext.getChat(),
                    middleContext.getChat().getStyles(),
                    middleContext.getFormat(),
                    middleContext.getMessageFormat(),
                    middleContext.getMessage(),
                    List.copyOf(middleContext.getRecipients())
            );

            EventUtil.callAsynchronously(preMessageEvent);

            middleContext.setFormat(preMessageEvent.getFormat());
            middleContext.setMessageFormat(preMessageEvent.getMessageFormat());
            middleContext.setMessage(preMessageEvent.getMessage());

            Set<ChatStyle> styles = preMessageEvent.getStyles();

            ChattyMessageEvent messageEvent = new ChattyMessageEvent(
                    middleContext.getSender(),
                    middleContext.getChat(),
                    PlainTextComponentSerializer.plainText().serialize(preMessageEvent.getMessage()),
                    List.copyOf(middleContext.getRecipients())
            );

            EventUtil.callAsynchronously(messageEvent);

            if (middleContext.getChat().getSound() != null) {
                for (Player recipient : middleContext.getRecipients()) {
                    if (recipient != middleContext.getSender()) {
                        audiences.player(recipient).playSound(middleContext.getChat().getSound());
                    }
                }
            }

            if (middleContext.getChat().getRange() <= -3) {
                sendProxyMessage(middleContext);
            }

            List<MessageContext<Component>> groupedByStyle = groupedByStyle(middleContext, styles);
            for (int groupIndex = 0; groupIndex < groupedByStyle.size(); groupIndex++) {
                MessageContext<Component> groupContext = groupedByStyle.get(groupIndex);
                groupContext.getMetadata().put("all_recipients", middleContext.getRecipients());

                MessageContext<Component> lateContext = processor.handle(groupContext, Stage.LATE).getNewContext();
                sendProcessedMessage(lateContext, middleContext.getRecipients());

                // Format console message without style
                if (groupIndex == 0) {
                    String stringFormat = LegacyComponentSerializer.legacySection().serialize(lateContext.getFormat());
                    String stringMessage = LegacyComponentSerializer.legacySection().serialize(lateContext.getMessage());
                    stringFormat = stringFormat.replaceFirst(Pattern.quote("{player}"), Matcher.quoteReplacement(lateContext.getSender().getDisplayName()));
                    stringFormat = stringFormat.replaceFirst(Pattern.quote("{message}"), Matcher.quoteReplacement(stringMessage));
                    stringFormat = stringFormat.replace("%", "%%");
                    event.setFormat(stringFormat);
                    event.setMessage(stringMessage);
                }
            }

            if (middleContext.getChat().getRange() > -3) {
                sendNobodyHeardYou(event, middleContext);
            }

            long millisEnd = System.currentTimeMillis();
            long millisDelta = millisEnd - millisStart;

            if (settings.isDebug()) {
                logger.log(Level.INFO, "Later contexts processed for " + millisDelta + "ms (event: " + eventHashcode + ")");
            }

            processed = true;
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Cannot handle chat event", t);
        } finally {
            if (!processed) {
                event.setCancelled(true);
            }
        }
    }

    private void sendProxyMessage(MessageContext<Component> middleContext) {
        Chat chat = middleContext.getChat();

        Component noStyleProxyMessage;
        Map<String, ru.brikster.chatty.proxy.data.ChatStyle> proxyStyles = new HashMap<>();

        MessageContext<Component> proxyNoStyleContext = new MessageContextImpl<>(middleContext);
        proxyNoStyleContext.setFormat(chat.getFormat());
        proxyNoStyleContext.setMessageFormat(chat.getMessageFormat());
        proxyNoStyleContext.setMessage(middleContext.getMessage());
        proxyNoStyleContext.setRecipients(Collections.emptyList());
        MessageContext<Component> proxyNoStyleLateContext = processor.handle(proxyNoStyleContext, Stage.LATE).getNewContext();
        noStyleProxyMessage = componentFromContextConstructor.construct(proxyNoStyleLateContext).compact();

        for (var style : chat.getStyles()) {
            MessageContext<Component> proxyStyleContext = new MessageContextImpl<>(middleContext);
            proxyStyleContext.setFormat(style.format());
            proxyStyleContext.setMessageFormat(style.messageFormat());
            proxyStyleContext.setMessage(middleContext.getMessage());
            proxyStyleContext.setRecipients(Collections.emptyList());
            MessageContext<Component> proxyLateContext = processor.handle(proxyStyleContext, Stage.LATE).getNewContext();
            Component message = componentFromContextConstructor.construct(proxyLateContext).compact();

            proxyStyles.put(style.id(), new ru.brikster.chatty.proxy.data.ChatStyle(
                    style.priority(),
                    GsonComponentSerializer.gson().serialize(message),
                    proxyLateContext.getMessageFormat()
            ));
        }

        proxyService.sendChatMessage(chat, noStyleProxyMessage, proxyStyles, chat.getSound());
    }

    private void sendNobodyHeardYou(AsyncPlayerChatEvent event, MessageContext<Component> middleContext) {
        if (middleContext.getChat().isSendNobodyHeardYou()) {
            Set<Player> allowedRecipients = new HashSet<>();
            allowedRecipients.add(event.getPlayer());

            if (middleContext.getChat().isEnableSpy() && middleContext.getMetadata().containsKey("spy-recipients")) {
                //noinspection unchecked
                allowedRecipients.addAll((List<Player>) middleContext.getMetadata().get("spy-recipients"));
            }

            if (settings.isHideVanishedRecipients()) {
                allowedRecipients.removeIf(player -> player != event.getPlayer()
                        && !event.getPlayer().canSee(player));
            }

            if (allowedRecipients.containsAll(middleContext.getRecipients())) {
                audiences.player(event.getPlayer()).sendMessage(messages.getNobodyHeard());
            }
        }
    }

    private List<MessageContext<Component>> groupedByStyle(MessageContext<Component> context, Set<ChatStyle> styles) {
        Chat chat = context.getChat();
        boolean useSpy = chat.isEnableSpy() && context.getMetadata().containsKey("spy-recipients");

        //noinspection unchecked
        var grouping = chatStylePlayerGrouper.makeGrouping(context.getRecipients(), styles,
                useSpy ? (List<Player>) context.getMetadata().get("spy-recipients") : null,
                useSpy ? new ChatStyle(
                        "internal-spy-style",
                        chat.getSpyFormat(),
                        chat.getMessageFormat(),
                        Integer.MAX_VALUE) : null);

        Map<ChatStyle, List<Player>> stylePlayersMap = grouping.getStylesMap();

        List<MessageContext<Component>> contexts = new ArrayList<>();

        List<Player> noStyleRecipients = grouping.getNoStylePlayers();

        MessageContext<Component> noStyleContext = new MessageContextImpl<>(context);
        noStyleContext.setMessage(context.getMessage());
        noStyleContext.setRecipients(noStyleRecipients);
        contexts.add(noStyleContext);

        stylePlayersMap.forEach((style, recipients) -> {
            MessageContext<Component> styleContext = new MessageContextImpl<>(context);
            styleContext.setFormat(style.format());
            styleContext.setMessageFormat(style.messageFormat());
            styleContext.setMessage(context.getMessage());
            styleContext.setRecipients(recipients);
            contexts.add(styleContext);
        });

        return contexts;
    }

    private void sendProcessedMessage(MessageContext<Component> lateContext,
                                      Collection<? extends @NotNull Player> middleContextRecipients) {
        Identity senderIdentity = Identity.identity(lateContext.getSender().getUniqueId());
        for (Player recipient : lateContext.getRecipients()) {
            MessageContext<Component> personalLateContext = new MessageContextImpl<>(lateContext);
            personalLateContext.getMetadata().put("all_recipients", middleContextRecipients);
            personalLateContext.setMessage(lateContext.getMessage());
            personalLateContext.setRecipients(Collections.singletonList(recipient));
            personalLateContext.setTarget(recipient);

            MessageContext<Component> postContext = processor.handle(personalLateContext, Stage.POST).getNewContext();

            Component message = componentFromContextConstructor.construct(postContext).compact();
            if (settings.isSendIdentifiedMessages()) {
                //noinspection deprecation
                audiences.player(recipient).sendMessage(senderIdentity, message);
            } else {
                audiences.player(recipient).sendMessage(message);
            }
        }
    }

}
