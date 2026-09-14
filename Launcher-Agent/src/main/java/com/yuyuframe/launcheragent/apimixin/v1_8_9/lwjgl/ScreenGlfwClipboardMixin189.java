package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.yuyuframe.launcheragent.lwjgl2compat.LegacyLwjgl3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;

/**
 * Presse-papier et ouverture de liens sans AWT : la 1.8.9 passe par
 * {@code Toolkit.getSystemClipboard()} et {@code java.awt.Desktop}, qui
 * cohabitent mal avec une fenêtre GLFW (blocages, et rien du tout sous macOS).
 * On passe par GLFW et par la commande système, via {@link LegacyLwjgl3}.
 *
 * <p>{@code @Inject} HEAD annulable plutôt que l'{@code @Overwrite} de
 * legacy-lwjgl3 ({@code MixinScreenFixClipboard} / {@code MixinScreenFixOpenLink},
 * moehreag, LGPL-2.1) : même effet, sans remplacer le corps vanilla.
 * Méthodes vérifiées dans le tiny 1.8.9 : {@code getClipboard()} et
 * {@code setClipboard(String)} statiques, {@code openLink(URI)}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class ScreenGlfwClipboardMixin189 {

	@Inject(method = "getClipboard()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private static void la$getClipboard(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(LegacyLwjgl3.getClipboard());
	}

	@Inject(method = "setClipboard(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
	private static void la$setClipboard(String text, CallbackInfo ci) {
		LegacyLwjgl3.setClipboard(text);
		ci.cancel();
	}

	@Inject(method = "openLink(Ljava/net/URI;)V", at = @At("HEAD"), cancellable = true)
	private void la$openLink(URI uri, CallbackInfo ci) {
		LegacyLwjgl3.openLink(uri.toString());
		ci.cancel();
	}
}
