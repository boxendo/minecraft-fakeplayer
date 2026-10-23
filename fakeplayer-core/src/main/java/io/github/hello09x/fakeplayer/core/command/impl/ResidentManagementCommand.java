package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.executors.CommandArguments;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import io.github.hello09x.fakeplayer.core.manager.ResidentManager;
import io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;

@Singleton
public class ResidentManagementCommand {
    @Inject private ResidentManager residents;

    public void list(CommandSender sender,CommandArguments args) {
        var entries=residents.visible(sender);
        sender.sendMessage(Component.translatable("fakeplayer.resident.records",Component.text(entries.size())));
        int page=(int)args.getOptional("page").orElse(1);
        entries.stream().skip((long)(page-1)*10).limit(10).forEach(v -> sender.sendMessage(Component.text(
                v.name()+" | "+state(v.state())+" | "+v.world()+" | "+v.reason())));
        if (!residents.isReadable()) sender.sendMessage(Component.translatable("fakeplayer.resident.unreadable"));
    }

    private ResidentManager.View target(CommandSender sender,CommandArguments args) throws WrapperCommandSyntaxException {
        var view=residents.find(sender,(String)args.get("record"));
        if (view==null) throw CommandAPI.failWithString(io.github.hello09x.devtools.core.utils.ComponentUtils.toString(Component.translatable("fakeplayer.resident.not-found"), io.github.hello09x.devtools.core.translation.TranslatorUtils.getLocale(sender)));
        return view;
    }

    public void status(CommandSender sender,CommandArguments args) throws WrapperCommandSyntaxException {
        var v=target(sender,args);
        sender.sendMessage(Component.text(v.name()+" | "+v.uuid()));
        sender.sendMessage(Component.text(state(v.state())+" | "+v.world()+" | "+v.reason()));
        sender.sendMessage(Component.text("Owner: "+v.creatorName()+" | "+v.creatorId()));
        sender.sendMessage(Component.text("Attempts: "+v.attempts()+"/3; save="+DiagnosticManager.time(v.lastSave())+"; restore="+DiagnosticManager.time(v.lastRestore())));
    }

    public void restore(CommandSender sender,CommandArguments args) throws WrapperCommandSyntaxException {
        var v=target(sender,args);
        sender.sendMessage(Component.text(residents.retry(v.uuid())));
    }

    public void forget(CommandSender sender,CommandArguments args) throws WrapperCommandSyntaxException {
        var v=target(sender,args);
        if (!residents.forgetRecord(v.uuid())) sender.sendMessage(Component.translatable("fakeplayer.resident.unreadable"));
        else sender.sendMessage(Component.translatable("fakeplayer.resident.forgotten",Component.text(v.name())));
    }

    public static String state(String stage) {
        return switch(stage) {
            case "PENDING", "QUEUED" -> "等待生成";
            case "ONLINE" -> "在线";
            case "WAITING_WORLD" -> "等待世界加载";
            case "NAME_CONFLICT" -> "名称/UUID 被占用";
            case "LIMIT_BLOCKED" -> "等待数量/队列额度";
            case "RETRY_EXHAUSTED" -> "需要手动重试";
            case "CHUNK_LOADING" -> "准备区块";
            case "PRE_LOGIN", "LOGIN" -> "登录检查";
            case "PREPARING" -> "运行生成前设置";
            case "LOAD_DATA" -> "读取存档";
            case "JOINING", "TELEPORT" -> "加入并定位";
            case "CANCELLED" -> "已取消";
            case "FAILED" -> "失败";
            case "OFFLINE" -> "已离线";
            default -> "尚无当前进程记录";
        };
    }
}
