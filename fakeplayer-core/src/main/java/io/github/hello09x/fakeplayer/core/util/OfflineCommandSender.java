package io.github.hello09x.fakeplayer.core.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * 离线的假人创建者。
 *
 * <p>服务器重启后需要恢复驻留假人, 但此时原创建者可能不在线。
 * 这个 {@link CommandSender} 用保存下来的名字冒充创建者, 使
 * {@code /fp list}、{@code /fp kill} 等按创建者过滤的逻辑仍然有效。</p>
 *
 * <p><b>权限</b>：这是一个只用于标识“创建者是谁”的占位对象，
 * 它不持有任何权限（{@code isOp()}、{@code hasPermission()} 均为 {@code false}），
 * 以免重启恢复的假人通过创建者身份间接获得提权。</p>
 */
public class OfflineCommandSender implements CommandSender {

    private final String name;

    private final UUID uuid;

    private final CommandSender delegate;

    public OfflineCommandSender(@NotNull String name, @Nullable UUID uuid) {
        this.name = name;
        this.uuid = uuid;
        this.delegate = Bukkit.getConsoleSender();
    }

    public @Nullable UUID getUniqueId() {
        return this.uuid;
    }

    @Override
    public void sendMessage(@NotNull String message) {
        this.delegate.sendMessage("[fakeplayer] " + message);
    }

    @Override
    public void sendMessage(String @NotNull ... messages) {
        for (var message : messages) {
            this.sendMessage(message);
        }
    }

    @Override
    public void sendMessage(@Nullable UUID sender, @NotNull String message) {
        this.sendMessage(message);
    }

    @Override
    public void sendMessage(@Nullable UUID sender, String @NotNull ... messages) {
        this.sendMessage(messages);
    }

    @Override
    public void sendMessage(@NotNull Component message) {
        this.delegate.sendMessage(message);
    }

    @Override
    public @NotNull Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public @NotNull String getName() {
        return this.name;
    }

    @Override
    public @NotNull Component name() {
        return Component.text(this.name);
    }

    @Override
    public @NotNull Spigot spigot() {
        return this.delegate.spigot();
    }

    @Override
    public boolean isPermissionSet(@NotNull String name) {
        return false;
    }

    @Override
    public boolean isPermissionSet(@NotNull Permission perm) {
        return false;
    }

    @Override
    public boolean hasPermission(@NotNull String name) {
        return false;
    }

    @Override
    public boolean hasPermission(@NotNull Permission perm) {
        return false;
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value, int ticks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, int ticks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeAttachment(@NotNull PermissionAttachment attachment) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void recalculatePermissions() {
    }

    @Override
    public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return Set.of();
    }

    @Override
    public boolean isOp() {
        return false;
    }

    @Override
    public void setOp(boolean value) {
        throw new UnsupportedOperationException();
    }

}
