package team.cagayakegirls.forgematica.teamsync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;

/**
 * Client side of the team sync: shares own placements with the team and shows the placements shared by teammates.
 */
public final class TeamSyncClient
{
    private static final IMessageConsumer NO_FEEDBACK = new IMessageConsumer()
    {
        @Override public void addMessage(MessageType type, String messageKey, Object... args) {}
        @Override public void addMessage(MessageType type, int lifeTime, String messageKey, Object... args) {}
    };

    /** Own placements that are shared: placement -> state at last send */
    private static final Map<SchematicPlacement, OwnShare> OWN = new HashMap<>();
    /** Placements received from teammates: shareId -> placement */
    private static final Map<UUID, SchematicPlacement> RECEIVED = new HashMap<>();
    /** Incomplete incoming transfers */
    private static final Map<UUID, Transfer> TRANSFERS = new HashMap<>();

    private static boolean receiveEnabled = true;
    private static int tickCounter;

    private TeamSyncClient() {}

    // ------------------------------------------------------------------ sending

    private static boolean serverSupportsSync()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.getConnection().hasChannel(TeamSyncPayload.TYPE);
    }

    private static boolean isInTeam()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getTeam() != null;
    }

    /** Called after a placement was created by the player. Shares it silently if the player is in a team. */
    public static void shareIfInTeam(SchematicPlacement placement)
    {
        if (isInTeam() && serverSupportsSync())
        {
            share(placement);
        }
    }

    public static int shareSelected()
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement == null)
        {
            SchematicCommand.error("Keine Schematic ausgewählt.");
            return 0;
        }

        if (!serverSupportsSync())
        {
            SchematicCommand.error("Der Server hat die Forgematica-TeamSync-Mod nicht installiert.");
            return 0;
        }

        if (!isInTeam())
        {
            SchematicCommand.error("Du bist in keinem Team. Ein Admin kann dich mit /team join <Team> <Name> hinzufügen.");
            return 0;
        }

        return share(placement) ? 1 : 0;
    }

    public static int unshareSelected()
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        OwnShare share = placement != null ? OWN.remove(placement) : null;

        if (share == null)
        {
            SchematicCommand.error("Die ausgewählte Schematic wird nicht geteilt.");
            return 0;
        }

        sendRemove(share.id);
        SchematicCommand.info("Teilen beendet. Dein Team sieht diese Schematic nicht mehr.");
        return 1;
    }

    public static int setReceiveEnabled(boolean enabled)
    {
        receiveEnabled = enabled;
        SchematicCommand.info(enabled ? "Geteilte Schematics vom Team werden angezeigt."
                                      : "Geteilte Schematics vom Team werden ignoriert.");
        return 1;
    }

    private static boolean share(SchematicPlacement placement)
    {
        Path file = placement.getSchematicFile();

        if (file == null || !Files.isRegularFile(file))
        {
            SchematicCommand.error("Diese Schematic hat keine Datei und kann nicht geteilt werden.");
            return false;
        }

        byte[] bytes;

        try
        {
            bytes = Files.readAllBytes(file);
        }
        catch (Exception e)
        {
            SchematicCommand.error("Datei konnte nicht gelesen werden: " + e.getMessage());
            return false;
        }

        int chunkSize = TeamSyncPayload.MAX_CHUNK_BYTES;
        int count = Math.max(1, (bytes.length + chunkSize - 1) / chunkSize);

        if (count > TeamSyncPayload.MAX_CHUNKS)
        {
            SchematicCommand.error("Die Datei ist zu groß zum Teilen (max. 30 MB).");
            return false;
        }

        OwnShare existing = OWN.get(placement);
        UUID id = existing != null ? existing.id : UUID.randomUUID();
        OwnShare state = OwnShare.of(id, placement);
        OWN.put(placement, state);

        String fileName = file.getFileName().toString();

        for (int i = 0; i < count; i++)
        {
            int from = i * chunkSize;
            int to = Math.min(bytes.length, from + chunkSize);
            byte[] part = Arrays.copyOfRange(bytes, from, to);
            ClientPacketDistributor.sendToServer(new TeamSyncPayload(TeamSyncPayload.ACTION_CHUNK, id, "", fileName,
                    state.origin.getX(), state.origin.getY(), state.origin.getZ(),
                    (byte) state.rotation.ordinal(), (byte) state.mirror.ordinal(), i, count, part));
        }

        return true;
    }

    private static void sendMove(OwnShare state)
    {
        ClientPacketDistributor.sendToServer(new TeamSyncPayload(TeamSyncPayload.ACTION_MOVE, state.id, "", "",
                state.origin.getX(), state.origin.getY(), state.origin.getZ(),
                (byte) state.rotation.ordinal(), (byte) state.mirror.ordinal(), 0, 0, new byte[0]));
    }

    private static void sendRemove(UUID id)
    {
        if (serverSupportsSync())
        {
            ClientPacketDistributor.sendToServer(new TeamSyncPayload(TeamSyncPayload.ACTION_REMOVE, id, "", "",
                    0, 0, 0, (byte) 0, (byte) 0, 0, 0, new byte[0]));
        }
    }

    /**
     * Checks own shared placements twice per second: sends position updates when moved/rotated,
     * and a remove when the placement was deleted.
     */
    public static void onClientTick(ClientTickEvent.Post event)
    {
        if (OWN.isEmpty() || ++tickCounter < 10)
        {
            return;
        }

        tickCounter = 0;

        if (Minecraft.getInstance().getConnection() == null)
        {
            OWN.clear();
            RECEIVED.clear();
            TRANSFERS.clear();
            return;
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        Iterator<Map.Entry<SchematicPlacement, OwnShare>> it = OWN.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<SchematicPlacement, OwnShare> entry = it.next();
            SchematicPlacement placement = entry.getKey();

            if (!manager.getAllSchematicsPlacements().contains(placement))
            {
                sendRemove(entry.getValue().id);
                it.remove();
                continue;
            }

            OwnShare now = OwnShare.of(entry.getValue().id, placement);

            if (!now.samePlacement(entry.getValue()))
            {
                entry.setValue(now);
                sendMove(now);
            }
        }
    }

    // ------------------------------------------------------------------ receiving

    public static void handle(TeamSyncPayload payload, IPayloadContext ctx)
    {
        if (!receiveEnabled)
        {
            return;
        }

        try
        {
            switch (payload.action())
            {
                case TeamSyncPayload.ACTION_CHUNK -> receiveChunk(payload);
                case TeamSyncPayload.ACTION_MOVE -> receiveMove(payload);
                case TeamSyncPayload.ACTION_REMOVE -> receiveRemove(payload);
                default -> {}
            }
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("TeamSync: failed to handle packet: {}", e.toString());
        }
    }

    private static void receiveChunk(TeamSyncPayload p)
    {
        if (p.count() <= 0 || p.count() > TeamSyncPayload.MAX_CHUNKS || p.index() >= p.count())
        {
            return;
        }

        Transfer t = TRANSFERS.get(p.shareId());

        if (t == null || t.parts.length != p.count() || p.index() == 0)
        {
            t = new Transfer(p.count());
            TRANSFERS.put(p.shareId(), t);
        }

        if (t.parts[p.index()] == null)
        {
            t.parts[p.index()] = p.data();
            t.received++;
        }

        if (t.received < t.parts.length)
        {
            return;
        }

        TRANSFERS.remove(p.shareId());

        int total = 0;
        for (byte[] part : t.parts) total += part.length;
        byte[] bytes = new byte[total];
        int off = 0;
        for (byte[] part : t.parts)
        {
            System.arraycopy(part, 0, bytes, off, part.length);
            off += part.length;
        }

        Path dir = SchematicCommand.baseDir().resolve("team").resolve(safe(p.sender()));
        String fileName = safe(p.fileName());
        Path file = dir.resolve(fileName).normalize();

        if (!file.startsWith(SchematicCommand.baseDir()))
        {
            return;
        }

        try
        {
            Files.createDirectories(dir);
            Files.write(file, bytes);
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("TeamSync: could not save {}: {}", file, e.toString());
            return;
        }

        LitematicaSchematic schematic = SchematicCommand.readSchematic(file);

        if (schematic == null)
        {
            SchematicCommand.error(p.sender() + " hat eine Schematic geteilt, die nicht gelesen werden konnte.");
            return;
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement old = RECEIVED.remove(p.shareId());

        if (old != null)
        {
            manager.removeSchematicPlacement(old);
        }

        SchematicHolder.getInstance().addSchematic(schematic, true);
        BlockPos origin = new BlockPos(p.x(), p.y(), p.z());
        String name = schematic.getMetadata().getName() + " (" + p.sender() + ")";
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, name, true, true);
        manager.addSchematicPlacement(placement, true);
        applyTransform(placement, p);
        manager.setSelectedSchematicPlacement(placement);
        RECEIVED.put(p.shareId(), placement);

        SchematicCommand.showMaterialListHud(placement);
        SchematicCommand.msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(p.sender() + " hat \"" + fileName + "\" mit dem Team geteilt (bei "
                        + p.x() + " " + p.y() + " " + p.z() + ").").withStyle(ChatFormatting.GREEN)));
    }

    private static void receiveMove(TeamSyncPayload p)
    {
        SchematicPlacement placement = RECEIVED.get(p.shareId());

        if (placement != null)
        {
            placement.setOrigin(new BlockPos(p.x(), p.y(), p.z()), s -> {});
            applyTransform(placement, p);
        }
    }

    private static void receiveRemove(TeamSyncPayload p)
    {
        SchematicPlacement placement = RECEIVED.remove(p.shareId());
        TRANSFERS.remove(p.shareId());

        if (placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
            SchematicCommand.info(p.sender() + " teilt \"" + placement.getName() + "\" nicht mehr.");
        }
    }

    private static void applyTransform(SchematicPlacement placement, TeamSyncPayload p)
    {
        Rotation[] rotations = Rotation.values();
        Mirror[] mirrors = Mirror.values();

        if (p.rotation() >= 0 && p.rotation() < rotations.length)
        {
            placement.setRotation(rotations[p.rotation()], NO_FEEDBACK);
        }

        if (p.mirror() >= 0 && p.mirror() < mirrors.length)
        {
            placement.setMirror(mirrors[p.mirror()], NO_FEEDBACK);
        }
    }

    private static String safe(String s)
    {
        String out = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();

        while (out.startsWith("."))
        {
            out = out.substring(1);
        }

        return out.isEmpty() ? "unbenannt" : out;
    }

    // ------------------------------------------------------------------ data

    private record OwnShare(UUID id, BlockPos origin, Rotation rotation, Mirror mirror)
    {
        static OwnShare of(UUID id, SchematicPlacement p)
        {
            return new OwnShare(id, p.getOrigin(), p.getRotation(), p.getMirror());
        }

        boolean samePlacement(OwnShare o)
        {
            return this.origin.equals(o.origin) && this.rotation == o.rotation && this.mirror == o.mirror;
        }
    }

    private static final class Transfer
    {
        final byte[][] parts;
        int received;

        Transfer(int count)
        {
            this.parts = new byte[count][];
        }
    }
}
