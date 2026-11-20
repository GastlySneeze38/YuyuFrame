package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Les amis en ligne sur l'accueil : tête, pseudo, ce qu'ils font. Ceux qui
 * sont en jeu d'abord. Un clic ouvre l'onglet Amis, où sont la recherche,
 * les hors ligne et les invitations.
 */
public final class FriendsCard extends UiInteractive {

    private static final int ROWS = 3;

    private final Runnable open;

    public FriendsCard(Runnable open) {
        this.open = open;
    }

    public static float preferredHeight() {
        return px(UiTheme.Space.S24) * 2f + px(UiTheme.Space.S36) + ROWS * px(52f);
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        GameFriends.keepFresh();
        UiSurface.card(hover).radius(px(22f)).draw(r, x, y, w, h, vw, vh);
        UiSurface.hoverWash(r, x, y, w, h, px(22f), hover * 0.7f, vw, vh);

        float pad = px(UiTheme.Space.S24);
        float left = x + pad, right = x + w - pad, top = y + h - pad;
        float titleScale = px(UiTheme.Text.LG);
        r.drawText(UiFont.BOLD, Lang.tr("Amis en ligne"), left, top - px(UiTheme.Space.S16), UiTheme.TEXT_PRIMARY, titleScale, vw, vh);

        List<GameFriends.Friend> online = new ArrayList<>();
        int total = 0;
        for (GameFriends.Friend f : GameFriends.friends()) {
            total++;
            if (f.online()) online.add(f);
        }
        online.sort((a, b) -> Boolean.compare(b.inGame, a.inGame));
        float small = px(UiTheme.Text.SM);
        if (total > 0) {
            String count = online.size() + " " + Lang.tr("sur") + " " + total;
            r.drawText(UiFont.REGULAR, count, right - UiText.width(UiFont.REGULAR, count, small), top - px(UiTheme.Space.S16),
                UiTheme.TEXT_MUTED, small, vw, vh);
        }

        if (online.isEmpty()) {
            String message = !GameFriends.available() ? Lang.tr("Lance le jeu depuis le launcher YuyuFrame pour voir tes amis")
                : !GameFriends.loaded() ? (GameFriends.error() != null ? GameFriends.error() : Lang.tr("Chargement..."))
                : total == 0 ? Lang.tr("Ajoute des amis depuis le launcher")
                : Lang.tr("Aucun ami en ligne");
            UiText.drawWrapped(r, UiFont.REGULAR, message, left, top - px(UiTheme.Space.S48), right - left, 3,
                UiText.lineHeight(UiFont.REGULAR, small), UiTheme.TEXT_MUTED, small, vw, vh);
            return;
        }

        float rowH = px(52f);
        float rowTop = top - px(UiTheme.Space.S36);
        float head = px(UiTheme.Space.S32);
        for (int i = 0; i < Math.min(ROWS, online.size()); i++) {
            GameFriends.Friend f = online.get(i);
            float rowY = rowTop - (i + 1) * rowH;
            float hy = rowY + (rowH - head) / 2f;
            drawHead(r, f, left, hy, head, vw, vh);

            float dot = px(UiTheme.Space.S8);
            r.drawRoundedRect(right - dot, rowY + (rowH - dot) / 2f, right, rowY + (rowH + dot) / 2f, dot / 2f,
                UiTheme.statusColor(f.state), vw, vh);

            float tx = left + head + px(UiTheme.Space.S12);
            float maxW = right - dot - px(UiTheme.Space.S12) - tx;
            float nameScale = px(UiTheme.Text.MD);
            float mid = rowY + rowH / 2f;
            r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, f.name, maxW, nameScale), tx, mid + px(UiTheme.Space.S2),
                UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, doing(f), maxW, px(UiTheme.Text.XS)), tx,
                mid - px(UiTheme.Space.S14), UiTheme.TEXT_MUTED, px(UiTheme.Text.XS), vw, vh);
        }
    }

    /** Une ligne : en duel, sur quel serveur, ou simplement en ligne. */
    private static String doing(GameFriends.Friend f) {
        if (f.duel != null && !f.duel.isEmpty()) return Lang.tr("En duel");
        if (f.server != null && !f.server.isEmpty()) return f.server;
        if (f.inGame) {
            return f.version == null || f.version.isEmpty() ? Lang.tr("En jeu") : Lang.tr("En jeu") + " - " + f.version;
        }
        if (f.custom != null && !f.custom.isEmpty()) return f.custom;
        return Lang.tr("En ligne");
    }

    private static void drawHead(UiRenderer r, GameFriends.Friend f, float x, float y, float size, int vw, int vh) {
        BufferedImage img = null;
        String key = null;
        if (f.minecraftUuid != null && !f.minecraftUuid.isEmpty()) {
            key = "https://mc-heads.net/avatar/" + f.minecraftUuid.replace("-", "") + "/64";
            img = UiRemoteImage.get(key);
        }
        if (img == null) {
            img = McTexture.defaultFace();
            key = "friend-face-default";
        }
        if (img != null) r.drawIcon(key, img, x, y, size, size, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (open != null) open.run();
    }
}
