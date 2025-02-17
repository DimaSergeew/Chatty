package ru.brikster.chatty.api.chat.message.context;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.brikster.chatty.api.chat.Chat;

import java.util.Collection;
import java.util.Map;

public interface MessageContext<MessageT> {

    boolean isCancelled();

    void setCancelled(boolean cancelled);

    @NotNull
    Component getFormat();

    void setFormat(@NotNull Component component);

    @NotNull
    String getMessageFormat();

    void setMessageFormat(@NotNull String messageFormat);

    @NotNull
    Collection<? extends @NotNull Player> getRecipients();

    void setRecipients(@NotNull Collection<? extends @NotNull Player> recipients);

    @NotNull
    MessageT getMessage();

    void setMessage(@NotNull MessageT message);

    @NotNull
    Chat getChat();

    @NotNull
    Player getSender();

    @Nullable
    Player getTarget();

    @NotNull
    Map<String, Object> getMetadata();

    void setTarget(@NotNull Player player);

}
