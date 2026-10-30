package io.github.gtnhmcp.nei;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import codechicken.nei.ItemList;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.IUsageHandler;
import codechicken.nei.recipe.StackInfo;

/**
 * NEI 2.8.155-GTNH adapter. Every method must be invoked on the client tick thread.
 * Calls use NEI handlers, never a second GT recipe registry or live server inventory.
 */
public final class NeiService {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long PAGE_BUDGET_NANOS = 250_000_000L;
    private static final int MAX_QUERY_RECIPES = 2000;
    private static final int MAX_HANDLERS_PER_PAGE = 64;
    private final RecipeCache<Map<String, Object>> recipes = new RecipeCache<>(
        256,
        CACHE_TTL_MS,
        () -> System.nanoTime() / 1_000_000L);
    private final RecipeCache<Query> queries = new RecipeCache<>(
        16,
        CACHE_TTL_MS,
        () -> System.nanoTime() / 1_000_000L);
    private Object lastWorld;
    private Object lastItems;
    private int lastReload = Integer.MIN_VALUE;

    public Map<String, Object> call(String toolName, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Collections.emptyMap() : arguments;
        refreshEpoch();
        if ("get_recipe".equals(toolName)) {
            String id = NeiArguments.string(args, "recipeId", null, 128);
            return new LinkedHashMap<>(recipes.get(id));
        }
        ensureReady();
        if ("search_items".equals(toolName)) return searchItems(args);
        if ("find_recipes".equals(toolName)) return find(args, false);
        if ("find_usages".equals(toolName)) return find(args, true);
        throw new IllegalArgumentException("Unknown NEI tool: " + toolName);
    }

    /** Explicit invalidation for disconnect/reload hooks. Existing recipe IDs and cursors become invalid. */
    public void invalidate() {
        recipes.clear();
        queries.clear();
    }

    private void refreshEpoch() {
        Object world = Minecraft.getMinecraft().theWorld;
        Object items = ItemList.items;
        int reload = gregTechReloadVersion();
        if (world != lastWorld || items != lastItems || reload != lastReload) {
            invalidate();
            lastWorld = world;
            lastItems = items;
            lastReload = reload;
        }
    }

    private static void ensureReady() {
        if (!ItemList.loadFinished || ItemList.items == null || ItemList.items.isEmpty()) {
            throw new IllegalStateException(
                "NEI item loading is not complete. Enter a world and retry after NEI has finished loading.");
        }
    }

