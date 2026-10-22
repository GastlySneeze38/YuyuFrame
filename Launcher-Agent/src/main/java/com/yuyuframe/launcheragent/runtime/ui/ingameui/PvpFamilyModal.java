package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.combat.DuelDetail;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le détail d'une famille dans un duel : la fenêtre qui s'ouvre au clic sur
 * une ligne du face-à-face de {@link UiPvpDuelScreen}.
 *
 * <p><b>Elle montre des faits et ce qu'ils ont rapporté, jamais le calcul.</b>
 * Un joueur conteste une note qu'il ne comprend pas, pas un fait qu'il se
 * rappelle avoir vécu. On y lit donc des compteurs (« 0 critique sur 5 »), les
 * actions qui ont le plus compté, datées depuis l'ouverture du duel, avec les
 * points qu'elles ont fait gagner ou perdre à la note sur 500 (2026-10-22 :
 * chaque action est jugée face à ce qu'en tire un joueur moyen), et la même
 * mesure chez l'adversaire quand elle existe de son côté. Ni poids, ni écart,
 * ni formule : le « comment c'est compté » reste dans {@link PvpInfoModal}, à
 * un bouton de là.
 *
 * <p>De haut en bas : la famille, un verdict, la note et ses points ; ce qui
 * l'a fait monter et baisser (deux faits au plus de chaque côté, les plus
 * forts) ; quatre actions au plus, celles qui ont pesé le plus, dans l'ordre
 * du duel ; le face-à-face ; un conseil tiré du fait qui a le plus coûté.
 * Tout vient du noyau ({@link DuelDetail}) ; ici on ne fait que traduire et
 * ranger.
 */
final class PvpFamilyModal {

    /** Deux faits au plus de chaque côté, quatre actions au plus. */
    private static final int FACTS_SHOWN = 2;
    private static final int ACTIONS_SHOWN = 4;

    private static final float OPEN_SECS = 0.45f;
    private static final float CLOSE_SECS = 0.2f;

    private final int family;
    private final double[] report;
    private final DuelDetail detail;
    private final Runnable explain;

    private final List<UiWidget> widgets = new ArrayList<UiWidget>();
    private final List<Float> widgetBaseY = new ArrayList<Float>();
    private float px, py, pw, ph;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    private final long openedAt = System.nanoTime();
    private long closingAt;

    /**
     * @param family  le rang de la famille dans le résumé (0 conversion … 4 ressources)
     * @param report  le résumé du duel
     * @param detail  son détail, lu par le noyau
     * @param explain ouvre l'explication de la famille ({@link PvpInfoModal})
     */
    PvpFamilyModal(int family, double[] report, DuelDetail detail, Runnable explain) {
        this.family = family;
        this.report = report;
        this.detail = detail;
        this.explain = explain;
    }

    List<UiWidget> widgets() {
        return widgets;
    }

    void close() {
        if (closingAt == 0L) closingAt = System.nanoTime();
    }

    boolean closed() {
        return closingAt != 0L && (System.nanoTime() - closingAt) / 1e9f >= CLOSE_SECS;
    }

    private float presence() {
        if (closingAt != 0L) return 1f - PvpScoreUi.ease(closingAt, 0f, CLOSE_SECS);
        return PvpScoreUi.ease(openedAt, 0f, OPEN_SECS);
    }

    private void layout(int vw, int vh) {
        widgets.clear();
        pw = Math.min(UiTheme.scaled(780f), vw - UiTheme.scaled(80f));
        ph = Math.min(UiTheme.scaled(640f), vh - UiTheme.scaled(80f));
        px = (vw - pw) / 2f;
        py = (vh - ph) / 2f;
        float pad = UiTheme.scaled(24f);
        float h = UiTheme.scaled(38f);
        float closeW = UiTheme.scaled(140f);
        float explainW = UiTheme.scaled(230f);
        widgets.add(new Button(px + pw - pad - closeW, py + pad, closeW, h, "Fermer", this::close));
        widgets.add(new Button(px + pw - pad - closeW - UiTheme.scaled(12f) - explainW, py + pad, explainW, h, "Comment c'est compté", () -> {
            close();
            explain.run();
        }));
        widgetBaseY.clear();
        for (UiWidget w : widgets) widgetBaseY.add(w.y);
    }

    /**
     * Ouvre le calque d'un bloc qui entre un peu après le précédent : il
     * monte en fondu et grandit légèrement depuis son milieu. À refermer.
     */
    private void pushBlock(UiRenderer r, float delay, float pivotY) {
        float t = PvpScoreUi.linear(openedAt, 0.12f + delay, 0.45f);
        float e = PvpScoreUi.ease(openedAt, 0.12f + delay, 0.45f);
        r.pushLayer(t, 0.94f + 0.06f * PvpScoreUi.backOut(t), px + pw / 2f, pivotY, 0f, -(1f - e) * UiTheme.scaled(22f));
    }

    void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (vw != lastW || vh != lastH || UiTheme.UI_SCALE != lastScale) {
            layout(vw, vh);
            lastW = vw; lastH = vh; lastScale = UiTheme.UI_SCALE;
        }
        float p = presence();
        for (int i = 0; i < widgets.size(); i++) widgets.get(i).y = widgetBaseY.get(i);

