package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.VanillaGuiSink;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.VanillaGuiSinkProvider;

/**
 * Fournisseur de {@link VanillaGuiSink1211}. Ne référence AUCUN type du jeu :
 * le {@code ServiceLoader} l'instancie sur toutes les versions (voir
 * {@link VanillaGuiSinkProvider}).
 */
public final class VanillaGuiSinkProvider1211 implements VanillaGuiSinkProvider {

    @Override
    public boolean supports(String mcVersion) {
        return "1.21.11".equals(mcVersion);
    }

    @Override
    public VanillaGuiSink create() {
        return new VanillaGuiSink1211();
    }
}
