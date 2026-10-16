package team.cagayakegirls.forgematica;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.config.Configs;

/**
 * Settings that keep Forgematica light on the GPU/CPU for big build projects.
 * Applied automatically once per installation (marker file), and again on /schematic performance.
 */
public final class PerformancePreset
{
    private static final String MARKER = "forgematica-performance-v1.done";
    private static boolean checked;

    private PerformancePreset() {}

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
                Files.writeString(marker, "Forgematica performance preset applied once. Delete this file to apply it again on next world join.\n");
                SchematicCommand.info("Leistungs-Einstellungen wurden gesetzt (rückgängig: Konfiguration → Visuals).");
            }
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("Could not apply performance preset: {}", e.toString());
        }
    }

    public static int applyCommand()
    {
        apply();
        SchematicCommand.info("Leistungs-Einstellungen gesetzt: nur nahe Chunks, einfachere Overlays, keine Geister-Entities.");
        return 1;
    }

    public static void apply()
    {
        // Only build render data for chunks around the player instead of the whole schematic
        set(Configs.Generic.LOAD_ENTIRE_SCHEMATICS, false);

        // Ghost blocks stay transparent, but without the expensive extras
        set(Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT, true);
        set(Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES, false);
        set(Configs.Visuals.RENDER_ENABLE_TRANSLUCENT_RESORTING, false);
        set(Configs.Visuals.RENDER_AO_MODERN_ENABLE, false);

        // Simple box overlays instead of per-model overlays, fewer hidden faces
        set(Configs.Visuals.OVERLAY_REDUCED_INNER_SIDES, true);
        set(Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_OUTLINE, false);
        set(Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_SIDES, false);
        set(Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH, false);
        set(Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY_CULLING, true);

        // Entities inside schematics are not needed for building
        set(Configs.Visuals.RENDER_SCHEMATIC_ENTITIES, false);
        set(Configs.Visuals.ENABLE_SCHEMATIC_ENTITY_HITBOXES, false);

        Configs.saveToFile();
    }

    private static void set(ConfigBoolean config, boolean value)
    {
        if (config.getBooleanValue() != value)
        {
            config.setBooleanValue(value);
        }
    }
}
