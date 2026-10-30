package io.github.gtnhmcp.acceptance;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import com.google.gson.GsonBuilder;

import codechicken.nei.Widget;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiRecipeButton;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.widget.RecipeWidget;
import codechicken.nei.scroll.ScrollContainer;
import io.github.gtnhmcp.context.ContextStore;
import io.github.gtnhmcp.context.ProductionIntent;
import io.github.gtnhmcp.game.GameBridge;
import io.github.gtnhmcp.nei.NeiSerializer;
import io.github.gtnhmcp.protocol.ResourceProvider;

/**
 * Application-internal integration checks against the production GUI and a real RC2 NEI screen. This fixture does not
 * simulate operating-system input or claim visual/manual acceptance. It is excluded from the production source set.
 */
public final class ContextUiAcceptance {

    private static final String COMPOSER = "io.github.gtnhmcp.ui.ShareMessageScreen";
    private final Path directory;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private String phase = "idle";
    private String status = "idle";
    private String error;
    private int phaseTicks;
    private ContextStore store;
    private String worldId;
    private long sequence;
    private Map<String, Object> expectedRecipe;
    private Map<String, Object> expectedIntent;
    private GuiRecipe<?> recipeScreen;
    private String text;
    private String recipeText;

    public ContextUiAcceptance(Path directory) {
        this.directory = directory;
    }

