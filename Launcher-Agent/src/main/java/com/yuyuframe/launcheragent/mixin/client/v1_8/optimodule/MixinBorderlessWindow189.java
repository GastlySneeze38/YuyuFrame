package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.optimodule.BorderlessWindowNative;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Cible : net.minecraft.client.MinecraftClient (official ave), toggleFullscreen()
 * (official q()V — mappings-1.8.9.tiny : method_2953). C'est la MÊME méthode
 * que la touche F11 ET le bouton "Plein écran" des options vidéo appellent
 * tous les deux (un seul point d'entrée vanilla) — l'intercepter ici couvre
 * donc les deux, pas seulement F11.
 *
 * Si le module est actif : annule le comportement vanilla (qui appellerait
 * Display.setFullscreen(), plein écran EXCLUSIF avec changement de mode
 * vidéo — source de l'instabilité alt-tab remontée) et bascule nous-mêmes
 * options.fullscreen (pour rester cohérent avec tout autre code vanilla qui
 * lit ce champ) PUIS applique/retire le mode sans bordure via
 * BorderlessWindowNative — qui ne touche QUE le style/la position de la
 * fenêtre Win32 réelle, jamais le contexte OpenGL (aucune perte de texture,
 * contrairement à un Display.destroy()/create()).
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class MixinBorderlessWindow189 {

    @Inject(method = "q()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$toggleFullscreen(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("borderless-window");
            if (module == null || !module.isEnabled()) return;

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;
            Field fullscreenField = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "fullscreen");
            if (fullscreenField == null) return;

            boolean nowFullscreen = !fullscreenField.getBoolean(options);
            fullscreenField.setBoolean(options, nowFullscreen);

            if (nowFullscreen) BorderlessWindowNative.enterBorderless();
            else BorderlessWindowNative.exitBorderless();

            ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[MixinBorderlessWindow189] la$toggleFullscreen: " + t);
        }
    }
}
