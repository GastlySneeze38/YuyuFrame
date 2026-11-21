package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.anim.UiStagger;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * L'onglet Compétitif du menu YuyuFrame (2026-11-21), une page horizontale
 * comme Modules et Missions (décor : les losanges du bas). Il remplace la page
 * « Score PvP » à trois colonnes.
 *
 * <ul>
 *   <li>en haut, le titre, le mode de jeu en liste déroulante (d'autres
 *       modes viendront) et Casual / Compétitif ;</li>
 *   <li>un bandeau sur toute la largeur ({@link Banner}) : le rang et sa
 *       progression (en casual la note moyenne), quatre chiffres, et la
 *       courbe « ta place parmi les joueurs » ;</li>
 *   <li>dessous, les duels en grille de cartes ({@link DuelCard}), filtrés
 *       par Tous / Victoires / Défaites. Un clic ouvre la note détaillée
 *       ({@link UiPvpDuelScreen}).</li>
 * </ul>
 *
 * <p>L'historique est demandé au noyau UNE fois, à la création de l'onglet
 * ({@link DuelHistory#load}) : il relit et renote les journaux. Il est relu
 * quand le serveur de classement vient d'être joint ({@link #pollReady}).
 */
final class CompetitiveTab {

    /** Le mode affiché (index de {@link PvpScoreUi#MODES}), -1 = pas encore choisi : celui où l'on est le plus avancé. */
    private static int mode = -1;
    /** Compétitif ou casual : ce qu'on regarde (pas ce qu'on joue, voir {@link PvpScoreUi.PlayingSwitch}). */
    private static boolean competitive = true;
    /** Filtre des cartes : 0 tous, 1 victoires, 2 défaites. Les trois gardés d'une ouverture à l'autre. */
    private static int resultFilter;

    private DuelHistory history = DuelHistory.load();
    private int rankedReady = NativeCombat.rankedReady();
    /** Départ des animations du bandeau : rejouées à l'ouverture de l'onglet et au changement de mode. */
    private long shownAt = System.nanoTime();

    /** Les duels notés du mode (en compétitif : sans ceux joués en casual), du plus récent au plus ancien. */
    private final List<DuelHistory.Duel> pool = new ArrayList<>();
    private int wins, losses, draws, streak;
    private double average = -1, best = -1;

    CompetitiveTab() {
        if (mode < 0) mode = mostAdvancedMode();
        refresh();
    }

    /** Ouvre l'onglet sur un mode donné : celui choisi sur la carte de joueur de l'accueil. */
    static void preselect(int modeIndex) {
        mode = clampMode(modeIndex);
    }

    private static int clampMode(int index) {
        return Math.max(0, Math.min(PvpScoreUi.MODE_CODES.length - 1, index));
    }

    /** L'onglet s'ouvre : le bandeau rejoue son entrée. */
    void replay() {
        shownAt = System.nanoTime();
    }

    /**
     * La synchronisation avec le serveur de classement vient d'aboutir (ou de
     * tomber) : l'historique est relu. Rend {@code true} si la page doit se
     * refaire.
     */
    boolean pollReady() {
        int ready = NativeCombat.rankedReady();
        if (ready == rankedReady) return false;
        rankedReady = ready;
        if (ready == NativeCombat.RANKED_READY_YES) {
            history = DuelHistory.load();
            refresh();
        }
        return true;
    }

    /**
     * L'index du mode où le joueur est le plus avancé : un rang dévoilé avant
     * un placement, puis la position la plus haute, puis le plus de duels.
     */
    private int mostAdvancedMode() {
        int bestIndex = 0;
        double bestKey = -1;
        for (int i = 0; i < PvpScoreUi.MODE_CODES.length; i++) {
            DuelHistory.Rank rank = history.rank(PvpScoreUi.MODE_CODES[i]);
            if (rank == null || rank.duels == 0) continue;
            double key = (rank.placed ? 1e9 : 0) + PvpRank.cumulative(rank.division, rank.points) * 1e3 + rank.duels;
            if (key > bestKey) {
                bestKey = key;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private int code() {
        return PvpScoreUi.MODE_CODES[clampMode(mode)];
    }

    private DuelHistory.Rank rank() {
        return history.rank(code());
    }

    /** Ce qu'on compte du mode et du type choisis. */
    private void refresh() {
        pool.clear();
        int w = 0, l = 0, d = 0;
        double sum = 0, top = -1;
        for (DuelHistory.Duel duel : history.duels) {
            if (!counts(duel, code())) continue;
            pool.add(duel);
            if (duel.result() == 1) w++;
            else if (duel.result() == 2) l++;
            else if (duel.result() == 3) d++;
            sum += duel.score();
            top = Math.max(top, duel.score());
        }
        wins = w; losses = l; draws = d;
        average = pool.isEmpty() ? -1 : Math.round(sum / pool.size());
        best = top;
        // La série en cours : les victoires d'affilée depuis le duel le plus récent.
        int s = 0;
        for (DuelHistory.Duel duel : pool) {
            if (duel.result() != 1) break;
            s++;
        }
        streak = s;
    }

    /** Un duel noté de ce mode, et du type affiché (en compétitif, ceux joués en casual n'ont pas compté). */
    private static boolean counts(DuelHistory.Duel duel, int code) {
        return duel.score() >= 0 && duel.mode() == code && (!competitive || !UiPvpScoreScreen.isCasual(duel));
    }

    /** En compétitif les duels classés du mode ; en casual TOUS ses duels notés (la liste casual les montre tous). */
    private int typeCount(boolean ranked) {
        int n = 0;
        for (DuelHistory.Duel duel : history.duels) {
            if (duel.score() < 0 || duel.mode() != code()) continue;
            if (!ranked || !UiPvpScoreScreen.isCasual(duel)) n++;
        }
        return n;
    }

    /** Les duels des cartes, selon le filtre des résultats. */
    private List<DuelHistory.Duel> shown() {
        if (resultFilter == 0) return pool;
        List<DuelHistory.Duel> out = new ArrayList<>();
        int wanted = resultFilter == 1 ? 1 : 2;
        for (DuelHistory.Duel duel : pool) if (duel.result() == wanted) out.add(duel);
        return out;
    }

    private UiColor tone() {
        DuelHistory.Rank rank = rank();
        if (!competitive) return PvpScoreUi.scoreColor(average);
        return rank != null && rank.placed ? PvpRank.color(rank.division) : UiTheme.ACCENT;
    }

    // ── Les commandes de l'en-tête ───────────────────────────────────────────

    /**
     * Le mode de jeu, en liste déroulante : d'autres modes viendront s'ajouter
     * à Masse et Épée, des segments ne tiendraient plus dans l'en-tête.
     */
    PvpScoreUi.ModeDropdown modeDropdown(Runnable rebuild) {
        return new PvpScoreUi.ModeDropdown(0f, 0f, 0f, 0f, clampMode(mode), i -> {
            if (i == mode) return;
            mode = i;
            refresh();
            replay();
            rebuild.run();
        });
    }

    /** Largeur voulue de la liste des modes. */
    static float modeDropdownWidth() {
        return px(210f);
    }

    /** Casual / Compétitif : ce que montrent le bandeau et les cartes. */
    com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FilterTabs typeTabs(Runnable rebuild) {
        return new com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FilterTabs(new String[]{ "Casual", "Compétitif" },
            new java.util.function.IntSupplier[]{ () -> typeCount(false), () -> typeCount(true) },
            () -> competitive ? 1 : 0, i -> {
                if ((i == 1) == competitive) return;
                competitive = i == 1;
                refresh();
                replay();
                rebuild.run();
            });
    }

    /** Tous / Victoires / Défaites, au-dessus de la grille. */
    com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FilterTabs resultTabs(Runnable rebuild) {
        return new com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FilterTabs(new String[]{ "Tous", "Victoires", "Défaites" },
            new java.util.function.IntSupplier[]{ pool::size, () -> wins, () -> losses },
            () -> resultFilter, i -> {
                if (i == resultFilter) return;
                resultFilter = i;
                rebuild.run();
            });
    }

    /** Ce qu'on JOUE (compétitif ou casual) : la pastille du module, dans l'en-tête. */
    PvpScoreUi.PlayingSwitch playingSwitch(Runnable rebuild) {
        PvpScoreUi.PlayingSwitch sw = new PvpScoreUi.PlayingSwitch(0f, 0f, 0f, 0f, rebuild);
        sw.tooltip = PvpScoreUi.PlayingSwitch.available()
            ? Lang.tr("Compétitif : tes duels font bouger ton rang. Casual : ils sont notés sans y toucher.")
            : PvpScoreUi.PlayingSwitch.unavailableReason();
        return sw;
    }

    /** La pastille ne dessine rien elle-même (voir {@link PvpScoreUi.PlayingSwitch#paint}) : ce widget la peint à sa place dans l'ordre de dessin. */
    UiWidget switchPainter(PvpScoreUi.PlayingSwitch sw) {
        return new UiWidget(0f, 0f, 0f, 0f) {
            @Override
            public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
                if (sw.w <= 0f) return;
                sw.paint(r, mouseX, mouseY, tone(), vw, vh);
            }

            @Override
            public boolean contains(double mx, double my) {
                return false;
            }
        };
    }

    /** Largeur voulue de la pastille de ce qu'on joue. */
    static float switchWidth() {
        return px(270f);
    }

    /** « Diamant II en masse - 148 duels classés ». */
    void drawHint(UiRenderer r, float x, float baseline, int vw, int vh) {
        float s = px(UiTheme.Text.LG);
        String modeName = Lang.tr(PvpScoreUi.MODES[clampMode(mode)]).toLowerCase(Locale.ROOT);
        String lead;
        if (competitive) {
            DuelHistory.Rank rank = rank();
            lead = rank != null && rank.placed ? PvpRank.name(rank.division) : Lang.tr("Non classé");
        } else {
            lead = Lang.tr("Casual");
        }
        com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText.drawOnBackdrop(r, UiFont.BOLD, lead, x, baseline, tone(), s, vw, vh);
        float lx = x + com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText.width(UiFont.BOLD, lead, s);
        int n = pool.size();
        String rest = " " + Lang.tr("en") + " " + modeName + "   -   " + n + " "
            + Lang.tr(competitive ? (n > 1 ? "duels classés" : "duel classé") : (n > 1 ? "duels notés" : "duel noté"));
        com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText.drawOnBackdrop(r, UiFont.REGULAR, rest, lx, baseline,
            UiTheme.TEXT_MUTED, s, vw, vh);
    }

    /** « Derniers duels », titre de section posé sur le décor. */
    static void drawGridTitle(UiRenderer r, float x, float baseline, int vw, int vh) {
        com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.drawSection(r, Lang.tr("Derniers duels"), x, baseline,
            com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.sectionSize(), vw, vh);
    }

    // ── La grille ────────────────────────────────────────────────────────────

    /** Hauteur d'une carte de duel. */
    static float cardHeight() {
        return px(112f);
    }

    /**
     * Les cartes dans la liste qui défile : autant de colonnes que la largeur
     * en laisse (260 au moins chacune, six au plus). Rend {@code false} s'il
     * n'y a aucun duel à montrer (la page affiche alors un message).
     */
    boolean buildGrid(UiScrollContainer scroll, float x, float w, Consumer<DuelHistory.Duel> open) {
        List<DuelHistory.Duel> duels = shown();
        if (duels.isEmpty()) return false;
        float gap = px(UiTheme.Space.S12);
        int cols = Math.max(1, com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.columns(w, px(260f), gap, 1, 6));
        float cardW = com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.cellWidth(w, cols, gap);
        float cardH = cardHeight();
        for (int i = 0; i < duels.size(); i++) {
            int row = i / cols, col = i % cols;
            DuelHistory.Duel duel = duels.get(i);
            DuelCard card = new DuelCard(duel, competitive, UiStagger.delayFor(i, UiTheme.Motion.STAGGER_S, UiTheme.Motion.STAGGER_MAX_S),
                () -> open.accept(duel));
            card.x = x + col * (cardW + gap);
            card.y = -row * (cardH + gap) - cardH;
            card.w = cardW;
            card.h = cardH;
            scroll.add(card);
        }
        return true;
    }

    /** Pourquoi la grille est vide. */
    String emptyMessage() {
        if (resultFilter != 0 && !pool.isEmpty()) {
            return Lang.tr(resultFilter == 1 ? "Aucune victoire pour l'instant dans ce mode." : "Aucune défaite pour l'instant dans ce mode.");
        }
        return Lang.tr(competitive
            ? "Aucun duel classé pour l'instant : ceux joués en casual sont dans l'onglet Casual."
            : "Aucun duel noté pour l'instant : ils apparaîtront ici dès le premier.");
    }

    // ── Le bandeau ───────────────────────────────────────────────────────────

    Banner banner() {
        return new Banner();
    }

    /** Hauteur du bandeau. */
    static float bannerHeight() {
        return px(136f);
    }

    /**
     * Le bandeau du rang, sur toute la largeur : à gauche l'insigne, le nom
     * et la progression (en casual la note moyenne) ; au milieu quatre
     * chiffres ; à droite la courbe de la place parmi les joueurs. Sur une
     * fenêtre étroite, la courbe part d'abord, les chiffres ensuite.
     */
    final class Banner extends UiWidget {

        Banner() {
            super(0f, 0f, 0f, 0f);
        }

        @Override
        public boolean contains(double mx, double my) {
            return false;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            float p = PvpScoreUi.ease(shownAt, 0f, 0.44f);
            PvpScoreUi.alpha = p;
            try {
                float dy = -(1f - p) * px(8f);
                drawBanner(r, x, y + dy, w, h, vw, vh);
            } finally {
                PvpScoreUi.alpha = 1f;
            }
        }

        private void drawBanner(UiRenderer r, float x, float y, float w, float h, int vw, int vh) {
            UiColor tone = tone();
            float radius = px(UiTheme.Space.S18);
            UiSurface.card(0f).radius(radius).alpha(PvpScoreUi.alpha).draw(r, x, y, w, h, vw, vh);
            // Un voile de la couleur du rang, qui part de la gauche et s'éteint.
            r.drawGradientRect2D(x, y, x + w * 0.45f, y + h, radius,
                tone.withAlpha(0.16f * PvpScoreUi.alpha), tone.withAlpha(0f),
                tone.withAlpha(0.16f * PvpScoreUi.alpha), tone.withAlpha(0f), vw, vh);

            float pad = px(UiTheme.Space.S24);
            float gap = px(UiTheme.Space.S24);
            float curveW = px(280f), statW = px(96f);
            float statsW = statW * 4f;
            boolean withCurve = w >= px(1120f);
            boolean withStats = w >= px(760f);
            float right = x + w - pad;
            float curveLeft = right - curveW;
            float statsRight = withCurve ? curveLeft - gap : right;
            float statsLeft = statsRight - statsW;
            float textRight = withStats ? statsLeft - gap : right;

            float badge = h - pad * 2f;
            float bx = x + pad, by = y + pad;
            drawEmblem(r, bx, by, badge, tone, vw, vh);
            float textX = bx + badge + px(UiTheme.Space.S20);
            drawProgress(r, textX, y, Math.max(px(80f), textRight - textX), h, tone, vw, vh);

            if (withStats) {
                // Des séparateurs fins entre les chiffres.
                for (int i = 0; i < 4; i++) {
                    float cx = statsLeft + statW * i;
                    if (i > 0) {
                        PvpScoreUi.rect(r, cx, y + h * 0.28f, cx + UiTheme.hairline(), y + h * 0.72f, 0f,
                            new UiColor(255, 255, 255, 18), vw, vh);
                    }
                    drawStat(r, i, cx, y, statW, h, vw, vh);
                }
            }
            if (withCurve) {
                PvpScoreUi.rect(r, curveLeft - gap / 2f, y + h * 0.2f, curveLeft - gap / 2f + UiTheme.hairline(), y + h * 0.8f, 0f,
                    new UiColor(255, 255, 255, 18), vw, vh);
                drawPlace(r, curveLeft, y + pad, curveW, h - pad * 2f, vw, vh);
            }
        }

        /** L'insigne du rang ; en casual, la note moyenne dans un carré de sa couleur. */
        private void drawEmblem(UiRenderer r, float x, float y, float size, UiColor tone, int vw, int vh) {
            float pop = PvpScoreUi.pop(shownAt, 0.1f, 0.6f);
            float s = size * Math.max(0.01f, 0.9f + 0.1f * pop);
            float ox = x + (size - s) / 2f, oy = y + (size - s) / 2f;
            if (competitive) {
                DuelHistory.Rank rank = rank();
                if (rank != null && rank.placed) {
                    float breath = 0.5f + 0.5f * (float) Math.sin((System.nanoTime() - shownAt) / 1e9 * 2.0);
                    PvpRank.badge(r, rank.division, ox, oy, s, 0.25f + 0.2f * breath, PvpScoreUi.alpha, vw, vh);
                } else {
                    PvpRank.placementBadge(r, ox, oy, s, PvpScoreUi.alpha, vw, vh);
                }
                return;
            }
            float corner = px(UiTheme.Space.S16);
            PvpScoreUi.rect(r, ox, oy, ox + s, oy + s, corner, tone.withAlpha(0.14f), vw, vh);
            r.drawRoundedRectBorder(ox, oy, ox + s, oy + s, corner, UiTheme.hairline(), PvpScoreUi.a(tone.withAlpha(0.45f)), vw, vh);
            String value = average >= 0 ? String.valueOf(PvpScoreUi.count(average, PvpScoreUi.ease(shownAt, 0.1f, 1f))) : PvpScoreUi.NO_SCORE;
            float big = px(1.15f);
            PvpScoreUi.centered(r, UiFont.BOLD, value, ox + s / 2f, PvpScoreUi.middle(oy + s * 0.56f, big), s - px(8f), tone, big, vw, vh);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("sur 500"), ox + s / 2f, oy + px(UiTheme.Space.S12), s,
                UiTheme.TEXT_MUTED, px(UiTheme.Text.XS), vw, vh);
        }

        /** Le nom, puis la barre de la division et l'objectif suivant ; pendant le placement, les dix cases. */
        private void drawProgress(UiRenderer r, float x, float y, float w, float h, UiColor tone, int vw, int vh) {
            float nameScale = px(0.78f);
            float small = px(UiTheme.Text.SM);
            float mid = y + h / 2f;
            if (!competitive) {
                PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("Casual"), x, mid + px(14f), w, UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
                PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Note moyenne de tes duels, sans toucher à ton rang"), x, mid - px(14f),
                    w, UiTheme.TEXT_MUTED, small, vw, vh);
                return;
            }
            DuelHistory.Rank rank = rank();
            boolean placed = rank != null && rank.placed;
            int division = rank == null ? 0 : rank.division;
            double points = rank == null ? 0 : rank.points;
            int duels = rank == null ? 0 : rank.duels;
            PvpScoreUi.left(r, UiFont.BOLD, placed ? PvpRank.name(division) : Lang.tr("Non classé"), x, mid + px(16f), w,
                placed ? tone : UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
            float barW = Math.min(w, px(360f));
            float barY = mid - px(4f);
            if (placed) {
                boolean champion = division >= PvpRank.CHAMPION;
                float fill = champion ? 1f : (float) Math.max(0, Math.min(1, points / PvpRank.DIVISION_POINTS));
                PvpScoreUi.bar(r, x, barY, x + barW, px(6f), 100.0 * fill * PvpScoreUi.ease(shownAt, 0.2f, 1.1f), tone, vw, vh);
                String count = champion ? Math.round(points) + " " + Lang.tr("points")
                    : Math.round(points) + " / " + PvpRank.DIVISION_POINTS + " " + Lang.tr("points");
                String goal = PvpRank.nextGoal(division, points);
                PvpScoreUi.left(r, UiFont.REGULAR, goal.isEmpty() ? count : count + "   -   " + goal, x, barY - px(24f), w,
                    UiTheme.TEXT_MUTED, small, vw, vh);
            } else {
                // Une case par duel de placement, verte ou rouge selon son verdict.
                int[] results = placementResults();
                float pipGap = px(4f);
                float pipW = (barW - pipGap * (PvpRank.PLACEMENT_DUELS - 1)) / PvpRank.PLACEMENT_DUELS;
                for (int i = 0; i < PvpRank.PLACEMENT_DUELS; i++) {
                    float px0 = x + i * (pipW + pipGap);
                    float light = i < duels ? PvpScoreUi.ease(shownAt, 0.2f + 0.06f * i, 0.4f) : 0f;
                    UiColor lit = i < results.length ? PvpScoreUi.resultColor(results[i]) : UiTheme.ACCENT;
                    PvpScoreUi.rect(r, px0, barY, px0 + pipW, barY + px(6f), px(3f),
                        UiColor.lerp(new UiColor(255, 255, 255, 16), lit, light), vw, vh);
                }
                int remaining = Math.max(0, PvpRank.PLACEMENT_DUELS - duels);
                PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Placement") + " " + duels + " / " + PvpRank.PLACEMENT_DUELS
                        + "   -   " + Lang.tr("ton rang se dévoile dans") + " " + remaining + " "
                        + Lang.tr(remaining > 1 ? "duels notés" : "duel noté"),
                    x, barY - px(24f), w, UiTheme.TEXT_MUTED, small, vw, vh);
            }
        }

        /** Les verdicts (1, 2, 3) des duels de placement du mode, du premier au dernier. */
        private int[] placementResults() {
            List<Integer> results = new ArrayList<>();
            for (DuelHistory.Duel duel : history.duels) {
                if (duel.mode() == code() && UiPvpScoreScreen.placementOf(duel) == 1) results.add(0, duel.result());
            }
            int[] out = new int[results.size()];
            for (int i = 0; i < out.length; i++) out[i] = results.get(i);
            return out;
        }

        /** Un chiffre : note moyenne (ou meilleure en casual), taux de victoire, série, nombre de duels. */
        private void drawStat(UiRenderer r, int index, float x, float y, float w, float h, int vw, int vh) {
            float progress = PvpScoreUi.ease(shownAt, 0.15f + 0.06f * index, 0.9f);
            int played = wins + losses + draws;
            String value;
            String label;
            UiColor color = UiTheme.TEXT_PRIMARY;
            switch (index) {
                case 0:
                    double shownScore = competitive ? average : best;
                    value = shownScore >= 0 ? String.valueOf(PvpScoreUi.count(shownScore, progress)) : PvpScoreUi.NO_SCORE;
                    label = Lang.tr(competitive ? "NOTE MOY." : "MEILLEURE");
                    color = PvpScoreUi.scoreColor(shownScore);
                    break;
                case 1:
                    value = played > 0 ? PvpScoreUi.count(100.0 * wins / played, progress) + " %" : PvpScoreUi.NO_SCORE;
                    label = Lang.tr("VICTOIRES");
                    color = played > 0 ? (wins * 2 >= played ? PvpScoreUi.GOOD : PvpScoreUi.BAD) : PvpScoreUi.NEUTRAL;
                    break;
                case 2:
                    value = String.valueOf(PvpScoreUi.count(streak, progress));
                    label = Lang.tr("SÉRIE");
                    break;
                default:
                    value = String.valueOf(PvpScoreUi.count(played, progress));
                    label = Lang.tr("DUELS");
                    break;
            }
            float big = px(0.86f);
            float cx = x + w / 2f, mid = y + h / 2f;
            PvpScoreUi.centered(r, UiFont.BOLD, value, cx, mid, w - px(8f), color, big, vw, vh);
            PvpScoreUi.centered(r, UiFont.BOLD, label, cx, mid - px(26f), w - px(8f), UiTheme.TEXT_MUTED, px(UiTheme.Text.XS), vw, vh);
        }

        /**
         * La place parmi les joueurs : la répartition debout, de gauche (Bois,
         * ou 0) à droite (Champion, ou 500), qui se trace vers la droite ; le
         * point du joueur dessus. En compétitif, une ESTIMATION, dite comme
         * telle ({@link PvpRank#ESTIMATED_SHARE}).
         */
        private void drawPlace(UiRenderer r, float x, float y, float w, float h, int vw, int vh) {
            float small = px(UiTheme.Text.SM);
            float textY = y + h - px(12f);
            float chartTop = y + h - px(26f);
            float chartBottom = y + px(14f);
            double at;
            String lead;
            DoubleUnaryOperator height;
            DoubleFunction<UiColor> color;
            if (competitive) {
                DuelHistory.Rank rank = rank();
                boolean placed = rank != null && rank.placed;
                double axis = PvpRank.AXIS_DIVISIONS;
                double peak = 0;
                for (int i = 0; i <= 200; i++) peak = Math.max(peak, PvpRank.density(axis * i / 200));
                final double max = peak;
                height = a -> PvpRank.density(a * axis) / max;
                color = a -> PvpRank.color((int) Math.min(axis - 1, a * axis));
                double position = placed ? PvpRank.axisPosition(rank.division, rank.points) : -1;
                at = placed ? position / axis : -1;
                long better = placed ? Math.round(PvpRank.percentBelow(position)) : 0;
                lead = !placed ? Lang.tr("Ta place apparaît après le placement")
                    : better >= 1 ? Lang.tr("Devant") + " " + better + " % " + Lang.tr("des joueurs") : Lang.tr("Ta place parmi les joueurs");
                PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("répartition estimée"), x, y - px(2f), w, UiTheme.TEXT_MUTED,
                    px(0.3f), vw, vh);
            } else {
                double max = NativeCombat.SCORE_MAX;
                height = a -> UiPvpScoreScreen.bell(a * max);
                color = a -> UiPvpScoreScreen.curveColor(a * max);
                at = average >= 0 ? Math.max(0, Math.min(max, average)) / max : -1;
                long better = average >= 0 ? Math.round(100.0 * UiPvpScoreScreen.normalCdf((average - 250.0) / 100.0)) : 0;
                lead = average < 0 ? Lang.tr("Pas encore de duel noté")
                    : better >= 1 ? Lang.tr("Devant") + " " + better + " % " + Lang.tr("des joueurs") : Lang.tr("Ta place parmi les joueurs");
            }
            PvpScoreUi.left(r, UiFont.BOLD, lead, x, textY, w, UiTheme.TEXT_PRIMARY, small, vw, vh);
            drawCurve(r, x, chartBottom, w, chartTop - chartBottom, height, color, at, vw, vh);
        }

        /** Une colonne de remplissage et un bout de trait tous les deux pixels, en deux lots ; elle se trace vers la droite. */
        private void drawCurve(UiRenderer r, float x, float y, float w, float h, DoubleUnaryOperator height,
                               DoubleFunction<UiColor> color, double at, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            float alpha = PvpScoreUi.alpha;
            float drawn = PvpScoreUi.ease(shownAt, 0.25f, 1.1f);
            float step = Math.max(1f, px(2f));
            int cols = Math.max(1, (int) Math.ceil(w / step));
            int shownCols = Math.max(0, Math.min(cols, Math.round(cols * drawn)));
            float thickness = Math.max(1.5f, px(2.2f));
            float[][] fill = new float[shownCols][];
            UiColor[] fillColors = new UiColor[shownCols];
            float[][] line = new float[shownCols][];
            UiColor[] lineColors = new UiColor[shownCols];
            for (int i = 0; i < shownCols; i++) {
                float x1 = x + i * step, x2 = Math.min(x + w, x1 + step);
                double a1 = (x1 - x) / w, a2 = (x2 - x) / w;
                float y1 = y + h * (float) height.applyAsDouble(a1);
                float y2 = y + h * (float) height.applyAsDouble(a2);
                UiColor tone = color.apply((a1 + a2) / 2.0);
                fill[i] = new float[]{ x1, y, x2 + 0.5f, Math.min(y1, y2) };
                fillColors[i] = tone.multiplyAlpha(0.10f * alpha);
                line[i] = new float[]{ x1, Math.min(y1, y2) - thickness / 2f, x2 + 0.5f, Math.max(y1, y2) + thickness / 2f };
                lineColors[i] = tone.multiplyAlpha(0.9f * alpha);
            }
            if (shownCols > 0) {
                r.drawRoundedRectBatch(fill, fillColors, 0f, vw, vh);
                r.drawRoundedRectBatch(line, lineColors, 0f, vw, vh);
            }
            PvpScoreUi.rect(r, x, y - UiTheme.hairline(), x + w, y, 0f, new UiColor(255, 255, 255, 30), vw, vh);
            if (at < 0) return;
            // Le point du joueur, une fois la courbe tracée.
            float pop = PvpScoreUi.pop(shownAt, 1.1f, 0.5f);
            if (pop <= 0f) return;
            float mx = x + (float) at * w;
            float my = y + h * (float) height.applyAsDouble(at);
            float stem = Math.max(1f, px(1.4f));
            PvpScoreUi.rect(r, mx - stem / 2f, y, mx + stem / 2f, my, 0f, new UiColor(255, 255, 255, 150).multiplyAlpha(Math.min(1f, pop)), vw, vh);
            float dot = px(5f) * Math.max(0f, pop);
            UiColor glow = color.apply(at);
            r.drawGlow(mx - dot, my - dot, mx + dot, my + dot, dot, 0.5f, glow.multiplyAlpha(alpha * Math.min(1f, pop)), vw, vh);
            r.drawRoundedRect(mx - dot, my - dot, mx + dot, my + dot, dot, new UiColor(255, 255, 255, 255).multiplyAlpha(alpha), vw, vh);
        }
    }

    // ── Une carte de duel ────────────────────────────────────────────────────

    /**
     * Un duel : la tête et le pseudo de l'adversaire, le résultat en pastille
     * (avec les points en compétitif), la note en grand, la date et la durée.
     * Elle entre avec la cascade des cartes de Modules ; au survol elle se
     * soulève, à l'appui elle s'enfonce un peu.
     */
    static final class DuelCard extends UiInteractive {

        private final DuelHistory.Duel duel;
        private final boolean ranked;
        private final Runnable open;
        private final UiTransition enter;

        DuelCard(DuelHistory.Duel duel, boolean ranked, float delay, Runnable open) {
            this.duel = duel;
            this.ranked = ranked;
            this.open = open;
            this.enter = new UiTransition(UiTheme.Motion.ENTER_S, delay, UiEasing.UI_OUT);
            this.enter.show();
        }

        @Override
        protected void onActivate() {
            open.run();
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            updateStates(mouseX, mouseY);
            float t = Math.max(0f, Math.min(1f, enter.eased()));
            float alpha = t * clipFade;
            if (alpha <= 0.01f) return;
            float lift = hover * px(2f);
            float dy = -(1f - t) * px(8f) + lift;
            float scale = 1f - 0.02f * press;
            r.pushLayer(1f, scale, x + w / 2f, y + h / 2f, 0f, dy);
            PvpScoreUi.alpha = alpha;
            try {
                drawCard(r, vw, vh, alpha);
            } finally {
                PvpScoreUi.alpha = 1f;
                r.popLayer();
            }
        }

        private void drawCard(UiRenderer r, int vw, int vh, float alpha) {
            float radius = px(UiTheme.Space.S18);
            UiSurface.card(hover).radius(radius).alpha(alpha).draw(r, x, y, w, h, vw, vh);
            UiSurface.hoverWash(r, x, y, w, h, radius, hover * alpha, vw, vh);
            float pulse = pulse();
            if (pulse >= 0f) {
                float spread = px(UiTheme.Space.S3) * pulse;
                r.drawRoundedRect(x - spread, y - spread, x + w + spread, y + h + spread, radius + spread,
                    UiTheme.ACCENT.withAlpha(0.30f * Math.max(0f, 1f - pulse) * alpha), vw, vh);
            }

            float pad = px(UiTheme.Space.S16);
            float top = y + h - pad;

            // En haut : la tête, le pseudo, et la pastille du résultat à droite.
            float head = px(30f);
            float hy = top - head;
            drawHead(r, x + pad, hy, head, alpha, vw, vh);
            String[] chip = chip();
            UiColor chipTone = chipTone();
            float chipScale = px(UiTheme.Text.XS);
            float chipH = px(24f);
            float chipW = r.textWidth(UiFont.BOLD, chip[0], chipScale) + px(UiTheme.Space.S20);
            float chipX = x + w - pad - chipW;
            float chipY = hy + (head - chipH) / 2f;
            PvpScoreUi.rect(r, chipX, chipY, chipX + chipW, chipY + chipH, chipH / 2f, chipTone.withAlpha(0.16f), vw, vh);
            PvpScoreUi.centered(r, UiFont.BOLD, chip[0], chipX + chipW / 2f, PvpScoreUi.middle(chipY + chipH / 2f, chipScale),
                chipW, chipTone, chipScale, vw, vh);
            float nameX = x + pad + head + px(UiTheme.Space.S10);
            String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
            PvpScoreUi.left(r, UiFont.BOLD, opponent, nameX, PvpScoreUi.middle(hy + head / 2f, px(UiTheme.Text.LG)),
                chipX - nameX - px(UiTheme.Space.S8), UiTheme.TEXT_PRIMARY, px(UiTheme.Text.LG), vw, vh);

            // En bas : la note en grand, la date et la durée à droite.
            float big = px(0.9f);
            float base = y + pad;
            String note = PvpScoreUi.scoreText(duel.score());
            PvpScoreUi.text(r, UiFont.BOLD, note, x + pad, base, PvpScoreUi.scoreColor(duel.score()), big, vw, vh);
            float noteW = r.textWidth(UiFont.BOLD, note, big);
            PvpScoreUi.text(r, UiFont.REGULAR, "/ 500", x + pad + noteW + px(UiTheme.Space.S6), base, UiTheme.TEXT_MUTED,
                px(UiTheme.Text.XS), vw, vh);
            String when = PvpScoreUi.date(duel.time);
            String length = PvpScoreUi.duration(duel.report[NativeCombat.REPORT_DURATION_SECS]);
            String meta = when.isEmpty() ? length : when + "  -  " + length;
            float metaScale = px(UiTheme.Text.XS);
            PvpScoreUi.right(r, UiFont.REGULAR, meta, x + w - pad, base, w * 0.55f, UiTheme.TEXT_MUTED, metaScale, vw, vh);
        }

        /** Le texte de la pastille : « V +18 », « D -12 », « PLACEMENT »… */
        private String[] chip() {
            String letter = duel.result() == 1 ? Lang.tr("V") : duel.result() == 2 ? Lang.tr("D") : Lang.tr("N");
            if (!ranked) return new String[]{ PvpScoreUi.resultText(duel.result()).toUpperCase(Locale.ROOT) };
            int placement = UiPvpScoreScreen.placementOf(duel);
            if (placement != 0) return new String[]{ Lang.tr(placement == 1 ? "PLACEMENT" : "NON COMPTÉ") };
            boolean counted = duel.ranked != null && duel.ranked.length > 0;
            return new String[]{ counted ? letter + " " + PvpScoreUi.signedPoints(duel.ranked[0]) : letter };
        }

        private UiColor chipTone() {
            if (ranked && UiPvpScoreScreen.placementOf(duel) == 1) return UiTheme.ACCENT;
            if (ranked && UiPvpScoreScreen.placementOf(duel) == 2) return PvpScoreUi.NEUTRAL;
            return PvpScoreUi.resultColor(duel.result());
        }

        private void drawHead(UiRenderer r, float hx, float hy, float size, float alpha, int vw, int vh) {
            r.drawRoundedRect(hx - 1f, hy - 1f, hx + size + 1f, hy + size + 1f, px(UiTheme.Space.S6),
                UiTheme.PANEL_BG.multiplyAlpha(alpha), vw, vh);
            BufferedImage face = null;
            String key = "duel-face-default";
            if (!duel.opponent.isEmpty()) {
                String url = "https://mc-heads.net/avatar/" + duel.opponent + "/64";
                face = UiRemoteImage.get(url);
                if (face != null) key = url;
            }
            if (face == null) face = McTexture.defaultFace();
            if (face != null) r.drawIcon(key, face, hx, hy, size, size, alpha, vw, vh);
        }
    }
}
