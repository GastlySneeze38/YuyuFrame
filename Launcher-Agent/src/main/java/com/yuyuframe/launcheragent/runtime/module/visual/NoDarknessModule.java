package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.yuyuframe.launcheragent.runtime.module.hud.PotionEffectsModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

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
        // Joueur par l'accessor Mixin (PlayerData) + hasEffect/removeEffect
        // (méthodes publiques, voir stub LocalPlayer) — zéro réflexion. Le
        // repli réflexif multi-bracket (résolution de Holder/StatusEffects et
        // du champ statique DARKNESS) a été supprimé le 2026-08-27 ; à noter
        // pour un futur portage : DARKNESS n'existe pas avant la 1.19.
        try {
            LocalPlayer player = PlayerData.player();
            if (player == null) return;
            if (player.hasEffect(MobEffects.DARKNESS)) player.removeEffect(MobEffects.DARKNESS);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[NoDarknessModule] onTick: " + t);
            }
        }
    }
}
