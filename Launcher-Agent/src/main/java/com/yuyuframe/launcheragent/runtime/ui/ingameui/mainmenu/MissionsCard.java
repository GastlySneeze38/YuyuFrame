package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.friends.GameMissions;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.MinecraftFont;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Les missions du jour sur l'accueil : la barre d'XP du jeu pour l'XP du jour,
 * puis une ligne par mission (objet, phrase, progression, XP). Un clic ouvre
 * l'onglet Missions.
 */
public final class MissionsCard extends UiInteractive {

    /** Le vert du niveau d'XP du jeu. */
    static final UiColor XP_GREEN = new UiColor(128, 255, 32, 255);

    private final Runnable open;

    public MissionsCard(Runnable open) {
        this.open = open;
    }

    /** Hauteur voulue : en-tête, barre d'XP, trois lignes. */
    public static float preferredHeight() {
        return px(UiTheme.Space.S24) * 2f + px(UiTheme.Space.S24) + px(64f) + 3f * px(58f);
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        GameMissions.keepFresh();
        UiSurface.card(hover).radius(px(22f)).draw(r, x, y, w, h, vw, vh);
        UiSurface.hoverWash(r, x, y, w, h, px(22f), hover * 0.7f, vw, vh);

        float pad = px(UiTheme.Space.S24);
        float left = x + pad, right = x + w - pad;
        float top = y + h - pad;
        float titleScale = px(UiTheme.Text.LG);
        r.drawText(UiFont.BOLD, Lang.tr("Missions du jour"), left, top - px(UiTheme.Space.S16), UiTheme.TEXT_PRIMARY, titleScale, vw, vh);

        List<GameMissions.Mission> daily = new ArrayList<>();
        for (GameMissions.Mission m : GameMissions.missions()) if ("daily".equals(m.period)) daily.add(m);

        if (daily.isEmpty()) {
            String message = !GameMissions.available() ? Lang.tr("Lance le jeu depuis le launcher YuyuFrame pour voir tes missions")
                : !GameMissions.loaded() ? (GameMissions.error() != null ? GameMissions.error() : Lang.tr("Chargement..."))
                : Lang.tr("Aucune mission aujourd'hui");
            float s = px(UiTheme.Text.SM);
            UiText.drawWrapped(r, UiFont.REGULAR, message, left, top - px(56f), right - left, 3,
                UiText.lineHeight(UiFont.REGULAR, s), UiTheme.TEXT_MUTED, s, vw, vh);
            return;
        }

        float small = px(UiTheme.Text.SM);
        String left0 = MissionsPanel.countdown(daily.get(0).endsAt - System.currentTimeMillis());
        float leftW = UiText.width(UiFont.REGULAR, left0, small);
        r.drawText(UiFont.REGULAR, left0, right - leftW, top - px(UiTheme.Space.S16), UiTheme.TEXT_MUTED, small, vw, vh);

        // ── La barre d'XP : l'XP gagnée aujourd'hui sur celle du jour ─────────
        long gained = 0, total = 0;
        for (GameMissions.Mission m : daily) {
            total += m.xp;
            if (m.done()) gained += m.xp;
        }
        float barY = top - px(64f);
        float barH = px(UiTheme.Space.S12);
        drawXpBar(r, left, barY, right - left, barH, total == 0 ? 0f : (float) gained / total, vw, vh);
        UiFont pixel = MinecraftFont.accent(false);
        float levelScale = px(UiTheme.Text.LG);
        String level = String.valueOf(gained);
        float lw = UiText.width(pixel, level, levelScale);
        drawOutlined(r, pixel, level, left + (right - left - lw) / 2f, barY + barH + px(UiTheme.Space.S6), XP_GREEN, levelScale, vw, vh);
        float capS = px(UiTheme.Text.XS);
        r.drawText(UiFont.REGULAR, Lang.tr("XP du jour"), left, barY - px(UiTheme.Space.S18), UiTheme.TEXT_MUTED, capS, vw, vh);
        String totalText = gained + " / " + total + " XP";
        r.drawText(UiFont.REGULAR, totalText, right - UiText.width(UiFont.REGULAR, totalText, capS), barY - px(UiTheme.Space.S18),
            UiTheme.TEXT_MUTED, capS, vw, vh);

        // ── Les missions ──────────────────────────────────────────────────────
        float rowH = px(58f);
        float rowTop = barY - px(UiTheme.Space.S32);
        float iconS = px(UiTheme.Space.S28);
        for (int i = 0; i < Math.min(3, daily.size()); i++) {
            GameMissions.Mission m = daily.get(i);
            float rowY = rowTop - (i + 1) * rowH;
            r.drawRoundedRect(left, rowY + rowH - UiTheme.hairline(), right, rowY + rowH, 0f, UiTheme.DIVIDER, vw, vh);
            float alpha = m.done() ? 0.5f : 1f;
            BufferedImage icon = HomeIcons.mission(m.kind);
            McTexture.draw(r, icon, "mission:" + m.kind, left, rowY + (rowH - iconS) / 2f, iconS, iconS, alpha, vw, vh);

            String xp = "+" + m.xp;
            float xpScale = px(UiTheme.Text.MD);
            UiFont xpFont = MinecraftFont.accent(false);
            float xpW = UiText.width(xpFont, xp, xpScale);
            r.drawText(xpFont, xp, right - xpW, UiText.centeredBaseline(xpFont, rowY, rowH, xpScale),
                XP_GREEN.multiplyAlpha(alpha), xpScale, vw, vh);

            float tx = left + iconS + px(UiTheme.Space.S14);
            float maxW = right - xpW - px(UiTheme.Space.S16) - tx;
            float textScale = px(UiTheme.Text.MD);
            float mid = rowY + rowH / 2f;
            UiColor textColor = m.done() ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY;
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, m.text(), maxW, textScale), tx, mid + px(UiTheme.Space.S2),
                textColor, textScale, vw, vh);
            if (m.done()) {
                float tw = Math.min(maxW, UiText.width(UiFont.REGULAR, m.text(), textScale));
                r.drawRoundedRect(tx, mid + px(UiTheme.Space.S8), tx + tw, mid + px(UiTheme.Space.S8) + UiTheme.hairline(), 0f,
                    UiTheme.TEXT_MUTED, vw, vh);
                r.drawText(UiFont.REGULAR, m.progress + " / " + m.goal, tx, mid - px(UiTheme.Space.S16), UiTheme.TEXT_MUTED,
                    px(UiTheme.Text.XS), vw, vh);
            } else {
                float trackY = mid - px(UiTheme.Space.S12), trackH = px(UiTheme.Space.S4);
                r.drawRoundedRect(tx, trackY, tx + maxW, trackY + trackH, trackH / 2f, UiTheme.DIVIDER_STRONG, vw, vh);
                float t = Math.min(1f, (float) m.progress / m.goal);
                if (t > 0f) {
                    r.drawRoundedRect(tx, trackY, tx + maxW * t, trackY + trackH, trackH / 2f, UiTheme.accentLight(), vw, vh);
                }
            }
        }
    }

    /** La barre d'XP du jeu ; sans ses textures, un rail et un remplissage verts. */
    static void drawXpBar(UiRenderer r, float x, float y, float w, float h, float fill, int vw, int vh) {
        BufferedImage bg = McTexture.xpBackground(), fg = McTexture.xpProgress();
        fill = Math.max(0f, Math.min(1f, fill));
        if (bg == null || fg == null) {
            r.drawRoundedRect(x, y, x + w, y + h, h / 2f, UiTheme.DIVIDER_STRONG, vw, vh);
            if (fill > 0f) r.drawRoundedRect(x, y, x + w * fill, y + h, h / 2f, XP_GREEN, vw, vh);
            return;
        }
        McTexture.draw(r, bg, "xp-bg", x, y, w, h, 1f, vw, vh);
        if (fill <= 0f) return;
        // Le remplissage se coupe à sa part : la texture entière, découpée par le clip.
        r.beginScissor(Math.round(x), Math.round(y), Math.max(1, Math.round(w * fill)), Math.round(h) + 1);
        try {
            McTexture.draw(r, fg, "xp-fill", x, y, w, h, 1f, vw, vh);
        } finally {
            r.endScissor();
        }
    }

    /** Texte cerné de noir sur ses quatre côtés, comme le niveau d'XP du jeu. */
    static void drawOutlined(UiRenderer r, UiFont font, String text, float x, float y, UiColor color, float scale, int vw, int vh) {
        float o = Math.max(1f, px(UiTheme.Space.S2));
        UiColor black = new UiColor(0, 0, 0, 255);
        r.drawText(font, text, x - o, y, black, scale, vw, vh);
        r.drawText(font, text, x + o, y, black, scale, vw, vh);
        r.drawText(font, text, x, y - o, black, scale, vw, vh);
        r.drawText(font, text, x, y + o, black, scale, vw, vh);
        r.drawText(font, text, x, y, color, scale, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (open != null) open.run();
    }
}
