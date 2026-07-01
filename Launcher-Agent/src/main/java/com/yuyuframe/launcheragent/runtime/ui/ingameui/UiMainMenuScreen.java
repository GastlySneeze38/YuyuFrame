package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran d'accueil du moteur config custom — équivalent de OneConfigGui.create() :
 * sidebar de navigation à gauche, barre de recherche + grille de cartes mods
 * à droite. Chaque carte a son propre UiToggle (activer/désactiver,
 * enregistré AVANT le widget "corps de carte" dans {@code widgets} pour que
 * le clic sur le toggle gagne le test de collision face au clic "ouvrir la
 * config").
 *
 * Layout entièrement reconstruit (rebuildAll) au redimensionnement de fenêtre
 * ET à chaque frappe dans la recherche — sans jamais perdre d'état, car :
 * (a) l'état "activé/désactivé" d'un mod vit dans {@link ModEntry} (externe
 * aux widgets, jamais recréé), (b) {@code searchField} est créé UNE FOIS puis
 * seulement repositionné/ré-ajouté (même instance, donc même texte/focus).
 *
 * Données factices ({@link #MOCK_MODS}) en attendant l'enregistrement réel
 * des mods (YuyuPvP, etc. — voir runtime.ui.modules) — voir docs/LauncherAgent/index.md.
 */
public class UiMainMenuScreen extends UiScreenBase {

    private static final float SIDEBAR_W = 190f;
    private static final float MARGIN = 24f;
    private static final float CARD_GAP = 16f;
    private static final float CARD_H = 76f;
    private static final float SEARCH_H = 24f;

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
    private UiTextField searchField;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;

    public UiMainMenuScreen(Object lastScreen) {
        super("YuyuFrame");
        this.lastScreen = lastScreen;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight)) {
            rebuildAll();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            // Titres par-dessus TOUT (widgets inclus) — le fond de la sidebar,
            // lui, est un widget ordinaire ajouté EN PREMIER dans rebuildAll()
            // (voir SidebarBackground) pour être dessiné AVANT les items de
            // nav par super.uiDraw(), sinon il les recouvrirait entièrement
            // (bug vécu : le bouton "Modifier le HUD" — et "Accueil"/
            // "Parametres" avant lui, juste jamais remarqué — invisibles
            // derrière ce fond dessiné après coup).
            renderer.drawText(UiFont.BOLD, "YuyuFrame", MARGIN, screenHeight - 40, UiTheme.TEXT_PRIMARY, 0.55f, screenWidth, screenHeight);
            renderer.drawText("Mods installes", SIDEBAR_W + MARGIN, screenHeight - 40, UiTheme.TEXT_SECONDARY, 0.42f, screenWidth, screenHeight);
        } catch (Throwable ignored) {}
    }

    private void rebuildAll() {
        widgets.clear();

        widgets.add(new SidebarBackground());
        widgets.add(new SidebarItem(MARGIN, screenHeight - 84, SIDEBAR_W - MARGIN * 2, "Accueil", true, null));
        widgets.add(new SidebarItem(MARGIN, screenHeight - 114, SIDEBAR_W - MARGIN * 2, "Parametres", false, null));
        // Épinglé en bas de la sidebar (pas empilé sous les items du haut) —
        // même position quel que soit le nombre d'items ajoutés au-dessus.
        widgets.add(new SidebarItem(MARGIN, MARGIN, SIDEBAR_W - MARGIN * 2, "Modifier le HUD", false,
            () -> closeTo(new UiHudEditorScreen(UiMainMenuScreen.this))));
        widgets.add(new CloseButton(screenWidth - 24f - 28f, screenHeight - 24f - 28f));

        float contentX = SIDEBAR_W + MARGIN;
        float contentW = screenWidth - contentX - MARGIN;

        if (searchField == null) {
            // Recréé la grille (pas tout l'écran) à chaque frappe — même
            // instance de champ conservée, voir javadoc de la classe.
            searchField = new UiTextField(0, 0, 0, 0, "Rechercher un mod...", v -> rebuildAll());
        }
        searchField.x = contentX;
        searchField.y = screenHeight - 64f - SEARCH_H;
        searchField.w = Math.min(240f, contentW);
        searchField.h = SEARCH_H;
        widgets.add(searchField);

        String filter = searchField.text().trim().toLowerCase(Locale.ROOT);
        List<ModEntry> filtered = new ArrayList<>();
        for (ModEntry m : MOCK_MODS) {
            if (filter.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(m);
        }

        float cardW = (contentW - CARD_GAP) / 2f;
        float top = searchField.y - 24f;

        for (int i = 0; i < filtered.size(); i++) {
            ModEntry mod = filtered.get(i);
            int col = i % 2, row = i / 2;
            float cx = contentX + col * (cardW + CARD_GAP);
            float cy = top - row * (CARD_H + CARD_GAP) - CARD_H;

            UiToggle toggle = new UiToggle(cx + cardW - 34f - 12f, cy + CARD_H - 18f - 10f, mod.enabled, v -> mod.enabled = v);
            widgets.add(toggle); // AVANT la carte : gagne le hit-test sur sa propre zone
            widgets.add(new ModCard(cx, cy, cardW, mod));
        }
    }

    /** Bande de fond de la sidebar — widget ordinaire (pas un dessin manuel après coup) pour rester DERRIÈRE les items de nav ajoutés après elle. */
    private final class SidebarBackground extends UiWidget {
        SidebarBackground() { super(0, 0, SIDEBAR_W, screenHeight); }

        // Jamais cliquable : sans ce override, son rectangle (toute la
        // sidebar) gagnerait le test de collision AVANT les vrais boutons
        // ajoutés après elle dans la liste (premier widget dont contains()
        // matche = celui qui reçoit onClick, voir UiScreenBase) — "Accueil"/
        // "Parametres"/"Modifier le HUD" ne recevaient donc jamais leur clic.
        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(0, 0, SIDEBAR_W, vpHeight, 0, UiTheme.SIDEBAR_BG, vpWidth, vpHeight);
        }
    }

    /** Item de nav sidebar — "action" null = purement visuel (ex: "Parametres", pas encore de page derriere). */
    private final class SidebarItem extends UiWidget {
        private final String label;
        private final boolean active;
        private final Runnable action;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        SidebarItem(float x, float y, float w, String label, boolean active, Runnable action) {
            super(x, y, w, 26f);
            this.label = label;
            this.active = active;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
                renderer.drawRoundedRect(x, y + 3, x + 3, y + h - 3, 1.5f, UiTheme.ACCENT, vpWidth, vpHeight);
            } else {
                UiColor bg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hoverAnim.get());
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            }
            UiColor textColor = active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED;
            renderer.drawText(label, x + 12, y + h / 2f - 4f, textColor, 0.4f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            if (action != null) action.run();
        }
    }

    private final class ModCard extends UiWidget {
        private final ModEntry mod;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        ModCard(float x, float y, float w, ModEntry mod) {
            super(x, y, w, CARD_H);
            this.mod = mod;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, bg, vpWidth, vpHeight);

            // Pastille icone (initiale du mod) — pas d'image reelle en attendant les icones mods.
            float iconSize = 36f;
            renderer.drawRoundedRect(x + 12, y + h - iconSize - 12, x + 12 + iconSize, y + h - 12,
                UiTheme.RADIUS_SM, UiTheme.ACCENT_DIM, vpWidth, vpHeight);
            String initial = mod.name.substring(0, 1).toUpperCase(Locale.ROOT);
            float iw = renderer.textWidth(initial, 0.5f);
            renderer.drawText(initial, x + 12 + (iconSize - iw) / 2f, y + h - 12 - iconSize / 2f - 6f, UiTheme.ACCENT, 0.5f, vpWidth, vpHeight);

            float textX = x + 12 + iconSize + 12;
            renderer.drawText(mod.name, textX, y + h - 26, UiTheme.TEXT_PRIMARY, 0.42f, vpWidth, vpHeight);
            renderer.drawText(mod.desc, textX, y + h - 46, UiTheme.TEXT_SECONDARY, 0.4f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            closeTo(new UiModConfigScreen(UiMainMenuScreen.this, mod.name));
        }
    }

    private final class CloseButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        CloseButton(float x, float y) { super(x, y, 28f, 28f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            String label = "x";
            float tw = renderer.textWidth(label, 0.45f);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 5f, UiTheme.TEXT_SECONDARY, 0.45f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