        r.drawRoundedRect(0f, 0f, vw, vh, 0f, UiTheme.GLASS_SCRIM.multiplyAlpha(p), vw, vh);
        float y1 = py, y2 = py + ph;
        // La fenêtre entière dans un calque : elle jaillit du centre avec un
        // petit rebond, puis ses blocs arrivent l'un après l'autre ; elle
        // recule en s'effaçant à la fermeture.
        float scale = closingAt != 0L
            ? 0.92f + 0.08f * p
            : 0.84f + 0.16f * PvpScoreUi.backOut(PvpScoreUi.linear(openedAt, 0f, OPEN_SECS));
        r.pushLayer(p, scale, px + pw / 2f, py + ph / 2f, 0f, -(1f - p) * UiTheme.scaled(16f));
        try {
            r.drawShadow(px, y1, px + pw, y2, UiTheme.scaled(14f), 30f, 0f, new UiColor(0, 0, 0, 150), vw, vh);
            r.drawGlassPanel(px, y1, px + pw, y2, UiTheme.scaled(14f),
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL, UiTheme.PANEL_BG,
                UiTheme.GLASS_BORDER, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            float pad = UiTheme.scaled(24f);
            float left = px + pad, right = px + pw - pad;
            float y = y2 - pad;
            pushBlock(r, 0f, y);
            try { y = drawHeader(r, left, right, y, vw, vh); } finally { r.popLayer(); }
            if (report[NativeCombat.REPORT_MY_COUNTS + family] > 0) {
                pushBlock(r, 0.07f, y);
                try { y = drawFacts(r, left, right, y - UiTheme.scaled(18f), vw, vh); } finally { r.popLayer(); }
                pushBlock(r, 0.14f, y);
                try { y = drawActions(r, left, right, y - UiTheme.scaled(20f), vw, vh); } finally { r.popLayer(); }
                pushBlock(r, 0.21f, y);
                try { y = drawVersus(r, left, right, y - UiTheme.scaled(18f), vw, vh); } finally { r.popLayer(); }
            }
            pushBlock(r, 0.28f, y);
            try {
                drawAdvice(r, left, right, y - UiTheme.scaled(16f), y1 + pad + UiTheme.scaled(48f), vw, vh);
            } finally {
                r.popLayer();
            }
            pushBlock(r, 0.34f, y1 + pad);
            try {
                for (UiWidget w : widgets) w.draw(r, mouseX, mouseY, vw, vh);
            } finally {
                r.popLayer();
            }
        } finally {
            r.popLayer();
            PvpScoreUi.alpha = 1f;
        }
    }

    // ── Les blocs ────────────────────────────────────────────────────────────

