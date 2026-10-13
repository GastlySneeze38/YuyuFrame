package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.combat.CombatTracker;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Score PvP (docs/scoring/) : allumé, le module relève les duels à la masse,
 * le noyau natif les reconnaît, les note et écrit journal et résumé dans
 * {@code yuyuframe/matches/} du dossier de l'instance. À la fin d'un duel, un
 * panneau montre la note quelques secondes.
 *
 * <p>Le module ne contient aucune logique de jeu. Java relève
 * ({@link CombatTracker}) et affiche ; Rust reconnaît les duels et calcule
 * ({@code content-core/src/combat/}). Le résumé arrive ici en nombres, déjà
 * calculés ({@link NativeCombat#report}).
 *
 * <p>Une touche ouvre ou ferme un duel à la main, en recours : le noyau le
 * fait de lui-même sur les serveurs qu'il sait lire.
 *
 * <p>Éteint par défaut : tant que le score n'est pas calibré, personne ne
 * doit se retrouver avec des journaux écrits sans l'avoir demandé.
 */
public final class PvpScoreModule extends LauncherModule {

    /** Les versions dont les mixins de combat existent ({@code apimixin/<version>/combat}). */
    public static final java.util.Set<String> VERSIONS = java.util.Set.of("26.1.2", "26.3");

    private static final String SOUND = "minecraft:block.note_block.pling";
    /** Durée d'affichage du résumé. */
    private static final long SHOWN_NANOS = 12_000_000_000L;

    /** Libellés des six familles, dans l'ordre de {@link NativeCombat#REPORT_FAMILIES}. */
    private static final String[] FAMILIES = {
        "Conversion", "Qualité du smash", "Défense", "Contrôle vertical", "Ressources", "Niveau adverse",
    };
    private static final String[] RESULTS = { "", "Victoire", "Défaite", "Match nul", "" };

    /** La touche qui ouvre et ferme un duel à la main. */
    public String matchKey = "K";

    private boolean keyWasDown;

    private final double[] incoming = new double[NativeCombat.REPORT_LEN];
    /** Le résumé affiché, et le numéro du dernier reçu — celui d'avant le lancement n'est pas montré. */
    private final double[] shown = new double[NativeCombat.REPORT_LEN];
    private int lastReport = NativeCombat.report(new double[NativeCombat.REPORT_LEN]);
    private long shownUntilNanos;
    private boolean drawnInGuiPass;

    public PvpScoreModule() {
        super("pvp-score", "Score PvP",
            "Note vos duels à la masse et affiche le résultat à la fin de chacun. En cours de développement : la note n'est pas encore calibrée.",
            "Note de vos duels à la masse", false,
            // DÉCLARÉS, sinon les mixins correspondants ne sont pas tissés du
            // tout (voir MixinHookPointRegistry).
            HookPoint.CLIENT_TICK, HookPoint.SERVER_CONNECT, HookPoint.CLIENT_LEVEL_LOAD,
            HookPoint.ENTITY_ATTACK, HookPoint.DAMAGE_EVENT, HookPoint.ENTITY_EVENT, HookPoint.ITEM_USE);
        iconUrl = icons8("sword");
        CombatTracker.install();
        CombatTracker.setActive(isEnabled());
    }

    @Override
    protected void settings(SettingList s) {
        s.keybind("matchKey", "Touche du duel",
            "Ouvre ou ferme un duel à la main, quand il n'est pas reconnu tout seul.",
            "Réglages", null, () -> matchKey, v -> matchKey = v);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        CombatTracker.setActive(enabled);
    }

    /**
     * À chaque image : la touche, et l'arrivée d'un nouveau résumé.
     *
     * <p>La touche est lue ici et non à chaque tick du jeu : un appui bref
     * tient moins d'un vingtième de seconde et serait manqué. Elle est
     * ignorée quand un écran est ouvert — sinon taper la lettre dans le chat
     * ouvrirait un duel.
     */
    @Override
    public void onTick() {
        UiInputPollerModern input = UiInputPollerModern.ACTIVE;
        boolean down = input != null && input.isKeyDownByName(matchKey)
            && AccessorRegistry.get(AccessPoint.CLIENT_SCREEN, null) == null;
        if (down && !keyWasDown) {
            boolean before = CombatTracker.recording();
            boolean after = CombatTracker.toggleMatch();
            if (after != before) {
                ClientData.playUiSound(SOUND, after ? 1.8f : 0.7f, 1f);
            }
        }
        keyWasDown = down;

        int number = NativeCombat.report(incoming);
        if (number != lastReport) {
            lastReport = number;
            System.arraycopy(incoming, 0, shown, 0, shown.length);
            shownUntilNanos = System.nanoTime() + SHOWN_NANOS;
        }
    }

    // Deux points d'entrée pour le même dessin, comme LowHealthTintModule : la
    // passe GUI du jeu quand elle existe, l'appel après présentation sinon.
    @Override
    public void onRenderInVanillaGui(UiRenderer renderer, int vpWidth, int vpHeight) {
        drawnInGuiPass = true;
        drawSummary(renderer, vpWidth, vpHeight);
    }

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        if (drawnInGuiPass) {
            drawnInGuiPass = false;
            return;
        }
        drawSummary(renderer, vpWidth, vpHeight);
    }

    /**
     * Le résumé du dernier duel, en haut de l'écran, au centre : le résultat,
     * la note et sa confiance, les cinq familles, puis le ping et les
     * opportunités. Sans note, la raison prend sa place.
     *
     * <p>Repère du moteur : l'origine est en bas à gauche, y monte.
     */
    private void drawSummary(UiRenderer r, int vpWidth, int vpHeight) {
        if (System.nanoTime() >= shownUntilNanos) {
            return;
        }
        float pad = UiTheme.scaled(12f);
        float row = UiTheme.scaled(16f);
        float width = UiTheme.scaled(230f);
        float small = UiTheme.scaled(0.42f);
        float normal = UiTheme.scaled(0.5f);
        float big = UiTheme.scaled(1.25f);
        // Assez haut pour que la note ne touche pas l'en-tête (vu sur capture
        // le 2026-10-13 : à 30, « Score PvP » mordait sur le chiffre).
        float bigHeight = UiTheme.scaled(38f);
        int footerRows = shown[NativeCombat.REPORT_PING] >= 0 ? 3 : 2;

        float height = pad * 2 + row + bigHeight + row + NativeCombat.FAMILY_COUNT * row + row * 0.6f + footerRows * row;
        float left = (vpWidth - width) / 2f;
        float right = left + width;
        float top = vpHeight - UiTheme.scaled(36f);
        float bottom = top - height;

        r.drawShadow(left, bottom, right, top, UiTheme.RADIUS_SM, 14f, 0f, new UiColor(0, 0, 0, 110), vpWidth, vpHeight);
        r.drawRoundedRect(left, bottom, right, top, UiTheme.RADIUS_SM, UiTheme.PANEL_BG, vpWidth, vpHeight);

        // En-tête : le nom à gauche, le résultat à droite.
        float y = top - pad - row;
        r.drawText(Lang.tr("Score PvP"), left + pad, y, UiTheme.TEXT_SECONDARY, small, vpWidth, vpHeight);
        int result = (int) shown[NativeCombat.REPORT_RESULT];
        String resultText = result >= 0 && result < RESULTS.length ? Lang.tr(RESULTS[result]) : "";
        if (!resultText.isEmpty()) {
            UiColor color = result == 1 ? UiTheme.SUCCESS : result == 2 ? UiTheme.DANGER : UiTheme.TEXT_SECONDARY;
            r.drawText(resultText, right - pad - r.textWidth(resultText, small), y, color, small, vpWidth, vpHeight);
        }

        // La note, ou pourquoi il n'y en a pas.
        y -= bigHeight;
        double score = shown[NativeCombat.REPORT_SCORE];
        if (score >= 0) {
            String text = String.valueOf(Math.round(score));
            r.drawText(text, left + pad, y, UiTheme.TEXT_PRIMARY, big, vpWidth, vpHeight);
            r.drawText("/ 100", left + pad + r.textWidth(text, big) + UiTheme.scaled(6f), y, UiTheme.TEXT_MUTED, normal, vpWidth, vpHeight);
            String confidence = Lang.tr("Confiance") + " " + Math.round(shown[NativeCombat.REPORT_CONFIDENCE] * 100) + " %";
            r.drawText(confidence, right - pad - r.textWidth(confidence, small), y, UiTheme.TEXT_SECONDARY, small, vpWidth, vpHeight);
        } else {
            String reason = shown[NativeCombat.REPORT_REASON] == 1 ? "Duel trop court pour être noté" : "Trop peu d'opportunités pour une note";
            r.drawText(Lang.tr(reason), left + pad, y + UiTheme.scaled(8f), UiTheme.TEXT_PRIMARY, normal, vpWidth, vpHeight);
        }

        // Les cinq familles : libellé, barre, valeur.
        y -= row;
        float barLeft = left + pad + UiTheme.scaled(104f);
        float barRight = right - pad - UiTheme.scaled(28f);
        for (int i = 0; i < NativeCombat.FAMILY_COUNT; i++) {
            y -= row;
            double family = Math.max(0, Math.min(100, shown[NativeCombat.REPORT_FAMILIES + i]));
            r.drawText(Lang.tr(FAMILIES[i]), left + pad, y, UiTheme.TEXT_SECONDARY, small, vpWidth, vpHeight);
            float barY = y + UiTheme.scaled(3f);
            float barTop = barY + UiTheme.scaled(4f);
            r.drawRoundedRect(barLeft, barY, barRight, barTop, UiTheme.scaled(2f), UiTheme.CARD_BG, vpWidth, vpHeight);
            float filled = barLeft + (barRight - barLeft) * (float) (family / 100.0);
            if (filled > barLeft + 1f) {
                r.drawRoundedRect(barLeft, barY, filled, barTop, UiTheme.scaled(2f), UiTheme.ACCENT, vpWidth, vpHeight);
            }
            String value = String.valueOf(Math.round(family));
            r.drawText(value, right - pad - r.textWidth(value, small), y, UiTheme.TEXT_PRIMARY, small, vpWidth, vpHeight);
        }

        // Pied : une ligne par information, libellé à gauche, valeur à droite.
        // Sur une seule ligne, les deux décomptes et le ping se recouvraient.
        y -= row * 0.6f;
        y = footer(r, y, row, left + pad, right - pad, small, "Opportunités converties",
            (int) shown[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) shown[NativeCombat.REPORT_MY_OPENED], vpWidth, vpHeight);
        y = footer(r, y, row, left + pad, right - pad, small, "Opportunités subies",
            (int) shown[NativeCombat.REPORT_THEIR_CONVERTED] + " / " + (int) shown[NativeCombat.REPORT_THEIR_OPENED], vpWidth, vpHeight);
        // Le ping est affiché, jamais corrigé.
        if (shown[NativeCombat.REPORT_PING] >= 0) {
            footer(r, y, row, left + pad, right - pad, small, "Ping", Math.round(shown[NativeCombat.REPORT_PING]) + " ms", vpWidth, vpHeight);
        }
    }

    /** Une ligne du pied ; rend la hauteur de la suivante. */
    private static float footer(UiRenderer r, float y, float row, float left, float right, float scale,
                                String label, String value, int vpWidth, int vpHeight) {
        y -= row;
        r.drawText(Lang.tr(label), left, y, UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
        r.drawText(value, right - r.textWidth(value, scale), y, UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
        return y;
    }
}
