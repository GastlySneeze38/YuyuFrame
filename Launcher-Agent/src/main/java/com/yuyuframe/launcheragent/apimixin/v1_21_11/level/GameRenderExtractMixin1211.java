package com.yuyuframe.launcheragent.apimixin.v1_21_11.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#GAME_RENDER_EXTRACT} sur 1.21.11 — APPROCHANT de
 * {@code GameRenderExtractMixin261}, pas un pendant exact.
 *
 * <p>26.1.2 sépare l'extraction ({@code GameRenderer.extract(DeltaTracker, boolean)})
 * du rendu ; 1.21.11 n'a pas encore cette séparation, {@code GameRenderer.extract}
 * n'existe pas. Le point le plus proche est le HEAD de
 * {@code GameRenderer.render(RenderTickCounter, boolean)} (Mojang
 * {@code render(DeltaTracker, boolean)}) : même moment dans la frame (avant
 * tout le monde et le HUD), même paramètre transmis. Aucun module ne réclame
 * ce HookPoint aujourd'hui.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GameRenderExtractMixin1211 {

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"), require = 0)
    private void la$dispatchGameRenderExtract(@Coerce Object tickCounter, boolean tick, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.GAME_RENDER_EXTRACT, tickCounter);
    }
}
