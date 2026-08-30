package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.legacy17.SneakRampModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ralentissement de sneak progressif — voir {@code SneakRampModule}.
 *
 * ORIGINE : demande initiale "le sneak est plus lent à descendre en 1.7
 * qu'en 1.8", vérifiée FAUSSE par bytecode réel avant d'écrire quoi que ce
 * soit (javap sur bev.class = KeyboardInput, vrai jar 1.8.9 extrait de
 * versions/1.8.9/1.8.9.jar) : {@code a()V} (tick()) contient EXACTEMENT
 * <pre>
 * if (this.d) { this.a = (float)((double) this.a * 0.3d); this.b = (float)((double) this.b * 0.3d); }
 * </pre>
 * — {@code this.d} = état sneak de l'input (lettre officielle confirmée par
 * javap), {@code this.a}/{@code this.b} = mouvement avant/latéral bruts. Le
 * facteur 0.3 est appliqué EN UN SEUL TICK, aucune rampe à ralentir. Ce
 * Mixin ne "corrige" donc rien d'authentique — il AJOUTE une rampe qui
 * n'existe dans aucune version vanilla, pour recréer la sensation demandée.
 *
 * MÉCANISME : @ModifyConstant remplace le littéral 0.3d (présent DEUX fois
 * dans la méthode, une par axe de mouvement — require=2) par une valeur
 * calculée qui progresse vers 0.3 (sneak) ou 1.0 (debout) au lieu de sauter
 * directement dessus. Le calcul lui-même vit dans un @Inject séparé en HEAD
 * ({@code la$advanceRamp}), qui avance l'état de la rampe UNE SEULE FOIS par
 * tick — @ModifyConstant se contente ensuite de LIRE cette valeur déjà
 * calculée aux deux occurrences, sans jamais l'avancer une deuxième fois
 * (qui doublerait la vitesse de transition puisque le littéral apparaît 2 fois
 * dans le même appel de tick).
 *
 * {@code this.d} est lu en HEAD, donc en retard d'un tick (vanilla ne le
 * réécrit que plus loin dans la méthode) — négligeable sur une transition
 * étalée sur plusieurs ticks, pas la peine d'ajouter un deuxième hook rien
 * que pour ce détail.
 *
 * Champs statiques : KeyboardInput est recréé à chaque changement de
 * monde/serveur (même raison que MixinToggleSneak189/MixinToggleSprint189).
 */
@Mixin(targets = "net.minecraft.client.input.KeyboardInput")
public abstract class MixinSneakRamp189 {

    // Lettre officielle directe ("d") — @Shadow par nom Yarn ne se résout
    // jamais dans ce bootstrap Mixin custom (voir MixinGameRenderer189 pour
    // le correctif déjà appliqué ailleurs suite à une InvalidInjectionException
    // en jeu — même prudence appliquée ici d'emblée).
    @Shadow private boolean d;

    private static float la$currentMultiplier = 1.0f;

    @Inject(method = "a()V", at = @At("HEAD"), require = 0)
    private void la$advanceRamp(CallbackInfo ci) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("sneak-ramp-1-7");
            if (!(moduleBase instanceof SneakRampModule) || !moduleBase.isEnabled()) {
                la$currentMultiplier = 1.0f;
                return;
            }
            SneakRampModule module = (SneakRampModule) moduleBase;

            float rampTicks = Math.max(1f, module.rampTicks);
            float step = 0.7f / rampTicks; // distance totale entre 1.0 (debout) et 0.3 (sneak vanilla)
            float target = this.d ? 0.3f : 1.0f;
            if (la$currentMultiplier < target) {
                la$currentMultiplier = Math.min(target, la$currentMultiplier + step);
            } else if (la$currentMultiplier > target) {
                la$currentMultiplier = Math.max(target, la$currentMultiplier - step);
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinSneakRamp189] la$advanceRamp: " + t);
        }
    }

    @ModifyConstant(method = "a()V", constant = @Constant(doubleValue = 0.3d), require = 2)
    private double la$applyRampedSlowdown(double original) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("sneak-ramp-1-7");
            if (!(moduleBase instanceof SneakRampModule) || !moduleBase.isEnabled()) return original;
            return la$currentMultiplier;
        } catch (Throwable t) {
            LauncherLog.err("[MixinSneakRamp189] la$applyRampedSlowdown: " + t);
            return original;
        }
    }
}
