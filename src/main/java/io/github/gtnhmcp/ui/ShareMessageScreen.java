package io.github.gtnhmcp.ui;

import static io.github.gtnhmcp.ui.ContextSharingUi.tr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import org.lwjgl.input.Keyboard;

import io.github.gtnhmcp.context.ProductionIntent;
import io.github.gtnhmcp.game.GameBridge;

/** A local message composer; vanilla chat and the remote game server never receive its text. */
final class ShareMessageScreen extends GuiScreen {

    private final GameBridge bridge;
    private final Map<String, Object> attachment;
    private final Map<String, Object> capturedContext;
    private final GuiScreen parent;
    private final World originalWorld;
    private final ProductionIntent productionIntent;
    private int selectedTargetIndex;
    private boolean targetChosenByUser;
    private boolean useAttachedRecipe;
    private GuiTextField input;
    private String draft;
    private String error;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int inputOffset;
    private int panelHeight;
    private boolean submitted;

    ShareMessageScreen(GameBridge bridge, Map<String, Object> attachment, Map<String, Object> capturedContext,
        String text, GuiScreen parent) {
        this.bridge = bridge;
        this.attachment = attachment;
        this.capturedContext = capturedContext;
        this.parent = parent;
        this.originalWorld = Minecraft.getMinecraft().theWorld;
        this.productionIntent = ProductionIntent.fromAttachment(attachment);
        this.selectedTargetIndex = productionIntent.initialTargetIndex();
        // Product and route choices live in structured controls; this field contains only user-authored text.
        this.draft = text;
    }

    boolean isOriginalWorld() {
        return originalWorld != null && Minecraft.getMinecraft().theWorld == originalWorld;
    }

