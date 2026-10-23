package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

import java.util.HashMap;
import java.util.Map;

/**
 * La pose d'un {@link UiModel} pour une image : les angles et décalages de
 * certaines parties, désignées par leur nom.
 *
 * <p>C'est l'équivalent du {@code setupAnim} des modèles du jeu : une partie
 * non citée garde sa pose au repos. Un angle posé ici <b>remplace</b> celui du
 * repos (comme le jeu écrit {@code lid.xRot}), un décalage s'ajoute au pivot.
 *
 * <p>Objet réutilisable : on le remplit à chaque image, rien n'est alloué
 * tant que les mêmes parties bougent.
 */
public final class UiModelPose {

    private final Map<String, float[]> rotations = new HashMap<>();
    private final Map<String, float[]> offsets = new HashMap<>();

    /** Angles de {@code part}, en radians, ordre Z puis Y puis X comme dans le jeu. */
    public UiModelPose rotate(String part, float x, float y, float z) {
        float[] r = rotations.computeIfAbsent(part, k -> new float[3]);
        r[0] = x; r[1] = y; r[2] = z;
        return this;
    }

    /** Décale le pivot de {@code part}, en pixels du modèle. */
    public UiModelPose offset(String part, float x, float y, float z) {
        float[] o = offsets.computeIfAbsent(part, k -> new float[3]);
        o[0] = x; o[1] = y; o[2] = z;
        return this;
    }

    /** Remet toutes les parties au repos. */
    public UiModelPose clear() {
        rotations.clear();
        offsets.clear();
        return this;
    }

    /** Angles posés pour {@code part}, ou {@code null} : la partie garde ceux du repos. */
    public float[] rotationOf(String part) {
        return rotations.get(part);
    }

    /** Décalage posé pour {@code part}, ou {@code null}. */
    public float[] offsetOf(String part) {
        return offsets.get(part);
    }
}
