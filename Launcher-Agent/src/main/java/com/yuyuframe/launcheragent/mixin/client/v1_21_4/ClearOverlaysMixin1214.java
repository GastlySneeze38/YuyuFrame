package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Portage 1.21.4 de {@code ClearOverlaysMixin} (1.21.11) — même architecture
 * exacte : {@code InGameHud.renderOverlay(DrawContext,Identifier,F)V} un
 * seul point de passage pour l'overlay citrouille ET neige poudreuse,
 * vérifié directement dans {@code mappings/yarn-1.21.4-mergedv2.jar}
 * (méthode {@code method_31977}, IDENTIQUE à 1.21.11 — même signature, même
 * classe déclarante).
 *
 * {@code @Coerce Object} pour les deux paramètres capturés — jamais de type
 * stub concret (voir la leçon `ClearOverlaysMixin`/1.21.11 dans
 * module-bracket-audit.md : Sponge exige une correspondance EXACTE de type
 * pour un paramètre capturé, et un stub compile-only n'est jamais la vraie
 * classe obfusquée runtime — appliqué ici dès l'écriture, pas après un
 * crash comme la première fois).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class ClearOverlaysMixin1214 {

    private static volatile Method mGetPath;

    @Inject(method = "renderOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$filterTextureOverlay(@Coerce Object context, @Coerce Object textureId, float alpha, CallbackInfo ci) {
        try {
            if (textureId == null) return;
            if (mGetPath == null) {
                mGetPath = McReflect.noArgMethod(textureId.getClass(), "net/minecraft/util/Identifier", "getPath");
                if (mGetPath == null) return;
            }
            Object pathObj = mGetPath.invoke(textureId);
            String path = pathObj != null ? pathObj.toString() : null;
            if (path == null) return;

            if (path.contains("pumpkin")) {
                LauncherModule module = ModuleRegistry.get("no-pumpkin-overlay");
                if (module != null && module.isEnabled()) ci.cancel();
                return;
            }
            if (path.contains("powder_snow")) {
                LauncherModule module = ModuleRegistry.get("clear-vision");
                if (module != null && module.isEnabled()) ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[ClearOverlaysMixin1214] la$filterTextureOverlay: " + t);
        }
    }
}