    @Override
    public void initGui() {
        if (input != null) draft = input.getText();
        Keyboard.enableRepeatEvents(true);
        panelWidth = Math.min(520, width - 24);
        panelLeft = (width - panelWidth) / 2;
        inputOffset = attachment == null ? 112 : 150;
        panelHeight = inputOffset + 76;
        panelTop = Math.max(4, (height - panelHeight) / 2);
        input = new GuiTextField(fontRendererObj, panelLeft + 12, panelTop + inputOffset, panelWidth - 24, 20);
        input.setMaxStringLength(2048);
        input.setText(draft);
        input.setFocused(true);
        input.setCanLoseFocus(false);
        buttonList.clear();
        buttonList.add(
            new GuiButton(
                0,
                panelLeft + panelWidth - 166,
                panelTop + inputOffset + 44,
                76,
                20,
                tr("gtnhmcp.share.send")));
        buttonList.add(
            new GuiButton(
                1,
                panelLeft + panelWidth - 84,
                panelTop + inputOffset + 44,
                72,
                20,
                tr("gtnhmcp.share.cancel")));
        if (attachment != null) {
            GuiButton target = new GuiButton(2, panelLeft + 12, panelTop + 74, panelWidth - 24, 20, targetLabel());
            target.enabled = !productionIntent.candidates()
                .isEmpty();
            buttonList.add(target);
            if (productionIntent.isRecipe()) {
                buttonList.add(new GuiButton(3, panelLeft + 12, panelTop + 98, panelWidth - 24, 20, modeLabel()));
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xE018202A);
        fontRendererObj.drawString(tr("gtnhmcp.share.title"), panelLeft + 12, panelTop + 12, 0xFFFFFF);
        String title = attachment == null ? tr("gtnhmcp.share.textOnly")
            : tr("gtnhmcp.share." + attachment.get("type")) + ": " + attachment.get("title");
        drawLines(title, panelTop + 32, 0xA8D8FF, 2);
        drawLines(attachmentSummary(), panelTop + 54, 0xC5CDD7, attachment == null ? 2 : 1);
        if (attachment != null && !productionIntent.isRecipe()) {
            drawLines(tr("gtnhmcp.share.itemIntent"), panelTop + 100, 0xC5CDD7, 2);
        }
        drawLines(tr("gtnhmcp.share.localOnly"), panelTop + inputOffset - 28, 0x99A7B6, 2);
        input.drawTextBox();
        if (input.getText()
            .isEmpty()) {
            String placeholder = tr(
                attachment == null ? "gtnhmcp.share.questionPlaceholder" : "gtnhmcp.share.requirementsPlaceholder");
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(placeholder, panelWidth - 40),
                panelLeft + 20,
                panelTop + inputOffset + 6,
                0x788696);
        }
        if (error != null) drawLines(error, panelTop + inputOffset + 24, 0xFF9999, 2);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawLines(String text, int y, int color, int maxLines) {
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, panelWidth - 24);
        for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
            fontRendererObj.drawString(lines.get(i), panelLeft + 12, y + i * 10, color);
        }
    }

    private String attachmentSummary() {
        if (attachment == null) return tr("gtnhmcp.share.textContext");
        Object rawData = attachment.get("data");
        if (!(rawData instanceof Map<?, ?>)) return "";
        Map<?, ?> data = (Map<?, ?>) rawData;
        if ("item".equals(attachment.get("type"))) {
            String identity = "fluid".equals(data.get("kind")) ? String.valueOf(data.get("fluidName"))
                : data.get("registryName") + ":" + data.get("meta");
            return identity + "  " + data.get("amount") + " " + data.get("unit");
        }
        String summary = tr("gtnhmcp.share.recipeCaptured");
        Object gt = data.get("gregtech");
        if (gt instanceof Map<?, ?>) {
            Map<?, ?> details = (Map<?, ?>) gt;
            summary += " " + details.get("euPerTick") + " EU/t, " + details.get("durationTicks") + " t";
        }
        return summary;
    }

    static String outputNames(Map<?, ?> recipe) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "recipe");
        reference.put("data", recipe);
        List<Map<String, Object>> candidates = ProductionIntent.fromAttachment(reference)
            .candidates();
        if (candidates.isEmpty()) return tr("gtnhmcp.share.noKnownOutputs");
        if (candidates.size() == 1) return String.valueOf(
            candidates.get(0)
                .get("displayName"));
        return StatCollector.translateToLocalFormatted("gtnhmcp.share.outputCount", candidates.size());
    }

    private String targetLabel() {
        String label;
        if (productionIntent.candidates()
            .isEmpty()) label = tr("gtnhmcp.share.noKnownOutputs");
        else if (selectedTargetIndex < 0) label = StatCollector.translateToLocalFormatted(
            "gtnhmcp.share.selectTarget",
            productionIntent.candidates()
                .size());
        else label = StatCollector.translateToLocalFormatted(
            "gtnhmcp.share.target",
            productionIntent.candidates()
                .get(selectedTargetIndex)
                .get("displayName"));
        return fontRendererObj.trimStringToWidth(label, panelWidth - 36);
    }

    private String modeLabel() {
        return fontRendererObj.trimStringToWidth(
            tr(useAttachedRecipe ? "gtnhmcp.share.mode.fixed" : "gtnhmcp.share.mode.explore"),
            panelWidth - 36);
    }

    private void refreshIntentControls() {
        for (GuiButton button : buttonList) {
            if (button.id == 2) button.displayString = targetLabel();
            if (button.id == 3) button.displayString = modeLabel();
        }
    }

    @Override
    protected void keyTyped(char keyChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) close();
        else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) submit();
        else input.textboxKeyTyped(keyChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        input.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) submit();
        else if (button.id == 1) close();
        else if (button.id == 2 && !productionIntent.candidates()
            .isEmpty()) {
                selectedTargetIndex = (selectedTargetIndex + 2) % (productionIntent.candidates()
                    .size() + 1) - 1;
                targetChosenByUser = true;
                refreshIntentControls();
            } else if (button.id == 3 && productionIntent.isRecipe()) {
                useAttachedRecipe = !useAttachedRecipe;
                refreshIntentControls();
            }
    }

    private void submit() {
        if (submitted) return;
        draft = input.getText();
        if (!isOriginalWorld()) {
            error = tr("gtnhmcp.share.worldChanged");
            return;
        }
        if (attachment == null && draft.trim()
            .isEmpty()) {
            error = tr("gtnhmcp.share.empty");
            return;
        }
        try {
            Map<String, Object> messageAttachment = attachment;
            if (attachment != null) {
                messageAttachment = new LinkedHashMap<>(attachment);
                messageAttachment.put(
                    "intent",
                    productionIntent.snapshot(selectedTargetIndex, targetChosenByUser, useAttachedRecipe));
            }
            String messageText = draft.trim()
                .isEmpty() ? tr("gtnhmcp.share.defaultRequest") : draft;
            bridge.submitContextMessage(messageAttachment, messageText, capturedContext);
            submitted = true;
            mc.thePlayer.addChatMessage(new ChatComponentText("[GTNH MCP] " + tr("gtnhmcp.share.queued")));
            close();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            error = failure.getMessage() == null ? tr("gtnhmcp.share.unavailable") : failure.getMessage();
        }
    }

    private void close() {
        mc.displayGuiScreen(isOriginalWorld() ? parent : null);
        if (mc.currentScreen == null) mc.setIngameFocus();
    }

    @Override
    public void updateScreen() {
        input.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        draft = input == null ? draft : input.getText();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
