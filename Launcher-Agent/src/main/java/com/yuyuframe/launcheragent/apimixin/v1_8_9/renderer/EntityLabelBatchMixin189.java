package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.apimixin.v1_8_9.entity.PresenceLabel189;
import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.entity.LabelBatch189;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Note les étiquettes de nom au lieu de les dessiner une par une
 * ({@code EntityRenderer.renderLabelIfPresent}, {@code biv}) ; elles partent
 * toutes ensemble à la fin du dessin des entités. Voir {@link LabelBatch189}.
 *
 * <p>Actif seulement avec le renderer de terrain, et seulement pendant sa
 * boucle d'entités : partout ailleurs (aperçu du joueur dans l'inventaire,
 * renderer éteint) l'étiquette est dessinée par vanilla comme avant.
 */
@Mixin(targets = "net.minecraft.client.render.entity.EntityRenderer")
public abstract class EntityLabelBatchMixin189 {

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$deferLabel(@Coerce Object entity, String text, double x, double y, double z, int maxDistance,
                               CallbackInfo ci) {
        // Le premier test garde LabelBatch189 (et JOML) hors de portée quand le moteur est indisponible.
        // Les joueurs YuyuFrame prennent le style du module « Joueur » : seulement sur ce chemin, celui
        // des étiquettes regroupées — une étiquette laissée à vanilla garde son aspect d'origine.
        if (RendererSwitch.active() && LabelBatch189.defer(entity, text, x, y, z, maxDistance, PresenceLabel189.styled(entity, text))) ci.cancel();
    }
}
