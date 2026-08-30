package com.yuyuframe.launcheragent.apimixin.v26_1.render;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour les constantes d'attribut de sommet — ajouté le
 * 2026-08-30 pour construire un {@code VertexFormat} maison (refonte du
 * rendu, voir {@code docs/LauncherAgent/rendering-pipeline.md}).
 *
 * <p>Ces champs sont PUBLICS et statiques : un accès direct compilerait. Ils
 * passent quand même par un accessor, comme {@code Minecraft.player} et
 * {@code Minecraft.level} — c'est la norme du projet. La portabilité
 * multiversion vient de ce que TOUS les accès à l'état du jeu traversent une
 * seule interface par bracket : le jour où une constante change de nom sur
 * une autre version, seul l'accessor de CE bracket bouge, jamais un appelant.
 *
 * <p>{@code static} REQUIS sur chaque accesseur : les champs le sont, et
 * Sponge Mixin exige la même staticité que la cible — sans quoi il avertit à
 * chaque lancement et l'accès ne fonctionne pas. Le corps lève plutôt que de
 * renvoyer {@code null} en silence, pour rendre bruyant un mixin non tissé.
 *
 * <p>Types réels (vérifiés sur le jar client 26.1.2) : {@code UV0} = 2
 * flottants, {@code UV1}/{@code UV2} = 2 entiers courts. C'est ce qui permet
 * de porter les 5 scalaires d'un SDF de coin arrondi — position locale (UV0),
 * demi-taille (UV1), rayon (UV2).
 */
@Mixin(targets = "com.mojang.blaze3d.vertex.VertexFormatElement")
public interface VertexFormatElementAccessor261 {

    @Accessor("POSITION")
    static VertexFormatElement la$position() { throw new AssertionError("VertexFormatElementAccessor261 non tissé"); }

    @Accessor("COLOR")
    static VertexFormatElement la$color() { throw new AssertionError("VertexFormatElementAccessor261 non tissé"); }

    @Accessor("UV0")
    static VertexFormatElement la$uv0() { throw new AssertionError("VertexFormatElementAccessor261 non tissé"); }

    @Accessor("UV1")
    static VertexFormatElement la$uv1() { throw new AssertionError("VertexFormatElementAccessor261 non tissé"); }

    @Accessor("UV2")
    static VertexFormatElement la$uv2() { throw new AssertionError("VertexFormatElementAccessor261 non tissé"); }
}
