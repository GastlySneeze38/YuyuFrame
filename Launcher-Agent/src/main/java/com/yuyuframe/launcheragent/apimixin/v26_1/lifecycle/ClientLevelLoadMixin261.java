package com.yuyuframe.launcheragent.apimixin.v26_1.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code MinecraftMixin#afterClientLevelChange} (fabric-lifecycle-events-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#CLIENT_LEVEL_LOAD} — TAIL de
 * {@code updateLevelInEngines}, seulement dispatché si {@code level != null}
 * (déconnexion = level null, pas un vrai "chargement").
 *
 * ⚠️ HISTORIQUE (2026-08-24, [[project_mc_261_port]] §10) — retiré du JSON
 * PAR PRÉCAUTION, jamais testé isolément : même classe cible
 * ({@code Minecraft}) et même famille que {@code ClientTickMixin261}, dont le
 * {@code VerifyError} sur {@code setScreen} était alors avéré.
 *
 * ✅ RÉTABLI le 2026-08-25 (§12), la cause commune ayant été corrigée — voir
 * la javadoc de {@code ClientTickMixin261} pour le détail, et celle de
 * {@code LauncherMixinService.gameClassLoader} pour le mécanisme (résolution
 * des classes du jeu impossible pendant COMPUTE_FRAMES → supertype commun
 * {@code Object} → frames invalides sur une méthode arbitraire de la classe).
 *
 * Reste gaté sur {@link HookPoint#CLIENT_LEVEL_LOAD}, non consommé
 * aujourd'hui : il n'est donc pas tissé en pratique.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ClientLevelLoadMixin261 {

    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("TAIL"))
    private void la$dispatchClientLevelLoad(ClientLevel level, boolean stopSound, CallbackInfo ci) {
        if (level != null) {
            VanillaHookRegistry.dispatch(HookPoint.CLIENT_LEVEL_LOAD, level);
        }
    }
}
