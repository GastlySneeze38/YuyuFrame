package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_3.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiSink;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiSinkProvider;

/**
 * Fournisseur de {@link VanillaGuiSink263}. Ne référence AUCUN type du jeu :
 * le {@code ServiceLoader} l'instancie sur toutes les versions (voir
 * {@link VanillaGuiSinkProvider}).
 *
 * <p>Sans lui, {@code VanillaGuiSinks} retomberait en 26.2 sur
 * {@code VanillaGuiSink261} (26.1.2), dont les accessors ne sont pas tissés
 * sur cette version — ce sont ceux de {@code apimixin/v26_3} qui le sont.
 */
public final class VanillaGuiSinkProvider263 implements VanillaGuiSinkProvider {

    @Override
    public boolean supports(String mcVersion) {
        return "26.3".equals(mcVersion);
    }

    @Override
    public VanillaGuiSink create() {
        return new VanillaGuiSink263();
    }
}
