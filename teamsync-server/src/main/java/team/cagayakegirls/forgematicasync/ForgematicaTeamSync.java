package team.cagayakegirls.forgematicasync;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Dedicated-server companion of the Forgematica 26.3 port.
 * Relays shared schematics between members of the same vanilla team (/team).
 */
@Mod(value = "forgematica_teamsync", dist = Dist.DEDICATED_SERVER)
public class ForgematicaTeamSync
{
    public ForgematicaTeamSync(IEventBus modBus)
    {
        modBus.addListener(ForgematicaTeamSync::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event)
    {
        event.registrar(TeamSyncPayload.PROTOCOL_VERSION)
                .optional()
                .playBidirectional(TeamSyncPayload.TYPE, TeamSyncPayload.CODEC, TeamSyncRelay::handle, (payload, ctx) -> {});
    }
}
