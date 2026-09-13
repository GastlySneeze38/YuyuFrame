package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.element.GlassPanelElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.element.IconElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.element.RoundedRectElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.element.TextElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.element.VignetteElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.pipeline.Blaze3DGuiGlass;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.pipeline.Blaze3DGuiText;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.pipeline.Blaze3DGuiIcon;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.pipeline.Blaze3DGuiVignette;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.GuiGraphicsExtractorAccessor261;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Émet du dessin DANS l'état de GUI de vanilla, à la position Z du hook
 * d'extraction où l'on se trouve — <b>étape 1 de la refonte du pipeline de
 * rendu</b> (2026-08-30).
 *
 * <h2>Le problème que ça résout</h2>
 *
 * Notre moteur est aujourd'hui une PASSE SÉPARÉE : tout ce qu'il dessine part
 * dans une file vidée à un point fixe de la frame ({@code blitToScreen} HEAD),
 * et nos icônes d'item vanilla partent dans une autre file vidée à un AUTRE
 * point fixe (après {@code Lighting.setupFor}). Aucun des deux n'a été choisi
 * par rapport au contenu vanilla — d'où un empilement subi :
 *
 * <pre>[toute la GUI vanilla, chat inclus] &lt; [nos items] &lt; [notre custom]</pre>
 *
 * C'est la cause unique des deux symptômes signalés sur
 * {@code ArmorDurabilityModule} : en style « Vanilla » ses icônes passent
 * au-dessus du chat, et en style « Personnalisé » son panneau passe au-dessus
 * des icônes.
 *
 * <h2>Le mécanisme</h2>
 *
 * Vanilla accumule sa GUI dans un {@code GuiRenderState} en ORDRE DU PEINTRE,
 * puis la soumet en une fois. Nos hooks {@code HUD_EXTRACT_*} reçoivent déjà
 * l'{@code GuiGraphicsExtractor} qui alimente cet état — et sa méthode
 * {@code fill(RenderPipeline, TextureSetup, …)} accepte un pipeline
 * ARBITRAIRE, donc un des nôtres. Émettre depuis un hook place donc le dessin
 * exactement là où vanilla aurait dessiné l'élément que ce hook remplace :
 * le z-order devient une propriété du POINT D'ÉMISSION, pas du point de flush.
 *
 * <h2>Portée de cette étape</h2>
 *
 * Volontairement limitée au <b>quad de couleur pleine</b>. Vanilla n'écrit que
 * {@code Position + Color} (et {@code UV0} pour les éléments texturés) —
 * vérifié en lisant le bytecode de {@code ColoredRectangleRenderState.buildVertices}
 * et {@code BlitRenderState.buildVertices}. Deux flottants libres ne suffisent
 * pas à porter les paramètres d'un SDF de coin arrondi (il faut la
 * demi-taille et le rayon en plus de la position locale), donc les coins
 * arrondis demandent un {@code VertexFormat} personnalisé — c'est l'étape 1b,
 * délibérément séparée pour ne pas mélanger deux inconnues dans un même test.
 *
 * <p>Cette classe ne dépend d'AUCUN état du moteur : elle ne fait que
 * transmettre à vanilla. Aucun risque de corruption d'état GPU, contrairement
 * au mélange GL brut / Blaze3D de la passe séparée.
 */
public final class VanillaGuiLayer {
    private VanillaGuiLayer() {}

    private static boolean unavailableLogged;

    /**
     * Le contexte reçu par un hook {@code HUD_EXTRACT_*} est-il exploitable ?
     *
     * <p>{@code false} hors bracket 26.1.2 : sur les autres versions, le hook
     * ne transporte pas un {@code GuiGraphicsExtractor} (mécanisme introduit
     * avec l'état de GUI différé), et l'appelant doit garder son chemin de
     * rendu habituel.
     */
    public static boolean isAvailable(Object hookContext) {
        return extractor(hookContext) != null;
    }

    private static GuiGraphicsExtractor extractor(Object hookContext) {
        try {
            return hookContext instanceof GuiGraphicsExtractor ? (GuiGraphicsExtractor) hookContext : null;
        } catch (Throwable t) {
            // NoClassDefFoundError attendu hors 26.1.2 (nom de classe réel) —
            // journalisé une seule fois, jamais avalé en silence.
            if (!unavailableLogged) {
                unavailableLogged = true;
                LauncherLog.err("[VanillaGuiLayer] contexte inexploitable sur ce bracket : " + t);
            }
            return null;
        }
    }

    /** Largeur de l'écran en pixels GUI, ou {@code -1} si indisponible. Les coordonnées de cette API sont en pixels GUI, PAS en pixels de framebuffer. */
    public static int guiWidth(Object hookContext) {
        GuiGraphicsExtractor g = extractor(hookContext);
        return g == null ? -1 : g.guiWidth();
    }