    /** La famille, un verdict, la note et sur combien de situations elle repose. Rend le bas du bloc. */
    private float drawHeader(UiRenderer r, float left, float right, float top, int vw, int vh) {
        double score = report[NativeCombat.REPORT_FAMILIES + family];
        long events = Math.round(report[NativeCombat.REPORT_MY_COUNTS + family]);
        boolean measured = events > 0;
        float label = UiTheme.scaled(0.42f);
        float scoreScale = UiTheme.scaled(1.7f);
        String value = measured ? String.valueOf(Math.round(score)) : PvpScoreUi.NO_SCORE;
        float valueW = r.textWidth(UiFont.BOLD, value, scoreScale);
        PvpScoreUi.text(r, UiFont.BOLD, value, right - valueW, top - UiTheme.scaled(56f),
            measured ? PvpScoreUi.scoreColor(score) : PvpScoreUi.NEUTRAL, scoreScale, vw, vh);
        float textW = right - left - valueW - UiTheme.scaled(24f);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr(PvpScoreModule.familyName(family, report)).toUpperCase(Locale.ROOT), left, top - UiTheme.scaled(20f),
            textW, UiTheme.TEXT_MUTED, label, vw, vh);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr(verdict(score, measured)), left, top - UiTheme.scaled(54f), textW, UiTheme.TEXT_PRIMARY,
            UiTheme.scaled(0.66f), vw, vh);
        if (measured) {
            String basis = Lang.tr("Mesurée sur") + " " + events + " " + Lang.tr(events > 1 ? "situations de ce duel" : "situation de ce duel");
            float basisScale = UiTheme.scaled(0.4f);
            PvpScoreUi.left(r, UiFont.REGULAR, basis, left, top - UiTheme.scaled(80f), textW, UiTheme.TEXT_SECONDARY, basisScale, vw, vh);
            // Ce que la famille a fait gagner ou perdre à la note : la somme de ses actions.
            if (!detail.actions(family).isEmpty()) {
                double points = detail.points(family);
                String sum = " · " + PvpScoreUi.signedPoints(points) + " " + Lang.tr("dans la note");
                PvpScoreUi.left(r, UiFont.BOLD, sum, left + r.textWidth(UiFont.REGULAR, basis, basisScale), top - UiTheme.scaled(80f),
                    textW, points >= 0 ? PvpScoreUi.GOOD : PvpScoreUi.BAD, basisScale, vw, vh);
            }
        }
        return top - UiTheme.scaled(92f);
    }

    /** Ce qui a fait monter, ce qui a fait baisser : deux cartes côte à côte. Rend leur bas. */
    private float drawFacts(UiRenderer r, float left, float right, float top, int vw, int vh) {
        float gap = UiTheme.scaled(14f);
        float half = (right - left - gap) / 2f;
        float height = UiTheme.scaled(108f);
        drawFactCard(r, left, top - height, left + half, top, true, vw, vh);
        drawFactCard(r, left + half + gap, top - height, right, top, false, vw, vh);
        return top - height;
    }

    private void drawFactCard(UiRenderer r, float x1, float y1, float x2, float y2, boolean up, int vw, int vh) {
        UiColor tone = up ? PvpScoreUi.GOOD : PvpScoreUi.BAD;
        PvpScoreUi.tintedCard(r, x1, y1, x2, y2, tone, 0.16f, vw, vh);
        float inner = UiTheme.scaled(16f);
        float width = x2 - x1 - inner * 2f;
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr(up ? "A fait monter" : "A fait baisser"), x1 + inner, y2 - UiTheme.scaled(28f), width, tone,
            UiTheme.scaled(0.44f), vw, vh);
        List<DuelDetail.Fact> facts = detail.facts(family, up);
        if (facts.isEmpty()) {
            PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Rien de notable"), x1 + inner, y2 - UiTheme.scaled(60f), width, UiTheme.TEXT_MUTED,
                UiTheme.scaled(0.46f), vw, vh);
            return;
        }
        float y = y2 - UiTheme.scaled(60f);
        for (int i = 0; i < Math.min(FACTS_SHOWN, facts.size()); i++) {
            PvpScoreUi.left(r, UiFont.REGULAR, factText(facts.get(i)), x1 + inner, y, width, UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.46f), vw, vh);
            y -= UiTheme.scaled(28f);
        }
    }

    /**
     * Les actions qui ont le plus pesé (quatre au plus), rangées dans l'ordre
     * du duel, les tendances sur tout le duel à la fin ; chacune avec ce
     * qu'elle a rapporté. Une action qui tombe sur un moment déjà décrit par
     * le noyau (« 10 blocs, en sprint ») en reprend la description. Puis
     * « + N autres ». Rend le bas du bloc.
     */
    private float drawActions(UiRenderer r, float left, float right, float top, int vw, int vh) {
        List<DuelDetail.Action> all = detail.actions(family);
        if (all.isEmpty()) {
            return top + UiTheme.scaled(20f);
        }
        List<DuelDetail.Action> shown = new ArrayList<DuelDetail.Action>(all.subList(0, Math.min(ACTIONS_SHOWN, all.size())));
        shown.sort((a, b) -> Double.compare(a.isTrend() ? Double.MAX_VALUE : a.seconds, b.isTrend() ? Double.MAX_VALUE : b.seconds));
        float small = UiTheme.scaled(0.42f);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("CE QUI A RAPPORTÉ"), left, top - UiTheme.scaled(14f), right - left, UiTheme.TEXT_MUTED, small, vw, vh);
        float rowH = UiTheme.scaled(32f);
        float y = top - UiTheme.scaled(26f);
        float body = UiTheme.scaled(0.46f);
        float timeW = UiTheme.scaled(70f);
        float statusW = UiTheme.scaled(110f);
        for (DuelDetail.Action a : shown) {
            PvpScoreUi.rect(r, left, y, right, y + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_BORDER, vw, vh);
            float mid = y - rowH / 2f;
            String when = a.isTrend() ? Lang.tr("duel") : clock(a.seconds);
            PvpScoreUi.left(r, UiFont.BOLD, when, left, PvpScoreUi.middle(mid, body), timeW, UiTheme.TEXT_SECONDARY, body, vw, vh);
            PvpScoreUi.left(r, UiFont.REGULAR, actionText(a), left + timeW, PvpScoreUi.middle(mid, body), right - left - timeW - statusW, UiTheme.TEXT_PRIMARY,
                body, vw, vh);
            String points = PvpScoreUi.signedPoints(a.points) + " " + Lang.tr("pts");
            UiColor tone = Math.round(a.points) > 0 ? PvpScoreUi.GOOD : Math.round(a.points) < 0 ? PvpScoreUi.BAD : UiTheme.TEXT_MUTED;
            PvpScoreUi.right(r, UiFont.BOLD, points, right, PvpScoreUi.middle(mid, body), statusW, tone, body, vw, vh);
            y -= rowH;
        }
        PvpScoreUi.rect(r, left, y, right, y + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_BORDER, vw, vh);
        int others = all.size() - shown.size();
        if (others > 0) {
            String more = "+ " + others + " " + Lang.tr(others > 1 ? "autres" : "autre");
            PvpScoreUi.left(r, UiFont.REGULAR, more, left, PvpScoreUi.middle(y - UiTheme.scaled(14f), body * 0.92f), right - left, UiTheme.TEXT_MUTED,
                body * 0.92f, vw, vh);
            y -= UiTheme.scaled(28f);
        }
        return y;
    }

    /** Vous et lui sur la même mesure — seulement quand elle existe de son côté. Rend le bas du bloc. */
    private float drawVersus(UiRenderer r, float left, float right, float top, int vw, int vh) {
        DuelDetail.Versus v = detail.versus(family);
        if (v == null) {
            return top + UiTheme.scaled(18f);
        }
        float gap = UiTheme.scaled(14f);
        float half = (right - left - gap) / 2f;
        float height = UiTheme.scaled(66f);
        float inner = UiTheme.scaled(16f);
        String[] labels = { "Vous", "Votre adversaire" };
        double[][] values = { v.mine, v.theirs };
        UiColor[] tones = { UiTheme.TEXT_PRIMARY, PvpScoreUi.FOE };
        for (int i = 0; i < 2; i++) {
            float x = left + i * (half + gap);
            PvpScoreUi.card(r, x, top - height, x + half, top, vw, vh);
            PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr(labels[i]), x + inner, top - UiTheme.scaled(24f), half - inner * 2f, UiTheme.TEXT_SECONDARY,
                UiTheme.scaled(0.4f), vw, vh);
            PvpScoreUi.left(r, UiFont.BOLD, fill(VERSUS.getOrDefault(v.code, v.code), values[i]), x + inner, top - UiTheme.scaled(52f), half - inner * 2f,
                tones[i], UiTheme.scaled(0.56f), vw, vh);
        }
        return top - height;
    }

    /** Un conseil, tiré du fait qui a le plus fait baisser la famille ; sinon celui de la famille. */
    private void drawAdvice(UiRenderer r, float left, float right, float top, float bottom, int vw, int vh) {
        List<DuelDetail.Fact> down = detail.facts(family, false);
        String advice = down.isEmpty() ? null : ADVICE.get(down.get(0).code);
        if (advice == null) advice = (PvpScoreModule.isSword(report) ? SWORD_FAMILY_ADVICE : FAMILY_ADVICE)[family];
        float scale = UiTheme.scaled(0.42f);
        float y = top - UiTheme.scaled(14f);
        for (String line : PvpScoreUi.wrap(r, UiFont.REGULAR, Lang.tr(advice), scale, right - left)) {
            if (y < bottom) break;
            PvpScoreUi.text(r, UiFont.REGULAR, line, left, y, UiTheme.TEXT_SECONDARY, scale, vw, vh);
            y -= UiTheme.scaled(22f);
        }
    }

    // ── Les textes ───────────────────────────────────────────────────────────

    private static String verdict(double score, boolean measured) {
        if (!measured) return "Pas mesurée dans ce duel";
        if (score >= PvpScoreUi.GOOD_FROM) return "Un point fort de ce duel";
        if (score >= PvpScoreUi.POOR_UNDER) return "Dans votre moyenne";
        return "Le point à travailler";
    }

    /**
     * Les actions, par code (ceux des {@code Norm} de
     * {@code content-core/src/combat/score/features/}) : ce qu'on écrit quand
     * elle a rapporté, puis quand elle a coûté. Un code inconnu (noyau plus
     * récent que l'écran) s'affiche tel quel.
     */
    private static final Map<String, String[]> ACTIONS = new HashMap<String, String[]>();
    /** Les actions dont l'attendu est une chance propre à la situation : on la montre. */
    private static final Map<String, Boolean> CHANCES = new HashMap<String, Boolean>();

    static {
        ACTIONS.put("conversion.opening", new String[]{ "Ouverture convertie", "Ouverture ratée" });
        ACTIONS.put("smash.hit", new String[]{ "Smash bien tiré", "Smash mal tiré" });
        ACTIONS.put("smash.spear", new String[]{ "Charge de lance bien tirée", "Charge de lance mal tirée" });
        ACTIONS.put("smash.none", new String[]{ "Aucun smash sur ses ouvertures", "Aucun smash sur ses ouvertures" });
        ACTIONS.put("defense.blocked", new String[]{ "Smash arrêté au bouclier", "Smash reçu ou garde cassée" });
        ACTIONS.put("defense.ground_shield", new String[]{ "Pas planté au sol bouclier levé", "Planté au sol bouclier levé" });
        ACTIONS.put("defense.shield_efficiency", new String[]{ "Bouclier levé au bon moment", "Bouclier levé pour rien" });
        ACTIONS.put("defense.openings", new String[]{ "Son ouverture ratée", "Son ouverture convertie" });
        ACTIONS.put("defense.vulnerable", new String[]{ "Peu de temps au sol sous lui", "Longtemps au sol sous lui" });
        ACTIONS.put("defense.dodge", new String[]{ "Descente esquivée", "Descente pas esquivée" });
        ACTIONS.put("defense.landing", new String[]{ "Chute après un smash raté", "Chute après un smash raté" });
        ACTIONS.put("defense.recovery", new String[]{ "Dégagé vite après un coup", "Lent à vous dégager après un coup" });
        ACTIONS.put("control.reached", new String[]{ "Descente arrivée à sa portée", "Descente arrivée trop loin" });
        ACTIONS.put("control.caught", new String[]{ "Distance rattrapée en tombant", "Distance pas rattrapée" });
        ACTIONS.put("control.first_time", new String[]{ "Grosse descente finie par un smash", "Grosse descente sans smash" });
        ACTIONS.put("control.climb_speed", new String[]{ "Montée rapide", "Montée lente" });
        ACTIONS.put("control.retake", new String[]{ "Hauteur reprise vite", "Hauteur reprise tard" });
        ACTIONS.put("control.wind_height", new String[]{ "Charge de vent qui monte haut", "Charge de vent qui monte peu" });
        ACTIONS.put("control.domination", new String[]{ "Souvent au-dessus de lui", "Souvent en dessous de lui" });
        ACTIONS.put("control.peak", new String[]{ "Bien placé au sommet", "Loin de lui au sommet" });
        ACTIONS.put("control.closing", new String[]{ "Vous vous rapprochez plus vite qu'il ne fuit", "Il fuit plus vite que vous n'approchez" });
        ACTIONS.put("control.lunge", new String[]{ "Élan de Lunge à portée", "Élan de Lunge dans le vide" });
        ACTIONS.put("control.glide", new String[]{ "Vol plané jusqu'à une ouverture", "Vol plané sans ouverture" });
        ACTIONS.put("control.follow_up", new String[]{ "Smash enchaîné sans toucher le sol", "Smash sans suite" });
        ACTIONS.put("control.chain_length", new String[]{ "Longue chaîne de smashs", "Chaîne de smashs courte" });
        ACTIONS.put("control.rebuild", new String[]{ "Ouverture retrouvée vite", "Ouverture retrouvée lentement" });
        ACTIONS.put("control.initiative", new String[]{ "Ouverture que vous vous êtes créée", "Ouverture qu'il s'est créée" });
        ACTIONS.put("defense.exchange", new String[]{ "Coup porté", "Coup reçu" });
        ACTIONS.put("resources.useful", new String[]{ "Charge de vent ou perle utile", "Charge de vent ou perle sans raison" });
        ACTIONS.put("resources.effective", new String[]{ "Charge de vent ou perle efficace", "Charge de vent ou perle sans effet" });
        ACTIONS.put("resources.answered", new String[]{ "Menace traitée à la charge de vent", "Menace laissée sans réponse" });
        ACTIONS.put("resources.mace_ready", new String[]{ "Masse en main à temps", "Masse en main trop tard" });
        ACTIONS.put("resources.wind_ready", new String[]{ "Charge de vent prête à l'atterrissage", "Charge de vent prête trop tard" });
        ACTIONS.put("resources.apple_ready", new String[]{ "Pomme en main quand vous étiez bas", "Pomme en main trop tard" });
        ACTIONS.put("resources.switch", new String[]{ "Changement d'objet rapide", "Changement d'objet lent" });
        ACTIONS.put("resources.apple", new String[]{ "Pomme d'or au bon moment", "Pomme d'or trop tôt" });
        ACTIONS.put("resources.meal", new String[]{ "Repas à l'abri", "Repas sous sa menace" });
        ACTIONS.put("resources.hunger", new String[]{ "Barre de faim tenue", "Barre de faim basse" });
        ACTIONS.put("resources.low_time", new String[]{ "Peu de temps bas en vie", "Longtemps bas en vie" });
        ACTIONS.put("resources.heal", new String[]{ "Vie remontée vite", "Vie remontée lentement" });
        ACTIONS.put("resources.totem_refill", new String[]{ "Totem remis vite", "Totem remis tard" });
        ACTIONS.put("resources.totem_balance", new String[]{ "Totem forcé", "Totem perdu" });
        ACTIONS.put("resources.potion_active", new String[]{ "Potions actives au contact", "Potions tombées au contact" });
        ACTIONS.put("resources.potion_renew", new String[]{ "Potion reprise à temps", "Potion reprise tard" });
        ACTIONS.put("resources.potion_safe", new String[]{ "Potion bue à l'abri", "Potion bue sous sa menace" });
        ACTIONS.put("resources.rocket", new String[]{ "Fusée qui mène à une ouverture", "Fusée sans ouverture" });
        // L'épée.
        ACTIONS.put("sword.keep", new String[]{ "Main gardée après votre coup", "Main perdue après votre coup" });
        ACTIONS.put("sword.wtap", new String[]{ "W-tap réussi", "W-tap raté" });
        ACTIONS.put("sword.length", new String[]{ "Long combo", "Combo court" });
        ACTIONS.put("sword.hit", new String[]{ "Coup complet", "Coup mal chargé" });
        ACTIONS.put("sword.knockback", new String[]{ "Coup qui le repousse", "Coup sans recul" });
        ACTIONS.put("sword.break", new String[]{ "Son combo cassé", "Son combo continue" });
        ACTIONS.put("sword.jump_reset", new String[]{ "Jump reset", "Coup reçu sans jump reset" });
        ACTIONS.put("sword.first_hit", new String[]{ "Échange ouvert par vous", "Échange ouvert par lui" });
        ACTIONS.put("sword.strafe", new String[]{ "Déplacements de côté", "Déplacements droit sur lui" });
        CHANCES.put("sword.wtap", false);

        CHANCES.put("conversion.opening", false);
        CHANCES.put("control.reached", false);
        CHANCES.put("defense.dodge", false);
        // Ses ouvertures : l'attendu est qu'elles ne passent pas, on montre SA chance.
        CHANCES.put("defense.openings", true);
    }

    /**
     * Une action : la description d'un moment du noyau au même instant s'il y
     * en a un (« Ouverture facile, 4 blocs de chute »), sinon son libellé ; et
     * sa chance quand elle en a une propre à sa situation.
     */
    private String actionText(DuelDetail.Action a) {
        String text = null;
        if (!a.isTrend()) {
            for (DuelDetail.Moment m : detail.moments(family)) {
                if (Math.abs(m.seconds - a.seconds) < 0.15) {
                    text = momentText(m)[0];
                    break;
                }
            }
        }
        if (text == null) {
            String[] labels = ACTIONS.get(a.code);
            text = labels == null ? a.code : Lang.tr(labels[a.points >= 0 ? 0 : 1]);
        }
        Boolean inverted = CHANCES.get(a.code);
        if (inverted != null) {
            double chance = inverted ? 1.0 - a.expected : a.expected;
            text += " (" + Lang.tr("chance") + " " + Math.round(chance * 100) + " %)";
        }
        return text;
    }

    /**
     * Les faits, par code (ceux de {@code content-core/src/combat/score/features/}).
     * {@code {0}}, {@code {1}}… sont les nombres du fait ; {@code {s0}} met un
     * « s » quand le premier vaut 2 ou plus.
     */
    private static final Map<String, String> FACTS = new HashMap<String, String>();
    /** Le face-à-face, par code. */
    private static final Map<String, String> VERSUS = new HashMap<String, String>();
    /** Le conseil qui suit le fait qui a le plus coûté, par code. */
    private static final Map<String, String> ADVICE = new HashMap<String, String>();
    private static final String[] FAMILY_ADVICE = {
        "Les ouvertures faciles, collé à lui sur une chute courte, sont celles qu'il ne faut pas laisser passer.",
        "Un smash critique fait 1,5 fois ses dégâts : la barre d'attaque pleine, et pas de sprint au moment du coup.",
        "Levez le bouclier quand il tombe sur vous, et ne restez pas au sol sous lui.",
        "Suivez-le pendant votre chute pour que le gros coup passe du premier coup.",
        "Chaque charge de vent doit servir : monter pour attaquer, casser son smash ou vous dégager.",
    };
    /** Les conseils de l'épée, famille par famille (la cinquième n'existe pas). */
    private static final String[] SWORD_FAMILY_ADVICE = {
        "Après chaque coup, relancez le sprint et attendez la barre pleine : le coup suivant le repousse avant qu'il puisse répondre.",
        "Frappez barre pleine, en sprint pour le repousser, ou en tombant pour un critique.",
        "Quand il enchaîne, sautez au moment du coup reçu pour prendre moins de recul, et reprenez la main vite.",
        "Touchez-le au bout de votre portée, puis ne lui rentrez pas dedans : gardez la distance entre deux coups.",
        "",
    };

    static {
        FACTS.put("conversion.converted", "{0} ouverture{s0} convertie{s0} sur {1}, un joueur moyen en aurait converti {2}");
        FACTS.put("conversion.easy_missed", "{0} ouverture{s0} facile{s0} ratée{s0}");
        FACTS.put("conversion.hard_landed", "{0} ouverture{s0} difficile{s0} réussie{s0}");
        FACTS.put("smash.none", "Aucun smash réussi sur {0} ouverture{s0}");
        FACTS.put("smash.on_time", "{0} smash{s0} sur {1} au bas de la chute");
        FACTS.put("smash.crit", "{0} critique{s0} sur {1} smashs");
        FACTS.put("smash.sprint", "En sprint au moment du coup : {0} fois");
        FACTS.put("smash.charge", "Barre d'attaque pas pleine : {0} fois");
        FACTS.put("defense.blocked", "{0} smash{s0} arrêté{s0} en l'air, {1} au sol");
        FACTS.put("defense.broken", "Bouclier cassé par une hache {0} fois");
        FACTS.put("defense.openings", "Il a converti {0} de ses {1} ouvertures");
        FACTS.put("defense.vulnerable", "{0} s au sol sous lui, à sa portée");
        FACTS.put("defense.dodge", "{0} de ses {1} descentes esquivées");
        FACTS.put("defense.landing", "{0} cœur{s0} perdu{s0} en chute après un smash raté");
        FACTS.put("defense.recovery", "{0} s en moyenne pour vous dégager après un coup");
        FACTS.put("control.reached", "{0} descente{s0} sur {1} arrivée{s0} à sa portée");
        FACTS.put("control.first_time", "{0} grosse{s0} descente{s0} sur {1} finie{s0} par un smash");
        FACTS.put("control.retake", "{0} s en moyenne pour repasser au-dessus de lui");
        FACTS.put("control.peak", "À {0} blocs de lui au sommet, en moyenne");
        FACTS.put("control.chained", "{0} smash{s0} sur {1} suivi{s0} d'une autre ouverture sans toucher le sol");
        FACTS.put("control.rebuild", "{0} s en moyenne pour retrouver une ouverture");
        FACTS.put("control.initiative", "{0} ouverture{s0} créée{s0}, {1} pour lui");
        FACTS.put("defense.exchange", "{0} coup{s0} porté{s0}, {1} reçu{s1}");
        FACTS.put("sword.keep", "Main gardée après {0} de vos {1} coups");
        FACTS.put("sword.wtap", "{0} W-tap{s0} réussi{s0} sur {1}");
        FACTS.put("sword.wtap_distance", "{0} W-tap{s0} raté{s0} : vous lui êtes rentré dedans");
        FACTS.put("sword.wtap_charge", "{0} W-tap{s0} raté{s0} : barre pas rechargée à temps");
        FACTS.put("sword.wtap_late", "{0} W-tap{s0} raté{s0} : coup suivant trop tard");
        FACTS.put("sword.length", "Plus long combo : {0} coups, {1} combo{s1} de deux coups ou plus");
        FACTS.put("sword.full", "{0} coup{s0} sur {1} à barre pleine");
        FACTS.put("sword.kinds", "{0} coup{s0} sprinté{s0}, {1} critique{s1}");
        FACTS.put("sword.knockback", "Le recul donné par vos coups");
        FACTS.put("sword.break", "{0} de ses {1} enchaînements cassés");
        FACTS.put("sword.jump_reset", "{0} jump reset{s0} sur {1} coups reçus au sol");
        FACTS.put("sword.first_hit", "{0} échange{s0} ouvert{s0} sur {1}");
        FACTS.put("sword.strafe", "Vos déplacements de côté face à lui");
        FACTS.put("resources.useful", "{0} charge{s0} de vent et perle{s0} utile{s0} sur {1}");
        FACTS.put("resources.mace_ready", "Masse en main à temps : {0} fois sur {1}");
        FACTS.put("resources.apples", "{0} pomme{s0} d'or sur {1} mangée{s0} au bon moment");
        FACTS.put("resources.totem_refill", "{0} s en moyenne pour remettre un totem");

        VERSUS.put("conversion.converted", "{0} ouverture{s0} sur {1}");
        VERSUS.put("smash.landed", "{0} smash{s0} réussi{s0}");
        VERSUS.put("smash.crit", "{0} critique{s0} sur {1}");
        VERSUS.put("defense.dodge", "{0} descente{s0} esquivée{s0} sur {1}");
        VERSUS.put("defense.blocked_air", "{0} smash{s0} arrêté{s0} en l'air");
        VERSUS.put("control.reached", "{0} descente{s0} sur {1} à portée");
        VERSUS.put("control.first_time", "{0} gros coup{s0} sur {1} du premier coup");
        VERSUS.put("resources.totem_refill", "Totem remis en {0} s");
        VERSUS.put("control.initiative", "{0} ouverture{s0} créée{s0}");
        VERSUS.put("sword.longest", "Plus long combo : {0}");
        VERSUS.put("sword.crits", "{0} critique{s0}");
        VERSUS.put("sword.jump_reset", "{0} jump reset{s0} sur {1}");
        // Hors note : la distance entre deux joueurs est la même pour les deux.
        VERSUS.put("sword.range", "Coups donnés à {0} blocs en moyenne");
        VERSUS.put("sword.spacing", "{0} fois rentré dedans entre deux coups");

        ADVICE.put("conversion.easy_missed", "Une ouverture facile, c'est collé à lui sur une chute courte : frappez-la sans attendre.");
        ADVICE.put("smash.none", "Une ouverture qui passe sans coup ne rapporte rien : frappez pendant la chute, même trop tôt plutôt que jamais.");
        ADVICE.put("smash.crit", "Un smash critique fait 1,5 fois ses dégâts : la barre d'attaque pleine, et pas de sprint au moment du coup.");
        ADVICE.put("smash.sprint", "Le sprint empêche le critique : relâchez-le avant de frapper.");
        ADVICE.put("smash.charge", "La barre d'attaque de la masse se remplit lentement : attendez-la avant de frapper.");
        ADVICE.put("smash.on_time", "Attendez le bas de la chute : chaque bloc de plus ajoute des dégâts.");
        ADVICE.put("defense.broken", "Une hache casse le bouclier pour 5 secondes : baissez-le quand il sort sa hache.");
        ADVICE.put("defense.vulnerable", "Au sol sous lui, vous êtes la cible : remontez ou sortez de sa zone.");
        ADVICE.put("defense.dodge", "Pendant sa descente, sortez de sa zone de 3,5 blocs au dernier moment.");
        ADVICE.put("defense.landing", "Un smash raté ne remet pas la chute à zéro : gardez une charge de vent pour amortir.");
        ADVICE.put("control.first_time", "Suivez-le pendant votre chute pour que le gros coup passe du premier coup.");
        ADVICE.put("control.reached", "Commencez vos descentes juste au-dessus de lui, pas à côté.");
        ADVICE.put("control.retake", "Quand il passe au-dessus de vous, reprenez la hauteur tout de suite.");
        ADVICE.put("control.initiative", "Prenez la hauteur le premier : celui qui se crée les ouvertures mène le duel.");
        ADVICE.put("defense.exchange", "Ne restez pas à portée quand vous ne pouvez pas frapper : chaque coup reçu compte.");
        ADVICE.put("sword.wtap", "Relancez le sprint entre deux coups, et frappez dès que la barre est pleine : c'est ce qui le repousse avant qu'il réponde.");
        ADVICE.put("sword.wtap_distance", "Après votre coup, relâchez l'avant ou reculez un instant : ne lui rentrez pas dedans.");
        ADVICE.put("sword.wtap_charge", "Attendez la barre pleine : un coup trop tôt ne donne pas de recul, et il revient sur vous.");
        ADVICE.put("sword.wtap_late", "Frappez dès qu'il revient dans votre portée, barre pleine : plus tard, c'est lui qui touche.");
        ADVICE.put("sword.keep", "Après chaque coup, gardez la distance et la barre pleine pour retoucher avant lui.");
        ADVICE.put("sword.break", "Quand il enchaîne, sautez au moment du coup et reprenez la main au lieu d'encaisser.");
        ADVICE.put("sword.jump_reset", "Sautez au moment où son coup arrive : vous prenez moins de recul et revenez plus vite.");
        ADVICE.put("sword.first_hit", "Entrez dans sa portée au moment où votre barre est pleine, pour porter le premier coup.");
        ADVICE.put("sword.strafe", "À portée, déplacez-vous de côté plutôt que droit sur lui.");
        ADVICE.put("resources.useful", "Chaque charge de vent doit servir : monter pour attaquer, casser son smash ou vous dégager.");
        ADVICE.put("resources.mace_ready", "Passez à la masse dès que l'ouverture se dessine.");
        ADVICE.put("resources.totem_refill", "Remettez un totem dans l'autre main dès que le premier saute.");
    }

    private static String factText(DuelDetail.Fact f) {
        return fill(FACTS.getOrDefault(f.code, f.code), f.values);
    }

    /** Un moment : sa description, et son état à droite (vide s'il n'en a pas). */
    private static String[] momentText(DuelDetail.Moment m) {
        double[] v = m.values;
        switch (m.code) {
            case "conversion.easy_missed":
                return new String[]{ fill("Ouverture facile, {0} blocs de chute", v), Lang.tr("ratée") };
            case "conversion.hard_landed":
                return new String[]{ fill("Ouverture difficile, {0} blocs de chute", v), Lang.tr("réussie") };
            case "smash.hit": {
                StringBuilder text = new StringBuilder(fill("{0} blocs", v));
                if (v.length > 1 && v[1] >= 0) text.append(", ").append(Lang.tr("charge")).append(' ').append(number(v[1])).append(" %");
                if (v.length > 2 && v[2] == 1) text.append(", ").append(Lang.tr("en sprint"));
                String status = v.length > 3 && v[3] >= 0 ? Lang.tr(v[3] == 1 ? "critique" : "pas de critique") : "";
                return new String[]{ text.toString(), status };
            }
            case "defense.blocked":
                return new String[]{ fill(v.length > 1 && v[1] == 1 ? "Smash de {0} blocs, en l'air" : "Smash de {0} blocs, au sol", v), Lang.tr("arrêté") };
            case "defense.broken":
                return new String[]{ Lang.tr("Bouclier cassé par une hache"), Lang.tr("5 s sans bouclier") };
            case "defense.smashed":
                return new String[]{ fill("Son smash de {0} blocs", v), Lang.tr("reçu") };
            case "defense.landing":
                return new String[]{ fill("Chute après un smash raté", v), fill("{0} cœur{s0} perdu{s0}", v) };
            case "control.big_descent":
                return new String[]{ fill("Grosse descente de {0} blocs", v), Lang.tr(m.good ? "smash du premier coup" : "sans smash") };
            case "resources.wasted":
                return new String[]{ Lang.tr(v.length > 0 && v[0] == 1 ? "Perle sans effet" : "Charge de vent sans effet"), Lang.tr("gâchée") };
            case "resources.totem":
                return v.length > 0 && v[0] >= 0
                    ? new String[]{ Lang.tr("Totem sauté"), fill("remis en {0} s", v) }
                    : new String[]{ Lang.tr("Totem sauté"), Lang.tr("pas remis") };
            case "sword.wtap_failed": {
                int reason = v.length > 0 ? (int) v[0] : 0;
                String why = reason == 1 ? "Rentré dans l'adversaire"
                    : reason == 2 ? "Barre pas rechargée au coup suivant"
                    : reason == 3 ? "Coup suivant trop tard"
                    : "Coup reçu après le W-tap";
                return new String[]{ Lang.tr(why), Lang.tr("W-tap raté") };
            }
            case "sword.combo":
                return new String[]{ fill("Combo de {0} coups", v), Lang.tr("tenu") };
            default:
                return new String[]{ m.code, "" };
        }
    }

    /** Traduit un modèle et y met ses nombres. */
    private static String fill(String template, double[] values) {
        String text = Lang.tr(template);
        for (int i = 0; i < values.length; i++) {
            text = text.replace("{s" + i + "}", values[i] >= 2 ? "s" : "").replace("{" + i + "}", number(values[i]));
        }
        return text;
    }

    /** Un nombre comme on l'écrit : entier sans virgule, sinon une décimale. */
    private static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) return String.valueOf(Math.round(value));
        return String.format(Locale.FRANCE, "%.1f", value);
    }

    /** « 0:44 » depuis l'ouverture du duel ; les dix secondes d'avant comptent pour 0:00. */
    private static String clock(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Un bouton de la fenêtre : le contour s'éclaire au survol, il suit le fondu. */
    private static final class Button extends UiWidget {
        private final String label;
        private final Runnable action;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);

        Button(float x, float y, float w, float h, String label, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float t = hover.get();
            float radius = UiTheme.scaled(8f);
            PvpScoreUi.rect(r, x - 1f, y - 1f, x + w + 1f, y + h + 1f, radius + 1f, UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.ACCENT, t), vw, vh);
            PvpScoreUi.rect(r, x, y, x + w, y + h, radius, UiColor.lerp(UiTheme.CARD_BG, UiTheme.ACCENT.withAlpha(0.5f), t), vw, vh);
            float scale = UiTheme.scaled(0.46f);
            PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr(label), x + w / 2f, PvpScoreUi.middle(y + h / 2f, scale), w - UiTheme.scaled(12f),
                UiTheme.TEXT_PRIMARY, scale, vw, vh);
        }

        @Override
        public void onClick() {
            action.run();
        }
    }
}
