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
    /** Received but not yet accepted: shareId -> offer */
    private static final Map<UUID, Offer> OFFERS = new java.util.LinkedHashMap<>();
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
            SchematicCommand.error("Auf dem Server fehlt Forgematica TeamSync (Paper: Plugin in den plugins-Ordner, NeoForge: Server-Mod in den mods-Ordner).");
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
        ownIds().values().remove(share.id);
        saveOwnIds();
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
        ownIds().put(ownKey(placement), id);
        saveOwnIds();

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
        if (++tickCounter < 10)
        {
            return;
        }

        tickCounter = 0;

        if (Minecraft.getInstance().getConnection() == null)
        {
            OWN.clear();
            RECEIVED.clear();
            TRANSFERS.clear();
            OFFERS.clear();
            return;
        }

        restoreOwnShares();

        if (OWN.isEmpty())
        {
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
                ownIds().values().remove(entry.getValue().id);
                saveOwnIds();
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

        Offer offer = new Offer(p.shareId(), p.sender(), fileName, file, p.x(), p.y(), p.z(), p.rotation(), p.mirror());

        // Already accepted earlier (also after a relog): update silently
        if (RECEIVED.containsKey(p.shareId()) || isAcceptedBefore(p.shareId()))
        {
            apply(offer, false);
            return;
        }

        OFFERS.put(p.shareId(), offer);
        notifyOffer(offer);
    }

    private static void notifyOffer(Offer offer)
    {
        Minecraft mc = Minecraft.getInstance();
        String key = team.cagayakegirls.forgematica.ux.FmKeys.OPEN_MENU.getTranslatedKeyMessage().getString();

        SchematicCommand.msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(offer.sender + " möchte \"" + offer.fileName + "\" mit dir teilen. ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("Annehmen: Taste " + key + " → \"Team-Angebote\"  oder  /schematic accept").withStyle(ChatFormatting.YELLOW)));

        if (mc.player != null)
        {
            mc.player.sendOverlayMessage(Component.literal("📨 Neues Team-Angebot von " + offer.sender + " – Taste " + key + " drücken")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** Turns an offer into a (locked) placement. */
    private static void apply(Offer offer, boolean announce)
    {
        LitematicaSchematic schematic = SchematicCommand.readSchematic(offer.file);

        if (schematic == null)
        {
            SchematicCommand.error(offer.sender + " hat eine Schematic geteilt, die nicht gelesen werden konnte.");
            return;
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement old = RECEIVED.remove(offer.id);

        if (old != null)
        {
            manager.removeSchematicPlacement(old);
        }

        // Placements restored by Litematica after a relog point to the same team file: replace them
        for (SchematicPlacement existing : new java.util.ArrayList<>(manager.getAllSchematicsPlacements()))
        {
            if (offer.file.equals(existing.getSchematicFile()))
            {
                manager.removeSchematicPlacement(existing);
            }
        }

        SchematicHolder.getInstance().addSchematic(schematic, true);
        String name = schematic.getMetadata().getName() + " (" + offer.sender + ")";
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, new BlockPos(offer.x, offer.y, offer.z), name, true, true);
        manager.addSchematicPlacement(placement, true);
        applyTransform(placement, offer.rotation, offer.mirror);
        team.cagayakegirls.forgematica.ux.MoveMode.setLocked(placement, true);
        RECEIVED.put(offer.id, placement);
        rememberAccepted(offer.id);

        if (announce)
        {
            manager.setSelectedSchematicPlacement(placement);
            SchematicCommand.showMaterialListHud(placement);
            SchematicCommand.msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\"" + offer.fileName + "\" von " + offer.sender + " angenommen (bei "
                            + offer.x + " " + offer.y + " " + offer.z + ").").withStyle(ChatFormatting.GREEN)));
        }
    }

    // ------------------------------------------------------------------ offers API (menu / commands)

    public static java.util.List<Offer> getOffers()
    {
        return new java.util.ArrayList<>(OFFERS.values());
    }

    public static int getOfferCount()
    {
        return OFFERS.size();
    }

    public static void accept(UUID id)
    {
        Offer offer = OFFERS.remove(id);

        if (offer != null)
        {
            apply(offer, true);
        }
    }

    public static void decline(UUID id)
    {
        Offer offer = OFFERS.remove(id);

        if (offer != null)
        {
            SchematicCommand.info("Angebot \"" + offer.fileName + "\" von " + offer.sender + " abgelehnt.");
        }
    }

    public static int acceptAll()
    {
        if (OFFERS.isEmpty())
        {
            SchematicCommand.info("Keine offenen Team-Angebote.");
            return 0;
        }

        for (UUID id : new java.util.ArrayList<>(OFFERS.keySet()))
        {
            accept(id);
        }

        return 1;
    }

    public static int declineAll()
    {
        if (OFFERS.isEmpty())
        {
            SchematicCommand.info("Keine offenen Team-Angebote.");
            return 0;
        }

        for (UUID id : new java.util.ArrayList<>(OFFERS.keySet()))
        {
            decline(id);
        }

        return 1;
    }

    /** Hides a received schematic again (it can be re-offered by the owner). */
    public static void forget(SchematicPlacement placement)
    {
        RECEIVED.values().remove(placement);
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
    }

    public static boolean isReceived(SchematicPlacement placement)
    {
        return RECEIVED.containsValue(placement);
    }

    public static boolean isShared(SchematicPlacement placement)
    {
        return OWN.containsKey(placement);
    }

    // ------------------------------------------------------------------ persisted own share ids (survive relogs)

    private static Map<String, UUID> ownIds;

    private static String ownKey(SchematicPlacement p)
    {
        return String.valueOf(p.getSchematicFile()) + "|" + p.getName();
    }

    private static Path ownFile()
    {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("forgematica-team-own.txt");
    }

    private static Map<String, UUID> ownIds()
    {
        if (ownIds == null)
        {
            ownIds = new java.util.LinkedHashMap<>();

            try
            {
                if (Files.exists(ownFile()))
                {
                    for (String line : Files.readAllLines(ownFile()))
                    {
                        int tab = line.indexOf('\t');
                        if (tab > 0)
                        {
                            try { ownIds.put(line.substring(tab + 1), UUID.fromString(line.substring(0, tab))); }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Litematica.LOGGER.warn("TeamSync: could not read own share list: {}", e.toString());
            }
        }

        return ownIds;
    }

    private static void saveOwnIds()
    {
        try
        {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (Map.Entry<String, UUID> e : ownIds().entrySet()) lines.add(e.getValue() + "\t" + e.getKey());
            Files.write(ownFile(), lines);
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("TeamSync: could not save own share list: {}", e.toString());
        }
    }

    /** After a relog Litematica restores the placements: pick up the ones that were shared before. */
    private static void restoreOwnShares()
    {
        if (ownIds().isEmpty() || !serverSupportsSync())
        {
            return;
        }

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (OWN.containsKey(placement) || RECEIVED.containsValue(placement))
            {
                continue;
            }

            UUID id = ownIds().get(ownKey(placement));

            if (id != null)
            {
                OwnShare state = OwnShare.of(id, placement);
                OWN.put(placement, state);
                sendMove(state);
            }
        }
    }

    // ------------------------------------------------------------------ persisted "accepted" ids

    private static java.util.Set<UUID> acceptedIds;

    private static Path acceptedFile()
    {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("forgematica-team-accepted.txt");
    }

    private static java.util.Set<UUID> accepted()
    {
        if (acceptedIds == null)
        {
            acceptedIds = new java.util.LinkedHashSet<>();

            try
            {
                if (Files.exists(acceptedFile()))
                {
                    for (String line : Files.readAllLines(acceptedFile()))
                    {
                        try { acceptedIds.add(UUID.fromString(line.trim())); } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
            catch (Exception e)
            {
                Litematica.LOGGER.warn("TeamSync: could not read accepted list: {}", e.toString());
            }
        }

        return acceptedIds;
    }

    private static boolean isAcceptedBefore(UUID id)
    {
        return accepted().contains(id);
    }

    private static void rememberAccepted(UUID id)
    {
        if (accepted().add(id))
        {
            try
            {
                java.util.List<String> lines = new java.util.ArrayList<>();
                // keep the file small
                java.util.List<UUID> all = new java.util.ArrayList<>(accepted());
                for (UUID u : all.subList(Math.max(0, all.size() - 500), all.size())) lines.add(u.toString());
                Files.write(acceptedFile(), lines);
            }
            catch (Exception e)
            {
                Litematica.LOGGER.warn("TeamSync: could not save accepted list: {}", e.toString());
            }
        }
    }

    private static void forgetAccepted(UUID id)
    {
        if (accepted().remove(id))
        {
            try
            {
                java.util.List<String> lines = new java.util.ArrayList<>();
                for (UUID u : accepted()) lines.add(u.toString());
                Files.write(acceptedFile(), lines);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private static void receiveMove(TeamSyncPayload p)
    {
        Offer offer = OFFERS.get(p.shareId());

        if (offer != null)
        {
            offer.x = p.x(); offer.y = p.y(); offer.z = p.z();
            offer.rotation = p.rotation(); offer.mirror = p.mirror();
        }

        SchematicPlacement placement = RECEIVED.get(p.shareId());

        if (placement != null)
        {
            team.cagayakegirls.forgematica.ux.MoveMode.withUnlocked(placement, () -> {
                placement.setOrigin(new BlockPos(p.x(), p.y(), p.z()), s -> {});
                applyTransform(placement, p.rotation(), p.mirror());
            });
        }
    }

    private static void receiveRemove(TeamSyncPayload p)
    {
        Offer offer = OFFERS.remove(p.shareId());
        SchematicPlacement placement = RECEIVED.remove(p.shareId());
        TRANSFERS.remove(p.shareId());
        forgetAccepted(p.shareId());

        if (placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
            SchematicCommand.info(p.sender() + " teilt \"" + placement.getName() + "\" nicht mehr.");
        }
        else if (offer != null)
        {
            SchematicCommand.info(p.sender() + " hat das Angebot \"" + offer.fileName + "\" zurückgezogen.");
        }
    }

    private static void applyTransform(SchematicPlacement placement, byte rotation, byte mirror)
    {
        Rotation[] rotations = Rotation.values();
        Mirror[] mirrors = Mirror.values();

        if (rotation >= 0 && rotation < rotations.length)
        {
            placement.setRotation(rotations[rotation], NO_FEEDBACK);
        }

        if (mirror >= 0 && mirror < mirrors.length)
        {
            placement.setMirror(mirrors[mirror], NO_FEEDBACK);
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

    public static final class Offer
    {
        public final UUID id;
        public final String sender;
        public final String fileName;
        final Path file;
        public int x, y, z;
        byte rotation, mirror;

        Offer(UUID id, String sender, String fileName, Path file, int x, int y, int z, byte rotation, byte mirror)
        {
            this.id = id; this.sender = sender; this.fileName = fileName; this.file = file;
            this.x = x; this.y = y; this.z = z; this.rotation = rotation; this.mirror = mirror;
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
