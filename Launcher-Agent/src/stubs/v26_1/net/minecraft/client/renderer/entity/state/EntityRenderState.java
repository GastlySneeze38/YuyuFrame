package net.minecraft.client.renderer.entity.state;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Stub compile-only (26.1+) — la copie figée d'une entité, remplie par
 * {@code EntityRenderer.extractRenderState} puis dessinée. Champs PUBLICS
 * vérifiés par javap sur le jar client 26.1.2.
 */
public class EntityRenderState {

    /** Le pseudo à afficher, ou {@code null} si l'étiquette ne doit pas l'être. */
    public Component nameTag;

    /** La ligne de score affichée sous le pseudo, ou {@code null}. */
    public Component scoreText;

    /** Le point d'ancrage de l'étiquette, relatif à l'entité. */
    public Vec3 nameTagAttachment;

    /** Entité accroupie : son étiquette n'est pas visible à travers les murs. */
    public boolean isDiscrete;
}
