package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.visual.ClearVisionModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Gui.extractTextureOverlay(GuiGraphicsExtractor, Identifier, float)}
 * est le point de passage COMMUN pour DEUX overlays plein-écran distincts en
 * 26.1+ (vérifié par javap + désassemblage bytecode de {@code
 * extractCameraOverlays}, jar client 26.1.2 réel) :
 * - l'overlay de citrouille sculptée (via le nouveau composant générique
 *   {@code Equippable.cameraOverlay()} — n'importe quel équipement PEUT en
 *   déclarer un, pumpkin n'est plus un cas spécial câblé en dur ; texture
 *   réelle : {@code textures/misc/pumpkinblur.png})
 * - l'overlay de givre de neige poudreuse ({@code POWDER_SNOW_OUTLINE_LOCATION},
 *   valeur littérale {@code textures/misc/powder_snow_outline.png})
 *
 * PAS de contexte suffisant dans le reste de la méthode pour distinguer les
 * deux autrement que par le chemin de la texture demandée (aucun paramètre
 * "type d'overlay" séparé) — d'où le filtrage sur {@code Identifier.getPath()}
 * ci-dessous, chacun contrôlé par son propre module (l'un n'annule jamais
 * l'autre).
 *
 * {@code Identifier}/{@code GuiGraphicsExtractor} : stubs compile-only
 * (voir {@code src/stubs/net/minecraft/resources/Identifier.java}) — noms
 * réels Mojang déjà littéraux sur 26.1+, aucune traduction Yarn nécessaire,
 * juste assez pour typer les paramètres capturés par ce {@code @Inject}.
 *
 * NON RETESTÉ EN JEU au moment de l'écriture.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class ClearOverlaysMixin261 {

    @Inject(method = "extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$filterTextureOverlay(GuiGraphicsExtractor extractor, Identifier textureId, float alpha, CallbackInfo ci) {
        try {
            String path = textureId != null ? textureId.getPath() : null;
            if (path == null) return;

            if (path.contains("pumpkin")) {
                LauncherModule module = ModuleRegistry.get("no-pumpkin-overlay");
                if (module != null && module.isEnabled()) ci.cancel();
                return;
            }
            if (path.contains("powder_snow")) {
                // Demandé explicitement ("3 paramètres indépendants eau/lave/
                // neige") — cet overlay de givre est spécifique à la neige
                // poudreuse, doit donc suivre EXACTEMENT le même réglage que
                // le brouillard neige (voir PowderedSnowFogEnvironmentMixin261),
                // pas juste l'état global du module.
                LauncherModule module = ModuleRegistry.get("clear-vision");
                if (module instanceof ClearVisionModule && module.isEnabled()
                        && ((ClearVisionModule) module).clearPowderSnow) {
                    ci.cancel();
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[ClearOverlaysMixin261] la$filterTextureOverlay: " + t);
        }
    }
}
