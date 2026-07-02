package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Port de PvP-Mod PotionEffectsConfig/PotionEffectsHud — sa propre carte, comme dans la référence. */
public final class PotionEffectsModule extends SingleHudModule {
    public PotionEffectsModule() {
        super("potion-effects", "Effets de potion", "Liste des effets de potion actifs", false,
            new HudElement("potion-effects", "Effets de potion", HudAnchor.TOP_RIGHT, 8f, 40f,
                (HudElement.CustomRenderer) new Renderer()));
    }

    /**
     * Icône = pastille de la couleur du liquide (StatusEffect.getColor()),
     * PAS l'icône vanilla réelle : l'original faisait déjà ce choix (icône
     * vanilla non reproductible fiablement depuis son propre environnement),
     * aucune régression donc par rapport à la référence.
     */
    private static final class Renderer implements HudElement.CustomRenderer {
        private static final String[] ROMAN = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
        // 28, pas 18 : chaque effet dessine DEUX lignes (nom au-dessus, durée
        // en-dessous — voir drawRow), pas une seule — leur écart réel (nom à
        // ty, durée à ty-12) dépassait déjà les 18px d'espacement entre deux
        // effets consécutifs, faisant chevaucher la durée d'un effet avec le
        // nom du suivant ("les effets se chevauchent").
        private static final float LINE_H = 28f;
        private static final float ICON = 10f;
        private static final float NAME_SCALE = 0.36f;
        private static final float TIME_SCALE = 0.3f;
        /** Repli avant que le joueur/les effets ne soient connus (comme FPS/Ping). */
        private static final float FALLBACK_WIDTH = 100f;
        private static final int FALLBACK_COUNT = 2;

        private static final class EffectRow {
            final String name, time;
            final UiColor color;
            EffectRow(String name, String time, UiColor color) { this.name = name; this.time = time; this.color = color; }
        }

        @Override
        public float[] naturalSize() {
            // Largeur ET hauteur RECALCULÉES À CHAQUE FRAME depuis les effets
            // RÉELLEMENT actifs (comme FPS/Ping/ArmorDurability, voir
            // HudElement.refreshSize()) — un nombre de lignes fixe en dur ne
            // correspondait pas au nombre d'effets réellement affichés dans
            // draw(), d'où le chevauchement dès qu'il y en avait plus que ce
            // nombre supposé.
            List<EffectRow> rows = currentRows();
            if (rows.isEmpty()) return new float[]{ FALLBACK_WIDTH, FALLBACK_COUNT * LINE_H };
            float maxNameW = 0f;
            for (EffectRow row : rows) maxNameW = Math.max(maxNameW, UiFont.REGULAR.textWidth(row.name, NAME_SCALE));
            float contentW = Math.max(FALLBACK_WIDTH, ICON + 5f + maxNameW);
            return new float[]{ contentW, rows.size() * LINE_H };
        }

        private List<EffectRow> currentRows() {
            List<EffectRow> rows = new ArrayList<>();
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return rows;
                Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                if (player == null) return rows;

                Method getInstances = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStatusEffectInstances");
                if (getInstances == null) return rows;
                Collection<?> effects = (Collection<?>) getInstances.invoke(player);
                if (effects == null || effects.isEmpty()) return rows;

                Class<?> effectClass = McReflect.yarnClass("net/minecraft/entity/effect/StatusEffect");
                Object[] statusEffects = effectClass != null
                    ? (Object[]) McReflect.field(effectClass, "net/minecraft/entity/effect/StatusEffect", "STATUS_EFFECTS").get(null)
                    : null;

                Class<?> instanceClass = null;
                for (Object instance : effects) {
                    if (instanceClass == null) instanceClass = instance.getClass();

                    int effectId = (int) McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getEffectId").invoke(instance);
                    int amplifier = (int) McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getAmplifier").invoke(instance);
                    int duration = (int) McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getDuration").invoke(instance);

                    Object effect = (statusEffects != null && effectId >= 0 && effectId < statusEffects.length) ? statusEffects[effectId] : null;
                    String name = effectName(effect);
                    if (amplifier > 0 && amplifier <= ROMAN.length) name += " " + ROMAN[amplifier - 1];

                    int seconds = duration / 20;
                    String time = (seconds >= 60 ? (seconds / 60) + "m " : "") + (seconds % 60) + "s";

                    rows.add(new EffectRow(name, time, effectColor(effect)));
                }
            } catch (Throwable ignored) {}
            return rows;
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            List<EffectRow> rows = currentRows();
            float icon = ICON * scale, lineH = LINE_H * scale;

            float ty = y + h - icon;
            for (EffectRow row : rows) {
                renderer.drawRoundedRect(x, ty, x + icon, ty + icon, icon / 2f, row.color, vpWidth, vpHeight);
                renderer.drawText(row.name, x + icon + 5f * scale, ty, UiTheme.TEXT_PRIMARY, NAME_SCALE * scale, vpWidth, vpHeight);
                renderer.drawText(row.time, x + icon + 5f * scale, ty - 12f * scale, UiTheme.TEXT_SECONDARY, TIME_SCALE * scale, vpWidth, vpHeight);
                ty -= lineH;
            }
        }

        private String effectName(Object effect) {
            if (effect == null) return "?";
            try {
                Method getKey = McReflect.noArgMethod(effect.getClass(), "net/minecraft/entity/effect/StatusEffect", "getTranslationKey");
                return prettify((String) getKey.invoke(effect));
            } catch (Throwable t) {
                return "?";
            }
        }

        /** Pas de traduction I18n (éviterait une nouvelle chaîne de réflexion) — nettoie juste la clé brute ("effect.moveSpeed" -> "Move Speed"). */
        private String prettify(String translationKey) {
            if (translationKey == null) return "?";
            String raw = translationKey.contains(".") ? translationKey.substring(translationKey.lastIndexOf('.') + 1) : translationKey;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (Character.isUpperCase(c) && i > 0) sb.append(' ');
                sb.append(i == 0 ? Character.toUpperCase(c) : c);
            }
            return sb.toString();
        }

        private UiColor effectColor(Object effect) {
            if (effect == null) return UiTheme.ACCENT;
            try {
                Method getColor = McReflect.noArgMethod(effect.getClass(), "net/minecraft/entity/effect/StatusEffect", "getColor");
                int rgb = (int) getColor.invoke(effect);
                return new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
            } catch (Throwable t) {
                return UiTheme.ACCENT;
            }
        }
    }
}
