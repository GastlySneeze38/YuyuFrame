package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY} sur 1.21.11 —
 * {@code InGameHud.renderOverlay(DrawContext, Identifier, float)}, point de
 * passage commun aux overlays citrouille et neige poudreuse.
 *
 * <p>Remplace {@code mixin.client.ClearOverlaysMixin}, qui décidait lui-même
 * (lecture de {@code no-pumpkin-overlay}/{@code clear-vision} en dur) : ce
 * mixin ne fait plus que dispatcher, le filtrage appartient aux modules.
 *
 * <h2>Le contrat passe au chemin de texture (String)</h2>
 *
 * En 26.1.2, {@code ctx} était l'{@code Identifier} lui-même, et les modules
 * testaient {@code ctx instanceof Identifier} — un type 26.1.2. Sur cette
 * version l'identifiant est une classe obfusquée : ce test aurait toujours
 * échoué, en silence. Le HookPoint transporte donc désormais le CHEMIN de la
 * texture, une {@code String} — valable sur toutes les versions, et les
 * modules n'ont plus à connaître le moindre type du jeu. Le mixin 26.1.2 a été
 * aligné dans le même changement.
 *
 * <h2>Plus de réflexion (2026-09-13)</h2>
 *
 * Le chemin était lu par {@code McReflect} sur {@code Identifier.getPath}. Un
 * Mixin ne peut pas l'appeler lui-même : son corps n'est pas traduit au
 * chargement, et le nom est obfusqué sur cette version. Il passe donc par le
 * point d'accès {@link AccessPoint#IDENTIFIER_PATH}, dont la liaison typée vit
 * dans {@code AccessorBindings1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractTextureOverlayMixin1211 {

    @Inject(method = "renderOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchTextureOverlay(@Coerce Object context, @Coerce Object textureId, float alpha, CallbackInfo ci) {
        try {
            if (textureId == null) return;
            // null = liaison absente ou en échec, déjà journalisé une fois par AccessorRegistry.
            Object path = AccessorRegistry.get(AccessPoint.IDENTIFIER_PATH, textureId);
            if (path instanceof String && VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, path)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[HudExtractTextureOverlayMixin1211] " + t);
        }
    }
}
