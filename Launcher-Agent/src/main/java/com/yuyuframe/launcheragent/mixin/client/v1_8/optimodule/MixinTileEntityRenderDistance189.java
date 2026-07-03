package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.optimodule.TileEntityRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cible : net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher
 * (official bhc), méthode renderEntity(Lakw;DDDFI)V (officiel "a",
 * method_10100) — vérifiée par désassemblage bytecode réel :
 * renderBlockEntity(BlockEntity,D,D,D,F) délègue directement ici avec -1 en
 * stage de casse. Les coordonnées D,D,D reçues sont DÉJÀ relatives à la
 * caméra (convention vanilla constante pour éviter les soucis de précision
 * flottante aux grandes coordonnées absolues) — donc la distance au carré se
 * calcule directement depuis x/y/z, aucun besoin de lire la position caméra
 * séparément.
 *
 * CORRECTIF (voir MixinLabelRenderDistance189 pour l'historique complet) : la
 * version précédente capturait le 1er paramètre (BlockEntity, {@code akw})
 * en {@code Object} dans le handler @Inject — confirmé CASSÉ en test réel
 * (log : {@code InvalidInjectionException Expected (Lakw;DDDFI...)V but
 * found (Ljava/lang/Object;DDDFI...)V}), Mixin rejette le descripteur dès
 * qu'un type référence en tête est déclaré autrement qu'avec son vrai type,
 * et il n'y a aucun moyen de déclarer {@code akw} en Java (classe obfusquée
 * du package par défaut, inaccessible depuis un package nommé). Solution :
 * @ModifyVariable cible directement le SLOT (index=2/4/6 — this=slot0,
 * tileEntity=slot1, x=slots2-3, y=slots4-5, z=slots6-7, doubles = 2 slots
 * chacun, vérifié par désassemblage) sans jamais avoir besoin de typer/
 * déclarer le paramètre BlockEntity.
 */
@Mixin(targets = "net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher")
public abstract class MixinTileEntityRenderDistance189 {

    private static double la$curX;
    private static double la$curY;
    private static double la$curZ;

    @ModifyVariable(method = "a(Lakw;DDDFI)V", at = @At("HEAD"), index = 2)
    private double la$captureX(double x) { la$curX = x; return x; }

    @ModifyVariable(method = "a(Lakw;DDDFI)V", at = @At("HEAD"), index = 4)
    private double la$captureY(double y) { la$curY = y; return y; }

    @ModifyVariable(method = "a(Lakw;DDDFI)V", at = @At("HEAD"), index = 6)
    private double la$captureZ(double z) { la$curZ = z; return z; }

    @Inject(method = "a(Lakw;DDDFI)V", at = @At("HEAD"), cancellable = true)
    private void la$cullDistantTileEntities(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("tile-entity-render-distance");
            if (module == null || !module.isEnabled() || !(module instanceof TileEntityRenderDistanceModule)) return;
            float maxDist = ((TileEntityRenderDistanceModule) module).maxDistance;
            double dist2 = la$curX * la$curX + la$curY * la$curY + la$curZ * la$curZ;
            if (dist2 > (double) (maxDist * maxDist)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinTileEntityRenderDistance189: " + t);
        }
    }
}