    public void start() {
        if ("running".equals(status)) return;
        checks.clear();
        messages.clear();
        error = null;
        status = "running";
        text = "UI acceptance text request " + System.currentTimeMillis();
        recipeText = "Use LV machines only. Reuse existing lines; target 12 units per minute.";
        try {
            Minecraft mc = Minecraft.getMinecraft();
            require(
                mc.thePlayer != null && mc.theWorld != null && mc.isIntegratedServerRunning(),
                "A loaded singleplayer fixture world is required");
            enterCommand(text);
            transition("wait_text_composer");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /** Call once per client END tick. All game/GUI accesses stay on that thread. */
    public void tick() {
        if (!"running".equals(status)) return;
        try {
            if (++phaseTicks > 600) throw new IllegalStateException("Timed out in " + phase);
            Minecraft mc = Minecraft.getMinecraft();
            switch (phase) {
                case "wait_text_composer":
                    if (!isComposer(mc.currentScreen)) return;
                    inspectTextComposer(mc.currentScreen);
                    transition("verify_text_submit");
                    break;
                case "verify_text_submit":
                    verifyTextSubmit();
                    enterCommand("This draft must be cancelled");
                    transition("wait_cancel_composer");
                    break;
                case "wait_cancel_composer":
                    if (!isComposer(mc.currentScreen)) return;
                    key(mc.currentScreen, '\0', Keyboard.KEY_ESCAPE);
                    check("cancel_returns_to_game", mc.currentScreen == null);
                    check("cancel_does_not_publish", latestSequence() == sequence);
                    GuiUsageRecipe.openRecipeGui("item", new ItemStack(Blocks.soul_sand));
                    transition("wait_nei");
                    break;
                case "wait_nei":
                    if (!(mc.currentScreen instanceof GuiRecipe<?>)) return;
                    selectCentrifuge((GuiRecipe<?>) mc.currentScreen);
                    transition("click_recipe_button");
                    break;
                case "click_recipe_button":
                    clickExactRecipeButton();
                    transition("wait_recipe_composer");
                    break;
                case "wait_recipe_composer":
                    if (!isComposer(mc.currentScreen)) return;
                    inspectRecipeComposer(mc.currentScreen);
                    transition("verify_recipe_submit");
                    break;
                case "verify_recipe_submit":
                    verifyRecipeSubmit();
                    mc.displayGuiScreen(null);
                    phase = "complete";
                    status = "passed";
                    writeResult();
                    break;
                default:
                    throw new IllegalStateException("Unknown UI acceptance phase");
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void enterCommand(String request) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        GuiChat chat = new GuiChat("/gtnhai " + request);
        mc.displayGuiScreen(chat);
        key(chat, '\r', Keyboard.KEY_RETURN);
        // This checks the real vanilla command path, including its close-after-send behavior.
        check("vanilla_chat_closes_after_local_command", mc.currentScreen == null);
    }

    private void inspectTextComposer(GuiScreen screen) throws Exception {
        check(
            "text_command_opens_production_composer",
            COMPOSER.equals(
                screen.getClass()
                    .getName()));
        GameBridge bridge = (GameBridge) field(screen, "bridge");
        store = (ContextStore) field(bridge, "contextStore");
        worldId = store.activeWorldId();
        require(worldId != null, "No persistent world identity in the production bridge");
        sequence = latestSequence();
        check("text_only_has_no_attachment", field(screen, "attachment") == null);
        GuiTextField input = (GuiTextField) field(screen, "input");
        check("command_text_reaches_editable_input", text.equals(input.getText()));
        text += "; prefer existing machines";
        input.setText(text);
        key(screen, '\r', Keyboard.KEY_RETURN);
        check("text_submit_closes_composer", Minecraft.getMinecraft().currentScreen == null);
    }

    private void verifyTextSubmit() throws Exception {
        Map<String, Object> event = onlyNewMessage();
        check("text_submit_publishes_exact_edited_text", text.equals(event.get("userText")));
        check("persisted_text_attachment_is_null", event.get("attachment") == null);
        Map<?, ?> context = (Map<?, ?>) event.get("context");
        check(
            "text_message_has_captured_world_context",
            worldId.equals(context.get("worldId")) && context.get("worldSessionId") instanceof String
                && context.get("player") instanceof Map<?, ?>);
        rememberMessage(event, "text");
    }

    private void selectCentrifuge(GuiRecipe<?> gui) throws Exception {
        recipeScreen = gui;
        for (int i = 0; i < gui.currenthandlers.size(); i++) {
            IRecipeHandler handler = gui.currenthandlers.get(i);
            String mapId = recipeMap(handler);
            if (mapId != null && mapId.toLowerCase(java.util.Locale.ROOT)
                .contains("centrifuge")) {
                Method method = GuiRecipe.class.getDeclaredMethod("setRecipePage", int.class);
                method.setAccessible(true);
                method.invoke(gui, i);
                gui.forceRefreshPage();
                check("real_nei_centrifuge_handler_selected", true);
                return;
            }
        }
        throw new IllegalStateException("The RC2 soul-sand usage screen has no centrifuge handler");
    }

    private void clickExactRecipeButton() throws Exception {
        require(Minecraft.getMinecraft().currentScreen == recipeScreen, "NEI screen changed before recipe capture");
        ScrollContainer container = (ScrollContainer) field(recipeScreen, "container");
        for (Widget widget : container.getWidgets()) {
            if (!(widget instanceof RecipeWidget)) continue;
            RecipeWidget recipe = (RecipeWidget) widget;
            RecipeHandlerRef ref = recipe.getRecipeHandlerRef();
            if (recipeMap(ref.handler) == null) continue;
            for (GuiRecipeButton button : recipe.getRecipeButtons()) {
                if (!button.getClass()
                    .getName()
                    .equals("io.github.gtnhmcp.ui.ContextSharingUi$RecipeShareButton")) continue;
                expectedRecipe = NeiSerializer.recipe(ref.handler, ref.recipeIndex);
                check("nei_event_inserts_production_ai_button", true);
                int x = recipe.x + button.xPosition + button.width / 2;
                int y = recipe.y + button.yPosition + button.height / 2;
                check("real_recipe_widget_accepts_ai_button_click", recipe.handleClick(x, y, 0));
                check("recipe_click_opens_production_composer", isComposer(Minecraft.getMinecraft().currentScreen));
                return;
            }
        }
        throw new IllegalStateException("No production AI recipe button exists in the current NEI page");
    }

    @SuppressWarnings("unchecked")
    private void inspectRecipeComposer(GuiScreen screen) throws Exception {
        Map<String, Object> attachment = (Map<String, Object>) field(screen, "attachment");
        check(
            "recipe_composer_captures_exact_handler_and_index",
            "recipe".equals(attachment.get("type")) && expectedRecipe.equals(attachment.get("data")));
        check(
            "recipe_attachment_title_names_category",
            ((String) attachment.get("title")).contains(String.valueOf(expectedRecipe.get("category"))));
        Map<?, ?> gt = (Map<?, ?>) expectedRecipe.get("gregtech");
        check(
            "captured_recipe_includes_gt_machine_parameters",
            gt != null && gt.containsKey("recipeMapId")
                && gt.containsKey("euPerTick")
                && gt.containsKey("durationTicks"));
        GuiTextField input = (GuiTextField) field(screen, "input");
        check(
            "recipe_requirements_field_contains_no_generated_goal_or_policy",
            input.getText()
                .trim()
                .isEmpty());
        ProductionIntent intent = (ProductionIntent) field(screen, "productionIntent");
        check(
            "multiple_outputs_do_not_silently_select_a_product",
            intent.candidates()
                .size() > 1 && ((Integer) field(screen, "selectedTargetIndex")) == -1);
        check("production_routes_are_explored_by_default", Boolean.FALSE.equals(field(screen, "useAttachedRecipe")));
        action(screen, 2);
        check("target_can_be_selected_explicitly", ((Integer) field(screen, "selectedTargetIndex")) == 0);
        input.setText(recipeText);
        action(screen, 2);
        action(screen, 3);
        check("using_the_attached_recipe_is_explicit_opt_in", Boolean.TRUE.equals(field(screen, "useAttachedRecipe")));
        check(
            "changing_target_and_route_keeps_only_authored_requirements",
            ((Integer) field(screen, "selectedTargetIndex")) == 1 && recipeText.equals(input.getText()));
        action(screen, 3);
        screen.initGui();
        check(
            "resize_preserves_target_mode_and_user_text",
            ((Integer) field(screen, "selectedTargetIndex")) == 1
                && Boolean.FALSE.equals(field(screen, "useAttachedRecipe"))
                && recipeText.equals(((GuiTextField) field(screen, "input")).getText()));
        expectedIntent = intent.snapshot(1, true, false);
        key(screen, '\r', Keyboard.KEY_RETURN);
        check("recipe_submit_returns_to_original_nei_screen", Minecraft.getMinecraft().currentScreen == recipeScreen);
    }

    private void verifyRecipeSubmit() throws Exception {
        Map<String, Object> event = onlyNewMessage();
        Map<?, ?> attachment = (Map<?, ?>) event.get("attachment");
        check("persisted_recipe_snapshot_matches_clicked_recipe", expectedRecipe.equals(attachment.get("data")));
        check(
            "persisted_intent_targets_product_and_keeps_recipe_as_reference",
            expectedIntent.equals(attachment.get("intent")));
        check("recipe_message_includes_extra_requirements", recipeText.equals(event.get("userText")));
        rememberMessage(event, "recipe");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyNewMessage() throws Exception {
        Map<String, Object> page = store.messages(worldId, sequence, 20);
        List<?> events = (List<?>) page.get("messages");
        require(events.size() == 1, "A GUI send must produce exactly one context message");
        Map<String, Object> event = (Map<String, Object>) events.get(0);
        sequence = ((Number) event.get("sequence")).longValue();
        return event;
    }

    private long latestSequence() throws Exception {
        return ((Number) store.readResource(ResourceProvider.MESSAGES_URI)
            .get("latestSeq")).longValue();
    }

    private void rememberMessage(Map<String, Object> event, String kind) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", kind);
        summary.put("id", event.get("id"));
        summary.put("sequence", event.get("sequence"));
        if ("recipe".equals(kind)) {
            summary.put("recipe", expectedRecipe);
            summary.put("intent", expectedIntent);
        }
        messages.add(summary);
    }

    private static String recipeMap(IRecipeHandler handler) throws Exception {
        try {
            Object map = handler.getClass()
                .getMethod("getRecipeMap")
                .invoke(handler);
            return String.valueOf(
                map.getClass()
                    .getField("unlocalizedName")
                    .get(map));
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static boolean isComposer(GuiScreen screen) {
        return screen != null && COMPOSER.equals(
            screen.getClass()
                .getName());
    }

    private static void action(GuiScreen screen, int id) throws Exception {
        Object buttons;
        try {
            buttons = field(screen, "buttonList");
        } catch (NoSuchFieldException absent) {
            buttons = field(screen, "field_146292_n");
        }
        for (Object raw : (List<?>) buttons) {
            GuiButton button = (GuiButton) raw;
            if (button.id != id) continue;
            for (String name : new String[] { "actionPerformed", "func_146284_a" }) {
                try {
                    Method method = screen.getClass()
                        .getDeclaredMethod(name, GuiButton.class);
                    method.setAccessible(true);
                    method.invoke(screen, button);
                    return;
                } catch (NoSuchMethodException absent) {
                    // Production override has MCP names in dev and SRG names in the game.
                }
            }
        }
        throw new IllegalStateException("The requested composer control does not exist");
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                // Test-only reflection handles production-private state without adding a public testing API.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void key(GuiScreen screen, char character, int keyCode) throws Exception {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : new String[] { "keyTyped", "func_73869_a" }) {
                try {
                    Method method = type.getDeclaredMethod(name, char.class, int.class);
                    method.setAccessible(true);
                    method.invoke(screen, character, keyCode);
                    return;
                } catch (NoSuchMethodException absent) {
                    // MCP names in the development environment, SRG names in a normal Forge game.
                }
            }
        }
        throw new NoSuchMethodException("GUI key event");
    }

    private void check(String name, boolean success) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("check", name);
        entry.put("passed", success);
        checks.add(entry);
        require(success, name);
    }

    private static void require(boolean success, String description) {
        if (!success) throw new IllegalStateException(description);
    }

    private void transition(String next) throws IOException {
        phase = next;
        phaseTicks = 0;
        writeResult();
    }

    private void fail(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        status = "failed";
        error = failure.getClass()
            .getSimpleName() + ": "
            + String.valueOf(failure.getMessage());
        error = error.replace(
            directory.toAbsolutePath()
                .toString(),
            "<acceptance-directory>");
        try {
            writeResult();
        } catch (IOException ignored) {}
    }

    private void writeResult() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("phase", phase);
        result.put("method", "application_internal_integration; no OS input or visual/manual assertion");
        result.put("itemHover", "not_verified_by_this_fixture");
        result.put("checks", checks);
        result.put("messages", messages);
        if (error != null) result.put("error", error);
        Path pending = directory.resolve("context-ui-result.json.tmp");
        Files.write(
            pending,
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(result)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(
            pending,
            directory.resolve("context-ui-result.json"),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }
}
