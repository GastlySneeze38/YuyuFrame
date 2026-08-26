package com.yuyuframe.launcheragent.apigraphic;

/**
 * Type de dégradé multi-stop (roadmap Phase 5.1) — voir {@link UiRenderer#drawMultiStopGradientRect}.
 * Paramétrage commun aux 3 backends (GL legacy, GL moderne, Blaze3D era E) :
 * {@code (startX,startY)} = point de départ (LINEAR : t=0 ; RADIAL/CONIC : centre),
 * {@code (endX,endY)} = point de fin (LINEAR : t=1 ; RADIAL : point qui fixe le
 * rayon, à distance {@code |end-start|} du centre ; CONIC : fixe l'angle "0",
 * direction {@code end-start} depuis le centre).
 */
public enum UiGradientType {
    LINEAR, RADIAL, CONIC
}
