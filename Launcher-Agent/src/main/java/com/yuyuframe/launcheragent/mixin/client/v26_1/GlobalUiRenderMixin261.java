package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 (voir {@code GlobalUiRenderMixin}, javadoc de
 * tête) pour MC 26.1+ — même logique, MÊME point d'accroche conceptuel
 * (TAIL de {@code GameRenderer.render}, la classe reste chargée assez tard
 * pour ne jamais subir le problème de retransform à chaud documenté sur
 * l'original), seuls les noms de classe/méthode/type changent :
 * {@code net.minecraft.client.render.GameRenderer} → {@code
 * net.minecraft.client.renderer.GameRenderer} (package renommé
 * "render"→"renderer"), {@code RenderTickCounter} → {@code DeltaTracker}
 * (classe renommée) — vérifié via {@code javap} sur le jar client 26.1.2 réel :
 * {@code public void render(net.minecraft.client.DeltaTracker, boolean);}.
 *
 * Vérifié en jeu (menu/HUD custom fonctionnels sur 26.1.2).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GlobalUiRenderMixin261 {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            // Voir GlobalUiRenderMixin (bracket 1.21.11) pour le pourquoi complet
            // de cet appel en tout premier — même piège de classloader Knot/app
            // attendu ici, aucune raison qu'il ait disparu sur 26.1+.
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge261.getMcInstance();
            if (mc == null) return;

            if (GlobalUiRenderBridge261.inputPoller == null) {
                long handle = GlobalUiRenderBridge261.getWindowHandle(mc);
                if (handle == 0L) return;
                GlobalUiRenderBridge261.inputPoller = new UiInputPollerModern(handle, this.getClass().getClassLoader());
                ModuleRegistry.all();
                GlobalUiSettings.INSTANCE.onConfigChanged();
            }
            GlobalUiRenderBridge261.inputPoller.poll();

            // BUG TROUVÉ : voir GlobalUiRenderMixin (bracket 1.21.11) pour le
            // détail complet — tickAll() sans son propre try/catch pouvait,
            // si un module échouait à son init statique, bloquer
            // PERMANENTEMENT le test d'ouverture du menu juste en dessous
            // (NoClassDefFoundError relancé à chaque frame par
            // ModuleRegistry, catché par le catch global de la méthode AVANT
            // d'atteindre ce test). Isolé ici pour que la touche de menu
            // reste toujours accessible quoi qu'il arrive au reste du moteur
            // de modules.
            try {
                ModuleRegistry.tickAll();
            } catch (Throwable t) {
                LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261: ModuleRegistry.tickAll() a levé: " + t);
            }

            Object currentScreen = GlobalUiRenderBridge261.getCurrentScreen(mc);
            // Voir GlobalUiRenderBridge261.isMouseGrabbed() : empêche notre
            // menu de s'ouvrir PAR-DESSUS un éditeur en overlay d'un autre
            // mod (ex: Axiom) qui ne passe pas par Minecraft.setScreen() et
            // que "currentScreen == null" seul ne peut donc pas détecter.
            if (currentScreen == null && GlobalUiRenderBridge261.inputPoller.menuKeyPressed
                    && GlobalUiRenderBridge261.isMouseGrabbed(mc)) {
                try {
                    GlobalUiRenderBridge261.setScreen(mc, new UiMainMenuScreen(null));
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261: setScreen(UiMainMenuScreen) a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261: " + t);
        }
    }
}
