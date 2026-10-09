package com.yuyuframe.launcheragent.apigraphic.hud;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * L'icône d'un objet d'EXEMPLE, pour l'aperçu de l'éditeur de HUD (voir
 * {@link HudPreview}) : là où un module attend la poignée d'une vraie pile
 * d'objets du jeu, l'exemple porte ceci à la place — une image, la vraie
 * texture de l'objet lue dans les ressources du jeu.
 *
 * <p>{@code UiRenderer.drawVanillaItemIcon} la reconnaît et dessine l'image ;
 * les modules n'ont donc rien à savoir de l'aperçu pour leurs icônes.
 */
public final class HudSampleIcon {

    /** Clé de cache de la texture côté moteur : stable, une par objet. */
    public final String key;
    private final Supplier<BufferedImage> image;

    public HudSampleIcon(String key, Supplier<BufferedImage> image) {
        this.key = key;
        this.image = image;
    }

    /** L'image, ou {@code null} si la texture n'a pas pu être lue (l'icône est alors simplement absente). */
    public BufferedImage image() {
        return image.get();
    }
}
