package net.minecraft.world.effect;

import net.minecraft.core.Holder;

/** Stub compile-only (26.1+) — méthodes publiques pour {@code PotionEffectsModule}. */
public abstract class MobEffectInstance {
    public int getAmplifier() { return 0; }
    public int getDuration() { return 0; }
    public Holder<MobEffect> getEffect() { return null; }
    /** {@code true} pour un effet sans fin — {@code getDuration()} vaut alors -1, que l'ancien calcul affichait « 0s ». Descripteur vérifié sur le jar 26.1.2 : {@code ()Z}. */
    public boolean isInfiniteDuration() { return false; }
}
