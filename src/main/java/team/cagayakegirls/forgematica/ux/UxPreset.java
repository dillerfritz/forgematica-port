package team.cagayakegirls.forgematica.ux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;

/**
 * One-time switch to the simplified Forgematica setup:
 * - old Litematica hotkeys (mostly "M + …") are cleared; the important ones now live in the Minecraft key binds
 * - ghost blocks: no blue tint, adjustable opacity; wrong blocks stay red
 */
public final class UxPreset
{
    private static final String MARKER = "forgematica-ux-v2.done";

    /** Litematica hotkeys that stay active (mouse actions with the tool item / easy place / pick block). */
    private static final Set<String> KEEP = Set.of(
            "easyPlaceUseKey", "pickBlockFirst", "operationModeChangeModifier",
            "toolPlaceCorner1", "toolPlaceCorner2", "toolSelectElements",
            "toolSelectModifierBlock1", "toolSelectModifierBlock2", "selectionNudgeModifier",
            "renderOverlayThroughBlocks");

    private static boolean checked;

    private UxPreset() {}

    public static void onClientTick(ClientTickEvent.Post event)
    {
        if (checked || Minecraft.getInstance().level == null)
        {
            return;
        }

        checked = true;

        try
        {
            Path marker = FMLPaths.CONFIGDIR.get().resolve(MARKER);

            if (!Files.exists(marker))
            {
                apply();
                Files.writeString(marker, "Forgematica simplified setup applied. Delete this file to apply it again.\n");
                SchematicCommand.info("Neu: Menü mit Taste K, alle Tasten unter Optionen → Steuerung → Tastenbelegung → Forgematica.");
            }
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("Could not apply UX preset: {}", e.toString());
        }
    }

    public static void apply()
    {
        for (ConfigHotkey hotkey : Hotkeys.HOTKEY_LIST)
        {
            if (!KEEP.contains(hotkey.getName()))
            {
                hotkey.getKeybind().setValueFromString("");
            }
        }

        // Ghost blocks: real block textures, semi transparent, no blue tint
        Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
        Configs.Visuals.GHOST_BLOCK_ALPHA.setDoubleValue(0.6);
        Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.setBooleanValue(false);
        Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.setValueFromString("#40FFFFFF");

        // Wrong blocks: clearly red
        Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_BLOCK.setValueFromString("#66FF2020");

        Configs.saveToFile();
    }
}
