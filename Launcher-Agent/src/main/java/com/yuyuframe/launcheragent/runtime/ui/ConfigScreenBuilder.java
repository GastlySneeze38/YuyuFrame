package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.config.Setting;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiColorPicker;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiDropdown;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiKeybindButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSlider;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Construit les lignes de réglage d'une page de config à partir des
 * {@link Setting} déclarés par un {@link LauncherModule}.
 * {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.UiModConfigScreen}
 * ne connaît AUCUN module en particulier : un module déclare ses réglages,
 * jamais de code d'écran.
 *
 * <p>Ne fait plus AUCUNE réflexion depuis le 2026-08-30 : les réglages
 * étaient auparavant découverts par {@code getDeclaredFields()} + annotations
 * {@code @Config*}, ce qui imposait d'écrire le même mapping annotation→type
 * ici, à la lecture du disque et à l'écriture — voir {@link Setting} pour les
 * quatre défauts que ça entraînait.
 *
 * <p>Catégories groupées dans l'ordre de PREMIÈRE apparition (ordre de
 * déclaration dans {@code settings(SettingList)}) — pas de liste figée à
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

        // Réglages DÉCLARÉS par le module (plus aucune réflexion depuis le
        // 2026-08-30, voir Setting) — ordre d'affichage = ordre de
        // déclaration, onglets créés à la première apparition d'une catégorie.
        for (Setting setting : module.settings()) {
            List<UiWidget> rows = byCategory.computeIfAbsent(setting.category, k -> new ArrayList<>());
            float cursor = cursors.getOrDefault(setting.category, 0f);
            cursor = addRow(rows, setting, module, x, w, cursor);
            cursors.put(setting.category, cursor);
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
    /**
     * En-tête de section portant le toggle d'activation du module ET un CŒUR
     * de favori à sa gauche — utilisé par {@code UiModGroupConfigScreen} pour
     * qu'un module vivant dans un groupe puisse être mis en favori
     * (2026-08-30). Un module non groupé porte déjà son cœur sur sa carte de
     * l'écran d'accueil ; un module groupé n'ayant pas de carte, c'est ici son
     * seul point d'accès.
     *
     * <p>Les deux états sont des {@link BooleanSupplier} LUS À CHAQUE FRAME,
     * pas des booléens figés : le même module peut être basculé depuis sa
     * carte de l'accueil (s'il est favori), et les deux widgets doivent rester
     * d'accord — voir {@code UiToggle#boundTo}.
     */
    public static UiWidget sectionHeaderWithToggleAndFavorite(float x, float y, float w, float h, String label,
                                                              BooleanSupplier toggleState, Consumer<Boolean> onToggle,
                                                              BooleanSupplier favoriteState, Consumer<Boolean> onFavorite) {
        return new SectionHeader(x, y, w, h, label, toggleState, onToggle, favoriteState, onFavorite);
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
        private final com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat actionHoverAnim;
        // Toggle intégré (voir sectionHeaderWithToggle) — MUTUELLEMENT
        // EXCLUSIF avec action/actionLabel (jamais les deux en même temps
        // dans l'usage actuel) : réutilise le VRAI widget UiToggle (pas une
        // réimplémentation du switch ici) pour un rendu/une animation
        // identiques à toutes les autres cases à cocher de l'appli — sa
        // position est recalée à chaque frame dans draw() pour rester
        // ancrée à droite de CET en-tête précis.
        private final UiToggle toggle;
        /** Cœur de favori, à GAUCHE de {@link #toggle} — {@code null} si cet en-tête n'en porte pas (voir sectionHeaderWithToggleAndFavorite). */
        private final UiToggle favorite;

        SectionHeader(float x, float y, float w, float h, String label, String actionLabel, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.actionLabel = actionLabel;
            this.action = action;
            this.actionHoverAnim = action != null
                ? new com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat(0f, 16f) : null;
            this.toggle = null;
            this.favorite = null;
        }

        SectionHeader(float x, float y, float w, float h, String label, BooleanSupplier toggleState, Consumer<Boolean> onToggle,
                      BooleanSupplier favoriteState, Consumer<Boolean> onFavorite) {
            super(x, y, w, h);
            this.label = label;
            this.actionLabel = null;
            this.action = null;
            this.actionHoverAnim = null;
            this.toggle = new UiToggle(0, 0, toggleState.getAsBoolean(), onToggle).boundTo(toggleState);
            this.favorite = onFavorite == null ? null
                : new UiToggle(0, 0, UiTheme.scaled(20f), UiTheme.scaled(20f), favoriteState.getAsBoolean(), onFavorite)
                    .heartStyle().boundTo(favoriteState);
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
            if (favorite != null) {
                // Juste à gauche du toggle, même axe vertical.
                favorite.x = toggle.x - favorite.w - UiTheme.scaled(12f);
                favorite.y = y + (h - favorite.h) / 2f;
            }
        }

        @Override
        public boolean contains(double mx, double my) {
            if (toggle != null) {
                layoutToggle();
                lastClickX = mx;
                lastClickY = my;
                // Le cœur est TESTÉ EN PREMIER : les deux zones ne se
                // chevauchent pas, mais l'ordre rend l'intention explicite et
                // évite toute ambiguïté si les marges venaient à se resserrer.
                if (favorite != null && favorite.contains(mx, my)) return true;
                return toggle.contains(mx, my);
            }
            if (action == null) return false;
            return mx >= actionX() && mx <= actionX() + actionW() && my >= actionY() && my <= actionY() + actionH();
        }

        @Override
        public void onClick() {
            if (toggle != null) {
                layoutToggle();
                if (favorite != null && favorite.contains(lastClickX, lastClickY)) { favorite.onClick(); return; }
                toggle.onClick();
                return;
            }
            if (action != null) action.run();
        }

        /**
         * Dernières coordonnées vues par {@link #contains} — {@code onClick()}
         * ne reçoit PAS la position du clic (voir {@code UiWidget}), or il
         * faut ici décider entre le cœur et le toggle. {@code contains} est
         * toujours appelé juste avant par la répartition des clics, donc ces
         * valeurs sont celles du clic en cours.
         */
        private double lastClickX, lastClickY;

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // En-tête de section — plus teinté que les lignes ordinaires
            // (GLASS_STRENGTH_PANEL) pour rester le repère structurant de la
            // liste, sinon il se noierait dans la succession de bandes.
            renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL,
                UiTheme.PANEL_BG_ALT.multiplyAlpha(0.82f * clipFade), vpWidth, vpHeight);
            float barW = UiTheme.scaled(3f), barInset = UiTheme.scaled(3f);
            renderer.drawRoundedRect(x, y + barInset, x + barW, y + h - barInset, barW / 2f,
                UiTheme.ACCENT.multiplyAlpha(clipFade), vpWidth, vpHeight);
            // Traduit ICI, à l'affichage uniquement — "label" reste le texte
            // source (français) partout ailleurs dans cette classe (jamais
            // utilisé comme clé de recherche par ce widget lui-même, mais
            // CategoryTab (UiModConfigScreen) s'en sert comme clé dans
            // anchors — même principe, voir sa javadoc de classe).
            String displayLabel = Lang.tr(label);
            float scale = UiTheme.scaled(0.5f);
            renderer.drawTextShadowed(UiFont.BOLD, displayLabel, x + barW + UiTheme.scaled(10f), y + h / 2f - UiTheme.scaled(6f),
                UiTheme.TEXT_PRIMARY.multiplyAlpha(clipFade), new UiColor(0, 0, 0, 150).multiplyAlpha(clipFade),
                scale, scale, scale, vpWidth, vpHeight);

            if (toggle != null) {
                layoutToggle();
                if (favorite != null) favorite.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
                toggle.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
                return;
            }

            if (action != null) {
                actionHoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
                UiColor btnColor = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, actionHoverAnim.get());
                renderer.drawRoundedRect(actionX(), actionY(), actionX() + actionW(), actionY() + actionH(),
                    UiTheme.RADIUS_SM, btnColor.multiplyAlpha(clipFade), vpWidth, vpHeight);
                float actionScale = UiTheme.scaled(0.4f);
                String displayAction = Lang.tr(actionLabel);
                float tw = renderer.textWidth(displayAction, actionScale);
                renderer.drawText(displayAction, actionX() + (actionW() - tw) / 2f, actionY() + actionH() / 2f - UiTheme.scaled(4f),
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

        cursor = toggleRow(rows, x, w, cursor, Lang.tr("Verrouillé"),
            Lang.tr("Empêche de déplacer/redimensionner cet élément dans l'éditeur de HUD."),
            element.locked, v -> { element.locked = v; module.onConfigChanged(); HudConfigStore.save(); });
        cursor = toggleRow(rows, x, w, cursor, Lang.tr("Afficher même avec un écran ouvert"),
            Lang.tr("Reste visible pendant le chat, l'inventaire ou tout autre écran (sauf nos propres menus)."),
            element.showWhenScreenOpen, v -> { element.showWhenScreenOpen = v; module.onConfigChanged(); HudConfigStore.save(); });
        cursor = sliderRow(rows, x, w, cursor, Lang.tr("Échelle"),
            Lang.tr("Taille de toute la boîte (largeur ET hauteur ensemble, jamais l'une sans l'autre)."),
            HudElement.MIN_SCALE, HudElement.MAX_SCALE, 0.05f, element.scale, v -> { element.setScale(v); module.onConfigChanged(); HudConfigStore.save(); }, null);
        cursor = sliderRow(rows, x, w, cursor, Lang.tr("Marge horizontale"),
            Lang.tr("Espace entre le bord de la boîte et le contenu (X)."),
            0f, 20f, 1f, element.paddingX, v -> { element.paddingX = v; module.onConfigChanged(); HudConfigStore.save(); }, null);
        cursor = sliderRow(rows, x, w, cursor, Lang.tr("Marge verticale"),
            Lang.tr("Espace entre le bord de la boîte et le contenu (Y)."),
            0f, 20f, 1f, element.paddingY, v -> { element.paddingY = v; module.onConfigChanged(); HudConfigStore.save(); }, null);
        // Opacité PROPRE à cet élément — multipliée par le réglage global
        // "Opacité du HUD" (voir HudElement.opacity) : le global reste un
        // gradateur d'ensemble, celui-ci règle la hiérarchie entre modules.
        cursor = sliderRow(rows, x, w, cursor, Lang.tr("Opacité"),
            Lang.tr("Transparence de CE panneau seulement — se combine avec le réglage global \"Opacité du HUD\"."),
            0.1f, 1f, 0.05f, element.opacity, v -> { element.opacity = v; module.onConfigChanged(); HudConfigStore.save(); }, null);
        // Couleur du texte : jusqu'ici figée dans le code de chaque module
        // (accent bleu de FPS, vert de Ping...) et non persistée.
        cursor = colorRow(rows, x, w, cursor, Lang.tr("Couleur du texte"),
            Lang.tr("Couleur du texte de ce panneau (ou de son suffixe d'accent quand il en a un, ex. \"FPS\")."),
            element.textColor != null ? element.textColor : UiTheme.TEXT_PRIMARY,
            c -> { element.textColor = c; module.onConfigChanged(); HudConfigStore.save(); });

        // Réinitialise anchor/offset (voir HudElement.resetPosition) — devait
        // AUSSI persister le résultat, sinon le prochain redémarrage ramenait
        // la position "sauvegardée" précédente au lieu du reset qu'on vient
        // de demander (oublié avant ce correctif, aucun onConfigChanged/save
        // n'était appelé après element::resetPosition).
        // resetAll (pas resetPosition) — remet AUSSI échelle/marges/opacité/
        // couleur : après avoir bidouillé ces réglages, il fallait sinon les
        // remettre un par un à la main en devinant les valeurs d'origine,
        // jamais affichées nulle part.
        Runnable resetAction = () -> { element.resetAll(); module.onConfigChanged(); HudConfigStore.save(); };
        if (headerActions != null) {
            // buildContinuous (demande explicite : "le bouton réinitialiser
            // la position, place le dans le titre HUD pour tout les hud") —
            // déplacé DANS l'en-tête de section, plus de ligne dédiée.
            // NOTE traduction : la clé de "category" (utilisée pour anchors/
            // navigation) reste TOUJOURS le texte source français — le LABEL
            // affiché (ici, l'action du header) reste NON traduit ici aussi :
            // SectionHeader.draw() traduit "actionLabel" lui-même à
            // l'affichage (seul point de traduction, pour éviter de
            // traduire deux fois — voir sa javadoc).
            headerActions.put(category, resetAction);
            headerActionLabels.put(category, "Tout réinitialiser");
        } else {
            // build() legacy (voir UiModGroupConfigScreen, pas d'en-tête de
            // section dans ce mode) — comportement inchangé, ligne normale.
            cursor = buttonRow(rows, x, w, cursor, Lang.tr("Tout réinitialiser"), resetAction);
        }

        cursors.put(category, cursor);
    }

    /**
     * Une ligne d'écran par réglage déclaré. Le {@code switch} sur le type
     * concret remplace la cascade d'{@code isAnnotationPresent} : javac
     * vérifie ici que chaque type de {@link Setting} est traité, ce que la
     * version par annotations ne pouvait pas garantir.
     */
    private static float addRow(List<UiWidget> rows, Setting setting, LauncherModule module,
                                float x, float w, float cursor) {
        String label = Lang.tr(setting.name);
        String tooltip = Lang.tr(setting.description);
        Runnable commit = () -> { module.onConfigChanged(); HudConfigStore.save(); };

        if (setting instanceof Setting.Toggle) {
            Setting.Toggle t = (Setting.Toggle) setting;
            warnUnsupportedDependency(module, setting);
            return toggleRow(rows, x, w, cursor, label, tooltip, t.get.getAsBoolean(),
                v -> { t.set.accept(v); commit.run(); });
        }
        if (setting instanceof Setting.Slider) {
            Setting.Slider sl = (Setting.Slider) setting;
            return sliderRow(rows, x, w, cursor, label, tooltip, sl.min, sl.max, sl.step, sl.get.getAsFloat(),
                v -> { sl.set.accept(v); commit.run(); }, setting.enabledWhen);
        }
        if (setting instanceof Setting.Color) {
            Setting.Color c = (Setting.Color) setting;
            warnUnsupportedDependency(module, setting);
            return colorRow(rows, x, w, cursor, label, tooltip, c.get.get(),
                v -> { c.set.accept(v); commit.run(); });
        }
        if (setting instanceof Setting.Dropdown) {
            Setting.Dropdown d = (Setting.Dropdown) setting;
            warnUnsupportedDependency(module, setting);
            List<String> translated = new ArrayList<>(d.options.size());
            for (String opt : d.options) translated.add(Lang.tr(opt));
            return dropdownRow(rows, x, w, cursor, label, tooltip, translated, d.get.getAsInt(),
                v -> { d.set.accept(v); commit.run(); });
        }
        if (setting instanceof Setting.Keybind) {
            Setting.Keybind k = (Setting.Keybind) setting;
            warnUnsupportedDependency(module, setting);
            return keybindRow(rows, x, w, cursor, label, tooltip, k.get.get(),
                v -> { k.set.accept(v); commit.run(); });
        }
        if (setting instanceof Setting.Inline) {
            return inlineRow(rows, x, w, cursor, (Setting.Inline) setting, commit);
        }
        if (setting instanceof Setting.Text) {
            Setting.Text t = (Setting.Text) setting;
            warnUnsupportedDependency(module, setting);
            return textRow(rows, x, w, cursor, label, tooltip, t.placeholder, t.get.get(),
                v -> { t.set.accept(v); commit.run(); });
        }
        if (setting instanceof Setting.Action) {
            Setting.Action a2 = (Setting.Action) setting;
            warnUnsupportedDependency(module, setting);
            return buttonRow(rows, x, w, cursor, label, a2.run);
        }
        if (setting instanceof Setting.Opaque) {
            // Persisté mais volontairement invisible ici — le module l'édite
            // dans son propre écran (voir Setting.Opaque). Ce n'est pas un
            // type « non géré » : le sauter est le comportement voulu.
            return cursor;
        }
        LauncherLog.err("[ConfigScreenBuilder] type de réglage non géré : "
            + setting.getClass().getSimpleName() + " (" + module.id + "." + setting.id + ")");
        return cursor;
    }

    /**
     * {@link Setting#enabledWhen} n'est honoré que par {@code UiSlider}, seul
     * widget sachant se griser aujourd'hui. Le déclarer ailleurs n'a donc
     * aucun effet visible — on le DIT plutôt que de l'ignorer en silence,
     * puisque c'est précisément le genre de panne muette que le passage aux
     * réglages déclarés supprime. À retirer le jour où toggle/dropdown/color/
     * keybind sauront se griser eux aussi.
     */
    private static void warnUnsupportedDependency(LauncherModule module, Setting setting) {
        if (setting.enabledWhen == null || WARNED_DEPENDENCY.contains(module.id + "." + setting.id)) return;
        WARNED_DEPENDENCY.add(module.id + "." + setting.id);
        LauncherLog.err("[ConfigScreenBuilder] " + module.id + "." + setting.id
            + " : enabledWhen déclaré mais seul un curseur sait se griser — réglage laissé actif");
    }

    private static final java.util.Set<String> WARNED_DEPENDENCY = new java.util.HashSet<>();

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
            // Bandeau de verre par ligne de réglage (rework 2026-08-27) — c'est
            // LA surface dominante des écrans de config. Teinte légère : ces
            // bandes se succèdent verticalement, une teinte forte redonnerait
            // un aplat plein écran et annulerait l'intérêt du flou.
            // clipFade porté par la couleur de repli, qui pilote AUSSI
            // l'opacité du verre (voir UiRenderer#drawGlassPanel).
            renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD,
                new UiColor(0, 0, 0, 60).multiplyAlpha(clipFade),
                renderer.isGlassAvailable() ? UiTheme.GLASS_BORDER.multiplyAlpha(clipFade) : null,
                Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
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
                                    float min, float max, float step, float initial, Consumer<Float> onChange,
                                    java.util.function.BooleanSupplier enabledSupplier) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float sliderW = UiTheme.scaled(190f);
        rows.add(new UiSlider(x + w - sliderW - UiTheme.scaled(10f), rowY + (ROW_H - UiTheme.scaled(20f)) / 2f, sliderW, min, max, step, initial, onChange, enabledSupplier));
        return rowY - ROW_GAP;
    }



    private static float colorRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                   UiColor initial, Consumer<UiColor> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        UiColorPicker picker = new UiColorPicker(x + w - UiTheme.scaled(42f) - UiTheme.scaled(10f), rowY + (ROW_H - UiTheme.scaled(26f)) / 2f, initial, onChange);
        rows.add(picker);
        // BUG TROUVÉ (retour utilisateur : "je veux une modal détachée du
        // bouton, que le bouton ne prenne que SA place") — cette ligne
        // réservait jusqu'à picker.panelBottom(), c'est-à-dire la hauteur du
        // panneau ENTIER dans la liste, MÊME QUAND IL EST FERMÉ (donc à
        // chaque construction de l'écran) : le bouton poussait les lignes
        // suivantes comme si le panneau était toujours ouvert. Ce n'était
        // nécessaire qu'AVANT que le panneau ne se dessine en overlay (voir
        // UiWidget#drawOverlay) — désormais il flotte TOUJOURS par-dessus
        // tout, y compris les lignes suivantes de la liste, sans jamais
        // avoir besoin qu'on lui réserve de la place. La ligne ne réserve
        // donc plus que sa propre hauteur, comme toggleRow/sliderRow/etc.
        return rowY - ROW_GAP;
    }

    private static float dropdownRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                      List<String> options, int initialIndex, IntConsumer onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float dw = UiTheme.scaled(170f);
        float ddH = UiTheme.scaled(26f);
        rows.add(new UiDropdown(x + w - dw - UiTheme.scaled(10f), rowY + (ROW_H - ddH) / 2f, dw, ddH, options, initialIndex, onChange));
        // BUG TROUVÉ (retour utilisateur : "les paramètres... sont beaucoup
        // trop éloignés") — MÊME bug que colorRow (voir son commentaire) :
        // réservait TOUJOURS la hauteur de la liste déroulée entière, même
        // fermée, alors que UiDropdown dessine cette liste en overlay (voir
        // UiWidget#drawOverlay) depuis le même fix de z-order que le color
        // picker — flotte déjà par-dessus tout, aucune réserve nécessaire.
        return rowY - ROW_GAP;
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

    /**
     * Ligne COMPACTE : tous les contrôles d'une entrée sur UNE ligne, sans
     * colonne de libellé — voir {@link Setting.Inline} pour le pourquoi.
     *
     * <p>Le champ de texte prend toute la largeur restante : c'est lui qui
     * porte le contenu long (une commande), les deux autres contrôles ont une
     * largeur fixe. Le bouton de suppression est à droite, sur la même ligne —
     * c'était l'autre moitié de la demande.
     */
    private static float inlineRow(List<UiWidget> rows, float x, float w, float cursor,
                                    Setting.Inline inline, Runnable commit) {
        float rowY = cursor - ROW_H;
        float ctrlH = UiTheme.scaled(26f);
        float ctrlY = rowY + (ROW_H - ctrlH) / 2f;
        float gap = UiTheme.scaled(8f);
        float delW = UiTheme.scaled(30f);
        float leftW = UiTheme.scaled(inline.fixedLabel != null ? 190f : 120f);

        if (inline.fixedLabel != null) {
            // Libellé NON cliquable — un libellé large posé avant les
            // contrôles leur volerait le clic (voir RowBackground pour le
            // même piège, UiScrollContainer dispatche au premier qui matche).
            rows.add(new InlineLabel(x, rowY, leftW, ROW_H, inline.fixedLabel));
        } else {
            rows.add(new UiKeybindButton(x, ctrlY, leftW, ctrlH, inline.keyGet.get(),
                v -> { inline.keySet.accept(v); commit.run(); }));
        }

        // Champ secondaire optionnel (le NOM d'une macro), inséré entre le
        // bouton de touche et le champ principal — largeur fixe : c'est un
        // libellé court, alors que le champ principal porte une commande dont
        // la longueur est imprévisible et doit prendre ce qui reste.
        float cursorX = x + leftW + gap;
        float usedW = leftW + gap;
        if (inline.nameGet != null) {
            float nameW = UiTheme.scaled(120f);
            UiTextField nameField = new UiTextField(cursorX, ctrlY, nameW, ctrlH,
                inline.namePlaceholder, v -> { inline.nameSet.accept(v); commit.run(); });
            String initialName = inline.nameGet.get();
            if (initialName != null) nameField.setText(initialName);
            rows.add(nameField);
            cursorX += nameW + gap;
            usedW += nameW + gap;
        }

        // L'œil prend sa place SUR la ligne, comme la croix : le champ se
        // rétrécit d'autant plutôt que de passer dessous.
        float eyeW = inline.masked ? UiTheme.scaled(30f) + gap : 0f;
        float fieldW = Math.max(UiTheme.scaled(60f), w - usedW - delW - eyeW - gap);
        final UiTextField field = new UiTextField(cursorX, ctrlY, fieldW, ctrlH,
            inline.placeholder, v -> { inline.textSet.accept(v); commit.run(); });
        String initial = inline.textGet.get();
        if (initial != null) field.setText(initial);
        if (inline.masked) field.masked();
        rows.add(field);

        if (inline.masked) {
            rows.add(new EyeButton(x + w - delW - eyeW, ctrlY, UiTheme.scaled(30f), ctrlH, field));
        }

        rows.add(new UiButton(x + w - delW, ctrlY, delW, ctrlH, "×", inline.delete));
        return rowY - ROW_GAP;
    }

    /**
     * Œil de révélation d'un champ masqué.
     *
     * <p>Widget dédié plutôt qu'un {@code UiButton} à libellé : le libellé
     * d'un bouton est figé à la construction, il ne changerait donc qu'au
     * prochain recalcul de la page — l'œil resterait « fermé » alors que le
     * mot de passe est visible. Ici l'état est relu à chaque frame depuis le
     * champ lui-même, seule source de vérité.
     *
     * <p>Bascule et non maintien : sur un mot de passe qu'on est en train de
     * corriger, garder le bouton enfoncé d'une main tout en tapant de l'autre
     * n'est pas praticable.
     */
    private static final class EyeButton extends UiWidget {
        private final UiTextField field;

        EyeButton(float x, float y, float w, float h, UiTextField field) {
            super(x, y, w, h);
            this.field = field;
        }

        @Override
        public void onClick() {
            field.setRevealed(!field.isRevealed());
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean hovered = contains(mouseX, mouseY);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                hovered ? UiTheme.CARD_HOVER : UiTheme.CARD_BG, vpWidth, vpHeight);

            UiColor ink = field.isRevealed() ? UiTheme.ACCENT : UiTheme.TEXT_SECONDARY;
            float cx = x + w / 2f, cy = y + h / 2f;
            float eyeW = w * 0.44f, eyeH = h * 0.18f;

            // Œil schématique : une paupière (barre horizontale arrondie) et
            // une pupille. Assez lisible à 30 px de large, et sans dépendre
            // d'un glyphe qui pourrait manquer de la police.
            renderer.drawRoundedRect(cx - eyeW, cy - eyeH, cx + eyeW, cy + eyeH,
                eyeH, ink, vpWidth, vpHeight);
            float pupil = Math.min(eyeH, w * 0.10f);
            renderer.drawRoundedRect(cx - pupil, cy - pupil, cx + pupil, cy + pupil,
                pupil, UiTheme.PANEL_BG, vpWidth, vpHeight);

            if (field.isRevealed()) {
                // Barre oblique — l'œil OUVERT (contenu visible) est l'état
                // exceptionnel, c'est donc lui qu'on marque.
                float t = Math.max(1f, UiTheme.scaled(1.5f));
                renderer.drawRoundedRect(cx - eyeW, cy - t, cx + eyeW, cy + t, t, ink, vpWidth, vpHeight);
            }
        }
    }

    /** Libellé figé d'une {@link Setting.Inline} — {@code contains()} faux, sinon il intercepterait les clics des contrôles de sa propre ligne. */
    private static final class InlineLabel extends UiWidget {
        private final String text;

        InlineLabel(float x, float y, float w, float h, String text) {
            super(x, y, w, h);
            this.text = text;
        }

        @Override public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawText(text, x, y + h / 2f - UiTheme.scaled(4f),
                UiTheme.TEXT_PRIMARY, LABEL_SCALE, vpWidth, vpHeight);
        }
    }

    /**
     * Ligne CHAMP DE SAISIE. Plus large que les autres contrôles (280 contre
     * 190 pour un curseur) : ce qu'on y tape est du texte libre — une
     * commande, un mot de passe — et non une valeur courte.
     *
     * <p>{@code setText} après construction plutôt qu'un paramètre : le
     * constructeur d'{@code UiTextField} ne prend qu'un placeholder, la
     * valeur initiale se pose ensuite.
     */
    private static float textRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                  String placeholder, String initial, Consumer<String> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, w, rowY, label, tooltip);
        float fw = UiTheme.scaled(280f);
        float fh = UiTheme.scaled(26f);
        UiTextField field = new UiTextField(x + w - fw - UiTheme.scaled(10f), rowY + (ROW_H - fh) / 2f, fw, fh,
            placeholder, onChange);
        if (initial != null) field.setText(initial);
        rows.add(field);
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

}
