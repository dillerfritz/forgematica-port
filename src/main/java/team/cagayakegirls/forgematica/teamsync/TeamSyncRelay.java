package team.cagayakegirls.forgematica.teamsync;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server side of the team sync (used by the integrated server in singleplayer / LAN).
 * Forwards every packet unchanged to all online members of the sender's vanilla team.
 * Same logic as the dedicated-server companion mod.
 */
public final class TeamSyncRelay
{
    private TeamSyncRelay() {}

    public static void handle(TeamSyncPayload payload, IPayloadContext ctx)
    {
        if (!(ctx.player() instanceof ServerPlayer sender))
        {
            return;
        }

        if (payload.index() < 0 || payload.count() < 0 || payload.count() > TeamSyncPayload.MAX_CHUNKS
            || payload.data().length > TeamSyncPayload.MAX_CHUNK_BYTES)
        {
            return;
        }

        MinecraftServer server = sender.level().getServer();

        if (server == null)
        {
            return;
        }

        PlayerTeam team = server.getScoreboard().getPlayersTeam(sender.getScoreboardName());

        if (team == null)
        {
            if (payload.index() == 0)
            {
                sender.sendSystemMessage(Component.literal("[Forgematica] Du bist in keinem Team (/team join <Team>)."));
            }
            return;
        }

        TeamSyncPayload relayed = payload.withSender(sender.getScoreboardName());
        int recipients = 0;

        for (ServerPlayer other : server.getPlayerList().getPlayers())
        {
            if (other == sender || server.getScoreboard().getPlayersTeam(other.getScoreboardName()) != team)
            {
                continue;
            }

            if (other.connection != null && other.connection.hasChannel(TeamSyncPayload.TYPE))
            {
                PacketDistributor.sendToPlayer(other, relayed);
                recipients++;
            }
        }

        if (payload.action() == TeamSyncPayload.ACTION_CHUNK && payload.index() == 0)
        {
            sender.sendSystemMessage(Component.literal("[Forgematica] Wird mit " + recipients
                    + " Teammitglied(ern) im Team \"" + team.getName() + "\" geteilt."));
        }
    }
}
