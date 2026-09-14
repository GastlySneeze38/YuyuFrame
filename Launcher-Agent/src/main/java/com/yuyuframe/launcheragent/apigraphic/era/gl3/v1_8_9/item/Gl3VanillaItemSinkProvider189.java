package com.yuyuframe.launcheragent.apigraphic.era.gl3.v1_8_9.item;

import com.yuyuframe.launcheragent.apigraphic.era.gl3.item.Gl3VanillaItemSink;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.item.Gl3VanillaItemSinkProvider;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;

/**
 * Fournisseur de {@link Gl3VanillaItemSink189}. Ne référence AUCUN type du jeu :
 * le {@code ServiceLoader} l'instancie sur toutes les versions (voir
 * {@link Gl3VanillaItemSinkProvider}).
 */
@YarnNamed
public final class Gl3VanillaItemSinkProvider189 implements Gl3VanillaItemSinkProvider {

    @Override
    public boolean supports(String mcVersion) {
        return "1.8.9".equals(mcVersion);
    }

    @Override
    public Gl3VanillaItemSink create() {
        return new Gl3VanillaItemSink189();
    }
}
