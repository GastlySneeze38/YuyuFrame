package com.yuyuframe.launcheragent.runtime.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Registre global des éléments HUD déplaçables — un module (voir
 * runtime.modules.LauncherModule) appelle {@link #register}/{@link #unregister}
 * pour que son propre élément apparaisse ou non dans UiHudEditorScreen et
 * HudOverlayRenderer, typiquement en fonction de son propre état activé/
 * désactivé (voir runtime.modules.builtin.VanillaHudModule pour l'exemple :
 * fps/coords/ping, portés depuis PvP-Mod). Vide par défaut — aucune donnée
 * factice ni entrée figée en dur ici, tout vient des modules.
 */
public final class HudRegistry {
    private HudRegistry() {}

    private static final List<HudElement> ELEMENTS = new ArrayList<>();

    public static List<HudElement> elements() { return ELEMENTS; }

    public static void register(HudElement element) { ELEMENTS.add(element); }

    public static void unregister(HudElement element) { ELEMENTS.remove(element); }
}
