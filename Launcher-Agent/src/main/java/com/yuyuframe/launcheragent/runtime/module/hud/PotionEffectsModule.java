package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

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
            // Joueur par l'accessor Mixin (PlayerData) + getActiveEffects()/
            // getAmplifier()/getDuration()/getEffect()/value() (méthodes
            // publiques, voir stubs LocalPlayer/MobEffectInstance/Holder) —
            // zéro réflexion.
            //
            // Le repli réflexif multi-bracket a été supprimé le 2026-08-27. À
            // savoir avant tout portage vers un autre bracket : cette méthode a
            // été RENOMMÉE trois fois — getStatusEffectInstances (1.8.9) →
            // getStatusEffects (1.16.5) → getActiveEffects (26.1) ; et avant la
            // ~1.13 ("Flattening"), l'effet se lisait via getEffectId() (int)
            // indexant le tableau statique StatusEffect.STATUS_EFFECTS, tous
            // deux disparus depuis au profit d'un accès direct à l'objet.
            try {
                LocalPlayer player = PlayerData.player();
                if (player == null) return rows;
                Collection<MobEffectInstance> effects = player.getActiveEffects();
                if (effects == null) return rows;

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
            } catch (Throwable ignored) {
                rows.clear();
            }
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

    }
}
