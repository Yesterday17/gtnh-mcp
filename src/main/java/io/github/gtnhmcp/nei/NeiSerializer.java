package io.github.gtnhmcp.nei;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.StackInfo;
import codechicken.nei.recipe.TemplateRecipeHandler;

/** Copies game objects into bounded JSON-compatible snapshots; never calls drawing or GUI tooltip methods. */
public final class NeiSerializer {

    private static final int MAX_SLOTS = 64;
    private static final int MAX_ALTERNATIVES_PER_SLOT = 16;
    private static final int MAX_ALTERNATIVES_PER_RECIPE = 128;
    private static final int MAX_NBT_LENGTH = 4096;
    private static final int MAX_RECIPE_TEXT = 24_000;

    private NeiSerializer() {}

    public static Map<String, Object> item(ItemStack stack) {
        return item(stack, new Budget(), true);
    }

    private static Map<String, Object> item(ItemStack stack, Budget budget, boolean includeNbt) {
        Map<String, Object> out = map();
        out.put("registryName", String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())));
        out.put("meta", stack.getItemDamage());
        out.put("displayName", bounded(displayName(stack), 256));
        out.put("amount", stack.stackSize);
        out.put("unit", "item");
        out.put("amountSource", "NEI item stack");
        if (includeNbt && stack.hasTagCompound()) {
            String nbt = stack.getTagCompound()
                .toString();
            if (nbt.length() <= MAX_NBT_LENGTH && budget.allowText(nbt.length())) out.put("nbt", nbt);
            else out.put("nbtOmitted", true);
        }
        if (StackInfo.isFluidDisplayItem(stack)) {
            FluidStack fluid = StackInfo.getFluid(stack);
            if (fluid != null && fluid.getFluid() != null) {
                out.put("kind", "fluid");
                out.put(
                    "fluidName",
                    fluid.getFluid()
                        .getName());
                out.put("amount", fluid.amount);
                out.put("unit", "mB");
                out.put("amountSource", "NEI fluid display data");
                out.put("displayName", bounded(fluid.getLocalizedName(), 256));
            } else {
                out.put("kind", "unresolvedFluidDisplay");
                out.remove("amount");
                out.remove("unit");
                out.remove("amountSource");
            }
        } else {
            out.put("kind", "item");
        }
        return out;
    }

    static String displayName(ItemStack stack) {
        try {
            return stack.getDisplayName();
        } catch (RuntimeException | LinkageError error) {
            return String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        }
    }

    public static Map<String, Object> recipe(IRecipeHandler handler, int index) {
        Map<String, Object> out = map();
        Budget budget = new Budget();
        Object gtRecipe = gregTechRecipe(handler, index);
        boolean gt = gtRecipe != null;
        out.put("handlerId", bounded(handler.getHandlerId(), 256));
        out.put("category", bounded(handler.getRecipeName(), 256));
        out.put("source", "NEI");
        out.put("ingredients", slots(handler.getIngredientStacks(index), "input", gt, budget));
        PositionedStack result = handler.getResultStack(index);
        out.put("result", result == null ? null : slot(result, "output", gt, budget));
        out.put("otherSlots", slots(handler.getOtherStacks(index), gt ? "output" : "unknown", gt, budget));
        out.put("alternativesAreChoices", true);
        out.put("truncated", budget.truncated);
        out.put("conditionsComplete", false);
        if (gt) {
            try {
                out.put("gregtech", gregTechMetadata(handler, gtRecipe));
            } catch (ReflectiveOperationException | RuntimeException error) {
                out.put(
                    "enrichmentWarning",
                    "GT metadata unavailable: " + error.getClass()
                        .getSimpleName());
            }
        }
        return out;
    }

    static Map<String, Object> summary(Map<String, Object> recipe) {
        Map<String, Object> out = map();
        for (String key : new String[] { "recipeId", "epoch", "handlerId", "category", "source", "gregtech",
            "truncated", "conditionsComplete", "enrichmentWarning", "capturedAtEpochMillis" }) {
            if (recipe.containsKey(key)) out.put(key, recipe.get(key));
        }
        out.put("ingredients", summarizeSlots(recipe.get("ingredients"), 4));
        List<Object> outputs = new ArrayList<>();
        if (recipe.get("result") != null) outputs.add(recipe.get("result"));
        Object others = recipe.get("otherSlots");
        if (others instanceof List<?>) outputs.addAll((List<?>) others);
        out.put("resultsAndOtherSlots", summarizeSlots(outputs, 4));
        out.put("detailsTool", "get_recipe");
        return out;
    }

    private static List<Object> summarizeSlots(Object value, int limit) {
        if (!(value instanceof List<?>)) return Collections.emptyList();
        List<Object> out = new ArrayList<>();
        for (Object raw : (List<?>) value) {
            if (out.size() >= limit) break;
            if (!(raw instanceof Map<?, ?>)) continue;
            Map<?, ?> slot = (Map<?, ?>) raw;
            Map<String, Object> summary = map();
            summary.put("role", slot.get("role"));
            summary.put("alternativesTotal", slot.get("alternativesTotal"));
            summary.put("chance", slot.get("chance"));
            summary.put("chanceScale", 10_000);
            Object alternatives = slot.get("alternatives");
            if (alternatives instanceof List<?> && !((List<?>) alternatives).isEmpty()) {
                Map<?, ?> first = (Map<?, ?>) ((List<?>) alternatives).get(0);
                Map<String, Object> sample = map();
                for (String key : new String[] { "kind", "registryName", "meta", "displayName", "fluidName", "amount",
                    "unit" }) {
                    if (first.containsKey(key)) sample.put(key, first.get(key));
                }
                summary.put("example", sample);
            }
            out.add(summary);
        }
        return out;
    }

    private static List<Object> slots(List<PositionedStack> stacks, String role, boolean gt, Budget budget) {
        if (stacks == null) return Collections.emptyList();
        List<Object> out = new ArrayList<>();
        for (PositionedStack stack : stacks) {
            if (stack == null) continue;
            if (budget.slots >= MAX_SLOTS) {
                budget.truncated = true;
                break;
            }
            out.add(slot(stack, role, gt, budget));
        }
        return out;
    }

    private static Map<String, Object> slot(PositionedStack positioned, String role, boolean gt, Budget budget) {
        Map<String, Object> out = map();
        budget.slots++;
        out.put("role", role);
        out.put("x", positioned.relx);
        out.put("y", positioned.rely);
        out.put("chance", positioned.getChance());
        out.put("chanceScale", 10_000);
        out.put(
            "chanceMeaning",
            "input".equals(role) ? "consumed" : "output".equals(role) ? "produced" : "unspecified");
        ItemStack[] choices = positioned.items;
        int total = choices == null ? 0 : choices.length;
        out.put("alternativesTotal", total);
        List<Object> alternatives = new ArrayList<>();
        Integer realAmount = gt ? integerField(positioned, "realStackSize") : null;
        if (choices != null) {
            for (ItemStack choice : choices) {
                if (choice == null || choice.getItem() == null) continue;
                if (alternatives.size() >= MAX_ALTERNATIVES_PER_SLOT
                    || budget.alternatives >= MAX_ALTERNATIVES_PER_RECIPE) break;
                budget.alternatives++;
                Map<String, Object> serialized = item(choice, budget, true);
                if (realAmount != null && "item".equals(serialized.get("kind"))) {
                    serialized.put("amount", realAmount);
                    serialized.put("amountSource", "GregTech realStackSize");
                }
                alternatives.add(serialized);
            }
        }
        out.put("alternatives", alternatives);
        boolean truncated = alternatives.size() < total;
        out.put("alternativesTruncated", truncated);
        budget.truncated |= truncated;
        if (gt && "input".equals(role)) {
            Object amount = alternatives.isEmpty() ? null : ((Map<?, ?>) alternatives.get(0)).get("amount");
            out.put("nonConsumed", amount instanceof Number && ((Number) amount).longValue() == 0);
        }
        try {
            List<String> tooltip = positioned.getTooltip();
            if (tooltip != null) {
                List<String> lines = new ArrayList<>();
                for (String line : tooltip) {
                    if (line == null || lines.size() >= 6) break;
                    String text = bounded(line, 256);
                    if (!budget.allowText(text.length())) break;
                    lines.add(text);
                }
                if (!lines.isEmpty()) out.put("notes", lines);
            }
        } catch (RuntimeException | LinkageError ignored) {
            out.put("notesUnavailable", true);
        }
        return out;
    }

    private static Object gregTechRecipe(IRecipeHandler handler, int index) {
        if (!(handler instanceof TemplateRecipeHandler)) return null;
        Object cached = ((TemplateRecipeHandler) handler).arecipes.get(index);
        if (cached == null || !isGregTechCachedRecipe(cached.getClass())) return null;
        try {
            return cached.getClass()
                .getField("mRecipe")
                .get(cached);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isGregTechCachedRecipe(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("gregtech.nei.GTNEIDefaultHandler$CachedDefaultRecipe".equals(current.getName())) return true;
        }
        return false;
    }

    private static Map<String, Object> gregTechMetadata(IRecipeHandler handler, Object recipe)
        throws ReflectiveOperationException {
        Map<String, Object> out = map();
        for (String[] pair : new String[][] { { "euPerTick", "mEUt" }, { "durationTicks", "mDuration" },
            { "specialValueRaw", "mSpecialValue" }, { "enabled", "mEnabled" }, { "fakeRecipe", "mFakeRecipe" },
            { "nbtSensitive", "isNBTSensitive" } }) {
            out.put(
                pair[0],
                recipe.getClass()
                    .getField(pair[1])
                    .get(recipe));
        }
        Method getMap = handler.getClass()
            .getMethod("getRecipeMap");
        Object recipeMap = getMap.invoke(handler);
        out.put(
            "recipeMapId",
            recipeMap.getClass()
                .getField("unlocalizedName")
                .get(recipeMap));
        out.put(
            "amperage",
            recipeMap.getClass()
                .getMethod("getAmperage")
                .invoke(recipeMap));
        out.put("powerIsBaseRecipeValue", true);
        out.put(
            "specialConditions",
            "Not fully decoded; inspect the in-game NEI recipe for heat, cleanroom and other requirements.");
        return out;
    }

    private static Integer integerField(Object object, String field) {
        try {
            Field found = object.getClass()
                .getField(field);
            return ((Number) found.get(object)).intValue();
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }

    static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static final class Budget {

        int slots;
        int alternatives;
        int text;
        boolean truncated;

        boolean allowText(int length) {
            if (text + length > MAX_RECIPE_TEXT) {
                truncated = true;
                return false;
            }
            text += length;
            return true;
        }
    }
}
