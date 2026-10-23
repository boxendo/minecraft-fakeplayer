package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.fakeplayer.core.manager.ResidentManager;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.WHITE;

@Singleton
public class ResidentCommand extends AbstractCommand {

    @Inject
    private ResidentManager residentManager;

    /**
     * 开关假人驻留
     * <p>开启后插件关闭时会保存该假人的位置与动作，服务器重启后原地恢复。</p>
     */
    public void resident(@NotNull CommandSender sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        set(sender,args,Boolean.parseBoolean((String)Objects.requireNonNull(args.get("value"))));
    }

    public void set(CommandSender sender,CommandArguments args,boolean resident) throws WrapperCommandSyntaxException {
        var target=super.getFakeplayer(sender,args);
        if (!residentManager.isReadable()) {
            sender.sendMessage(translatable("fakeplayer.resident.unreadable"));
            return;
        }
        String value=String.valueOf(resident);
        this.residentManager.setResident(target,resident);

        sender.sendMessage(translatable(
                "fakeplayer.command.resident.success",
                text(target.getName(), WHITE),
                text(value, GOLD)
        ).color(GRAY));
    }

}
