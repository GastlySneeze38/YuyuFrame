package com.yuyuframe.launcheragent.apimixin.v1_21_11.keybind;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link HookPoint#KEYBIND_CATEGORY_REGISTER} sur 1.21.11 — pendant de
 * {@code KeybindCategoryRegisterMixin261}.
 *
 * <pre>
 *   26.1.2 : KeyMapping$Category.register(Identifier)   (statique)
 *   1.21.11 : KeyBinding$Category.create(Identifier)     (statique, Mojang register)
 * </pre>
 *
 * Statique : handler statique lui aussi (voir la leçon de
 * {@code HudExtractArmorMixin261}). Surcharge {@code create(String)} écartée
 * par le descripteur.
 */
@Mixin(targets = "net.minecraft.client.option.KeyBinding$Category")
public abstract class KeybindCategoryRegisterMixin1211 {

    @Inject(method = "create(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/option/KeyBinding$Category;",
            at = @At("RETURN"), require = 0)
    private static void la$dispatchKeybindCategoryRegister(@Coerce Object id, CallbackInfoReturnable<Object> cir) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBIND_CATEGORY_REGISTER, id);
    }
}