    private Map<String, Object> searchItems(Map<String, Object> args) {
        String query = NeiArguments.string(args, "query", "", 128)
            .trim()
            .toLowerCase(Locale.ROOT);
        int offset = NeiArguments.integer(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = NeiArguments.integer(args, "limit", 20, 1, 50);
        List<ItemStack> items = ItemList.items;
        long deadline = System.nanoTime() + PAGE_BUDGET_NANOS;
        List<Object> matches = new ArrayList<>();
        int position = Math.min(offset, items.size());
        String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
        while (position < items.size() && matches.size() < limit && System.nanoTime() < deadline) {
            ItemStack stack = items.get(position++);
            if (stack == null || stack.getItem() == null) continue;
            String registry = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
            String text = (registry + " " + NeiSerializer.displayName(stack)).toLowerCase(Locale.ROOT);
            boolean matched = true;
            for (String word : words) {
                if (!text.contains(word)) {
                    matched = false;
                    break;
                }
            }
            if (matched) matches.add(NeiSerializer.item(stack));
        }
        Map<String, Object> out = envelope();
        out.put("items", matches);
        out.put("query", query);
        out.put("offsetKind", "neiItemIndex");
        out.put("nextOffset", position < items.size() ? position : null);
        out.put("hasMore", position < items.size());
        out.put("indexedItems", items.size());
        out.put(
            "note",
            "Continue with nextOffset and the same query. Results are NEI-visible item variants, not inventory contents.");
        return out;
    }

    private Map<String, Object> find(Map<String, Object> args, boolean usages) {
        int limit = NeiArguments.integer(args, "limit", 10, 1, 30);
        String cursor = NeiArguments.string(args, "cursor", "", 128);
        Query query;
        if (!cursor.isEmpty()) {
            try {
                query = queries.get(cursor);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Query cursor expired or unknown. Start a new recipe lookup.");
            }
            if (query.usages != usages) throw new IllegalArgumentException("Cursor belongs to a different lookup tool");
        } else {
            query = new Query(resolveItem(args), usages, handlers(usages));
            cursor = queries.put(query);
        }
        long started = System.nanoTime();
        long deadline = started + PAGE_BUDGET_NANOS;
        int handlersStarted = 0;
        List<Object> page = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        while (page.size() < limit && query.produced < MAX_QUERY_RECIPES && System.nanoTime() < deadline) {
            if (query.active == null) {
                if (query.handlerIndex >= query.handlers.size() || handlersStarted >= MAX_HANDLERS_PER_PAGE) break;
                IRecipeHandler prototype = query.handlers.get(query.handlerIndex++);
                handlersStarted++;
                try {
                    query.active = usages ? ((IUsageHandler) prototype).getUsageHandler("item", query.item.copy())
                        : ((ICraftingHandler) prototype).getRecipeHandler("item", query.item.copy());
                    query.recipeIndex = 0;
                } catch (RuntimeException | LinkageError error) {
                    recordError(errors, prototype, error);
                    query.hadErrors = true;
                    continue;
                }
            }
            if (query.active == null) continue;
            try {
                if (query.recipeIndex >= query.active.numRecipes()) {
                    query.active = null;
                    continue;
                }
                Map<String, Object> snapshot = NeiSerializer.recipe(query.active, query.recipeIndex++);
                String id = recipes.put(snapshot);
                snapshot.put("recipeId", id);
                snapshot.put("epoch", recipes.epoch());
                snapshot.put("cacheTtlSeconds", CACHE_TTL_MS / 1000);
                snapshot.put("capturedAtEpochMillis", System.currentTimeMillis());
                page.add(NeiSerializer.summary(snapshot));
                query.produced++;
            } catch (RuntimeException | LinkageError error) {
                recordError(errors, query.active, error);
                query.hadErrors = true;
                query.active = null;
            }
        }
        boolean capped = query.produced >= MAX_QUERY_RECIPES;
        boolean more = !capped && (query.active != null || query.handlerIndex < query.handlers.size());
        Map<String, Object> out = envelope();
        out.put("recipes", page);
        out.put("queryItem", NeiSerializer.item(query.item));
        out.put("lookup", usages ? "ingredientUsages" : "producesItem");
        out.put(
            "matchingSemantics",
            "Native NEI handler matching; may include ore-equivalent items, GT familiar prefixes (e.g. ingot/nugget), "
                + "and associated fluid/container forms. The query item is a lookup seed; inspect actual recipe inputs "
                + "and outputs before assuming it is directly consumed or produced.");
        out.put("cursor", more ? cursor : null);
        out.put("hasMore", more);
        out.put("partial", more || capped || query.hadErrors);
        out.put("resultCapReached", capped);
        out.put("handlersVisited", query.handlerIndex);
        out.put("handlersTotal", query.handlers.size());
        out.put("errors", errors);
        out.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
        out.put("budgetMillis", PAGE_BUDGET_NANOS / 1_000_000);
        out.put("budgetIsCooperative", true);
        return out;
    }

    private static List<IRecipeHandler> handlers(boolean usages) {
        List<IRecipeHandler> all = new ArrayList<>();
        if (usages) {
            synchronized (GuiUsageRecipe.serialUsageHandlers) {
                all.addAll(GuiUsageRecipe.serialUsageHandlers);
            }
            synchronized (GuiUsageRecipe.usagehandlers) {
                all.addAll(GuiUsageRecipe.usagehandlers);
            }
        } else {
            synchronized (GuiCraftingRecipe.serialCraftingHandlers) {
                all.addAll(GuiCraftingRecipe.serialCraftingHandlers);
            }
            synchronized (GuiCraftingRecipe.craftinghandlers) {
                all.addAll(GuiCraftingRecipe.craftinghandlers);
            }
        }
        if (all.isEmpty()) throw new IllegalStateException("NEI recipe handlers are not registered yet");
        List<IRecipeHandler> unique = new ArrayList<>();
        // GT registers a distinct prototype per recipe map, sharing the same handler ID.
        // Only the same object appearing in both NEI registries is a duplicate.
        for (IRecipeHandler handler : IdentityDistinct.of(all)) {
            String id = handler.getHandlerId();
            if (NEIClientConfig.hiddenHandlers.contains(id)
                || NEIClientConfig.hiddenHandlers.contains(handler.getOverlayIdentifier())) continue;
            unique.add(handler);
        }
        return unique;
    }

    private static ItemStack resolveItem(Map<String, Object> args) {
        String registryName = NeiArguments.string(args, "registryName", null, 256);
        int meta = NeiArguments.integer(args, "meta", 0, 0, 32767);
        Object registered = Item.itemRegistry.getObject(registryName);
        if (!(registered instanceof Item))
            throw new IllegalArgumentException("Unknown item registryName: " + registryName);
        ItemStack stack = new ItemStack((Item) registered, 1, meta);
        String nbt = NeiArguments.string(args, "nbt", "", 8192);
        if (!nbt.isEmpty()) {
            NeiArguments.validateNbtDepth(nbt);
            try {
                NBTBase parsed = JsonToNBT.func_150315_a(nbt);
                if (!(parsed instanceof NBTTagCompound))
                    throw new IllegalArgumentException("nbt must be an SNBT compound");
                stack.setTagCompound((NBTTagCompound) parsed);
            } catch (Exception error) {
                throw new IllegalArgumentException(
                    "Invalid item SNBT compound: " + NeiSerializer.bounded(error.getMessage(), 160));
            }
        }
        return StackInfo.normalizeRecipeQueryStack(stack);
    }

    private Map<String, Object> envelope() {
        Map<String, Object> out = NeiSerializer.map();
        out.put("epoch", recipes.epoch());
        out.put("source", "NEI client recipe data");
        return out;
    }

    private static void recordError(List<String> errors, IRecipeHandler handler, Throwable error) {
        if (errors.size() >= 8) return;
        errors.add(
            NeiSerializer.bounded(
                handler.getClass()
                    .getName() + ": "
                    + error.getClass()
                        .getSimpleName()
                    + " "
                    + error.getMessage(),
                256));
    }

    private static int gregTechReloadVersion() {
        try {
            Class<?> mod = Class.forName("gregtech.GTMod", false, NeiService.class.getClassLoader());
            Object proxy = mod.getField("proxy")
                .get(null);
            if (proxy == null) return 0;
            Method count = proxy.getClass()
                .getMethod("getNEIReloadCount");
            return ((Number) count.invoke(proxy)).intValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static final class Query {

        final ItemStack item;
        final boolean usages;
        final List<IRecipeHandler> handlers;
        int handlerIndex;
        int recipeIndex;
        int produced;
        boolean hadErrors;
        IRecipeHandler active;

        Query(ItemStack item, boolean usages, List<IRecipeHandler> handlers) {
            this.item = item;
            this.usages = usages;
            this.handlers = handlers;
        }
    }
}
