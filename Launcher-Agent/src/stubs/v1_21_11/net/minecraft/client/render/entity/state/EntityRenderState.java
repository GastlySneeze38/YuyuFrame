package net.minecraft.client.render.entity.state;

import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code idf}) — la copie figée d'une
 * entité. Champs relevés dans yarn-1.21.11-mergedv2.
 */
public class EntityRenderState {

    /** Le pseudo à afficher, ou {@code null}. */
    public Text displayName;

    /** Le point d'ancrage de l'étiquette, relatif à l'entité. */
    public Vec3d nameLabelPos;

    /** Entité accroupie : son étiquette n'est pas visible à travers les murs. */
    public boolean sneaking;
}