    /**
     * Rect ARRONDI avec notre pipeline SDF, à la position Z du hook — première
     * primitive du moteur réellement portée dans l'état de GUI vanilla.
     *
     * <p>Passe par {@code GuiRenderState.addGuiElement} (et non par
     * {@code GuiGraphicsExtractor.fill}) parce que seul un
     * {@code GuiElementRenderState} à nous peut écrire les attributs de sommet
     * que le SDF réclame — voir {@link RoundedRectElement}.
     *
     * <p>L'état de GUI est atteint par accessor Mixin : le champ
     * {@code guiRenderState} est privé.
     *
     * @param radius rayon en pixels GUI, borné à la demi-dimension par l'élément.
     * @return {@code false} si indisponible (hors 26.1.2, accessor non tissé,
     *         ou pipeline non compilable) — l'appelant garde son chemin habituel.
     */
    public static boolean roundedRect(Object hookContext, float x0, float y0, float x1, float y1,
                                      float radius, UiColor color) {
        return roundedRect(hookContext, x0, y0, x1, y1, radius, radius, radius, radius, color);
    }

    /**
     * Variante à RAYON PAR COIN — rayons en pixels GUI, repère Y vers le bas.
     *
     * <p>Sert aux panneaux HUD collés à un bord d'écran, qui doivent garder
     * leurs coins carrés de ce côté (voir {@code HudPanelRenderer.edgeAwareRadii}).
     */
    public static boolean roundedRect(Object hookContext, float x0, float y0, float x1, float y1,
                                      float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                                      UiColor color) {
        GuiRenderState state = renderState(hookContext);
        if (state == null) return false;
        try {
            state.addGuiElement(new RoundedRectElement(x0, y0, x1, y1,
                rTopLeft, rTopRight, rBottomLeft, rBottomRight, argb(color)));
            return true;
        } catch (Throwable t) {
            reportOnce("roundedRect: " + t);
            return false;
        }
    }

    /**
     * Icône RGBA — voir {@link IconElement}.
     *
     * <p>{@code cacheKey} identifie l'image dans l'atlas partagé : la même clé
     * ne provoque qu'UNE copie GPU, quel que soit le nombre de frames.
     * L'{@code alpha} module l'opacité sans toucher aux couleurs de l'image.
     *
     * <p>Pipeline compilé à la demande, comme la vignette et pour la même
     * raison : toutes les passes de HUD n'affichent pas d'icône, et un échec
     * ici ne doit pas emporter le reste du HUD.
     */
    public static boolean icon(Object hookContext, String cacheKey, java.awt.image.BufferedImage img,
                               float x0, float y0, float x1, float y1, float alpha) {
        if (img == null) return false;
        GuiRenderState state = renderState(hookContext);
        if (state == null) return false;
        try {
            if (!Blaze3DGuiIcon.ensureCompiled()) {
                reportOnce("icône : pipeline indisponible");
                return false;
            }
            Object[] entry = Blaze3DGuiIcon.atlasEntry(cacheKey, img);
            if (entry == null) return false; // atlas plein / Blaze3D absent, déjà journalisé
            int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
            state.addGuiElement(new IconElement(x0, y0, x1, y1, (float[]) entry[0],
                (a << 24) | 0x00FFFFFF, (TextureSetup) entry[1]));
            return true;
        } catch (Throwable t) {
            reportOnce("icon: " + t);
            return false;
        }
    }

    /**
     * Dégradé de bord plein écran (vignette) — voir {@link VignetteElement}.
     *
     * <p>{@code color.a} est l'opacité AU BORD ; elle retombe à zéro à
     * {@code vSize} pixels GUI du bord le plus proche.
     *
     * <p>Contrairement au rect et au texte, ce pipeline est compilé À LA
     * DEMANDE ici plutôt que dans {@code VanillaGuiTarget.begin()} : la
     * vignette ne sert qu'à un module, et seulement à vie basse — inutile
     * d'en faire payer la construction à chaque passe de HUD, et un échec de
     * sa part ne doit pas faire tomber le HUD entier.
     */
    public static boolean vignette(Object hookContext, float x0, float y0, float x1, float y1,
                                   float vSize, UiColor color) {
        GuiRenderState state = renderState(hookContext);
        if (state == null) return false;
        try {
            if (!Blaze3DGuiVignette.ensureCompiled()) {
                reportOnce("vignette : pipeline indisponible");
                return false;
            }
            state.addGuiElement(new VignetteElement(x0, y0, x1, y1, vSize, argb(color)));
            return true;
        } catch (Throwable t) {
            reportOnce("vignette: " + t);
            return false;
        }
    }

