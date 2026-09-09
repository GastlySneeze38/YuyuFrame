package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.visual.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * Temps du monde (client-only) — cible {@code World.getSkyAngle(float)F}
 * (lettre officielle "c", confirmée method_3670 dans mappings-1.8.9.tiny),
 * qui pilote la position soleil/lune ET la couleur du ciel/luminosité
 * ambiante côté RENDU uniquement (vérifié par javap sur adm.class = World :
 * lit {@code this.dimension}/lettre "t" + {@code this.worldInfo.getWorldTime()}
 * puis délègue à {@code Dimension.getSkyAngle(long, float)F} — lettre "a" sur
 * anm/Dimension, confirmée method_3980).
 *
 * On appelle NOUS-MÊMES cette même formule vanilla (anm.a(J,F)F) avec NOTRE
 * propre valeur d'heure au lieu du vrai temps du monde lu depuis WorldInfo —
 * réutilise exactement la courbe vanilla (pas de réimplémentation risquée),
 * mais n'affecte QUE ce que cette méthode calcule (rendu du ciel), jamais le
 * vrai {@code WorldInfo.worldTime} qui reste sous contrôle serveur (spawn de
 * mobs, etc. inchangés — un joueur sur un serveur non-OP ne peut de toute
 * façon pas changer le vrai temps, seulement l'apparence locale).
 *
 * Aucun paramètre de la cible capturé au-delà du {@code float} primitif déjà
 * présent (le seul paramètre de {@code getSkyAngle(float)}) — pas de souci
 * de type comme rencontré ailleurs avec des paramètres de référence MC.
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class MixinWorldTime189 {

    @Inject(method = "c(F)F", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$fakeSkyAngle(float partialTicks, CallbackInfoReturnable<Float> cir) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("world-time");
            if (!(moduleBase instanceof WorldTimeModule) || !moduleBase.isEnabled()) return;
            WorldTimeModule module = (WorldTimeModule) moduleBase;

            Object dimension = McReflect.field(this.getClass(), "net/minecraft/world/World", "dimension").get(this);
            if (dimension == null) return;

            Method getSkyAngle = McReflect.method(dimension.getClass(), "net/minecraft/world/dimension/Dimension", "getSkyAngle", long.class, float.class);
            if (getSkyAngle == null) return;

            float angle = (float) getSkyAngle.invoke(dimension, (long) module.time, partialTicks);
            cir.setReturnValue(angle);
        } catch (Throwable t) {
            LauncherLog.err("[MixinWorldTime189] la$fakeSkyAngle: " + t);
        }
    }
}
