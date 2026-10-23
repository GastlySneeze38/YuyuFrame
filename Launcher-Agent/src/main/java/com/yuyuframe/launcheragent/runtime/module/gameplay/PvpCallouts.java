package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpScoreUi;

import java.util.ArrayList;
import java.util.List;

/**
 * Les mots des bons coups, en haut de l'écran pendant le duel
 * (docs/scoring/classement.md § Les mots des bons coups) : « COMBO x4 »,
 * « W-TAP », « SMASH PARFAIT »… Le noyau les repère ({@code combat/live.rs}),
 * ce fichier les fait surgir, avec un son.
 *
 * <ul>
 *   <li>Un mot surgit en grossissant, rebondit, monte un peu et s'efface ;
 *       trois au plus, le nouveau en haut, les autres glissent dessous.</li>
 *   <li>Un combo ne s'empile pas : « COMBO x3 » devient « x4 » au même
 *       endroit, avec un rebond et une note un ton plus haut.</li>
 *   <li>Quatre raretés : blanc, vert, or, arc-en-ciel — plus rare, plus
 *       gros, plus long.</li>
 * </ul>
 *
 * <p>Il retient aussi, pour le panneau de fin de duel, le meilleur moment du
 * duel et les records personnels battus (plus long combo, plus haut smash).
 */
final class PvpCallouts implements HudElement.CustomRenderer {

    // Sortes : même liste que combat/live.rs ; GIANT vient de Java (après le classement).
    static final int COMBO = 1, WTAP = 2, CRIT = 3, SMASH = 4, BIG_SMASH = 5, PERFECT_SMASH = 6,
        SHIELD_BROKEN = 7, BLOCKED = 8, TOTEM = 9, CLUTCH = 10, GIANT = 11;

    private static final float WIDTH = 280f;
    private static final float HEIGHT = 100f;
    private static final int VISIBLE = 3;
    /** Durée d'un mot, par rareté (1 à 4). */
    private static final float[] LIFE = { 0f, 1.3f, 1.6f, 2.0f, 2.6f };
    /** Taille d'un mot, par rareté. */
    private static final float[] SIZE = { 0f, 0.82f, 0.96f, 1.15f, 1.35f };
    /** Les mots plus anciens, sous le nouveau, rapetissent : le dernier coup reste celui qu'on lit. */
    private static final float OLDER = 0.68f;
    private static final UiColor WHITE = new UiColor(242, 242, 248, 255);
    private static final UiColor GOLD = new UiColor(255, 206, 72, 255);
    /** Deux sons trop rapprochés n'en font qu'un. */
    private static final long SOUND_GAP_NANOS = 60_000_000L;

    private static final class Word {
        int kind, tier, value;
        long bornNanos;
        long popNanos;
        /** Position animée dans la pile (0 = en haut). */
        float slot = -1f;
    }

    private final List<Word> words = new ArrayList<>();
    private final int[] buffer = new int[30];
    private long lastSoundNanos;

    // ── Le duel en cours, pour le panneau de fin ──
    private long duelStartNanos = System.nanoTime();
    private int bestKind, bestTier, bestValue;
    private float bestAt = -1f;
    /** Un record battu ce duel : « combo de 9 », « smash de 14 blocs » ; vide sinon. */
    private String newRecord = "";

    // ── Records personnels (réglage opaque du module) ──
    private int recordCombo;
    private int recordSmashTenths;
    private boolean recordsDirty;

    /** Relit les mots déposés par le noyau. */
    void poll() {
        int n = NativeCombat.callouts(buffer);
        for (int i = 0; i < n; i++) {
            add(buffer[i * 3], buffer[i * 3 + 1], buffer[i * 3 + 2]);
        }
    }

    /** Un duel s'ouvre : on oublie le meilleur moment du précédent. */
    void duelStarted() {
        duelStartNanos = System.nanoTime();
        bestKind = 0;
        bestTier = 0;
        bestValue = 0;
        bestAt = -1f;
        newRecord = "";
    }

    /** Le réglage « Mots des bons coups » : éteint, ni mot ni son — le meilleur moment et les records restent. */
    volatile boolean visible = true;

    void add(int kind, int tier, int value) {
        tier = Math.max(1, Math.min(4, tier));
        long now = System.nanoTime();
        if (!visible) {
            remember(kind, tier, value, now);
            return;
        }
        Word top = words.isEmpty() ? null : words.get(0);
        if (kind == COMBO && top != null && top.kind == COMBO && alive(top, now)) {
            // Le combo grandit sur place.
            top.value = value;
            top.tier = Math.max(top.tier, tier);
            top.bornNanos = now;
            top.popNanos = now;
        } else {
            Word word = new Word();
            word.kind = kind;
            word.tier = tier;
            word.value = value;
            word.bornNanos = now;
            word.popNanos = now;
            words.add(0, word);
            while (words.size() > VISIBLE) words.remove(words.size() - 1);
        }
        sound(kind, tier, value, now);
        remember(kind, tier, value, now);
    }

