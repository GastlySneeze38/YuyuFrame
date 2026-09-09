package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
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

    /**
     * 0 = « Personnalisé » (un seul panneau, pastille de couleur), 1 =
     * « Vanilla » (une boîte par effet, VRAIE icône du jeu) — même paire de
     * styles que {@code ArmorDurabilityModule}, à la demande de
     * l'utilisateur. Voir {@code Renderer.drawVanilla}.
     */
    public int style = 0;

    @Override
    protected void settings(SettingList s) {
        s.dropdown("style", "Style",
            "\"Personnalisé\" = un panneau unique, pastille de la couleur du liquide. \"Vanilla\" = une boîte par effet avec la VRAIE icône du jeu, alignée sur le bord d'écran le plus proche (centrée si le HUD ne touche ni la gauche ni la droite).",
            "Réglages", new String[]{ "Personnalisé", "Vanilla" }, null,
            () -> style, v -> style = v);
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
        RENDERER.vanillaStyle = style == 1;
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

        // ── Style « Vanilla » (voir drawVanilla) ──────────────────────────
        //
        // Toutes ces valeurs ont été multipliées par ~1,8 le 2026-08-31 après
        // comparaison directe avec la maquette de l'utilisateur (« trop
        // petit ») : à la première passe, une boîte faisait à peu près la
        // moitié de la hauteur de la référence. Elles restent des unités de
        // dessin, multipliées ensuite par l'échelle propre du HUD.
        /** Taille de l'icône vanilla, en unités de dessin (×{@code scale}). */
        private static final float V_ICON = 26f;
        private static final float V_PAD_X = 9f;
        private static final float V_GAP = 7f;
        private static final float V_ROW_H = 43f;
        private static final float V_RADIUS = 9f;
        private static final float V_BORDER = 2.5f;
        // Réduites le 2026-08-31 (retour utilisateur : « le texte est
        // beaucoup trop grand pour la taille de la box »). La boîte et
        // l'icône gardent leur taille, seul le texte redescend — c'est le
        // rapport entre les deux qui n'allait pas, pas l'échelle générale.
        private static final float V_NAME_SCALE = 0.55f;
        private static final float V_TIME_SCALE = 0.40f;

        /**
         * Hauteur laissée VIDE en bas de chaque boîte, sous le texte —
         * demandée explicitement (« vanilla laisse une petite marge entre le
         * dessous du texte et le bord de la box pour que le chevauchement soit
         * plus prononcé »).
         *
         * <p>C'est ce qui rend le chevauchement lisible : la boîte suivante
         * vient se superposer sur cette bande, donc sur du vide, au lieu de
         * frôler les glyphes de la durée. Sans elle, l'empilement se lit comme
         * un défaut d'alignement plutôt que comme un effet voulu.
         *
         * <p>Doit rester supérieure à {@link #V_ROW_OVERLAP}, sinon la boîte
         * du dessous mord de nouveau sur le texte.
         */
        private static final float V_BOTTOM_MARGIN = 8f;
        /**
         * CHEVAUCHEMENT vertical entre deux boîtes consécutives — valeur
         * NÉGATIVE d'espacement, demandée explicitement (« il n'y a pas ce
         * chevauchement comme sur vanilla »).
         *
         * <p>C'est ce qui donne l'aspect « chaîne » de la référence : la boîte
         * du dessous, dessinée APRÈS, recouvre le contour bas de celle du
         * dessus sur cette hauteur, et les deux liserés se fondent en un seul
         * trait au lieu de laisser deux lignes parallèles séparées par un
         * vide. Réglé à deux fois l'épaisseur du contour, exactement de quoi
         * absorber les deux liserés qui se font face.
         */
        private static final float V_ROW_OVERLAP = V_BORDER * 2f;
        /** Repli de largeur quand aucun effet n'est actif (le HUD garde une taille manipulable dans l'éditeur). */
        private static final float V_FALLBACK_WIDTH = 160f;

        /** Mutables directement par PotionEffectsModule.onConfigChanged(). */
        volatile boolean showDuration = true;
        volatile int sortMode = 0;
        volatile float blinkSeconds = 5f;
        volatile boolean vanillaStyle = false;

        private static final class EffectRow {
            final String name, time;
            final UiColor color;
            /** Secondes restantes, {@code -1} pour un effet infini — sert au clignotement et au tri. */
            final int secondsLeft;
            final boolean beneficial;
            /**
             * Chemin de registre de l'effet ({@code speed},
             * {@code hero_of_the_village}…) — c'est LUI qui nomme la texture
             * vanilla, voir {@link Renderer#iconOf}. {@code null} si le
             * {@code Holder} n'est pas enregistré (effet d'un mod tiers non
             * résolu, par exemple).
             */
            final String iconKey;
            EffectRow(String name, String time, UiColor color, int secondsLeft, boolean beneficial, String iconKey) {
                this.name = name; this.time = time; this.color = color;
                this.secondsLeft = secondsLeft; this.beneficial = beneficial;
                this.iconKey = iconKey;
            }
        }

        // Même correctif de perf que sur ArmorDurabilityModule : currentRows()
        // était appelé DEUX fois par frame (naturalSize() puis draw()) pour la
        // même donnée, alors que le framework HUD garantit naturalSize() en
        // premier. Chaque appel parcourait les effets, allouait une liste et
        // deux chaînes par effet.
        private List<EffectRow> cachedRows;
        private long cachedAtMs;

        /**
         * Période de rafraîchissement des lignes — 50 ms, soit un TICK de jeu.
         *
         * <p>AUDIT PERF (2026-08-31) : {@code computeRows()} tournait à chaque
         * FRAME et allouait une liste, un {@code EffectRow}, une
         * {@code UiColor} et deux chaînes PAR EFFET. Avec sept effets à 120
         * fps, ça faisait plusieurs milliers d'objets par seconde pour un
         * panneau qui ne peut pas changer plus vite qu'un tick.
         *
         * <p>Ce n'est pas une approximation : les durées sont exprimées en
         * ticks, elles ne changent donc que 20 fois par seconde. Recalculer
         * plus souvent produisait exactement la même chose.
         */
        private static final long ROWS_REFRESH_MS = 50L;

        @Override
        public float[] naturalSize() {
            // Premier appel du cycle HUD de ce frame — c'est ICI qu'on
            // rafraîchit ; draw() réutilise.
            long now = System.currentTimeMillis();
            if (cachedRows == null || now - cachedAtMs >= ROWS_REFRESH_MS) {
                cachedRows = computeRows();
                cachedAtMs = now;
            }

            if (vanillaStyle) {
                if (cachedRows.isEmpty()) return new float[]{ V_FALLBACK_WIDTH, V_ROW_H };
                float maxBoxW = 0f;
                for (EffectRow row : cachedRows) maxBoxW = Math.max(maxBoxW, vanillaBoxWidth(row));
                // Les boîtes se CHEVAUCHENT : la hauteur totale retire un
                // chevauchement par intervalle, sinon la boîte englobante du
                // HUD serait plus haute que ce qui se dessine et laisserait un
                // vide en bas (visible dans l'éditeur).
                return new float[]{
                    maxBoxW,
                    cachedRows.size() * V_ROW_H - (cachedRows.size() - 1) * V_ROW_OVERLAP
                };
            }

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
                        vanillaStyle ? clockText(instance) : durationText(instance),
                        colorOf(effect),
                        instance.isInfiniteDuration() ? -1 : instance.getDuration() / 20,
                        effect != null && effect.isBeneficial(),
                        registryPath(holder)));
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

        /**
         * Format horloge {@code MM:SS} du style « Vanilla » — la maquette
         * fournie par l'utilisateur l'affiche ainsi. Le style « Personnalisé »
         * garde son {@code 1m 5s}, plus court quand la place manque.
         */
        private String clockText(MobEffectInstance instance) {
            if (instance.isInfiniteDuration()) return "∞";
            int seconds = Math.max(0, instance.getDuration() / 20);
            int m = seconds / 60, s = seconds % 60;
            return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
        }

        /**
         * Chemin de registre de l'effet ({@code speed},
         * {@code hero_of_the_village}…), qui nomme aussi sa texture.
         *
         * <p>{@code unwrapKey()} et {@code identifier()} sont les VRAIS noms
         * 26.1.2 — deux pièges déjà payés au prix fort sur le biome de
         * {@code CoordsModule} (les noms « évidents » {@code getKey()} /
         * {@code getValue()} n'existent pas), voir les javadocs des stubs
         * {@code Holder} et {@code ResourceKey}.
         */
        private String registryPath(Holder<MobEffect> holder) {
            if (holder == null) return null;
            try {
                java.util.Optional<net.minecraft.resources.ResourceKey<MobEffect>> key = holder.unwrapKey();
                if (key == null || !key.isPresent()) return null;
                net.minecraft.resources.Identifier id = key.get().identifier();
                return id != null ? id.getPath() : null;
            } catch (Throwable t) {
                if (!keyErrorLogged) {
                    keyErrorLogged = true;
                    LauncherLog.err("[PotionEffectsModule] registryPath: " + t);
                }
                return null;
            }
        }

        private static boolean keyErrorLogged;

        // ── Textures d'effets vanilla ─────────────────────────────────────
        //
        // Un PNG 18x18 par effet dans le jar du jeu — PAS un atlas, chaque
        // effet a son propre fichier (vérifié : 40 fichiers sous
        // assets/minecraft/textures/mob_effect/). Chargement paresseux et une
        // seule fois par effet, y compris en cas d'échec : la valeur nulle est
        // mémorisée, sinon un effet moddé sans texture relancerait une lecture
        // de ressource à chaque frame.
        //
        // Même mécanisme que CrosshairModule.loadVanillaSprite — c'est le
        // classloader du jeu qui sert le jar, on ne l'ouvre pas nous-mêmes.
        private static final java.util.Map<String, java.awt.image.BufferedImage> ICONS =
            new java.util.HashMap<String, java.awt.image.BufferedImage>();

        private java.awt.image.BufferedImage iconOf(String registryPath) {
            if (registryPath == null) return null;
            if (ICONS.containsKey(registryPath)) return ICONS.get(registryPath);
            java.awt.image.BufferedImage img = null;
            try {
                String path = "assets/minecraft/textures/mob_effect/" + registryPath + ".png";
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                java.io.InputStream in = cl != null ? cl.getResourceAsStream(path) : null;
                if (in == null) in = PotionEffectsModule.class.getClassLoader().getResourceAsStream(path);
                if (in != null) {
                    try {
                        img = javax.imageio.ImageIO.read(in);
                    } finally {
                        in.close();
                    }
                }
            } catch (Throwable t) {
                if (!iconErrorLogged) {
                    iconErrorLogged = true;
                    LauncherLog.err("[PotionEffectsModule] iconOf('" + registryPath + "'): " + t);
                }
            }
            ICONS.put(registryPath, img);
            return img;
        }

        private static boolean iconErrorLogged;

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

        /** Le style « Vanilla » dessine SA PROPRE boîte par effet — pas de panneau HUD générique par-dessus (même mécanisme qu'{@code ArmorDurabilityModule}). */
        @Override
        public boolean skipBackground() {
            return vanillaStyle;
        }

        /**
         * Largeur EXTÉRIEURE d'une boîte (contour compris), ajustée à SON
         * contenu — c'est ce qui rend les boîtes de largeurs différentes, donc
         * l'alignement visible. Extérieure et non intérieure pour que
         * {@code naturalSize()} et {@code drawVanilla()} parlent de la même
         * chose : une boîte alignée sur un bord doit voir son CONTOUR toucher
         * ce bord, pas son remplissage.
         */
        private float vanillaBoxWidth(EffectRow row) {
            float textW = Math.max(
                UiFont.REGULAR.textWidth(row.name, V_NAME_SCALE),
                showDuration ? UiFont.REGULAR.textWidth(row.time, V_TIME_SCALE) : 0f);
            return 2f * V_BORDER + V_PAD_X * 2f + V_ICON + V_GAP + textW;
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            if (vanillaStyle) {
                drawVanilla(renderer, x, y, w, h, scale, vpWidth, vpHeight);
                return;
            }
            drawCustom(renderer, x, y, w, h, scale, vpWidth, vpHeight);
        }

        /**
         * Style « Vanilla » — une boîte par effet, taillée à son contenu, avec
         * la VRAIE icône du jeu (maquette fournie par l'utilisateur).
         *
         * <h2>Alignement (la demande précise)</h2>
         *
         * Les boîtes n'ont pas la même largeur ; il faut donc décider de quel
         * côté elles s'alignent dans la boîte englobante du HUD :
         * <ul>
         *   <li>HUD collé au bord GAUCHE → boîtes alignées à gauche ;</li>
         *   <li>collé au bord DROIT → alignées à droite, et le contenu est
         *       MIROITÉ : icône côté droit, textes alignés à droite, pour que
         *       les icônes restent en colonne contre le bord ;</li>
         *   <li>ni l'un ni l'autre → boîtes centrées.</li>
         * </ul>
         *
         * <h2>Pourquoi PAS {@code EDGE_TOLERANCE}</h2>
         *
         * La première version testait le contact strict au bord (2 pixels,
         * la tolérance qui sert à mettre les coins au carré). Elle ne se
         * déclenchait JAMAIS : la marge par défaut d'un élément HUD est de 8
         * unités, il n'est donc jamais « collé » au sens de ces 2 pixels, et
         * tout retombait sur le centrage — le bug signalé (« le truc de quand
         * c'est collé à un coin ne marche pas »). On raisonne désormais en
         * BANDES : le HUD appartient au bord dont il est à moins de
         * {@link #V_SIDE_BAND} de la largeur d'écran. Une marge de 8 pixels
         * tombe évidemment dedans, un HUD réellement au milieu n'y tombe pas.
         */
        private void drawVanilla(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            List<EffectRow> rows = currentRows();
            if (rows.isEmpty()) return;

            float band = vpWidth * V_SIDE_BAND;
            boolean nearLeft = x < band;
            boolean nearRight = (x + w) > vpWidth - band;
            // Dans les deux bandes à la fois (HUD presque aussi large que
            // l'écran, ou écran très étroit) : on centre — choisir un bord
            // serait arbitraire.
            int align = (nearLeft == nearRight) ? 0 : (nearLeft ? -1 : 1); // -1 gauche, 0 centre, 1 droite

            float rowH = V_ROW_H * scale, overlap = V_ROW_OVERLAP * scale;
            float icon = V_ICON * scale, padX = V_PAD_X * scale, gap = V_GAP * scale;
            float border = V_BORDER * scale, radius = V_RADIUS * scale;

            // Repère Y-montant : la PREMIÈRE ligne est en haut de la boîte
            // englobante, donc au Y le plus grand.
            float top = y + h;
            for (EffectRow row : rows) {
                float outerW = vanillaBoxWidth(row) * scale;
                float outerX = align < 0 ? x : (align > 0 ? x + w - outerW : x + (w - outerW) * 0.5f);
                float outerBottom = top - rowH;

                // Contour puis remplissage INSÉRÉ : deux rects arrondis, donc
                // même pipeline et même texture — ils se regroupent en un seul
                // maillage (voir GuiRenderer.addElementToMesh). Le coût réel
                // est un draw call pour tout le panneau, pas deux par ligne.
                //
                // Chaque boîte est dessinée APRÈS la précédente : là où elles
                // se chevauchent, c'est le contour de la boîte du dessous qui
                // l'emporte, et les deux liserés se lisent comme un seul trait
                // — l'aspect « chaîne » de la référence.
                renderer.drawRoundedRect(outerX, outerBottom, outerX + outerW, top,
                    radius, borderColor(row), vpWidth, vpHeight);
                renderer.drawRoundedRect(outerX + border, outerBottom + border, outerX + outerW - border, top - border,
                    Math.max(0f, radius - border), UiTheme.PANEL_BG, vpWidth, vpHeight);

                // BANDE DE CONTENU : tout ce qui se dessine (icône comprise)
                // vit entre le haut de la boîte et la marge basse — la bande
                // que la boîte suivante recouvrira reste vide. Centrer sur la
                // boîte ENTIÈRE, comme le faisait la version précédente,
                // ramenait le texte contre le bord bas et annulait l'effet.
                float bandBottom = outerBottom + border + V_BOTTOM_MARGIN * scale;
                float bandTop = top - border;
                float bandH = bandTop - bandBottom;

                boolean mirrored = align > 0;
                float innerLeft = outerX + border + padX;
                float innerRight = outerX + outerW - border - padX;
                float iconX = mirrored ? innerRight - icon : innerLeft;
                float iconY = bandBottom + (bandH - icon) * 0.5f;
                drawEffectIcon(renderer, row, iconX, iconY, icon, vpWidth, vpHeight);

                float nameW = UiFont.REGULAR.textWidth(row.name, V_NAME_SCALE * scale);
                float timeW = UiFont.REGULAR.textWidth(row.time, V_TIME_SCALE * scale);
                float textRight = iconX - gap;
                float textLeft = iconX + icon + gap;
                float nameX = mirrored ? textRight - nameW : textLeft;
                float timeX = mirrored ? textRight - timeW : textLeft;

                // Fractions de la BANDE, pas de la boîte : deux lignes quand
                // la durée est affichée, une seule sinon (le nom se recentre
                // alors au lieu de flotter en haut).
                float nameBaseline = bandBottom + (showDuration ? bandH * 0.52f : bandH * 0.32f);
                renderer.drawText(row.name, nameX, nameBaseline, UiTheme.TEXT_PRIMARY, V_NAME_SCALE * scale, vpWidth, vpHeight);
                if (showDuration) {
                    renderer.drawText(row.time, timeX, bandBottom + bandH * 0.14f,
                        timeColor(row), V_TIME_SCALE * scale, vpWidth, vpHeight);
                }

                top -= rowH - overlap;
            }
        }

        /**
         * Largeur de la bande, en fraction de l'écran, dans laquelle un HUD
         * est considéré comme appartenant à ce bord — voir
         * {@link #drawVanilla}. 20 % : assez large pour attraper les marges
         * usuelles (8 unités) comme un placement « à gauche » à vue d'œil,
         * assez étroit pour qu'un HUD franchement au milieu reste centré.
         */
        private static final float V_SIDE_BAND = 0.20f;

        /**
         * Vraie icône vanilla, ou pastille de couleur en repli — un effet de
         * mod sans texture sous {@code mob_effect/} ne doit pas laisser un
         * trou dans la boîte.
         */
        private void drawEffectIcon(UiRenderer renderer, EffectRow row, float iconX, float iconY, float icon,
                                    int vpWidth, int vpHeight) {
            java.awt.image.BufferedImage img = iconOf(row.iconKey);
            if (img != null) {
                renderer.drawIcon("mob_effect/" + row.iconKey, img, iconX, iconY, icon, icon, vpWidth, vpHeight);
            } else {
                renderer.drawRoundedRect(iconX, iconY, iconX + icon, iconY + icon, icon / 2f, row.color, vpWidth, vpHeight);
            }
        }

        /** Contour teinté par la NATURE de l'effet — bénéfique dans l'accent du thème, néfaste en rouge : lisible d'un coup d'œil sans lire les noms. */
        private UiColor borderColor(EffectRow row) {
            return row.beneficial ? UiTheme.ACCENT : UiTheme.DANGER;
        }

        private void drawCustom(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
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
