package com.yuyuframe.launcheragent.apimixin.v1_8_9.freelook;

/**
 * Le décalage que le freelook a appliqué à la caméra pour l'image en cours,
 * en degrés — zéro hors freelook. Écrit par {@code CameraFreelookMixin189} à
 * chaque pose de caméra, relu par ce qui doit rester face à elle (les
 * étiquettes, voir {@code LabelFacing189}). Thread de rendu seulement.
 */
public final class FreelookOffset189 {

    public static float yaw;
    public static float pitch;

    private FreelookOffset189() {
    }
}
