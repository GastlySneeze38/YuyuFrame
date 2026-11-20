package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.presence.PresenceContext;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.ProfileBanner;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * La fiche de l'ami choisi, à droite de l'onglet Amis : sa bannière et son
 * titre (tels qu'il les montre à ses amis, question {@code fprofile} du
 * canal), sa tête, son état, sa bio ou son statut perso, puis ce qu'il fait,
 * où, en quelle version, et deux boutons : l'inviter sur mon serveur, ou voir
 * son profil dans le launcher.
 *
 * <p>Les boutons sont des widgets à part ({@link #children}), placés par la
 * fiche à chaque image : sa hauteur suit ce qu'il y a à dire.
 */
public final class FriendDetail extends UiWidget {

    private static final float BANNER_H = 96f, HEAD = 88f, PAD = 24f, RADIUS = 22f, FACT_H = 48f;
    private static final UiColor GOLD = new UiColor(232, 184, 72, 255);

    private final Supplier<String> selected;
    private final Button invite;
    private final Button profile;
    /** Hauteur réellement dessinée à la dernière image (la fiche est calée en haut de sa boîte). */
    private float drawnHeight;

    public FriendDetail(Supplier<String> selected) {
        super(0f, 0f, 0f, 0f);
        this.selected = selected;
        this.invite = new Button(() -> GameFriends.inviting() ? Lang.tr("Envoi...") : Lang.tr("Inviter sur mon serveur"), true,
            () -> canInvite(friend()), () -> GameFriends.invite(friend()));
        this.profile = new Button(() -> Lang.tr("Voir le profil dans le launcher"), false,
            () -> friend() != null && GameFriends.available(), () -> GameFriends.openInLauncher(friend()));
    }

    public List<UiWidget> children() {
        List<UiWidget> out = new ArrayList<>();
        out.add(invite);
        out.add(profile);
        return out;
    }

    private GameFriends.Friend friend() {
        return GameFriends.find(selected.get());
    }

    /** Inviter n'a de sens que sur un serveur public : en solo, au menu ou sur un serveur privé, il n'y a rien à rejoindre. */
    private static boolean canInvite(GameFriends.Friend f) {
        return f != null && f.online() && PresenceContext.onPublicServer() && !GameFriends.inviting();
    }

    @Override
    public boolean contains(double mx, double my) {
        // Seuls les boutons se cliquent.
        return false;
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        GameFriends.Friend f = friend();
        float top = y + h;
        float pad = px(PAD);
        if (f == null) {
            hideButtons();
            float ch = px(120f);
            UiSurface.card(0f).radius(px(RADIUS)).draw(r, x, top - ch, w, ch, vw, vh);
            float s = px(UiTheme.Text.MD);
            String text = GameFriends.friends().isEmpty() ? Lang.tr("Ajoute des amis depuis le launcher")
                : Lang.tr("Choisis un ami pour voir ce qu'il fait");
            UiText.drawCentered(r, UiFont.REGULAR, UiText.fit(UiFont.REGULAR, text, w - 2f * pad, s), x, w,
                UiText.centeredBaseline(UiFont.REGULAR, top - ch, ch, s), UiTheme.TEXT_MUTED, s, vw, vh);
            return;
        }
        GameFriends.Profile p = GameFriends.profile(f.id);

        // Ce qu'on a à dire, pour connaître la hauteur avant de dessiner.
        List<String[]> facts = new ArrayList<>();
        if (f.inGame) facts.add(new String[]{ "activity", Lang.tr("Activité"), FriendInfo.activity(f) });
        // Le serveur a sa ligne quand l'activité parle d'autre chose (un duel) : sinon elle le dit déjà.
        if (!f.server.isEmpty() && (!f.inGame || !f.duel.isEmpty())) facts.add(new String[]{ "server", Lang.tr("Serveur"), f.server });
        String version = FriendInfo.version(f).replace("Minecraft ", "");
        if (!version.isEmpty() && f.inGame) facts.add(new String[]{ "version", Lang.tr("Version"), version });
        if (!f.online() && f.lastSeen > 0) facts.add(new String[]{ "seen", Lang.tr("Vu"), FriendInfo.ago(f.lastSeen) });
        if (f.online() && !f.inGame && "launcher".equals(f.activity)) facts.add(new String[]{ "launcher", Lang.tr("Activité"), Lang.tr("Dans le launcher") });
        String quote = p != null && !p.bio.isEmpty() ? p.bio : f.custom;
        String title = p != null ? p.title : "";
        String notice = GameFriends.notice();
        String hint = notice == null && f.online() && !PresenceContext.onPublicServer()
            ? Lang.tr("Rejoins un serveur pour inviter tes amis") : null;

        // Chaque ligne, en distance depuis le haut de la fiche.
        float nameOff = px(UiTheme.Space.S8) + px(BANNER_H) + px(44f) + px(34f);
        float titleOff = title.isEmpty() ? nameOff : nameOff + px(26f);
        float quoteOff = quote.isEmpty() ? titleOff : titleOff + px(UiTheme.Space.S28);
        float factsOff = quoteOff + px(18f);
        float inviteOff = factsOff + facts.size() * px(FACT_H) + px(UiTheme.Space.S20) + px(48f);
        float profileOff = inviteOff + px(UiTheme.Space.S10) + px(44f);
        float noticeOff = profileOff + px(22f);
        float cardH = Math.min(h, profileOff + (notice != null || hint != null ? px(30f) : 0f) + pad);
        drawnHeight = cardH;
        float cy = top - cardH;

        UiSurface.card(0f).radius(px(RADIUS)).draw(r, x, cy, w, cardH, vw, vh);
        float inset = px(UiTheme.Space.S8);
        float bannerBottom = top - inset - px(BANNER_H);
        ProfileBanner.draw(r, p != null ? p.banner : "", p != null ? p.accent : "", x + inset, bannerBottom, w - 2f * inset,
            px(BANNER_H), px(RADIUS - 6f), vw, vh);

        // La tête, à cheval sur la bannière.
        float head = px(HEAD);
        float hx = x + pad, hy = bannerBottom - px(44f);
        float frame = px(UiTheme.Space.S4);
        r.drawRoundedRect(hx - frame, hy - frame, hx + head + frame, hy + head + frame, px(UiTheme.Space.S10), UiTheme.PANEL_BG, vw, vh);
        FriendInfo.drawHead(r, f, hx, hy, head, f.online() ? 1f : 0.6f, vw, vh);

        // Pseudo, puis une pastille d'état.
        float nameScale = px(UiTheme.Text.XXL);
        float nameBase = top - nameOff;
        float nameW = UiText.width(UiFont.BOLD, f.name, nameScale);
        String state = FriendInfo.stateLabel(f);
        float cs = px(UiTheme.Text.XS);
        float chipW = UiText.width(UiFont.BOLD, state, cs) + px(UiTheme.Space.S16);
        float maxName = w - 2f * pad - chipW - px(UiTheme.Space.S12);
        r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, f.name, maxName, nameScale), x + pad, nameBase, UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
        float chipX = x + pad + Math.min(nameW, maxName) + px(UiTheme.Space.S12);
        float chipH = px(UiTheme.Space.S24);
        float chipY = nameBase - px(UiTheme.Space.S2);
        UiColor sc = FriendInfo.stateColor(f);
        r.drawRoundedRect(chipX, chipY, chipX + chipW, chipY + chipH, px(UiTheme.Space.S8), sc.withAlpha(0.16f), vw, vh);
        r.drawText(UiFont.BOLD, state, chipX + px(UiTheme.Space.S8), UiText.centeredBaseline(UiFont.BOLD, chipY, chipH, cs), sc, cs, vw, vh);

        // Le titre mis en vitrine, en doré, sous le pseudo.
        if (!title.isEmpty()) {
            float ly = top - titleOff;
            float ts = px(UiTheme.Text.SM);
            BufferedImage tag = HomeIcons.nameTag();
            float icon = px(18f);
            if (tag != null) McTexture.draw(r, tag, "name_tag", x + pad, ly - px(UiTheme.Space.S4), icon, icon, 1f, vw, vh);
            r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, title, w - 2f * pad - icon, ts), x + pad + icon + px(UiTheme.Space.S8), ly,
                GOLD, ts, vw, vh);
        }
        // Sa bio, sinon son statut perso.
        if (!quote.isEmpty()) {
            float qs = px(UiTheme.Text.MD);
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, "« " + quote + " »", w - 2f * pad, qs), x + pad, top - quoteOff,
                UiTheme.TEXT_SECONDARY, qs, vw, vh);
        }

        // Les faits, une ligne chacun, séparées par un trait.
        float fy = top - factsOff;
        float line = Math.max(1f, px(1f));
        UiColor sep = new UiColor(255, 255, 255, 18);
        if (!facts.isEmpty()) r.drawRoundedRect(x + pad, fy - line, x + w - pad, fy, 0f, sep, vw, vh);
        float fs = px(UiTheme.Text.SM);
        float labelW = px(96f);
        for (String[] fact : facts) {
            float rowTop = fy, rowBottom = fy - px(FACT_H);
            float base = UiText.centeredBaseline(UiFont.REGULAR, rowBottom, px(FACT_H), fs);
            BufferedImage img = factIcon(fact[0], f);
            float icon = px(22f);
            if (img != null) McTexture.draw(r, img, factIconKey(fact[0], f), x + pad, rowBottom + (px(FACT_H) - icon) / 2f, icon, icon, 1f, vw, vh);
            float lx = x + pad + icon + px(UiTheme.Space.S12);
            r.drawText(UiFont.REGULAR, fact[1], lx, base, UiTheme.TEXT_MUTED, fs, vw, vh);
            float vx = lx + labelW;
            r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, fact[2], x + w - pad - vx, fs), vx, base, UiTheme.TEXT_PRIMARY, fs, vw, vh);
            r.drawRoundedRect(x + pad, rowBottom - line, x + w - pad, rowBottom, 0f, sep, vw, vh);
            fy = rowBottom;
            if (rowTop < cy) break;
        }

        // Les boutons.
        place(invite, x + pad, top - inviteOff, w - 2f * pad, px(48f));
        place(profile, x + pad, top - profileOff, w - 2f * pad, px(44f));
        String below = notice != null ? notice : hint;
        if (below != null) {
            float ns = px(UiTheme.Text.XS);
            UiColor nc = notice == null ? UiTheme.TEXT_MUTED : GameFriends.noticeIsError() ? FriendInfo.DND : FriendInfo.ONLINE;
            UiText.drawCentered(r, UiFont.REGULAR, UiText.fit(UiFont.REGULAR, below, w - 2f * pad, ns), x, w, top - noticeOff, nc, ns, vw, vh);
        }
    }

    private static BufferedImage factIcon(String kind, GameFriends.Friend f) {
        switch (kind) {
            case "activity": return FriendInfo.activityIcon(f);
            case "server": return HomeIcons.compass();
            case "version": return HomeIcons.book();
            case "seen": return HomeIcons.clock();
            default: return HomeIcons.map();
        }
    }

    private static String factIconKey(String kind, GameFriends.Friend f) {
        switch (kind) {
            case "activity": return FriendInfo.activityIconKey(f);
            case "server": return "compass_00";
            case "version": return "writable_book";
            case "seen": return "clock_00";
            default: return "filled_map";
        }
    }

    private static void place(UiWidget wd, float x, float y, float w, float h) {
        wd.x = x;
        wd.y = y;
        wd.w = w;
        wd.h = h;
    }

    private void hideButtons() {
        invite.w = 0f;
        profile.w = 0f;
    }

    /** Un bouton de la fiche : plein (accent) ou doux ; grisé quand il ne peut rien faire. */
    public static final class Button extends UiInteractive {
        private final Supplier<String> label;
        private final boolean primary;
        private final BooleanSupplier enabled;
        private final Runnable action;

        public Button(Supplier<String> label, boolean primary, BooleanSupplier enabled, Runnable action) {
            this.label = label;
            this.primary = primary;
            this.enabled = enabled;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (w <= 0f || h <= 0f) return;
            boolean on = enabled.getAsBoolean();
            disabled = !on;
            updateStates(mouseX, mouseY);
            float radius = px(UiTheme.Space.S12);
            UiColor fill;
            if (primary) {
                fill = on ? UiColor.lerp(UiTheme.ACCENT, UiTheme.accentLight(), hover * 0.5f) : UiTheme.ACCENT.withAlpha(0.3f);
            } else {
                fill = new UiColor(255, 255, 255, 14 + (int) (16 * hover));
            }
            r.drawRoundedRect(x, y, x + w, y + h, radius, fill, vw, vh);
            if (!primary) UiSurface.hoverWash(r, x, y, w, h, radius, hover, vw, vh);
            float s = px(primary ? UiTheme.Text.MD : UiTheme.Text.SM);
            String text = UiText.fit(UiFont.BOLD, label.get(), w - px(UiTheme.Space.S16), s);
            UiColor color = !on ? UiTheme.TEXT_MUTED : primary ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY;
            UiText.drawCentered(r, UiFont.BOLD, text, x, w, UiText.centeredBaseline(UiFont.BOLD, y, h, s), color, s, vw, vh);
        }

        @Override
        protected void onActivate() {
            if (enabled.getAsBoolean() && action != null) action.run();
        }
    }
}
