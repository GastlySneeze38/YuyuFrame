package com.yuyuframe.launcheragent.runtime.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Registre global des éléments HUD déplaçables — un mod appellera
 * {@link #register} pour que son propre élément apparaisse dans
 * UiHudEditorScreen. Entrées factices ci-dessous en attendant qu'un vrai mod
 * (YuyuPvP, HUD Custom...) existe côté gameplay pour s'enregistrer lui-même.
 */
public final class HudRegistry {
    private HudRegistry() {}

    private static final List<HudElement> ELEMENTS = new ArrayList<>();
    static {
        ELEMENTS.add(new HudElement("fps", "FPS", 70f, 24f, HudAnchor.TOP_LEFT, 8f, 8f));
        ELEMENTS.add(new HudElement("coords", "Coordonnées", 130f, 24f, HudAnchor.TOP_LEFT, 8f, 40f));
        ELEMENTS.add(new HudElement("compass", "Boussole", 110f, 24f, HudAnchor.TOP_CENTER, 0f, 8f));
        ELEMENTS.add(new HudElement("ping", "Ping", 70f, 24f, HudAnchor.TOP_RIGHT, 8f, 8f));
    }

    public static List<HudElement> elements() { return ELEMENTS; }

    public static void register(HudElement element) { ELEMENTS.add(element); }
}
