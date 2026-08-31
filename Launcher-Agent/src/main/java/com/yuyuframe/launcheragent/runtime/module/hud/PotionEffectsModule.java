package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

/**
 * Port de PvP-Mod PotionEffectsConfig/PotionEffectsHud — sa propre carte,
 * comme dans la référence.
 *
 * <p>Rework du 2026-08-31 (audit à la demande de l'utilisateur) — sept
 * défauts corrigés d'un coup, détaillés à leur point d'application :
 * amplificateur décalé d'un cran, noms anglais bricolés, durée infinie
 * affichée « 0s », double calcul par frame, ordre d'affichage instable,
 * {@code catch} muet, amplificateurs au-delà de X perdus. Les VRAIES icônes
 * vanilla ({@code assets/minecraft/textures/mob_effect/*.png}) restent en
 * attente : elles demandent un pipeline texturé dans l'état de GUI, sans quoi
 * elles passeraient au-dessus du chat.
 */
public final class PotionEffectsModule extends SingleHudModule {

    /** Instance unique — même raison que {@code ArmorDurabilityModule.RENDERER} : le renderer part dans le {@code super(...)}, avant que ce constructeur n'ait fini. */
    private static final Renderer RENDERER = new Renderer();

    public boolean showDuration = true;
    /** 0 = bénéfiques d'abord, 1 = fin la plus proche, 2 = alphabétique. */
    public int sortMode = 0;
    /** Secondes restantes sous lesquelles la durée clignote ; 0 = jamais. */
    public float blinkSeconds = 5f;

    @Override
    protected void settings(SettingList s) {
        s.toggle("showDuration", "Afficher la durée",
            "La seconde ligne sous chaque effet. Décoché, le panneau se réduit à une ligne par effet.",
            "Réglages", null, () -> showDuration, v -> showDuration = v);
        s.dropdown("sortMode", "Tri",
            "L'ordre renvoyé par le jeu n'est pas garanti stable : sans tri explicite, la liste peut se réordonner d'une frame à l'autre.",
            "Réglages", new String[]{ "Bénéfiques d'abord", "Fin la plus proche", "Alphabétique" }, null,
            () -> sortMode, v -> sortMode = v);
        s.slider("blinkSeconds", "Clignotement en fin d'effet (s)",
            "La durée se met à pulser sous ce nombre de secondes restantes — comme le fait vanilla sur ses propres icônes. 0 pour désactiver.",
            "Réglages", 0f, 30f, 1f, null,
            () -> blinkSeconds, v -> blinkSeconds = v);
    }

