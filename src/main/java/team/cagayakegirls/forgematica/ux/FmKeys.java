package team.cagayakegirls.forgematica.ux;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.config.Configs;

/**
 * All Forgematica keys, registered as normal Minecraft key mappings
 * (Options → Controls → Key Binds → category "Forgematica").
 */
public final class FmKeys
{
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("forgematica", "main"));

    public static final KeyMapping OPEN_MENU        = key("open_menu",        InputConstants.KEY_K);
    public static final KeyMapping MATERIAL_LIST    = key("material_list",    InputConstants.KEY_O);
    public static final KeyMapping MOVE_MODE        = key("move_mode",        InputConstants.KEY_I);
    public static final KeyMapping TOGGLE_RENDERING = key("toggle_rendering", InputConstants.UNKNOWN.getValue());

    // Only active while the move mode is on
    public static final KeyMapping MOVE_FORWARD     = key("move_forward",     InputConstants.KEY_UP);
    public static final KeyMapping MOVE_BACK        = key("move_back",        InputConstants.KEY_DOWN);
    public static final KeyMapping MOVE_LEFT        = key("move_left",        InputConstants.KEY_LEFT);
    public static final KeyMapping MOVE_RIGHT       = key("move_right",       InputConstants.KEY_RIGHT);
    public static final KeyMapping MOVE_UP          = key("move_up",          InputConstants.KEY_PAGEUP);
    public static final KeyMapping MOVE_DOWN        = key("move_down",        InputConstants.KEY_PAGEDOWN);
    public static final KeyMapping ROTATE           = key("rotate",           InputConstants.KEY_R);
    public static final KeyMapping MIRROR           = key("mirror",           InputConstants.UNKNOWN.getValue());
    public static final KeyMapping CONFIRM          = key("confirm",          InputConstants.KEY_RETURN);
    public static final KeyMapping CANCEL           = key("cancel",           InputConstants.KEY_BACKSPACE);

    private static final KeyMapping[] ALL = {
            OPEN_MENU, MATERIAL_LIST, MOVE_MODE, TOGGLE_RENDERING,
            MOVE_FORWARD, MOVE_BACK, MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN, ROTATE, MIRROR, CONFIRM, CANCEL
    };

    private FmKeys() {}

    private static KeyMapping key(String name, int keyCode)
    {
        return new KeyMapping("key.forgematica." + name, KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYBOARD, keyCode, CATEGORY);
    }

    public static void onRegister(RegisterKeyMappingsEvent event)
    {
        event.registerCategory(CATEGORY);

        for (KeyMapping key : ALL)
        {
            event.register(key);
        }
    }

    public static void onClientTick(ClientTickEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.gui.screen() != null)
        {
            MoveMode.tickNoInput();
            return;
        }

        while (OPEN_MENU.consumeClick())
        {
            GuiBase.openGui(new SimpleMenu());
            return;
        }

        while (MATERIAL_LIST.consumeClick())
        {
            SchematicCommand.toggleMaterialList(true);
        }

        while (TOGGLE_RENDERING.consumeClick())
        {
            boolean on = !Configs.Visuals.ENABLE_RENDERING.getBooleanValue();
            Configs.Visuals.ENABLE_RENDERING.setBooleanValue(on);
            mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                    on ? "Schematics eingeblendet" : "Schematics ausgeblendet"));
        }

        while (MOVE_MODE.consumeClick())
        {
            MoveMode.toggle();
        }

        MoveMode.tick();
    }
}
