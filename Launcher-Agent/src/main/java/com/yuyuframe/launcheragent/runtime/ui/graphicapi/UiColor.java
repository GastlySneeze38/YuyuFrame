package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

/** Couleur RGBA simple, indépendante de toute lib externe (pas de OneColor/NanoVG). */
public final class UiColor {

    public final float r, g, b, a;

    public UiColor(int r, int g, int b, int a) {
        this.r = r / 255f;
        this.g = g / 255f;
        this.b = b / 255f;
        this.a = a / 255f;
    }

    public UiColor(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.a = a;
    }

    public static final UiColor TRANSPARENT = new UiColor(0, 0, 0, 0);
}
