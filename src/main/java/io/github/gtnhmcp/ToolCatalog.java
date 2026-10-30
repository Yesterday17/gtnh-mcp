package io.github.gtnhmcp;

import static io.github.gtnhmcp.game.Values.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.gtnhmcp.protocol.ToolDefinition;

public final class ToolCatalog {

    private ToolCatalog() {}

    public static List<ToolDefinition> tools() {
        List<ToolDefinition> result = new ArrayList<>();
        result.add(
            tool(
                "get_context",
                "Read current game session, player, dimension and available capabilities. Read-only.",
                map()));
        result.add(
            tool(
                "search_items",
                "Search NEI's loaded item list by registry ID or localized display name. Read-only; paginated.",
                map(
                    "query",
                    text("Name or registry ID substring"),
                    "offset",
                    number(0, Integer.MAX_VALUE),
                    "limit",
                    number(1, 50))));
        Map<String, Object> query = map(
            "registryName",
            text("Exact registry ID returned by search_items, e.g. minecraft:iron_ingot"),
            "meta",
            number(0, 32767),
            "nbt",
            text("Optional SNBT compound; retain the item identity returned by search_items"),
            "limit",
            number(1, 30),
            "cursor",
            text("Continue a previous query; omit item fields when using this cursor"));
        result.add(
            tool(
                "find_recipes",
                "Query NEI crafting recipes using an item as the lookup seed. Native handlers may expand equivalent items, GT familiar prefixes (ingot/nugget), or fluid forms; inspect actual outputs. Supply registryName or a continuation cursor. Display recipes may not be executable.",
                query));
        result.add(
            tool(
                "find_usages",
                "Query NEI ingredient usages using an item as the lookup seed. Native handlers may expand equivalent items, GT familiar prefixes, or fluid forms; inspect actual inputs. Supply registryName or a continuation cursor. Ignores keyboard state and machine-catalyst expansion.",
                query));
        result.add(
            new ToolDefinition(
                "get_recipe",
                "Get a cached NEI recipe in detail. IDs expire on world changes, reload, or eviction; requery if expired.",
                schema(map("recipeId", text("Recipe ID returned by find_recipes/find_usages")), "recipeId")));
        Map<String, Object> position = map(
            "x",
            number(-30000000, 30000000),
            "y",
            number(0, 255),
            "z",
            number(-30000000, 30000000));
        Map<String, Object> inventory = map(
            "scope",
            map("type", "string", "enum", Arrays.asList("player", "container")),
            "x",
            position.get("x"),
            "y",
            position.get("y"),
            "z",
            position.get("z"));
        result.add(
            tool(
                "get_inventory",
                "Read player inventory (default) or one loaded nearby container at x/y/z. Container access requires singleplayer. Reports only this scope, not all base storage.",
                inventory));
        result.add(
            tool(
                "inspect_target",
                "Read the block under the player's crosshair. Singleplayer includes authoritative tile state; multiplayer only client-visible block information.",
                map()));
        result.add(
            new ToolDefinition(
                "inspect_machine",
                "Read an already-loaded nearby tile at x/y/z in the player's dimension. Includes available GT state without triggering structure checks. Singleplayer only.",
                schema(position, "x", "y", "z")));
        result.add(
            tool(
                "find_machines",
                "List loaded tile entities within a bounded radius of the player. Results may include containers and other non-machine tiles. Singleplayer only; does not load chunks.",
                map("radius", number(1, 32), "limit", number(1, 100))));
        result.addAll(ContextToolCatalog.tools());
        return result;
    }

    private static ToolDefinition tool(String name, String description, Map<String, Object> properties) {
        return new ToolDefinition(name, description, schema(properties));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return map(
            "type",
            "object",
            "properties",
            properties,
            "required",
            Arrays.asList(required),
            "additionalProperties",
            false);
    }

    private static Map<String, Object> text(String description) {
        return map("type", "string", "description", description, "maxLength", 16384);
    }

    private static Map<String, Object> number(int min, int max) {
        return map("type", "integer", "minimum", min, "maximum", max);
    }
}
