package net.minecraft.world.effect;

import net.minecraft.network.chat.Component;

/** Stub compile-only (26.1+) — méthodes publiques pour {@code PotionEffectsModule}. */
public abstract class MobEffect {
    public String getDescriptionId() { return null; }
    public int getColor() { return 0; }
    /** Nom TRADUIT de l'effet — descripteur vérifié sur le jar 26.1.2 : {@code ()Lnet/minecraft/network/chat/Component;}. Remplace le découpage maison de {@code getDescriptionId()}, qui rendait un nom anglais bricolé. */
    public Component getDisplayName() { return null; }
    /** {@code true} pour un effet positif — sert au tri du HUD, comme vanilla qui sépare bénéfiques et néfastes. Descripteur vérifié : {@code ()Z}. */
    public boolean isBeneficial() { return false; }
}
