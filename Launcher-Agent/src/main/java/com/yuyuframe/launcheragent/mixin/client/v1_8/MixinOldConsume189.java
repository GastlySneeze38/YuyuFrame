package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.legacy17.OldConsumeModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Manger/Boire 1.7 — inspiré de TAKfsg/oldblockhit-legacy-fabric
 * ({@code Config.oldConsume}, repo GitHub, méthode {@code method_9867}
 * réécrite en {@code @Overwrite}).
 *
 * Cible {@code applyEatOrDrinkTransformation(AbstractClientPlayerEntity,F)V}
 * (lettre officielle "a", descripteur "(Lbet;F)V", confirmé method_9867 dans
 * mappings-1.8.9.tiny). @Inject cancellable en HEAD (équivalent de
 * @Overwrite ici, mais compatible avec d'autres Mixins ciblant la même
 * méthode) — remplace ENTIÈREMENT le calcul, comme les deux mods de
 * référence (contrairement à l'arc, additif).
 *
 * AUCUN paramètre de la cible capturé (le 1er, {@code Lbet;}, provoquerait la
 * même InvalidInjectionException que sur MixinOldItemRotations189) : le
 * joueur est retrouvé via réflexion ({@code MinecraftClient.player}, TOUJOURS
 * le même objet que le paramètre reçu en 1ère personne solo) et le tickDelta
 * via un champ statique capturé en HEAD de {@code renderArmHoldingItem(F)V}
 * (même {@code a(F)V} que MixinDiagonalSword189/MixinSwingWhileBlocking189)
 * — seul paramètre PRIMITIF, capturable sans souci.
 *
 * getItemUseTicks() = lettre "bR" sur wn/PlayerEntity (hérité par bet),
 * getMaxUseTime() = lettre "l" sur zx/ItemStack — tous deux confirmés dans
 * mappings-1.8.9.tiny. Formule identique à la référence Fabric
 * (Config.oldConsume) : easing en puissance 9 (f2^9) au lieu du power(h,27)
 * vanilla, translation/rotation à l'ancienne.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class MixinOldConsume189 {

    private static float la$capturedTickDelta;
    private static float la$capturedSwingProgress;

    @Inject(method = "a(F)V", at = @At("HEAD"), require = 0)
    private void la$captureTickDelta(float tickDelta, CallbackInfo ci) {
        la$capturedTickDelta = tickDelta;
        try {
            LauncherModule module = ModuleRegistry.get("old-consume");
            if (!(module instanceof OldConsumeModule) || !module.isEnabled()) return;
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;
            Method getHandSwingProgress = McReflect.method(player.getClass(), "net/minecraft/entity/LivingEntity", "getHandSwingProgress", float.class);
            if (getHandSwingProgress == null) return;
            la$capturedSwingProgress = (float) getHandSwingProgress.invoke(player, tickDelta);
        } catch (Throwable ignored) {}
    }

    // Même souci que "swing en bloquant"/"arc 1.7" (voir MixinSwingWhileBlocking189/
    // MixinOldBow189) : la branche MANGER/BOIRE de renderArmHoldingItem(F)V
    // appelle ELLE AUSSI applyEquipAndSwingOffset(equip, 0.0F) — 0.0F codé en
    // dur — avant de basculer sur le rendu manger/boire. C'est le 2ème appel
    // (ordinal=1) à b(FF)V dans cette méthode (après carte=0), confirmé en
    // recomptant les 4 occurrences de "b(f2, 0.0F)" dans le bytecode réel.
    @ModifyArg(method = "a(F)V", at = @At(value = "INVOKE", target = "Lbfn;b(FF)V", ordinal = 1), index = 1, require = 0)
    private float la$useRealSwingProgressWhileConsuming(float original) {
        try {
            LauncherModule module = ModuleRegistry.get("old-consume");
            if (module instanceof OldConsumeModule && module.isEnabled()) {
                return la$capturedSwingProgress;
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinOldConsume189] la$useRealSwingProgressWhileConsuming: " + t);
        }
        return original;
    }

    private static long la$lastDiag;

    @Inject(method = "a(Lbet;F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$oldConsumeAnimation(CallbackInfo ci) {
        boolean diag = System.currentTimeMillis() - la$lastDiag > 1000;
        if (diag) la$lastDiag = System.currentTimeMillis();
        try {
            if (diag) LauncherLog.info("[MixinOldConsume189] la$oldConsumeAnimation appelé");
            LauncherModule module = ModuleRegistry.get("old-consume");
            if (!(module instanceof OldConsumeModule) || !module.isEnabled()) {
                if (diag) LauncherLog.info("[MixinOldConsume189] module null/désactivé: " + module);
                return;
            }

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;

            Field mainHandField = McReflect.field(this.getClass(), "net/minecraft/client/render/item/HeldItemRenderer", "mainHand");
            if (mainHandField == null) return;
            Object itemStack = mainHandField.get(this);
            if (itemStack == null) return;

            Method getItemUseTicks = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/player/PlayerEntity", "getItemUseTicks");
            Method getMaxUseTime = McReflect.noArgMethod(itemStack.getClass(), "net/minecraft/item/ItemStack", "getMaxUseTime");
            if (getItemUseTicks == null || getMaxUseTime == null) {
                if (diag) LauncherLog.info("[MixinOldConsume189] getItemUseTicks/getMaxUseTime introuvable");
                return;
            }

            int itemUseTicks = (int) getItemUseTicks.invoke(player);
            int maxUseTime = (int) getMaxUseTime.invoke(itemStack);
            float tickDelta = la$capturedTickDelta;

            Class<?> mathHelper = McReflect.yarnClass("net/minecraft/util/math/MathHelper");
            if (mathHelper == null) return;
            Method cos = McReflect.method(mathHelper, "net/minecraft/util/math/MathHelper", "cos", float.class);
            Method abs = McReflect.method(mathHelper, "net/minecraft/util/math/MathHelper", "abs", float.class);
            Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
            if (cos == null || abs == null || glStateManager == null) return;
            Method translate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "translate",
                float.class, float.class, float.class);
            Method rotate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "rotate",
                float.class, float.class, float.class, float.class);
            if (translate == null || rotate == null) return;

            float useAmount = (float) itemUseTicks - tickDelta + 1.0f;
            float f1 = 1.0f - useAmount / (float) maxUseTime;
            float f2 = 1.0f - f1;
            f2 = f2 * f2 * f2;
            f2 = f2 * f2 * f2;
            f2 = f2 * f2 * f2; // f2^9 — même "easing" que la référence Fabric (Config.oldConsume)
            float f3 = 1.0f - f2;

            float cosVal = (float) cos.invoke(null, (useAmount / 4.0f) * (float) Math.PI);
            float bob = (float) abs.invoke(null, cosVal * 0.1f) * (f1 > 0.2f ? 1f : 0f);
            translate.invoke(null, 0.0f, bob, 0.0f);
            translate.invoke(null, f3 * 0.6f, -f3 * 0.5f, 0.0f);
            rotate.invoke(null, f3 * 90.0f, 0.0f, 1.0f, 0.0f);
            rotate.invoke(null, f3 * 10.0f, 1.0f, 0.0f, 0.0f);
            rotate.invoke(null, f3 * 30.0f, 0.0f, 0.0f, 1.0f);

            if (diag) LauncherLog.info("[MixinOldConsume189] transform appliqué, itemUseTicks=" + itemUseTicks + " maxUseTime=" + maxUseTime);
            ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[MixinOldConsume189] la$oldConsumeAnimation: " + t);
        }
    }
}
