package com.yuyuframe.launcheragent.apimixin.v26_1_2.render;

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

    /**
     * {@code RenderPipelines.GUI} — pipeline NON TEXTURÉ (format
     * {@code Position + Color}), celui qu'utilise {@code fill()}.
     *
     * <p>Ajouté le 2026-08-30 pour la refonte du rendu : tout pipeline à nous
     * destiné à être soumis dans l'état de GUI de vanilla doit copier CE
     * format-là. Avec celui de {@code GUI_TEXT} (texturé, {@code UV0 + UV2}),
     * le client crashe dans {@code GuiRenderer.prepare} — voir
     * {@code docs/LauncherAgent/rendering-pipeline.md}.
     *
     * <p>Passe par un accessor et non par {@code getField} : c'est la norme
     * du projet pour tout accès à l'état du jeu, y compris sur un champ
     * public, parce que la portabilité multiversion vient de ce que TOUS les
     * accès traversent une seule interface par bracket (voir
     * {@code MinecraftAccessor261}).
     */
    @Accessor("GUI")
    static RenderPipeline la$gui() { throw new AssertionError("RenderPipelinesAccessor261 non tissé"); }
}
