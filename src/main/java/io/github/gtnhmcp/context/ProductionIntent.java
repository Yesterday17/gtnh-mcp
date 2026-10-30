package io.github.gtnhmcp.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Derives production targets from detached snapshots without treating a reference recipe as a required route. */
public final class ProductionIntent {

    private final boolean recipe;
    private final List<Map<String, Object>> candidates;
    private final boolean candidatesComplete;

    private ProductionIntent(boolean recipe, List<Map<String, Object>> candidates, boolean candidatesComplete) {
        this.recipe = recipe;
        this.candidates = List.copyOf(candidates);
        this.candidatesComplete = candidatesComplete;
    }

    public static ProductionIntent fromAttachment(Map<String, Object> attachment) {
        boolean recipe = attachment != null && "recipe".equals(attachment.get("type"));
        Map<?, ?> data = attachment != null && attachment.get("data") instanceof Map<?, ?>
            ? (Map<?, ?>) attachment.get("data")
            : Map.of();
        LinkedHashMap<Map<String, Object>, Candidate> found = new LinkedHashMap<>();
        boolean complete = !Boolean.TRUE.equals(data.get("truncated"));
        if (!recipe) {
            Candidate item = product(data);
            if (item != null) {
                item.alternatives.add(item.identity);
                item.alternativesComplete = item.complete;
                found.put(item.identity, item);
                complete &= item.complete;
            } else complete = false;
        } else {
            List<Map<?, ?>> slots = new ArrayList<>();
            if (data.get("result") instanceof Map<?, ?>) slots.add((Map<?, ?>) data.get("result"));
            if (data.get("otherSlots") instanceof List<?>) {
                for (Object slot : (List<?>) data.get("otherSlots")) {
                    if (slot instanceof Map<?, ?>) slots.add((Map<?, ?>) slot);
                    else complete = false;
                }
            }
            for (Map<?, ?> slot : slots) {
                if (!"output".equals(slot.get("role"))) {
                    // Other NEI slots can contain catalysts, returned containers or unclassified displays.
                    complete = false;
                    continue;
                }
                if (!(slot.get("alternatives") instanceof List<?>)) {
                    complete = false;
                    continue;
                }
                List<?> raw = (List<?>) slot.get("alternatives");
                List<Candidate> choices = new ArrayList<>();
                boolean slotComplete = !Boolean.TRUE.equals(slot.get("alternativesTruncated"));
                Object total = slot.get("alternativesTotal");
                if (total instanceof Number && ((Number) total).intValue() > raw.size()) slotComplete = false;
                for (Object value : raw) {
                    Candidate choice = value instanceof Map<?, ?> ? product((Map<?, ?>) value) : null;
                    if (choice == null) slotComplete = false;
                    else {
                        choices.add(choice);
                        slotComplete &= choice.complete;
                    }
                }
                if (choices.isEmpty()) slotComplete = false;
                for (Candidate choice : choices) {
                    Candidate target = found.computeIfAbsent(choice.identity, ignored -> choice);
                    target.complete &= choice.complete;
                    target.alternativesComplete &= slotComplete;
                    for (Candidate alternative : choices) target.alternatives.add(alternative.identity);
                }
                complete &= slotComplete;
            }
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Candidate candidate : found.values()) candidates.add(candidate.snapshot());
        return new ProductionIntent(recipe, candidates, complete && !candidates.isEmpty());
    }

    public List<Map<String, Object>> candidates() {
        return candidates;
    }

    public boolean isRecipe() {
        return recipe;
    }

    /** A byproduct or one rotating alternative is never silently preferred over the other known outputs. */
    public int initialTargetIndex() {
        return candidatesComplete && candidates.size() == 1 ? 0 : -1;
    }

    public Map<String, Object> snapshot(int targetIndex, boolean userSelected, boolean useAttachedRecipe) {
        if (targetIndex < -1 || targetIndex >= candidates.size()) throw new IllegalArgumentException("Unknown product");
        Map<String, Object> selected = targetIndex < 0 ? null : candidates.get(targetIndex);
        boolean constrained = recipe && useAttachedRecipe;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("kind", "plan_production");
        result.put("routePolicy", constrained ? "use_attached_recipe" : "explore_routes");
        result.put("recipeRole", recipe ? constrained ? "constraint" : "reference" : "none");
        result.put(
            "targetSelection",
            selected == null ? "unspecified" : userSelected ? "user_selected" : "automatic_single_product");
        result.put("targetProduct", selected);
        result.put("candidateProducts", candidates);
        result.put("candidatesComplete", candidatesComplete);
        result.put(
            "needsTargetClarification",
            selected == null || !Boolean.TRUE.equals(selected.get("identityComplete")));
        // Quantities and chances belong to the reference recipe; they are not a requested production rate.
        result.put("productionRate", null);
        return Collections.unmodifiableMap(result);
    }

    private static Candidate product(Map<?, ?> source) {
        Map<String, Object> identity = new LinkedHashMap<>();
        boolean complete = !Boolean.TRUE.equals(source.get("nbtOmitted"));
        String kind = Objects.toString(source.get("kind"), "item");
        if ("fluid".equals(kind)) {
            if (!(source.get("fluidName") instanceof String) || ((String) source.get("fluidName")).isBlank())
                return null;
            identity.put("kind", "fluid");
            identity.put("fluidName", source.get("fluidName"));
            // NEI display-item NBT contains display quantity; it is not a fluid's intrinsic identity.
        } else if ("item".equals(kind)) {
            if (!(source.get("registryName") instanceof String) || ((String) source.get("registryName")).isBlank())
                return null;
            if (!(source.get("meta") instanceof Number)) return null;
            identity.put("kind", "item");
            identity.put("registryName", source.get("registryName"));
            identity.put("meta", ((Number) source.get("meta")).intValue());
            if (source.get("nbt") instanceof String) identity.put("nbt", source.get("nbt"));
        } else return null;
        String fallback = Objects
            .toString(identity.get("fluidName"), Objects.toString(identity.get("registryName"), ""));
        return new Candidate(
            Collections.unmodifiableMap(identity),
            Objects.toString(source.get("displayName"), fallback),
            complete);
    }

    private static final class Candidate {

        private final Map<String, Object> identity;
        private final String displayName;
        private final LinkedHashSet<Map<String, Object>> alternatives = new LinkedHashSet<>();
        private boolean complete;
        private boolean alternativesComplete = true;

        Candidate(Map<String, Object> identity, String displayName, boolean complete) {
            this.identity = identity;
            this.displayName = displayName;
            this.complete = complete;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("identity", identity);
            result.put("displayName", displayName);
            result.put("identityComplete", complete);
            result.put("outputAlternatives", List.copyOf(alternatives));
            result.put("outputAlternativesAreChoices", true);
            result.put("outputAlternativesComplete", alternativesComplete);
            return Collections.unmodifiableMap(result);
        }
    }
}
