package com.yuyuframe.launcheragent.apimixin.v1_21_11.freelook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accès à la rotation de la caméra 1.21.11, pour {@link CameraFreelookMixin1211}.
 *
 * <p>Remplace la réflexion de l'ancien {@code mixin.client.CameraFreelookMixin}
 * ({@code McReflect.field(…, "pitch")}, {@code McReflect.method(…, "setRotation")}).
 * Uniquement des types primitifs : rien à typer contre une classe obfusquée.
 *
 * <p>Les trois noms sont traduits par des entrées de refmap de type FIELD et
 * INVOKER ({@code LauncherMixinService.REFMAP_ENTRIES}) : une référence
 * d'accessor n'a pas de propriétaire, {@code REFMAP_REMAP} ne peut donc pas la
 * traduire seul (vérifié dans le bytecode de {@code RemappingReferenceMapper}).
 *
 * <p>Ordre des paramètres de {@code setRotation(FF)V} : lacet puis tangage —
 * confirmé par les noms de paramètres Yarn ({@code yaw}, {@code pitch}).
 */
@Mixin(targets = "net.minecraft.client.render.Camera")
public interface CameraAccessor1211 {

    @Accessor("yaw")
    float la$yaw();

    @Accessor("pitch")
    float la$pitch();

    @Invoker("setRotation")
    void la$setRotation(float yaw, float pitch);
}
