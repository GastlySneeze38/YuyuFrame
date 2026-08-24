package com.yuyuframe.launcheragent.apimixin.v26_1.keybind;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Porte {@code KeyMappingCategoryMixin#onReturnRegister} (fabric-key-mapping-api-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#KEYBIND_CATEGORY_REGISTER} —
 * méthode STATIQUE, {@code targets} pointe la classe imbriquée
 * {@code KeyMapping$Category}. Original Fabric re-trie {@code SORT_ORDER}
 * après chaque enregistrement — non reproduit, signal de timing seul.
 */
@Mixin(targets = "net.minecraft.client.KeyMapping$Category")
abstract class KeybindCategoryRegisterMixin261 {

    @Inject(method = "register(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/KeyMapping$Category;", at = @At("RETURN"))
    private static void la$dispatchKeybindCategoryRegister(Identifier id, CallbackInfoReturnable<KeyMapping.Category> cir) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBIND_CATEGORY_REGISTER, id);
    }
}
