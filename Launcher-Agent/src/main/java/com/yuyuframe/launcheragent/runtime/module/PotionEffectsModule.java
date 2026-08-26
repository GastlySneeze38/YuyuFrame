package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Port de PvP-Mod PotionEffectsConfig/PotionEffectsHud — sa propre carte, comme dans la référence. */
public final class PotionEffectsModule extends SingleHudModule {
    public PotionEffectsModule() {
        super("potion-effects", "Effets de potion", "Liste des effets de potion actifs", false,
            new HudElement("potion-effects", "Effets de potion", HudAnchor.TOP_RIGHT, 8f, 40f,
                (HudElement.CustomRenderer) new Renderer()),
            HookPoint.HUD_EXTRACT_EFFECTS);
        iconUrl = icons8("test-tube");

        // BUG TROUVÉ (audit modules 2026-08-25, §19 — même trou que
        // ArmorDurabilityModule, voir sa javadoc) : dessinait sa propre liste
        // d'effets SANS supprimer les icônes vanilla natives — mixin
        // HudExtractEffectsMixin261/HookPoint HUD_EXTRACT_EFFECTS déjà
        // existants, jamais câblés faute de relais dans SingleHudModule.
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_EFFECTS, ctx -> isEnabled());
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
            // 26.1.2 sans réflexion — Minecraft.player + getActiveEffects()/
            // getAmplifier()/getDuration()/getEffect()/value() (méthodes
            // publiques, voir stubs LocalPlayer/MobEffectInstance/Holder). Try/
            // catch dédié : nom de classe RÉEL, inexistant tel quel sur les
            // autres brackets (obfusqués) — repli réflexion multi-bracket sinon.
            try {
                LocalPlayer directPlayer = Minecraft.getInstance().player;
                if (directPlayer != null) {
                    Collection<MobEffectInstance> effects = directPlayer.getActiveEffects();
                    if (effects != null) {
                        for (MobEffectInstance instance : effects) {
                            int amplifier = instance.getAmplifier();
                            int duration = instance.getDuration();
                            Holder<MobEffect> holder = instance.getEffect();
                            MobEffect effect = holder != null ? holder.value() : null;

                            String name = effect != null ? prettify(effect.getDescriptionId()) : "?";
                            if (amplifier > 0 && amplifier <= ROMAN.length) name += " " + ROMAN[amplifier - 1];
                            int seconds = duration / 20;
                            String time = (seconds >= 60 ? (seconds / 60) + "m " : "") + (seconds % 60) + "s";
                            UiColor color = UiTheme.ACCENT;
                            if (effect != null) {
                                int rgb = effect.getColor();
                                color = new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
                            }
                            rows.add(new EffectRow(name, time, color));
                        }
                        return rows;
                    }
                }
            } catch (Throwable ignored) {
                rows.clear();
            }
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return rows;
                Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                if (player == null) return rows;

                // BUG TROUVÉ (audit modules, voir historique de session) :
                // "getStatusEffectInstances" (nom Yarn 1.8.9) n'existe plus en
                // 1.16.5 — renommé "getStatusEffects" (mappings 1.16.5 :
                // () Ljava/util/Collection; dh method_6026 getStatusEffects).
                // Vrai renommage de nom Yarn entre versions, pas juste une
                // forme différente — essaie les deux.
                Method getInstances = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStatusEffectInstances");
                if (getInstances == null) {
                    getInstances = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStatusEffects");
                }
                if (getInstances == null) {
                    // 26.1+ : renommé une 3e fois — "getActiveEffects" (vérifié
                    // par javap sur le jar client 26.1.2 réel, LivingEntity).
                    getInstances = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getActiveEffects");
                }
                if (getInstances == null) return rows;
                Collection<?> effects = (Collection<?>) getInstances.invoke(player);
                if (effects == null || effects.isEmpty()) return rows;

