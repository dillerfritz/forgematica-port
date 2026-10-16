package team.cagayakegirls.forgematicasync;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One packet of the team schematic sync.
 * <p>
 * MUST stay byte-for-byte compatible with the copy in Forgematica
 * (team.cagayakegirls.forgematica.teamsync.TeamSyncPayload in Forgematica).
 *
 * @param action    ACTION_CHUNK (file data + placement), ACTION_MOVE (placement only) or ACTION_REMOVE
 * @param shareId   stable id of the shared placement
 * @param sender    name of the sharing player (filled in by the server when relaying)
 * @param fileName  schematic file name
 * @param x         placement origin
 * @param rotation  Rotation ordinal
 * @param mirror    Mirror ordinal
 * @param index     chunk index (ACTION_CHUNK)
 * @param count     total chunk count (ACTION_CHUNK)
 * @param data      chunk bytes (ACTION_CHUNK)
 */
public record TeamSyncPayload(byte action, UUID shareId, String sender, String fileName,
                              int x, int y, int z, byte rotation, byte mirror,
                              int index, int count, byte[] data) implements CustomPacketPayload
{
    public static final byte ACTION_CHUNK = 0;
    public static final byte ACTION_MOVE = 1;
    public static final byte ACTION_REMOVE = 2;

    public static final int MAX_CHUNK_BYTES = 30000;
    public static final int MAX_CHUNKS = 1000; // ~30 MB
    public static final String PROTOCOL_VERSION = "1";

    public static final Type<TeamSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("forgematica", "team_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TeamSyncPayload> CODEC =
            StreamCodec.ofMember(TeamSyncPayload::write, TeamSyncPayload::read);

    private void write(RegistryFriendlyByteBuf buf)
    {
        buf.writeByte(this.action);
        buf.writeUUID(this.shareId);
        buf.writeUtf(this.sender, 64);
        buf.writeUtf(this.fileName, 256);
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        buf.writeByte(this.rotation);
        buf.writeByte(this.mirror);
        buf.writeVarInt(this.index);
        buf.writeVarInt(this.count);
        buf.writeByteArray(this.data);
    }

    private static TeamSyncPayload read(RegistryFriendlyByteBuf buf)
    {
        return new TeamSyncPayload(
                buf.readByte(),
                buf.readUUID(),
                buf.readUtf(64),
                buf.readUtf(256),
                buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readByte(), buf.readByte(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readByteArray(MAX_CHUNK_BYTES));
    }

    public TeamSyncPayload withSender(String newSender)
    {
        return new TeamSyncPayload(this.action, this.shareId, newSender, this.fileName, this.x, this.y, this.z,
                this.rotation, this.mirror, this.index, this.count, this.data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}
