package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Toggle Sneak — même redesign "fake KeyBinding.pressed" que
 * {@link MixinToggleSprint189} (voir sa javadoc pour le contexte complet :
 * rollbacks serveur sur Hypixel avec l'ancienne version qui forçait
 * directement Input.sneaking + le ralentissement ×0.3 + Entity.setSneaking()
 * à la main).
 *
 * En encadrant KeyboardInput.tick() (bev.a()) d'un HEAD/TAIL qui bascule
 * UNIQUEMENT {@code sneakKey.pressed}, vanilla recalcule LUI-MÊME
 * {@code this.d} (sneaking, à l'offset ~121-127 du bytecode réel de bev.a())
 * ET applique LUI-MÊME le ralentissement ×0.3 sur movementSideways/
 * movementForward (offset ~130-165) — plus besoin de le répliquer à la main.
 * La synchro serveur passe par {@code ClientPlayerEntity.p()}
 * (sendMovementPackets, lit {@code av()} qui lit directement
 * {@code input.sneaking}) exactement comme pour un appui réel — comportement
 * rigoureusement identique à un maintien physique de la touche, donc
 * indiscernable d'un appui réel pour le serveur.
 */
@Mixin(targets = "net.minecraft.client.input.KeyboardInput")
public abstract class MixinToggleSneak189 {

    private boolean la$prevDown;
    private boolean la$toggled;
    private boolean la$overriding;

    @Inject(method = "a()V", at = @At("HEAD"), require = 0)
    private void la$sneakHead(CallbackInfo ci) {
        la$overriding = false;
        try {
            LauncherModule module = ModuleRegistry.get("toggle-sneak");

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;
            Object sneakKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "sneakKey").get(options);
            if (sneakKey == null) return;

            Field pressedField = McReflect.field(sneakKey.getClass(), "net/minecraft/client/option/KeyBinding", "pressed");
            if (pressedField == null) return;
            boolean realDown = pressedField.getBoolean(sneakKey);

            if (realDown && !la$prevDown) la$toggled = !la$toggled;
            la$prevDown = realDown;

            if (module != null && module.isEnabled() && la$toggled && !realDown) {
                pressedField.setBoolean(sneakKey, true);
                la$overriding = true;
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinToggleSneak189] la$sneakHead: " + t);
        }
    }

    @Inject(method = "a()V", at = @At("TAIL"), require = 0)
    private void la$sneakTail(CallbackInfo ci) {
        if (!la$overriding) return;
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;
            Object sneakKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "sneakKey").get(options);
            if (sneakKey == null) return;
            Field pressedField = McReflect.field(sneakKey.getClass(), "net/minecraft/client/option/KeyBinding", "pressed");
            if (pressedField != null) pressedField.setBoolean(sneakKey, false);
        } catch (Throwable t) {
            LauncherLog.err("[MixinToggleSneak189] la$sneakTail: " + t);
        } finally {
            la$overriding = false;
        }
    }
}
