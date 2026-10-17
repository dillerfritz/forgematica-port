package team.cagayakegirls.forgematicasync.paper;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Paper version of the Forgematica TeamSync relay.
 * <p>
 * Receives packets on the plugin channel "forgematica:team_sync" from Forgematica clients and forwards them
 * to all online members of the sender's scoreboard team (/team). The byte format must match
 * TeamSyncPayload in Forgematica (Minecraft FriendlyByteBuf encoding).
 */
public final class ForgematicaTeamSyncPlugin extends JavaPlugin implements PluginMessageListener, Listener
{
    public static final String CHANNEL = "forgematica:team_sync";

    private static final byte ACTION_CHUNK = 0;
    private static final int MAX_CHUNK_BYTES = 30000;
    private static final int MAX_CHUNKS = 1000;
    private static final byte ACTION_MOVE = 1;
    private static final byte ACTION_REMOVE = 2;
    /** Max. memory used for remembering shared schematics (for players who join later). */
    private static final long MAX_CACHE_BYTES = 200L * 1024L * 1024L;

    /** shareId -> remembered share, oldest first */
    private final Map<UUID, Cached> cache = new LinkedHashMap<>();
    private long cacheBytes;

    private static final class Cached
    {
        final String team;
        final String sender;
        final byte[][] chunks;
        byte[] lastMove;
        long bytes;

        Cached(String team, String sender, int count)
        {
            this.team = team;
            this.sender = sender;
            this.chunks = new byte[count][];
        }

        boolean complete()
        {
            for (byte[] c : this.chunks) if (c == null) return false;
            return true;
        }
    }

    @Override
    public void onEnable()
    {
        this.getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info("Forgematica TeamSync aktiv (Kanal " + CHANNEL + ")");
    }

