package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Port de {@code ClearOverlaysMixin261} pour le bracket 1.21.11 — voir sa
 * javadoc pour l'architecture complète (un seul point de passage pour les
 * overlays citrouille ET neige poudreuse, filtré par chemin de texture).
 *
 * BUG TROUVÉ (test utilisateur, 1.21.11) — {@code InvalidInjectionException:
 * Invalid descriptor... Expected (Lgir;Lamo;F...)V but found
 * (Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F...)V} :
 * contrairement à {@code method=} (traduit par le refmap via
 * {@code REFMAP_ENTRIES}, voir {@code LauncherMixinService}), les types des
 * PARAMÈTRES CAPTURÉS par le handler lui-même (première version : {@code
 * DrawContext context, Identifier textureId}) ne sont PAS traduits — ce
 * projet désactive l'annotation processor Mixin (`-proc:none`, voir
 * build.bat) qui ferait normalement cette réécriture de bytecode. Nos stubs
 * compile-only ({@code net.minecraft.client.gui.DrawContext}/{@code
 * net.minecraft.util.Identifier}) sont des classes TOTALEMENT DIFFÉRENTES
 * des vraies classes obfusquées runtime ({@code gir}/{@code amo}) — Sponge
 * exige une correspondance exacte de type pour tout paramètre capturé,
 * jamais de repli. {@code CrosshairMixin} (même bracket, déjà fonctionnel)
 * évite ce piège en ne capturant JAMAIS aucun paramètre du target, seulement
 * {@code CallbackInfo} — impossible ici, la logique a besoin de LIRE
 * {@code textureId}.
 *
 * Fix : {@link Coerce} — annotation Sponge Mixin dédiée à exactement ce cas
 * (capturer un paramètre dont le type réel n'est pas résolvable à la
 * compilation) : déclarer le paramètre en {@code Object}, Sponge accepte
 * n'importe quel type runtime assignable à {@code Object} (donc tout) sans
 * validation stricte. La valeur réelle (instance de {@code amo}, obf de
 * {@code Identifier}) est ensuite lue par réflexion via {@link McReflect}
 * (jamais de cast direct vers un type non résolvable).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class ClearOverlaysMixin {

    private static volatile Method mGetPath;

    @Inject(method = "renderOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$filterTextureOverlay(@Coerce Object context, @Coerce Object textureId, float alpha, CallbackInfo ci) {
        try {
            if (textureId == null) return;
            if (mGetPath == null) {
                mGetPath = McReflect.noArgMethod(textureId.getClass(), "net/minecraft/util/Identifier", "getPath");
                if (mGetPath == null) return;
            }
            Object pathObj = mGetPath.invoke(textureId);
            String path = pathObj != null ? pathObj.toString() : null;
            if (path == null) return;

            if (path.contains("pumpkin")) {
                LauncherModule module = ModuleRegistry.get("no-pumpkin-overlay");
                if (module != null && module.isEnabled()) ci.cancel();
                return;
            }
            if (path.contains("powder_snow")) {
                LauncherModule module = ModuleRegistry.get("clear-vision");
                if (module != null && module.isEnabled()) ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[ClearOverlaysMixin] la$filterTextureOverlay: " + t);
        }
    }
}
