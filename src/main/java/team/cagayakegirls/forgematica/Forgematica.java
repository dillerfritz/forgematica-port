package team.cagayakegirls.forgematica;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.compat.modmenu.ModMenuImpl;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import team.cagayakegirls.forgematica.teamsync.TeamSyncClient;
import team.cagayakegirls.forgematica.teamsync.TeamSyncPayload;
import team.cagayakegirls.forgematica.teamsync.TeamSyncRelay;

@Mod(value = Forgematica.MOD_ID, dist = Dist.CLIENT)
public class Forgematica {
    public static final String MOD_ID = "forgematica";

    public Forgematica(IEventBus modBus, ModContainer modContainer) {
        if (FMLLoader.getCurrent().getDist().isClient()) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, new ModMenuImpl().getModConfigScreenFactory());
            new Litematica().onInitialize();
            NeoForge.EVENT_BUS.addListener(SchematicCommand::onRegisterCommands);
            NeoForge.EVENT_BUS.addListener(TeamSyncClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(PerformancePreset::onClientTick);
            NeoForge.EVENT_BUS.addListener(team.cagayakegirls.forgematica.ux.UxPreset::onClientTick);
            NeoForge.EVENT_BUS.addListener(team.cagayakegirls.forgematica.ux.FmKeys::onClientTick);
            modBus.addListener(team.cagayakegirls.forgematica.ux.FmKeys::onRegister);
            modBus.addListener(Forgematica::registerPayloads);
        }
    }

    // Team sync: optional, so servers / clients without it still connect normally.
    // The server handler is used by the integrated server (singleplayer / LAN);
    // dedicated servers use the separate "Forgematica TeamSync" server mod with the same channel.
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(TeamSyncPayload.PROTOCOL_VERSION)
                .optional()
                .playBidirectional(TeamSyncPayload.TYPE, TeamSyncPayload.CODEC, TeamSyncRelay::handle, TeamSyncClient::handle);
    }
}
