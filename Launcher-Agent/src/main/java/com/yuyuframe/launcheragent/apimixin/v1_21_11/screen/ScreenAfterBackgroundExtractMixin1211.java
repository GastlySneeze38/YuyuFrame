package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CONTAINER_SCREEN_EXTRACT_TOOLTIP} sur 1.21.11 — pendant de
 * {@code ScreenAfterBackgroundExtractMixin261}.
 *
 * <pre>
 *   26.1.2 : Screen.extractRenderStateWithTooltipAndSubtitles → extractBackground, AFTER
 *   1.21.11 : Screen.renderWithTooltip                        → renderBackground, AFTER
 * </pre>
 *
 * Vérifié javap : {@code gsb.c(gir,IIF)} appelle {@code b(gir,IIF)}
 * ({@code renderBackground}) une seule fois, puis {@code a(gir,IIF)}
 * ({@code render}) — même ordre qu'en 26.1.2, donc même moment : fond dessiné,
 * contenu de l'écran pas encore.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class ScreenAfterBackgroundExtractMixin1211 {

    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/DrawContext;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;renderBackground(Lnet/minecraft/client/gui/DrawContext;IIF)V",
                     shift = At.Shift.AFTER),
            require = 0)
    private void la$dispatchAfterBackground(@Coerce Object context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP, context);
    }
}
