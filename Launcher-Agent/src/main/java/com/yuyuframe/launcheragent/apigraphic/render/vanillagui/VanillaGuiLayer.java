package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiRoundedRect;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiText;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.GuiGraphicsExtractorAccessor261;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
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

    /** Hauteur de l'écran en pixels GUI, ou {@code -1}. */
    public static int guiHeight(Object hookContext) {
        GuiGraphicsExtractor g = extractor(hookContext);
        return g == null ? -1 : g.guiHeight();
    }

    /**
     * Quad de couleur pleine avec le pipeline VANILLA, à la position Z du hook.
     *
     * <p>Coordonnées en pixels GUI, origine en HAUT à gauche, {@code y} vers le
     * BAS — convention vanilla, l'inverse de notre moteur (origine en bas,
     * {@code y} vers le haut). La conversion reste à la charge de l'appelant
     * tant qu'on n'a pas de couche d'adaptation : la faire ici en devinant
     * l'échelle d'interface introduirait un arrondi de plus.
     *
     * @return {@code false} si indisponible — l'appelant garde alors son chemin habituel.
     */
    public static boolean fill(Object hookContext, int x0, int y0, int x1, int y1, UiColor color) {
        GuiGraphicsExtractor g = extractor(hookContext);
        if (g == null) return false;
        try {
            g.fill(x0, y0, x1, y1, argb(color));
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[VanillaGuiLayer] fill: " + t);
            return false;
        }
    }

    /**
     * Quad rendu avec UN DE NOS PIPELINES, à la position Z du hook — c'est
     * l'appel qui valide (ou invalide) toute la refonte.
     *
     * <p>Le pipeline doit ne déclarer QUE des uniformes que vanilla lie
     * lui-même ({@code DynamicTransforms}, {@code Projection}) : la soumission
     * est faite par {@code GuiRenderer}, qui ne connaît pas nos blocs
     * personnalisés (un {@code BatchParams} ne serait jamais lié). Il doit
     * aussi se contenter des attributs que vanilla écrit — {@code Position} et
     * {@code Color}. Voir {@code VanillaGuiProbePipeline}.
     *
     * @param pipeline objet {@code RenderPipeline} (typé {@code Object} : il est
     *                 construit par réflexion, voir {@code ShaderPipelineFactory}).
     * @return {@code false} si indisponible ou si le pipeline n'est pas fourni.
     */
    public static boolean fillWithPipeline(Object hookContext, Object pipeline, int x0, int y0, int x1, int y1) {
        GuiGraphicsExtractor g = extractor(hookContext);
        if (g == null || pipeline == null) return false;
        try {
            g.fill((com.mojang.blaze3d.pipeline.RenderPipeline) pipeline, TextureSetup.noTexture(), x0, y0, x1, y1);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[VanillaGuiLayer] fillWithPipeline: " + t);
            return false;
        }
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
        GuiRenderState state = renderState(hookContext);
        if (state == null) return false;
        try {
            if (!Blaze3DGuiRoundedRect.ensureCompiled()) return false;
            state.addGuiElement(new RoundedRectElement(x0, y0, x1, y1, radius, argb(color)));
            return true;
        } catch (Throwable t) {
            reportOnce("roundedRect: " + t);
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
            if (!Blaze3DGuiText.ensureCompiled()) return false;
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
     * Fait vider la file d'icônes d'item vanilla JUSTE AVANT que le chat ne
     * soit ajouté à l'état de GUI — correctif du z-order signalé sur le style
     * « Vanilla » d'{@code ArmorDurabilityModule} (2026-08-30).
     *
     * <h3>Le bug</h3>
     *
     * {@code GuiFlushMixin261} vide cette file depuis {@code GameRenderer.render},
     * après {@code Lighting.setupFor} — un point situé APRÈS que toute la GUI
     * (chat compris) a été extraite. L'état de GUI étant en ordre du peintre,
     * nos icônes, ajoutées en dernier, passaient donc PAR-DESSUS le chat.
     *
     * <h3>Le correctif</h3>
     *
     * S'accrocher à {@code HUD_EXTRACT_CHAT}, dispatché en HEAD de
     * {@code Gui.extractChat} : à cet instant tout le HUD vanilla est déjà
     * dans l'état, le chat pas encore. Nos icônes atterrissent exactement
     * entre les deux.
     *
     * <p>Le handler renvoie TOUJOURS {@code false} : il ne doit jamais annuler
     * le rendu du chat, il ne fait que s'insérer dans la frame.
     *
     * <p>{@code GuiFlushMixin261} est CONSERVÉ comme filet de sécurité : si
     * {@code extractChat} n'était pas appelé dans un état donné, les icônes
     * seraient tout de même dessinées (au mauvais z, mais dessinées). Une file
     * déjà vidée rend ce second flush inoffensif — c'est un no-op.
     */
    /**
     * Rendu du HUD à exécuter depuis la passe GUI, injecté par l'appelant.
     *
     * <p>Découplage volontaire : {@code apigraphic} ne doit pas dépendre de
     * {@code runtime.ui.hud}, qui porte la POLITIQUE d'affichage (quels
     * éléments, visibles quand). C'est {@code ModuleRegistry} qui fournit
     * l'implémentation au moment de l'installation.
     */
    public interface HudPass {
        void run(Object hookContext);
    }

    private static HudPass hudRenderer = ctx -> {};

    /** Voir {@link HudPass}. À appeler AVANT {@link #installItemIconFlush()}. */
    public static void setHudPass(HudPass pass) {
        if (pass != null) hudRenderer = pass;
    }

    public static void installItemIconFlush() {
        if (itemFlushInstalled) return;
        itemFlushInstalled = true;
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_CHAT, ctx -> {
            // ORDRE VOLONTAIRE, c'est lui qui règle le second bug d'armure :
            // le HUD d'abord (ses panneaux atterrissent dans l'état, et ses
            // icônes d'item vont dans la file), le flush ENSUITE — les icônes
            // se retrouvent donc au-dessus des panneaux, et l'ensemble sous le
            // chat qui n'est pas encore extrait.
            hudRenderer.run(ctx);
            flushItemIcons(ctx);
            return false;
        });
    }

    private static boolean itemFlushInstalled;

    private static void flushItemIcons(Object hookContext) {
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

    /**
     * Ouvre une nouvelle strate : tout ce qui est émis ensuite passe au-dessus.
     * Mécanisme NATIF de vanilla, exposé ici parce qu'il remplacera à terme nos
     * propres bricolages d'ordre.
     */
    public static void nextStratum(Object hookContext) {
        GuiGraphicsExtractor g = extractor(hookContext);
        if (g == null) return;
        try {
            g.nextStratum();
        } catch (Throwable t) {
            LauncherLog.err("[VanillaGuiLayer] nextStratum: " + t);
        }
    }

    private static int argb(UiColor c) {
        return (Math.round(c.a * 255f) << 24)
             | (Math.round(c.r * 255f) << 16)
             | (Math.round(c.g * 255f) << 8)
             |  Math.round(c.b * 255f);
    }
}
