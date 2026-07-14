package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 (voir {@code CrosshairMixin}) pour MC 26.1+ —
 * {@code net.minecraft.client.gui.hud.InGameHud} devient {@code
 * net.minecraft.client.gui.Gui}, et {@code renderCrosshair(DrawContext,
 * RenderTickCounter)} devient {@code extractCrosshair(GuiGraphicsExtractor,
 * DeltaTracker)} — RENOMMÉ, pas juste les types des paramètres (Mojang a
 * restructuré tout le pipeline de dessin GUI autour d'un motif "extraction
 * d'état de rendu" sur 26.1+, {@code GuiGraphicsExtractor} remplaçant
 * l'ancien {@code DrawContext} — voir {@code UiRenderer}/le reste du moteur
 * UI custom : AUCUN souci ici, ce Mixin ne lit/dessine JAMAIS via ce
 * paramètre, il se contente d'ANNULER l'appel, donc son renommage/sa
 * restructuration interne n'a aucune incidence sur ce hook précis).
 * Vérifié via {@code javap} sur le jar client 26.1.2 réel :
 * {@code private void extractCrosshair(GuiGraphicsExtractor, DeltaTracker);}
 * — désormais PRIVATE (était accessible autrement avant), sans incidence
 * pour {@code @Inject} (fusionne directement dans le bytecode de la
 * méthode, indépendant de sa visibilité Java).
 *
 * Vérifié en jeu (26.1.2).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class CrosshairMixin261 {

    @Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideVanillaCrosshair(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("custom-crosshair");
            if (module != null && module.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[CrosshairMixin261] la$hideVanillaCrosshair: " + t);
        }
    }
}
