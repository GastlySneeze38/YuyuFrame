package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Portage 1.21.4 de {@code CameraFreelookMixin} (1.21.11) — architecture
 * IDENTIQUE CONFIRMÉE par désassemblage complet (javap, jar 1.21.4 réel) de
 * {@code Camera.update(World,Entity,boolean,boolean,float)V} : mêmes IDs
 * intermediary EXACTS que 1.21.11 (method_19321=update, method_19324=moveBy,
 * method_19325=setRotation, method_19317=updateEyeHeight...), même structure
 * de bytecode (branche sommeil, branche 3e personne avec DEUX call sites de
 * {@code moveBy(FFF)V} aux offsets 309/368, branche vue rapprochée) — voir
 * {@code CameraFreelookMixin} (1.21.11) pour la description complète de
 * cette structure, repris ici SANS adaptation nécessaire.
 */
@Mixin(targets = "net.minecraft.client.render.Camera")
public abstract class CameraFreelookMixin1214 {

    private static volatile Field fPitch, fYaw;
    private static volatile Method mSetRotation;

    private boolean la$moveCalledThisUpdate;

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("HEAD"), require = 0)
    private void la$onUpdateHead(CallbackInfo ci) {
        la$moveCalledThisUpdate = false;
    }

    @Inject(method = "moveBy(FFF)V", at = @At("HEAD"), require = 0)
    private void la$beforeMoveBy(CallbackInfo ci) {
        try {
            la$moveCalledThisUpdate = true;
            if (!FreelookModule.isActive()) return;
            applyOffset();
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin1214] la$beforeMoveBy: " + t);
        }
    }

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL"), require = 0)
    private void la$onUpdateTail(CallbackInfo ci) {
        try {
            if (!FreelookModule.isActive()) return;
            if (la$moveCalledThisUpdate) return;
            applyOffset();
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin1214] la$onUpdateTail: " + t);
        }
    }

    private void applyOffset() throws Exception {
        if (fPitch == null) {
            fPitch = McReflect.field(this.getClass(), "net/minecraft/client/render/Camera", "pitch");
            fYaw = McReflect.field(this.getClass(), "net/minecraft/client/render/Camera", "yaw");
            mSetRotation = McReflect.method(this.getClass(), "net/minecraft/client/render/Camera",
                "setRotation", float.class, float.class);
            if (fPitch == null || fYaw == null || mSetRotation == null) return;
        }
        float pitch = fPitch.getFloat(this);
        float yaw = fYaw.getFloat(this);
        float newPitch = clamp(pitch + (float) FreelookModule.pitchOffset(), -90f, 90f);
        float newYaw = yaw + (float) FreelookModule.yawOffset();
        // setRotation(FF)V : p1=yaw, p2=pitch — confirmé via mappings/yarn-1.21.4-mergedv2.jar.
        mSetRotation.invoke(this, newYaw, newPitch);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
