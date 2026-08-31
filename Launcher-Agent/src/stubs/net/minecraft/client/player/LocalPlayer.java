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
    /** Renvoie {@code boolean} (vrai si un effet a bien été retiré), PAS void — le type de retour fait partie du descripteur d'appel, voir le stub {@code SoundEngine}. Vérifié sur {@code LivingEntity.removeEffect(Holder)Z} du jar 26.1.2. */
    public boolean removeEffect(Holder<MobEffect> effect) { return false; }
    public FoodData getFoodData() { return null; }
    /** Mode créatif — la barre de faim vanilla n'est alors pas dessinée, nos overlays non plus (voir {@code SaturationModule}). Descripteur vérifié sur le jar 26.1.2 : {@code ()Z}. */
    public boolean isCreative() { return false; }
    /** Mode spectateur — même raison qu'{@link #isCreative()}. */
    public boolean isSpectator() { return false; }
    /**
     * {@code true} si le joueur peut manger maintenant — {@code false} barre
     * de faim pleine, sauf pour un aliment {@code canAlwaysEat} (pomme dorée…).
     * C'est la garde qu'utilise vanilla lui-même avant de consommer.
     * Descripteur vérifié sur le jar 26.1.2 : {@code canEat(Z)Z}.
     */
    public boolean canEat(boolean canAlwaysEat) { return false; }
    public ItemStack getItemBySlot(EquipmentSlot slot) { return null; }
    public ItemStack getItemInHand(InteractionHand hand) { return null; }
    public HumanoidArm getMainArm() { return null; }
    public float getAttackStrengthScale(float adjustTicks) { return 0f; }
    public UUID getUUID() { return null; }
    public double getX() { return 0d; }
    public double getY() { return 0d; }
    public double getZ() { return 0d; }
    public float getYRot() { return 0f; }
    public float getXRot() { return 0f; }
    public void turn(double yRot, double xRot) {}
    public float getHealth() { return 0f; }
    public float getMaxHealth() { return 0f; }
    public float getEyeHeight() { return 0f; }
    // Object, pas GameProfile : com.mojang.authlib n'est PAS sur le classpath
    // de la passe de compilation des stubs (isolée, voir build.bat) — cast
    // vers com.mojang.authlib.GameProfile côté appelant (classpath complet
    // là-bas), voir MumbleLinkModule.
    /** Renvoie {@code GameProfile} (authlib), PAS {@code Object} : le déclarer en Object produisait un descripteur {@code ()Ljava/lang/Object;} introuvable au runtime. Voir le stub {@code com.mojang.authlib.GameProfile}. */
    public com.mojang.authlib.GameProfile getGameProfile() { return null; }
}
