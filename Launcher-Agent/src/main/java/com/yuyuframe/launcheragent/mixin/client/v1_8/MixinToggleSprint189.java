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
 * Toggle Sprint — technique PolySprint ADAPTÉE à ce bootstrap (voir aussi
 * MixinToggleSneak189, même principe). @Redirect sur KeyBinding.isKeyDown()
 * comme le fait le vrai PolySprint s'est révélé IMPOSSIBLE ici (voir
 * historique de session : Object/stub nommé rejetés "expected avb", aucun
 * remapping de signature de handler dans ce bootstrap custom).
 *
 * REDESIGN (v169 causait des rollbacks serveur sur Hypixel) : la première
 * version appelait directement {@code Entity.setSprinting(true)} par
 * réflexion depuis un @Inject en TAIL, avec un seul garde-fou approximatif
 * ({@code movementForward >= 0.8f}). Ça contourne TOUTES les autres
 * conditions internes vanilla de tickMovement() (faim, monture, cécité,
 * sneaking, etc. — logique complexe jamais entièrement décompilée) : le
 * serveur peut recevoir un paquet START_SPRINTING alors que SA PROPRE
 * simulation dit que ce n'est pas censé être possible → flag anti-triche →
 * rollback.
 *
 * Nouvelle approche : on n'encadre PLUS que {@code KeyBinding.pressed}
 * (sprintKey) — on le force à {@code true} juste AVANT que tickMovement() ne
 * l'examine (HEAD), puis on le restaure juste APRÈS (TAIL). tickMovement()
 * tourne alors SANS AUCUNE modification, avec TOUTES ses vraies conditions
 * internes intactes — comportement rigoureusement identique à un maintien
 * physique de la touche, donc indiscernable d'un appui réel pour le serveur
 * (paquet réseau envoyé par le même chemin, au même moment, dans les mêmes
 * conditions que vanilla l'aurait fait lui-même).
 */
@Mixin(targets = "net.minecraft.entity.player.ClientPlayerEntity")
public abstract class MixinToggleSprint189 {

    private boolean la$prevDown;
    private boolean la$toggled;
    private boolean la$overriding;

    @Inject(method = "m()V", at = @At("HEAD"), require = 0)
    private void la$sprintHead(CallbackInfo ci) {
        la$overriding = false;
        try {
            LauncherModule module = ModuleRegistry.get("toggle-sprint");

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;
            Object sprintKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "sprintKey").get(options);
            if (sprintKey == null) return;

            Field pressedField = McReflect.field(sprintKey.getClass(), "net/minecraft/client/option/KeyBinding", "pressed");
            if (pressedField == null) return;
            boolean realDown = pressedField.getBoolean(sprintKey);

            if (realDown && !la$prevDown) la$toggled = !la$toggled;
            la$prevDown = realDown;

            if (module != null && module.isEnabled() && la$toggled && !realDown) {
                pressedField.setBoolean(sprintKey, true);
                la$overriding = true;
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinToggleSprint189] la$sprintHead: " + t);
        }
    }

    @Inject(method = "m()V", at = @At("TAIL"), require = 0)
    private void la$sprintTail(CallbackInfo ci) {
        if (!la$overriding) return;
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;
            Object sprintKey = McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "sprintKey").get(options);
            if (sprintKey == null) return;
            Field pressedField = McReflect.field(sprintKey.getClass(), "net/minecraft/client/option/KeyBinding", "pressed");
            if (pressedField != null) pressedField.setBoolean(sprintKey, false);
        } catch (Throwable t) {
            LauncherLog.err("[MixinToggleSprint189] la$sprintTail: " + t);
        } finally {
            la$overriding = false;
        }
    }
}
