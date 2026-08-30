package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudRegistry;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Base commune des modules qui n'ajoutent RIEN d'autre qu'un unique élément
 * HUD (FPS/Ping/Coordonnées/Keystrokes/Potions/Armure — chacun sa propre
 * carte, comme dans PvP-Mod où chaque HUD est sa propre Config/Mod) —
 * (dés)enregistre l'élément dans {@link HudRegistry} au gré du toggle de la
 * carte, une seule fois, ici. Implémente {@link HudElementOwner} : ses
 * réglages génériques (verrouillage, échelle, marges, reset position)
 * apparaissent donc automatiquement dans la config, voir ConfigScreenBuilder.
 *
 * ⚠️ Constructeur 4-arg (sans {@code HookPoint}) LAISSÉ tel quel (2026-08-25,
 * §19 — audit modules, retour utilisateur) : il ne relayait AUCUN HookPoint à
 * {@link LauncherModule}, rendant IMPOSSIBLE pour un sous-type de supprimer
 * l'affichage vanilla qu'il duplique — bug réel trouvé sur
 * {@code ArmorDurabilityModule}/{@code PotionEffectsModule}, qui dessinent
 * PAR-DESSUS l'armure/les effets vanilla natifs au lieu de les remplacer (les
 * mixins {@code HUD_EXTRACT_ARMOR}/{@code HUD_EXTRACT_EFFECTS} existaient déjà,
 * simplement jamais consultés). Le nouveau constructeur 5-arg comble ce trou
 * SANS toucher les autres (FPS/Ping/Coords/Keystrokes/Saturation — aucun
 * équivalent vanilla à supprimer, rien à câbler).
 */
abstract class SingleHudModule extends LauncherModule implements HudElementOwner {

    private final HudElement element;

    protected SingleHudModule(String id, String name, String description, boolean enabledByDefault, HudElement element) {
        super(id, name, description, enabledByDefault);
        this.element = element;
        if (enabledByDefault) HudRegistry.register(element);
    }

    protected SingleHudModule(String id, String name, String description, boolean enabledByDefault, HudElement element, HookPoint... hookPoints) {
        super(id, name, description, enabledByDefault, hookPoints);
        this.element = element;
        if (enabledByDefault) HudRegistry.register(element);
    }

    @Override
    public HudElement hudElement() { return element; }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) HudRegistry.register(element);
        else HudRegistry.unregister(element);
    }
}
