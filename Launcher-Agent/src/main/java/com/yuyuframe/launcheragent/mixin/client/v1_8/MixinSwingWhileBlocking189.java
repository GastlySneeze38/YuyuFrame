package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.SwingWhileBlockingModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Swing en bloquant — inspiré de TAKfsg/oldblockhit-legacy-fabric (mod
 * Fabric/Yarn pour 1.8.9, repo GitHub, {@code HeldItemRendererMixin.java}) —
 * PREMIÈRE référence de ce genre trouvée cette session tournant nativement
 * sur Fabric/Yarn (donc IDs "intermediary" method_XXXX directement
 * vérifiables dans mappings-1.8.9.tiny, sans traduction MCP↔Yarn) : confirme
 * les lettres officielles déjà établies (method_9873 = b, method_9877 = d).
 *
 * DEUX correctifs distincts, cumulés :
 *
 * 1) "useSwing" (Fabric: {@code HeldItemRendererMixin.useSwing}) — quand
 *    attaque + usage sont maintenus ensemble ET que la cible est un BLOC,
 *    vanilla ne relance JAMAIS l'animation de swing (le clic gauche est
 *    absorbé par le blocage). On relance nous-mêmes le swing (même logique
 *    "redémarrage à mi-course" que {@code bw()}/swingHand, voir
 *    MixinSwingSpeed189) en injectant en HEAD de
 *    {@code applyEquipAndSwingOffset(FF)V} (lettre "b", confirmé
 *    method_9873) — appelé à CHAQUE frame en 1ère personne, donc fiable pour
 *    détecter la combinaison de touches en continu.
 *
 * 2) "enableBlockHits" (Fabric: {@code @ModifyConstant} sur une constante
 *    0.0F) — bytecode RÉEL vérifié (javap sur bfn.class = HeldItemRenderer) :
 *    dans {@code renderArmHoldingItem(F)V} (lettre "a", method_1354), la
 *    branche "épée en train de bloquer" appelle
 *    {@code b(equipProgress, 0.0F)} — un 0.0F CODÉ EN DUR au lieu de la
 *    vraie swingProgress — donc même si le swing tourne bien côté logique
 *    (point 1), l'animation RENDUE reste toujours figée à 0% pendant le
 *    blocage. C'est le 3ème appel à b(FF)V dans cette méthode (ordinal=2,
 *    immédiatement suivi de l'appel à applySwordBlockTransformation/d()V —
 *    confirmé en comptant les 4 occurrences de "b(f2, 0.0F)" dans le
 *    bytecode réel, une par branche du tableswitch carte/outil/épée-bloc/arc).
 *    {@code @ModifyArg} sur un {@code float} PRIMITIF (pas une référence
 *    typée MC comme KeyBinding/Entity) — contrairement à {@code @Redirect},
 *    aucun problème de correspondance de type de classe obfusquée à
 *    remapper, donc pas concerné par le bug qui a forcé l'abandon de
 *    @Redirect pour sprint/sneak.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class MixinSwingWhileBlocking189 {

    private static float la$capturedSwingProgress;

    @Inject(method = "a(F)V", at = @At("HEAD"), require = 0)
    private void la$captureSwingProgress(float tickDelta, CallbackInfo ci) {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;
            Method getHandSwingProgress = McReflect.method(player.getClass(), "net/minecraft/entity/LivingEntity", "getHandSwingProgress", float.class);
            if (getHandSwingProgress == null) return;
            la$capturedSwingProgress = (float) getHandSwingProgress.invoke(player, tickDelta);
        } catch (Throwable t) {
            LauncherLog.err("[MixinSwingWhileBlocking189] la$captureSwingProgress: " + t);
        }
    }

    private static long la$lastDiag;

    @ModifyArg(method = "a(F)V", at = @At(value = "INVOKE", target = "Lbfn;b(FF)V", ordinal = 2), index = 1, require = 0)
    private float la$useRealSwingProgressWhileBlocking(float original) {
        boolean diag = System.currentTimeMillis() - la$lastDiag > 2000;
        try {
            if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] la$useRealSwingProgressWhileBlocking appelé, original=" + original + " captured=" + la$capturedSwingProgress);
            LauncherModule module = ModuleRegistry.get("swing-while-blocking");
            if (module instanceof SwingWhileBlockingModule && module.isEnabled()) {
                return la$capturedSwingProgress;
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinSwingWhileBlocking189] la$useRealSwingProgressWhileBlocking: " + t);
        }
        return original;
    }

    // Front montant UNIQUEMENT (pas à chaque frame tant que les touches
    // restent tenues) : la version précédente réappliquait handSwingTicks=-1/
    // handSwinging=true en continu, plusieurs centaines de fois par seconde
    // tant qu'attaque+usage+bloc restaient vrais ensemble — retour
    // utilisateur : plus possible de casser AUCUN bloc, ni en survie ni en
    // créatif (que "swing-while-blocking" soit ou non la cause exacte, ce
    // spam d'écriture continue dans l'état de swing du joueur était le seul
    // comportement de cette session qui écrivait en boucle plutôt qu'une
    // fois par appui — corrigé par précaution, même principe que les autres
    // mixins de bascule du projet, voir MixinToggleSprint189).
    private static boolean la$prevBothDown;

    @Inject(method = "b(FF)V", at = @At("HEAD"), require = 0)
    private void la$restartSwingWhileBlocking(float equipProgress, float swingProgress, CallbackInfo ci) {
        boolean diag = System.currentTimeMillis() - la$lastDiag > 2000;
        if (diag) la$lastDiag = System.currentTimeMillis();
        try {
            LauncherModule module = ModuleRegistry.get("swing-while-blocking");
            if (!(module instanceof SwingWhileBlockingModule) || !module.isEnabled()) {
                if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] module null/désactivé: " + module);
                la$prevBothDown = false;
                return;
            }

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;
            Object attackKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "attackKey").get(options);
            Object useKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "useKey").get(options);
            if (attackKey == null || useKey == null) {
                if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] attackKey/useKey introuvable");
                return;
            }

            Field pressedField = McReflect.field(attackKey.getClass(), "net/minecraft/client/option/KeyBinding", "pressed");
            if (pressedField == null) return;
            boolean attackDown = pressedField.getBoolean(attackKey);
            boolean useDown = pressedField.getBoolean(useKey);
            if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] attackDown=" + attackDown + " useDown=" + useDown);
            if (!attackDown || !useDown) {
                la$prevBothDown = false;
                return;
            }

            Object result = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "result").get(mc);
            if (result == null) {
                if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] result null");
                la$prevBothDown = false;
                return;
            }
            Object type = McReflect.field(result.getClass(), "net/minecraft/util/hit/BlockHitResult", "type").get(result);
            Class<?> typeEnum = McReflect.yarnClass("net/minecraft/util/hit/BlockHitResult$Type");
            if (typeEnum == null) {
                if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] typeEnum introuvable");
                return;
            }
            Field blockConstantField = McReflect.field(typeEnum, "net/minecraft/util/hit/BlockHitResult$Type", "BLOCK");
            if (blockConstantField == null) {
                if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] blockConstantField introuvable");
                return;
            }
            Object blockConstant = blockConstantField.get(null);
            if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] result.getClass()=" + result.getClass()
                + " type=" + type + " blockConstant=" + blockConstant);
            if (type != blockConstant) {
                la$prevBothDown = false;
                return;
            }

            // Front montant : seulement si on n'était PAS déjà dans cet état
            // au dernier appel.
            if (la$prevBothDown) return;
            la$prevBothDown = true;

            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;

            Field handSwinging = McReflect.field(player.getClass(), "net/minecraft/entity/LivingEntity", "handSwinging");
            Field handSwingTicks = McReflect.field(player.getClass(), "net/minecraft/entity/LivingEntity", "handSwingTicks");
            if (handSwinging == null || handSwingTicks == null) return;

            boolean swinging = handSwinging.getBoolean(player);
            int ticks = handSwingTicks.getInt(player);
            int swingEnd = 6; // approximation vanilla de base — voir MixinSwingSpeed189 pour l'ajustement Célérité/Fatigue, ignoré ici (cas rare pendant un blocage)

            if (!swinging || ticks >= swingEnd / 2 || ticks < 0) {
                handSwingTicks.setInt(player, -1);
                handSwinging.setBoolean(player, true);
                if (diag) LauncherLog.info("[MixinSwingWhileBlocking189] swing relancé");
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinSwingWhileBlocking189] la$restartSwingWhileBlocking: " + t);
        }
    }
}
