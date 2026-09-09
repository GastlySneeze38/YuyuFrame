package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.legacy17.DiagonalSwordModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Épée en diagonale — même famille de fonctionnalité qu'OverflowAnimations/
 * Animatium-Legacy ({@code itemRotationRoll}, voir MixinSwingSpeed189 pour le
 * contexte du repo), mais réduite à un seul axe/cas d'usage précis (demande
 * explicite : juste l'épée, pas un réglage de position global par item).
 *
 * CORRECTIF (premier essai en TAIL de {@code applyEquipAndSwingOffset}/b(FF)V
 * ne se voyait pas du tout) : vérifié sur le VRAI code source d'Animatium-
 * Legacy (Polyfrost/OverflowAnimationsV2, MixinItemRenderer.java sur GitHub)
 * — ils n'injectent PAS dans transformFirstPersonItem, mais juste AVANT
 * l'appel final à {@code renderItem(...)}, dans {@code renderItemInFirstPerson}
 * lui-même. Raison confirmée par javap sur bfn.class = HeldItemRenderer :
 * OpenGL applique les transforms en ordre INVERSE de leur appel (le DERNIER
 * appelé est le PLUS INTERNE, appliqué au maillage AVANT tout le reste) — une
 * rotation ajoutée en TAIL de b(FF)V devient donc la transform la PLUS
 * INTERNE de toutes, réabsorbée/réorientée par les rotations vanilla
 * ultérieures (45°, courbe de swing...), quasi invisible au final. En
 * s'accrochant ici — juste AVANT l'appel à
 * {@code renderItem(LivingEntity, ItemStack, TransformType)} (lettre "a",
 * descripteur "(Lpr;Lzx;Lbgr$b;)V", confirmé dans mappings-1.8.9.tiny ET par
 * javap, tous les chemins de renderItemInFirstPerson convergent vers CET
 * appel juste avant le rendu) — notre rotation devient la transform la PLUS
 * EXTERNE, appliquée en dernier juste avant le dessin réel : un vrai tilt
 * visible à l'écran, peu importe ce que vanilla a fait avant.
 *
 * Gatée sur {@code ItemStack.getItem() instanceof SwordItem} (lettre "aay",
 * confirmée dans mappings-1.8.9.tiny) via le champ {@code HeldItemRenderer.d}
 * (mainHand, type ItemStack) — pas appliquée aux autres items tenus.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class MixinDiagonalSword189 {

    private static long la$lastDiag;

    @Inject(method = "a(F)V", at = @At(value = "INVOKE", target = "Lbfn;a(Lpr;Lzx;Lbgr$b;)V"), require = 0)
    private void la$diagonalTilt(float tickDelta, CallbackInfo ci) {
        boolean diag = System.currentTimeMillis() - la$lastDiag > 2000;
        if (diag) la$lastDiag = System.currentTimeMillis();
        try {
            if (diag) LauncherLog.info("[MixinDiagonalSword189] la$diagonalTilt appelé");

            LauncherModule moduleBase = ModuleRegistry.get("diagonal-sword");
            if (!(moduleBase instanceof DiagonalSwordModule) || !moduleBase.isEnabled()) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] module null/désactivé: " + moduleBase);
                return;
            }
            DiagonalSwordModule module = (DiagonalSwordModule) moduleBase;

            Field mainHandField = McReflect.field(this.getClass(), "net/minecraft/client/render/item/HeldItemRenderer", "mainHand");
            if (mainHandField == null) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] mainHandField introuvable");
                return;
            }
            Object itemStack = mainHandField.get(this);
            if (itemStack == null) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] itemStack null (rien en main)");
                return;
            }

            Method getItem = McReflect.noArgMethod(itemStack.getClass(), "net/minecraft/item/ItemStack", "getItem");
            if (getItem == null) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] getItem introuvable");
                return;
            }
            Object item = getItem.invoke(itemStack);
            if (item == null) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] item null");
                return;
            }

            Class<?> swordItemClass = McReflect.yarnClass("net/minecraft/item/SwordItem");
            if (swordItemClass == null || !swordItemClass.isInstance(item)) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] pas une épée: item=" + item.getClass()
                    + " swordItemClass=" + swordItemClass);
                return;
            }

            Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
            if (glStateManager == null) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] GlStateManager introuvable");
                return;
            }
            Method rotate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "rotate",
                float.class, float.class, float.class, float.class);
            Method translate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "translate",
                float.class, float.class, float.class);
            if (rotate == null || translate == null) {
                if (diag) LauncherLog.info("[MixinDiagonalSword189] rotate/translate introuvable");
                return;
            }
            // Décalage AVANT la rotation : recentre le pivot pour que l'épée
            // tourne autour d'un point proche de son centre visuel au lieu de
            // son coin — sans ça, une rotation Z pure paraît "bizarre" (l'épée
            // semble sortir de l'écran ou pivoter depuis le mauvais endroit).
            translate.invoke(null, module.offsetX, module.offsetY, 0f);
            rotate.invoke(null, module.angle, 0f, 0f, 1f);
            if (diag) LauncherLog.info("[MixinDiagonalSword189] rotate appliqué, angle=" + module.angle
                + " offsetX=" + module.offsetX + " offsetY=" + module.offsetY);
        } catch (Throwable t) {
            LauncherLog.err("[MixinDiagonalSword189] la$diagonalTilt: " + t);
        }
    }
}
