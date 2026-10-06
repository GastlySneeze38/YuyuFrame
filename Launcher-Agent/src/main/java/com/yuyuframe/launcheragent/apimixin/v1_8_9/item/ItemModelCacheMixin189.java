package com.yuyuframe.launcheragent.apimixin.v1_8_9.item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Branche {@link ItemModelCache189} sur l'{@code ItemRenderer} de la 1.8.9
 * ({@code bjh}) : la géométrie d'un modèle d'objet est enregistrée une fois,
 * puis rejouée. Ce fichier ne nomme aucun type du jeu.
 *
 * <p>Deux injections sur {@code renderBakedItemModel(modèle, couleur, objet)},
 * le point unique par lequel passent tous les modèles d'objets :
 * <ul>
 *   <li>en tête — rejoue le cache et annule, ou ouvre un enregistrement et
 *       laisse vanilla dessiner dedans ;</li>
 *   <li>en sortie — clôt l'enregistrement.</li>
 * </ul>
 * Pas d'invoker pour rappeler la méthode d'origine : c'est vanilla lui-même
 * qui s'exécute entre les deux.
 */
@Mixin(targets = "net.minecraft.client.render.item.ItemRenderer")
public abstract class ItemModelCacheMixin189 {

    @Inject(method = "renderBakedItemModel(Lnet/minecraft/client/render/model/BakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$replayOrRecord(@Coerce Object model, int color, @Coerce Object stack, CallbackInfo ci) {
        if (ItemModelCache189.begin(model, color, stack)) ci.cancel();
    }

    @Inject(method = "renderBakedItemModel(Lnet/minecraft/client/render/model/BakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At("RETURN"), require = 0)
    private void la$endRecording(@Coerce Object model, int color, @Coerce Object stack, CallbackInfo ci) {
        ItemModelCache189.end();
    }

    /** Les modèles sont refaits : les enregistrements ne valent plus rien. */
    @Inject(method = "reload(Lnet/minecraft/resource/ResourceManager;)V", at = @At("HEAD"), require = 0)
    private void la$clearOnReload(@Coerce Object resourceManager, CallbackInfo ci) {
        ItemModelCache189.clear();
    }
}
