package team.cagayakegirls.forgematica.ux;

import java.util.List;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import team.cagayakegirls.forgematica.teamsync.TeamSyncClient;

/** Lists schematics teammates want to share, with "Annehmen" / "Ablehnen". */
public class TeamOffersScreen extends GuiBase
{
    private static final int W = 320;
    private static final int MAX_ROWS = 8;

    public TeamOffersScreen()
    {
        this.title = "Team-Angebote";
    }

    @Override
    public void initGui()
    {
        super.initGui();

        List<TeamSyncClient.Offer> offers = TeamSyncClient.getOffers();
        int x = (this.width - W) / 2;
        int y = 44;

        for (int i = 0; i < Math.min(MAX_ROWS, offers.size()); i++)
        {
            TeamSyncClient.Offer offer = offers.get(i);
            this.addButton(new ButtonGeneric(x + W - 150, y, 72, 20, GuiBase.TXT_GREEN + "Annehmen"),
                    (btn, mb) -> { TeamSyncClient.accept(offer.id); this.afterChange(); });
            this.addButton(new ButtonGeneric(x + W - 74, y, 72, 20, GuiBase.TXT_RED + "Ablehnen"),
                    (btn, mb) -> { TeamSyncClient.decline(offer.id); this.afterChange(); });
            y += 26;
        }

        if (offers.size() > 1)
        {
            y += 6;
            this.addButton(new ButtonGeneric(x, y, W / 2 - 2, 20, "Alle annehmen"),
                    (btn, mb) -> { TeamSyncClient.acceptAll(); this.afterChange(); });
            this.addButton(new ButtonGeneric(x + W / 2 + 2, y, W / 2 - 2, 20, "Alle ablehnen"),
                    (btn, mb) -> { TeamSyncClient.declineAll(); this.afterChange(); });
        }
    }

    private void afterChange()
    {
        if (TeamSyncClient.getOfferCount() == 0)
        {
            this.closeGui(true);
        }
        else
        {
            this.initGui();
        }
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        List<TeamSyncClient.Offer> offers = TeamSyncClient.getOffers();
        int x = (this.width - W) / 2;
        int y = 44;

        if (offers.isEmpty())
        {
            this.drawStringWithShadow(ctx, GuiBase.TXT_GRAY + "Keine offenen Angebote.", x, y + 6, 0xFFFFFFFF);
            return;
        }

        this.drawStringWithShadow(ctx, "Dein Team möchte diese Schematics mit dir teilen:", x, 28, 0xFFFFFFFF);

        for (int i = 0; i < Math.min(MAX_ROWS, offers.size()); i++)
        {
            TeamSyncClient.Offer offer = offers.get(i);
            this.drawStringWithShadow(ctx, GuiBase.TXT_AQUA + offer.fileName, x, y + 1, 0xFFFFFFFF);
            this.drawString(ctx, GuiBase.TXT_GRAY + "von " + offer.sender + " · bei " + offer.x + " " + offer.y + " " + offer.z, x, y + 11, 0xFFAAAAAA);
            y += 26;
        }
    }
}
