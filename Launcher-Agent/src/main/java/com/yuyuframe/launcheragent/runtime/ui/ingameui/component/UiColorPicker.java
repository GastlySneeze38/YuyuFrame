package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.UiWidget;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pastille de couleur + panneau déroulant. Historiquement calqué sur
 * cc.polyfrost.oneconfig.gui.elements.ColorSelector (3 bandes 1D
 * Teinte/Saturation/Luminosité) — REFONTE (demande explicite, référence
 * capture d'écran façon iOS/Sketch, "puisque on a la capacité du dégradé") :
 * Teinte+Saturation fusionnées en une roue circulaire unique (angle =
 * teinte, distance au centre = saturation), la Luminosité restant un
 * slider 1D séparé (la roue ne peut pas l'encoder — voir
 * {@link #applyBrightnessOverlay} pour comment elle est composée par-dessus).
 *
 * BUG TROUVÉ EN TEST (retour utilisateur, capture d'écran : "le cercle ne
 * s'affiche pas") : une première version dessinait la roue via un NOUVEAU
 * shader GLSL custom dans {@code UiRenderer} — invisible sur le bracket
 * testé (portage MC 26.1, pipeline Blaze3D era E actif), car ce shader
 * n'était routé QUE sur les pipelines Legacy/Modern (même limitation
 * documentée que drawShadow/drawGlow — Blaze3D est extrêmement fragile, voir
 * {@code UiTextBlaze3D}), donc silencieusement no-op sur ce bracket. Corrigé
 * en abandonnant l'idée d'un shader dédié : la roue est désormais une
 * TEXTURE générée une fois côté CPU ({@link #wheelImage}, teinte/saturation
 * "cuites" pixel par pixel à pleine luminosité, cache par taille) dessinée
 * via {@link UiRenderer#drawIcon} — déjà routé et éprouvé sur les 3
 * pipelines (icônes Modrinth). La luminosité est appliquée PAR-DESSUS via un
 * simple cercle noir semi-transparent ({@link #applyBrightnessOverlay}),
 * mathématiquement équivalent à multiplier par la luminosité (identité
 * HSBtoRGB(h,s,v) = v·lerp(blanc, HSBtoRGB(h,1,1), s) — un fondu vers le
 * noir d'alpha (1-v) produit exactement ce résultat par alpha blending).
 * Cette approche est PLUS robuste que la génération précédente : aucune
 * dépendance à un shader custom sur Blaze3D, seulement des primitives déjà
 * validées en jeu sur les 3 pipelines.
 *
 * L'ancienne bande "Saturation" 1D redondante est retirée (la roue l'encode
 * déjà via la distance au centre).
 *
 * Ajouté avec cette refonte : bouton "Copier" à côté du champ hex
 * (presse-papiers système, {@link Toolkit#getSystemClipboard()}).
 *
 * TOUJOURS PAS repris (limites réelles, pas juste par choix) :
 *  - Pipette — nécessiterait de lire les pixels du framebuffer, capacité
 *    absente de notre GraphicAPI.
 *  - Bouton "partager" (présent sur la référence) — pas de sens dans ce
 *    contexte de panneau de configuration purement local.
 *  - Favoris PERSISTANTS (survivant à un redémarrage) — nécessiterait une
 *    vraie sauvegarde disque, contrairement aux "récents" ci-dessous qui ne
 *    vivent qu'en mémoire pour la session.
 *
 * Damier de transparence derrière l'aperçu et la bande Alpha (seule façon de
 * VRAIMENT voir un alpha). Fermeture au clic EXTÉRIEUR (pastille ET panneau).
 * Rangée de couleurs RÉCENTES (jusqu'à 6, en mémoire pour la session,
 * partagées entre TOUTES les instances) — "récentes" seulement, PAS des
 * favoris persistants.
 */
public class UiColorPicker extends UiWidget {

    // Non static — dépendent de UiTheme.UI_SCALE au moment de la construction
    // (voir GlobalUiSettings, réglage "Taille de l'interface"), même motif que
    // UiSlider/UiToggle.
    private final float SWATCH_W = UiTheme.scaled(42f), SWATCH_H = UiTheme.scaled(26f);
    // BUG TROUVÉ (retour utilisateur : "la marge du bord de la modal...
    // trop basse ou inexistante") — PANEL_PAD (10 scaled) laissait tout
    // juste la place du rayon du marqueur de bande au repos (Luminosité/
    // Opacité) : à value extrême (0 ou 1, ex. Opacité à 100%) son bord
    // touchait déjà le bord du panneau, et le dépassait au survol (le
    // marqueur grossit de 20%). Marge simplement augmentée plutôt que de
    // changer la logique de valeur/glissement des bandes.
    private final float PANEL_W = UiTheme.scaled(210f), PANEL_PAD = UiTheme.scaled(18f);
    private final float PREVIEW_H = UiTheme.scaled(28f);
    private final float LABEL_H = UiTheme.scaled(15f), BAND_H = UiTheme.scaled(18f), ROW_GAP = UiTheme.scaled(8f);
    private final float HEX_H = UiTheme.scaled(26f);
    private final float RECENT_H = UiTheme.scaled(22f);
    private final float WHEEL_D = UiTheme.scaled(150f);
    private final float WHEEL_KNOB_R = UiTheme.scaled(7f);
    private final float COPY_BTN_W = UiTheme.scaled(46f);
    private final float COPY_GAP = UiTheme.scaled(6f);
    private final float labelTextScale = UiTheme.scaled(0.4f);
    private final float copyTextScale = UiTheme.scaled(0.38f);
    private static final long COPIED_FEEDBACK_MS = 900L;

    // Couleurs récentes — PARTAGÉES entre toutes les instances (comme un
    // historique de palette système), en mémoire UNIQUEMENT pour la session
    // (voir javadoc de classe, "toujours pas repris" pour le pourquoi de ne
    // pas persister sur disque). Dédupliquées (une couleur déjà présente
    // remonte en tête au lieu de se dupliquer).
    private static final List<UiColor> RECENT = new ArrayList<>();
    private static final int RECENT_MAX = 6;

    private static void pushRecent(UiColor c) {
        RECENT.removeIf(existing -> to255(existing.r) == to255(c.r) && to255(existing.g) == to255(c.g)
            && to255(existing.b) == to255(c.b) && to255(existing.a) == to255(c.a));
        RECENT.add(0, c);
        while (RECENT.size() > RECENT_MAX) RECENT.remove(RECENT.size() - 1);
    }

    private float hue, sat, bri; // HSB, tous 0..1
    private float a;
    private boolean expanded;
    private final Consumer<UiColor> onChange;
    private final UiTextField hexField;
    private long copiedAtMs = 0L;
    // Transition expand/collapse — voir audit runtime/ui/ : panneau qui
    // apparaissait/disparaissait d'un coup. Simplification délibérée : seul
    // le FOND du panneau est fondu (multiplyAlpha) — les bandes/le champ hex
    // à l'intérieur restent à pleine opacité dès que le fond dépasse un
    // seuil quasi-nul. `expanded` reste la seule source de vérité pour
    // l'INTERACTION (pollContinuous) — cette transition ne pilote QUE le rendu.
    private final UiTransition panelTransition = new UiTransition(0.14f, 0f, UiEasing.EASE_OUT_CUBIC);

    private final UiAnimatedFloat swatchHoverAnim = new UiAnimatedFloat(0f, 16f);
    private final UiAnimatedFloat wheelHoverAnim = new UiAnimatedFloat(0f, 18f);
    private final UiAnimatedFloat briHoverAnim = new UiAnimatedFloat(0f, 18f);
    private final UiAnimatedFloat alphaHoverAnim = new UiAnimatedFloat(0f, 18f);
    private final UiAnimatedFloat copyHoverAnim = new UiAnimatedFloat(0f, 18f);

    private boolean draggingWheel, draggingBri, draggingAlpha;

    public UiColorPicker(float x, float y, UiColor initial, Consumer<UiColor> onChange) {
        // Ne PAS référencer SWATCH_W/SWATCH_H ici : les initialiseurs de champs
        // d'instance ne s'exécutent qu'APRÈS cet appel super(), ils vaudraient
        // encore 0f à ce stade (piège déjà rencontré avec ArmorDurabilityModule
        // — this-avant-super()).
        super(x, y, UiTheme.scaled(42f), UiTheme.scaled(26f));
        this.onChange = onChange;
        float[] hsb = Color.RGBtoHSB(to255(initial.r), to255(initial.g), to255(initial.b), null);
        hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
        a = initial.a;
        float hexW = contentW() - COPY_BTN_W - COPY_GAP;
        hexField = new UiTextField(0, 0, hexW, HEX_H, "#RRGGBBAA", this::onHexTyped);
        hexField.setText(hexString());
    }

    private static int to255(float v) { return Math.round(Math.max(0f, Math.min(1f, v)) * 255f); }

    private UiColor rgbColor() {
        int rgb = Color.HSBtoRGB(hue, sat, bri);
        return new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, to255(a));
    }

    private void fire() {
        hexField.setText(hexString());
        if (onChange != null) onChange.accept(rgbColor());
    }

    private String hexString() {
        UiColor c = rgbColor();
        return String.format("#%02X%02X%02X%02X", to255(c.r), to255(c.g), to255(c.b), to255(c.a));
    }

    private void onHexTyped(String typed) {
        String hex = typed.startsWith("#") ? typed.substring(1) : typed;
        if (hex.length() != 6 && hex.length() != 8) return;
        try {
            int rr = Integer.parseInt(hex.substring(0, 2), 16);
            int gg = Integer.parseInt(hex.substring(2, 4), 16);
            int bb = Integer.parseInt(hex.substring(4, 6), 16);
            int aa = hex.length() == 8 ? Integer.parseInt(hex.substring(6, 8), 16) : to255(a);
            float[] hsb = Color.RGBtoHSB(rr, gg, bb, null);
            hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
            a = aa / 255f;
            if (onChange != null) onChange.accept(rgbColor());
        } catch (NumberFormatException ignored) {
            // Frappe en cours (hex incomplet/invalide) — pas d'action, on attend la suite.
        }
    }

    /** Presse-papiers système — pas de "partage" (voir javadoc de classe, hors scope pour un panneau de config local). */
    private void copyHexToClipboard() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hexField.text()), null);
            copiedAtMs = System.currentTimeMillis();
        } catch (Throwable t) {
            LauncherLog.err("[UiColorPicker] copyHexToClipboard: " + t);
        }
    }

    public UiColor value() { return rgbColor(); }

    /** Ouvre/ferme le panneau — point d'entrée UNIQUE (onClick ET clic extérieur, voir pollContinuous) pour que la couleur finale rejoigne TOUJOURS les récents à la fermeture, peu importe comment elle a été fermée. */
    private void setExpanded(boolean v) {
        if (expanded && !v) pushRecent(rgbColor());
        expanded = v;
    }

    // ── Géométrie du panneau — dérivée depuis le bas de la pastille (y), empile VERS LE BAS (ou VERS LE HAUT si {@link #flipUp}) ──

    private float contentW() { return PANEL_W - 2 * PANEL_PAD; }

    /**
     * BUG TROUVÉ (retour utilisateur : "la modal apparaît en dessous du
     * bouton même si il n'y a pas l'espace") — le panneau s'ouvrait
     * TOUJOURS vers le bas depuis la pastille, sans jamais vérifier s'il y
     * avait la place : une pastille proche du bas de l'écran/de la liste
     * poussait le panneau hors de l'écran (ou par-dessus la pastille
     * elle-même). Décidé une fois par frame dans {@link #drawOverlay}
     * (seul point d'entrée qui reçoit {@code vpHeight}) puis mis en cache
     * ici pour que {@link #pollContinuous} (qui n'a PAS accès à vpHeight,
     * voir signature de {@code UiWidget#pollContinuous}) reste cohérent
     * avec ce qui est dessiné.
     */
    private boolean flipUp;

    /**
     * Hauteur TOTALE du panneau — somme des mêmes écarts que la chaîne
     * {@link #previewY}→{@link #recentY}→{@link #panelBottom}, mais
     * calculée à plat (INDÉPENDANTE de {@link #panelTop}, donc valide que
     * le panneau soit orienté vers le bas ou vers le haut) : sert à décider
     * {@link #flipUp} et à ancrer le panneau retourné.
     */
    private float contentHeight() {
        float total = PREVIEW_H
            + 3f * (ROW_GAP + LABEL_H)   // wheelLabelY + briLabelY + alphaLabelY
            + WHEEL_D
            + 2f * BAND_H                // briBandY + alphaBandY
            + ROW_GAP + HEX_H;
        if (!RECENT.isEmpty()) total += ROW_GAP + RECENT_H;
        total += PANEL_PAD; // symétrique du PANEL_PAD déjà soustrait par panelBottom()
        return total;
    }

    private float panelTop() { return flipUp ? (y + h + PANEL_PAD + contentHeight()) : (y - PANEL_PAD); }
    private float previewY() { return panelTop() - PREVIEW_H; }
    private float wheelLabelY() { return previewY() - ROW_GAP - LABEL_H; }
    private float wheelY() { return wheelLabelY() - WHEEL_D; }
    private float wheelCenterX() { return x + PANEL_PAD + contentW() / 2f; }
    private float wheelCenterY() { return wheelY() + WHEEL_D / 2f; }
    private float briLabelY() { return wheelY() - ROW_GAP - LABEL_H; }
    private float briBandY() { return briLabelY() - BAND_H; }
    private float alphaLabelY() { return briBandY() - ROW_GAP - LABEL_H; }
    private float alphaBandY() { return alphaLabelY() - BAND_H; }
    private float hexY() { return alphaBandY() - ROW_GAP - HEX_H; }

    /**
     * BUG TROUVÉ (retour utilisateur : "il y a une marge de fou en bas") —
     * cette ligne réservait TOUJOURS {@code ROW_GAP+RECENT_H} même quand
     * {@link #RECENT} est vide (rien dessiné par {@link #drawRecentRow}
     * dans ce cas, voir son early-return) : un grand blanc apparaissait
     * entre la ligne hex et le bas du panneau tant qu'aucune couleur
     * n'avait encore été fermée dans la session. Ne réserve plus cet espace
     * quand il n'y a rien à y afficher.
     */
    private float recentY() { return RECENT.isEmpty() ? hexY() : hexY() - ROW_GAP - RECENT_H; }

    /**
     * PUBLIC (pas juste privé comme le reste de la géométrie) — voir BUG
     * TROUVÉ (retour utilisateur, capture d'écran : le toggle de la ligne
     * SUIVANTE apparaissait à travers le bas du panneau déroulé). La marge
     * réservée sous cette ligne dans {@code ConfigScreenBuilder.colorRow}
     * était un nombre codé en dur, recalculé (mal, deux fois de suite) à la
     * main à chaque changement de la géométrie interne du panneau — exposer
     * directement cette VALEUR RÉELLE élimine le problème à la racine : le
     * calcul ne peut plus jamais désynchroniser de ce qui est vraiment
     * dessiné, quel que soit un futur changement ici.
     */
    public float panelBottom() { return recentY() - PANEL_PAD; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float swatchRadius = UiTheme.scaled(4f), swatchInset = UiTheme.scaled(3f);
        swatchHoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        UiColor swatchBorder = UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.ACCENT, swatchHoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, swatchRadius, swatchBorder, vpWidth, vpHeight);
        drawCheckerboard(renderer, x + swatchInset, y + swatchInset, x + w - swatchInset, y + h - swatchInset, vpWidth, vpHeight);
        renderer.drawRoundedRect(x + swatchInset, y + swatchInset, x + w - swatchInset, y + h - swatchInset, swatchRadius - 1f, rgbColor(), vpWidth, vpHeight);
    }

    /**
     * Le panneau déroulé ENTIER vit ici, PAS dans {@link #draw} (voir
     * UiWidget#drawOverlay pour le pourquoi — BUG TROUVÉ, retour
     * utilisateur : "la modal entière... se fait chevaucher par tout") :
     * dessiné dans un second passage APRÈS tout le reste de la liste
     * (UiScrollContainer), ce panneau flotte désormais TOUJOURS au-dessus,
     * peu importe ce qui se trouve plus bas dans l'écran. Seule la pastille
     * (bouton fermé) reste dans draw() — sa position dans la liste continue
     * de compter pour son propre clipFade/sa propre visibilité au bord du
     * scroll, ce que ce panneau n'a lui, jamais besoin d'avoir puisqu'il
     * n'existe que par-dessus tout, jamais coupé par un bord de viewport.
     */
    @Override
    public void drawOverlay(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        // Décidé AVANT le early-return ci-dessous (et avant tout appel à
        // panelTop()/panelBottom()) — voir javadoc de #flipUp : doit rester
        // à jour même pendant le fondu d'ouverture, et {@link #pollContinuous}
        // (hit-test du clic extérieur, de la roue, etc.) lit ce cache CETTE
        // même frame.
        flipUp = (y - PANEL_PAD - contentHeight()) < UiTheme.scaled(10f);

        float swatchRadius = UiTheme.scaled(4f);
        panelTransition.setTarget(expanded);
        float panelAlpha = panelTransition.eased();
        if (panelAlpha <= 0.02f) return;

        renderer.drawShadow(x, panelBottom(), x + PANEL_W, panelTop(), UiTheme.RADIUS_MD, UiTheme.scaled(10f), 0f,
            new UiColor(0, 0, 0, (int) (110 * panelAlpha)), vpWidth, vpHeight);
        renderer.drawRoundedRect(x, panelBottom(), x + PANEL_W, panelTop(), UiTheme.RADIUS_MD, UiTheme.PANEL_BG_ALT.multiplyAlpha(panelAlpha), vpWidth, vpHeight);

        float bx = x + PANEL_PAD, bw = contentW();

        // BUG TROUVÉ (retour utilisateur : "pas assez [de marge] en haut") —
        // ce rect s'arrêtait à panelTop()-PAD/2, une marge de SEULEMENT la
        // moitié de celle utilisée en bas (panelBottom()=recentY()-PAD) et
        // sur les côtés (bx=x+PAD) : marge désormais identique aux 3 autres.
        drawCheckerboard(renderer, bx, previewY(), bx + bw, panelTop() - PANEL_PAD, vpWidth, vpHeight);
        renderer.drawRoundedRect(bx, previewY(), bx + bw, panelTop() - PANEL_PAD, swatchRadius, rgbColor(), vpWidth, vpHeight);

        drawBandLabel(renderer, bx, wheelLabelY(), "Teinte & Saturation", vpWidth, vpHeight);
        wheelHoverAnim.setTarget(draggingWheel || overWheel(mouseX, mouseY) ? 1f : 0f);
        float wr = WHEEL_D / 2f;
        String wheelCacheKey = "huewheel-" + Math.round(WHEEL_D);
        renderer.drawIcon(wheelCacheKey, wheelImage(), wheelCenterX() - wr, wheelCenterY() - wr, WHEEL_D, WHEEL_D, vpWidth, vpHeight);
        applyBrightnessOverlay(renderer, vpWidth, vpHeight);
        drawWheelKnob(renderer, wheelHoverAnim.get(), vpWidth, vpHeight);

        drawBandLabel(renderer, bx, briLabelY(), "Luminosité", vpWidth, vpHeight);
        briHoverAnim.setTarget(draggingBri || over(mouseX, mouseY, bx, briBandY(), bw) ? 1f : 0f);
        drawBands(renderer, bx, briBandY(), bw, bri, t -> hsbColor(hue, sat, t), briHoverAnim.get(), vpWidth, vpHeight);

        drawBandLabel(renderer, bx, alphaLabelY(), "Opacité", vpWidth, vpHeight);
        UiColor solid = rgbColor();
        alphaHoverAnim.setTarget(draggingAlpha || over(mouseX, mouseY, bx, alphaBandY(), bw) ? 1f : 0f);
        drawCheckerboard(renderer, bx, alphaBandY(), bx + bw, alphaBandY() + BAND_H, vpWidth, vpHeight);
        drawBands(renderer, bx, alphaBandY(), bw, a, t -> new UiColor(solid.r, solid.g, solid.b, t), alphaHoverAnim.get(), vpWidth, vpHeight);

        hexField.x = bx;
        hexField.y = hexY();
        hexField.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
        drawCopyButton(renderer, mouseX, mouseY, bx, bw, vpWidth, vpHeight);

        drawRecentRow(renderer, bx, recentY(), bw, mouseX, mouseY, vpWidth, vpHeight);
    }

    /** Damier gris clair/foncé — seule façon de voir VRAIMENT une transparence (voir javadoc de classe : la bande/l'aperçu se contentaient avant de dégrader vers le fond du panneau, jamais représentatif d'un alpha réel). Coins toujours droits (radius=0, comme les segments de bande) : pas de vrai clip pour arrondir seulement le damier aux coins d'un parent arrondi, voir limites connues de UiScrollContainer. */
    private void drawCheckerboard(UiRenderer renderer, float x1, float y1, float x2, float y2, int vpW, int vpH) {
        float cell = UiTheme.scaled(6f);
        UiColor base = new UiColor(70, 70, 76, 255);
        UiColor light = new UiColor(105, 105, 112, 255);
        renderer.drawRoundedRect(x1, y1, x2, y2, 0, base, vpW, vpH);
        int cols = Math.max(1, (int) Math.ceil((x2 - x1) / cell));
        int rows = Math.max(1, (int) Math.ceil((y2 - y1) / cell));
        for (int ry = 0; ry < rows; ry++) {
            for (int cx = 0; cx < cols; cx++) {
                if (((ry + cx) & 1) == 0) continue;
                float sx1 = x1 + cx * cell, sy1 = y1 + ry * cell;
                float sx2 = Math.min(sx1 + cell, x2), sy2 = Math.min(sy1 + cell, y2);
                renderer.drawRoundedRect(sx1, sy1, sx2, sy2, 0, light, vpW, vpH);
            }
        }
    }

    private interface BandColorFn { UiColor at(float t); }

    private void drawBandLabel(UiRenderer renderer, float x, float y, String label, int vpW, int vpH) {
        renderer.drawText(label, x, y + UiTheme.scaled(2f), UiTheme.TEXT_SECONDARY, labelTextScale, vpW, vpH);
    }

    /**
     * BUG TROUVÉ (retour utilisateur : "le dégradé... ça se voit, tu ne
     * peux pas utiliser la nouvelle capacité du moteur ?") — l'ancienne
     * version approximait ce dégradé par {@code BANDS} rectangles pleins
     * juxtaposés (marches visibles). Les DEUX appelants de cette méthode
     * (Luminosité, Opacité) sont en réalité des dégradés STRICTEMENT
     * LINÉAIRES entre {@code colorFn.at(0)} et {@code colorFn.at(1)} —
     * HSBtoRGB(h,s,v)=v·HSBtoRGB(h,s,1) est linéaire en v, l'alpha pur
     * l'est trivialement — donc {@link UiRenderer#drawGradientRect2D}
     * (2 couleurs suffisent : gauche=coin bas/haut-gauche, droite=coin
     * bas/haut-droite) reproduit ce dégradé EXACTEMENT, pas juste une
     * approximation plus fine. Routé nativement sur les 3 pipelines (voir
     * {@code UiTextBlaze3D#queueGradientRect2D} — contrairement à la
     * première tentative de roue Teinte/Saturation via shader custom,
     * celui-ci ne nécessite aucun shader, seulement des couleurs de
     * sommet, donc fonctionne aussi sur Blaze3D era E).
     */
    private void drawBands(UiRenderer renderer, float x, float y, float w, float value, BandColorFn colorFn, float hoverT, int vpW, int vpH) {
        UiColor left = colorFn.at(0f), right = colorFn.at(1f);
        renderer.drawGradientRect2D(x, y, x + w, y + BAND_H, 0f, left, right, left, right, vpW, vpH);
        float markerX = x + value * w;
        float markerCy = y + BAND_H / 2f;
        float knobR = (BAND_H / 2f + UiTheme.scaled(1f)) * (1f + hoverT * 0.2f);
        renderer.drawShadow(markerX - knobR, markerCy - knobR, markerX + knobR, markerCy + knobR, knobR,
            UiTheme.scaled(3f), 0f, new UiColor(0, 0, 0, 110), vpW, vpH);
        renderer.drawRoundedRect(markerX - knobR, markerCy - knobR, markerX + knobR, markerCy + knobR, knobR, UiTheme.TEXT_PRIMARY, vpW, vpH);
    }

    /** Marqueur de la roue Teinte/Saturation — même style (ombre légère + grossissement au survol/glissement) que {@link #drawBands}, mais positionné en polaire ({@link #wheelKnobX}/{@link #wheelKnobY}) plutôt que le long d'un axe. Bordure sombre ajoutée : un disque blanc uni serait illisible sur les teintes claires (jaune, blanc du centre à faible saturation). */
    private void drawWheelKnob(UiRenderer renderer, float hoverT, int vpW, int vpH) {
        float kx = wheelKnobX(), ky = wheelKnobY();
        float knobR = WHEEL_KNOB_R * (1f + hoverT * 0.25f);
        renderer.drawShadow(kx - knobR, ky - knobR, kx + knobR, ky + knobR, knobR,
            UiTheme.scaled(3f), 0f, new UiColor(0, 0, 0, 110), vpW, vpH);
        renderer.drawRoundedRect(kx - knobR, ky - knobR, kx + knobR, ky + knobR, knobR, new UiColor(255, 255, 255, 255), vpW, vpH);
        float innerR = knobR - UiTheme.scaled(2f);
        renderer.drawRoundedRect(kx - innerR, ky - innerR, kx + innerR, ky + innerR, innerR, rgbColor(), vpW, vpH);
    }

    // Cache PARTAGÉ entre toutes les instances (même motif que RECENT) — clé =
    // taille en pixels (dépend de UiTheme.UI_SCALE au moment de la construction,
    // voir WHEEL_D) ; l'image ne change JAMAIS après génération (seule la
    // luminosité varie, appliquée à part par-dessus, voir applyBrightnessOverlay),
    // donc aucune raison de la régénérer tant que la taille reste la même.
    private static final Map<Integer, BufferedImage> WHEEL_IMAGE_CACHE = new HashMap<>();

    private BufferedImage wheelImage() {
        int size = Math.max(2, Math.round(WHEEL_D));
        return WHEEL_IMAGE_CACHE.computeIfAbsent(size, UiColorPicker::generateWheelImage);
    }

    /**
     * Roue Teinte/Saturation à pleine luminosité, "cuite" pixel par pixel —
     * voir javadoc de classe pour pourquoi (échec du shader custom sur
     * Blaze3D). Même formule EXACTE que l'ancien fragment shader
     * (angle→teinte, distance→saturation, bord anti-aliasé par smoothstep),
     * juste évaluée côté CPU une seule fois au lieu de par pixel/par frame
     * sur GPU. Repère image (py croissant vers le BAS, standard
     * BufferedImage) converti en repère écran Y-UP ({@code dyVisual}) pour
     * rester cohérent avec {@link #wheelKnobX}/{@link #updateHueSatFromMouse}.
     */
    private static BufferedImage generateWheelImage(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        float radius = size / 2f;
        float cx = size / 2f, cy = size / 2f;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                float dx = px + 0.5f - cx;
                float dyVisual = cy - (py + 0.5f);
                float dist = (float) Math.sqrt(dx * dx + dyVisual * dyVisual);
                float edgeAlpha = 1f - smoothstep(radius - 1f, radius, dist);
                if (edgeAlpha <= 0f) continue; // reste transparent (0 par défaut)
                float sat = Math.max(0f, Math.min(1f, dist / radius));
                double angle = Math.atan2(dyVisual, dx);
                double h = 0.25 - angle / (2.0 * Math.PI);
                h = h - Math.floor(h);
                int rgb = Color.HSBtoRGB((float) h, sat, 1f);
                int a = Math.round(edgeAlpha * 255f);
                img.setRGB(px, py, (a << 24) | (rgb & 0xFFFFFF));
            }
        }
        return img;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /**
     * Applique la luminosité PAR-DESSUS la roue (cuite à v=1) via un simple
     * disque noir semi-transparent — voir javadoc de classe pour la preuve
     * mathématique (identité HSBtoRGB(h,s,v)=v·lerp(blanc,HSBtoRGB(h,1,1),s)).
     * No-op à pleine luminosité (bri≈1 — rien à assombrir).
     */
    private void applyBrightnessOverlay(UiRenderer renderer, int vpW, int vpH) {
        if (bri >= 0.999f) return;
        float wr = WHEEL_D / 2f, cx = wheelCenterX(), cy = wheelCenterY();
        int alpha = Math.round((1f - bri) * 255f);
        renderer.drawRoundedRect(cx - wr, cy - wr, cx + wr, cy + wr, wr, new UiColor(0, 0, 0, alpha), vpW, vpH);
    }

    /** Bouton "Copier"/"Copié" (retour temporaire {@link #COPIED_FEEDBACK_MS}) à droite du champ hex — voir javadoc de classe (presse-papiers, pas de "partage"). */
    private void drawCopyButton(UiRenderer renderer, double mouseX, double mouseY, float bx, float bw, int vpW, int vpH) {
        float btnX = bx + bw - COPY_BTN_W;
        boolean hovered = overCopyButton(mouseX, mouseY);
        copyHoverAnim.setTarget(hovered ? 1f : 0f);
        UiColor bg = UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, copyHoverAnim.get());
        renderer.drawRoundedRect(btnX, hexY(), btnX + COPY_BTN_W, hexY() + HEX_H, UiTheme.RADIUS_SM, bg, vpW, vpH);
        boolean justCopied = System.currentTimeMillis() - copiedAtMs < COPIED_FEEDBACK_MS;
        String label = justCopied ? "Copié" : "Copier";
        float tw = renderer.textWidth(label, copyTextScale);
        renderer.drawText(label, btnX + (COPY_BTN_W - tw) / 2f, hexY() + HEX_H / 2f - UiTheme.scaled(4f),
            justCopied ? UiTheme.ACCENT : UiTheme.TEXT_SECONDARY, copyTextScale, vpW, vpH);
    }

    /** Rangée de couleurs récentes (voir javadoc de classe) — vide (rien dessiné) tant qu'aucune couleur n'a encore été fermée au moins une fois dans la session. */
    private void drawRecentRow(UiRenderer renderer, float bx, float ry, float bw, double mouseX, double mouseY, int vpW, int vpH) {
        if (RECENT.isEmpty()) return;
        float gap = UiTheme.scaled(6f);
        float slot = Math.min(RECENT_H, (bw - gap * (RECENT_MAX - 1)) / RECENT_MAX);
        for (int i = 0; i < RECENT.size(); i++) {
            float sx = bx + i * (slot + gap);
            UiColor c = RECENT.get(i);
            boolean hovered = mouseX >= sx && mouseX <= sx + slot && mouseY >= ry && mouseY <= ry + RECENT_H;
            drawCheckerboard(renderer, sx, ry, sx + slot, ry + RECENT_H, vpW, vpH);
            renderer.drawRoundedRect(sx, ry, sx + slot, ry + RECENT_H, UiTheme.RADIUS_SM, c, vpW, vpH);
            if (hovered) {
                renderer.drawRoundedRectBorder(sx, ry, sx + slot, ry + RECENT_H, UiTheme.RADIUS_SM, UiTheme.scaled(1.5f), UiTheme.TEXT_PRIMARY, vpW, vpH);
            }
        }
    }

    private static UiColor hsbColor(float h, float s, float b) {
        int rgb = Color.HSBtoRGB(h, s, b);
        return new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
    }

    /**
     * Position du marqueur de la roue — inverse EXACT du mapping angle→teinte
     * du shader {@code HUE_WHEEL_FRAGMENT_SRC} ({@code hue = fract(0.25 -
     * angle/2π)} ⇒ {@code angle = 2π·(0.25-hue)}), pour que le marqueur
     * pointe TOUJOURS vers le pixel qui a réellement cette teinte à l'écran.
     */
    private float wheelKnobX() {
        double angle = 2.0 * Math.PI * (0.25 - hue);
        return wheelCenterX() + (float) (sat * (WHEEL_D / 2f) * Math.cos(angle));
    }

    private float wheelKnobY() {
        double angle = 2.0 * Math.PI * (0.25 - hue);
        return wheelCenterY() + (float) (sat * (WHEEL_D / 2f) * Math.sin(angle));
    }

    /** Calcule teinte/saturation depuis une position souris — mapping DIRECT (pas juste l'inverse de {@link #wheelKnobX}) recalculé indépendamment à partir de l'angle/distance réels, exactement comme le shader (voir {@link #wheelKnobX} pour la formule miroir). */
    private void updateHueSatFromMouse(double mouseX, double mouseY) {
        double dx = mouseX - wheelCenterX();
        double dy = mouseY - wheelCenterY();
        double radius = WHEEL_D / 2f;
        double dist = Math.sqrt(dx * dx + dy * dy);
        sat = (float) Math.max(0.0, Math.min(1.0, dist / radius));
        double angle = Math.atan2(dy, dx);
        double h = 0.25 - angle / (2.0 * Math.PI);
        h = h - Math.floor(h); // fract() — garantit un résultat dans [0,1)
        hue = (float) h;
    }

    private boolean overWheel(double mouseX, double mouseY) {
        float r = WHEEL_D / 2f;
        float cx = wheelCenterX(), cy = wheelCenterY();
        return mouseX >= cx - r && mouseX <= cx + r && mouseY >= cy - r && mouseY <= cy + r;
    }

    private boolean overCopyButton(double mouseX, double mouseY) {
        float bx = x + PANEL_PAD, bw = contentW();
        float btnX = bx + bw - COPY_BTN_W;
        return mouseX >= btnX && mouseX <= btnX + COPY_BTN_W && mouseY >= hexY() && mouseY <= hexY() + HEX_H;
    }

    @Override
    public void onClick() {
        setExpanded(!expanded);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!expanded) return;
        hexField.pollContinuous(input);

        // BUG TROUVÉ (retour utilisateur : "enlever le focus [au clic
        // extérieur de la barre]") — UiTextField ne se défocalise JAMAIS de
        // lui-même (aucune logique de "blur" dans sa propre classe, voir sa
        // javadoc) : une fois focus (clic dessus), le curseur continuait de
        // clignoter et le champ continuait de capter le clavier même après
        // un clic ailleurs dans le panneau (roue, bandes, bouton Copier...).
        if (input.leftClicked && !hexField.contains(input.mouseX, input.mouseY)) {
            hexField.setFocused(false);
        }

        float bx = x + PANEL_PAD, bw = contentW();

        // Fermeture au clic EXTÉRIEUR (voir javadoc de classe : comportement
        // de modal standard, absent jusqu'ici) — testé AVANT le reste : un
        // clic hors pastille ET hors panneau ferme, sans déclencher un
        // début de glissement au passage.
        if (input.leftClicked) {
            boolean overSwatch = input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= y && input.mouseY <= y + h;
            boolean overPanel = input.mouseX >= x && input.mouseX <= x + PANEL_W
                && input.mouseY >= panelBottom() && input.mouseY <= panelTop();
            if (!overSwatch && !overPanel) {
                setExpanded(false);
                draggingWheel = draggingBri = draggingAlpha = false;
                return;
            }
        }

        if (!input.leftDown) {
            draggingWheel = draggingBri = draggingAlpha = false;
            return;
        }

        if (input.leftClicked) {
            if (overWheel(input.mouseX, input.mouseY)) draggingWheel = true;
            else if (over(input.mouseX, input.mouseY, bx, briBandY(), bw)) draggingBri = true;
            else if (over(input.mouseX, input.mouseY, bx, alphaBandY(), bw)) draggingAlpha = true;
            else if (hexField.contains(input.mouseX, input.mouseY)) hexField.onClick();
            else if (overCopyButton(input.mouseX, input.mouseY)) copyHexToClipboard();
            else {
                // Clic sur une pastille "récente" — voir drawRecentRow pour
                // la géométrie EXACTE des slots (dupliquée ici pour le hit-test,
                // même motif que UiScrollContainer.thumbY/thumbHeight).
                float gap = UiTheme.scaled(6f);
                float slot = Math.min(RECENT_H, (bw - gap * (RECENT_MAX - 1)) / RECENT_MAX);
                float ry = recentY();
                for (int i = 0; i < RECENT.size(); i++) {
                    float sx = bx + i * (slot + gap);
                    if (input.mouseX >= sx && input.mouseX <= sx + slot && input.mouseY >= ry && input.mouseY <= ry + RECENT_H) {
                        UiColor picked = RECENT.get(i);
                        float[] hsb = Color.RGBtoHSB(to255(picked.r), to255(picked.g), to255(picked.b), null);
                        hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
                        a = picked.a;
                        fire();
                        break;
                    }
                }
            }
        }

        if (draggingWheel) { updateHueSatFromMouse(input.mouseX, input.mouseY); fire(); }
        else if (draggingBri) { bri = clampT(input.mouseX, bx, bw); fire(); }
        else if (draggingAlpha) { a = clampT(input.mouseX, bx, bw); fire(); }
    }

    private boolean over(double mouseX, double mouseY, float bx, float by, float bw) {
        return mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + BAND_H;
    }

    private float clampT(double mouseX, float bx, float bw) {
        return Math.max(0f, Math.min(1f, (float) (mouseX - bx) / bw));
    }
}
