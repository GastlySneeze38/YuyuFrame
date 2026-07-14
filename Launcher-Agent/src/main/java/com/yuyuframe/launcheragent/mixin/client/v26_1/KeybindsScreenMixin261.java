package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.screen.CustomKeybindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 (voir {@code KeybindsScreenMixin}, javadoc de
 * tête pour le pourquoi complet du point d'injection en TAIL du
 * constructeur) pour MC 26.1+ :
 * {@code net.minecraft.client.gui.screen.option.KeybindsScreen} devient
 * {@code net.minecraft.client.gui.screens.options.controls.KeyBindsScreen}
 * (renommage ET changement de package plus profond — "screen"→"screens",
 * "KeybindsScreen"→"KeyBindsScreen" avec un B majuscule, dossier
 * "options/controls/" ajouté) ; {@code GameOptions} devient {@code Options}.
 * Constructeur vérifié via {@code javap} sur le jar client 26.1.2 réel :
 * {@code public KeyBindsScreen(Screen, Options);}.
 *
 * N'utilise PAS {@code ScreenHelper.getMc}/{@code getField}/{@code navigate}
 * (contrairement à l'original) : ces méthodes portent en dur le nom
 * obfusqué 1.21.11 {@code "gfj"} (voir {@code ScreenHelper.CLS_MINECRAFT_CLIENT})
 * spécifique à CE bracket — utilise {@link GlobalUiRenderBridge261} à la
 * place, qui porte les vrais noms Mojang vérifiés pour 26.1+.
 *
 * Vérifié en jeu (26.1.2).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.options.controls.KeyBindsScreen")
public abstract class KeybindsScreenMixin261 {

    @Inject(
        method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Options;)V",
        at = @At("TAIL")
    )
    private void la$onInit(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge261.getMcInstance();
            Object parentScreen = mc != null ? GlobalUiRenderBridge261.getCurrentScreen(mc) : null;

            LauncherLog.ui(3, "[LauncherAgent] KeybindsScreenMixin261: remplacement par CustomKeybindsScreen (parent="
                + parentScreen + ")");
            if (mc != null) GlobalUiRenderBridge261.setScreen(mc, new CustomKeybindsScreen(parentScreen));
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] KeybindsScreenMixin261 onInit: " + t);
            t.printStackTrace(System.err);
        }
    }
}
