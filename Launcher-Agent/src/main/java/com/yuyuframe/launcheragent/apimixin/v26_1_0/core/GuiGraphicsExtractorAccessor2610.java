package com.yuyuframe.launcheragent.apimixin.v26_1_0.core;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code GuiGraphicsExtractor.guiRenderState} (champ
 * PRIVÉ) — ajouté le 2026-08-30 pour la refonte du rendu.
 *
 * <p>C'est la clé de voûte : nos hooks {@code HUD_EXTRACT_*} reçoivent déjà
 * l'{@code GuiGraphicsExtractor}, mais celui-ci n'expose publiquement que des
 * primitives toutes faites ({@code fill}, {@code text}, {@code blit}…). Pour
 * soumettre un élément À NOUS — donc avec nos propres attributs de sommet —
 * il faut atteindre l'état sous-jacent et appeler
 * {@code GuiRenderState.addGuiElement}.
 *
 * <p>L'élément atterrit à la position Z du hook depuis lequel on l'émet :
 * c'est ce qui remplace un empilement subi par un empilement choisi. Voir
 * {@code docs/LauncherAgent/rendering-pipeline.md}.
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor")
public interface GuiGraphicsExtractorAccessor2610 {

    @Accessor("guiRenderState")
    GuiRenderState la$guiRenderState();

    /** Pile de pose (champ privé {@code pose}) — mise à l'échelle des icônes d'objet, voir {@code VanillaItemIcon.scale}. */
    @Accessor("pose")
    Matrix3x2fStack la$pose();
}
