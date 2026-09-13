package net.minecraft.client.renderer.fog;

/**
 * Stub compile-only (26.1+) — seuls les 4 champs de distance nous
 * intéressent (voir {@code WaterFogEnvironmentMixin261} et consorts) ;
 * {@code color}/{@code skyEnd}/{@code cloudEnd} volontairement omis (jamais
 * lus/écrits par notre code, pas besoin d'importer {@code org.joml.Vector4f}
 * juste pour ce stub). Voir {@code Identifier.java} pour le pourquoi de ce
 * genre de stub sur 26.1+ (noms réels déjà littéraux, pas de remap
 * ScreenStubPatcher nécessaire).
 */
public final class FogData {
    public float environmentalStart;
    public float renderDistanceStart;
    public float environmentalEnd;
    public float renderDistanceEnd;
}