    /**
     * Texte SDF du moteur, émis à la position Z du hook.
     *
     * <p>{@code baselineY} est la LIGNE DE BASE en pixels GUI, axe Y vers le
     * BAS (convention vanilla) — voir {@link TextElement} pour l'inversion
     * d'axe par rapport au moteur.
     *
     * @return {@code false} si indisponible — l'appelant garde son chemin habituel.
     */
    public static boolean text(Object hookContext, UiFont font, String content,
                               float x, float baselineY, float scale, UiColor color) {
        if (content == null || content.isEmpty()) return true;
        GuiRenderState state = renderState(hookContext);
        if (state == null) return false;
        try {
            TextureSetup atlas = Blaze3DGuiText.textureSetup(font);
            if (atlas == null) {
                reportOnce("texte : atlas de police indisponible");
                return false;
            }
            state.addGuiElement(new TextElement(font, content, x, baselineY, scale, argb(color), atlas));
            return true;
        } catch (Throwable t) {
            reportOnce("text: " + t);
            return false;
        }
    }

    /**
     * Panneau de VERRE DÉPOLI (fond flouté + teinte), rayon par coin.
     *
     * <p>La chaîne de flou doit avoir été calculée AVANT dans la même frame —
     * voir {@code VanillaGuiTarget.beginGlassFrame}. Sans elle, il n'y a pas
     * de texture à échantillonner et l'appel se dégrade à {@code false},
     * l'appelant se rabattant sur un aplat.
     */
    public static boolean glassPanel(Object hookContext, float x0, float y0, float x1, float y1,
                                     float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                                     UiColor tint, UiColor background) {
        GuiRenderState state = renderState(hookContext);
        if (state == null) return false;
        try {
            if (!Blaze3DGuiGlass.ensureCompiled()) return false;
            TextureSetup blurred = Blaze3DGuiGlass.textureSetup();
            if (blurred == null) {
                reportOnce("verre : chaîne de flou absente pour cette frame");
                return false;
            }
            // rgb = teinte, a = opacité finale : le fragment mélange le flou
            // avec la teinte puis applique l'alpha (voir Blaze3DGuiGlass).
            int argb = (Math.round(background.a * 255f) << 24)
                     | (Math.round(tint.r * 255f) << 16)
                     | (Math.round(tint.g * 255f) << 8)
                     |  Math.round(tint.b * 255f);
            state.addGuiElement(new GlassPanelElement(x0, y0, x1, y1,
                rTopLeft, rTopRight, rBottomLeft, rBottomRight, argb, blurred));
            return true;
        } catch (Throwable t) {
            reportOnce("glassPanel: " + t);
            return false;
        }
    }

    /** État de GUI derrière le contexte d'un hook, ou {@code null} — factorisé, chaque échec journalisé une fois. */
    private static GuiRenderState renderState(Object hookContext) {
        GuiGraphicsExtractor g = extractor(hookContext);
        if (g == null) return null;
        try {
            if (!(g instanceof GuiGraphicsExtractorAccessor261)) {
                reportOnce("guiRenderState inaccessible — GuiGraphicsExtractorAccessor261 non tissé");
                return null;
            }
            GuiRenderState state = ((GuiGraphicsExtractorAccessor261) g).la$guiRenderState();
            if (state == null) reportOnce("guiRenderState null");
            return state;
        } catch (Throwable t) {
            reportOnce("renderState: " + t);
            return null;
        }
    }

    /**
     * Vide la file d'icônes d'item vanilla dans l'état de GUI.
     *
     * <p>L'ENREGISTREMENT du hook qui appelle ceci (et le rendu du HUD qui le
     * précède) a déménagé dans {@link VanillaGuiPass} : il ne dépend d'aucun
     * type du jeu et sert donc toutes les versions, alors que cette classe-ci
     * est compilée contre les noms 26.1.2. Voir sa javadoc pour le pourquoi du
     * point d'accroche (entre le HUD vanilla et le chat).
     */

    public static void flushItemIcons(Object hookContext) {
        GuiRenderState state = renderState(hookContext);
        if (state == null) return;
        try {
            UiRenderer.flushPendingModernItemIconsFromState(state);
        } catch (Throwable t) {
            reportOnce("flush icônes : " + t);
        }
    }

    private static String lastReport;

    /** Journalise une raison d'échec UNE fois par raison distincte — jamais de sortie muette. */
    private static void reportOnce(String message) {
        if (message.equals(lastReport)) return;
        lastReport = message;
        LauncherLog.err("[VanillaGuiLayer] " + message);
    }

    private static int argb(UiColor c) {
        return (Math.round(c.a * 255f) << 24)
             | (Math.round(c.r * 255f) << 16)
             | (Math.round(c.g * 255f) << 8)
             |  Math.round(c.b * 255f);
    }
}
