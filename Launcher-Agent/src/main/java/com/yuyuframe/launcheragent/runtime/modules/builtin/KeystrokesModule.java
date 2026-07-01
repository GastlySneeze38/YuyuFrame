package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.KeystrokesHudRenderer;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigToggle;

/**
 * Port de PvP-Mod KeystrokesConfig/KeystrokesHud — sa propre carte, comme
 * dans la référence.
 *
 * {@code RENDERER} est STATIC (pas un champ d'instance passé par lambda
 * capturant {@code this}) : le renderer doit être construit et passé au
 * {@code super(...)} de {@link SingleHudModule} AVANT que le constructeur de
 * cette classe n'ait fini — javac interdit toute référence à {@code this}
 * (même via lambda) dans les arguments d'un appel super().
 */
public final class KeystrokesModule extends SingleHudModule {

    private static final KeystrokesHudRenderer RENDERER = new KeystrokesHudRenderer();

    @ConfigToggle(name = "Afficher la barre d'espace", category = "Éléments")
    public boolean showSpaceKey = true;

    public KeystrokesModule() {
        super("keystrokes", "Keystrokes", "Touches ZQSD/WASD + espace + CPS", false,
            new HudElement("keystrokes", "Keystrokes", 90f, 110f, HudAnchor.BOTTOM_LEFT, 8f, 8f,
                (HudElement.CustomRenderer) RENDERER));
    }

    @Override
    public void onConfigChanged() {
        RENDERER.showSpaceKey = showSpaceKey;
    }
}
