package com.yuyuframe.launcheragent.runtime.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Registre global des éléments HUD déplaçables — un module (voir
 * runtime.modules.builtin.SingleHudModule) appelle {@link #register}/
 * {@link #unregister} pour que son propre élément apparaisse ou non dans
 * UiHudEditorScreen et HudOverlayRenderer, typiquement en fonction de son
 * propre état activé/désactivé. Vide par défaut — aucune donnée factice ni
 * entrée figée en dur ici, tout vient des modules (runtime.modules.builtin :
 * FpsModule/PingModule/CoordsModule/etc., voir leur ContentSource nichée).
 */
public final class HudRegistry {
    private HudRegistry() {}

    private static final List<HudElement> ELEMENTS = new ArrayList<>();

    public static List<HudElement> elements() { return ELEMENTS; }

    public static void register(HudElement element) { ELEMENTS.add(element); }

    public static void unregister(HudElement element) { ELEMENTS.remove(element); }
}
