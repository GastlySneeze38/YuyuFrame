package com.yuyuframe.launcheragent.apigraphic.draw.item;

/**
 * Géométrie du fond de case VANILLA — le vrai sprite
 * {@code hud/hotbar_offhand_left}, pas une case recréée.
 *
 * <h2>Pourquoi CE sprite</h2>
 *
 * Premier essai avec {@code container/slot.png} (18x18) — ABANDONNÉ : présent
 * dans le jar et référencé par {@code AbstractContainerScreen} (donc pas un
 * fichier mort), mais un test avec un vrai resource pack « clean UI » est
 * resté sans effet visuel. Ces packs ne personnalisent que la famille
 * {@code hud/hotbar_*} — déjà utilisée nativement pour la VRAIE case de main
 * secondaire à côté de la hotbar, exactement le même contexte visuel que nos
 * cases d'armure.
 *
 * <h2>D'où viennent ces chiffres</h2>
 *
 * Décodage manuel du PNG réel (RGBA8, aucune bibliothèque d'image disponible
 * dans l'environnement — parseur zlib + filtres de scanline écrit à la main) :
 * case visible = coin arrondi occupant grosso modo x=[0,22] y=[1,23] du canvas
 * 29x24 (le reste, x&gt;22, est transparent — laissé tel quel, PAS recadré, pour
 * rester au plus près du sprite vanilla réel) ; zone intérieure destinée à
 * l'icône = EXACTEMENT 16x16 à l'offset (3,4) depuis le coin haut-gauche, ce
 * qui coïncide pile avec la taille native de nos icônes. Aucune supposition.
 *
 * <h2>Disponibilité</h2>
 *
 * Le dessin passe par {@code DrawContext.drawGuiTexture(RenderPipeline,
 * Identifier,x,y,w,h)} (Yarn 1.21.11, {@code method_52706}) ==
 * {@code GuiGraphicsExtractor.blitSprite} (vrai nom 26.1.2, même descripteur).
 * Ce descripteur n'existe que sur les brackets différés : sur 1.20.4/1.21.4,
 * {@code method_52706} existe MAIS avec un descripteur DIFFÉRENT par bracket
 * (1.20.4 : pas de {@code RenderPipeline} du tout ; 1.21.4 : un
 * {@code Function<Identifier,RenderPipeline>}) — trois signatures pour le même
 * identifiant intermédiaire selon la version. L'ère gl3 passe donc par le
 * fichier PNG brut ({@link #PATH}) plutôt que par l'atlas.
 */
public final class VanillaSlotSprite {

    private VanillaSlotSprite() {}

    public static final int W = 29;
    public static final int H = 24;

    /** Décalage de l'icône 16x16 depuis le coin haut-gauche du sprite. */
    public static final int ICON_DX = 3;
    public static final int ICON_DY = 4;

    /** Nom d'ATLAS, pour le chemin différé ({@code drawGuiTexture}/{@code blitSprite}). */
    public static final String SPRITE = "hud/hotbar_offhand_left";

    /** Fichier PNG BRUT, pour le chemin immédiat, qui n'a pas d'accès à l'atlas côté résolution. */
    public static final String PATH = "textures/gui/sprites/hud/hotbar_offhand_left.png";
}
