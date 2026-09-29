package com.yuyuframe.launcheragent.apimixin.v26_1_1.keybind;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code OptionsMixin#loadHook} (fabric-key-mapping-api-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#KEYBIND_REGISTER} — HEAD de
 * {@code Options.load()}, avant que {@code keyMappings} ne soit figé pour la
 * session. Original Fabric y insère ses propres {@code KeyMapping} dans le
 * tableau ({@code KeyMappingRegistryImpl.process}) — non reproduit, ce hook
 * n'est ici qu'un signal de timing.
 */
@Mixin(targets = "net.minecraft.client.Options")
abstract class KeybindRegisterMixin2611 {

    @Inject(at = @At("HEAD"), method = "load()V")
    private void la$dispatchKeybindRegister(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBIND_REGISTER, null);
    }
}
