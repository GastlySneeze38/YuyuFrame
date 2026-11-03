package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshBatch;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.friends.GameMissions;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * « Mission accomplie » : une bannière qui se déroule de haut en bas à
 * droite du panneau de fin de duel, quand le serveur vient de compter une
 * mission comme faite ({@link GameMissions#takeCompleted}).
 *
 * <p>Suspendue à une tringle, elle se termine en pointe ; la pointe porte
 * une bordure ouvragée — double chevron, tirets, ailettes aux coins et un
 * losange qui pulse. Or pour une mission du jour, violet (la couleur du
 * passe) pour une mission de la semaine. Le son est celui d'un défi
 * accompli du jeu.
 *
 * <p>Tout est construit à chaque image en quadrilatères libres (la brique
 * 3D, comme les rayons de la cérémonie de rang) : le déroulé n'est qu'une
 * ligne de coupe qui descend, et chaque élément n'apparaît qu'une fois
 * dépassé par elle. Repère du moteur : y monte.
 */
final class PvpMissionBanner implements com.yuyuframe.launcheragent.apigraphic.hud.HudElement.CustomRenderer {

    // ── Mesures, à l'échelle 1 ──
    private static final float W = 112f;
    private static final float ROD_H = 7f;
    private static final float ROD_OVER = 7f;
    private static final float BODY_H = 140f;
    private static final float TIP_H = 30f;
    private static final float INSET = 5f;
    /** Largeur et hauteur de l'élément du HUD : la tringle déborde de la toile, la pointe compte. */
    static final float WIDTH = W + 2f * ROD_OVER;
    static final float HEIGHT = ROD_H + BODY_H + TIP_H;

    // ── Le temps ──
    private static final float UNROLL = 0.65f;
    private static final float HOLD_END = 5.4f;
    private static final float ROLL_UP = 0.45f;
    private static final float LENGTH = HOLD_END + ROLL_UP;

    private static final UiColor DAILY = new UiColor(255, 206, 72, 255);
    private static final UiColor WEEKLY = new UiColor(129, 120, 255, 255);
    private static final UiColor SILVER = new UiColor(212, 218, 232, 255);
    private static final UiColor SILVER_DIM = new UiColor(150, 158, 180, 255);
    private static final UiColor BODY_TOP = new UiColor(34, 38, 56, 245);
    private static final UiColor BODY_BOTTOM = new UiColor(15, 17, 27, 245);
    /** Le cœur du losange de la pointe. */
    private static final UiColor SPARK = new UiColor(255, 255, 255, 255);

    /** Texture des quads : du blanc, teinté par sommet. */
    private static final java.awt.image.BufferedImage WHITE = white();

    private final ArrayDeque<GameMissions.Mission> queue = new ArrayDeque<>();
    private GameMissions.Mission current;
    private long startNanos;
    private boolean twinkled;
    /** Le lot en cours : un neuf à chaque dessin, le moteur peut garder le précédent jusqu'à la fin de l'image. */
    private UiMeshBatch batch = new UiMeshBatch();

    private void newBatch() {
        batch = new UiMeshBatch();
        batch.reset();
    }

    /** Une mission tout juste finie : annoncée après celles déjà en file. */
    void offer(GameMissions.Mission mission) {
        if (mission != null) queue.add(mission);
    }

    /** Oublie tout : un nouveau duel commence. */
    void clear() {
        queue.clear();
        current = null;
    }

    /** Une bannière est à l'écran (ou en file) : le panneau de fin de duel reste. */
    boolean active() {
        return current != null || !queue.isEmpty();
    }

    /**
     * À chaque image : la suivante démarre quand la précédente est repliée,
     * et seulement si {@code canStart} (pas pendant la cérémonie d'un rang).
     */
    void tick(boolean canStart) {
        long now = System.nanoTime();
        if (current != null && seconds(now) >= LENGTH) current = null;
        if (current == null && canStart && !queue.isEmpty()) {
            current = queue.poll();
            startNanos = now;
            twinkled = false;
            PvpSounds.play(PvpSounds.CHALLENGE, 1.0f, 0.8f);
        }
        if (current != null && !twinkled && seconds(now) >= UNROLL) {
            twinkled = true;
            PvpSounds.play(PvpSounds.TWINKLE, 1.2f, 0.45f);
        }
    }

    private float seconds(long now) {
        return (now - startNanos) / 1e9f;
    }

    // ── L'élément du HUD : déplaçable et redimensionnable dans l'éditeur ──

    @Override
    public float[] naturalSize() {
        return new float[]{ WIDTH, HEIGHT };
    }

    /** En jeu, seulement pendant une annonce. L'éditeur de HUD l'affiche toujours (l'exemple en boucle). */
    @Override
    public boolean hasContent() {
        return current != null;
    }

    /** Pas de carte : la bannière est sa propre forme. */
    @Override
    public boolean skipBackground() {
        return true;
    }

    @Override
    public void draw(UiRenderer r, float x, float y, float w, float h, float s, int vw, int vh) {
        float left = x + ROD_OVER * s, top = y + h;
        GameMissions.Mission m = current;
        if (m != null) {
            render(r, m, seconds(System.nanoTime()), left, top, s, vw, vh);
            return;
        }
        // Rien à annoncer : on n'est ici que dans l'éditeur de HUD, qui a
        // besoin de la voir pour la placer — une mission du jour, en boucle.
        float loop = LENGTH + 1.2f;
        float t = (System.nanoTime() % (long) (loop * 1e9)) / 1e9f;
        render(r, SAMPLE, Math.min(t, HOLD_END), left, top, s, vw, vh);
    }

    private static final GameMissions.Mission SAMPLE = GameMissions.sample();

    private void render(UiRenderer r, GameMissions.Mission m, float t, float left, float top, float s, int vw, int vh) {
        UiColor accent = "weekly".equals(m.period) ? WEEKLY : DAILY;

        float w = W * s, rodH = ROD_H * s, bodyH = BODY_H * s, tipH = TIP_H * s, inset = INSET * s;
        float x0 = left, x1 = left + w, cx = left + w / 2f;
        float bodyTop = top - rodH;
        float bodyBottom = bodyTop - bodyH;
        float apex = bodyBottom - tipH;

        // Ce qui est déroulé : la ligne de coupe descend, dépasse un peu et
        // revient (la toile qui retombe), puis remonte au repli.
        float total = bodyH + tipH;
        float shown;
        if (t < UNROLL) shown = total * easeOutBack(t / UNROLL);
        else if (t < HOLD_END) shown = total;
        else shown = total * (1f - easeIn((t - HOLD_END) / ROLL_UP));
        float clip = bodyTop - Math.max(0f, shown);
        float fade = t > LENGTH - 0.15f ? Math.max(0f, (LENGTH - t) / 0.15f) : 1f;
        float settled = clamp01((t - UNROLL) / 0.3f);

        // Le halo, une fois déroulée.
        if (settled > 0f) {
            r.drawShadow(x0, bodyBottom, x1, bodyTop, 6f * s, 26f * s, 0f, accent.multiplyAlpha(0.35f * settled * fade), vw, vh);
        }

        newBatch();
        // La toile : le corps jusqu'à la coupe, puis la pointe en trapèze.
        float bodyLow = Math.max(bodyBottom, clip);
        if (bodyLow < bodyTop) {
            int topColor = argb(BODY_TOP, fade);
            int lowColor = argb(lerp(BODY_TOP, BODY_BOTTOM, (bodyTop - bodyLow) / bodyH), fade);
            quad(x0, bodyLow, x1, bodyLow, x1, bodyTop, x0, bodyTop, lowColor, lowColor, topColor, topColor);
            // La bande de la couleur de la mission sous la tringle.
            float band = 3f * s;
            if (bodyTop - band >= clip) {
                int a = argb(accent, fade);
                quad(x0, bodyTop - band, x1, bodyTop - band, x1, bodyTop, x0, bodyTop, a, a, a, a);
            }
        }
        if (clip < bodyBottom) {
            float low = Math.max(apex, clip);
            float half = (w / 2f) * (low - apex) / tipH;
            int top2 = argb(BODY_BOTTOM, fade);
            quad(cx - half, low, cx + half, low, x1, bodyBottom, x0, bodyBottom, top2, top2, top2, top2);
        }

        // La bordure intérieure : deux montants puis le V de la pointe.
        int silver = argb(SILVER, 0.85f * fade);
        int dim = argb(SILVER_DIM, 0.75f * fade);
        float line = 1.4f * s;
        float innerApex = apex + 2.4f * inset;
        segment(x0 + inset, bodyTop - 4f * s, x0 + inset, bodyBottom, line, silver, clip);
        segment(x1 - inset, bodyTop - 4f * s, x1 - inset, bodyBottom, line, silver, clip);
        segment(x0 + inset, bodyBottom, cx, innerApex, line, silver, clip);
        segment(x1 - inset, bodyBottom, cx, innerApex, line, silver, clip);
        // Le second chevron, plus court, dans la pointe.
        float chevronTop = bodyBottom + 6f * s;
        segment(x0 + 3.4f * inset, chevronTop, cx, innerApex + 9f * s, line, dim, clip);
        segment(x1 - 3.4f * inset, chevronTop, cx, innerApex + 9f * s, line, dim, clip);
        // Les tirets, de part et d'autre, au-dessus de la pointe.
        for (int i = 0; i < 2; i++) {
            float y = bodyBottom + (12f + 8f * i) * s;
            float len = (i == 0 ? 9f : 5f) * s;
            segment(x0 + inset + 3f * s, y, x0 + inset + 3f * s + len, y, line, dim, clip);
            segment(x1 - inset - 3f * s - len, y, x1 - inset - 3f * s, y, line, dim, clip);
        }
        // Les ailettes, là où les bords tournent vers la pointe.
        float wing = 5f * s;
        if (bodyBottom - 2f * s >= clip) {
            tri(x0, bodyBottom + 9f * s, x0 - wing, bodyBottom + 2f * s, x0, bodyBottom - 2f * s, silver);
            tri(x1, bodyBottom + 9f * s, x1, bodyBottom - 2f * s, x1 + wing, bodyBottom + 2f * s, silver);
        }
        // Le losange de la pointe, qui pulse une fois déroulée.
        float dy = apex + 9f * s;
        if (dy - 5f * s >= clip) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 4.0);
            float glow = (5f + 3f * pulse * settled) * s;
            diamond(cx, dy, glow * 1.8f, argb(accent, 0.22f * settled * fade));
            diamond(cx, dy, 4.5f * s, argb(accent, fade));
            diamond(cx, dy, 1.8f * s, argb(SPARK, 0.9f * fade));
        }

        // La tringle, toujours là, et ses deux embouts.
        float rx0 = x0 - ROD_OVER * s, rx1 = x1 + ROD_OVER * s;
        int rodTop = argb(SILVER, fade), rodLow = argb(SILVER_DIM, fade);
        quad(rx0, bodyTop, rx1, bodyTop, rx1, top, rx0, top, rodLow, rodLow, rodTop, rodTop);
        diamond(rx0, top - rodH / 2f, 4.5f * s, argb(accent, fade));
        diamond(rx1, top - rodH / 2f, 4.5f * s, argb(accent, fade));
        // Le rouleau qui descend pendant le déroulé.
        if (shown > 0f && shown < total) {
            float half = clip >= bodyBottom ? w / 2f : (w / 2f) * (clip - apex) / tipH;
            int roll = argb(SILVER_DIM, fade);
            quad(cx - half - 1f * s, clip, cx + half + 1f * s, clip, cx + half + 1f * s, clip + 4f * s, cx - half - 1f * s, clip + 4f * s,
                roll, roll, rodTop, rodTop);
        }
        r.drawTexturedQuads("mission-banner", WHITE, batch, vw, vh);

        // Le contenu, ligne par ligne quand la coupe l'a dépassé.
        drawContent(r, m, accent, cx, bodyTop, w, clip, fade, s, vw, vh);
    }

    private void drawContent(UiRenderer r, GameMissions.Mission m, UiColor accent, float cx, float bodyTop, float w, float clip,
                             float fade, float s, int vw, int vh) {
        float room = w - 2f * (INSET + 6f) * s;

        // Le badge : un rond de la couleur de la mission et sa coche.
        float badgeY = bodyTop - 22f * s, radius = 10.5f * s;
        if (badgeY - radius >= clip) {
            r.drawRoundedRect(cx - radius, badgeY - radius, cx + radius, badgeY + radius, radius, accent.multiplyAlpha(fade), vw, vh);
            newBatch();
            int dark = argb(BODY_BOTTOM, fade);
            float th = 2.2f * s;
            segment(cx - 5f * s, badgeY + 0.5f * s, cx - 1.5f * s, badgeY - 3.5f * s, th, dark, -Float.MAX_VALUE);
            segment(cx - 1.5f * s, badgeY - 3.5f * s, cx + 5.5f * s, badgeY + 4f * s, th, dark, -Float.MAX_VALUE);
            r.drawTexturedQuads("mission-banner", WHITE, batch, vw, vh);
        }

        // « MISSION / ACCOMPLIE » sur deux lignes, la seconde en couleur.
        String title = Lang.tr("Mission accomplie").toUpperCase(Locale.ROOT);
        int space = title.indexOf(' ');
        String first = space > 0 ? title.substring(0, space) : title;
        String second = space > 0 ? title.substring(space + 1) : "";
        float y = bodyTop - 48f * s;
        text(r, UiFont.BOLD, first, cx, y, room, UiTheme.TEXT_PRIMARY, 0.32f * s, clip, fade, vw, vh);
        if (!second.isEmpty()) {
            y -= 15f * s;
            text(r, UiFont.BOLD, second, cx, y, room, accent, 0.34f * s, clip, fade, vw, vh);
        }
        y -= 13f * s;
        String period = Lang.tr("weekly".equals(m.period) ? "Missions de la semaine" : "Missions du jour");
        text(r, UiFont.REGULAR, period, cx, y, room, UiTheme.TEXT_MUTED, 0.22f * s, clip, fade, vw, vh);

        // Le filet et son losange.
        y -= 9f * s;
        if (y - 2f * s >= clip) {
            newBatch();
            int dim = argb(SILVER_DIM, 0.6f * fade);
            segment(cx - room / 2f + 6f * s, y, cx - 5f * s, y, 1f * s, dim, -Float.MAX_VALUE);
            segment(cx + 5f * s, y, cx + room / 2f - 6f * s, y, 1f * s, dim, -Float.MAX_VALUE);
            diamond(cx, y, 2.6f * s, argb(accent, fade));
            r.drawTexturedQuads("mission-banner", WHITE, batch, vw, vh);
        }

        // La phrase de la mission, sur trois lignes au plus.
        y -= 14f * s;
        float scale = 0.25f * s;
        for (String line : wrap(r, m.text(), room, scale, 3)) {
            text(r, UiFont.REGULAR, line, cx, y, room, UiTheme.TEXT_SECONDARY, scale, clip, fade, vw, vh);
            y -= 11f * s;
        }

        // L'XP gagnée, juste au-dessus de la pointe.
        if (m.xp > 0) {
            float xpY = bodyTop - BODY_H * s + 22f * s;
            text(r, UiFont.BOLD, "+" + m.xp + " XP", cx, xpY, room, accent, 0.36f * s, clip, fade, vw, vh);
        }
    }

    // ── Petits outils de dessin ──

    /** Un texte centré, rétréci s'il dépasse, montré quand la coupe est passée sous lui. */
    private static void text(UiRenderer r, UiFont font, String text, float cx, float baseline, float maxWidth, UiColor color,
                             float scale, float clip, float fade, int vw, int vh) {
        if (baseline - 3f < clip || text.isEmpty()) return;
        float width = r.textWidth(font, text, scale);
        if (width > maxWidth && width > 0f) {
            scale *= maxWidth / width;
            width = maxWidth;
        }
        r.drawText(font, text, cx - width / 2f, baseline, color.multiplyAlpha(fade), scale, vw, vh);
    }

    /** Les mots de {@code text} rangés en lignes de {@code maxWidth}, {@code maxLines} au plus. */
    private static List<String> wrap(UiRenderer r, String text, float maxWidth, float scale, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && r.textWidth(UiFont.REGULAR, tried, scale) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.size() > maxLines ? lines.subList(0, maxLines) : lines;
    }

    /** Un quad dans n'importe quel sens : retourné s'il tourne à l'envers. */
    private void quad(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int ca, int cb, int cc, int cd) {
        float area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (area >= 0f) {
            batch.add(ax, ay, bx, by, cx, cy, dx, dy, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, ca, cb, cc, cd);
        } else {
            batch.add(ax, ay, dx, dy, cx, cy, bx, by, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, ca, cd, cc, cb);
        }
    }

    private void tri(float ax, float ay, float bx, float by, float cx, float cy, int color) {
        quad(ax, ay, bx, by, cx, cy, cx, cy, color, color, color, color);
    }

    private void diamond(float cx, float cy, float half, int color) {
        quad(cx, cy - half, cx + half, cy, cx, cy + half, cx - half, cy, color, color, color, color);
    }

    /** Un trait d'épaisseur {@code thickness}, montré seulement quand la coupe est passée sous lui. */
    private void segment(float x1, float y1, float x2, float y2, float thickness, int color, float clip) {
        if (Math.min(y1, y2) < clip) return;
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0f) return;
        float nx = -dy / len * thickness / 2f, ny = dx / len * thickness / 2f;
        quad(x1 - nx, y1 - ny, x2 - nx, y2 - ny, x2 + nx, y2 + ny, x1 + nx, y1 + ny, color, color, color, color);
    }

    private static int argb(UiColor c, float alpha) {
        int a = Math.round(clamp01(c.a * alpha) * 255f);
        return (a << 24) | (Math.round(c.r * 255f) << 16) | (Math.round(c.g * 255f) << 8) | Math.round(c.b * 255f);
    }

    private static UiColor lerp(UiColor a, UiColor b, float t) {
        t = clamp01(t);
        return new UiColor(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, a.a + (b.a - a.a) * t);
    }

    /** Arrive un peu trop loin puis revient : la toile qui retombe. */
    private static float easeOutBack(float x) {
        x = clamp01(x);
        float c1 = 1.4f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1f, 3) + c1 * (float) Math.pow(x - 1f, 2);
    }

    private static float easeIn(float x) {
        x = clamp01(x);
        return x * x * x;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static java.awt.image.BufferedImage white() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(x, y, 0xFFFFFFFF);
        return img;
    }
}
