package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Method;
import java.util.Collection;

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
        private static final float LINE_H = 18f;
        private static final float ICON = 10f;
        private static final float PADDING = 5f;
        /** Largeur "naturelle" à scale=1 — pas de calcul dynamique fiable, les noms d'effets varient trop en longueur. */
        private static final float NATURAL_WIDTH = 130f;
        /** Nombre d'effets supposé pour la taille PAR DÉFAUT de la boîte — le vrai nombre n'est connu qu'en jeu (accès joueur), pas à la construction du module. */
        private static final int DEFAULT_EFFECT_COUNT = 2;

        @Override
        public float[] naturalSize() {
            return new float[]{ NATURAL_WIDTH, DEFAULT_EFFECT_COUNT * LINE_H + 2 * PADDING };
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return;
                Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                if (player == null) return;

                Method getInstances = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStatusEffectInstances");
                if (getInstances == null) return;
                Collection<?> effects = (Collection<?>) getInstances.invoke(player);
                if (effects == null || effects.isEmpty()) return;

                Class<?> effectClass = McReflect.yarnClass("net/minecraft/entity/effect/StatusEffect");
                Object[] statusEffects = effectClass != null
                    ? (Object[]) McReflect.field(effectClass, "net/minecraft/entity/effect/StatusEffect", "STATUS_EFFECTS").get(null)
                    : null;

                // w/h dérivent TOUJOURS de naturalSize()*scale — pas
                // d'agrandissement automatique supplémentaire ici. Si le
                // nombre d'effets ACTIFS dépasse DEFAULT_EFFECT_COUNT, le
                // contenu peut déborder la boîte — acceptable, l'utilisateur
                // peut l'agrandir (scale, linéaire et fiable).
                float icon = ICON * scale, padding = PADDING * scale, lineH = LINE_H * scale;

                Class<?> instanceClass = null;
                float ty = y + h - padding - icon;
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

                    renderer.drawRoundedRect(x + padding, ty, x + padding + icon, ty + icon, icon / 2f, effectColor(effect), vpWidth, vpHeight);
                    renderer.drawText(name, x + padding + icon + 5f * scale, ty + 1f * scale, UiTheme.TEXT_PRIMARY, 0.36f * scale, vpWidth, vpHeight);
                    renderer.drawText(time, x + padding + icon + 5f * scale, ty - 9f * scale, UiTheme.TEXT_SECONDARY, 0.3f * scale, vpWidth, vpHeight);

                    ty -= lineH;
                }
            } catch (Throwable ignored) {}
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
