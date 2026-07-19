package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiColorPicker;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiDropdown;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiKeybindButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSlider;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Construit les lignes de réglage d'une page de config PAR RÉFLEXION à
 * partir des champs annotés (@ConfigToggle/@ConfigSlider/@ConfigColor/
 * @ConfigDropdown/@ConfigKeybind) d'un {@link LauncherModule} — équivalent
 * structurel de la génération de page OneConfig à partir d'une classe Config
 * annotée. {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.UiModConfigScreen}
 * ne connaît plus AUCUN module en particulier : un futur module n'a qu'à
 * déclarer ses champs, jamais de code d'écran.
 *
 * Catégories groupées dans l'ordre de PREMIÈRE apparition des champs (ordre
 * de déclaration, {@link Class#getDeclaredFields()}) — pas de liste figée à
 * l'avance : un module qui n'utilise qu'une seule catégorie n'affiche qu'un
 * seul onglet.
 */
public final class ConfigScreenBuilder {
    private ConfigScreenBuilder() {}

    // Non final — recalculées à chaque build() depuis UiTheme.UI_SCALE
    // (réglage "Taille de l'interface", voir GlobalUiSettings) : cette classe
    // est un utilitaire 100% statique (jamais instanciée), donc pas de champ
    // d'instance possible comme pour UiSlider/UiToggle/UiColorPicker.
    private static float ROW_H = 34f, ROW_GAP = 8f;
    private static float LABEL_SCALE = 0.5f;

    public static LinkedHashMap<String, List<UiWidget>> build(LauncherModule module, float x, float w) {
        return buildGrouped(module, x, w, null, null).byCategory;
    }

    /** Regroupement brut (catégorie -> lignes, PLUS le curseur local final de chaque catégorie) — partagé par {@link #build} (pages séparées, voir UiModGroupConfigScreen) et {@link #buildContinuous} (liste continue, voir UiModConfigScreen). */
    private static final class Grouped {
        final LinkedHashMap<String, List<UiWidget>> byCategory;
        final LinkedHashMap<String, Float> finalCursors;
        Grouped(LinkedHashMap<String, List<UiWidget>> byCategory, LinkedHashMap<String, Float> finalCursors) {
            this.byCategory = byCategory;
            this.finalCursors = finalCursors;
        }
    }

    /**
     * {@code headerActions}/{@code headerActionLabels} — {@code null} pour
     * {@link #build} (comportement legacy inchangé, voir UiModGroupConfigScreen :
     * "Réinitialiser la position" reste une ligne normale). Non-null
     * seulement depuis {@link #buildContinuous}, qui déplace cette action
     * DANS l'en-tête de section au lieu d'une ligne dédiée (demande
     * explicite : "le bouton réinitialiser la position, place le dans le
     * titre HUD pour tout les hud") — voir {@code addHudElementRows}.
     */
    private static Grouped buildGrouped(LauncherModule module, float x, float w,
                                         LinkedHashMap<String, Runnable> headerActions,
                                         LinkedHashMap<String, String> headerActionLabels) {
        ROW_H = UiTheme.scaled(34f);
        ROW_GAP = UiTheme.scaled(8f);
        LABEL_SCALE = UiTheme.scaled(0.5f);

        LinkedHashMap<String, List<UiWidget>> byCategory = new LinkedHashMap<>();
        LinkedHashMap<String, Float> cursors = new LinkedHashMap<>();

        // Réglages génériques façon OneConfig (verrouillage, échelle, marges,
        // reset position) — PAS des champs annotés du module, directement
        // sur le HudElement lui-même : ajoutés EN PREMIER (catégorie "HUD"),
        // avant les éventuels réglages propres au module (voir HudElementOwner).
        if (module instanceof HudElementOwner) {
            addHudElementRows(byCategory, cursors, ((HudElementOwner) module).hudElement(), module, x, w,
                headerActions, headerActionLabels);
        }

        for (Field field : module.getClass().getDeclaredFields()) {
            String category = categoryOf(field);
            if (category == null) continue;
            field.setAccessible(true);

            List<UiWidget> rows = byCategory.computeIfAbsent(category, k -> new ArrayList<>());
            float cursor = cursors.getOrDefault(category, 0f);
            cursor = addRow(rows, field, module, x, w, cursor);
            cursors.put(category, cursor);
        }
        return new Grouped(byCategory, cursors);
    }

    // Dimensions d'en-tête de section EXPOSÉES (voir sectionHeader/sectionHeaderHeight
    // ci-dessous) — réutilisées telles quelles par UiModGroupConfigScreen pour
    // que ses onglets (groupes de modules, pas des catégories d'un seul
    // module) s'enchaînent en liste continue avec EXACTEMENT le même
    // espacement visuel que UiModConfigScreen, sans dupliquer ces constantes.
    // Hauteur agrandie (retour utilisateur : "le toggle est serré") — 30->40,
    // le toggle intégré (24px de haut) n'avait que 3px de marge haut/bas.
    public static float sectionHeaderHeight() { return UiTheme.scaled(40f); }
    public static float sectionGapBeforeHeight() { return UiTheme.scaled(22f); }
    public static float headerToRowGapHeight() { return UiTheme.scaled(10f); }

    /** En-tête de section SANS action — voir {@link SectionHeader}, exposé pour UiModGroupConfigScreen (ses "onglets" n'ont jamais de bouton d'action intégré, contrairement à la catégorie "HUD" d'un module seul). */
    public static UiWidget sectionHeader(float x, float y, float w, float h, String label) {
        return new SectionHeader(x, y, w, h, label, null, null);
    }

    /**
     * En-tête de section avec un TOGGLE intégré au lieu d'un bouton d'action
     * (demande explicite : "tout les paramètre activé avec un toggle enlève
     * les pour mettre juste le toggle a côté du titre") — utilisé par
     * UiModGroupConfigScreen pour les onglets À UN SEUL module : le toggle
     * "Activé" de ce module vient ici, dans SON en-tête de section, au lieu
     * d'occuper sa propre ligne dans le contenu.
     */
    public static UiWidget sectionHeaderWithToggle(float x, float y, float w, float h, String label,
                                                    boolean initialToggle, Consumer<Boolean> onToggle) {
        return new SectionHeader(x, y, w, h, label, initialToggle, onToggle);
    }

    /** Résultat de {@link #buildContinuous} — une SEULE liste (en-têtes de section + lignes déjà enchaînées, plus de pages séparées) + l'ancre de défilement de chaque catégorie (voir {@code UiScrollContainer#scrollToAnchor}). */
    public static final class Result {
        public final List<UiWidget> rows;
        public final LinkedHashMap<String, Float> anchors;
        Result(List<UiWidget> rows, LinkedHashMap<String, Float> anchors) {
            this.rows = rows;
            this.anchors = anchors;
        }
    }

    /**
     * Variante "liste continue" (demandée explicitement : "tous les
     * paramètres sont dans la même liste scrollable et les catégories
     * servent à descendre jusqu'au groupe concerné") — CHAQUE catégorie
     * garde son propre curseur LOCAL partant de 0 (réutilise {@link
     * #buildGrouped} tel quel, aucune duplication de la logique de ligne),
     * mais au lieu d'être affichées comme des PAGES séparées, toutes les
     * catégories sont enchaînées les unes sous les autres dans UNE seule
     * liste — chaque widget est décalé (voir {@code UiWidget.y}, public
     * mutable, même motif que UiModGroupConfigScreen) du curseur GLOBAL
     * courant, précédé d'un en-tête de section ({@link SectionHeader}).
     * L'ancre retournée pour chaque catégorie est le bord HAUT de son
     * en-tête, en espace "baseY" (voir {@code UiScrollContainer#add}) — à
     * passer tel quel à {@code scrollToAnchor}.
     */
    public static Result buildContinuous(LauncherModule module, float x, float w) {
        LinkedHashMap<String, Runnable> headerActions = new LinkedHashMap<>();
        LinkedHashMap<String, String> headerActionLabels = new LinkedHashMap<>();
        Grouped grouped = buildGrouped(module, x, w, headerActions, headerActionLabels);
        float headerH = sectionHeaderHeight();
        float sectionGapBefore = sectionGapBeforeHeight();
        float headerToRowGap = headerToRowGapHeight();

        List<UiWidget> combined = new ArrayList<>();
        LinkedHashMap<String, Float> anchors = new LinkedHashMap<>();
        float globalCursor = 0f;
        boolean first = true;
        for (java.util.Map.Entry<String, List<UiWidget>> entry : grouped.byCategory.entrySet()) {
            String category = entry.getKey();
            List<UiWidget> categoryRows = entry.getValue();
            if (categoryRows.isEmpty()) continue;

            if (!first) globalCursor -= sectionGapBefore;
            first = false;

            globalCursor -= headerH;
            float headerY = globalCursor;
            combined.add(new SectionHeader(x, headerY, w, headerH, category,
                headerActionLabels.get(category), headerActions.get(category)));
            anchors.put(category, headerY + headerH);

            globalCursor -= headerToRowGap;
            float shift = globalCursor;
            for (UiWidget rw : categoryRows) {
                rw.y += shift;
                combined.add(rw);
            }
            globalCursor = shift + grouped.finalCursors.getOrDefault(category, 0f);
        }
        return new Result(combined, anchors);
    }

    /**
     * En-tête de section pleine largeur (bold + liseré d'accent) — ce que
     * {@link #buildContinuous} insère entre deux groupes de réglages
     * maintenant qu'ils partagent tous la même liste scrollable, à la place
     * des pages séparées d'avant. Ombre portée sur le texte (voir {@link
     * UiRenderer#drawTextShadowed}) : ces en-têtes se retrouvent sur un fond
     * d'écran désormais TRANSPARENT (voir UiModConfigScreen#overlayColor),
     * contrairement à l'ancien fond de carte quasi-opaque qui garantissait
     * déjà le contraste sans y penser.
     *
     * {@code actionLabel}/{@code action} optionnels (null = pas de bouton) —
     * seule la catégorie "HUD" en fournit un actuellement ("Réinitialiser la
     * position", voir addHudElementRows). {@code contains()} est restreint à
     * la zone du bouton (même motif que ResultCard dans ModrinthContentScreen :
     * le header lui-même n'a aucune action au clic, seul son bouton en a une).
     */
    private static final class SectionHeader extends UiWidget {
        private final String label;
        private final String actionLabel;
        private final Runnable action;
        private final com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat actionHoverAnim;
        // Toggle intégré (voir sectionHeaderWithToggle) — MUTUELLEMENT
        // EXCLUSIF avec action/actionLabel (jamais les deux en même temps
        // dans l'usage actuel) : réutilise le VRAI widget UiToggle (pas une
        // réimplémentation du switch ici) pour un rendu/une animation
        // identiques à toutes les autres cases à cocher de l'appli — sa
        // position est recalée à chaque frame dans draw() pour rester
        // ancrée à droite de CET en-tête précis.
        private final UiToggle toggle;

        SectionHeader(float x, float y, float w, float h, String label, String actionLabel, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.actionLabel = actionLabel;
            this.action = action;
            this.actionHoverAnim = action != null
                ? new com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat(0f, 16f) : null;
            this.toggle = null;
        }

        SectionHeader(float x, float y, float w, float h, String label, boolean initialToggle, Consumer<Boolean> onToggle) {
            super(x, y, w, h);
            this.label = label;
            this.actionLabel = null;
            this.action = null;
            this.actionHoverAnim = null;
            this.toggle = new UiToggle(0, 0, initialToggle, onToggle);
        }

        private float actionW() { return UiTheme.scaled(160f); }
        private float actionH() { return UiTheme.scaled(22f); }
        // Marge droite agrandie (retour utilisateur : "le toggle est serré") — 8->14.
        private float actionX() { return x + w - actionW() - UiTheme.scaled(14f); }
        private float actionY() { return y + (h - actionH()) / 2f; }

        private void layoutToggle() {
            // Marge droite agrandie (même retour) — 10->16.
            toggle.x = x + w - toggle.w - UiTheme.scaled(16f);
            toggle.y = y + (h - toggle.h) / 2f;
        }

        @Override
        public boolean contains(double mx, double my) {
            if (toggle != null) {
                layoutToggle();
                return toggle.contains(mx, my);
            }
            if (action == null) return false;
            return mx >= actionX() && mx <= actionX() + actionW() && my >= actionY() && my <= actionY() + actionH();
        }

        @Override
        public void onClick() {
            if (toggle != null) { toggle.onClick(); return; }
            if (action != null) action.run();
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                UiTheme.PANEL_BG_ALT.multiplyAlpha(0.82f * clipFade), vpWidth, vpHeight);
            float barW = UiTheme.scaled(3f), barInset = UiTheme.scaled(3f);
            renderer.drawRoundedRect(x, y + barInset, x + barW, y + h - barInset, barW / 2f,
                UiTheme.ACCENT.multiplyAlpha(clipFade), vpWidth, vpHeight);
            float scale = UiTheme.scaled(0.5f);
            renderer.drawTextShadowed(UiFont.BOLD, label, x + barW + UiTheme.scaled(10f), y + h / 2f - UiTheme.scaled(6f),
                UiTheme.TEXT_PRIMARY.multiplyAlpha(clipFade), new UiColor(0, 0, 0, 150).multiplyAlpha(clipFade),
                scale, scale, scale, vpWidth, vpHeight);

            if (toggle != null) {
                layoutToggle();
                toggle.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
                return;
            }

            if (action != null) {
                actionHoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
                UiColor btnColor = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, actionHoverAnim.get());
                renderer.drawRoundedRect(actionX(), actionY(), actionX() + actionW(), actionY() + actionH(),
                    UiTheme.RADIUS_SM, btnColor.multiplyAlpha(clipFade), vpWidth, vpHeight);
                float actionScale = UiTheme.scaled(0.4f);
                float tw = renderer.textWidth(actionLabel, actionScale);
                renderer.drawText(actionLabel, actionX() + (actionW() - tw) / 2f, actionY() + actionH() / 2f - UiTheme.scaled(4f),
                    UiTheme.TEXT_PRIMARY.multiplyAlpha(clipFade), actionScale, vpWidth, vpHeight);
            }
        }
    }

    private static void addHudElementRows(LinkedHashMap<String, List<UiWidget>> byCategory, LinkedHashMap<String, Float> cursors,
                                           HudElement element, LauncherModule module, float x, float w,
                                           LinkedHashMap<String, Runnable> headerActions, LinkedHashMap<String, String> headerActionLabels) {
        String category = "HUD";
        List<UiWidget> rows = byCategory.computeIfAbsent(category, k -> new ArrayList<>());
        float cursor = 0f;

        cursor = toggleRow(rows, x, w, cursor, "Verrouillé",
            "Empêche de déplacer/redimensionner cet élément dans l'éditeur de HUD.",
            element.locked, v -> { element.locked = v; module.onConfigChanged(); HudConfigStore.save(); });
        cursor = toggleRow(rows, x, w, cursor, "Afficher même avec un écran ouvert",
            "Reste visible pendant le chat, l'inventaire ou tout autre écran (sauf nos propres menus).",
            element.showWhenScreenOpen, v -> { element.showWhenScreenOpen = v; module.onConfigChanged(); HudConfigStore.save(); });
        cursor = sliderRow(rows, x, w, cursor, "Échelle",
            "Taille de toute la boîte (largeur ET hauteur ensemble, jamais l'une sans l'autre).",
            HudElement.MIN_SCALE, HudElement.MAX_SCALE, 0.05f, element.scale, v -> { element.setScale(v); module.onConfigChanged(); HudConfigStore.save(); });
        cursor = sliderRow(rows, x, w, cursor, "Marge horizontale",
            "Espace entre le bord de la boîte et le contenu (X).",
            0f, 20f, 1f, element.paddingX, v -> { element.paddingX = v; module.onConfigChanged(); HudConfigStore.save(); });
        cursor = sliderRow(rows, x, w, cursor, "Marge verticale",
            "Espace entre le bord de la boîte et le contenu (Y).",
            0f, 20f, 1f, element.paddingY, v -> { element.paddingY = v; module.onConfigChanged(); HudConfigStore.save(); });

        // Réinitialise anchor/offset (voir HudElement.resetPosition) — devait
        // AUSSI persister le résultat, sinon le prochain redémarrage ramenait
        // la position "sauvegardée" précédente au lieu du reset qu'on vient
        // de demander (oublié avant ce correctif, aucun onConfigChanged/save
        // n'était appelé après element::resetPosition).
        Runnable resetAction = () -> { element.resetPosition(); module.onConfigChanged(); HudConfigStore.save(); };
        if (headerActions != null) {
            // buildContinuous (demande explicite : "le bouton réinitialiser
            // la position, place le dans le titre HUD pour tout les hud") —
            // déplacé DANS l'en-tête de section, plus de ligne dédiée.
            headerActions.put(category, resetAction);
            headerActionLabels.put(category, "Réinitialiser la position");
        } else {
            // build() legacy (voir UiModGroupConfigScreen, pas d'en-tête de
            // section dans ce mode) — comportement inchangé, ligne normale.
            cursor = buttonRow(rows, x, w, cursor, "Réinitialiser la position", resetAction);
        }

        cursors.put(category, cursor);
    }

    private static String categoryOf(Field field) {
        if (field.isAnnotationPresent(ConfigToggle.class)) return field.getAnnotation(ConfigToggle.class).category();
        if (field.isAnnotationPresent(ConfigSlider.class)) return field.getAnnotation(ConfigSlider.class).category();
        if (field.isAnnotationPresent(ConfigColor.class)) return field.getAnnotation(ConfigColor.class).category();
        if (field.isAnnotationPresent(ConfigDropdown.class)) return field.getAnnotation(ConfigDropdown.class).category();
        if (field.isAnnotationPresent(ConfigKeybind.class)) return field.getAnnotation(ConfigKeybind.class).category();
        return null;
    }

    private static float addRow(List<UiWidget> rows, Field field, LauncherModule module, float x, float w, float cursor) {
        if (field.isAnnotationPresent(ConfigToggle.class)) {
            ConfigToggle a = field.getAnnotation(ConfigToggle.class);
            return toggleRow(rows, x, w, cursor, a.name(), a.description(), getBoolean(field, module),
                v -> { setBoolean(field, module, v); module.onConfigChanged(); HudConfigStore.save(); });
        }
        if (field.isAnnotationPresent(ConfigSlider.class)) {
            ConfigSlider a = field.getAnnotation(ConfigSlider.class);
            return sliderRow(rows, x, w, cursor, a.name(), a.description(), a.min(), a.max(), a.step(), getFloat(field, module),
                v -> { setFloat(field, module, v); module.onConfigChanged(); HudConfigStore.save(); });
        }
        if (field.isAnnotationPresent(ConfigColor.class)) {
            ConfigColor a = field.getAnnotation(ConfigColor.class);
            return colorRow(rows, x, w, cursor, a.name(), a.description(), getColor(field, module),
                v -> { setColor(field, module, v); module.onConfigChanged(); HudConfigStore.save(); });
        }
        if (field.isAnnotationPresent(ConfigDropdown.class)) {
            ConfigDropdown a = field.getAnnotation(ConfigDropdown.class);
            return dropdownRow(rows, x, w, cursor, a.name(), a.description(), Arrays.asList(a.options()), getInt(field, module),
                v -> { setInt(field, module, v); module.onConfigChanged(); HudConfigStore.save(); });
        }
        if (field.isAnnotationPresent(ConfigKeybind.class)) {
            ConfigKeybind a = field.getAnnotation(ConfigKeybind.class);
            return keybindRow(rows, x, w, cursor, a.name(), a.description(), getString(field, module),
                v -> { setString(field, module, v); module.onConfigChanged(); HudConfigStore.save(); });
        }
        return cursor;
    }

    // ── Lignes — mêmes proportions que l'ancien UiModConfigScreen codé en dur ──

    /**
     * Fond discret PAR LIGNE (voir audit + retour utilisateur : "pas un
     * carré qui entoure tout les paramètres mais 1 rectangle par
     * paramètre") — juste assez sombre pour distancier la ligne du jeu
     * transparent derrière (voir UiModConfigScreen), sans redevenir une
     * carte pleine qui bloquerait toute la visibilité du monde. Hauteur
     * TOUJOURS {@link #ROW_H} (pas la hauteur totale réservée par le
     * curseur pour les lignes qui ouvrent un panneau, ex. colorRow/
     * dropdownRow) : ce panneau déroulé dessine déjà son propre fond une
     * fois ouvert, un second fond plus grand ici ferait doublon.
     */
    private static final class RowBackground extends UiWidget {
        RowBackground(float x, float y, float w, float h) { super(x, y, w, h); }

        /**
         * BUG TROUVÉ (retour utilisateur : "le z-order des toggle/bouton
         * n'est plus bon") : purement décoratif, mais ajouté EN PREMIER dans
         * la liste de sa ligne (voir addRowBackground) — {@code
         * UiScrollContainer.pollInput()} dispatche un clic au PREMIER widget
         * de la liste dont {@code contains()} matche, et le {@code
         * contains()} PAR DÉFAUT de UiWidget couvre TOUTE la ligne (même
         * zone que le toggle/bouton dessiné par-dessus, à droite). Ce fond,
         * arrivé avant eux dans la liste, interceptait donc systématiquement
         * le clic à leur place (son propre onClick(), jamais surchargé, ne
         * faisant rien) — sans jamais changer le RENDU (toujours dessiné
         * derrière), seule la détection de clic était cassée. UiSlider,
         * lui, n'est pas touché : son drag passe par pollContinuous() (son
         * PROPRE contains(), jamais par ce dispatch partagé "premier qui
         * matche"). Fix : ce fond ne doit JAMAIS être cliquable.
         */
        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                new UiColor(0, 0, 0, 60).multiplyAlpha(clipFade), vpWidth, vpHeight);
        }
    }

    /** Ajouté EN PREMIER dans la liste de chaque ligne (voir buildContinuous/UiModConfigScreen : l'ordre d'insertion pilote le z-order) — sinon le fond dessinerait PAR-DESSUS le label/contrôle de sa propre ligne. */
    private static void addRowBackground(List<UiWidget> rows, float x, float w, float rowY) {
        rows.add(new RowBackground(x, rowY, w, ROW_H));
    }

    private static float rowLabel(List<UiWidget> rows, float x, float w, float rowY, String label, String tooltip) {
        addRowBackground(rows, x, w, rowY);
        rows.add(new UiLabel(x + UiTheme.scaled(10f), rowY + ROW_H / 2f - UiTheme.scaled(5f), label, UiTheme.TEXT_PRIMARY, LABEL_SCALE).tooltip(tooltip));
        return rowY;
    }

    private static float toggleRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                    boolean initial, Consumer<Boolean> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float toggleH = UiTheme.scaled(24f);
        rows.add(new UiToggle(x + w - UiTheme.scaled(44f) - UiTheme.scaled(10f), rowY + (ROW_H - toggleH) / 2f, initial, onChange));
        return rowY - ROW_GAP;
    }

    private static float sliderRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                    float min, float max, float step, float initial, Consumer<Float> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float sliderW = UiTheme.scaled(190f);
        rows.add(new UiSlider(x + w - sliderW - UiTheme.scaled(10f), rowY + (ROW_H - UiTheme.scaled(20f)) / 2f, sliderW, min, max, step, initial, onChange));
        return rowY - ROW_GAP;
    }

    private static float colorRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                   UiColor initial, Consumer<UiColor> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        rows.add(new UiColorPicker(x + w - UiTheme.scaled(42f) - UiTheme.scaled(10f), rowY + (ROW_H - UiTheme.scaled(26f)) / 2f, initial, onChange));
        // Marge supplémentaire : le panneau déroulant du color picker s'ouvre vers le bas.
        return rowY - ROW_GAP - UiTheme.scaled(115f);
    }

    private static float dropdownRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                      List<String> options, int initialIndex, IntConsumer onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float dw = UiTheme.scaled(170f);
        float ddH = UiTheme.scaled(26f);
        rows.add(new UiDropdown(x + w - dw - UiTheme.scaled(10f), rowY + (ROW_H - ddH) / 2f, dw, ddH, options, initialIndex, onChange));
        // Même raison que colorRow : réserve la hauteur du panneau déroulé.
        return rowY - ROW_GAP - (options.size() * ddH + UiTheme.scaled(10f));
    }

    private static float keybindRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                     String initialKey, Consumer<String> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float kw = UiTheme.scaled(110f);
        float kh = UiTheme.scaled(26f);
        rows.add(new UiKeybindButton(x + w - kw - UiTheme.scaled(10f), rowY + (ROW_H - kh) / 2f, kw, kh, initialKey, onChange));
        return rowY - ROW_GAP;
    }

    /** Ligne bouton seul (pas de label à gauche, ex: "Réinitialiser la position") — pas de valeur associée, juste une action. */
    private static float buttonRow(List<UiWidget> rows, float x, float w, float cursor, String label, Runnable action) {
        float rowY = cursor - ROW_H;
        addRowBackground(rows, x, w, rowY);
        float bw = UiTheme.scaled(190f);
        float bh = UiTheme.scaled(28f);
        rows.add(new UiButton(x + w - bw - UiTheme.scaled(10f), rowY + (ROW_H - bh) / 2f, bw, bh, label, action));
        return rowY - ROW_GAP;
    }

    // ── Accès réflexion — silencieux (throw impossible côté auteur de module : types validés par convention d'annotation) ──

    private static boolean getBoolean(Field f, Object o) { try { return f.getBoolean(o); } catch (Exception e) { return false; } }
    private static void setBoolean(Field f, Object o, boolean v) { try { f.setBoolean(o, v); } catch (Exception ignored) {} }

    private static float getFloat(Field f, Object o) { try { return f.getFloat(o); } catch (Exception e) { return 0f; } }
    private static void setFloat(Field f, Object o, float v) { try { f.setFloat(o, v); } catch (Exception ignored) {} }

    private static int getInt(Field f, Object o) { try { return f.getInt(o); } catch (Exception e) { return 0; } }
    private static void setInt(Field f, Object o, int v) { try { f.setInt(o, v); } catch (Exception ignored) {} }

    private static String getString(Field f, Object o) { try { return (String) f.get(o); } catch (Exception e) { return ""; } }
    private static void setString(Field f, Object o, String v) { try { f.set(o, v); } catch (Exception ignored) {} }

    private static UiColor getColor(Field f, Object o) { try { return (UiColor) f.get(o); } catch (Exception e) { return UiColor.TRANSPARENT; } }
    private static void setColor(Field f, Object o, UiColor v) { try { f.set(o, v); } catch (Exception ignored) {} }
}
