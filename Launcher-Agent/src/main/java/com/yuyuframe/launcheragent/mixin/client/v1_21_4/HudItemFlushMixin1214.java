package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BUG TROUVÉ ET CORRIGÉ (icônes Armure/Durabilité, 1.21.4 — voir la javadoc
 * complète de {@link UiRenderer#drawVanillaItemIconModernImmediate}) :
 * construire notre PROPRE {@code DrawContext} isolé (avec son propre
 * {@code VertexConsumerProvider.Immediate}) APRÈS que tout le rendu vanilla
 * de la frame ait déjà eu lieu (depuis {@code GlobalUiRenderMixin1214}, TAIL
 * de {@code GameRenderer.render()}) plaçait systématiquement notre dessin
 * hors du contexte GL/shader vivant que vanilla configure lui-même —
 * plusieurs contournements successifs (VAO, cache shader RenderSystem) n'y
 * ont rien changé.
 *
 * Correctif structurel : au lieu de dessiner isolément, ce Mixin hooke
 * directement {@code InGameHud.render(DrawContext, RenderTickCounter)} — le
 * MÊME point d'accroche que {@code HudRenderCallback} de Fabric API utilise
 * pour laisser les mods dessiner par-dessus le HUD — et transmet le VRAI
 * paramètre {@code DrawContext} de cet appel (l'instance vivante que vanilla
 * utilise pour tout son propre HUD cette frame, déjà dans le bon état GL) à
 * {@link UiRenderer#flushPendingImmediateItemIcons(Object)}. Les DEUX
 * paramètres capturés en {@code @Coerce Object} (jamais un type stub concret
 * — voir la leçon {@code ClearOverlaysMixin} dans module-bracket-audit.md) ;
 * le premier transmis tel quel, jamais casté, le second (RenderTickCounter)
 * inutilisé mais OBLIGATOIRE à déclarer.
 *
 * BUG TROUVÉ ET CORRIGÉ (test utilisateur, 1.21.4, "NoPumpkin et sûrement
 * d'autres modules ne fonctionnent plus") : première version de ce Mixin ne
 * capturait QUE le premier paramètre (`@Coerce Object context`), en
 * supposant qu'omettre le second (RenderTickCounter, inutilisé) suffisait —
 * `InvalidInjectionException: Expected (Lfof;Lfla;...)V but found
 * (Ljava/lang/Object;...)V`. Contrairement au cas usuel où omettre les
 * paramètres FINAUX inutilisés est accepté (voir {@code MixinCrosshair1214},
 * qui ne capture QUE `CallbackInfo`), Sponge exige ICI que TOUS les
 * paramètres cibles soient présents dès qu'au moins un est capturé avec
 * `@Coerce` sur cette méthode précise. Cette exception a fait échouer TOUT
 * le tissage de `InGameHud` pour cette passe (même mécanisme de cascade que
 * la leçon {@code ClearOverlaysMixin}) — cassant `MixinCrosshair1214` ET
 * `ClearOverlaysMixin1214` (donc `NoPumpkinOverlayModule`) au passage, bien
 * qu'eux-mêmes corrects. Corrigé en déclarant les DEUX paramètres.
 *
 * TAIL, pas HEAD : les icônes en attente doivent apparaître PAR-DESSUS ce que
 * vanilla dessine dans ce même appel (crosshair, hotbar...), pas en dessous.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudItemFlushMixin1214 {

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("TAIL"), require = 0)
    private void la$flushPendingItemIcons(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.get(this.getClass().getClassLoader()).flushPendingImmediateItemIcons(context);
        } catch (Throwable t) {
            LauncherLog.err("[HudItemFlushMixin1214] la$flushPendingItemIcons: " + t);
        }
    }
}
