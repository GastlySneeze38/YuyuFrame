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
 * Toggle Sprint — port de la technique RÉELLE de PolySprint (mod open source,
 * décompilé directement depuis PolySprint-1.8.9-forge-1.0.2.jar installé dans
 * l'instance du joueur pour vérifier, PAS depuis le code multi-version de leur
 * dépôt GitHub qui vise 1.21+) : {@code MixinEntityPlayerSP} redirige TOUT
 * appel à {@code KeyBinding.isKeyDown()} dans {@code onLivingUpdate()} vers
 * une fonction "shouldSetSprint" — même principe ici.
 *
 * Remplace l'ancienne approche (poll clavier LWJGL brut + réflexion à CHAQUE
 * FRAME RENDUE, donc des centaines de fois/seconde) : ce Redirect s'exécute
 * exactement à la cadence du vrai tick vanilla (0 poll séparé, 0 overhead de
 * réflexion en dehors de ces appels déjà existants) — "notre sprint prenait
 * trop de perf" corrigé à la racine plutôt qu'optimisé en surface.
 *
 * Bytecode RÉEL vérifié (javap sur le vrai .minecraft/versions/1.8.9/1.8.9.jar,
 * classe officielle "bew") : {@code ClientPlayerEntity.tickMovement()}
 * (lettre officielle "m") appelle DEUX FOIS {@code KeyBinding.isPressed()}
 * (lettre officielle "d" — PAS single-consumer, voir javadoc ci-dessous),
 * les deux fois sur {@code GameOptions.sprintKey} (lettre "ae") — aucune
 * autre touche vérifiée dans cette méthode, donc rediriger TOUS les appels
 * sans ordinal est sûr (mêmes garanties que le binaire PolySprint réel).
 *
 * IMPORTANT — correction d'une erreur de ce projet tout au long de la
 * session : {@code KeyBinding.isPressed()} (lettre "d", method_6619) N'EST
 * PAS la méthode "à un seul consommateur" — celle-ci décrémente
 * {@code timesPressed}, c'est {@code KeyBinding.wasPressed()} (lettre "f",
 * method_841). Vérifié en décompilant avb.class (KeyBinding) du vrai jar
 * vanilla : {@code d()} fait juste {@code return this.pressed;} (aucun effet
 * de bord), {@code f()} décrémente bien un compteur. isPressed() est donc
 * SÛRE à lire/rediriger ici, contrairement à ce qu'affirmaient les
 * commentaires précédents dans ce module (voir KeystrokesModule/
 * ToggleKeyModule, qui évitaient isPressed() par erreur).
 */
@Mixin(targets = "net.minecraft.entity.player.ClientPlayerEntity")
public abstract class MixinToggleSprint189 {

    private boolean la$prevDown;
    private boolean la$toggled;
    private static boolean la$diagLogged;
    private static boolean la$headDiagLogged;

    /** Diagnostic isolé : confirme si Mixin trouve ne serait-ce que la méthode "m()V" elle-même, indépendamment du @Redirect ci-dessous. */
    @Inject(method = "m()V", at = @At("HEAD"), require = 0)
    private void la$diagHead(CallbackInfo ci) {
        if (la$headDiagLogged) return;
        la$headDiagLogged = true;
        LauncherLog.info("[MixinToggleSprint189] HEAD de m()V ATTEINT");
    }

    // "m()V" — lettre officielle DIRECTE, pas le nom Yarn "tickMovement" : ce
    // nom n'est indexé dans mappings-1.8.9.tiny QUE sous la classe ancêtre
    // LivingEntity (où la méthode est déclarée à l'origine), jamais ré-indexé
    // pour l'override réel de ClientPlayerEntity — MappingsRegistry.getObfMethodName
    // échoue donc silencieusement pour CE scope de classe précis et retombe
    // sur le nom Yarn tel quel ("tickMovement", qui n'existe pas dans le
    // bytecode réel) → l'injecteur ne matchait JAMAIS rien (confirmé : le
    // diagnostic "redirect ATTEINT" ne s'affichait jamais en jeu). "m" est la
    // lettre RÉELLE vérifiée via javap sur bew.class (le vrai .minecraft/
    // versions/1.8.9/1.8.9.jar) — fiable ici car ce déploiement tourne en
    // Scheme.OFFICIAL (Forge, pas Fabric/intermediary, confirmé par les logs :
    // "fabric=false").
    // "Lavb;d()Z" — même raison que method="m()V" : la résolution du "target"
    // d'@At(INVOKE) passe par le même pipeline de remapping yarn→officiel que
    // "method", jamais exercé avant dans ce bootstrap (les Mixins existants
    // n'utilisent que @At("TAIL")/@At("HEAD"), jamais INVOKE) — diagnostic
    // HEAD confirmé atteint, mais le Redirect ne matchait toujours rien avec
    // le nom Yarn "Lnet/minecraft/client/option/KeyBinding;isPressed()Z".
    // "avb" = KeyBinding, "d" = isPressed, vérifiés tous les deux par javap
    // sur le vrai jar 1.8.9.
    @Redirect(method = "m()V",
        at = @At(value = "INVOKE", target = "Lavb;d()Z"),
        require = 0)
    private boolean la$toggleSprint(KeyBinding keyBinding) {
        try {
            if (!la$diagLogged) {
                la$diagLogged = true;
                LauncherLog.info("[MixinToggleSprint189] redirect ATTEINT, keyBinding=" + keyBinding);
            }
            boolean realDown = pressedField(keyBinding);

            LauncherModule module = ModuleRegistry.get("toggle-sprint");
            if (module == null || !module.isEnabled() || !isSprintKey(keyBinding)) return realDown;

            // Détection du front montant EXACTEMENT une fois par tick réel
            // (cet appel EST le tick, pas un poll séparé) — bascule sur
            // appui, comme un vrai bouton toggle.
            if (realDown && !la$prevDown) la$toggled = !la$toggled;
            la$prevDown = realDown;

            return la$toggled;
        } catch (Throwable t) {
            LauncherLog.err("[MixinToggleSprint189] la$toggleSprint: " + t);
            return pressedFieldSafe(keyBinding);
        }
    }

    private boolean isSprintKey(Object keyBinding) {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return false;
            Object sprintKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "sprintKey").get(options);
            return sprintKey == keyBinding;
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
