package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * {@link HookPoint#HUD_EXTRACT_ARMOR} sur 1.8.9.
 *
 * <h2>Pourquoi un site d'appel, et pas la méthode appelée</h2>
 *
 * La 1.8.9 n'a pas de méthode par barre : armure, cœurs, faim, monture et air
 * sont dessinés à la suite dans {@code InGameHud.renderStatusBars(Window)}, en
 * sections balisées par le profiler (javap {@code avo.d(avr)V} :
 * {@code push("armor")}, puis {@code swap("health")}, {@code swap("food")},
 * {@code swap("mountHealth")}, {@code swap("air")}, {@code pop()}). La règle
 * « viser la méthode appelée » n'a donc rien à viser ; l'exception prévue par
 * la javadoc de {@link HookPoint} s'applique : les {@code drawTexture} de la
 * section, bornée par ses libellés de profiler. Les libellés sont des chaînes
 * uniques de la méthode, et aucun mod tiers ne peut envelopper la méthode sur
 * cette version (conception 1.8.9, D1).
 *
 * <h2>Largeur à zéro plutôt qu'appel sauté (2026-09-15)</h2>
 *
 * La première version faisait un {@code @WrapWithCondition} avec un receveur
 * typé {@code Object}. MixinExtras VALIDE la signature et exige le vrai type
 * obfusqué ({@code avo}) : refusé, et tout {@code InGameHud} serait resté non
 * transformé (même échec constaté sur {@code MinecraftClient} au premier
 * lancement dégelé). Ici {@code @ModifyArg} ne touche que la LARGEUR, un
 * {@code int} : à zéro, le quadrilatère n'a aucune surface et rien n'est
 * dessiné. Aucun type du jeu dans la signature, et plusieurs {@code @ModifyArg}
 * composent sur un même appel.
 *
 * <p>Même forme pour les quatre autres barres ({@code Hearts}, {@code Food},
 * {@code VehicleHealth}, {@code AirBubbles}).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractArmorMixin189 {

    @ModifyArg(method = "renderStatusBars(Lnet/minecraft/client/util/Window;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V"),
        slice = @Slice(
            from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=armor"),
            to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=health")),
        index = 4, require = 0)
    private int la$dispatchArmor(int width) {
        return VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_ARMOR, null) ? 0 : width;
    }
}
