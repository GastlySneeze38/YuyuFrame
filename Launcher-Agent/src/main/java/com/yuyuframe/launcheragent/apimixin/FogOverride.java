package com.yuyuframe.launcheragent.apimixin;

/**
 * Valeurs écrites par les mixins {@code FOG_SETUP_*} quand un handler demande
 * de repousser le brouillard.
 *
 * <p>Contrat des HookPoints de brouillard (2026-09-11) : le handler ne touche
 * plus au {@code FogData} — il répond seulement {@code true} (« repousse-le »)
 * ou {@code false}. C'est le mixin de CHAQUE version qui écrit dans son propre
 * {@code FogData}, dont la classe et les champs changent d'une version à
 * l'autre. Avant, les modules faisaient {@code instanceof FogData} contre le
 * type 26.1.2, faux sur toute version obfusquée — même problème que
 * l'{@code Identifier} de {@link HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY}.
 */
public final class FogOverride {

    private FogOverride() {}

    /** Début du brouillard repoussé — la fin est à {@code 2 × FAR}. */
    public static final float FAR = 1_000_000f;
}
