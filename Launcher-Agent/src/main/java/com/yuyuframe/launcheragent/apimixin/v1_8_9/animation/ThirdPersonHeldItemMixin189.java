package com.yuyuframe.launcheragent.apimixin.v1_8_9.animation;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.render.HeldItemGl189;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#THIRD_PERSON_HELD_ITEM_TRANSFORM} sur 1.8.9 — couche
 * « objet tenu » des entités, {@code feature.HeldItemRenderer.render}
 * ({@code bky.a(Lpr;FFFFFFF)V}).
 *
 * <p>Juste avant le dessin de l'objet ({@code bfn.a(Lpr;Lzx;Lbgr$b;)V},
 * offset 207 au javap, seul appel), la matrice est déjà posée sur la main :
 * les opérations du module s'y ajoutent. Entité en {@link Coerce} (type
 * obfusqué), transmise telle quelle aux points d'accès par le module.
 */
@Mixin(targets = "net.minecraft.client.render.entity.feature.HeldItemRenderer")
public abstract class ThirdPersonHeldItemMixin189 {

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem("
            + "Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/render/model/json/ModelTransformation$Mode;)V"),
        require = 0)
    private void la$beforeItem(@Coerce Object entity, float limbAngle, float limbDistance, float tickDelta,
                               float age, float headYaw, float headPitch, float scale, CallbackInfo ci) {
        try {
            Object ops = VanillaHookRegistry.dispatchValue(HookPoint.THIRD_PERSON_HELD_ITEM_TRANSFORM, entity);
            if (ops instanceof MatrixOps) HeldItemGl189.apply((MatrixOps) ops);
        } catch (Throwable t) {
            LauncherLog.err("[ThirdPersonHeldItemMixin189] " + t);
        }
    }
}
