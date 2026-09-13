package com.yuyuframe.launcheragent.apimixin.v1_21_11.fog;

import com.yuyuframe.launcheragent.apimixin.data.FogOverride;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Corps commun aux six {@code FogSetup*Mixin1211} — pendant de
 * {@code FogPush261}, contrat dans {@link FogOverride}.
 *
 * <p>Classe NORMALE, pas un mixin (Mixin interdit de référencer une classe
 * mixin au runtime). {@code fogData} arrive en {@code Object} : sa classe est
 * obfusquée, on n'y touche qu'à travers {@link FogDataAccessor1211}.
 */
public final class FogPush1211 {

    private FogPush1211() {}

    private static boolean errorLogged;

    public static void dispatch(HookPoint point, Object fogData) {
        if (fogData == null || !VanillaHookRegistry.dispatch(point, null)) return;
        try {
            FogDataAccessor1211 data = (FogDataAccessor1211) fogData;
            data.la$setEnvironmentalStart(FogOverride.FAR);
            data.la$setEnvironmentalEnd(FogOverride.FAR * 2f);
            data.la$setRenderDistanceStart(FogOverride.FAR);
            data.la$setRenderDistanceEnd(FogOverride.FAR * 2f);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[FogPush1211] " + point + " : " + t);
            }
        }
    }
}
