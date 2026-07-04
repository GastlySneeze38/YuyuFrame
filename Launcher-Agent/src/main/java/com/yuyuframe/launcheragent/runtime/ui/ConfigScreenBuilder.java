package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
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
            addHudElementRows(byCategory, cursors, ((HudElementOwner) module).hudElement(), module, x, w);
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
        return byCategory;
    }

    private static void addHudElementRows(LinkedHashMap<String, List<UiWidget>> byCategory, LinkedHashMap<String, Float> cursors,
                                           HudElement element, LauncherModule module, float x, float w) {
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
        cursor = buttonRow(rows, x, w, cursor, "Réinitialiser la position", () -> { element.resetPosition(); module.onConfigChanged(); HudConfigStore.save(); });

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

    private static float rowLabel(List<UiWidget> rows, float x, float rowY, String label, String tooltip) {
        rows.add(new UiLabel(x, rowY + ROW_H / 2f - UiTheme.scaled(5f), label, UiTheme.TEXT_PRIMARY, LABEL_SCALE).tooltip(tooltip));
        return rowY;
    }

    private static float toggleRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                    boolean initial, Consumer<Boolean> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float toggleH = UiTheme.scaled(24f);
        rows.add(new UiToggle(x + w - UiTheme.scaled(44f), rowY + (ROW_H - toggleH) / 2f, initial, onChange));
        return rowY - ROW_GAP;
    }

    private static float sliderRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                    float min, float max, float step, float initial, Consumer<Float> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float sliderW = UiTheme.scaled(190f);
        rows.add(new UiSlider(x + w - sliderW, rowY + (ROW_H - UiTheme.scaled(20f)) / 2f, sliderW, min, max, step, initial, onChange));
        return rowY - ROW_GAP;
    }

    private static float colorRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                   UiColor initial, Consumer<UiColor> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        rows.add(new UiColorPicker(x + w - UiTheme.scaled(42f), rowY + (ROW_H - UiTheme.scaled(26f)) / 2f, initial, onChange));
        // Marge supplémentaire : le panneau déroulant du color picker s'ouvre vers le bas.
        return rowY - ROW_GAP - UiTheme.scaled(115f);
    }

    private static float dropdownRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                      List<String> options, int initialIndex, IntConsumer onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float dw = UiTheme.scaled(170f);
        float ddH = UiTheme.scaled(26f);
        rows.add(new UiDropdown(x + w - dw, rowY + (ROW_H - ddH) / 2f, dw, ddH, options, initialIndex, onChange));
        // Même raison que colorRow : réserve la hauteur du panneau déroulé.
        return rowY - ROW_GAP - (options.size() * ddH + UiTheme.scaled(10f));
    }

    private static float keybindRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                     String initialKey, Consumer<String> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float kw = UiTheme.scaled(110f);
        float kh = UiTheme.scaled(26f);
        rows.add(new UiKeybindButton(x + w - kw, rowY + (ROW_H - kh) / 2f, kw, kh, initialKey, onChange));
        return rowY - ROW_GAP;
    }

    /** Ligne bouton seul (pas de label à gauche, ex: "Réinitialiser la position") — pas de valeur associée, juste une action. */
    private static float buttonRow(List<UiWidget> rows, float x, float w, float cursor, String label, Runnable action) {
        float rowY = cursor - ROW_H;
        float bw = UiTheme.scaled(190f);
        float bh = UiTheme.scaled(28f);
        rows.add(new UiButton(x + w - bw, rowY + (ROW_H - bh) / 2f, bw, bh, label, action));
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
