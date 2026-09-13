package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

import java.awt.image.BufferedImage;

/**
 * Émission dans l'état de GUI de vanilla — une implémentation par version.
 *
 * <h2>Pourquoi cette interface</h2>
 *
 * Le HUD de l'agent est soumis à l'état de GUI de vanilla, à la profondeur du
 * hook {@code HUD_EXTRACT_CHAT} : c'est ce qui le place SOUS le chat et au bon
 * z-order (voir {@code docs/LauncherAgent/rendering-pipeline.md}). Or cet état
 * diffère profondément d'une version à l'autre :
 *
 * <ul>
 *   <li>26.1.2 : le hook transporte un {@code GuiGraphicsExtractor}, les
 *       éléments implémentent {@code GuiElementRenderState} et s'ajoutent par
 *       {@code addGuiElement} ;</li>
 *   <li>1.21.11 : le hook transporte un {@code DrawContext}, les éléments
 *       implémentent {@code SimpleGuiElementRenderState} ({@code setupVertices},
 *       pas {@code buildVertices}) et s'ajoutent par {@code addSimpleElement} ;
 *       l'état lui-même n'est atteignable que capté à la construction du
 *       contexte.</li>
 * </ul>
 *
 * Mêmes noms de classes des deux côtés par endroits, API différentes : un seul
 * code typé est impossible. Chaque version fournit donc son implémentation,
 * compilée dans SON unité (voir {@code build.bat}), et le moteur ne parle qu'à
 * cette interface — exactement le partage déjà retenu pour {@link Blaze3DGpu}.
 *
 * <h2>Contrat</h2>
 * <ul>
 *   <li>{@code hookContext} est la poignée opaque reçue par le hook ; chaque
 *       implémentation sait la retranstyper.</li>
 *   <li>Coordonnées en PIXELS GUI, origine en HAUT à gauche, {@code y} vers le
 *       BAS (convention vanilla) — la conversion depuis le repère du moteur est
 *       faite par {@link VanillaGuiTarget}, une fois pour toutes.</li>
 *   <li>{@code false} = « non pris en charge » : l'appelant garde son chemin
 *       habituel. Jamais d'exception vers l'appelant, jamais d'échec muet.</li>
 * </ul>
 *
 * <p>Obtenue par {@link VanillaGuiSinks#active()}.
 */
public interface VanillaGuiSink {

    /** Identifiant lisible pour les logs (ex. {@code "1.21.11"}). */
    String id();

    /** L'état de GUI derrière ce contexte est-il atteignable ? */
    boolean accepts(Object hookContext);

    /** Largeur de l'écran en PIXELS GUI, ou {@code -1}. */
    int guiWidth(Object hookContext);

    /**
     * Précompile les pipelines nécessaires à une passe de HUD (fond arrondi et
     * texte). Appelé UNE FOIS par passe, pas par primitive.
     */
    boolean ensureCompiled();

    /** Rect à rayon PAR COIN, repère Y vers le bas. */
    boolean roundedRect(Object hookContext, float x0, float y0, float x1, float y1,
                        float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                        UiColor color);

    /** @param baselineY ligne de base, repère Y vers le bas. */
    boolean text(Object hookContext, UiFont font, String content,
                 float x, float baselineY, float scale, UiColor color);

    /** Icône RGBA depuis l'atlas partagé ; {@code alpha} module l'opacité. */
    boolean icon(Object hookContext, String cacheKey, BufferedImage img,
                 float x0, float y0, float x1, float y1, float alpha);

    /** Dégradé de bord plein écran ; {@code color.a} est l'opacité AU BORD. */
    boolean vignette(Object hookContext, float x0, float y0, float x1, float y1,
                     float vSize, UiColor color);

    /**
     * Cette version sait-elle composer un panneau de verre dans l'état de GUI ?
     *
     * <p>Interrogé AVANT de calculer la chaîne de flou : celle-ci coûte
     * plusieurs passes plein écran par frame, inutiles si le composite ne peut
     * pas suivre (voir {@code VanillaGuiTarget.beginGlassFrame}).
     */
    boolean supportsGlassPanel();

    /** Panneau de verre dépoli — la chaîne de flou doit avoir été calculée avant. */
    boolean glassPanel(Object hookContext, float x0, float y0, float x1, float y1,
                       float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                       UiColor tint, UiColor background);

    /** Vide la file d'icônes d'item vanilla dans l'état de GUI (no-op si non porté). */
    void flushItemIcons(Object hookContext);

    // ── Pont vers les renderers d'objets vanilla (2026-09-13) ─────────────
    //
    // Remplacent le pont RÉFLEXIF de Blaze3DVanillaItemRenderer, qui cherchait
    // DrawContext/GuiGraphicsExtractor et ses méthodes par nom Yarn — c'est lui
    // qui avait perdu ses icônes deux fois (mauvaise surcharge en v1105,
    // mappings pas encore chargés en v1112). La file et les diagnostics
    // restent dans le moteur ; seul l'appel au jeu passe ici, typé par version.

    /**
     * L'état de GUI vivant derrière un hôte de vidage, ou {@code null} si cette
     * version ne sert pas cet hôte (l'implémentation le journalise alors une
     * fois).
     */
    Object guiState(com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost host, Object hostObject);

    /**
     * Soumet les blits PUIS les icônes dans {@code guiState} (un blit de fond de
     * conteneur recouvrirait sinon les icônes). Pour chaque icône
     * {@code vanillaExtras} : fond de case AVANT l'icône, décorations APRÈS.
     */
    void drawVanillaItems(Object guiState,
                          java.util.List<com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon> icons,
                          java.util.List<com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit> blits);
}
