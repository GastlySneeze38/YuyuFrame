package com.yuyuframe.launcheragent.apigraphic.era.gl3.item;

/**
 * Fabrique un {@link Gl3VanillaItemSink} pour une version — découverte par
 * {@code ServiceLoader} ({@link Gl3VanillaItemSinks}).
 *
 * <p>Une implémentation NE DOIT référencer AUCUN type du jeu : le
 * {@code ServiceLoader} l'instancie sur toutes les versions. Même précaution
 * que {@code VanillaGuiSinkProvider}.
 */
public interface Gl3VanillaItemSinkProvider {

    boolean supports(String mcVersion);

    Gl3VanillaItemSink create();
}
