package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Retire l'effet Ténèbres (Warden / Sculk Shrieker, ajouté en 1.19) dès
 * qu'il apparaît sur le joueur local — même technique que n'importe quel
 * mod client "no darkness" : pas de Mixin, juste une suppression répétée
 * À CHAQUE tick (le serveur peut la réappliquer tant que la source reste
 * active, une seule suppression au moment de l'apparition ne suffit pas).
 * Absent avant 1.19 (effet inexistant, aucun mapping) — dégrade proprement
 * en ne faisant rien plutôt que planter.
 *
 * {@code StatusEffects.DARKNESS} (Yarn) / réel Mojang 26.1+ {@code
 * MobEffects.DARKNESS} (vérifié par javap sur le jar client 26.1.2 réel) —
 * un {@code Holder<MobEffect>} DIRECTEMENT (pas de RegistryEntry à
 * déballer, contrairement à {@code StatusEffectInstance.getEffectType()}
 * dans {@link PotionEffectsModule} : ici c'est déjà le type déclaré du
 * champ). {@code hasStatusEffect}/{@code removeStatusEffect} (Yarn) → réel
 * {@code hasEffect}/{@code removeEffect}, mêmes signatures à 1 argument
 * {@code Holder}.
 */
public final class NoDarknessModule extends LauncherModule {

    private static boolean errorLogged;

    public NoDarknessModule() {
        super("no-darkness", "Sans Ténèbres", "Retire l'effet Ténèbres (Warden / Sculk Shrieker) dès qu'il apparaît", false);
    }

    @Override
    public void onTick() {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Field playerField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player");
            if (playerField == null) return;
            Object player = playerField.get(mc);
            if (player == null) return;

            // Holder direct — voir javadoc de tête, PAS de déballage value() ici.
            Class<?> holderClass = McReflect.yarnClass("net/minecraft/registry/entry/RegistryEntry", "net.minecraft.core.Holder");
            if (holderClass == null) return;

            Class<?> statusEffectsClass = McReflect.yarnClass("net/minecraft/entity/effect/StatusEffects", "net.minecraft.world.effect.MobEffects");
            if (statusEffectsClass == null) return; // absent avant 1.19, voir javadoc de tête
            Field darknessField = McReflect.field(statusEffectsClass, "net/minecraft/entity/effect/StatusEffects", "DARKNESS");
            if (darknessField == null) return;
            Object darkness = darknessField.get(null);
            if (darkness == null) return;

            Method hasEffect = McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity",
                "hasStatusEffect", "hasEffect", holderClass);
            Method removeEffect = McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity",
                "removeStatusEffect", "removeEffect", holderClass);
            if (hasEffect == null || removeEffect == null) return;

            if ((boolean) hasEffect.invoke(player, darkness)) removeEffect.invoke(player, darkness);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[NoDarknessModule] onTick: " + t);
            }
        }
    }
}
