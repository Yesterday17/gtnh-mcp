package io.github.gtnhmcp.config;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

import io.github.gtnhmcp.ClientProxy;
import io.github.gtnhmcp.GtnhMcpMod;

/** Only enabled/port are editable. Applying waits asynchronously, leaving the game responsive. */
public final class LocalConfigScreen extends GuiScreen {

    private final GuiScreen parent;
    private final ClientProxy proxy;
    private GuiTextField portField;
    private GuiButton enabledButton;
    private GuiButton applyButton;
    private boolean enabled;
    private String portDraft;
    private String messageKey;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private CompletableFuture<ServerLifecycle.Result> pending;

    public LocalConfigScreen(GuiScreen parent) {
        this.parent = parent;
        proxy = GtnhMcpMod.proxy instanceof ClientProxy ? (ClientProxy) GtnhMcpMod.proxy : null;
        ServerLifecycle.State state = settings();
        enabled = state != null && state.enabled;
        portDraft = Integer.toString(state == null ? 29710 : state.port);
    }

    private ServerLifecycle.State settings() {
        return proxy == null ? null : proxy.serverSettings();
    }

    @Override
    public void initGui() {
        if (portField != null) portDraft = portField.getText();
        Keyboard.enableRepeatEvents(true);
        panelWidth = Math.min(420, width - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (height - 226) / 2);
        portField = new GuiTextField(fontRendererObj, panelLeft + 128, panelTop + 78, panelWidth - 144, 20);
        portField.setMaxStringLength(5);
        portField.setText(portDraft);
        buttonList.clear();
        enabledButton = new GuiButton(0, panelLeft + 128, panelTop + 48, panelWidth - 144, 20, "");
        applyButton = new GuiButton(
            1,
            panelLeft + panelWidth - 178,
            panelTop + 198,
            78,
            20,
            tr("gtnhmcp.config.apply"));
        buttonList.add(enabledButton);
        buttonList.add(applyButton);
        buttonList.add(new GuiButton(2, panelLeft + panelWidth - 94, panelTop + 198, 78, 20, tr("gui.done")));
        refreshControls();
    }

    private void refreshControls() {
        ServerLifecycle.State state = settings();
        boolean busy = pending != null || (state != null && state.applying);
        enabledButton.displayString = tr(enabled ? "gtnhmcp.config.on" : "gtnhmcp.config.off");
        enabledButton.enabled = state != null && !busy;
        applyButton.enabled = state != null && !busy;
        portField.setEnabled(state != null && !busy);
    }

    @Override
    public void updateScreen() {
        portField.updateCursorCounter();
        if (pending != null && pending.isDone()) {
            try {
                ServerLifecycle.Result result = pending.join();
                messageKey = result.success ? "gtnhmcp.config.applied" : "gtnhmcp.config.error." + result.errorCode;
            } catch (RuntimeException failure) {
                messageKey = "gtnhmcp.config.error.apply_failed";
            }
            pending = null;
        }
        refreshControls();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        if (button.id == 0) {
            enabled = !enabled;
            refreshControls();
        } else if (button.id == 1) {
            int port;
            try {
                port = Integer.parseInt(portField.getText());
                if (port < 1024 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                messageKey = "gtnhmcp.config.error.invalid_port";
                return;
            }
            messageKey = null;
            pending = proxy.applyServerSettings(enabled, port);
            refreshControls();
        } else if (button.id == 2) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else if (pending == null) {
            portField.textboxKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        portField.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 226, 0xE018202A);
        drawCenteredString(fontRendererObj, tr("gtnhmcp.config.title"), width / 2, panelTop + 14, 0xFFFFFF);
        fontRendererObj.drawString(tr("gtnhmcp.config.enabled"), panelLeft + 16, panelTop + 54, 0xDDDDDD);
        fontRendererObj.drawString(tr("gtnhmcp.config.port"), panelLeft + 16, panelTop + 84, 0xDDDDDD);
        portField.drawTextBox();
        ServerLifecycle.State state = settings();
        String status;
        if (state == null) status = tr("gtnhmcp.config.error.unavailable");
        else if (state.applying) status = tr("gtnhmcp.config.applying");
        else if (state.running) status = tr("gtnhmcp.config.listening", "http://127.0.0.1:" + state.port + "/mcp");
        else if (state.errorCode != null) status = tr("gtnhmcp.config.error." + state.errorCode);
        else status = tr("gtnhmcp.config.disabled");
        lines(status, panelTop + 110, 0xA8D8FF, 2);
        lines(tr("gtnhmcp.config.tokenPrivate"), panelTop + 136, 0x99A7B6, 2);
        if (messageKey != null)
            lines(tr(messageKey), panelTop + 166, "gtnhmcp.config.applied".equals(messageKey) ? 0xAAE0AA : 0xFF9999, 3);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void lines(String text, int y, int color, int limit) {
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, panelWidth - 32);
        for (int i = 0; i < Math.min(limit, lines.size()); i++) {
            fontRendererObj.drawString(lines.get(i), panelLeft + 16, y + i * 10, color);
        }
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
