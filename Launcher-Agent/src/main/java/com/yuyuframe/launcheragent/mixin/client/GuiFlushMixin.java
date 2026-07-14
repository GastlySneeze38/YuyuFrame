package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Soumet les icônes d'objet vanilla mises en file par {@link UiRenderer}
 * (armure/main de {@code ArmorDurabilityModule}, voir sa javadoc dans
 * UiRenderer "Icône d'objet vanilla — pipeline MODERNE") dans l'état
 * GuiRenderState VIVANT de vanilla — {@code this} ici EST l'instance réelle
 * de GameRenderer (fusionnée par Mixin), donc {@code this.guiRenderer.state}
 * est le MÊME objet que GameRenderer.render() va lui-même envoyer au GPU un
 * peu plus loin dans cette même méthode (GuiRenderer.render(GpuBufferSlice),
 * tracé par désassemblage bytecode — voir historique de session).
 *
 * HEAD, pas TAIL (contrairement à GlobalUiRenderMixin, qui gère la logique du
 * moteur UI custom et n'a pas cette contrainte) : le flush réel a lieu QUELQUE
 * PART DANS cette méthode, donc TAIL serait TROP TARD. HEAD est sûr ici (pas
 * juste "avant le flush" par chance) car AUCUN reset()/clear() de
 * GuiRenderState n'a lieu dans render() lui-même — le peuplement du HUD
 * vanilla se fait dans une passe "extract" séparée, appelée AVANT render()
 * (confirmé par trace bytecode complète sur l'équivalent réel 26.1.2, même
 * famille d'architecture GuiRenderer/GuiRenderState qu'ici).
 *
 * Mixin distinct de GlobalUiRenderMixin (même méthode cible, classe séparée)
 * pour ne pas mélanger deux responsabilités sans rapport (logique moteur UI
 * vs participation au pipeline de rendu vanilla) — Sponge Mixin supporte
 * nativement plusieurs @Mixin sur la même méthode cible.
 *
 * BUG TROUVÉ (test utilisateur, 26.1.2) : {@code LinkageError: loader
 * constraint violation ... UiColor ... previously loaded by 'knot'} —
 * exactement le piège de classloader documenté dans la javadoc de
 * GlobalUiRenderMixin (Knot/'app'), mais réintroduit ICI : HEAD s'exécute
 * TOUJOURS avant TAIL dans un même appel de méthode, donc au tout premier
 * frame, ce hook touchait {@link UiRenderer} (et transitivement UiColor)
 * AVANT que {@code FabricKnotExposer.ensureExposed()} n'ait eu la chance de
 * tourner (lui, câblé en TAIL sur GlobalUiRenderMixin) — chargement via 'app'
 * ici, puis via 'knot' plus tard ailleurs, définitions incompatibles de la
 * MÊME classe. Fix : appeler ensureExposed() ICI AUSSI, en tout premier —
 * idempotent, sans coût si déjà fait.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GuiFlushMixin {

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIcons(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin: " + t);
        }
    }
}
