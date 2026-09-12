package com.yuyuframe.launcheragent.apimixin.v1_21_11.render;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Capte le {@code GuiRenderState} d'un {@code DrawContext} au moment où il est
 * construit — voir {@link DrawContextStateBinding1211} pour le pourquoi (champ
 * privé, ni réflexion ni {@code @Accessor} possibles ici).
 *
 * <p>Cible le constructeur PRIVÉ à cinq paramètres, et lui seul : le
 * constructeur public à quatre paramètres lui délègue ({@code invokespecial}
 * vérifié au bytecode du jar 1.21.11 réel), donc tous les contextes passent par
 * là. Injecter dans les deux capterait deux fois le même.
 *
 * <p>Paramètres en {@link Coerce} {@code Object} : leurs types réels sont
 * obfusqués et nos stubs ne leur correspondent pas — même convention que les
 * autres mixins de cette tranche.
 */
@Mixin(targets = "net.minecraft.client.gui.DrawContext")
public abstract class DrawContextStateMixin1211 {

    @Inject(method = "<init>(Lnet/minecraft/client/MinecraftClient;Lorg/joml/Matrix3x2fStack;Lnet/minecraft/client/gui/render/state/GuiRenderState;II)V",
            at = @At("RETURN"), require = 0)
    private void la$bindGuiRenderState(@Coerce Object client, @Coerce Object matrices, @Coerce Object state,
                                       int width, int height, CallbackInfo ci) {
        try {
            DrawContextStateBinding1211.bind(this, state);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] DrawContextStateMixin1211: " + t);
        }
    }
}
