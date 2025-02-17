package ru.brikster.chatty.api.event;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import ru.brikster.chatty.api.chat.Chat;
import ru.brikster.chatty.api.chat.ChatStyle;

import java.util.List;
import java.util.Set;

/**
 * Event for external changing format and message
 */
public final class ChattyPreMessageEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player player;
    private final Chat chat;
    private Set<ChatStyle> styles;
    private Component format;
    private String messageFormat;
    private Component message;
    private final List<Player> recipients;

    public ChattyPreMessageEvent(@NotNull Player player,
                                 @NotNull Chat chat,
                                 @NotNull Set<ChatStyle> styles,
                                 @NotNull Component format,
                                 @NotNull String messageFormat,
                                 @NotNull Component message,
                                 @NotNull List<Player> recipients) {
        super(true);
        this.player = player;
        this.chat = chat;
        this.styles = styles;
        this.format = format;
        this.messageFormat = messageFormat;
        this.message = message;
        this.recipients = recipients;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }

    /**
     * Returns the player that sends a message
     *
     * @return player that sends a message
     */
    @NotNull
    public Player getSender() {
        return player;
    }

    /**
     * Returns the chat to which message sends
     *
     * @return chat to which message sends
     */
    @NotNull
    public Chat getChat() {
        return chat;
    }

    /**
     * Returns the chat format
     *
     * @return format component
     */
    @NotNull
    public Component getFormat() {
        return format;
    }

    /**
     * Returns the message format
     *
     * @return message format
     */
    @NotNull
    public String getMessageFormat() {
        return messageFormat;
    }

    /**
     * Returns the message component, typed by player
     *
     * @return message component
     */
    @NotNull
    public Component getMessage() {
        return message;
    }

    public @NotNull Set<ChatStyle> getStyles() {
        return styles;
    }

    public void setStyles(@NotNull Set<ChatStyle> styles) {
        this.styles = styles;
    }

    public void setFormat(@NotNull Component format) {
        this.format = format;
    }

    public void setMessageFormat(@NotNull String messageFormat) {
        this.messageFormat = messageFormat;
    }

    public void setMessage(@NotNull Component message) {
        this.message = message;
    }

    @NotNull
    public List<Player> getRecipients() {
        return recipients;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

}
