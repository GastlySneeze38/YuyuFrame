package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * Ce que le scoring lit d'une entité — voir {@code AccessPoint.ENTITY_*} et
 * docs/scoring/capture.md.
 *
 * <p>Tous ces membres sont publics dans le jeu. Ils passent quand même par
 * un accessor, sur consigne (voir {@code FoodDataAccessor261}) : un renommage
 * sur une autre version ne doit toucher que l'accessor de cette version.
 *
 * <p>Signatures relevées par {@code javap} sur les jars 26.1, 26.1.1, 26.1.2,
 * 26.2 et 26.3 le 2026-10-13 : identiques sur les cinq. {@code fallDistance}
 * est un {@code double} sur cette ligne.
 */
@Mixin(targets = "net.minecraft.world.entity.Entity")
public interface EntityAccessor261 {

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

    /** Déclarée sur {@code Entity}, redéfinie par {@code LivingEntity} (javap, jar 26.1.2, 2026-10-14). */
    @Invoker("getYHeadRot")
    float la$yHeadRot();
}
