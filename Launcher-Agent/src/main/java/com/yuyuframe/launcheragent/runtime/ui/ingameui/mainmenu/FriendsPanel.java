package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiOverlay;
import com.yuyuframe.launcheragent.apigraphic.widget.UiOverlayStack;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.presence.PresenceContext;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIcon;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Les amis YuyuFrame, dans la barre latérale entre la navigation et
 * « Modifier le HUD » — même lecture que le panneau du launcher
 * ({@code components/friends/FriendsPanel.tsx}) : groupes « En jeu »,
 * « En ligne », « Hors ligne » repliables, une ligne par ami avec sa tête,
 * son pseudo et ce qu'il fait.
 *
 * <p>Aucun bouton sur les lignes : survoler un ami ouvre une BULLE à droite
 * de la barre (élément flottant de l'écran, {@link UiOverlayStack}) avec ce
 * qu'on sait de lui et « Inviter sur mon serveur ». Elle reste ouverte tant
 * que la souris est sur la ligne ou sur elle, avec un court délai pour
 * franchir l'écart entre les deux.
 *
 * <p>La recherche se replie derrière la loupe. La liste défile ligne par
 * ligne à la molette, on ne dessine que des lignes entières.
 *
 * <p>Données : {@link GameFriends}, par le canal du jeu.
 */
public final class FriendsPanel extends UiWidget {

    private static final String ICON_SEARCH = UiIcon.named("search");
    private static final String ICON_CLOSE = UiIcon.named("multiply");
    private static final String ICON_OPEN = UiIcon.named("expand-arrow");
    private static final String ICON_CLOSED = UiIcon.named("forward");
    private static final String ICON_FRIENDS = UiIcon.named("conference-call");

    // État gardé d'une ouverture du menu à l'autre.
    private static boolean searchOpen;
    private static final Set<String> collapsedGroups = new HashSet<>();
    private static int scroll;

    /** Champ de recherche : un widget de l'écran (il a besoin du clavier) que ce panneau place et cache. */
    private final UiTextField search;
    /** Bord gauche de la bulle, posé par l'écran (juste à droite de la barre latérale). */
    public float bubbleLeft;

    /** Ce qui est sous la souris dans le panneau, pour {@link #onClick()}. */
    private String hoveredAction;
    private int maxScroll;

    private GameFriends.Friend bubbleFriend;
    private float bubbleRowTop;
    private long bubbleSeenAt;
    private float[] bubbleRect, inviteRect;
    private UiOverlayStack bubbleStack;

    public FriendsPanel(UiTextField search) {
        super(0f, 0f, 0f, 0f);
        this.search = search;
    }

    /** Le champ de recherche, à ajouter aux widgets de l'écran. */
    public static UiTextField newSearchField() {
        return new UiTextField(0, 0, 0, 0, Lang.tr("Rechercher un ami..."), v -> scroll = 0) {
            @Override
            public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
                // Replié : taille nulle, rien à dessiner.
                if (w > 1f && h > 1f) super.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
            }
        }.searchIcon();
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoveredAction = null;
        if (w <= 0f || h <= 0f) {
            search.w = 0f;
            search.h = 0f;
            return;
        }
        GameFriends.keepFresh();
        float top = y + h;
        float pad = px(UiTheme.Space.S10);

        // ── En-tête : « Amis », combien en ligne, la loupe ─────────────────
        float headerH = px(UiTheme.Space.S40);
        renderer.drawRoundedRect(x + pad, top - px(1f), x + w - pad, top, 0f,
            UiTheme.TEXT_MUTED.multiplyAlpha(0.22f), vpWidth, vpHeight);
        float headerMid = top - headerH / 2f - px(UiTheme.Space.S3);
        float titleIcon = px(17f);
        UiIcon.crisp(renderer, ICON_FRIENDS, x + pad, headerMid - titleIcon / 2f + px(1f), titleIcon, 1f, vpWidth, vpHeight);
        float titleX = x + pad + titleIcon + px(UiTheme.Space.S10);
        float headingScale = px(UiTheme.Text.HEADING);
        renderer.drawText(UiFont.BOLD, Lang.tr("Amis"), titleX, headerMid - px(UiTheme.Space.S5), UiTheme.TEXT_PRIMARY,
            headingScale, vpWidth, vpHeight);
        List<GameFriends.Friend> all = GameFriends.friends();
        int online = 0;
        for (GameFriends.Friend f : all) if (f.online()) online++;
        if (GameFriends.loaded() && !all.isEmpty()) {
            float titleW = renderer.textWidth(UiFont.BOLD, Lang.tr("Amis"), headingScale);
            renderer.drawText(online + " " + Lang.tr("en ligne"), titleX + titleW + px(9f), headerMid - px(UiTheme.Space.S4),
                UiTheme.TEXT_MUTED, px(UiTheme.Text.BODY), vpWidth, vpHeight);
        }
        float iconS = px(UiTheme.Space.S16), halo = px(UiTheme.Space.S5);
        float ix = x + w - pad - iconS, iy = headerMid - iconS / 2f + px(1f);
        boolean onSearchIcon = mouseX >= ix - halo && mouseX <= ix + iconS + halo && mouseY >= iy - halo && mouseY <= iy + iconS + halo;
        if (onSearchIcon) {
            renderer.drawRoundedRect(ix - halo, iy - halo, ix + iconS + halo, iy + iconS + halo,
                UiTheme.RADIUS_SM, UiTheme.SIDEBAR_HOVER, vpWidth, vpHeight);
            hoveredAction = "search";
        }
        UiIcon.crisp(renderer, searchOpen ? ICON_CLOSE : ICON_SEARCH, ix, iy, iconS, 1f, vpWidth, vpHeight);

        // ── Recherche, repliée derrière la loupe ────────────────────────
        float cursor = top - headerH;
        if (searchOpen) {
            float fieldH = px(UiTheme.Space.S32);
            search.x = x + pad * 0.5f;
            search.w = w - pad;
            search.h = fieldH;
            search.y = cursor - fieldH - px(UiTheme.Space.S2);
            cursor -= fieldH + px(UiTheme.Space.S10);
        } else {
            search.w = 0f;
            search.h = 0f;
            if (search.focused()) search.setFocused(false);
        }

        // ── Résultat d'une invitation, réservé en bas ───────────────────
        String notice = GameFriends.notice();
        float bottom = y;
        float lineH = px(UiTheme.Space.S16);
        if (notice != null) {
            UiColor c = GameFriends.noticeIsError() ? UiTheme.DANGER : UiTheme.SUCCESS;
            UiText.drawWrapped(renderer, null, notice, x + pad, y + px(UiTheme.Space.S24), w - pad * 2f, 2, lineH,
                c, px(UiTheme.Text.SMALL), vpWidth, vpHeight);
            bottom += px(38f);
        }

        float maxW = w - pad * 2f;
        float messageBase = cursor - px(UiTheme.Space.S18);
        String message = null;
        if (!GameFriends.available()) {
            message = Lang.tr("Lance le jeu depuis le launcher YuyuFrame pour voir tes amis");
        } else if (!GameFriends.loaded()) {
            message = GameFriends.error() != null ? GameFriends.error() : Lang.tr("Chargement...");
        } else if (all.isEmpty()) {
            message = Lang.tr("Aucun ami pour l'instant. Ajoute-les depuis le launcher.");
        }
        if (message != null) {
            UiText.drawWrapped(renderer, null, message, x + pad, messageBase, maxW, 5, lineH, UiTheme.TEXT_MUTED,
                px(UiTheme.Text.BODY), vpWidth, vpHeight);
            updateBubble(null, 0f);
            return;
        }

        // ── Les lignes : en-têtes de groupe et amis, filtrés ───────────
        String needle = searchOpen ? search.text().trim().toLowerCase(Locale.ROOT) : "";
        List<Object> entries = new ArrayList<>();
        String[][] groups = {
            { "game", Lang.tr("En jeu") }, { "online", Lang.tr("En ligne") }, { "offline", Lang.tr("Hors ligne") } };
        for (String[] g : groups) {
            List<GameFriends.Friend> members = new ArrayList<>();
            for (GameFriends.Friend f : all) {
                String group = f.inGame ? "game" : f.online() ? "online" : "offline";
                if (!group.equals(g[0])) continue;
                if (!needle.isEmpty() && !f.name.toLowerCase(Locale.ROOT).contains(needle)) continue;
                members.add(f);
            }
            if (members.isEmpty()) continue;
            entries.add(new String[]{ g[0], g[1] + " · " + members.size() });
            if (!collapsedGroups.contains(g[0])) entries.addAll(members);
        }
        if (entries.isEmpty()) {
            UiText.drawWrapped(renderer, null, Lang.tr("Aucun résultat"), x + pad, messageBase, maxW, 2, lineH,
                UiTheme.TEXT_MUTED, px(UiTheme.Text.BODY), vpWidth, vpHeight);
            updateBubble(null, 0f);
            return;
        }

        float groupH = px(UiTheme.Space.S32), rowH = px(58f);
        // Combien d'entrées tiennent à partir de la fin : borne du défilement.
        float avail = cursor - bottom;
        float used = 0f;
        int fromEnd = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            float eh = entries.get(i) instanceof String[] ? groupH : rowH;
            if (used + eh > avail) break;
            used += eh;
            fromEnd++;
        }
        maxScroll = Math.max(0, entries.size() - fromEnd);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        GameFriends.Friend hoveredFriend = null;
        float hoveredTop = 0f;
        float rowTop = cursor;
        for (int i = scroll; i < entries.size(); i++) {
            Object e = entries.get(i);
            float eh = e instanceof String[] ? groupH : rowH;
            if (rowTop - eh < bottom - 0.5f) break;
            float rowBottom = rowTop - eh;
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowBottom && mouseY <= rowTop;
            if (e instanceof String[]) {
                String[] g = (String[]) e;
                if (hovered) hoveredAction = "group:" + g[0];
                float cs = px(11f);
                float scale = px(UiTheme.Text.BODY_STRONG);
                UiColor c = hovered ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY;
                renderer.drawText(UiFont.BOLD, g[1], x + pad, rowBottom + eh / 2f - px(4.5f), c, scale, vpWidth, vpHeight);
                float tw = renderer.textWidth(UiFont.BOLD, g[1], scale);
                UiIcon.crisp(renderer, collapsedGroups.contains(g[0]) ? ICON_CLOSED : ICON_OPEN,
                    x + pad + tw + px(UiTheme.Space.S6), rowBottom + eh / 2f - cs / 2f, cs, 1f, vpWidth, vpHeight);
            } else {
                GameFriends.Friend f = (GameFriends.Friend) e;
                boolean open = bubbleFriend != null && bubbleFriend.id.equals(f.id);
                if (hovered || open) {
                    renderer.drawRoundedRect(x, rowBottom + px(1f), x + w, rowTop - px(1f), px(UiTheme.Space.S6),
                        UiTheme.SIDEBAR_HOVER, vpWidth, vpHeight);
                }
                if (hovered) {
                    hoveredFriend = f;
                    hoveredTop = rowTop;
                }
                drawRow(renderer, f, x + pad * 0.6f, rowBottom, w - pad * 1.2f, eh, vpWidth, vpHeight);
            }
            rowTop = rowBottom;
        }
        // Liste relue entre-temps : la bulle suit l'ami (nouvel objet), ou se ferme s'il a disparu.
        if (hoveredFriend == null && bubbleFriend != null) {
            GameFriends.Friend same = null;
            for (Object e : entries) {
                if (e instanceof GameFriends.Friend && ((GameFriends.Friend) e).id.equals(bubbleFriend.id)) same = (GameFriends.Friend) e;
            }
            bubbleFriend = same;
        }
        updateBubble(hoveredFriend, hoveredTop);
    }

    /**
     * Ouvre la bulle sur l'ami survolé, ou la garde le temps du délai de
     * grâce (la souris sur la bulle elle-même la relance, voir {@link #bubble}).
     */
    private void updateBubble(GameFriends.Friend hovered, float rowTop) {
        long now = System.currentTimeMillis();
        if (hovered != null) {
            bubbleFriend = hovered;
            bubbleRowTop = rowTop;
            bubbleSeenAt = now;
        }
        UiOverlayStack stack = UiOverlayStack.active();
        boolean keep = bubbleFriend != null && now - bubbleSeenAt <= UiTheme.Motion.BUBBLE_GRACE_MS;
        if (keep && !stack.isOpen(bubble)) {
            bubbleStack = stack;
            stack.open(bubble);
        } else if (!keep && bubbleStack != null && bubbleStack.isOpen(bubble)) {
            bubbleStack.close(bubble);
        }
        if (!keep) bubbleFriend = null;
    }

    private static boolean inside(float[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx <= r[2] && my >= r[1] && my <= r[3];
    }

    /** Une ligne : tête avec la pastille d'état, pseudo, et ce qu'il fait. */
    private void drawRow(UiRenderer renderer, GameFriends.Friend f, float rx, float ry, float rw, float rh,
                         int vpWidth, int vpHeight) {
        float avatar = px(42f);
        float ax = rx, ay = ry + (rh - avatar) / 2f;
        drawHead(renderer, f, ax, ay, avatar, vpWidth, vpHeight);
        if (!f.online()) {
            renderer.drawRoundedRect(ax, ay, ax + avatar, ay + avatar, px(UiTheme.Space.S5), UiTheme.DIMMER, vpWidth, vpHeight);
        }
        float dot = px(13f), ring = px(UiTheme.Space.S2);
        float dx = ax + avatar - dot + ring, dy = ay - ring;
        renderer.drawRoundedRect(dx - ring, dy - ring, dx + dot + ring, dy + dot + ring, dot / 2f + ring,
            UiTheme.SIDEBAR_BG, vpWidth, vpHeight);
        renderer.drawRoundedRect(dx, dy, dx + dot, dy + dot, dot / 2f, UiTheme.statusColor(f.state), vpWidth, vpHeight);

        float tx = ax + avatar + px(UiTheme.Space.S12);
        float maxW = rx + rw - tx;
        float mid = ry + rh / 2f;
        float nameScale = px(UiTheme.Text.NAME);
        UiColor nameColor = f.online() ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED;
        String name = UiText.fit(UiFont.BOLD, f.name, maxW, nameScale);
        String sub = subtitle(f);
        if (sub == null) {
            renderer.drawText(UiFont.BOLD, name, tx, mid - px(UiTheme.Space.S5), nameColor, nameScale, vpWidth, vpHeight);
            return;
        }
        renderer.drawText(UiFont.BOLD, name, tx, mid + px(UiTheme.Space.S3), nameColor, nameScale, vpWidth, vpHeight);
        UiColor subColor = f.inGame ? UiTheme.SUCCESS
            : (f.online() && !f.custom.isEmpty()) ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED;
        float subScale = px(UiTheme.Text.BODY);
        renderer.drawText(UiText.fit(null, sub, maxW, subScale), tx, mid - px(UiTheme.Space.S14), subColor, subScale, vpWidth, vpHeight);
    }

    /** La deuxième ligne, mêmes règles que {@code PresenceLine} du launcher. */
    private static String subtitle(GameFriends.Friend f) {
        if (!f.online()) {
            String seen = f.lastSeen > 0 ? Lang.tr("Vu") + " " + ago(f.lastSeen) : null;
            if (!f.custom.isEmpty() && seen != null) return f.custom + " · " + seen;
            return !f.custom.isEmpty() ? f.custom : seen;
        }
        if (f.inGame) return activityText(f);
        if (!f.custom.isEmpty()) return f.custom;
        if ("launcher".equals(f.activity)) return Lang.tr("Dans le launcher");
        return null;
    }

    private static String activityText(GameFriends.Friend f) {
        if (!f.duel.isEmpty()) {
            String[] d = f.duel.split(":", 2);
            String kind = "ranked".equals(d[0]) ? Lang.tr("En duel classé") : Lang.tr("En duel amical");
            String mode = d.length > 1 ? modeName(d[1]) : "";
            return mode.isEmpty() ? kind : kind + " · " + mode;
        }
        String text = "Minecraft " + f.version;
        if (!f.loader.isEmpty() && !"vanilla".equals(f.loader)) {
            text += " · " + f.loader.substring(0, 1).toUpperCase(Locale.ROOT) + f.loader.substring(1);
        }
        return text;
    }

    private static String modeName(String mode) {
        switch (mode) {
            case "mace": return Lang.tr("Masse");
            case "sword": return Lang.tr("Épée");
            default: return "";
        }
    }

    /** « il y a 2 h », « hier »… Le mot de devant peut être vide dans une langue qui met tout derrière : d'où les {@code trim}. */
    private static String ago(long unixSeconds) {
        long s = Math.max(0L, System.currentTimeMillis() / 1000L - unixSeconds);
        if (s < 60L) return Lang.tr("à l'instant");
        if (s < 3600L) return (Lang.tr("il y a") + " " + (s / 60L) + " min").trim();
        if (s < 86400L) return (Lang.tr("il y a") + " " + (s / 3600L) + " h").trim();
        long days = s / 86400L;
        if (days == 1L) return Lang.tr("hier");
        return (Lang.tr("il y a") + " " + days + " " + Lang.tr("jours")).trim();
    }

    private static String stateLabel(GameFriends.Friend f) {
        if (f.inGame) return Lang.tr("En jeu");
        switch (f.state) {
            case "online": return Lang.tr("En ligne");
            case "away": return Lang.tr("Absent");
            case "dnd": return Lang.tr("Ne pas déranger");
            default: return Lang.tr("Hors ligne");
        }
    }

    /** La tête du compte Minecraft principal (mc-heads, comme le launcher), sinon l'initiale. */
    private static void drawHead(UiRenderer renderer, GameFriends.Friend f, float hx, float hy, float size, int vpWidth, int vpHeight) {
        if (!f.minecraftUuid.isEmpty()) {
            String url = "https://mc-heads.net/avatar/" + f.minecraftUuid.replace("-", "") + "/64";
            BufferedImage head = UiRemoteImage.get(url);
            if (head != null) {
                renderer.drawIcon(url, head, hx, hy, size, size, vpWidth, vpHeight);
                return;
            }
        }
        float ratio = size / px(42f);
        renderer.drawRoundedRect(hx, hy, hx + size, hy + size, px(UiTheme.Space.S5), UiTheme.ACCENT_DIM, vpWidth, vpHeight);
        String initial = f.name.isEmpty() ? "?" : f.name.substring(0, 1).toUpperCase(Locale.ROOT);
        float s = ratio * px(UiTheme.Text.INITIAL);
        UiText.drawCentered(renderer, UiFont.BOLD, initial, hx, size, hy + size / 2f - px(5.5f) * ratio,
            UiTheme.TEXT_PRIMARY, s, vpWidth, vpHeight);
    }

    /** La bulle de l'ami survolé, élément flottant de l'écran, à droite de la barre latérale. */
    private final UiOverlay bubble = new UiOverlay() {
        @Override public boolean modal() { return false; }
        /** Elle suit le survol, pas les clics : un clic ailleurs ne la ferme pas. */
        @Override public boolean closeOnOutsideClick() { return false; }
        /** Ni Échap : sous la souris elle se rouvrirait aussitôt, et Échap doit fermer l'écran. */
        @Override public boolean closeOnEscape() { return false; }
        @Override public float enterSeconds() { return 0.01f; }
        @Override public float exitSeconds() { return 0.01f; }

        @Override
        public boolean contains(double mx, double my) {
            return inside(bubbleRect, mx, my);
        }

        @Override
        public void click(double mx, double my, int button, boolean doubleClick) {
            if (button == 0 && bubbleFriend != null && inside(inviteRect, mx, my) && !GameFriends.inviting()) {
                GameFriends.invite(bubbleFriend);
            }
        }

        @Override
        public void onClosed() {
            bubbleRect = null;
            inviteRect = null;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight, float appear) {
            GameFriends.Friend f = bubbleFriend;
            if (f == null || w <= 0f) return;
            // La souris sur la bulle la garde ouverte (le panneau, lui, ne la voit pas : elle est couverte).
            if (inside(bubbleRect, mouseX, mouseY)) bubbleSeenAt = System.currentTimeMillis();
            drawBubble(renderer, f, mouseX, mouseY, vpWidth, vpHeight);
        }
    };

    private void drawBubble(UiRenderer renderer, GameFriends.Friend f, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float bw = px(320f), pad = px(UiTheme.Space.S16);
        List<String[]> lines = new ArrayList<>();
        if (f.inGame) lines.add(new String[]{ "accent", activityText(f) });
        else if ("launcher".equals(f.activity)) lines.add(new String[]{ "muted", Lang.tr("Dans le launcher") });
        if (!f.server.isEmpty()) lines.add(new String[]{ "secondary", Lang.tr("Serveur") + " : " + f.server });
        if (!f.custom.isEmpty()) lines.add(new String[]{ "secondary", "« " + f.custom + " »" });
        if (!f.online() && f.lastSeen > 0) lines.add(new String[]{ "muted", Lang.tr("Vu") + " " + ago(f.lastSeen) });
        // Inviter n'a de sens que sur un serveur public : en solo, au menu ou
        // sur un serveur privé, il n'y a rien à rejoindre.
        boolean canInvite = f.online() && PresenceContext.onPublicServer();

        float headS = px(58f), lineH = px(UiTheme.Space.S20), buttonH = px(UiTheme.Space.S36);
        float bh = pad + headS + px(UiTheme.Space.S12) + lines.size() * lineH
            + (canInvite ? px(UiTheme.Space.S10) + buttonH : 0f) + pad;
        float edge = px(UiTheme.Space.S12);
        float bx = bubbleLeft;
        float btop = Math.min(bubbleRowTop + px(UiTheme.Space.S6), vpHeight - edge);
        float bbottom = btop - bh;
        if (bbottom < edge) { bbottom = edge; btop = bbottom + bh; }
        // Le rectangle « vivant » déborde vers la barre : la souris qui passe
        // de la ligne à la bulle ne la ferme pas en route.
        bubbleRect = new float[]{ bx - px(UiTheme.Space.S10), bbottom, bx + bw, btop };

        float radius = px(UiTheme.Space.S10);
        renderer.drawShadow(bx, bbottom, bx + bw, btop, radius, px(UiTheme.Space.S18), 0f, UiTheme.SHADOW_FLOATING, vpWidth, vpHeight);
        renderer.drawRoundedRect(bx, bbottom, bx + bw, btop, radius, UiTheme.PANEL_BG, vpWidth, vpHeight);
        renderer.drawRoundedRect(bx + radius, btop - px(UiTheme.Space.S3), bx + bw - radius, btop, px(UiTheme.Radius.XS),
            UiTheme.statusColor(f.state).multiplyAlpha(0.8f), vpWidth, vpHeight);

        float hx = bx + pad, hy = btop - pad - headS;
        drawHead(renderer, f, hx, hy, headS, vpWidth, vpHeight);
        float tx = hx + headS + px(UiTheme.Space.S12), maxW = bx + bw - pad - tx;
        float subtitleScale = px(UiTheme.Text.SUBTITLE);
        renderer.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, f.name, maxW, subtitleScale), tx, hy + headS / 2f + px(UiTheme.Space.S4),
            UiTheme.TEXT_PRIMARY, subtitleScale, vpWidth, vpHeight);
        float dot = px(11f), dotY = hy + headS / 2f - px(17f);
        float bodyScale = px(UiTheme.Text.BODY_STRONG);
        renderer.drawRoundedRect(tx, dotY, tx + dot, dotY + dot, dot / 2f, UiTheme.statusColor(f.state), vpWidth, vpHeight);
        renderer.drawText(stateLabel(f), tx + dot + px(7f), dotY + px(1.5f), UiTheme.TEXT_SECONDARY, bodyScale, vpWidth, vpHeight);

        float ly = hy - px(UiTheme.Space.S12) - lineH + px(UiTheme.Space.S4);
        for (String[] l : lines) {
            UiColor c = "accent".equals(l[0]) ? UiTheme.SUCCESS : "secondary".equals(l[0]) ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED;
            renderer.drawText(UiText.fit(null, l[1], bw - pad * 2f, bodyScale), bx + pad, ly, c, bodyScale, vpWidth, vpHeight);
            ly -= lineH;
        }

        if (canInvite) {
            float by1 = bbottom + pad, by2 = by1 + buttonH;
            float bx1 = bx + pad, bx2 = bx + bw - pad;
            inviteRect = new float[]{ bx1, by1, bx2, by2 };
            boolean hot = inside(inviteRect, mouseX, mouseY) && !GameFriends.inviting();
            renderer.drawRoundedRect(bx1, by1, bx2, by2, px(7f), hot ? UiTheme.accentLight() : UiTheme.ACCENT, vpWidth, vpHeight);
            String label = GameFriends.inviting() ? Lang.tr("Envoi...") : Lang.tr("Inviter sur mon serveur");
            UiText.drawCentered(renderer, UiFont.BOLD, label, bx1, bx2 - bx1, by1 + buttonH / 2f - px(UiTheme.Space.S5),
                UiTheme.TEXT_PRIMARY, px(UiTheme.Text.LABEL), vpWidth, vpHeight);
        } else {
            inviteRect = null;
        }
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (input.scrollDelta != 0 && w > 0f && input.mouseX >= x && input.mouseX <= x + w
                && input.mouseY >= y && input.mouseY <= y + h) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - Integer.signum(input.scrollDelta)));
        }
    }

    @Override
    public void onClick() {
        String action = hoveredAction;
        if (action == null) return;
        if (action.equals("search")) {
            searchOpen = !searchOpen;
            if (searchOpen) {
                search.setFocused(true);
            } else {
                search.setText("");
                search.setFocused(false);
            }
            scroll = 0;
        } else if (action.startsWith("group:")) {
            String g = action.substring(6);
            if (!collapsedGroups.remove(g)) collapsedGroups.add(g);
        }
    }
}