    private void sound(int kind, int tier, int value, long now) {
        if (now - lastSoundNanos < SOUND_GAP_NANOS) return;
        lastSoundNanos = now;
        if (kind == COMBO) {
            // Un ton plus haut à chaque coup du combo : on veut entendre la suivante.
            PvpSounds.play(PvpSounds.PLING, 0.9f + 0.12f * (value - 3), 0.7f);
            return;
        }
        switch (tier) {
            case 1 -> PvpSounds.play(PvpSounds.PLING, 1.5f, 0.5f);
            case 2 -> PvpSounds.play(PvpSounds.BELL, 1.3f, 0.6f);
            case 3 -> PvpSounds.play(PvpSounds.ORB, 0.9f, 0.8f);
            default -> PvpSounds.play(PvpSounds.LEVEL_UP, 1.2f, 0.7f);
        }
    }

    /** Le meilleur moment du duel (le plus rare, puis la plus grosse valeur), et les records. */
    private void remember(int kind, int tier, int value, long now) {
        if (tier > bestTier || (tier == bestTier && value >= bestValue)) {
            bestKind = kind;
            bestTier = tier;
            bestValue = value;
            bestAt = kind == GIANT || kind == CLUTCH ? -1f : (now - duelStartNanos) / 1e9f;
        }
        if (kind == COMBO && value > recordCombo) {
            // Le premier combo jamais vu n'est pas un « record » : il n'y avait rien à battre.
            if (recordCombo >= 3) newRecord = Lang.tr("combo de") + " " + value;
            recordCombo = value;
            recordsDirty = true;
        }
        if ((kind == SMASH || kind == BIG_SMASH || kind == PERFECT_SMASH) && value > recordSmashTenths) {
            if (recordSmashTenths > 0) newRecord = Lang.tr("smash de") + " " + Math.round(value / 10f) + " " + Lang.tr("blocs");
            recordSmashTenths = value;
            recordsDirty = true;
        }
    }

    // ── Pour le panneau de fin de duel ──

    /** « NOUVEAU RECORD : combo de 9 », « Meilleur moment : SMASH PARFAIT à 0:42 », ou vide. */
    String moment() {
        if (!newRecord.isEmpty()) return Lang.tr("NOUVEAU RECORD :") + " " + newRecord;
        if (bestTier == 0) return "";
        String text = Lang.tr("Meilleur moment :") + " " + label(bestKind, bestValue);
        if (bestAt >= 0f) {
            int seconds = Math.round(bestAt);
            text += " " + Lang.tr("à") + " " + seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        }
        return text;
    }

    UiColor momentColor() {
        return !newRecord.isEmpty() ? GOLD : color(bestTier, 0, 0f);
    }

    // ── Records : « combo=9;smash=140 » ──

    String records() {
        return "combo=" + recordCombo + ";smash=" + recordSmashTenths;
    }

    void loadRecords(String text) {
        if (text == null) return;
        for (String part : text.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            try {
                int value = Integer.parseInt(kv[1].trim());
                if ("combo".equals(kv[0].trim())) recordCombo = value;
                else if ("smash".equals(kv[0].trim())) recordSmashTenths = value;
            } catch (NumberFormatException ignored) {
                // Une valeur illisible garde son défaut : un record se rebat.
            }
        }
    }

    /** Un record a bougé depuis la dernière sauvegarde (remis à faux). */
    boolean takeRecordsDirty() {
        boolean dirty = recordsDirty;
        recordsDirty = false;
        return dirty;
    }

    // ── Les textes et les couleurs ──

    static String label(int kind, int value) {
        return switch (kind) {
            case COMBO -> Lang.tr("COMBO") + " x" + value;
            case WTAP -> Lang.tr("W-TAP");
            case CRIT -> Lang.tr("CRITIQUE");
            case SMASH -> Lang.tr("SMASH");
            case BIG_SMASH -> Lang.tr("GROS SMASH");
            case PERFECT_SMASH -> Lang.tr("SMASH PARFAIT");
            case SHIELD_BROKEN -> Lang.tr("BOUCLIER BRISÉ");
            case BLOCKED -> Lang.tr("BLOQUÉ");
            case TOTEM -> Lang.tr("TOTEM ARRACHÉ");
            case CLUTCH -> Lang.tr("CLUTCH");
            case GIANT -> Lang.tr("TUEUR DE GÉANT");
            default -> "";
        };
    }

