package fi.dy.masa.litematica.command;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;

/**
 * Client-side /schematic command (Forgematica 26.3 port addition).
 *
 * /schematic download <link>   - downloads a schematic file into the schematics folder
 * /schematic list              - lists the schematic files in the schematics folder
 * /schematic load <name>       - loads a schematic, places it at the player and shows the material list
 * /schematic materials         - shows / hides the material list of the selected placement
 * /schematic help              - shows the help
 */
public class SchematicCommand
{
    private static final String[] EXTENSIONS = { ".litematic", ".schem", ".schematic", ".nbt" };
    private static final long MAX_DOWNLOAD_BYTES = 100L * 1024L * 1024L; // 100 MB
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static final SuggestionProvider<CommandSourceStack> FILE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(listSchematicNames(), builder);

    public static void onRegisterCommands(RegisterClientCommandsEvent event)
    {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("schematic")
                .executes(SchematicCommand::help)
                .then(Commands.literal("help").executes(SchematicCommand::help))
                .then(Commands.literal("list").executes(SchematicCommand::list))
                .then(Commands.literal("materials").executes(SchematicCommand::materials))
                .then(Commands.literal("performance").executes(ctx -> team.cagayakegirls.forgematica.PerformancePreset.applyCommand()))
                .then(Commands.literal("share").executes(ctx -> team.cagayakegirls.forgematica.teamsync.TeamSyncClient.shareSelected()))
                .then(Commands.literal("accept").executes(ctx -> team.cagayakegirls.forgematica.teamsync.TeamSyncClient.acceptAll()))
                .then(Commands.literal("decline").executes(ctx -> team.cagayakegirls.forgematica.teamsync.TeamSyncClient.declineAll()))
                .then(Commands.literal("rotate")
                        .executes(ctx -> { team.cagayakegirls.forgematica.ux.MoveMode.rotateSelected(net.minecraft.world.level.block.Rotation.CLOCKWISE_90); return 1; })
                        .then(Commands.literal("right").executes(ctx -> { team.cagayakegirls.forgematica.ux.MoveMode.rotateSelected(net.minecraft.world.level.block.Rotation.CLOCKWISE_90); return 1; }))
                        .then(Commands.literal("left").executes(ctx -> { team.cagayakegirls.forgematica.ux.MoveMode.rotateSelected(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90); return 1; })))
                .then(Commands.literal("move").executes(ctx -> { team.cagayakegirls.forgematica.ux.MoveMode.toggle(); return 1; }))
                .then(Commands.literal("menu").executes(ctx -> { Minecraft.getInstance().execute(() -> fi.dy.masa.malilib.gui.GuiBase.openGui(new team.cagayakegirls.forgematica.ux.SimpleMenu())); return 1; }))
                .then(Commands.literal("unshare").executes(ctx -> team.cagayakegirls.forgematica.teamsync.TeamSyncClient.unshareSelected()))
                .then(Commands.literal("teamsync")
                        .then(Commands.literal("on").executes(ctx -> team.cagayakegirls.forgematica.teamsync.TeamSyncClient.setReceiveEnabled(true)))
                        .then(Commands.literal("off").executes(ctx -> team.cagayakegirls.forgematica.teamsync.TeamSyncClient.setReceiveEnabled(false))))
                .then(Commands.literal("download")
                        .then(Commands.argument("link", StringArgumentType.greedyString())
                                .executes(SchematicCommand::download)))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests(FILE_SUGGESTIONS)
                                .executes(SchematicCommand::load))));
    }

    // ------------------------------------------------------------------ help / list

    private static int help(CommandContext<CommandSourceStack> ctx)
    {
        info("Forgematica-Befehle:");
        msg(Component.literal("/schematic download <Link>").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - lädt eine Schematic-Datei in den schematics-Ordner").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic list").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - zeigt alle Dateien im schematics-Ordner").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic load <Name>").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - lädt die Schematic an deiner Position + Materialliste").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic materials").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - Materialliste ein-/ausblenden").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic share").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - ausgewählte Schematic (erneut) mit deinem Team teilen").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic accept | decline").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - alle Team-Angebote annehmen / ablehnen").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic rotate [left|right]").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - ausgewählte Schematic um 90° drehen").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic move").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - ausgewählte Schematic verschieben (wie Taste)").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic unshare").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - Teilen beenden, Team sieht sie nicht mehr").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic teamsync on|off").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - geteilte Schematics vom Team empfangen an/aus").withStyle(ChatFormatting.GRAY)));
        msg(Component.literal("/schematic performance").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - Einstellungen für flüssiges Spielen setzen").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx)
    {
        List<String> names = listSchematicNames();

        if (names.isEmpty())
        {
            info("Der schematics-Ordner ist leer. Lade mit /schematic download <Link> eine Datei herunter.");
            return 0;
        }

        info("Schematics (" + names.size() + "):");

        for (String name : names)
        {
            msg(Component.literal(" - " + name).withStyle(ChatFormatting.AQUA));
        }

        return names.size();
    }

    // ------------------------------------------------------------------ download

    private static int download(CommandContext<CommandSourceStack> ctx)
    {
        String link = StringArgumentType.getString(ctx, "link").trim();
        URI uri;

        try
        {
            uri = URI.create(link);
        }
        catch (IllegalArgumentException e)
        {
            error("Das ist kein gültiger Link.");
            return 0;
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!scheme.equals("https") && !scheme.equals("http"))
        {
            error("Der Link muss mit https:// beginnen.");
            return 0;
        }

        String fileName = fileNameFromPath(uri.getPath());

        if (fileName == null)
        {
            error("Der Link muss direkt auf eine Datei mit .litematic, .schem, .schematic oder .nbt zeigen.");
            return 0;
        }

        info("Lade " + fileName + " herunter ...");

        CompletableFuture.runAsync(() -> {
            try
            {
                Path dir = baseDir();
                Path target = uniqueTarget(dir, fileName);
                Path temp = dir.resolve(target.getFileName().toString() + ".part");

                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "Forgematica-26.3")
                        .GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200)
                {
                    response.body().close();
                    onMain(() -> error("Download fehlgeschlagen (HTTP " + response.statusCode() + ")."));
                    return;
                }

                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

                if (length > MAX_DOWNLOAD_BYTES)
                {
                    response.body().close();
                    onMain(() -> error("Die Datei ist zu groß (max. 100 MB)."));
                    return;
                }

                long written = 0;

                try (InputStream in = response.body(); var out = Files.newOutputStream(temp))
                {
                    byte[] buf = new byte[64 * 1024];
                    int n;

                    while ((n = in.read(buf)) != -1)
                    {
                        written += n;

                        if (written > MAX_DOWNLOAD_BYTES)
                        {
                            throw new IOException("too large");
                        }

                        out.write(buf, 0, n);
                    }
                }
                catch (IOException e)
                {
                    Files.deleteIfExists(temp);
                    throw e;
                }

                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                String savedName = target.getFileName().toString();
                long kb = Math.max(1, written / 1024);

                onMain(() -> {
                    msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("Gespeichert: " + savedName + " (" + kb + " KB). ").withStyle(ChatFormatting.GREEN))
                            .append(Component.literal("Laden mit: /schematic load " + savedName).withStyle(ChatFormatting.YELLOW)));
                });
            }
            catch (Exception e)
            {
                Litematica.LOGGER.warn("Schematic download failed: {}", e.toString());
                onMain(() -> error("Download fehlgeschlagen: " + e.getMessage()));
            }
        });

        return 1;
    }

    // ------------------------------------------------------------------ load

    private static int load(CommandContext<CommandSourceStack> ctx)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null)
        {
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name").trim();
        Path base = baseDir();
        Path file = base.resolve(name).normalize();

        if (!file.startsWith(base) || !Files.isRegularFile(file))
        {
            error("Datei nicht gefunden: " + name + "  (siehe /schematic list)");
            return 0;
        }

        String fileName = file.getFileName().toString();
        LitematicaSchematic schematic = readSchematic(file);

        if (schematic == null)
        {
            error("Die Datei konnte nicht gelesen werden: " + fileName);
            return 0;
        }

        placeNewSchematic(schematic, fileName);
        return 1;
    }

    /**
     * Reads any supported schematic file (litematic / schem / schematic / nbt). Returns null on failure.
     */
    @org.jetbrains.annotations.Nullable
    public static LitematicaSchematic readSchematic(Path file)
    {
        Path dir = file.getParent();
        String fileName = file.getFileName().toString();
        FileType type = FileType.fromFile(file);

        try
        {
            return switch (type)
            {
                case LITEMATICA_SCHEMATIC -> LitematicaSchematic.createFromFile(dir, fileName);
                case SCHEMATICA_SCHEMATIC -> WorldUtils.convertSchematicaSchematicToLitematicaSchematic(dir, fileName, false, s -> {});
                case VANILLA_STRUCTURE -> WorldUtils.convertStructureToLitematicaSchematic(dir, fileName);
                case SPONGE_SCHEMATIC -> WorldUtils.convertSpongeSchematicToLitematicaSchematic(dir, fileName);
                default -> null;
            };
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("Schematic load failed: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ materials

    private static int materials(CommandContext<CommandSourceStack> ctx)
    {
        return toggleMaterialList(false);
    }

    /**
     * Adds a freshly loaded schematic in front of the player, shows the material list,
     * shares it with the team and starts the move mode so it can be positioned.
     */
    public static SchematicPlacement placeNewSchematic(LitematicaSchematic schematic, String fileName)
    {
        SchematicHolder.getInstance().addSchematic(schematic, true);

        BlockPos pos = team.cagayakegirls.forgematica.ux.MoveMode.targetPosition();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, pos, schematic.getMetadata().getName(), true, true);
        manager.addSchematicPlacement(placement, true);
        manager.setSelectedSchematicPlacement(placement);

        showMaterialListHud(placement);
        team.cagayakegirls.forgematica.teamsync.TeamSyncClient.shareIfInTeam(placement);
        team.cagayakegirls.forgematica.ux.MoveMode.start(placement);

        msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(fileName + " platziert. Jetzt mit den Pfeiltasten ausrichten, Enter = fertig.").withStyle(ChatFormatting.GREEN)));
        return placement;
    }

    /** Shows / hides the material list of the selected placement. */
    public static int toggleMaterialList(boolean actionBar)
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement == null)
        {
            error("Keine Schematic ausgewählt.");
            return 0;
        }

        MaterialListBase list = DataManager.getMaterialList();
        boolean show = !(list != null && list.getHudRenderer().getShouldRenderCustom());

        if (show)
        {
            showMaterialListHud(placement);
        }
        else
        {
            hideMaterialListHud(list);
        }

        String text = show ? "Materialliste eingeblendet" : "Materialliste ausgeblendet";
        Minecraft mc = Minecraft.getInstance();

        if (actionBar && mc.player != null)
        {
            mc.player.sendOverlayMessage(Component.literal(text));
        }
        else
        {
            info(text + ".");
        }

        return 1;
    }

    public static boolean isMaterialListShown()
    {
        MaterialListBase list = DataManager.getMaterialList();
        return list != null && list.getHudRenderer().getShouldRenderCustom();
    }

    /**
     * Creates (or refreshes) the material list of the placement and shows it at the screen edge.
     */
    public static void showMaterialListHud(SchematicPlacement placement)
    {
        try
        {
            MaterialListBase materialList = placement.getMaterialList();
            materialList.reCreateMaterialList();
            DataManager.setMaterialList(materialList);

            MaterialListHudRenderer renderer = materialList.getHudRenderer();

            if (!renderer.getShouldRenderCustom())
            {
                renderer.toggleShouldRender();
            }

            InfoHud.getInstance().addInfoHudRenderer(renderer, true);
        }
        catch (Exception e)
        {
            Litematica.LOGGER.warn("Could not show material list HUD: {}", e.toString());
        }
    }

    public static void hideMaterialListHud(MaterialListBase list)
    {
        MaterialListHudRenderer renderer = list.getHudRenderer();

        if (renderer.getShouldRenderCustom())
        {
            renderer.toggleShouldRender();
        }

        InfoHud.getInstance().removeInfoHudRenderersOfType(renderer.getClass(), true);
    }

    // ------------------------------------------------------------------ helpers

    public static Path baseDir()
    {
        Path dir = DataManager.getSchematicsBaseDirectory().toAbsolutePath().normalize();

        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException ignored)
        {
        }

        return dir;
    }

    private static List<String> listSchematicNames()
    {
        Path base = baseDir();
        List<String> names = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(base, 4))
        {
            stream.filter(Files::isRegularFile)
                  .filter(p -> hasSchematicExtension(p.getFileName().toString()))
                  .forEach(p -> names.add(base.relativize(p).toString().replace('\\', '/')));
        }
        catch (IOException ignored)
        {
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static boolean hasSchematicExtension(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);

        for (String ext : EXTENSIONS)
        {
            if (lower.endsWith(ext))
            {
                return true;
            }
        }

        return false;
    }

    private static String fileNameFromPath(String path)
    {
        if (path == null || path.isEmpty())
        {
            return null;
        }

        String last = path.substring(path.lastIndexOf('/') + 1);
        last = URLDecoder.decode(last, StandardCharsets.UTF_8);
        last = last.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();

        if (last.isEmpty() || last.startsWith(".") || !hasSchematicExtension(last))
        {
            return null;
        }

        return last;
    }

    private static Path uniqueTarget(Path dir, String fileName)
    {
        Path target = dir.resolve(fileName);

        if (!Files.exists(target))
        {
            return target;
        }

        int dot = fileName.lastIndexOf('.');
        String stem = fileName.substring(0, dot);
        String ext = fileName.substring(dot);

        for (int i = 2; i < 1000; i++)
        {
            Path candidate = dir.resolve(stem + "_" + i + ext);

            if (!Files.exists(candidate))
            {
                return candidate;
            }
        }

        return target;
    }

    private static void onMain(Runnable task)
    {
        Minecraft.getInstance().execute(task);
    }

    public static void msg(Component component)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.gui != null && mc.gui.hud != null)
        {
            mc.gui.hud.getChat().addClientSystemMessage(component);
        }
    }

    public static void info(String text)
    {
        msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    public static void error(String text)
    {
        msg(Component.literal("[Forgematica] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.RED)));
    }
}
