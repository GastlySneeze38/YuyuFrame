package com.yuyuframe.launcheragent.apimixin.v26_2.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code MinecraftMixin#checkThreadOnDev} (fabric-screen-api-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#SCREEN_SET} — transition
 * d'écran, HEAD de {@code setScreen} (avant que le nouvel écran ne soit
 * effectivement posé).
 *
 * ⚠️ HISTORIQUE (2026-08-24, [[project_mc_261_port]] §10) — retiré du JSON
 * après bissection en jeu : il provoquait alors un {@code VerifyError}
 * "Bad type on operand stack" sur {@code Minecraft.setScreen}. Conclusion
 * retenue à l'époque : « incompatibilité entre au moins un mixin apimixin sur
 * {@code Minecraft} et un autre mod modifiant cette classe » (soupçon sur
 * {@code fabric-screen-api-v1}).
 *
 * ✅ RÉTABLI le 2026-08-25 (§12) — ce crash ne se reproduit plus. Remis dans
 * le JSON, il se tisse proprement ({@code Mixing v26_2.screen.ScreenSetMixin262
 * … into net.minecraft.client.Minecraft}, handler
 * {@code handler$zba000$la$dispatchScreenSet}) et le jeu tourne sans
 * {@code VerifyError} — constaté sur 5 lancements consécutifs (v723→v727),
 * modpack identique, {@code fabric-screen-api-v1} toujours présent.
 *
 * Deux correctifs sont intervenus entre-temps, sans qu'on puisse attribuer le
 * mérite à l'un plutôt qu'à l'autre : {@code
 * LauncherMixinService.getClassNode()} n'interrogeait que {@code isolatedCl},
 * qui ne voit pas le jar du jeu — toute classe du jeu échouait donc à se
 * résoudre pendant le calcul ASM COMPUTE_FRAMES, faisant retomber le supertype
 * commun sur {@code Object} et produisant des frames invalides (voir la
 * javadoc de {@code LauncherMixinService.gameClassLoader}, qui décrit
 * exactement cette signature d'erreur) ; et {@code
 * LauncherMixinTransformerWrapper.triggerEarlyKnotExpose()} pour la résolution
 * APP/Knot.
 *
 * Reste gaté sur {@link HookPoint#SCREEN_SET} : aucun module ne le consomme
 * aujourd'hui, donc il n'est pas tissé en pratique — c'est voulu, la gate est
 * une optimisation du temps de tissage.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ScreenSetMixin262 {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void la$dispatchScreenSet(Screen screen, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_SET, screen);
    }
}
