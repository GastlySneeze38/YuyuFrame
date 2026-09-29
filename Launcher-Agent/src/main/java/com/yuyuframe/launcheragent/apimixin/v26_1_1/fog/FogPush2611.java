package com.yuyuframe.launcheragent.apimixin.v26_1_1.fog;

import com.yuyuframe.launcheragent.apimixin.data.FogOverride;
import net.minecraft.client.renderer.fog.FogData;

/**
 * Écriture commune aux six mixins {@code FogSetup*Mixin2611} — voir
 * {@link FogOverride} pour le contrat.
 *
 * <p>Classe NORMALE, pas un mixin : Mixin interdit de référencer une classe
 * mixin au runtime ({@code IllegalClassLoadError}), donc la méthode partagée
 * ne peut pas vivre sur l'un des six.
 */
public final class FogPush2611 {

    private FogPush2611() {}

    public static void pushFar(FogData fogData) {
        fogData.environmentalStart = FogOverride.FAR;
        fogData.environmentalEnd = FogOverride.FAR * 2f;
        fogData.renderDistanceStart = FogOverride.FAR;
        fogData.renderDistanceEnd = FogOverride.FAR * 2f;
    }
}
