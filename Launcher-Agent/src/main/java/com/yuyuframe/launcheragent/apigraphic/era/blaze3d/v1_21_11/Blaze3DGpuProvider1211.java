package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpu;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpuProvider;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;

/**
 * Fournisseur de {@link Blaze3DGpu1211}. Ne référence AUCUN type du jeu : le
 * {@code ServiceLoader} l'instancie sur toutes les versions (voir
 * {@link Blaze3DGpuProvider}).
 */
@YarnNamed
public final class Blaze3DGpuProvider1211 implements Blaze3DGpuProvider {

    @Override
    public boolean supports(String mcVersion) {
        return "1.21.11".equals(mcVersion);
    }

    @Override
    public Blaze3DGpu create() {
        return new Blaze3DGpu1211();
    }
}
