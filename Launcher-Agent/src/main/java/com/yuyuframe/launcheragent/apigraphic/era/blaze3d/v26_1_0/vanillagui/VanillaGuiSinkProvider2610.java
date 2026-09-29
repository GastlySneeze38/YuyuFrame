package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiSink;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiSinkProvider;

/**
 * Fournisseur de {@link VanillaGuiSink2610}. Ne référence AUCUN type du jeu :
 * le {@code ServiceLoader} l'instancie sur toutes les versions (voir
 * {@link VanillaGuiSinkProvider}).
 *
 * <p>Indispensable pour la 26.1 : sans lui, {@code VanillaGuiSinks} retomberait
 * sur {@code VanillaGuiSink261} (26.1.2), dont les accessors ne sont pas tissés
 * sur cette version — ce sont ceux de {@code apimixin/v26_1_0} qui le sont.
 */
public final class VanillaGuiSinkProvider2610 implements VanillaGuiSinkProvider {

    @Override
    public boolean supports(String mcVersion) {
        return "26.1".equals(mcVersion);
    }

    @Override
    public VanillaGuiSink create() {
        return new VanillaGuiSink2610();
    }
}
