package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

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
 * <p>Le chemin est lu ici par réflexion ({@link McReflect}) : transitoire,
 * jusqu'à l'étape « accessors 1.21.11 ». Méthode résolue une fois puis mise en
 * cache — pas de recherche à chaque frame.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractTextureOverlayMixin1211 {

    private static volatile Method mGetPath;
    private static volatile boolean resolutionFailed;

    @Inject(method = "renderOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchTextureOverlay(@Coerce Object context, @Coerce Object textureId, float alpha, CallbackInfo ci) {
        try {
            if (textureId == null || resolutionFailed) return;
            if (mGetPath == null) {
                mGetPath = McReflect.noArgMethod(textureId.getClass(), "net/minecraft/util/Identifier", "getPath");
                if (mGetPath == null) {
                    resolutionFailed = true;
                    LauncherLog.err("[HudExtractTextureOverlayMixin1211] Identifier.getPath introuvable — overlays non filtrables");
                    return;
                }
            }
            Object path = mGetPath.invoke(textureId);
            if (path != null && VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, path.toString())) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[HudExtractTextureOverlayMixin1211] " + t);
        }
    }
}