    @Override
    public void onDisable()
    {
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this);
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player sender, byte[] message)
    {
        if (!CHANNEL.equals(channel))
        {
            return;
        }

        Packet packet;

        try
        {
            packet = Packet.read(ByteBuffer.wrap(message));
        }
        catch (RuntimeException e)
        {
            this.getLogger().warning("Ungültiges TeamSync-Paket von " + sender.getName() + ": " + e);
            return;
        }

        if (packet.index < 0 || packet.count < 0 || packet.count > MAX_CHUNKS || packet.data.length > MAX_CHUNK_BYTES)
        {
            return;
        }

        boolean firstChunk = packet.action == ACTION_CHUNK && packet.index == 0;
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getEntryTeam(sender.getName());

        if (team == null)
        {
            if (firstChunk)
            {
                sender.sendMessage(Component.text("[Forgematica] Du bist in keinem Team (/team join <Team>).", NamedTextColor.RED));
            }
            return;
        }

        packet.sender = sender.getName();
        byte[] relayed = packet.write();
        this.remember(packet, relayed, team.getName());
        int recipients = 0;

        for (Player other : Bukkit.getOnlinePlayers())
        {
            if (other.equals(sender))
            {
                continue;
            }

            Team otherTeam = scoreboard.getEntryTeam(other.getName());

            if (otherTeam != null && otherTeam.getName().equals(team.getName())
                && other.getListeningPluginChannels().contains(CHANNEL))
            {
                other.sendPluginMessage(this, CHANNEL, relayed);
                recipients++;
            }
        }

        if (firstChunk)
        {
            sender.sendMessage(Component.text("[Forgematica] Wird mit " + recipients
                    + " Teammitglied(ern) im Team \"" + team.getName() + "\" geteilt.", NamedTextColor.GREEN));
        }
    }

    // ------------------------------------------------------------------ late joiners

    private void remember(Packet packet, byte[] relayed, String teamName)
    {
        UUID id = new UUID(packet.uuidMost, packet.uuidLeast);

        if (packet.action == ACTION_REMOVE)
        {
            this.forget(id);
            return;
        }

        Cached c = this.cache.get(id);

        if (packet.action == ACTION_MOVE)
        {
            if (c != null)
            {
                c.lastMove = relayed;
            }
            return;
        }

        if (packet.count <= 0 || packet.index >= packet.count)
        {
            return;
        }

        if (c == null || packet.index == 0 || c.chunks.length != packet.count)
        {
            this.forget(id);
            c = new Cached(teamName, packet.sender, packet.count);
            this.cache.put(id, c);
        }

        if (c.chunks[packet.index] == null)
        {
            c.chunks[packet.index] = relayed;
            c.bytes += relayed.length;
            this.cacheBytes += relayed.length;
        }

        // Drop the oldest shares when using too much memory
        Iterator<Map.Entry<UUID, Cached>> it = this.cache.entrySet().iterator();

        while (this.cacheBytes > MAX_CACHE_BYTES && it.hasNext())
        {
            Map.Entry<UUID, Cached> e = it.next();

            if (e.getKey().equals(id))
            {
                continue;
            }

            this.cacheBytes -= e.getValue().bytes;
            it.remove();
        }
    }

    private void forget(UUID id)
    {
        Cached old = this.cache.remove(id);

        if (old != null)
        {
            this.cacheBytes -= old.bytes;
        }
    }

    /** A Forgematica client opened the channel (= joined): send it the shares of its team. */
    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event)
    {
        if (!CHANNEL.equals(event.getChannel()))
        {
            return;
        }

        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> this.sendCachedTo(player), 40L);
    }

    private void sendCachedTo(Player player)
    {
        if (!player.isOnline())
        {
            return;
        }

        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());

        if (team == null)
        {
            return;
        }

        List<Cached> toSend = new ArrayList<>();

        for (Cached c : this.cache.values())
        {
            if (c.team.equals(team.getName()) && !c.sender.equals(player.getName()) && c.complete())
            {
                toSend.add(c);
            }
        }

        for (Cached c : toSend)
        {
            for (byte[] chunk : c.chunks)
            {
                player.sendPluginMessage(this, CHANNEL, chunk);
            }

            if (c.lastMove != null)
            {
                player.sendPluginMessage(this, CHANNEL, c.lastMove);
            }
        }
    }

    /** Mirror of TeamSyncPayload with the same wire format. */
    private static final class Packet
    {
        byte action;
        long uuidMost;
        long uuidLeast;
        String sender;
        String fileName;
        int x, y, z;
        byte rotation;
        byte mirror;
        int index;
        int count;
        byte[] data;

        static Packet read(ByteBuffer buf)
        {
            try
            {
                Packet p = new Packet();
                p.action = buf.get();
                p.uuidMost = buf.getLong();
                p.uuidLeast = buf.getLong();
                p.sender = readUtf(buf, 64);
                p.fileName = readUtf(buf, 256);
                p.x = buf.getInt();
                p.y = buf.getInt();
                p.z = buf.getInt();
                p.rotation = buf.get();
                p.mirror = buf.get();
                p.index = readVarInt(buf);
                p.count = readVarInt(buf);
                int len = readVarInt(buf);

                if (len < 0 || len > MAX_CHUNK_BYTES || len > buf.remaining())
                {
                    throw new IllegalArgumentException("bad data length " + len);
                }

                p.data = new byte[len];
                buf.get(p.data);
                return p;
            }
            catch (BufferUnderflowException e)
            {
                throw new IllegalArgumentException("truncated packet", e);
            }
        }

        byte[] write()
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream(this.data.length + 512);
            out.write(this.action);
            writeLong(out, this.uuidMost);
            writeLong(out, this.uuidLeast);
            writeUtf(out, this.sender);
            writeUtf(out, this.fileName);
            writeInt(out, this.x);
            writeInt(out, this.y);
            writeInt(out, this.z);
            out.write(this.rotation);
            out.write(this.mirror);
            writeVarInt(out, this.index);
            writeVarInt(out, this.count);
            writeVarInt(out, this.data.length);
            out.writeBytes(this.data);
            return out.toByteArray();
        }

        private static int readVarInt(ByteBuffer buf)
        {
            int value = 0;
            int shift = 0;
            byte b;

            do
            {
                b = buf.get();
                value |= (b & 0x7F) << shift;
                shift += 7;

                if (shift > 35)
                {
                    throw new IllegalArgumentException("VarInt too big");
                }
            }
            while ((b & 0x80) != 0);

            return value;
        }

        private static void writeVarInt(ByteArrayOutputStream out, int value)
        {
            while ((value & ~0x7F) != 0)
            {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }

            out.write(value);
        }

        private static String readUtf(ByteBuffer buf, int maxChars)
        {
            int len = readVarInt(buf);

            if (len < 0 || len > maxChars * 3 || len > buf.remaining())
            {
                throw new IllegalArgumentException("bad string length " + len);
            }

            byte[] bytes = new byte[len];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static void writeUtf(ByteArrayOutputStream out, String s)
        {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, bytes.length);
            out.writeBytes(bytes);
        }

        private static void writeInt(ByteArrayOutputStream out, int v)
        {
            out.write(v >>> 24);
            out.write(v >>> 16);
            out.write(v >>> 8);
            out.write(v);
        }

        private static void writeLong(ByteArrayOutputStream out, long v)
        {
            writeInt(out, (int) (v >>> 32));
            writeInt(out, (int) v);
        }
    }
}
