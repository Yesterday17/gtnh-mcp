package io.github.gtnhmcp.ui;

import static io.github.gtnhmcp.ui.ContextSharingUi.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import org.lwjgl.input.Keyboard;

import io.github.gtnhmcp.game.GameBridge;

/** A local message composer; vanilla chat and the remote game server never receive its text. */
final class ShareMessageScreen extends GuiScreen {

    private final GameBridge bridge;
    private final Map<String, Object> attachment;
    private final Map<String, Object> capturedContext;
    private final GuiScreen parent;
    private final World originalWorld;
    private GuiTextField input;
    private String draft;
    private String error;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private boolean submitted;

    ShareMessageScreen(GameBridge bridge, Map<String, Object> attachment, Map<String, Object> capturedContext,
        String text, GuiScreen parent) {
        this.bridge = bridge;
        this.attachment = attachment;
        this.capturedContext = capturedContext;
        this.parent = parent;
        this.originalWorld = Minecraft.getMinecraft().theWorld;
        this.draft = text.isEmpty() && attachment != null ? tr("gtnhmcp.share.defaultRequest") : text;
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
        panelTop = Math.max(10, (height - 188) / 2);
        input = new GuiTextField(fontRendererObj, panelLeft + 12, panelTop + 112, panelWidth - 24, 20);
        input.setMaxStringLength(2048);
        input.setText(draft);
        input.setFocused(true);
        input.setCanLoseFocus(false);
        buttonList.clear();
        buttonList
            .add(new GuiButton(0, panelLeft + panelWidth - 166, panelTop + 156, 76, 20, tr("gtnhmcp.share.send")));
        buttonList
            .add(new GuiButton(1, panelLeft + panelWidth - 84, panelTop + 156, 72, 20, tr("gtnhmcp.share.cancel")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 188, 0xE018202A);
        fontRendererObj.drawString(tr("gtnhmcp.share.title"), panelLeft + 12, panelTop + 12, 0xFFFFFF);
        String title = attachment == null ? tr("gtnhmcp.share.textOnly")
            : tr("gtnhmcp.share." + attachment.get("type")) + ": " + attachment.get("title");
        drawLines(title, panelTop + 32, 0xA8D8FF, 2);
        drawLines(attachmentSummary(), panelTop + 56, 0xC5CDD7, 2);
        drawLines(tr("gtnhmcp.share.localOnly"), panelTop + 80, 0x99A7B6, 2);
        input.drawTextBox();
        if (error != null) drawLines(error, panelTop + 136, 0xFF9999, 2);
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
        List<Object> slots = new ArrayList<>();
        if (recipe.get("result") != null) slots.add(recipe.get("result"));
        if (recipe.get("otherSlots") instanceof List<?>) slots.addAll((List<?>) recipe.get("otherSlots"));
        List<String> names = new ArrayList<>();
        for (Object slot : slots) {
            if (!(slot instanceof Map<?, ?>)) continue;
            Object alternatives = ((Map<?, ?>) slot).get("alternatives");
            if (!(alternatives instanceof List<?>) || ((List<?>) alternatives).isEmpty()) continue;
            Object first = ((List<?>) alternatives).get(0);
            if (!(first instanceof Map<?, ?>)) continue;
            names.add(String.valueOf(((Map<?, ?>) first).get("displayName")));
            if (names.size() == 2) break;
        }
        return names.isEmpty() ? tr("gtnhmcp.share.recipe") : String.join(", ", names);
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
    }

    private void submit() {
        if (submitted) return;
        draft = input.getText();
        if (!isOriginalWorld()) {
            error = tr("gtnhmcp.share.worldChanged");
            return;
        }
        if (draft.trim()
            .isEmpty()) {
            error = tr("gtnhmcp.share.empty");
            return;
        }
        try {
            bridge.submitContextMessage(attachment, draft, capturedContext);
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