    public PotionEffectsModule() {
        super("potion-effects", "Effets de potion", "Liste des effets de potion actifs", false,
            new HudElement("potion-effects", "Effets de potion", HudAnchor.TOP_RIGHT, 8f, 40f,
                (HudElement.CustomRenderer) RENDERER),
            HookPoint.HUD_EXTRACT_EFFECTS);
        iconUrl = icons8("test-tube");

        // BUG TROUVÉ (audit modules 2026-08-25, §19) : dessinait sa propre
        // liste d'effets SANS supprimer les icônes vanilla natives — mixin
        // HudExtractEffectsMixin261/HookPoint HUD_EXTRACT_EFFECTS déjà
        // existants, jamais câblés faute de relais dans SingleHudModule.
        //
        // Câblage LÉGITIME ici, contrairement à celui d'ArmorDurabilityModule
        // (retiré le 2026-08-31) : Gui.extractEffects dessine bien les icônes
        // d'effets actifs, c'est-à-dire EXACTEMENT ce que ce module réaffiche
        // à sa façon. Il y a donc un vrai doublon à supprimer.
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_EFFECTS, ctx -> isEnabled());
    }

    @Override
    public void onConfigChanged() {
        RENDERER.showDuration = showDuration;
        RENDERER.sortMode = sortMode;
        RENDERER.blinkSeconds = blinkSeconds;
    }

    /**
     * Icône = pastille de la couleur du liquide ({@code MobEffect.getColor()}),
     * PAS l'icône vanilla réelle : l'original faisait déjà ce choix. Le
     * remplacement par les vraies textures {@code mob_effect/*.png} est
     * décidé mais reporté — voir la javadoc de classe.
     */
    private static final class Renderer implements HudElement.CustomRenderer {
        private static final String[] ROMAN = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
        // 28, pas 18 : chaque effet dessine DEUX lignes (nom au-dessus, durée
        // en-dessous — voir drawRow), pas une seule — leur écart réel (nom à
        // ty, durée à ty-12) dépassait déjà les 18px d'espacement entre deux
        // effets consécutifs, faisant chevaucher la durée d'un effet avec le
        // nom du suivant ("les effets se chevauchent").
        private static final float LINE_H = 28f;
        /** Hauteur d'une ligne quand la durée est masquée — il n'y a plus qu'un texte à loger. */
        private static final float LINE_H_COMPACT = 16f;
        private static final float ICON = 10f;
        private static final float NAME_SCALE = 0.36f;
        private static final float TIME_SCALE = 0.3f;
        /** Repli avant que le joueur/les effets ne soient connus (comme FPS/Ping). */
        private static final float FALLBACK_WIDTH = 100f;
        private static final int FALLBACK_COUNT = 2;

        /** Mutables directement par PotionEffectsModule.onConfigChanged(). */
        volatile boolean showDuration = true;
        volatile int sortMode = 0;
        volatile float blinkSeconds = 5f;

        private static final class EffectRow {
            final String name, time;
            final UiColor color;
            /** Secondes restantes, {@code -1} pour un effet infini — sert au clignotement et au tri. */
            final int secondsLeft;
            final boolean beneficial;
            EffectRow(String name, String time, UiColor color, int secondsLeft, boolean beneficial) {
                this.name = name; this.time = time; this.color = color;
                this.secondsLeft = secondsLeft; this.beneficial = beneficial;
            }
        }

        // Même correctif de perf que sur ArmorDurabilityModule : currentRows()
        // était appelé DEUX fois par frame (naturalSize() puis draw()) pour la
        // même donnée, alors que le framework HUD garantit naturalSize() en
        // premier. Chaque appel parcourait les effets, allouait une liste et
        // deux chaînes par effet.
        private List<EffectRow> cachedRows;

        @Override
        public float[] naturalSize() {
            // Premier appel du cycle HUD de ce frame — c'est ICI qu'on
            // rafraîchit ; draw() réutilise.
            cachedRows = computeRows();

            if (cachedRows.isEmpty()) return new float[]{ FALLBACK_WIDTH, FALLBACK_COUNT * lineHeight() };
            float maxTextW = 0f;
            for (EffectRow row : cachedRows) {
                maxTextW = Math.max(maxTextW, UiFont.REGULAR.textWidth(row.name, NAME_SCALE));
                if (showDuration) maxTextW = Math.max(maxTextW, UiFont.REGULAR.textWidth(row.time, TIME_SCALE));
            }
            float contentW = Math.max(FALLBACK_WIDTH, ICON + 5f + maxTextW);
            return new float[]{ contentW, cachedRows.size() * lineHeight() };
        }

        private float lineHeight() {
            return showDuration ? LINE_H : LINE_H_COMPACT;
        }

        private List<EffectRow> currentRows() {
            if (cachedRows == null) cachedRows = computeRows();
            return cachedRows;
        }

        private List<EffectRow> computeRows() {
            List<EffectRow> rows = new ArrayList<EffectRow>();
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
                    Holder<MobEffect> holder = instance.getEffect();
                    MobEffect effect = holder != null ? holder.value() : null;

                    rows.add(new EffectRow(
                        nameOf(effect, instance.getAmplifier()),
                        durationText(instance),
                        colorOf(effect),
                        instance.isInfiniteDuration() ? -1 : instance.getDuration() / 20,
                        effect != null && effect.isBeneficial()));
                }
                sort(rows);
            } catch (Throwable t) {
                // Jamais muet (norme du projet) — un échec ici vide le panneau
                // sans laisser la moindre trace. Une fois suffit : appelé à
                // chaque frame.
                if (!rowsErrorLogged) {
                    rowsErrorLogged = true;
                    LauncherLog.err("[PotionEffectsModule] computeRows: " + t);
                }
                rows.clear();
            }
            return rows;
        }

        private static boolean rowsErrorLogged;

        /**
         * BUG TROUVÉ (rework 2026-08-31) : l'ancien code faisait
         * {@code ROMAN[amplifier - 1]}, soit un cran de trop bas —
         * Rapidité II (amplificateur 1) s'affichait « Rapidité I », et
         * l'amplificateur 0 (niveau I) était le seul correct, par accident,
         * puisqu'il n'affichait rien. Le niveau vaut {@code amplifier + 1}.
         *
         * <p>Au-delà de X on retombe sur le chiffre arabe plutôt que de ne
         * RIEN afficher, comme le faisait le test {@code <= ROMAN.length} :
         * un effet de commande peut monter très haut, et « Force 42 » reste
         * infiniment plus lisible qu'un « XLII » de toute façon absent.
         */
        private String nameOf(MobEffect effect, int amplifier) {
            String name = displayName(effect);
            int level = amplifier + 1;
            if (level <= 1) return name;
            return name + " " + (level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level));
        }

        /**
         * Nom TRADUIT, par {@code MobEffect.getDisplayName()} — l'ancien
         * {@code prettify()} découpait la clé de traduction à la main
         * ({@code effect.minecraft.speed} → « Speed »), donc un nom anglais
         * quelle que soit la langue du jeu. Le commentaire d'époque
         * justifiait ce choix par « éviterait une nouvelle chaîne de
         * réflexion » : argument caduc depuis la migration aux accessors,
         * c'est un simple appel de méthode publique.
         */
        private String displayName(MobEffect effect) {
            if (effect == null) return "?";
            try {
                Component name = effect.getDisplayName();
                if (name != null) {
                    String text = name.getString();
                    if (text != null && !text.isEmpty()) return text;
                }
            } catch (Throwable t) {
                if (!nameErrorLogged) {
                    nameErrorLogged = true;
                    LauncherLog.err("[PotionEffectsModule] displayName: " + t);
                }
            }
            return "?";
        }

        private static boolean nameErrorLogged;

        /**
         * BUG TROUVÉ (rework 2026-08-31) : un effet INFINI s'affichait
         * « 0s ». {@code getDuration()} renvoie {@code -1} dans ce cas, et
         * {@code -1 / 20} vaut 0 en division entière — l'effet paraissait
         * donc expiré en permanence. {@code isInfiniteDuration()} lève
         * l'ambiguïté.
         */
        private String durationText(MobEffectInstance instance) {
            if (instance.isInfiniteDuration()) return "∞";
            int seconds = Math.max(0, instance.getDuration() / 20);
            return (seconds >= 60 ? (seconds / 60) + "m " : "") + (seconds % 60) + "s";
        }

        private UiColor colorOf(MobEffect effect) {
            if (effect == null) return UiTheme.ACCENT;
            int rgb = effect.getColor();
            return new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }

        /**
         * L'ordre de {@code getActiveEffects()} n'est garanti par rien : sans
         * tri explicite, la liste pouvait se réordonner d'une frame à l'autre.
         * Les trois modes se terminent tous par le NOM, ce qui rend l'ordre
         * total et donc parfaitement déterministe — sans ce départage, deux
         * effets de même durée pouvaient encore permuter.
         */
        private void sort(List<EffectRow> rows) {
            switch (sortMode) {
                case 1: // fin la plus proche d'abord ; les infinis en dernier
                    Collections.sort(rows, new Comparator<EffectRow>() {
                        @Override public int compare(EffectRow a, EffectRow b) {
                            boolean ai = a.secondsLeft < 0, bi = b.secondsLeft < 0;
                            if (ai != bi) return ai ? 1 : -1;
                            if (a.secondsLeft != b.secondsLeft) return a.secondsLeft - b.secondsLeft;
                            return a.name.compareTo(b.name);
                        }
                    });
                    break;
                case 2: // alphabétique pur
                    Collections.sort(rows, new Comparator<EffectRow>() {
                        @Override public int compare(EffectRow a, EffectRow b) { return a.name.compareTo(b.name); }
                    });
                    break;
                default: // bénéfiques d'abord — la séparation que fait vanilla
                    Collections.sort(rows, new Comparator<EffectRow>() {
                        @Override public int compare(EffectRow a, EffectRow b) {
                            if (a.beneficial != b.beneficial) return a.beneficial ? -1 : 1;
                            return a.name.compareTo(b.name);
                        }
                    });
                    break;
            }
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            List<EffectRow> rows = currentRows();
            float icon = ICON * scale, lineH = lineHeight() * scale;

            float ty = y + h - icon;
            for (EffectRow row : rows) {
                renderer.drawRoundedRect(x, ty, x + icon, ty + icon, icon / 2f, row.color, vpWidth, vpHeight);
                renderer.drawText(row.name, x + icon + 5f * scale, ty, UiTheme.TEXT_PRIMARY, NAME_SCALE * scale, vpWidth, vpHeight);
                if (showDuration) {
                    renderer.drawText(row.time, x + icon + 5f * scale, ty - 12f * scale,
                        timeColor(row), TIME_SCALE * scale, vpWidth, vpHeight);
                }
                ty -= lineH;
            }
        }

        /**
         * Pulsation de la durée en fin d'effet — vanilla fait clignoter ses
         * propres icônes sous 200 ticks, on transpose sur le texte.
         *
         * <p>Sinus plutôt qu'un créneau tout ou rien : un clignotement franc
         * dans un coin de l'écran est agressif, une pulsation attire l'œil
         * sans l'agresser. Jamais en dessous de 25 % d'opacité, pour rester
         * lisible au creux du cycle.
         */
        private UiColor timeColor(EffectRow row) {
            if (blinkSeconds <= 0f || row.secondsLeft < 0 || row.secondsLeft > blinkSeconds) {
                return UiTheme.TEXT_SECONDARY;
            }
            float phase = (System.currentTimeMillis() % 1000L) / 1000f;
            float pulse = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.cos(phase * 2.0 * Math.PI));
            return new UiColor(UiTheme.DANGER.r, UiTheme.DANGER.g, UiTheme.DANGER.b, pulse);
        }
    }
}
