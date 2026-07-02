package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Toggle Sneak — même technique que {@link MixinToggleSprint189} (voir sa
 * javadoc pour le contexte PolySprint complet), appliquée à
 * {@code KeyboardInput.tick()} plutôt que {@code ClientPlayerEntity.tickMovement()}.
 *
 * Bytecode RÉEL vérifié (javap sur bev.class = KeyboardInput, extrait du vrai
 * .minecraft/versions/1.8.9/1.8.9.jar) : sa méthode {@code tick()} (lettre
 * officielle "a") appelle {@code KeyBinding.isPressed()} SIX fois — une par
 * touche de mouvement (avant/arrière/gauche/droite/saut/SNEAK, dans cet
 * ordre), chacune sur un champ DIFFÉRENT de GameOptions. Rediriger TOUS ces
 * appels sans ordinal est sûr : {@link #isSneakKey} ne change le résultat que
 * pour {@code GameOptions.sneakKey} (lettre "ad") — les 5 autres retombent
 * sur {@code realDown}, comportement vanilla inchangé.
 *
 * C'est CE point précis (KeyboardInput.tick() recalculant l'état à chaque
 * tick, indépendamment de Entity.setSneaking()) qui expliquait pourquoi le
 * forçage précédent (juste appeler setSneaking(true) depuis une boucle de
 * rendu séparée) perdait systématiquement la course — voir l'historique de
 * ToggleKeyModule, remplacé par cette approche.
 */
@Mixin(targets = "net.minecraft.client.input.KeyboardInput")
public abstract class MixinToggleSneak189 {

    private boolean la$prevDown;
    private boolean la$toggled;
    private static boolean la$diagLogged;
    private static boolean la$headDiagLogged;

    /** Diagnostic isolé : confirme si Mixin trouve ne serait-ce que la méthode "a()V" elle-même, indépendamment du @Redirect ci-dessous. */
    @Inject(method = "a()V", at = @At("HEAD"), require = 0)
    private void la$diagHead(CallbackInfo ci) {
        if (la$headDiagLogged) return;
        la$headDiagLogged = true;
        LauncherLog.info("[MixinToggleSneak189] HEAD de a()V ATTEINT");
    }

    // "a()V" — lettre officielle DIRECTE, même raison que MixinToggleSprint189 :
    // le nom Yarn "tick" n'est indexé QUE sous la classe de base Input, jamais
    // pour l'override réel de KeyboardInput — la résolution par nom échouait
    // silencieusement. "a" vérifié via javap sur bev.class (vrai jar 1.8.9).
    // "Lavb;d()Z" — même correctif que MixinToggleSprint189 : lettres
    // officielles directes pour le target d'@At(INVOKE), jamais résolu
    // correctement via le nom Yarn dans ce bootstrap.
    @Redirect(method = "a()V",
        at = @At(value = "INVOKE", target = "Lavb;d()Z"),
        require = 0)
    private boolean la$toggleSneak(KeyBinding keyBinding) {
        try {
            if (!la$diagLogged) {
                la$diagLogged = true;
                LauncherLog.info("[MixinToggleSneak189] redirect ATTEINT, keyBinding=" + keyBinding);
            }
            boolean realDown = pressedField(keyBinding);

            LauncherModule module = ModuleRegistry.get("toggle-sneak");
            if (module == null || !module.isEnabled() || !isSneakKey(keyBinding)) return realDown;

            if (realDown && !la$prevDown) la$toggled = !la$toggled;
            la$prevDown = realDown;

            return la$toggled;
        } catch (Throwable t) {
            LauncherLog.err("[MixinToggleSneak189] la$toggleSneak: " + t);
            return pressedFieldSafe(keyBinding);
        }
    }

    private boolean isSneakKey(Object keyBinding) {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return false;
            Object sneakKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "sneakKey").get(options);
            return sneakKey == keyBinding;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean pressedField(Object keyBinding) throws Exception {
        Field f = McReflect.field(keyBinding.getClass(), "net/minecraft/client/option/KeyBinding", "pressed");
        return f != null && f.getBoolean(keyBinding);
    }

    private boolean pressedFieldSafe(Object keyBinding) {
        try { return pressedField(keyBinding); } catch (Throwable t) { return false; }
    }
}
