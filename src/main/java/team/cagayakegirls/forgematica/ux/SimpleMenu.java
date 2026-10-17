package team.cagayakegirls.forgematica.ux;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ISliderCallback;
import fi.dy.masa.malilib.gui.widgets.WidgetSlider;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.litematica.command.SchematicCommand;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import team.cagayakegirls.forgematica.teamsync.TeamSyncClient;

/**
 * The simple Forgematica main menu (replaces the big Litematica menu; that one is under "Erweitert").
 */
public class SimpleMenu extends GuiBase
{
    private static final int W = 220;
    private boolean confirmRemove;

    public SimpleMenu()
    {
        this.title = "Forgematica";
    }

    @Override
    public void initGui()
    {
        super.initGui();

        SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        int count = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size();
        int offers = TeamSyncClient.getOfferCount();
        int x = (this.width - W) / 2;
        int y = 40;
        int half = (W - 4) / 2;

        // --- Team offers (only shown when there are some, highlighted) ---
        if (offers > 0)
        {
            ButtonGeneric b = new ButtonGeneric(x, y, W, 20, GuiBase.TXT_GOLD + GuiBase.TXT_BOLD + "📨 Team-Angebote (" + offers + ") – ansehen");
            this.addButton(b, (btn, mb) -> GuiBase.openGui(new TeamOffersScreen().setParent(this)));
            y += 28;
        }

        // --- Schematics ---
        this.addButton(new ButtonGeneric(x, y, half, 20, "Schematic laden"),
                (btn, mb) -> { GuiSchematicLoad gui = new GuiSchematicLoad(); gui.setParent(this); GuiBase.openGui(gui); });
        this.addButton(new ButtonGeneric(x + half + 4, y, half, 20, "Meine Schematics (" + count + ")"),
                (btn, mb) -> { GuiSchematicPlacementsList gui = new GuiSchematicPlacementsList(); gui.setParent(this); GuiBase.openGui(gui); });
        y += 24;

        boolean hasSel = selected != null;
        boolean received = hasSel && TeamSyncClient.isReceived(selected);

        ButtonGeneric move = new ButtonGeneric(x, y, half, 20, "Verschieben",
                "Schematic mit den Pfeiltasten verschieben, R = drehen, Enter = fertig");
        move.setEnabled(hasSel && !received);
        this.addButton(move, (btn, mb) -> { GuiBase.openGui(null); MoveMode.start(selected); });

        ButtonGeneric mat = new ButtonGeneric(x + half + 4, y, half, 20,
                "Materialliste: " + (SchematicCommand.isMaterialListShown() ? GuiBase.TXT_GREEN + "An" : GuiBase.TXT_GRAY + "Aus"));
        mat.setEnabled(hasSel);
        this.addButton(mat, (btn, mb) -> { SchematicCommand.toggleMaterialList(false); this.initGui(); });
        y += 24;

        if (received)
        {
            this.addButton(new ButtonGeneric(x, y, half, 20, "Ausblenden",
                            "Diese Team-Schematic bei dir entfernen"),
                    (btn, mb) -> { TeamSyncClient.forget(selected); this.initGui(); });
        }
        else
        {
            boolean shared = hasSel && TeamSyncClient.isShared(selected);
            ButtonGeneric share = new ButtonGeneric(x, y, half, 20, shared ? "Teilen beenden" : "Mit Team teilen");
            share.setEnabled(hasSel);
            this.addButton(share, (btn, mb) -> {
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(selected);
                if (shared) TeamSyncClient.unshareSelected(); else TeamSyncClient.shareSelected();
                this.initGui();
            });
        }

        ButtonGeneric remove = new ButtonGeneric(x + half + 4, y, half, 20,
                this.confirmRemove ? GuiBase.TXT_RED + "Wirklich entfernen?" : "Entfernen");
        remove.setEnabled(hasSel);
        this.addButton(remove, (btn, mb) -> {
            if (!this.confirmRemove)
            {
                this.confirmRemove = true;
            }
            else
            {
                this.confirmRemove = false;
                if (received) TeamSyncClient.forget(selected);
                else DataManager.getSchematicPlacementManager().removeSchematicPlacement(selected);
            }
            this.initGui();
        });
        y += 36;

        // --- Look ---
        this.addWidget(new WidgetSlider(x, y, W, 20, new OpacitySlider()));
        y += 24;

        ButtonGeneric missing = new ButtonGeneric(x, y, W, 20,
                "Fehlende Blöcke umranden: " + (Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.getBooleanValue() ? GuiBase.TXT_GREEN + "An" : GuiBase.TXT_GRAY + "Aus"),
                "Dünner weißer Rahmen um noch fehlende Blöcke. Falsche Blöcke sind immer rot.");
        this.addButton(missing, (btn, mb) -> {
            Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.toggleBooleanValue();
            Configs.saveToFile();
            this.initGui();
        });
        y += 24;

        ButtonGeneric visible = new ButtonGeneric(x, y, W, 20,
                "Schematics anzeigen: " + (Configs.Visuals.ENABLE_RENDERING.getBooleanValue() ? GuiBase.TXT_GREEN + "An" : GuiBase.TXT_GRAY + "Aus"));
        this.addButton(visible, (btn, mb) -> {
            Configs.Visuals.ENABLE_RENDERING.toggleBooleanValue();
            this.initGui();
        });
        y += 36;

        this.addButton(new ButtonGeneric(x, y, W, 20, GuiBase.TXT_GRAY + "Erweitert …",
                        "Das vollständige Litematica-Menü mit allen Funktionen"),
                (btn, mb) -> { GuiMainMenu gui = new GuiMainMenu(); gui.setParent(this); GuiBase.openGui(gui); });
    }

    @Override
    public void removed()
    {
        super.removed();
        Configs.saveToFile();
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        String sel = selected == null ? GuiBase.TXT_GRAY + "Keine Schematic ausgewählt"
                                      : "Ausgewählt: " + GuiBase.TXT_AQUA + selected.getName();
        int x = (this.width - W) / 2;
        this.drawStringWithShadow(ctx, sel, x, 26, 0xFFFFFFFF);

        String hint = "Tasten ändern: Optionen → Steuerung → Tastenbelegung → Forgematica";
        this.drawString(ctx, GuiBase.TXT_GRAY + hint, (this.width - this.getStringWidth(hint)) / 2, this.height - 16, 0xFFAAAAAA);
    }

    /** Ghost block opacity, 10 % … 100 %. */
    private static class OpacitySlider implements ISliderCallback
    {
        private static final double MIN = 0.1;

        @Override
        public int getMaxSteps()
        {
            return 19;
        }

        @Override
        public double getValueRelative()
        {
            return (Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue() - MIN) / (1.0 - MIN);
        }

        @Override
        public void setValueRelative(double relativeValue)
        {
            double v = MIN + Math.max(0, Math.min(1, relativeValue)) * (1.0 - MIN);
            v = Math.round(v * 20.0) / 20.0;
            Configs.Visuals.GHOST_BLOCK_ALPHA.setDoubleValue(v);

            if (!Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue())
            {
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
            }

        }

        @Override
        public String getFormattedDisplayValue()
        {
            return "Deckkraft der Geisterblöcke: " + Math.round(Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue() * 100) + " %";
        }
    }
}
