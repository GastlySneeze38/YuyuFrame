package com.yuyuframe.launcheragent.apimixin.v1_21_11.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CLIENT_LEVEL_LOAD} sur 1.21.11 — pendant de {@code ClientLevelLoadMixin261}.
 *
 * <pre>
 *   26.1.2 : Minecraft.updateLevelInEngines(ClientLevel, boolean), TAIL
 *   1.21.11 : MinecraftClient.setWorld(ClientWorld, boolean), TAIL
 * </pre>
 *
 * Même méthode Mojang dans les deux versions (officiel 1.21.11 {@code gfj.a(hif,Z)}).
 * ⚠️ Ne pas confondre avec {@code setWorld(ClientWorld)} ni {@code joinWorld} :
 * le descripteur à deux paramètres vise la bonne.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ClientLevelLoadMixin1211 {

    @Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;Z)V", at = @At("TAIL"), require = 0)
    private void la$dispatchClientLevelLoad(@Coerce Object world, boolean stopSound, CallbackInfo ci) {
        if (world != null) {
            VanillaHookRegistry.dispatch(HookPoint.CLIENT_LEVEL_LOAD, world);
        }
    }
}
