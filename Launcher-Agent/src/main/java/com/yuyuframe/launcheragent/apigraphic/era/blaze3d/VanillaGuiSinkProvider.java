package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

/**
 * Fabrique une {@link VanillaGuiSink} pour une version — découverte par
 * {@code ServiceLoader}, voir {@link VanillaGuiSinks}.
 *
 * <p>Une implémentation NE DOIT référencer AUCUN type du jeu : le
 * {@code ServiceLoader} l'instancie sur TOUTES les versions, y compris celles
 * qu'elle ne sert pas. Les types du jeu n'apparaissent que dans la sink
 * elle-même, créée après un {@link #supports} positif — même précaution que
 * {@code Blaze3DGpuProvider}.
 */
public interface VanillaGuiSinkProvider {

    /** {@code mcVersion} = propriété {@code launcheragent.mcVersion} (fournie par le launcher). */
    boolean supports(String mcVersion);

    VanillaGuiSink create();
}
