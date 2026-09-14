package com.yuyuframe.launcheragent.apigraphic.value;

/**
 * Mode de fusion d'une forme avec ce qui est DÉJÀ affiché derrière elle
 * (roadmap Phase 5.4) — formules façon CSS {@code mix-blend-mode}, calculées
 * par pixel dans le shader à partir d'une copie du fond.
 *
 * <p>{@link #code} est la valeur passée aux shaders (mêmes codes sur toutes
 * les ères : {@code Blaze3DBlend.MODE_*} et {@code Gl3Blend}).
 */
public enum UiBlendMode {
    /** {@code haut × fond} — assombrit. */
    MULTIPLY(0),
    /** {@code 1 − (1 − haut)(1 − fond)} — éclaircit. */
    SCREEN(1),
    /** Multiply sur les fonds sombres, screen sur les clairs — renforce le contraste. */
    OVERLAY(2);

    public final int code;

    UiBlendMode(int code) {
        this.code = code;
    }
}
