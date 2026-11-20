package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.presence.PresenceContext;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Un ami dans l'onglet Amis. Plus il est disponible, plus sa carte est grande :
 *
 * <ul>
 *   <li>{@link Size#IN_GAME} : grande tête, pseudo, ce qu'il fait avec l'objet
 *       du jeu qui va avec, sa version, et « Inviter » à droite quand on est
 *       sur un serveur ;</li>
 *   <li>{@link Size#ONLINE} : tête, pseudo, statut perso ;</li>
 *   <li>{@link Size#OFFLINE} : petite, grisée, « Vu il y a 2 h ».</li>
 * </ul>
 *
 * Un clic l'ouvre dans la fiche de droite. L'ami est relu par son identifiant
 * à chaque image : son état change sans refaire la liste.
 */
public final class FriendTile extends UiInteractive {

    public enum Size { IN_GAME, ONLINE, OFFLINE }

    private final String id;
    private final Size size;
    private final Supplier<String> selected;
    private final Consumer<String> onSelect;
    private double lastMouseX, lastMouseY;
    private float[] inviteRect;

    public FriendTile(String id, Size size, Supplier<String> selected, Consumer<String> onSelect) {
        this.id = id;
        this.size = size;
        this.selected = selected;
        this.onSelect = onSelect;
    }

    public static float height(Size size) {
        switch (size) {
            case IN_GAME: return px(116f);
            case ONLINE: return px(76f);
            default: return px(60f);
        }
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        GameFriends.Friend f = GameFriends.find(id);
        if (f == null) return;
        updateStates(mouseX, mouseY);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        float a = clipFade;
        boolean isSelected = id.equals(selected.get());
        float radius = px(size == Size.OFFLINE ? UiTheme.Space.S14 : UiTheme.Space.S18);

        // ── Le fond ──────────────────────────────────────────────────────
        if (size == Size.OFFLINE) {
            r.drawRoundedRect(x, y, x + w, y + h, radius, new UiColor(255, 255, 255, 9).multiplyAlpha(a), vw, vh);
        } else {
            UiColor border = size == Size.IN_GAME ? FriendInfo.ONLINE.withAlpha(0.28f) : UiTheme.GLASS_BORDER;
            if (isSelected) border = UiTheme.accentLight().withAlpha(0.8f);
            UiSurface.card(hover).radius(radius).border(border).alpha(a).draw(r, x, y, w, h, vw, vh);
            if (size == Size.IN_GAME) {
                // Un reflet vert en haut : on le repère avant de lire.
                r.drawGradientRect(x, y, x + w, y + h, radius, FriendInfo.ONLINE.withAlpha(0f),
                    FriendInfo.ONLINE.withAlpha(0.11f * a), vw, vh);
            }
        }
        if (isSelected && size == Size.OFFLINE) {
            r.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.ACCENT.withAlpha(0.22f * a), vw, vh);
        }
        UiSurface.hoverWash(r, x, y, w, h, radius, hover * a, vw, vh);

        // ── La tête et sa pastille d'état ────────────────────────────────
        float head = size == Size.IN_GAME ? px(64f) : size == Size.ONLINE ? px(44f) : px(36f);
        float pad = size == Size.IN_GAME ? px(UiTheme.Space.S20) : px(UiTheme.Space.S16);
        float hx = x + pad, hy = y + (h - head) / 2f;
        float headAlpha = size == Size.OFFLINE ? 0.5f : 1f;
        FriendInfo.drawHead(r, f, hx, hy, head, a * headAlpha, vw, vh);
        float dot = size == Size.IN_GAME ? px(16f) : px(13f);
        float ring = px(UiTheme.Space.S3);
        float dx = hx + head - dot + px(UiTheme.Space.S4), dy = hy - px(UiTheme.Space.S4);
        r.drawRoundedRect(dx - ring, dy - ring, dx + dot + ring, dy + dot + ring, (dot + 2f * ring) / 2f,
            UiTheme.PANEL_BG.multiplyAlpha(a), vw, vh);
        r.drawRoundedRect(dx, dy, dx + dot, dy + dot, dot / 2f, FriendInfo.stateColor(f).multiplyAlpha(a), vw, vh);

        float tx = hx + head + px(size == Size.IN_GAME ? UiTheme.Space.S18 : UiTheme.Space.S14);
        float right = x + w - pad;
        float mid = y + h / 2f;

        if (size != Size.IN_GAME) {
            float nameScale = px(size == Size.ONLINE ? UiTheme.Text.MD : UiTheme.Text.SM);
            UiColor nameColor = size == Size.OFFLINE ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_PRIMARY;
            float maxW = right - tx;
            r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, f.name, maxW, nameScale), tx, mid + px(UiTheme.Space.S3),
                nameColor.multiplyAlpha(a), nameScale, vw, vh);
            float ls = px(UiTheme.Text.XS);
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, FriendInfo.line(f), maxW, ls), tx, mid - px(UiTheme.Space.S16),
                UiTheme.TEXT_MUTED.multiplyAlpha(a), ls, vw, vh);
            inviteRect = null;
            return;
        }

        // ── En jeu : « Inviter » à droite, quand on peut inviter ─────────
        inviteRect = null;
        if (PresenceContext.onPublicServer()) {
            float bs = px(UiTheme.Text.SM);
            String label = GameFriends.inviting() ? Lang.tr("Envoi...") : Lang.tr("Inviter");
            float bw = UiText.width(UiFont.BOLD, label, bs) + px(UiTheme.Space.S32);
            float bh = px(38f);
            float bx = right - bw, by = mid - bh / 2f;
            inviteRect = new float[]{ bx, by, bx + bw, by + bh };
            boolean over = inside(inviteRect, mouseX, mouseY);
            r.drawRoundedRect(bx, by, bx + bw, by + bh, px(UiTheme.Space.S10),
                (over ? UiTheme.ACCENT.withAlpha(0.55f) : new UiColor(255, 255, 255, 20)).multiplyAlpha(a), vw, vh);
            r.drawText(UiFont.BOLD, label, bx + px(UiTheme.Space.S16), UiText.centeredBaseline(UiFont.BOLD, by, bh, bs),
                UiTheme.TEXT_PRIMARY.multiplyAlpha(a), bs, vw, vh);
            right = bx - px(UiTheme.Space.S16);
        }

        float maxW = right - tx;
        float nameScale = px(UiTheme.Text.XL);
        r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, f.name, maxW, nameScale), tx, mid + px(UiTheme.Space.S14),
            UiTheme.TEXT_PRIMARY.multiplyAlpha(a), nameScale, vw, vh);

        float icon = px(22f);
        float ay = mid - px(UiTheme.Space.S12);
        BufferedImage img = FriendInfo.activityIcon(f);
        if (img != null) McTexture.draw(r, img, FriendInfo.activityIconKey(f), tx, ay - px(UiTheme.Space.S4), icon, icon, a, vw, vh);
        float as = px(UiTheme.Text.SM);
        float ax = tx + icon + px(UiTheme.Space.S8);
        r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, FriendInfo.activity(f), right - ax, as), ax, ay,
            FriendInfo.ONLINE.multiplyAlpha(a), as, vw, vh);

        String version = FriendInfo.version(f);
        if (!version.isEmpty()) {
            float vs = px(UiTheme.Text.XS);
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, version, maxW, vs), tx, ay - px(UiTheme.Space.S24),
                UiTheme.TEXT_MUTED.multiplyAlpha(a), vs, vw, vh);
        }
    }

    private static boolean inside(float[] rect, double mx, double my) {
        return rect != null && mx >= rect[0] && mx <= rect[2] && my >= rect[1] && my <= rect[3];
    }

    @Override
    protected void onActivate() {
        GameFriends.Friend f = GameFriends.find(id);
        if (f == null) return;
        if (inside(inviteRect, lastMouseX, lastMouseY)) {
            GameFriends.invite(f);
            return;
        }
        if (onSelect != null) onSelect.accept(id);
    }
}
