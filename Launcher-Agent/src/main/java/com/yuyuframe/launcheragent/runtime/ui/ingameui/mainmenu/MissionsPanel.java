package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.friends.GameMissions;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIcon;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * L'onglet Missions : les missions du jour et de la semaine, sur le modèle de
 * l'écran Missions du launcher ({@code components/competitive/Challenges.tsx})
 * — titre en capitales et compte à rebours, paliers du jour en losanges, puis
 * une ligne par mission (anneau ou coche, phrase, avancement et mode, XP).
 *
 * <p>Jour et semaine côte à côte quand la place le permet, l'un sous l'autre
 * sinon. Données : {@link GameMissions}, par le canal du jeu.
 */
public final class MissionsPanel extends UiWidget {

    private static final String ICON_CLOCK = UiIcon.named("clock");
    private static final String ICON_CHECK = UiIcon.named("checkmark");

    public MissionsPanel() {
        super(0f, 0f, 0f, 0f);
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (w <= 0f || h <= 0f) return;
        GameMissions.keepFresh();

        String message = null;
        if (!GameMissions.available()) {
            message = Lang.tr("Lance le jeu depuis le launcher YuyuFrame pour voir tes missions");
        } else if (!GameMissions.loaded()) {
            message = GameMissions.error() != null ? GameMissions.error() : Lang.tr("Chargement...");
        } else if (GameMissions.missions().isEmpty()) {
            message = Lang.tr("Les missions arrivent bientôt.");
        }
        if (message != null) {
            float cw = Math.min(w, px(560f));
            float ch = px(120f);
            float cx = x, cy = y + h - ch;
            card(renderer, cx, cy, cw, ch, vpWidth, vpHeight);
            float scale = px(UiTheme.Text.LABEL);
            float mw = renderer.textWidth(message, scale);
            renderer.drawText(message, cx + Math.max(px(22f), (cw - mw) / 2f), cy + ch / 2f - px(UiTheme.Space.S5),
                UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            return;
        }

        List<GameMissions.Mission> daily = new ArrayList<>();
        List<GameMissions.Mission> weekly = new ArrayList<>();
        for (GameMissions.Mission m : GameMissions.missions()) {
            if ("daily".equals(m.period)) daily.add(m); else weekly.add(m);
        }
        float gap = px(UiTheme.Space.S20);
        float top = y + h;
        // Deux cartes de 620 au plus ; sous 360 chacune, elles s'empilent.
        boolean sideBySide = (w - gap) / 2f >= px(360f);
        if (sideBySide) {
            float cardW = Math.min(px(620f), (w - gap) / 2f);
            drawSection(renderer, Lang.tr("Missions du jour"), daily, true, x, top, cardW, vpWidth, vpHeight);
            drawSection(renderer, Lang.tr("Missions de la semaine"), weekly, false, x + cardW + gap, top, cardW, vpWidth, vpHeight);
        } else {
            float cardW = Math.min(px(620f), w);
            float used = drawSection(renderer, Lang.tr("Missions du jour"), daily, true, x, top, cardW, vpWidth, vpHeight);
            drawSection(renderer, Lang.tr("Missions de la semaine"), weekly, false, x, top - used - gap, cardW, vpWidth, vpHeight);
        }
    }

    /**
     * Fond d'une carte : la même surface de verre que le reste de l'écran
     * (elle était un aplat opaque, d'une autre matière que les cartes de
     * modules posées juste à côté).
     */
    private static void card(UiRenderer renderer, float cx, float cy, float cw, float ch, int vpWidth, int vpHeight) {
        UiSurface.panel().radius(UiTheme.RADIUS_LG)
            .shadow(UiTheme.SHADOW_PANEL, px(UiTheme.Space.S20), 0f, 0f)
            .draw(renderer, cx, cy, cw, ch, vpWidth, vpHeight);
    }

    /** Dessine une carte de missions sous {@code top} ; rend sa hauteur. */
    private float drawSection(UiRenderer renderer, String title, List<GameMissions.Mission> list, boolean daily,
                              float cx, float top, float cw, int vpWidth, int vpHeight) {
        float pad = px(UiTheme.Space.S24);
        float headerH = px(UiTheme.Space.S36);
        float checkpointsH = daily && !list.isEmpty() ? px(62f) : 0f;
        float rowH = px(68f);
        boolean allDone = !list.isEmpty();
        for (GameMissions.Mission m : list) if (!m.done()) allDone = false;
        boolean showRows = !list.isEmpty() && !(allDone && !daily);
        float bodyH = showRows ? list.size() * rowH : px(70f);
        float ch = pad + headerH + checkpointsH + bodyH + pad * 0.6f;
        float cy = top - ch;
        card(renderer, cx, cy, cw, ch, vpWidth, vpHeight);

        // ── Titre en capitales et compte à rebours ──────────────────────
        float headMid = top - pad - headerH / 2f + px(UiTheme.Space.S4);
        renderer.drawText(UiFont.BOLD, title.toUpperCase(Locale.ROOT), cx + pad, headMid - px(UiTheme.Space.S5),
            UiTheme.TEXT_PRIMARY, px(UiTheme.Text.CARD_TITLE), vpWidth, vpHeight);
        if (!list.isEmpty()) {
            String left = countdown(list.get(0).endsAt - System.currentTimeMillis());
            float scale = px(UiTheme.Text.BUTTON);
            float tw = renderer.textWidth(left, scale);
            float clock = px(15f);
            float rx = cx + cw - pad - tw;
            renderer.drawText(left, rx, headMid - px(4.5f), UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            UiIcon.crisp(renderer, ICON_CLOCK, rx - clock - px(7f), headMid - clock / 2f, clock, 0.6f, vpWidth, vpHeight);
        }

        float cursor = top - pad - headerH;

        // ── Paliers du jour : un losange par mission faite ─────────────
        if (checkpointsH > 0f) {
            int total = list.size(), done = 0;
            for (GameMissions.Mission m : list) if (m.done()) done++;
            float d = px(38f);
            float mid = cursor - checkpointsH / 2f;
            float left = cx + pad, right = cx + cw - pad - d;
            float step = total > 1 ? (right - left) / (total - 1) : 0f;
            for (int i = 0; i < total; i++) {
                float dx = left + i * step;
                if (i < total - 1) {
                    boolean lit = i < done - 1;
                    renderer.drawRoundedRect(dx + d + px(UiTheme.Space.S4), mid - px(1.5f), dx + step - px(UiTheme.Space.S4), mid + px(1.5f),
                        px(UiTheme.Radius.XS), lit ? UiTheme.ACCENT.multiplyAlpha(0.75f) : UiTheme.DIVIDER_STRONG, vpWidth, vpHeight);
                }
                boolean filled = i < done;
                if (filled) {
                    float s6 = px(UiTheme.Space.S6);
                    renderer.drawShadow(dx + s6, mid - d / 2f + s6, dx + d - s6, mid + d / 2f - s6, s6, px(UiTheme.Space.S14), 0f,
                        UiTheme.ACCENT.multiplyAlpha(0.45f), vpWidth, vpHeight);
                }
                int n = Math.max(1, Math.round(d));
                renderer.drawIcon("yf-diamond-" + filled + "-" + n + "-" + UiTheme.ACCENT.hashCode(), MissionShapes.diamond(n, filled),
                    Math.round(dx), Math.round(mid - d / 2f), n, n, 1f, vpWidth, vpHeight);
                String num = String.valueOf(i + 1);
                float scale = px(UiTheme.Text.VALUE);
                float nw = renderer.textWidth(UiFont.BOLD, num, scale);
                renderer.drawText(UiFont.BOLD, num, dx + (d - nw) / 2f, mid - px(UiTheme.Space.S5),
                    filled ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            }
            cursor -= checkpointsH;
        }

        if (!showRows) {
            String text = list.isEmpty() ? Lang.tr("Les missions arrivent bientôt.")
                : Lang.tr("Toutes les missions de la semaine sont faites.");
            float scale = px(UiTheme.Text.BODY_STRONG);
            UiText.drawCentered(renderer, UiFont.BOLD, text, cx, cw, cursor - bodyH / 2f - px(UiTheme.Space.S4),
                UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            return ch;
        }

        // ── Une ligne par mission ───────────────────────────────────────
        for (int i = 0; i < list.size(); i++) {
            GameMissions.Mission m = list.get(i);
            float rowTop = cursor - i * rowH, rowBottom = rowTop - rowH, mid = rowBottom + rowH / 2f;
            if (i > 0) {
                renderer.drawRoundedRect(cx + pad, rowTop - px(0.5f), cx + cw - pad, rowTop + px(0.5f), 0f,
                    UiTheme.DIVIDER, vpWidth, vpHeight);
            }
            float ring = px(UiTheme.Space.S28);
            float rx = cx + pad, ry = mid - ring / 2f;
            if (m.done()) {
                renderer.drawRoundedRect(rx, ry, rx + ring, ry + ring, ring / 2f, UiTheme.SUCCESS.multiplyAlpha(0.2f), vpWidth, vpHeight);
                UiIcon.crisp(renderer, ICON_CHECK, rx + ring * 0.22f, ry + ring * 0.22f, ring * 0.56f, 1f, vpWidth, vpHeight);
            } else {
                int n = Math.max(1, Math.round(ring));
                int pct = Math.round(Math.min(1f, m.progress / (float) m.goal) * 50f) * 2;
                renderer.drawIcon("yf-ring-" + pct + "-" + n + "-" + UiTheme.ACCENT.hashCode(), MissionShapes.ring(n, pct / 100f),
                    Math.round(rx), Math.round(ry), n, n, 1f, vpWidth, vpHeight);
            }

            // L'XP à droite, la phrase prend le reste.
            String xp = "+" + String.format(Locale.ROOT, "%,d", m.xp).replace(',', ' ') + " XP";
            float valueScale = px(UiTheme.Text.VALUE);
            float xpW = renderer.textWidth(UiFont.BOLD, xp, valueScale);
            renderer.drawText(UiFont.BOLD, xp, cx + cw - pad - xpW, mid - px(UiTheme.Space.S5),
                m.done() ? UiTheme.SUCCESS : UiTheme.accentLight(), valueScale, vpWidth, vpHeight);

            float tx = rx + ring + px(UiTheme.Space.S16);
            float maxW = cx + cw - pad - xpW - px(UiTheme.Space.S16) - tx;
            float itemScale = px(UiTheme.Text.ITEM);
            String phrase = UiText.fit(UiFont.BOLD, m.text(), maxW, itemScale);
            float titleBase = mid + px(UiTheme.Space.S3);
            renderer.drawText(UiFont.BOLD, phrase, tx, titleBase, m.done() ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY,
                itemScale, vpWidth, vpHeight);
            if (m.done()) {
                // Barrée une fois faite, comme dans le launcher.
                float pw = renderer.textWidth(UiFont.BOLD, phrase, itemScale);
                renderer.drawRoundedRect(tx, titleBase + px(UiTheme.Space.S5), tx + pw, titleBase + px(6.5f), 0f,
                    UiTheme.TEXT_MUTED.multiplyAlpha(0.6f), vpWidth, vpHeight);
            }
            String sub = m.progress + " / " + m.goal;
            String mode = "mace".equals(m.mode) ? Lang.tr("Masse") : "sword".equals(m.mode) ? Lang.tr("Épée") : "";
            if (!mode.isEmpty()) sub += " · " + mode;
            renderer.drawText(sub, tx, mid - px(UiTheme.Space.S16), UiTheme.TEXT_MUTED, px(UiTheme.Text.BODY), vpWidth, vpHeight);
        }
        return ch;
    }

    /** « 08:24:55 » sous un jour, « 3:16:49:05 » (jours en tête) au-delà — comme le launcher. */
    private static String countdown(long ms) {
        long total = Math.max(0L, ms / 1000L);
        long d = total / 86400L;
        String hms = String.format(Locale.ROOT, "%02d:%02d:%02d", (total % 86400L) / 3600L, (total % 3600L) / 60L, total % 60L);
        return d > 0 ? d + ":" + hms : hms;
    }
}
