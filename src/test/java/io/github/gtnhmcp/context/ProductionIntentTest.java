package io.github.gtnhmcp.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProductionIntentTest {

    @Test
    void itemSharingMeansProducingThatItemWithoutInferringAQuantity() {
        Map<String, Object> item = item("iron", 64);
        item.put("nbt", "{grade:1}");
        ProductionIntent intent = ProductionIntent.fromAttachment(Map.of("type", "item", "data", item));
        Map<String, Object> snapshot = intent.snapshot(intent.initialTargetIndex(), false, false);
        assertEquals("plan_production", snapshot.get("kind"));
        assertEquals("explore_routes", snapshot.get("routePolicy"));
        assertEquals("none", snapshot.get("recipeRole"));
        assertEquals("automatic_single_product", snapshot.get("targetSelection"));
        assertEquals(
            Map.of("kind", "item", "registryName", "test:iron", "meta", 0, "nbt", "{grade:1}"),
            target(snapshot).get("identity"));
        assertFalse(((Map<?, ?>) target(snapshot).get("identity")).containsKey("amount"));
        assertNull(snapshot.get("productionRate"));
    }

    @Test
    void multipleOutputsRequireAnExplicitTargetAndDoNotConstrainTheRoute() {
        ProductionIntent intent = recipe(
            List.of(slot("output", item("dust", 1)), slot("output", item("byproduct", 2))));
        assertEquals(-1, intent.initialTargetIndex());
        Map<String, Object> unspecified = intent.snapshot(-1, false, false);
        assertNull(unspecified.get("targetProduct"));
        assertEquals("unspecified", unspecified.get("targetSelection"));
        assertEquals("reference", unspecified.get("recipeRole"));
        assertEquals("explore_routes", unspecified.get("routePolicy"));
        Map<String, Object> chosen = intent.snapshot(1, true, false);
        assertEquals("byproduct", target(chosen).get("displayName"));
        assertEquals("user_selected", chosen.get("targetSelection"));
        assertEquals(
            "use_attached_recipe",
            intent.snapshot(1, true, true)
                .get("routePolicy"));
        assertEquals(
            "constraint",
            intent.snapshot(1, true, true)
                .get("recipeRole"));
    }

    @Test
    void unknownSlotsAndInputsAreNeverOfferedAsProducts() {
        ProductionIntent intent = recipe(List.of(slot("unknown", item("mold", 0)), slot("input", item("ore", 1))));
        assertTrue(
            intent.candidates()
                .isEmpty());
        assertEquals(-1, intent.initialTargetIndex());
        assertEquals(
            false,
            intent.snapshot(-1, false, false)
                .get("candidatesComplete"));
        assertEquals(
            true,
            intent.snapshot(-1, false, false)
                .get("needsTargetClarification"));
    }

    @Test
    void rotatingOutputAlternativesDoNotSilentlySelectTheFirstVariant() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("role", "output");
        output.put("alternatives", List.of(item("oak", 4), item("birch", 4)));
        ProductionIntent intent = recipe(List.of(output));
        assertEquals(
            2,
            intent.candidates()
                .size());
        assertEquals(-1, intent.initialTargetIndex());
        Map<?, ?> selected = target(intent.snapshot(1, true, false));
        assertEquals("birch", selected.get("displayName"));
        assertEquals(2, ((List<?>) selected.get("outputAlternatives")).size());
        assertEquals(true, selected.get("outputAlternativesAreChoices"));
    }

    @Test
    void duplicateProductsCollapseEvenWhenOutputAmountsDiffer() {
        ProductionIntent intent = recipe(List.of(slot("output", item("iron", 1)), slot("output", item("iron", 4))));
        assertEquals(
            1,
            intent.candidates()
                .size());
        assertEquals(0, intent.initialTargetIndex());
    }

    @Test
    void fluidsUseFluidIdentityInsteadOfDisplayItemQuantityOrNbt() {
        Map<String, Object> first = new LinkedHashMap<>(
            Map.of(
                "kind",
                "fluid",
                "fluidName",
                "oil",
                "displayName",
                "Oil",
                "amount",
                100,
                "registryName",
                "nei:fluid",
                "meta",
                0,
                "nbt",
                "{amount:100}"));
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("amount", 500);
        second.put("nbt", "{amount:500}");
        ProductionIntent intent = recipe(List.of(slot("output", first), slot("output", second)));
        assertEquals(
            1,
            intent.candidates()
                .size());
        assertEquals(
            Map.of("kind", "fluid", "fluidName", "oil"),
            target(intent.snapshot(0, false, false)).get("identity"));
    }

    @Test
    void incompleteAlternativesAndOmittedIdentityPreventAutomaticSelection() {
        Map<String, Object> truncated = slot("output", item("iron", 1));
        truncated.put("alternativesTruncated", true);
        ProductionIntent intent = recipe(List.of(truncated));
        assertEquals(-1, intent.initialTargetIndex());
        assertEquals(
            false,
            intent.candidates()
                .get(0)
                .get("outputAlternativesComplete"));
        Map<String, Object> opaque = item("cell", 1);
        opaque.put("nbtOmitted", true);
        ProductionIntent incomplete = ProductionIntent.fromAttachment(Map.of("type", "item", "data", opaque));
        assertEquals(-1, incomplete.initialTargetIndex());
        assertEquals(
            true,
            incomplete.snapshot(0, true, false)
                .get("needsTargetClarification"));
        assertTrue(
            recipe(List.of()).candidates()
                .isEmpty());
    }

    private static ProductionIntent recipe(List<Map<String, Object>> outputs) {
        return ProductionIntent.fromAttachment(Map.of("type", "recipe", "data", Map.of("otherSlots", outputs)));
    }

    private static Map<String, Object> slot(String role, Map<String, Object> item) {
        return new LinkedHashMap<>(Map.of("role", role, "alternatives", List.of(item)));
    }

    private static Map<String, Object> item(String name, int amount) {
        return new LinkedHashMap<>(
            Map.of("kind", "item", "registryName", "test:" + name, "meta", 0, "displayName", name, "amount", amount));
    }

    private static Map<?, ?> target(Map<String, Object> snapshot) {
        return (Map<?, ?>) snapshot.get("targetProduct");
    }
}
