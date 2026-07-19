package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Portage 1.21.11 de {@code MouseHandlerFreelookMixin261} — voir sa javadoc
 * pour l'architecture générale. Différences vérifiées par désassemblage du
 * vrai jar 1.21.11 (javap sur gfk.class, obf de {@code Mouse}) :
 *   - {@code Mouse} (Yarn) au lieu de {@code MouseHandler} (réel 26.1.2).
 *   - {@code updateMouse(D)V} (Yarn) au lieu de {@code turnPlayer(D)V} (réel).
 *   - {@code cursorDeltaX}/{@code cursorDeltaY} (Yarn) au lieu de {@code
 *     accumulatedDX}/{@code accumulatedDY} (réel) — mêmes rôles (champs
 *     privés accumulant le delta souris brut avant application au joueur),
 *     confirmés par leur position dans updateMouse (lus puis remis à 0.0
 *     juste avant l'appel final à {@code Entity.changeLookDirection(D,D)V}
 *     sur le champ {@code player}, obf {@code hnh}).
 *   - Bracket obfusqué (contrairement à 26.1.2, noms réels directs) : tout
 *     accès réflexif passe par {@link McReflect} (jamais {@code
 *     getDeclaredField} direct sur {@code this.getClass()}, qui échouerait
 *     silencieusement — le nom runtime réel est une lettre obfusquée, pas
 *     "cursorDeltaX").
 */
@Mixin(targets = "net.minecraft.client.Mouse")
public abstract class MouseHandlerFreelookMixin {

    private static volatile Field fCursorDeltaX, fCursorDeltaY;

    @Inject(method = "updateMouse(D)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$onUpdateMouse(double timeDelta, CallbackInfo ci) {
        try {
            if (!FreelookModule.isFreelookEngaged()) {
                FreelookModule.deactivate();
                return;
            }

            if (fCursorDeltaX == null) {
                fCursorDeltaX = McReflect.field(this.getClass(), "net/minecraft/client/Mouse", "cursorDeltaX");
                fCursorDeltaY = McReflect.field(this.getClass(), "net/minecraft/client/Mouse", "cursorDeltaY");
                if (fCursorDeltaX == null || fCursorDeltaY == null) return;
            }
            double dx = fCursorDeltaX.getDouble(this);
            double dy = fCursorDeltaY.getDouble(this);
            fCursorDeltaX.setDouble(this, 0.0);
            fCursorDeltaY.setDouble(this, 0.0);

            double sensitivity = readSensitivity();
            double factor = Math.pow(sensitivity * 0.6 + 0.2, 3.0) * 8.0;

            FreelookModule.accumulate(dx * factor, dy * factor);
            ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[MouseHandlerFreelookMixin] la$onUpdateMouse: " + t);
        }
    }

    /** Voir {@code MouseHandlerFreelookMixin261#readSensitivity} — même formule vanilla, résolution cross-bracket via McReflect. */
    private double readSensitivity() {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return 0.5;
            Field fOptions = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options");
            if (fOptions == null) return 0.5;
            Object options = fOptions.get(mc);
            if (options == null) return 0.5;
            Field fSensitivity = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "mouseSensitivity");
            if (fSensitivity == null) return 0.5;
            return McReflect.simpleOptionGetValue(fSensitivity.get(options));
        } catch (Throwable t) {
            return 0.5;
        }
    }
}
