package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * Ce que le scoring lit d'une entité — pendant de {@code EntityAccessor261},
 * voir {@code AccessPoint.ENTITY_*} et docs/scoring/capture.md.
 *
 * <p>Signatures relevées par {@code javap} sur le jar 26.3 le 2026-10-13 :
 * les mêmes qu'en 26.1.2, {@code fallDistance} en {@code double} compris.
 */
@Mixin(targets = "net.minecraft.world.entity.Entity")
public interface EntityAccessor263 {

    @Accessor("fallDistance")
    double la$fallDistance();

    @Invoker("getId")
    int la$id();

    @Invoker("getUUID")
    UUID la$uuid();

    /** Pour un joueur, le nom de son profil ; pour une autre entité, son UUID en texte. */
    @Invoker("getScoreboardName")
    String la$scoreboardName();

    @Invoker("getX")
    double la$x();

    @Invoker("getY")
    double la$y();

    @Invoker("getZ")
    double la$z();

    @Invoker("getDeltaMovement")
    Vec3 la$deltaMovement();

    @Invoker("onGround")
    boolean la$onGround();

    /** Déclarée sur {@code Entity}, redéfinie par {@code LivingEntity} (javap, jar 26.3, 2026-10-14). */
    @Invoker("getYHeadRot")
    float la$yHeadRot();
}
