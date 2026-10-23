package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

/**
 * Où et comment un modèle est montré : sa place à l'écran, sa taille, son
 * orientation et la perspective.
 *
 * <p>Le modèle tourne autour de son {@link #origin} (par défaut le centre de
 * sa boîte, posé par l'appelant), dans l'ordre : roulis, lacet, tangage — le
 * lacet fait tourner le modèle sur lui-même, le tangage le fait basculer vers
 * celui qui regarde. Il est ensuite déplacé de {@link #translate} (en pixels
 * du modèle, après rotation : pour un saut, une secousse), puis projeté.
 *
 * <h2>Perspective</h2>
 *
 * L'œil est à {@link #eyeDistance} pixels du modèle devant l'écran, sur l'axe
 * qui passe par le centre {@code (centerX, centerY)}. Plus il est proche, plus
 * la perspective est marquée ; {@code 0} = projection orthographique (pas de
 * fuite). Un point à la profondeur de l'origine garde exactement
 * {@link #scale} pixels d'écran par pixel du modèle.
 *
 * <p>Coordonnées d'écran dans le repère du moteur : pixels du framebuffer,
 * <b>Y vers le haut</b>, origine en bas à gauche.
 *
 * <p>Objet réutilisable, rempli à chaque image.
 */
public final class UiView3D {

    private float centerX, centerY;
    private float scale = 1f;
    private float yaw, pitch, roll;
    private float originX, originY, originZ;
    private float translateX, translateY, translateZ;
    private float eyeDistance;

    /** Le point de l'écran où tombe l'origine du modèle. */
    public UiView3D center(float x, float y) {
        centerX = x; centerY = y;
        return this;
    }

    /** Pixels d'écran par pixel du modèle, à la profondeur de l'origine. */
    public UiView3D scale(float pixelsPerUnit) {
        scale = pixelsPerUnit;
        return this;
    }

    /** En radians : lacet (autour de Y), tangage (autour de X), roulis (autour de Z). */
    public UiView3D rotation(float yaw, float pitch, float roll) {
        this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        return this;
    }

    /** Le point du modèle autour duquel il tourne et qui tombe sur {@link #center}. */
    public UiView3D origin(float x, float y, float z) {
        originX = x; originY = y; originZ = z;
        return this;
    }

    /** Déplacement après rotation, en pixels du modèle (Y vers le haut, Z vers l'œil). */
    public UiView3D translate(float x, float y, float z) {
        translateX = x; translateY = y; translateZ = z;
        return this;
    }

    /** Distance de l'œil en pixels du modèle ; {@code 0} = sans perspective. */
    public UiView3D eyeDistance(float distance) {
        eyeDistance = Math.max(0f, distance);
        return this;
    }

    public float centerX() { return centerX; }
    public float centerY() { return centerY; }
    public float scale() { return scale; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    public float originX() { return originX; }
    public float originY() { return originY; }
    public float originZ() { return originZ; }
    public float translateX() { return translateX; }
    public float translateY() { return translateY; }
    public float translateZ() { return translateZ; }
    public float eyeDistance() { return eyeDistance; }
}
