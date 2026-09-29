package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_2.gpu;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.Blaze3DGpu;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.Blaze3DGpuProvider;

/**
 * Fournisseur de {@code Blaze3DGpu262}. Ne référence AUCUN type du jeu : le
 * {@code ServiceLoader} l'instancie sur toutes les versions (voir
 * {@link Blaze3DGpuProvider}).
 */
public final class Blaze3DGpuProvider262 implements Blaze3DGpuProvider {

    @Override
    public boolean supports(String mcVersion) {
        return "26.2".equals(mcVersion);
    }

    @Override
    public Blaze3DGpu create() {
        return new Blaze3DGpu262();
    }
}
