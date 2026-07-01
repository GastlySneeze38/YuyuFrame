package com.yuyuframe.launcheragent.screen;

import com.yuyuframe.launcheragent.runtime.ui.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.UiToggle;
import com.yuyuframe.launcheragent.runtime.ui.UiWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran d'accueil du moteur config custom — équivalent de OneConfigGui.create() :
 * sidebar de navigation à gauche, grille de cartes mods à droite. Chaque
 * carte a son propre UiToggle (activer/désactiver, enregistré AVANT le
 * widget "corps de carte" dans {@code widgets} pour que le clic sur le
 * toggle gagne le test de collision face au clic "ouvrir la config").
 *
 * Données factices ({@link #MOCK_MODS}) en attendant l'enregistrement réel
 * des mods (YuyuPvP, etc.) — voir docs/LauncherAgent/index.md.
 */
public class UiMainMenuScreen extends UiScreenBase {

    private static final float SIDEBAR_W = 190f;
    private static final float MARGIN = 24f;
    private static final float CARD_GAP = 16f;
    private static final float CARD_H = 76f;

    private static final class ModEntry {
        final String name, desc;
        boolean enabled;
        ModEntry(String name, String desc, boolean enabled) { this.name = name; this.desc = desc; this.enabled = enabled; }
    }

    private static final List<ModEntry> MOCK_MODS = new ArrayList<>();
    static {
        MOCK_MODS.add(new ModEntry("YuyuPvP", "Ameliorations PvP competitif", true));
        MOCK_MODS.add(new ModEntry("HUD Custom", "Overlay d'informations en jeu", false));
        MOCK_MODS.add(new ModEntry("Anti Ghost Fix", "Corrections rendu ghost", true));
    }

    private final Object lastScreen;
    private boolean built;

    public UiMainMenuScreen(Object lastScreen) {
        super("YuyuFrame");
        this.lastScreen = lastScreen;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (!built && screenWidth > 0 && screenHeight > 0) {
            buildLayout();
            built = true;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            drawSidebarChrome(renderer);
        } catch (Throwable ignored) {}
    }

    private void drawSidebarChrome(UiRenderer renderer) {
        renderer.drawRoundedRect(0, 0, SIDEBAR_W, screenHeight, 0, UiTheme.SIDEBAR_BG, screenWidth, screenHeight);
        renderer.drawText(UiFont.BOLD, "YuyuFrame", MARGIN, screenHeight - 40, UiTheme.TEXT_PRIMARY, 0.55f, screenWidth, screenHeight);
        renderer.drawText("Mods installes", SIDEBAR_W + MARGIN, screenHeight - 40, UiTheme.TEXT_SECONDARY, 0.42f, screenWidth, screenHeight);
    }

    private void buildLayout() {
        widgets.clear();

        widgets.add(new SidebarItem(MARGIN, screenHeight - 84, SIDEBAR_W - MARGIN * 2, "Accueil", true));
        widgets.add(new SidebarItem(MARGIN, screenHeight - 114, SIDEBAR_W - MARGIN * 2, "Parametres", false));

        widgets.add(new CloseButton(screenWidth - 24f - 28f, screenHeight - 24f - 28f));

        float contentX = SIDEBAR_W + MARGIN;
        float contentW = screenWidth - contentX - MARGIN;
        float cardW = (contentW - CARD_GAP) / 2f;
        float top = screenHeight - 64f;

        for (int i = 0; i < MOCK_MODS.size(); i++) {
            ModEntry mod = MOCK_MODS.get(i);
            int col = i % 2, row = i / 2;
            float cx = contentX + col * (cardW + CARD_GAP);
            float cy = top - row * (CARD_H + CARD_GAP) - CARD_H;

            UiToggle toggle = new UiToggle(cx + cardW - 34f - 12f, cy + CARD_H - 18f - 10f, mod.enabled, v -> mod.enabled = v);
            widgets.add(toggle); // AVANT la carte : gagne le hit-test sur sa propre zone
            widgets.add(new ModCard(cx, cy, cardW, mod));
        }
    }

    /** Item de nav sidebar — purement visuel pour "Parametres" (pas encore de page derriere), actif pour "Accueil" (deja dessus). */
    private final class SidebarItem extends UiWidget {
        private final String label;
        private final boolean active;

        SidebarItem(float x, float y, float w, String label, boolean active) {
            super(x, y, w, 26f);
            this.label = label;
            this.active = active;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean hovered = contains(mouseX, mouseY);
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
                renderer.drawRoundedRect(x, y + 3, x + 3, y + h - 3, 1.5f, UiTheme.ACCENT, vpWidth, vpHeight);
            } else if (hovered) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_HOVER, vpWidth, vpHeight);
            }
            UiColor textColor = active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED;
            renderer.drawText(label, x + 12, y + h / 2f - 4f, textColor, 0.4f, vpWidth, vpHeight);
        }
    }

    private final class ModCard extends UiWidget {
        private final ModEntry mod;

        ModCard(float x, float y, float w, ModEntry mod) {
            super(x, y, w, CARD_H);
            this.mod = mod;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean hovered = contains(mouseX, mouseY);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, hovered ? UiTheme.CARD_HOVER : UiTheme.CARD_BG, vpWidth, vpHeight);

            // Pastille icone (initiale du mod) — pas d'image reelle en attendant les icones mods.
            float iconSize = 36f;
            renderer.drawRoundedRect(x + 12, y + h - iconSize - 12, x + 12 + iconSize, y + h - 12,
                UiTheme.RADIUS_SM, UiTheme.ACCENT_DIM, vpWidth, vpHeight);
            String initial = mod.name.substring(0, 1).toUpperCase(Locale.ROOT);
            float iw = renderer.textWidth(initial, 0.5f);
            renderer.drawText(initial, x + 12 + (iconSize - iw) / 2f, y + h - 12 - iconSize / 2f - 6f, UiTheme.ACCENT, 0.5f, vpWidth, vpHeight);

            float textX = x + 12 + iconSize + 12;
            renderer.drawText(mod.name, textX, y + h - 26, UiTheme.TEXT_PRIMARY, 0.42f, vpWidth, vpHeight);
            renderer.drawText(mod.desc, textX, y + h - 44, UiTheme.TEXT_SECONDARY, 0.32f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            closeTo(new UiModConfigScreen(UiMainMenuScreen.this, mod.name));
        }
    }

    private final class CloseButton extends UiWidget {
        CloseButton(float x, float y) { super(x, y, 28f, 28f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean hovered = contains(mouseX, mouseY);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, hovered ? UiTheme.CARD_HOVER : UiTheme.CARD_BG, vpWidth, vpHeight);
            String label = "x";
            float tw = renderer.textWidth(label, 0.45f);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 5f, UiTheme.TEXT_SECONDARY, 0.45f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