    /** Blanc, vert, or ; l'arc-en-ciel tourne avec le temps et d'une lettre à l'autre. */
    private static UiColor color(int tier, int letter, float time) {
        return switch (tier) {
            case 1 -> WHITE;
            case 2 -> PvpScoreUi.GOOD;
            case 3 -> GOLD;
            default -> {
                int rgb = java.awt.Color.HSBtoRGB((time * 0.6f + letter * 0.07f) % 1f, 0.55f, 1f);
                yield new UiColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255);
            }
        };
    }

    // ── Le dessin ──

    private static boolean alive(Word w, long now) {
        return (now - w.bornNanos) / 1e9f < LIFE[w.tier];
    }

    @Override
    public float[] naturalSize() {
        return new float[]{ WIDTH, HEIGHT };
    }

    /** En jeu, seulement quand un mot vit. L'éditeur de HUD l'affiche toujours. */
    @Override
    public boolean hasContent() {
        long now = System.nanoTime();
        for (Word w : words) if (alive(w, now)) return true;
        return false;
    }

    @Override
    public boolean skipBackground() {
        return true;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOutBack(float u) {
        float v = clamp01(u) - 1f;
        float c = 2.4f;
        return 1f + (c + 1f) * v * v * v + c * v * v;
    }

    @Override
    public void draw(UiRenderer r, float x, float y, float w, float h, float s, int vw, int vh) {
        long now = System.nanoTime();
        words.removeIf(word -> !alive(word, now));
        List<Word> shown = words;
        if (shown.isEmpty()) {
            // Hors duel, on n'est ici que par l'éditeur de HUD : un exemple.
            shown = sample(now);
        }
        float time = (now % 100_000_000_000L) / 1e9f;
        float centerX = x + w / 2f;
        float top = y + h;
        for (int i = 0; i < shown.size(); i++) {
            Word word = shown.get(i);
            // La place dans la pile glisse vers sa cible.
            word.slot = word.slot < 0f ? i : word.slot + (i - word.slot) * 0.25f;
            float age = (now - word.bornNanos) / 1e9f;
            float popAge = (now - word.popNanos) / 1e9f;
            float life = LIFE[word.tier];
            float alpha = clamp01(age / 0.08f) * clamp01((life - age) / 0.35f);
            if (alpha <= 0.01f) continue;
            float scale = SIZE[word.tier] * s * (0.4f + 0.6f * easeOutBack(popAge / 0.22f)) * (i == 0 ? 1f : OLDER);
            String text = label(word.kind, word.value);
            float width = r.textWidth(UiFont.BOLD, text, scale);
            // Plus gros que la boîte de l'élément ? Rétréci pour y tenir : la
            // taille du texte grandit, pas celle de l'élément.
            if (width > w && width > 0f) {
                scale *= w / width;
                width = w;
            }
            // Monte un peu en vieillissant ; les plus rares tremblent à leur arrivée.
            float rise = 4f * s * clamp01(age / life);
            float shake = word.tier >= 3 ? 3f * s * clamp01(1f - popAge / 0.3f) * (float) Math.sin(popAge * 80.0) : 0f;
            // Le nouveau en haut, en grand ; les anciens dessous, plus petits et plus serrés.
            // Le grand mot occupe ~30 px de haut, les petits ~15 : 34 px pour
            // le premier, puis 23 par mot, sans se chevaucher.
            float baseline = top - 34f * s - word.slot * 23f * s + rise;
            float left = centerX - width / 2f + shake;
            UiColor shadow = new UiColor(0, 0, 0, 255).withAlpha(0.55f * alpha);
            if (word.tier < 4) {
                r.drawText(UiFont.BOLD, text, left + 1.5f * s, baseline - 1.5f * s, shadow, scale, vw, vh);
                r.drawText(UiFont.BOLD, text, left, baseline, color(word.tier, 0, time).multiplyAlpha(alpha), scale, vw, vh);
            } else {
                // L'arc-en-ciel, lettre par lettre.
                float cursor = left;
                for (int c = 0; c < text.length(); c++) {
                    String letter = text.substring(c, c + 1);
                    r.drawText(UiFont.BOLD, letter, cursor + 1.5f * s, baseline - 1.5f * s, shadow, scale, vw, vh);
                    r.drawText(UiFont.BOLD, letter, cursor, baseline, color(4, c, time).multiplyAlpha(alpha), scale, vw, vh);
                    cursor += r.textWidth(UiFont.BOLD, letter, scale);
                }
            }
        }
    }

    /** L'exemple de l'éditeur de HUD : trois mots figés. */
    private static List<Word> sample(long now) {
        List<Word> list = new ArrayList<>();
        int[][] demo = { { PERFECT_SMASH, 3, 120 }, { COMBO, 2, 4 }, { WTAP, 1, 1 } };
        for (int i = 0; i < demo.length; i++) {
            Word word = new Word();
            word.kind = demo[i][0];
            word.tier = demo[i][1];
            word.value = demo[i][2];
            word.bornNanos = now - 500_000_000L;
            word.popNanos = now - 1_000_000_000L;
            word.slot = i;
            list.add(word);
        }
        return list;
    }
}
