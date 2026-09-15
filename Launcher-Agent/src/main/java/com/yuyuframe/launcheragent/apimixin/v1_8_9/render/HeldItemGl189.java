package com.yuyuframe.launcheragent.apimixin.v1_8_9.render;

import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import org.lwjgl.opengl.GL11;

/**
 * Applique un {@link MatrixOps} à la matrice courante — partagé par les mixins
 * de l'objet tenu (classe normale : Mixin refuse qu'on référence une classe
 * mixin depuis une autre).
 *
 * <p>Appels GL directs plutôt que {@code GlStateManager.translate/rotate} : en
 * 1.8.9 ces deux méthodes ne sont que {@code GL11.glTranslatef}/
 * {@code glRotatef}, sans cache d'état (bytecode {@code bfl.b(FFF)V} et
 * {@code bfl.b(FFFF)V}) — le résultat est identique, sans nommer de classe du
 * jeu dans un corps de mixin, qui n'est pas traduit au chargement.
 */
public final class HeldItemGl189 {

    private HeldItemGl189() {
    }

    public static void apply(MatrixOps ops) {
        for (int i = 0; i < ops.size(); i++) {
            if (ops.kind(i) == MatrixOps.TRANSLATE) {
                GL11.glTranslatef(ops.arg(i, 0), ops.arg(i, 1), ops.arg(i, 2));
            } else {
                GL11.glRotatef(ops.arg(i, 0), ops.arg(i, 1), ops.arg(i, 2), ops.arg(i, 3));
            }
        }
    }
}
