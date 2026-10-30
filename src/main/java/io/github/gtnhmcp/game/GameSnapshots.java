package io.github.gtnhmcp.game;

import static io.github.gtnhmcp.game.Values.map;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

/** Called only on the thread that owns the supplied game objects. No objects escape. */
public final class GameSnapshots {

    private GameSnapshots() {}

    public static Map<String, Object> item(ItemStack stack) {
        if (stack == null) return null;
        String name;
        try {
            name = stack.getDisplayName();
        } catch (RuntimeException e) {
            name = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        }
        String nbt = stack.hasTagCompound() ? stack.getTagCompound()
            .toString() : null;
        boolean truncated = nbt != null && nbt.length() > 4096;
        return map(
            "registryName",
            Item.itemRegistry.getNameForObject(stack.getItem()),
            "meta",
            stack.getItemDamage(),
            "displayName",
            name,
            "count",
            stack.stackSize,
            "nbt",
            truncated ? null : nbt,
            "nbtOmitted",
            truncated);
    }

    public static Map<String, Object> inventory(IInventory inventory) {
        int total = inventory.getSizeInventory();
        List<Object> slots = new ArrayList<>();
        for (int i = 0; i < Math.min(total, 256); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null) slots.add(map("slot", i, "item", item(stack)));
        }
        return map("slotCount", total, "slots", slots, "complete", total <= 256);
    }

    public static Map<String, Object> player(EntityPlayer player) {
        return map(
            "name",
            player.getCommandSenderName(),
            "dimension",
            player.dimension,
            "position",
            map("x", player.posX, "y", player.posY, "z", player.posZ),
            "health",
            player.getHealth(),
            "food",
            player.getFoodStats()
                .getFoodLevel(),
            "selectedSlot",
            player.inventory.currentItem,
            "heldItem",
            item(player.getHeldItem()));
    }

    public static Map<String, Object> block(World world, int x, int y, int z, boolean includeTile) {
        if (!world.blockExists(x, y, z)) throw new IllegalArgumentException("chunk_not_loaded: target is not loaded");
        Block block = world.getBlock(x, y, z);
        Map<String, Object> result = map(
            "dimension",
            world.provider.dimensionId,
            "x",
            x,
            "y",
            y,
            "z",
            z,
            "registryName",
            Block.blockRegistry.getNameForObject(block),
            "meta",
            world.getBlockMetadata(x, y, z));
        if (!includeTile) return result;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) return result;
        result.put(
            "tileType",
            tile.getClass()
                .getName());
        if (tile instanceof IInventory) result.put("inventory", inventory((IInventory) tile));
        if (tile instanceof IFluidHandler) {
            List<Object> tanks = new ArrayList<>();
            // UNKNOWN is one requested view, not a promise that all sided tanks are visible.
            try {
                FluidTankInfo[] infos = ((IFluidHandler) tile).getTankInfo(ForgeDirection.UNKNOWN);
                if (infos != null) {
                    for (int i = 0; i < Math.min(infos.length, 64); i++) {
                        FluidTankInfo info = infos[i];
                        if (info != null) tanks.add(
                            map(
                                "index",
                                i,
                                "capacityMb",
                                info.capacity,
                                "fluid",
                                info.fluid == null ? null
                                    : info.fluid.getFluid()
                                        .getName(),
                                "amountMb",
                                info.fluid == null ? 0 : info.fluid.amount));
                    }
                }
                result.put(
                    "tanks",
                    map(
                        "side",
                        "UNKNOWN",
                        "entries",
                        tanks,
                        "reportedTankCount",
                        infos == null ? null : infos.length,
                        "available",
                        infos != null,
                        "complete",
                        infos != null && infos.length <= 64,
                        "coverage",
                        infos == null ? "handler_returned_null" : "reported_by_handler"));
            } catch (RuntimeException e) {
                result.put("tanksUnavailable", true);
            }
        }
        if (tile.getClass()
            .getName()
            .startsWith("gregtech.")) addGregTechState(tile, result);
        return result;
    }

    private static void addGregTechState(Object tile, Map<String, Object> result) {
        Map<String, Object> state = map();
        addGetter(state, "active", tile, "isActive");
        addGetter(state, "allowedToWork", tile, "isAllowedToWork");
        state.put("energyScope", "controller_tile");
        addGetter(state, "controllerStoredEu", tile, "getStoredEU");
        addGetter(state, "controllerEuCapacity", tile, "getEUCapacity");
        Object meta = getter(tile, "getMetaTileEntity");
        if (meta != null) {
            state.put(
                "machineType",
                meta.getClass()
                    .getName());
            addGetter(state, "info", meta, "getInfoData");
            addGetter(state, "progressTicks", meta, "getProgresstime");
            addGetter(state, "maxProgressTicks", meta, "maxProgresstime");
            Object check = getter(meta, "getCheckRecipeResult");
            if (check != null) {
                addGetter(state, "recipeCheckId", check, "getID");
                addGetter(state, "recipeCheckSuccessful", check, "wasSuccessful");
                addGetter(state, "recipeCheckMessage", check, "getDisplayString");
            }
        }
        result.put("gregTech", state);
        result.put("diagnosticCoverage", "available_public_read_only_getters; no_structure_recheck");
    }

    private static Object getter(Object object, String name) {
        try {
            Method method = object.getClass()
                .getMethod(name);
            return method.invoke(object);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void addGetter(Map<String, Object> target, String key, Object object, String method) {
        Object value = getter(object, method);
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            target.put(key, value);
        } else if (value instanceof String[]) {
            List<String> strings = new ArrayList<>();
            for (String s : (String[]) value) {
                if (strings.size() == 32) break;
                strings.add(s == null ? "" : s.substring(0, Math.min(s.length(), 1024)));
            }
            target.put(key, strings);
        }
    }
}
