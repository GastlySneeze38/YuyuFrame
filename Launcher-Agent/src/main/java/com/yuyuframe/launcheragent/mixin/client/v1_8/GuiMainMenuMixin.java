package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smoke test 1.8.9 — confirme que le pipeline Mixin multi-version fonctionne.
 *
 * Cible : net.minecraft.client.gui.GuiMainMenu (nom Legacy Fabric Yarn / MCP)
 * Méthode : initGui — appelée à chaque affichage du menu principal.
 *
 * NOTE : si le nom de méthode diffère dans vos mappings, ajustez "initGui"
 * et fournissez les mappings Legacy Fabric Yarn 1.8.9 via yarn=<chemin>.
 */
@Mixin(targets = "net.minecraft.client.gui.GuiMainMenu")
public abstract class GuiMainMenuMixin {

    @Inject(method = "initGui", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        LauncherLog.ui(3, "[LauncherAgent] Hook GuiMainMenu.initGui() OK — pipeline 1.8.9 opérationnel");
    }
}
