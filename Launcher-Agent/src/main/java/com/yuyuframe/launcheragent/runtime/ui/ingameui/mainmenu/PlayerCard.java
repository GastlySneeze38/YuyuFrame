package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.friends.GameProfile;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.MinecraftFont;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpRank;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.ProfileBanner;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIcon;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * La carte de joueur de l'accueil, comme la fiche du launcher : la bannière
 * choisie dans le launcher, la tête, le pseudo et le titre mis en vitrine ; à
 * droite le rang du mode choisi (Masse ou Épée) et sa progression ; dessous
 * quatre chiffres et les derniers duels.
 *
 * <p>Tout y est cliquable : « Personnaliser » et le titre ramènent le
 * launcher sur le profil ({@link GameProfile#openInLauncher}), les modes
 * changent les chiffres sur place, chaque chiffre et chaque duel ouvrent
 * l'historique du mode.
 *
 * <p>La carte dessine le décor ; ses parties cliquables sont des widgets à
 * elles ({@link #children}), placés par la carte à chaque image et ajoutés
 * par l'écran juste après elle.
 */
public final class PlayerCard extends UiWidget {

    // Mesures de la maquette, avant l'échelle.
    private static final float INSET = 8f, BANNER_H = 128f, HEAD = 96f, BAND_H = 120f, CELLS_H = 80f,
        RECENT_GAP = 30f, RECENT_H = 40f, PAD = 24f, BOTTOM = 20f, RADIUS = 22f;

    private static final String PENCIL = LauncherModule.icons8("edit");
    private static final String CHEVRON = LauncherModule.icons8("forward");
    private static final UiColor WIN = new UiColor(92, 220, 130, 255);
    private static final UiColor LOSS = new UiColor(240, 96, 110, 255);

    private final HomeStats stats;
    private int mode;

    private final Chip personalize;
    private final TitleChip titleChip;
    private final Chip[] modeChips;
    private final StatCell[] cells;
    private final RecentStrip recent;
    private final TextLink allHistory;

    /** Rang : coin gauche, et colonne du texte, posés par {@link #place}. */
    private float rankX, rankTextX, rankW, bannerBottom;

    /**
     * @param openHistory ouvre l'historique des duels sur le mode donné
     *                    (index de {@link HomeStats#MODE_CODES})
     */
    public PlayerCard(HomeStats stats, IntConsumer openHistory) {
        super(0f, 0f, 0f, 0f);
        this.stats = stats;
        this.mode = Math.max(0, Math.min(stats.modes.length - 1, stats.preferred));
        Runnable history = () -> openHistory.accept(mode);

        personalize = new Chip("Personnaliser", PENCIL, null, GameProfile::openInLauncher, true);
        titleChip = new TitleChip(GameProfile::openInLauncher);
        modeChips = new Chip[HomeStats.MODE_NAMES.length];
        for (int i = 0; i < modeChips.length; i++) {
            int index = i;
            modeChips[i] = new Chip(HomeStats.MODE_NAMES[i], null, () -> mode == index, () -> mode = index, false);
        }
        cells = new StatCell[]{
            new StatCell("sword", HomeIcons::sword, () -> current().average, "Note moyenne", history),
            new StatCell("totem", HomeIcons::totem, () -> current().streak, "Victoires d'affilée", history),
            new StatCell("iron_sword", HomeIcons::ironSword,
                () -> current().rank != null ? String.valueOf(current().rank.duels) : "0", "Duels classés", history),
            new StatCell("experience_bottle", HomeIcons::xpBottle, () -> current().winRate, "Victoires", history),
        };
        recent = new RecentStrip(() -> current().recent, history);
        allHistory = new TextLink("Tout l'historique", history);
    }

    public static float preferredHeight() {
        return px(INSET + BANNER_H + BAND_H + CELLS_H + RECENT_GAP + RECENT_H + BOTTOM);
    }

    /** Les parties cliquables, à ajouter après la carte (ordre de dessin). */
    public List<UiWidget> children() {
        List<UiWidget> out = new ArrayList<>();
        out.add(personalize);
        out.add(titleChip);
        for (Chip c : modeChips) out.add(c);
        for (StatCell c : cells) out.add(c);
        out.add(recent);
        out.add(allHistory);
        return out;
    }

    private HomeStats.Mode current() {
        return stats.modes[mode];
    }

    /** Pose les parties cliquables dans la carte. Appelé après la mise en page, puis à chaque image. */
    public void place() {
        float top = y + h;
        float inset = px(INSET), pad = px(PAD);
        bannerBottom = top - inset - px(BANNER_H);

        // « Personnaliser », en haut à droite de la bannière.
        float ph = px(34f);
        personalize.w = personalize.preferredWidth(ph);
        personalize.h = ph;
        personalize.x = x + w - inset - px(UiTheme.Space.S16) - personalize.w;
        personalize.y = top - inset - px(UiTheme.Space.S16) - ph;

        // Le rang, à droite sous la bannière.
        rankW = Math.min(px(380f), w * 0.42f);
        rankX = x + w - pad - rankW;
        rankTextX = rankX + px(84f) + px(UiTheme.Space.S16);
        float chipH = px(24f);
        float cx = rankTextX;
        for (Chip c : modeChips) {
            c.w = c.preferredWidth(chipH);
            c.h = chipH;
            c.x = cx;
            c.y = bannerBottom - px(UiTheme.Space.S14) - chipH;
            cx += c.w + px(UiTheme.Space.S6);
        }

        // Le titre, après le pseudo (sa place dépend de la largeur du pseudo).
        float nameX = x + px(28f) + px(HEAD) + px(UiTheme.Space.S20);
        float nameW = Math.min(UiText.width(UiFont.BOLD, playerName(), nameScale()), rankX - nameX - px(UiTheme.Space.S24));
        float chipX = nameX + Math.max(0f, nameW) + px(UiTheme.Space.S16);
        float tw = titleChip.preferredWidth();
        boolean showTitle = tw > 0f && chipX + tw <= rankX - px(UiTheme.Space.S16);
        titleChip.x = chipX;
        titleChip.h = px(46f);
        titleChip.y = bannerBottom - px(26f) - titleChip.h / 2f;
        titleChip.w = showTitle ? tw : 0f;

        // Les quatre chiffres.
        float cellsTop = bannerBottom - px(BAND_H);
        float gap = px(UiTheme.Space.S12);
        float cw = (w - 2f * pad - 3f * gap) / 4f;
        for (int i = 0; i < cells.length; i++) {
            StatCell c = cells[i];
            c.x = x + pad + i * (cw + gap);
            c.y = cellsTop - px(CELLS_H);
            c.w = cw;
            c.h = px(CELLS_H);
        }

        // Les derniers duels, et le lien vers tout l'historique.
        float rowTop = cellsTop - px(CELLS_H) - px(RECENT_GAP);
        float rowH = px(RECENT_H);
        allHistory.w = allHistory.preferredWidth();
        allHistory.h = rowH;
        allHistory.x = x + w - pad - allHistory.w;
        allHistory.y = rowTop - rowH;
        recent.x = x + pad;
        recent.y = rowTop - rowH;
        recent.h = rowH;
        recent.w = Math.max(0f, allHistory.x - px(UiTheme.Space.S16) - recent.x);
    }

    private static float nameScale() {
        return px(0.9f);
    }

    private static String playerName() {
        String name = ClientData.username();
        return name == null || name.isEmpty() ? Lang.tr("Joueur") : name;
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        GameProfile.keepFresh();
        place();
        float top = y + h;
        float inset = px(INSET), pad = px(PAD);

        UiSurface.card(0f).radius(px(RADIUS)).draw(r, x, y, w, h, vw, vh);
        ProfileBanner.draw(r, GameProfile.banner(), GameProfile.accent(), x + inset, bannerBottom, w - 2f * inset,
            px(BANNER_H), px(RADIUS - INSET + 2f), vw, vh);

        drawHead(r, vw, vh);

        // Pseudo, à droite de la tête, sous la bannière.
        float nameX = x + px(28f) + px(HEAD) + px(UiTheme.Space.S20);
        float maxName = rankX - nameX - px(UiTheme.Space.S24);
        r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, playerName(), maxName, nameScale()), nameX,
            bannerBottom - px(38f), UiTheme.TEXT_PRIMARY, nameScale(), vw, vh);

        drawRank(r, vw, vh);

        // Le trait au-dessus des derniers duels.
        float lineY = cells[0].y - px(RECENT_GAP) / 2f;
        r.drawRoundedRect(x + pad, lineY, x + w - pad, lineY + Math.max(1f, px(1f)), 0f, new UiColor(255, 255, 255, 18), vw, vh);
    }

    private void drawHead(UiRenderer r, int vw, int vh) {
        float head = px(HEAD);
        float hx = x + px(28f);
        float hy = bannerBottom - px(40f);
        float frame = px(UiTheme.Space.S4);
        r.drawRoundedRect(hx - frame - px(UiTheme.Space.S2), hy - frame - px(UiTheme.Space.S6), hx + head + frame + px(UiTheme.Space.S2),
            hy + head + frame, px(UiTheme.Space.S12), UiTheme.SHADOW_CARD, vw, vh);
        r.drawRoundedRect(hx - frame, hy - frame, hx + head + frame, hy + head + frame, px(UiTheme.Space.S10),
            UiTheme.PANEL_BG, vw, vh);
        String name = ClientData.username();
        BufferedImage face = null;
        String key = "card-face";
        if (name != null && !name.isEmpty()) {
            key = "https://mc-heads.net/avatar/" + name + "/128";
            face = UiRemoteImage.get(key);
        }
        if (face == null) {
            face = McTexture.defaultFace();
            key = "card-face-default";
        }
        if (face != null) r.drawIcon(key, face, hx, hy, head, head, vw, vh);
    }

    private void drawRank(UiRenderer r, int vw, int vh) {
        DuelHistory.Rank rank = current().rank;
        boolean placed = rank != null && rank.placed;
        float badge = px(84f);
        float badgeTop = bannerBottom - px(UiTheme.Space.S14);
        if (placed) PvpRank.badge(r, rank.division, rankX, badgeTop - badge, badge, 0.35f, 1f, vw, vh);
        else PvpRank.placementBadge(r, rankX, badgeTop - badge, badge, 1f, vw, vh);

        float tx = rankTextX;
        float textW = rankX + rankW - tx;
        UiColor color = placed ? PvpRank.color(rank.division) : UiTheme.accentLight();
        String title = placed ? PvpRank.name(rank.division)
            : rank != null && rank.duels > 0 ? Lang.tr("En placement") : Lang.tr("Pas encore classé");
        UiFont pixel = MinecraftFont.accent(true);
        float titleScale = px(UiTheme.Text.XL) * 1.3f;
        r.drawText(pixel, UiText.fit(pixel, title, textW, titleScale), tx, bannerBottom - px(68f), color, titleScale, vw, vh);

        // La barre : les points de la division, ou les duels de placement joués.
        float fill = placed ? Math.min(1f, rank.points / (float) PvpRank.DIVISION_POINTS)
            : rank == null ? 0f : Math.min(1f, rank.duels / (float) PvpRank.PLACEMENT_DUELS);
        float barH = px(UiTheme.Space.S6);
        float barY = bannerBottom - px(82f);
        r.drawRoundedRect(tx, barY, tx + textW, barY + barH, barH / 2f, new UiColor(255, 255, 255, 22), vw, vh);
        if (fill > 0f) r.drawRoundedRect(tx, barY, tx + Math.max(barH, textW * fill), barY + barH, barH / 2f, color, vw, vh);

        String info;
        if (placed) {
            String goal = PvpRank.nextGoal(rank.division, rank.points);
            info = rank.points + " / " + PvpRank.DIVISION_POINTS + " RR" + (goal.isEmpty() ? "" : "   " + goal);
        } else if (rank != null && rank.duels > 0) {
            info = Lang.tr("Placement") + " " + rank.duels + " / " + PvpRank.PLACEMENT_DUELS;
        } else {
            info = Lang.tr("Joue un duel classé pour commencer");
        }
        float s = px(UiTheme.Text.XS);
        r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, info, textW, s), tx, bannerBottom - px(102f),
            UiTheme.TEXT_MUTED, s, vw, vh);
    }

    // ── Les parties cliquables ───────────────────────────────────────────────

    /** Une pastille : « Personnaliser » sur la bannière (sombre), ou un mode (accent quand il est choisi). */
    public static final class Chip extends UiInteractive {
        private final String label;
        private final String icon;
        private final BooleanSupplier active;
        private final Runnable action;
        private final boolean onBanner;

        Chip(String label, String icon, BooleanSupplier active, Runnable action, boolean onBanner) {
            this.label = label;
            this.icon = icon;
            this.active = active;
            this.action = action;
            this.onBanner = onBanner;
        }

        private float textScale() {
            return px(onBanner ? UiTheme.Text.SM : UiTheme.Text.XS);
        }

        float preferredWidth(float height) {
            float iconW = icon == null ? 0f : height * 0.42f + px(UiTheme.Space.S8);
            return UiText.width(UiFont.BOLD, Lang.tr(label), textScale()) + iconW + height * (onBanner ? 0.9f : 0.8f);
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            updateStates(mouseX, mouseY);
            boolean on = active != null && active.getAsBoolean();
            float radius = onBanner ? px(UiTheme.Space.S10) : h / 2f;
            UiColor fill = onBanner
                ? new UiColor(8, 8, 14, 150 + (int) (60 * hover))
                : on ? UiTheme.ACCENT.withAlpha(0.85f) : new UiColor(255, 255, 255, 14 + (int) (22 * hover));
            r.drawRoundedRect(x, y, x + w, y + h, radius, fill, vw, vh);
            if (onBanner) UiSurface.hoverWash(r, x, y, w, h, radius, hover, vw, vh);

            float s = textScale();
            String text = Lang.tr(label);
            float tw = UiText.width(UiFont.BOLD, text, s);
            float iconS = icon == null ? 0f : h * 0.42f;
            float gap = icon == null ? 0f : px(UiTheme.Space.S8);
            float left = x + (w - tw - iconS - gap) / 2f;
            if (icon != null) UiIcon.crisp(r, icon, left, y + (h - iconS) / 2f, iconS, 0.9f, vw, vh);
            UiColor color = on || onBanner ? UiTheme.TEXT_PRIMARY
                : UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, hover);
            r.drawText(UiFont.BOLD, text, left + iconS + gap, UiText.centeredBaseline(UiFont.BOLD, y, h, s), color, s, vw, vh);
        }

        @Override
        protected void onActivate() {
            if (action != null) action.run();
        }
    }

    /** Le titre mis en vitrine dans le launcher : une étiquette dorée, le titre, « Titre » dessous. Caché sans titre. */
    public static final class TitleChip extends UiInteractive {
        private static final UiColor GOLD = new UiColor(232, 184, 72, 255);
        private final Runnable action;

        TitleChip(Runnable action) {
            this.action = action;
        }

        float preferredWidth() {
            String title = GameProfile.title();
            if (title == null || title.isEmpty()) return 0f;
            float textW = Math.max(UiText.width(UiFont.BOLD, title, px(UiTheme.Text.SM)),
                UiText.width(UiFont.REGULAR, Lang.tr("Titre"), px(UiTheme.Text.XS)));
            return px(UiTheme.Space.S8) + px(30f) + px(UiTheme.Space.S10) + Math.min(textW, px(220f)) + px(UiTheme.Space.S16);
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            updateStates(mouseX, mouseY);
            float radius = px(UiTheme.Space.S10);
            r.drawRoundedRect(x, y, x + w, y + h, radius, new UiColor(255, 255, 255, 12), vw, vh);
            UiSurface.hoverWash(r, x, y, w, h, radius, hover, vw, vh);

            float box = px(30f);
            float bx = x + px(UiTheme.Space.S8), by = y + (h - box) / 2f;
            r.drawRoundedRect(bx, by, bx + box, by + box, px(UiTheme.Space.S8), GOLD.withAlpha(0.22f), vw, vh);
            BufferedImage tag = HomeIcons.nameTag();
            float iconS = px(20f);
            if (tag != null) McTexture.draw(r, tag, "name_tag", bx + (box - iconS) / 2f, by + (box - iconS) / 2f, iconS, iconS, 1f, vw, vh);

            float tx = bx + box + px(UiTheme.Space.S10);
            float maxW = x + w - px(UiTheme.Space.S12) - tx;
            float mid = y + h / 2f;
            float s = px(UiTheme.Text.SM);
            r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, GameProfile.title(), maxW, s), tx, mid + px(UiTheme.Space.S2),
                UiColor.lerp(GOLD, UiTheme.TEXT_PRIMARY, 0.25f), s, vw, vh);
            r.drawText(UiFont.REGULAR, Lang.tr("Titre"), tx, mid - px(UiTheme.Space.S14), UiTheme.TEXT_MUTED,
                px(UiTheme.Text.XS), vw, vh);
        }

        @Override
        protected void onActivate() {
            if (action != null) action.run();
        }
    }

    /** Un chiffre du mode choisi : l'objet du jeu, la valeur dans la police du jeu, ce qu'elle mesure. */
    public static final class StatCell extends UiInteractive {
        private final String iconKey;
        private final Supplier<BufferedImage> icon;
        private final Supplier<String> value;
        private final String label;
        private final Runnable action;

        StatCell(String iconKey, Supplier<BufferedImage> icon, Supplier<String> value, String label, Runnable action) {
            this.iconKey = iconKey;
            this.icon = icon;
            this.value = value;
            this.label = label;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            updateStates(mouseX, mouseY);
            float radius = px(UiTheme.Space.S16);
            r.drawRoundedRect(x, y, x + w, y + h, radius, new UiColor(255, 255, 255, 9), vw, vh);
            UiSurface.hoverWash(r, x, y, w, h, radius, hover, vw, vh);

            float pad = px(UiTheme.Space.S18);
            float iconS = px(UiTheme.Space.S36);
            BufferedImage img = icon.get();
            if (img != null) McTexture.draw(r, img, iconKey, x + pad, y + (h - iconS) / 2f, iconS, iconS, 1f, vw, vh);

            float chevron = px(UiTheme.Space.S14);
            float tx = x + pad + iconS + px(UiTheme.Space.S14);
            float maxW = x + w - pad - chevron - px(UiTheme.Space.S6) - tx;
            float mid = y + h / 2f;
            UiFont pixel = MinecraftFont.accent(true);
            float valueScale = px(UiTheme.Text.XL) * 1.2f;
            r.drawText(pixel, UiText.fit(pixel, value.get(), maxW, valueScale), tx, mid + px(UiTheme.Space.S2),
                UiTheme.TEXT_PRIMARY, valueScale, vw, vh);
            float s = px(UiTheme.Text.XS);
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, Lang.tr(label), maxW, s), tx, mid - px(UiTheme.Space.S18),
                UiTheme.TEXT_MUTED, s, vw, vh);
            if (hover > 0.01f) {
                UiIcon.crisp(r, CHEVRON, x + w - pad - chevron, mid - chevron / 2f, chevron, 0.7f * hover, vw, vh);
            }
        }

        @Override
        protected void onActivate() {
            if (action != null) action.run();
        }
    }

    /** « Derniers duels » et une pastille par duel, gagné en vert, perdu en rouge, avec sa note. */
    public static final class RecentStrip extends UiInteractive {
        private final Supplier<List<HomeStats.Recent>> duels;
        private final Runnable action;

        RecentStrip(Supplier<List<HomeStats.Recent>> duels, Runnable action) {
            this.duels = duels;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            updateStates(mouseX, mouseY);
            float s = px(UiTheme.Text.SM);
            String label = Lang.tr("Derniers duels");
            r.drawText(UiFont.BOLD, label, x, UiText.centeredBaseline(UiFont.BOLD, y, h, s),
                UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hover), s, vw, vh);
            float px0 = x + UiText.width(UiFont.BOLD, label, s) + px(UiTheme.Space.S16);

            List<HomeStats.Recent> list = duels.get();
            if (list.isEmpty()) {
                float m = px(UiTheme.Text.XS);
                r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, Lang.tr("Aucun duel pour l'instant"), x + w - px0, m), px0,
                    UiText.centeredBaseline(UiFont.REGULAR, y, h, m), UiTheme.TEXT_MUTED, m, vw, vh);
                return;
            }
            UiFont pixel = MinecraftFont.accent(true);
            float ps = px(UiTheme.Text.SM);
            float ph = px(32f);
            float py = y + (h - ph) / 2f;
            float gap = px(UiTheme.Space.S8);
            float cx = px0;
            for (HomeStats.Recent d : list) {
                // Un nul n'a pas de lettre (« N » est déjà le nord de la boussole dans les traductions).
                String letter = d.won ? Lang.tr("V") : d.lost ? Lang.tr("D") : "=";
                String text = d.score >= 0 ? letter + " " + d.score : letter;
                float pw = UiText.width(pixel, text, ps) + px(UiTheme.Space.S24);
                if (cx + pw > x + w) break;
                UiColor c = d.won ? WIN : d.lost ? LOSS : UiTheme.TEXT_SECONDARY;
                r.drawRoundedRect(cx, py, cx + pw, py + ph, px(UiTheme.Space.S8), c.withAlpha(0.14f + 0.06f * hover), vw, vh);
                r.drawText(pixel, text, cx + px(UiTheme.Space.S12), UiText.centeredBaseline(pixel, py, ph, ps), c, ps, vw, vh);
                cx += pw + gap;
            }
        }

        @Override
        protected void onActivate() {
            if (action != null) action.run();
        }
    }
}
