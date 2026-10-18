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
 * <p><b>Elle montre des faits, jamais le calcul.</b> Un joueur conteste une
 * note qu'il ne comprend pas, pas un fait qu'il se rappelle avoir vécu. On y
 * lit donc des compteurs (« 0 critique sur 5 »), des moments datés depuis
 * l'ouverture du duel (« 0:44, 10 blocs, en sprint ») et la même mesure chez
 * l'adversaire quand elle existe de son côté. Ni poids, ni centre, ni
 * référence, ni formule : le « comment c'est compté » reste dans
 * {@link PvpInfoModal}, à un bouton de là.
 *
 * <p>De haut en bas : la famille, un verdict et la note ; ce qui l'a fait
 * monter et baisser (deux faits au plus de chaque côté, les plus forts) ;
 * trois moments au plus, dans l'ordre du duel ; le face-à-face ; un conseil
 * tiré du fait qui a le plus coûté. Tout vient du noyau ({@link DuelDetail}) ;
 * ici on ne fait que traduire et ranger.
 */
final class PvpFamilyModal {

    /** Deux faits au plus de chaque côté, trois moments au plus. */
    private static final int FACTS_SHOWN = 2;
    private static final int MOMENTS_SHOWN = 3;

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
                try { y = drawMoments(r, left, right, y - UiTheme.scaled(20f), vw, vh); } finally { r.popLayer(); }
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
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr(PvpScoreModule.FAMILIES[family]).toUpperCase(Locale.ROOT), left, top - UiTheme.scaled(20f),
            textW, UiTheme.TEXT_MUTED, label, vw, vh);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr(verdict(score, measured)), left, top - UiTheme.scaled(54f), textW, UiTheme.TEXT_PRIMARY,
            UiTheme.scaled(0.66f), vw, vh);
        if (measured) {
            String basis = Lang.tr("Mesurée sur") + " " + events + " " + Lang.tr(events > 1 ? "situations de ce duel" : "situation de ce duel");
            PvpScoreUi.left(r, UiFont.REGULAR, basis, left, top - UiTheme.scaled(80f), textW, UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.4f), vw, vh);
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

    /** Trois moments au plus, les plus parlants, rangés dans l'ordre du duel ; puis « + N autres ». Rend le bas du bloc. */
    private float drawMoments(UiRenderer r, float left, float right, float top, int vw, int vh) {
        List<DuelDetail.Moment> all = detail.moments(family);
        if (all.isEmpty()) {
            return top + UiTheme.scaled(20f);
        }
        List<DuelDetail.Moment> shown = new ArrayList<DuelDetail.Moment>(all.subList(0, Math.min(MOMENTS_SHOWN, all.size())));
        shown.sort((a, b) -> Double.compare(a.seconds, b.seconds));
        float small = UiTheme.scaled(0.42f);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("MOMENTS DU DUEL"), left, top - UiTheme.scaled(14f), right - left, UiTheme.TEXT_MUTED, small, vw, vh);
        float rowH = UiTheme.scaled(32f);
        float y = top - UiTheme.scaled(26f);
        float body = UiTheme.scaled(0.46f);
        float timeW = UiTheme.scaled(70f);
        float statusW = UiTheme.scaled(170f);
        for (DuelDetail.Moment m : shown) {
            PvpScoreUi.rect(r, left, y, right, y + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_BORDER, vw, vh);
            float mid = y - rowH / 2f;
            String[] text = momentText(m);
            PvpScoreUi.left(r, UiFont.BOLD, clock(m.seconds), left, PvpScoreUi.middle(mid, body), timeW, UiTheme.TEXT_SECONDARY, body, vw, vh);
            PvpScoreUi.left(r, UiFont.REGULAR, text[0], left + timeW, PvpScoreUi.middle(mid, body), right - left - timeW - statusW, UiTheme.TEXT_PRIMARY,
                body, vw, vh);
            if (!text[1].isEmpty()) {
                PvpScoreUi.right(r, UiFont.BOLD, text[1], right, PvpScoreUi.middle(mid, body), statusW, m.good ? PvpScoreUi.GOOD : PvpScoreUi.BAD, body, vw, vh);
            }
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
        if (advice == null) advice = FAMILY_ADVICE[family];
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
        if (score >= 65) return "Un point fort de ce duel";
        if (score >= 45) return "Dans votre moyenne";
        return "Le point à travailler";
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
