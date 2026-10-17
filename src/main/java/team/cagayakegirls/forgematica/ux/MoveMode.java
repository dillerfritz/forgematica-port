package team.cagayakegirls.forgematica.ux;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import team.cagayakegirls.forgematica.teamsync.TeamSyncClient;

/**
 * "Verschieben-Modus": the selected placement can only be moved while this mode is on.
 * Outside of it, own placements are locked so they can't be moved by accident.
 */
public final class MoveMode
{
    private static final IMessageConsumer SILENT = new IMessageConsumer()
    {
        @Override public void addMessage(MessageType type, String messageKey, Object... args) {}
        @Override public void addMessage(MessageType type, int lifeTime, String messageKey, Object... args) {}
    };

    private static SchematicPlacement active;
    private static BlockPos startOrigin;
    private static Rotation startRotation;
    private static Mirror startMirror;
    private static int hintTicks;

    private static final KeyMapping[] REPEAT_KEYS = {
            FmKeys.MOVE_FORWARD, FmKeys.MOVE_BACK, FmKeys.MOVE_LEFT, FmKeys.MOVE_RIGHT, FmKeys.MOVE_UP, FmKeys.MOVE_DOWN
    };
    private static final int[] HELD = new int[REPEAT_KEYS.length];

    private MoveMode() {}

    public static boolean isActive()
    {
        return active != null;
    }

