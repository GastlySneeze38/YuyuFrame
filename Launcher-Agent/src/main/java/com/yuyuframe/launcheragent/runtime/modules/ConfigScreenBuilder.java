package com.yuyuframe.launcheragent.runtime.modules;

import com.yuyuframe.launcheragent.runtime.modules.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
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

    private static final float ROW_H = 26f, ROW_GAP = 6f;
    private static final float LABEL_SCALE = 0.42f;

    public static LinkedHashMap<String, List<UiWidget>> build(LauncherModule module, float x, float w) {
        LinkedHashMap<String, List<UiWidget>> byCategory = new LinkedHashMap<>();
        LinkedHashMap<String, Float> cursors = new LinkedHashMap<>();

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
                v -> { setBoolean(field, module, v); module.onConfigChanged(); });
        }
        if (field.isAnnotationPresent(ConfigSlider.class)) {
            ConfigSlider a = field.getAnnotation(ConfigSlider.class);
            return sliderRow(rows, x, w, cursor, a.name(), a.description(), a.min(), a.max(), a.step(), getFloat(field, module),
                v -> { setFloat(field, module, v); module.onConfigChanged(); });
        }
        if (field.isAnnotationPresent(ConfigColor.class)) {
            ConfigColor a = field.getAnnotation(ConfigColor.class);
            return colorRow(rows, x, w, cursor, a.name(), a.description(), getColor(field, module),
                v -> { setColor(field, module, v); module.onConfigChanged(); });
        }
        if (field.isAnnotationPresent(ConfigDropdown.class)) {
            ConfigDropdown a = field.getAnnotation(ConfigDropdown.class);
            return dropdownRow(rows, x, w, cursor, a.name(), a.description(), Arrays.asList(a.options()), getInt(field, module),
                v -> { setInt(field, module, v); module.onConfigChanged(); });
        }
        if (field.isAnnotationPresent(ConfigKeybind.class)) {
            ConfigKeybind a = field.getAnnotation(ConfigKeybind.class);
            return keybindRow(rows, x, w, cursor, a.name(), a.description(), getString(field, module),
                v -> { setString(field, module, v); module.onConfigChanged(); });
        }
        return cursor;
    }

    // ── Lignes — mêmes proportions que l'ancien UiModConfigScreen codé en dur ──

    private static float rowLabel(List<UiWidget> rows, float x, float rowY, String label, String tooltip) {
        rows.add(new UiLabel(x, rowY + ROW_H / 2f - 4f, label, UiTheme.TEXT_PRIMARY, LABEL_SCALE).tooltip(tooltip));
        return rowY;
    }

    private static float toggleRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                    boolean initial, Consumer<Boolean> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        rows.add(new UiToggle(x + w - 34f, rowY + (ROW_H - 18f) / 2f, initial, onChange));
        return rowY - ROW_GAP;
    }

    private static float sliderRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                    float min, float max, float step, float initial, Consumer<Float> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float sliderW = 150f;
        rows.add(new UiSlider(x + w - sliderW, rowY + (ROW_H - 16f) / 2f, sliderW, min, max, step, initial, onChange));
        return rowY - ROW_GAP;
    }

    private static float colorRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                   UiColor initial, Consumer<UiColor> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        rows.add(new UiColorPicker(x + w - 32f, rowY + (ROW_H - 20f) / 2f, initial, onChange));
        // Marge supplémentaire : le panneau déroulant du color picker s'ouvre vers le bas.
        return rowY - ROW_GAP - 90f;
    }

    private static float dropdownRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                      List<String> options, int initialIndex, IntConsumer onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float dw = 130f;
        rows.add(new UiDropdown(x + w - dw, rowY + (ROW_H - 20f) / 2f, dw, 20f, options, initialIndex, onChange));
        // Même raison que colorRow : réserve la hauteur du panneau déroulé.
        return rowY - ROW_GAP - (options.size() * 20f + 8f);
    }

    private static float keybindRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                                     String initialKey, Consumer<String> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float kw = 90f;
        rows.add(new UiKeybindButton(x + w - kw, rowY + (ROW_H - 20f) / 2f, kw, 20f, initialKey, onChange));
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
