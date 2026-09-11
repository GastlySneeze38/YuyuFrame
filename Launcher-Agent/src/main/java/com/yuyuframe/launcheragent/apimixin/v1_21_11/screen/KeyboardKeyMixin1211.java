package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#KEYBOARD_KEY} sur 1.21.11 — pendant de {@code KeyboardKeyMixin261}.
 *
 * <pre>
 *   26.1.2 : KeyboardHandler.keyPress(long, int, KeyEvent) → Screen.keyPressed(KeyEvent)
 *   1.21.11 : Keyboard.onKey(long, int, KeyInput)          → Screen.keyPressed(KeyInput)
 * </pre>
 *
 * <p><b>Forme différente, même moment.</b> 26.1.2 enveloppe l'appel
 * ({@code @WrapOperation}) pour lire l'événement. Ici l'événement est déjà un
 * paramètre de {@code onKey} : un {@code @Inject} juste AVANT l'appel le
 * capture, sans receveur typé ni MixinExtras. L'appel n'a lieu que sur un
 * écran ouvert (c'est lui le receveur), comme le {@code screen != null} de
 * 26.1.2. Vérifié javap : un seul {@code gsb.a(Lgzb;)Z} dans {@code gfi.a(J,I,gzb)}.
 *
 * <p>La cible {@code keyPressed} n'est déclarée dans Yarn que sur
 * {@code Element} : c'est le repli « méthode héritée » de
 * {@code MappingsRegistry.mapMethodName} qui la traduit.
 */
@Mixin(targets = "net.minecraft.client.Keyboard")
public abstract class KeyboardKeyMixin1211 {

    @Inject(method = "onKey(JILnet/minecraft/client/input/KeyInput;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;keyPressed(Lnet/minecraft/client/input/KeyInput;)Z"),
            require = 0)
    private void la$dispatchKeyPress(long window, int action, @Coerce Object input, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBOARD_KEY, input);
    }
}
