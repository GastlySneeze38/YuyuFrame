package com.yuyuframe.launcheragent.apigraphic.core;

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

    /** Interpolation linéaire composante par composante — utilisé pour les transitions hover/toggle animées. */
    public static UiColor lerp(UiColor from, UiColor to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new UiColor(
            from.r + (to.r - from.r) * t,
            from.g + (to.g - from.g) * t,
            from.b + (to.b - from.b) * t,
            from.a + (to.a - from.a) * t
        );
    }

    /** Même couleur, alpha multiplié par {@code factor} (0..1) — pour un fondu d'entrée/sortie (voir UiTransition) sans changer chaque couleur d'un widget une par une. */
    public UiColor multiplyAlpha(float factor) {
        return new UiColor(r, g, b, a * factor);
    }

    /** Même couleur, alpha REMPLACÉ (pas multiplié, contrairement à {@link #multiplyAlpha}) par {@code newAlpha} — pour les primitives qui calculent une opacité absolue par frame (drawGlow/drawRipple/drawSpinner), plus lisible qu'une division par l'alpha courant pour retomber sur la valeur voulue. */
    public UiColor withAlpha(float newAlpha) {
        return new UiColor(r, g, b, newAlpha);
    }
}