    /** Position in front of the player: the block face looked at, or 3 blocks ahead. */
    public static BlockPos targetPosition()
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK)
        {
            return bhr.getBlockPos().relative(bhr.getDirection());
        }

        return mc.player.blockPosition().relative(mc.player.getDirection(), 3);
    }

    public static void toggle()
    {
        if (active != null)
        {
            confirm();
            return;
        }

        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement == null)
        {
            SchematicCommand.error("Keine Schematic ausgewählt. Erst laden (Menü → Schematic laden).");
            return;
        }

        start(placement);
    }

    public static void start(SchematicPlacement placement)
    {
        if (TeamSyncClient.isReceived(placement))
        {
            SchematicCommand.error("Diese Schematic gehört einem Teammitglied – nur der Besitzer kann sie verschieben.");
            return;
        }

        if (active != null && active != placement)
        {
            confirm();
        }

        active = placement;
        startOrigin = placement.getOrigin();
        startRotation = placement.getRotation();
        startMirror = placement.getMirror();
        setLocked(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        hintTicks = 0;
        showHint();
    }

    public static void confirm()
    {
        if (active == null)
        {
            return;
        }

        setLocked(active, true);
        msgBar(Component.literal("✔ Position gespeichert – Schematic ist gesperrt").withStyle(ChatFormatting.GREEN));
        active = null;
    }

    public static void cancel()
    {
        if (active == null)
        {
            return;
        }

        active.setOrigin(startOrigin, s -> {});
        active.setRotation(startRotation, SILENT);
        active.setMirror(startMirror, SILENT);
        setLocked(active, true);
        msgBar(Component.literal("✖ Verschieben abgebrochen").withStyle(ChatFormatting.YELLOW));
        active = null;
    }

    /**
     * Rotates by 90° around the middle of the schematic (Litematica itself rotates around the corner,
     * which makes the build jump away).
     */
    public static void rotateAroundCenter(SchematicPlacement placement, Rotation delta)
    {
        fi.dy.masa.litematica.selection.Box before = placement.getEclosingBox();
        BlockPos c1 = before != null ? center(before) : null;

        placement.setRotation(placement.getRotation().getRotated(delta), SILENT);

        fi.dy.masa.litematica.selection.Box after = placement.getEclosingBox();

        if (c1 != null && after != null)
        {
            BlockPos c2 = center(after);
            placement.setOrigin(placement.getOrigin().offset(c1.getX() - c2.getX(), 0, c1.getZ() - c2.getZ()), s -> {});
        }
    }

    /** Rotate the selected own schematic (menu / command), also when it is locked. */
    public static void rotateSelected(Rotation delta)
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement == null)
        {
            SchematicCommand.error("Keine Schematic ausgewählt.");
            return;
        }

        if (TeamSyncClient.isReceived(placement))
        {
            SchematicCommand.error("Diese Schematic gehört einem Teammitglied – nur der Besitzer kann sie drehen.");
            return;
        }

        withUnlocked(placement, () -> rotateAroundCenter(placement, delta));
        msgBar(Component.literal("Gedreht ↻ " + rotationLabel(placement.getRotation())).withStyle(ChatFormatting.GOLD));
    }

    private static String rotationLabel(Rotation r)
    {
        return switch (r)
        {
            case NONE -> "0°";
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
        };
    }

    private static BlockPos center(fi.dy.masa.litematica.selection.Box box)
    {
        BlockPos a = box.getPos1();
        BlockPos b = box.getPos2();
        return new BlockPos(Math.floorDiv(a.getX() + b.getX(), 2), Math.floorDiv(a.getY() + b.getY(), 2), Math.floorDiv(a.getZ() + b.getZ(), 2));
    }

    /** Lock state helper (Litematica only has toggleLocked). */
    public static void setLocked(SchematicPlacement placement, boolean locked)
    {
        if (placement.isLocked() != locked)
        {
            placement.toggleLocked();
        }
    }

    /** Runs an action on a placement that may be locked, restoring the lock afterwards. */
    public static void withUnlocked(SchematicPlacement placement, Runnable action)
    {
        boolean wasLocked = placement.isLocked();
        setLocked(placement, false);

        try
        {
            action.run();
        }
        finally
        {
            setLocked(placement, wasLocked);
        }
    }

    static void tickNoInput()
    {
        for (KeyMapping key : REPEAT_KEYS) while (key.consumeClick()) {}
        for (int i = 0; i < HELD.length; i++) HELD[i] = 0;
        drain(FmKeys.ROTATE, FmKeys.ROTATE_LEFT, FmKeys.MIRROR, FmKeys.CONFIRM, FmKeys.CANCEL);
    }

    static void tick()
    {
        if (active == null)
        {
            tickNoInput();
            return;
        }

        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(active))
        {
            active = null;
            tickNoInput();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Direction facing = mc.player.getDirection();
        // 1 block per press; 10 only while the dedicated fast key is held (not the sprint key, which may be toggled on)
        int step = FmKeys.MOVE_FAST.isDown() ? 10 : 1;

        Direction[] dirs = {
                facing, facing.getOpposite(), facing.getCounterClockWise(), facing.getClockWise(), Direction.UP, Direction.DOWN
        };

        for (int i = 0; i < REPEAT_KEYS.length; i++)
        {
            KeyMapping key = REPEAT_KEYS[i];
            int presses = 0;

            while (key.consumeClick())
            {
                presses++;
            }

            if (key.isDown())
            {
                HELD[i]++;

                // Auto-repeat after holding ~0.5 s, then every 3 ticks
                if (presses == 0 && HELD[i] > 10 && HELD[i] % 3 == 0)
                {
                    presses = 1;
                }
            }
            else
            {
                HELD[i] = 0;
            }

            if (presses > 0)
            {
                BlockPos origin = active.getOrigin().relative(dirs[i], step * presses);
                active.setOrigin(origin, s -> {});
            }
        }

        while (FmKeys.ROTATE_LEFT.consumeClick())
        {
            rotateAroundCenter(active, Rotation.COUNTERCLOCKWISE_90);
            drain(FmKeys.ROTATE);
        }

        while (FmKeys.ROTATE.consumeClick())
        {
            rotateAroundCenter(active, fi.dy.masa.malilib.gui.GuiBase.isShiftDown() ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90);
        }

        while (FmKeys.MIRROR.consumeClick())
        {
            Mirror next = switch (active.getMirror())
            {
                case NONE -> Mirror.LEFT_RIGHT;
                case LEFT_RIGHT -> Mirror.FRONT_BACK;
                default -> Mirror.NONE;
            };
            active.setMirror(next, SILENT);
        }

        while (FmKeys.CONFIRM.consumeClick())
        {
            confirm();
            return;
        }

        while (FmKeys.CANCEL.consumeClick())
        {
            cancel();
            return;
        }

        if (++hintTicks >= 30)
        {
            hintTicks = 0;
            showHint();
        }
    }

    private static void showHint()
    {
        BlockPos o = active.getOrigin();
        msgBar(Component.literal("Verschieben: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(key(FmKeys.MOVE_FORWARD) + key(FmKeys.MOVE_BACK) + key(FmKeys.MOVE_LEFT) + key(FmKeys.MOVE_RIGHT)
                        + " bewegen · " + key(FmKeys.MOVE_UP) + "/" + key(FmKeys.MOVE_DOWN) + " hoch/runter · "
                        + key(FmKeys.ROTATE) + " drehen (Shift+" + key(FmKeys.ROTATE) + " links) · " + key(FmKeys.MOVE_FAST) + " halten = 10er · " + key(FmKeys.CONFIRM) + " fertig · " + key(FmKeys.CANCEL) + " abbrechen"
                        + "  [" + o.getX() + " " + o.getY() + " " + o.getZ() + "]").withStyle(ChatFormatting.WHITE)));
    }

    private static String key(KeyMapping key)
    {
        return key.isUnbound() ? "–" : key.getTranslatedKeyMessage().getString();
    }

    private static void drain(KeyMapping... keys)
    {
        for (KeyMapping key : keys) while (key.consumeClick()) {}
    }

    private static void msgBar(Component c)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null)
        {
            mc.player.sendOverlayMessage(c);
        }
    }
}
