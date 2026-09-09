package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 ({@link com.yuyuframe.launcheragent.mixin.client.GuiFlushMixin},
 * voir sa javadoc pour le pourquoi complet — y compris le piège de
 * classloader Knot/'app' et son correctif, ensureExposed() en tout premier)
 * pour MC 26.1+ — seuls les noms de classe/méthode changent : {@code
 * net.minecraft.client.render.GameRenderer} → {@code
 * net.minecraft.client.renderer.GameRenderer}, {@code RenderTickCounter} →
 * {@code DeltaTracker} — vérifié par désassemblage (classdump maison, javap
 * ne lit pas le class file version 69 de ce build) sur le jar client 26.1.2
 * réel : {@code public void render(net.minecraft.client.DeltaTracker, boolean)},
 * aucun reset()/clear() de GuiRenderState dans cette méthode elle-même (trace
 * bytecode complète, méthode "extract" séparée en amont).
 *
 * BUG TROUVÉ (test utilisateur, 26.1.2) : {@code LinkageError: loader
 * constraint violation ... UiColor ... previously loaded by 'knot'} — voir
 * GuiFlushMixin (bracket 1.21.11) pour le détail complet du mécanisme —
 * corrigé via ensureExposed() en tout premier.
 *
 * BUG TROUVÉ #2 (test utilisateur, 26.1.2, icônes visibles mais mal orientées
 * ET mal éclairées) : injecter en HEAD (comme initialement) soumettait nos
 * drawItem AVANT que vanilla n'ait établi le contexte GL/lumière/projection
 * propre au frame courant pour CE rendu GUI — à HEAD, cet état est encore
 * celui laissé par la TOUTE FIN du frame PRÉCÉDENT (rendu du monde en 3D
 * perspective, orientation de caméra quelconque), pas celui, correctement
 * orthographique/éclairé-GUI, que vanilla configure lui-même juste avant SES
 * PROPRES drawItem — confirmé par trace bytecode complète du VRAI
 * GameRenderer.render() 26.1.2 : {@code getLighting().setupFor(Lighting.Entry.ITEMS_3D)}
 * est appelé PLUS LOIN dans cette même méthode, juste avant le flush GPU
 * (guiRenderer.render(GpuBufferSlice)), et RIEN entre les deux ne
 * réinitialise GuiRenderState (toujours confirmé aucun reset() dans cette
 * méthode). Décalé sur ce point précis (juste APRÈS cet appel, via
 * {@code shift = At.Shift.AFTER}) — noms réels directs, aucun refmap
 * nécessaire pour ce bracket (comme le reste de v26_1).
 *
 * NON VÉRIFIÉ EN JEU (pas d'accès à un client Minecraft depuis cet
 * environnement) — seule la timing HEAD précédente a été testée par
 * l'utilisateur (icônes visibles mais mal orientées/éclairées, cohérent avec
 * le diagnostic ci-dessus).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GuiFlushMixin261 {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/platform/Lighting;setupFor(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V",
                 shift = At.Shift.AFTER))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIcons(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin261: " + t);
        }
    }
}
