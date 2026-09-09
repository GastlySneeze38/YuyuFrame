package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.optimodule.LabelRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache de TextRenderer.getStringWidth(String)I (official avn.a) — appelée
 * plusieurs fois par frame avec le MÊME texte à divers endroits du rendu
 * (labels flottants notamment, voir MixinLabelRenderDistance189).
 *
 * Écrit à l'origine comme 3x @Redirect sur les call-sites dans
 * EntityRenderer.renderLabelIfPresent — abandonné : un @Redirect sur une
 * méthode D'INSTANCE a besoin d'un receiver exactement typé, impossible ici
 * pour TextRenderer (classe obfusquée du package par défaut, voir
 * MixinLabelRenderDistance189 pour le détail). Solution retenue : mixer
 * DIRECTEMENT dans la méthode cible (pas un call-site) — le seul paramètre
 * de la cible est un String, un type réel et accessible, donc aucun souci de
 * typage. Bénéfice : profite à TOUS les appelants du projet, pas seulement
 * au rendu des labels.
 */
@Mixin(targets = "net.minecraft.client.font.TextRenderer")
public abstract class MixinCachedStringWidth189 {

    private static final Map<String, Integer> la$cache = new ConcurrentHashMap<>();

    @Inject(method = "a(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void la$readCache(String text, CallbackInfoReturnable<Integer> cir) {
        try {
            if (!la$enabled()) return;
            Integer cached = la$cache.get(text);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinCachedStringWidth189 read: " + t);
        }
    }

    @Inject(method = "a(Ljava/lang/String;)I", at = @At("RETURN"))
    private void la$writeCache(String text, CallbackInfoReturnable<Integer> cir) {
        try {
            if (!la$enabled()) return;
            la$cache.put(text, cir.getReturnValueI());
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinCachedStringWidth189 write: " + t);
        }
    }

    private static boolean la$enabled() {
        LauncherModule module = ModuleRegistry.get("label-render-distance");
        return module != null && module.isEnabled() && module instanceof LabelRenderDistanceModule;
    }
}
