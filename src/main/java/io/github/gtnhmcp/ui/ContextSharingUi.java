package io.github.gtnhmcp.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import codechicken.nei.KeyManager;
import codechicken.nei.api.API;
import codechicken.nei.guihook.GuiContainerManager;
import codechicken.nei.guihook.IContainerInputHandler;
import codechicken.nei.recipe.GuiRecipeButton;
import codechicken.nei.recipe.RecipeHandlerRef;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.gtnhmcp.game.GameBridge;
import io.github.gtnhmcp.nei.NeiSerializer;

/** User-initiated, local-only sharing. Capture the attachment before the recipe GUI can change. */
public final class ContextSharingUi implements IContainerInputHandler {

    public static final String SHARE_KEY = "gtnhmcp.share";
    private final GameBridge bridge;
    private ShareMessageScreen pendingScreen;

    public ContextSharingUi(GameBridge bridge) {
        this.bridge = bridge;
    }

    public void register() {
        // The pack already binds many keys. Users opt in through NEI's key-binding options.
        API.addKeyBind(SHARE_KEY, Keyboard.KEY_NONE);
        KeyManager.requestOptionsReload();
        GuiContainerManager.addInputHandler(this);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        ClientCommandHandler.instance.registerCommand(new ShareCommand());
    }

    @SubscribeEvent
    public void recipeButtons(GuiRecipeButton.UpdateRecipeButtonsEvent.Post event) {
        int x = Math.max(0, Math.min(166, event.width) - GuiRecipeButton.BUTTON_WIDTH);
        int y = event.height - GuiRecipeButton.BUTTON_HEIGHT - 6;
        for (; y >= 0; y -= GuiRecipeButton.BUTTON_HEIGHT + 1) {
            boolean occupied = false;
            for (GuiButton button : event.buttonList) {
                if (x < button.xPosition + button.width && x + GuiRecipeButton.BUTTON_WIDTH > button.xPosition
                    && y < button.yPosition + button.height
                    && y + GuiRecipeButton.BUTTON_HEIGHT > button.yPosition) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) {
                event.buttonList.add(new RecipeShareButton(event.handlerRef, x, y));
                return;
            }
        }
    }

    @SubscribeEvent
    public void keyInput(InputEvent.KeyInputEvent event) {
        if (Minecraft.getMinecraft().currentScreen == null && shareKeyPressed()) openItem(null);
    }

    @SubscribeEvent
    public void mouseInput(InputEvent.MouseInputEvent event) {
        if (Minecraft.getMinecraft().currentScreen == null && Mouse.getEventButtonState()
            && shareMousePressed(Mouse.getEventButton())) openItem(null);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingScreen == null) return;
        // GuiChat closes itself after dispatching a command; open the local composer on the next tick.
        ShareMessageScreen screen = pendingScreen;
        pendingScreen = null;
        if (screen.isOriginalWorld()) Minecraft.getMinecraft()
            .displayGuiScreen(screen);
        else reportError(tr("gtnhmcp.share.worldChanged"));
    }

    private static boolean shareKeyPressed() {
        int key = KeyManager.getKeyCode(SHARE_KEY);
        return key > Keyboard.KEY_NONE && Keyboard.getEventKeyState()
            && !Keyboard.isRepeatEvent()
            && Keyboard.getEventKey() == key;
    }

    private static boolean shareMousePressed(int button) {
        int key = KeyManager.getKeyCode(SHARE_KEY);
        return button >= 0 && key < 0 && key == button - 100;
    }

    private void openItem(GuiContainer gui) {
        try {
            ItemStack hovered = gui == null ? null : GuiContainerManager.getStackMouseOver(gui);
            Map<String, Object> attachment = null;
            if (hovered != null && hovered.getItem() != null) {
                Map<String, Object> item = NeiSerializer.item(hovered.copy());
                attachment = attachment("item", String.valueOf(item.get("displayName")), item);
            }
            open(attachment, "", gui, false);
        } catch (RuntimeException | LinkageError error) {
            reportError(error.getMessage());
        }
    }

    private void openRecipe(RecipeHandlerRef ref) {
        try {
            Map<String, Object> recipe = NeiSerializer.recipe(ref.handler, ref.recipeIndex);
            String title = recipe.get("category") + " / " + ShareMessageScreen.outputNames(recipe);
            open(attachment("recipe", title, recipe), "", Minecraft.getMinecraft().currentScreen, false);
        } catch (RuntimeException | LinkageError error) {
            reportError(error.getMessage());
        }
    }

    private void open(Map<String, Object> attachment, String text, GuiScreen parent, boolean defer) {
        Map<String, Object> context = bridge.captureShareContext();
        ShareMessageScreen screen = new ShareMessageScreen(bridge, attachment, context, text, parent);
        if (defer) pendingScreen = screen;
        else Minecraft.getMinecraft()
            .displayGuiScreen(screen);
    }

    private static Map<String, Object> attachment(String type, String title, Map<String, Object> data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("title", title);
        value.put("data", data);
        return value;
    }

    static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    static void reportError(String detail) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        String message = detail == null ? tr("gtnhmcp.share.unavailable") : detail;
        mc.thePlayer.addChatMessage(new ChatComponentText("[GTNH MCP] " + message));
    }

    @Override
    public boolean lastKeyTyped(GuiContainer gui, char keyChar, int keyCode) {
        if (Minecraft.getMinecraft().currentScreen != gui) return false;
        if (!shareKeyPressed()) return false;
        openItem(gui);
        return true;
    }

    @Override
    public boolean keyTyped(GuiContainer gui, char keyChar, int keyCode) {
        return false;
    }

    @Override
    public void onKeyTyped(GuiContainer gui, char keyChar, int keyCode) {}

    @Override
    public boolean mouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {
        if (Minecraft.getMinecraft().currentScreen != gui || !shareMousePressed(button)) return false;
        openItem(gui);
        return true;
    }

    @Override
    public void onMouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public void onMouseUp(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public boolean mouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scroll) {
        return false;
    }

    @Override
    public void onMouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scroll) {}

    @Override
    public void onMouseDragged(GuiContainer gui, int mouseX, int mouseY, int button, long duration) {}

    private final class RecipeShareButton extends GuiRecipeButton {

        RecipeShareButton(RecipeHandlerRef ref, int x, int y) {
            super(ref, x, y, 29710, "AI");
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            if (enabled && visible && contains(mouseX, mouseY)) openRecipe(handlerRef);
        }

        @Override
        public List<String> handleTooltip(List<String> tooltip) {
            tooltip.add(tr("gtnhmcp.share.recipeButton"));
            return tooltip;
        }

        @Override
        public Map<String, String> handleHotkeys(int mouseX, int mouseY, Map<String, String> hotkeys) {
            return hotkeys;
        }

        @Override
        public void lastKeyTyped(char keyChar, int keyCode) {}

        @Override
        public void drawItemOverlay() {}
    }

    private final class ShareCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "gtnhai";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/gtnhai [message]";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            try {
                open(null, String.join(" ", args), null, true);
            } catch (IllegalArgumentException | IllegalStateException error) {
                reportError(error.getMessage());
            }
        }
    }
}
