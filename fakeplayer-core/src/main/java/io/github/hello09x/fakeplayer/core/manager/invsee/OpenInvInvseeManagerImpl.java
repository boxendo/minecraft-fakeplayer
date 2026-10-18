package io.github.hello09x.fakeplayer.core.manager.invsee;

import com.google.common.base.Throwables;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerList;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * 使用 OpenInv 打开假人背包。
 *
 * <p>这里刻意通过反射调用 OpenInv 的 {@code IOpenInv} 接口，而不是直接依赖它的 jar：
 * OpenInv 只发布在 GitHub Releases，没有 Maven 坐标，如果声明成 system scope，
 * 其他人 clone 下来就必须自备 {@code lib/OpenInv.jar} 才能编译。</p>
 *
 * @author tanyaofei
 * @since 2024/8/12
 **/
public class OpenInvInvseeManagerImpl extends AbstractInvseeManager {

    private final static Logger log = Main.getInstance().getLogger();

    private final Object openInv;

    private final Method getSpecialInventory;

    private final Method openInventory;

    public OpenInvInvseeManagerImpl(FakeplayerManager manager, FakeplayerList fakeplayerList) {
        super(manager, fakeplayerList);
        Plugin plugin = Bukkit.getPluginManager().getPlugin("OpenInv");
        this.openInv = plugin;
        this.getSpecialInventory = this.findMethod(plugin, "getSpecialInventory", Player.class, boolean.class);
        this.openInventory = this.findMethod(plugin, "openInventory", Player.class, Inventory.class);
    }

    private @Nullable Method findMethod(@Nullable Plugin plugin, @NotNull String name, @NotNull Class<?>... parameterTypes) {
        if (plugin == null) {
            return null;
        }
        for (var method : plugin.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            var types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                if (!types[i].isAssignableFrom(parameterTypes[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    @Override
    protected @Nullable InventoryView openInventory(@NotNull Player viewer, @NotNull Player whom) {
        if (this.openInv == null || this.getSpecialInventory == null || this.openInventory == null) {
            return null;
        }
        try {
            var inventory = (Inventory) this.getSpecialInventory.invoke(this.openInv, whom, true);
            return (InventoryView) this.openInventory.invoke(this.openInv, viewer, inventory);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof InstantiationException) {
                log.warning("Failed to open %s's inventory for %s\n%s".formatted(
                        whom.getName(),
                        viewer.getName(),
                        Throwables.getStackTraceAsString(e.getTargetException())
                ));
                return null;
            }
            throw new IllegalStateException(e.getTargetException());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

}
