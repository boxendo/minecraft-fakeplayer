package io.github.hello09x.fakeplayer.core.util;

import io.github.hello09x.fakeplayer.core.command.Permission;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.UUID;

/** Shared ownership checks for online players, durable records, and suggestions. */
public final class FakeplayerAccess {
    private FakeplayerAccess() {}

    public static UUID creatorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId()
                : sender instanceof OfflineCommandSender offline ? offline.getUniqueId() : null;
    }

    public static boolean owns(CommandSender sender, UUID creatorId, String creatorName) {
        if (creatorId != null) {
            return sender instanceof Player player && creatorId.equals(player.getUniqueId());
        }
        // Legacy resident files may have lost their owner UUID. Preserve access by their stored name.
        if (sender instanceof Player && java.util.Set.of("CONSOLE", "Rcon").contains(creatorName)) return false;
        return creatorName.equalsIgnoreCase(sender.getName());
    }

    public static boolean managesOthers(CommandSender sender) {
        return sender.hasPermission(Permission.manageOthers);
    }

    public static boolean mayManage(CommandSender sender, UUID creatorId, String creatorName) {
        return managesOthers(sender) || owns(sender, creatorId, creatorName);
    }
}
