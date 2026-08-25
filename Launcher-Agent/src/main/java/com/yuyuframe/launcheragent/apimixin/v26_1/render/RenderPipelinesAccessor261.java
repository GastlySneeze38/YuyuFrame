package com.yuyuframe.launcheragent.apimixin.v26_1.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code RenderPipelines.GUI_TEXTURED}, AU CAS OÙ il
 * s'avère en réalité privé/package (voir la javadoc du stub {@code
 * RenderPipelines} — actuellement tenté en champ public direct en premier,
 * plus simple). Champ STATIC : accesseur généré valide malgré l'absence
 * probable d'instance de {@code RenderPipelines} (classe de constantes
 * jamais instanciée) — Sponge Mixin gère les accessors sur champs statiques
 * sans exiger de receveur particulier, mais nécessite malgré tout un moyen
 * d'appel (voir limitation notée : pas utilisable sans au moins une
 * instance ou un point d'entrée alternatif, à valider en jeu si jamais
 * {@code RenderPipelines.GUI_TEXTURED} direct échoue).
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderPipelines")
public interface RenderPipelinesAccessor261 {
    /**
     * {@code static} requis pour un champ statique (idiome Sponge Mixin). Non
     * utilisé pour l'instant — {@code @Accessor} sur {@code Minecraft} a
     * causé un VerifyError au tissage (voir la javadoc du stub {@code
     * Minecraft.java}) : à vérifier en jeu avant toute utilisation réelle
     * plutôt que de supposer cette classe sans risque.
     */
    @Accessor("GUI_TEXTURED")
    static RenderPipeline la$guiTextured() { throw new AssertionError(); }
}
