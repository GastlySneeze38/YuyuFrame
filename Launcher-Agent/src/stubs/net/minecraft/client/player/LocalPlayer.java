package net.minecraft.client.player;

import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.UUID;

/**
 * Stub compile-only (26.1+) — méthodes publiques ajoutées pour plusieurs
 * modules (PotionEffects/Ping/NoDarkness/Crosshair/ArmorDurability/Saturation,
 * voir audit ROADMAP-agent.md §3.3) — appel direct depuis {@code
 * Minecraft.player} (champ public, voir stub {@code Minecraft}), aucune
 * réflexion. Toutes déclarées ici (même si réellement héritées de {@code
 * Player}/{@code LivingEntity}/{@code Entity} côté Mojang) : le JVM résout
 * l'appel par la hiérarchie RÉELLE au runtime, pas par la nôtre — un stub
 * "à plat" suffit, même principe que partout ailleurs dans ce projet.
 */
public abstract class LocalPlayer {
    public Collection<MobEffectInstance> getActiveEffects() { return null; }
    public boolean hasEffect(Holder<MobEffect> effect) { return false; }
    public void removeEffect(Holder<MobEffect> effect) {}
    public FoodData getFoodData() { return null; }
    public ItemStack getItemBySlot(EquipmentSlot slot) { return null; }
    public ItemStack getItemInHand(InteractionHand hand) { return null; }
    public HumanoidArm getMainArm() { return null; }
    public float getAttackStrengthScale(float adjustTicks) { return 0f; }
    public UUID getUUID() { return null; }
    public double getX() { return 0d; }
    public double getY() { return 0d; }
    public double getZ() { return 0d; }
    public float getYRot() { return 0f; }
    public void turn(double yRot, double xRot) {}
}
