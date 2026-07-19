package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Voir {@code FreelookModule} pour l'architecture complète — ce Mixin
 * annule {@code MouseHandler.turnPlayer(D)V} (appelée une fois par frame
 * depuis {@code handleAccumulatedMovement()}, confirmé par désassemblage
 * bytecode du vrai jar 26.1.2 : dernier appel de la méthode est
 * {@code LocalPlayer.turn(D,D)}, qui tournerait RÉELLEMENT le joueur) quand
 * le freelook est engagé (maintenu OU basculé, voir FreelookModule.mode) —
 * {@code accumulatedDX}/{@code
 * accumulatedDY} (champs privés) sont lus/vidés ICI à la place, la même
 * courbe de sensibilité vanilla leur est appliquée manuellement (voir
 * javadoc de FreelookModule), et le résultat est détourné vers
 * {@link FreelookModule#accumulate} au lieu du joueur.
 *
 * {@code MouseHandler} est une classe UTILITAIRE non sous-classée (comme
 * GameRenderer/TitleScreen/ChatListener, déjà ciblés sans souci ailleurs
 * dans ce projet) — PAS un risque de hiérarchie comme Screen (voir
 * historique de session, ShulkerPreviewModule).
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
public abstract class MouseHandlerFreelookMixin261 {

    private static Field fAccumulatedDX, fAccumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void la$onTurnPlayer(CallbackInfo ci) {
        try {
            if (!FreelookModule.isFreelookEngaged()) {
                FreelookModule.deactivate();
                return;
            }

            if (fAccumulatedDX == null) {
                fAccumulatedDX = this.getClass().getDeclaredField("accumulatedDX");
                fAccumulatedDX.setAccessible(true);
                fAccumulatedDY = this.getClass().getDeclaredField("accumulatedDY");
                fAccumulatedDY.setAccessible(true);
            }
            double dx = fAccumulatedDX.getDouble(this);
            double dy = fAccumulatedDY.getDouble(this);
            fAccumulatedDX.setDouble(this, 0.0);
            fAccumulatedDY.setDouble(this, 0.0);

            double sensitivity = readSensitivity();
            double factor = Math.pow(sensitivity * 0.6 + 0.2, 3.0) * 8.0;

            FreelookModule.accumulate(dx * factor, dy * factor);
            ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[MouseHandlerFreelookMixin261] la$onTurnPlayer: " + t);
        }
    }

    /**
     * {@code Options.sensitivity()} (réel {@code OptionInstance<Double>},
     * même mécanisme que {@code fov}/{@code sensitivity} déjà lus par
     * ZoomModule via {@link McReflect#simpleOptionGetValue}) — 0.5 (valeur
     * vanilla par défaut) en repli si la résolution échoue, jamais une
     * exception qui casserait tout mouvement de souris.
     */
    private double readSensitivity() {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return 0.5;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return 0.5;
            Field field = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "mouseSensitivity", "sensitivity");
            if (field == null) return 0.5;
            return McReflect.simpleOptionGetValue(field.get(options));
        } catch (Throwable t) {
            return 0.5;
        }
    }
}