                // BUG TROUVÉ (audit modules, voir historique de session) :
                // StatusEffectInstance.getEffectId() (int) + StatusEffect.STATUS_EFFECTS
                // (tableau statique indexé par cet int) n'existent PLUS depuis la
                // refonte "Flattening" (~1.13) — remplacés par getEffectType(),
                // qui renvoie DIRECTEMENT l'objet StatusEffect, sans tableau à
                // indexer. Résolu dynamiquement (essaie l'ancien chemin d'abord,
                // sinon le nouveau) plutôt que de figer un seul des deux.
                Class<?> instanceClass = null;
                for (Object instance : effects) {
                    if (instanceClass == null) instanceClass = instance.getClass();

                    int amplifier = (int) McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getAmplifier").invoke(instance);
                    int duration = (int) McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getDuration").invoke(instance);
                    Object effect = resolveEffect(instanceClass, instance);
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

        /**
         * Voir le commentaire dans currentRows() — nouveau chemin (getEffectType)
         * essayé d'abord, ancien (getEffectId + tableau) en repli.
         *
         * BUG TROUVÉ (1.21.4, "?" affiché à la place du nom/couleur de l'effet) :
         * {@code StatusEffectInstance.getEffectType()} ne renvoie plus un {@code
         * StatusEffect} directement — il renvoie un {@code RegistryEntry<StatusEffect>}
         * (vérifié dans les mappings 1.21.4 : {@code ()Ljr; c method_5579
         * getEffectType}, {@code jr} = {@code net/minecraft/registry/entry/RegistryEntry})
         * — EXACTEMENT le même changement "Holder"/RegistryEntry déjà rencontré
         * pour {@code World.getBiome(BlockPos)} (voir CoordsModule/historique de
         * session). {@code effectName()}/{@code effectColor()} appelaient
         * {@code getTranslationKey()}/{@code getColor()} DIRECTEMENT sur ce
         * RegistryEntry (qui n'a pas ces méthodes) → exception avalée → repli
         * "?"/couleur par défaut. Déballé ici via {@code RegistryEntry.value()}
         * (Yarn named, même méthode que pour le biome) avant de renvoyer l'effet.
         */
        private Object resolveEffect(Class<?> instanceClass, Object instance) {
            try {
                // 26.1+ : getEffectType→getEffect (StatusEffectInstance→MobEffectInstance,
                // vérifié par javap) — renvoie toujours un Holder à déballer.
                Method getEffectType = McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getEffectType", "getEffect");
                if (getEffectType != null) {
                    Object effect = getEffectType.invoke(instance);
                    return unwrapRegistryEntry(effect);
                }
            } catch (Throwable ignored) {}
            try {
                Method getEffectId = McReflect.noArgMethod(instanceClass, "net/minecraft/entity/effect/StatusEffectInstance", "getEffectId");
                if (getEffectId == null) return null;
                int effectId = (int) getEffectId.invoke(instance);
                Class<?> effectClass = McReflect.yarnClass("net/minecraft/entity/effect/StatusEffect");
                Object[] statusEffects = effectClass != null
                    ? (Object[]) McReflect.field(effectClass, "net/minecraft/entity/effect/StatusEffect", "STATUS_EFFECTS").get(null)
                    : null;
                return (statusEffects != null && effectId >= 0 && effectId < statusEffects.length) ? statusEffects[effectId] : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Class<?> registryEntryClass;
        private static Method registryEntryValueMethod;
        private static boolean registryEntryResolveAttempted;

        /** Déballe un {@code RegistryEntry<T>} vers son {@code T} réel via {@code value()} — voir resolveEffect(). {@code obj} tel quel si ce n'en est pas un (repli 1.16.5/1.20.4 où getEffectType renvoie déjà le StatusEffect direct). */
        private Object unwrapRegistryEntry(Object obj) {
            if (obj == null) return null;
            if (!registryEntryResolveAttempted) {
                registryEntryResolveAttempted = true;
                // 26.1+ : RegistryEntry→Holder (net.minecraft.core.Holder,
                // vérifié par javap) — méthode value() inchangée.
                registryEntryClass = McReflect.yarnClass("net/minecraft/registry/entry/RegistryEntry", "net.minecraft.core.Holder");
                if (registryEntryClass != null) {
                    registryEntryValueMethod = McReflect.noArgMethod(registryEntryClass, "net/minecraft/registry/entry/RegistryEntry", "value");
                }
            }
            if (registryEntryClass != null && registryEntryValueMethod != null && registryEntryClass.isInstance(obj)) {
                try {
                    return registryEntryValueMethod.invoke(obj);
                } catch (Throwable ignored) {
                    return obj;
                }
            }
            return obj;
        }

        private String effectName(Object effect) {
            if (effect == null) return "?";
            try {
                // 26.1+ : getTranslationKey→getDescriptionId (StatusEffect→MobEffect, vérifié javap).
                Method getKey = McReflect.noArgMethod(effect.getClass(), "net/minecraft/entity/effect/StatusEffect", "getTranslationKey", "getDescriptionId");
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
