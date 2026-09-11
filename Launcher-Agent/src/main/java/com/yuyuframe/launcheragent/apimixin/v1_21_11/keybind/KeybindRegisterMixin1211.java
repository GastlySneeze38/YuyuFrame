package com.yuyuframe.launcheragent.apimixin.v1_21_11.keybind;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#KEYBIND_REGISTER} sur 1.21.11 — pendant de
 * {@code KeybindRegisterMixin261} : {@code GameOptions.load()V} (Mojang
 * {@code Options.load}), HEAD — avant la lecture de {@code options.txt}, pour
 * que les touches ajoutées y soient relues.
 */
@Mixin(targets = "net.minecraft.client.option.GameOptions")
public abstract class KeybindRegisterMixin1211 {

    @Inject(method = "load()V", at = @At("HEAD"), require = 0)
    private void la$dispatchKeybindRegister(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBIND_REGISTER, null);
    }
}
