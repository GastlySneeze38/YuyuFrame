package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.visual.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Portage 1.21.4 de {@code MouseHandlerFreelookMixin} (1.21.11) — architecture
 * IDENTIQUE vérifiée par mappings Yarn 1.21.4 : {@code Mouse.updateMouse(D)V}
 * (method_1606, même ID intermediary), champs {@code client}/{@code
 * cursorDeltaX}/{@code cursorDeltaY} tous présents avec les mêmes noms Yarn.
 * Voir {@code MouseHandlerFreelookMixin} (1.21.11) pour l'architecture
 * complète — repris tel quel, seuls le package et le nom de classe changent.
 */
@Mixin(targets = "net.minecraft.client.Mouse")
public abstract class MouseHandlerFreelookMixin1214 {

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
            LauncherLog.err("[MouseHandlerFreelookMixin1214] la$onUpdateMouse: " + t);
        }
    }

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
