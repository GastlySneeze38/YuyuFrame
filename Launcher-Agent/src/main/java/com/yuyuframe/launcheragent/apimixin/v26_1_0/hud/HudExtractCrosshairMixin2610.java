package com.yuyuframe.launcheragent.apimixin.v26_1_0.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapCrosshair} vers {@link HookPoint#HUD_EXTRACT_CROSSHAIR}
 * — voir {@link HudExtractCameraOverlayMixin2610} pour l'explication complète
 * du pattern (un mixin par HookPoint, simplification volontaire du système
 * de couches Fabric).
 *
 * ⚠️ NE PAS repasser sur un injecteur de SITE D'APPEL (2026-08-25, §14).
 * Ce fichier a successivement été {@code @WrapOperation} puis {@code @Redirect}
 * sur l'appel {@code Gui.extractCrosshair(…)} DANS {@code extractRenderState} :
 * les deux échouaient, avec {@code « Critical injection failure: …
 * (0/1) succeeded. Scanned 0 target(s) »} une fois l'échec rendu bruyant.
 *
 * Cause RÉELLE, trouvée en désassemblant à l'exécution les octets reçus par
 * {@link LauncherMixinTransformerWrapper} : <b>Iris</b> applique {@code
 * @WrapMethod} (MixinExtras) sur {@code Gui.extractRenderState}, ce qui
 * DÉPLACE tout le corps d'origine dans une méthode synthétique. Chez le
 * joueur, {@code extractRenderState} ne contient plus qu'une seule
 * instruction — {@code invokevirtual Gui.wrapMethod$bne000$iris$handleHudHidingScreens}
 * — et l'appel à {@code extractCrosshair} vit désormais DANS ce wrapper.
 * Aucun sélecteur {@code @At(INVOKE)} portant sur {@code extractRenderState}
 * ne peut donc plus le voir. Le nom du wrapper contient un identifiant
 * généré ({@code bne000}), il n'est ni stable ni ciblable.
 *
 * Leçon générale, valable pour tout {@code apimixin/} : à côté de ~89 mods,
 * un injecteur de SITE D'APPEL est fragile — n'importe quel mod qui enveloppe
 * ou redirige la méthode englobante fait disparaître le site. Injecter dans
 * la MÉTHODE APPELÉE elle-même ({@code @Inject} HEAD + {@code cancellable})
 * y est insensible : peu importe d'où l'appel part, il aboutit toujours ici.
 * C'est exactement ce que fait le voisin {@code HudExtractTextureOverlayMixin2610},
 * sur la MÊME classe, et c'est le seul des deux qui n'ait jamais cassé.
 *
 * Ceci n'est PAS un problème MixinExtras : MixinExtras fonctionne bien chez
 * nous depuis {@code IsolatedBootstrap.initMixinExtras()} (voir sa javadoc) —
 * c'est même Iris, via MixinExtras, qui provoque la situation.
 *
 * Descripteur complet obligatoire dans {@code method} : aucun mixin
 * {@code apimixin/} n'a d'entrée dans le refmap runtime.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractCrosshairMixin2610 {

    @Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"), cancellable = true)
    private void la$dispatchCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CROSSHAIR, graphics)) {
            ci.cancel();
        }
    }
}
