package io.github.hello09x.fakeplayer.v26_3.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/** Native slots point at the real target inventory. No close-time copy-back is involved. */
public final class PlayerControlMenu extends ChestMenu {
    private final BooleanSupplier valid;
    private final IntUnaryOperator mapping;

    public static InventoryView open(Player viewer, Player target, Inventory display,
                                     IntUnaryOperator mapping, BooleanSupplier valid) {
        var handle = ((CraftPlayer)viewer).getHandle();
        var live = new LiveInventory(target, display, mapping, valid);
        var id = handle.openMenu(new SimpleMenuProvider((window, inventory, player) ->
                new PlayerControlMenu(window, inventory, live, mapping, valid),
                net.minecraft.network.chat.Component.literal(target.getName() + " · 背包与装备")));
        return id.isPresent() ? handle.containerMenu.getBukkitView() : null;
    }

    private PlayerControlMenu(int id, net.minecraft.world.entity.player.Inventory viewer, LiveInventory live,
                              IntUnaryOperator mapping, BooleanSupplier valid) {
        super(MenuType.GENERIC_9x6, id, viewer, live, 6);
        this.valid = valid;
        this.mapping = mapping;
        for (int i=0;i<54;i++) {
            final int index=i;
            var previous=slots.get(i);
            var slot=new Slot(live,i,previous.x,previous.y) {
                @Override public boolean mayPlace(ItemStack stack) {
                    int target=mapping.applyAsInt(index);
                    if (!valid.getAsBoolean() || target<0) return false;
                    if (target>=36 && target<=39) {
                        var required=switch(target) {
                            case 36 -> org.bukkit.inventory.EquipmentSlot.FEET;
                            case 37 -> org.bukkit.inventory.EquipmentSlot.LEGS;
                            case 38 -> org.bukkit.inventory.EquipmentSlot.CHEST;
                            default -> org.bukkit.inventory.EquipmentSlot.HEAD;
                        };
                        return !stack.isEmpty() && CraftItemStack.asBukkitCopy(stack).getType().getEquipmentSlot()==required;
                    }
                    return true;
                }
                @Override public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
                    return valid.getAsBoolean() && mapping.applyAsInt(index)>=0;
                }
                @Override public int getMaxStackSize() {
                    int target=mapping.applyAsInt(index);
                    if (target<0) return 0;
                    return target>=36 && target<=39 ? 1 : super.getMaxStackSize();
                }
                @Override public int getMaxStackSize(ItemStack item) { return Math.min(getMaxStackSize(),item.getMaxStackSize()); }
            };
            slot.index=i;
            slots.set(i,slot);
        }
    }

    @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return valid.getAsBoolean(); }

    @Override public void clicked(int slot, int button, ContainerInput input, net.minecraft.world.entity.player.Player player) {
        if (!valid.getAsBoolean()) return;
        // Includes creative clone, number-key swap and throw; decorations never enter an inventory.
        if (slot>=0 && slot<54 && mapping.applyAsInt(slot)<0) return;
        super.clicked(slot,button,input,player);
    }

    @Override public boolean canDragTo(Slot slot) {
        return valid.getAsBoolean() && (slot.index>=54 || mapping.applyAsInt(slot.index)>=0);
    }

    @Override public boolean canTakeItemForPickAll(ItemStack item, Slot slot) {
        return valid.getAsBoolean() && (slot.index>=54 || mapping.applyAsInt(slot.index)>=0);
    }

    @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
        if (!valid.getAsBoolean() || index<0 || index>=slots.size()) return ItemStack.EMPTY;
        // Vanilla's merge pass does not consult mayPlace. Action-page decorations are not storage.
        if (index>=54 && mapping.applyAsInt(9)<0) return ItemStack.EMPTY;
        var slot=slots.get(index);
        if (!slot.mayPickup(player) || !slot.hasItem()) return ItemStack.EMPTY;
        var item=slot.getItem(); var original=item.copy();
        // Incoming shift-clicks use only the 36 storage slots, never the offhand or controls.
        if (!(index<54 ? moveItemStackTo(item,54,slots.size(),true) : moveItemStackTo(item,9,45,false))) return ItemStack.EMPTY;
        if (item.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player,item);
        return original;
    }

    private static final class LiveInventory extends SimpleContainer {
        private final net.minecraft.world.entity.player.Inventory source;
        private final Inventory display;
        private final IntUnaryOperator mapping;
        private final BooleanSupplier valid;

        LiveInventory(Player target, Inventory display, IntUnaryOperator mapping, BooleanSupplier valid) {
            super(54,display.getHolder());
            this.source=((CraftPlayer)target).getHandle().getInventory();
            this.display=display; this.mapping=mapping; this.valid=valid;
        }
        @Override public ItemStack getItem(int index) {
            int target=mapping.applyAsInt(index);
            return target>=0 ? source.getItem(target) : CraftItemStack.asNMSCopy(display.getItem(index));
        }
        @Override public void setItem(int index, ItemStack item) {
            int target=mapping.applyAsInt(index);
            if (target>=0 && valid.getAsBoolean()) { source.setItem(target,item); source.setChanged(); }
        }
        @Override public ItemStack removeItem(int index,int amount) {
            int target=mapping.applyAsInt(index);
            return target>=0 && valid.getAsBoolean() ? source.removeItem(target,amount) : ItemStack.EMPTY;
        }
        @Override public ItemStack removeItemNoUpdate(int index) {
            int target=mapping.applyAsInt(index);
            return target>=0 && valid.getAsBoolean() ? source.removeItemNoUpdate(target) : ItemStack.EMPTY;
        }
        @Override public void setChanged() { if (source!=null) source.setChanged(); }
        @Override public List<ItemStack> getContents() {
            return new AbstractList<>() {
                @Override public ItemStack get(int index) { return getItem(index); }
                @Override public int size() { return 54; }
            };
        }
        @Override public boolean isEmpty() { for(int i=0;i<54;i++) if(!getItem(i).isEmpty()) return false; return true; }
        @Override public void clearContent() { /* Decorations and target data are never cleared by menu disposal. */ }
    }
}
