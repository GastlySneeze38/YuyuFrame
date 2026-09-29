package com.yuyuframe.launcheragent.apimixin.v26_1_0.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code OptionInstance.value} (privé) — voir {@code
 * ZoomModule}. Écrit DIRECTEMENT ce champ (jamais {@code setValue()}, qui
 * déclenche la validation vanilla et clampe fov/sensibilité — piège documenté
 * dans {@code McReflect.simpleOptionSetValue}, supprimé le 2026-09-16, voir
 * git). {@code Object} (pas {@code T}) : erreur d'effacement de
 * type générique côté Mixin évitée, cast explicite côté appelant.
 */
@Mixin(targets = "net.minecraft.client.OptionInstance")
public interface OptionInstanceAccessor2610 {
    @Accessor("value")
    Object la$value();

    @Accessor("value")
    void la$setValue(Object value);
}
