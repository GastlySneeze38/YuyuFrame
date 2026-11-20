package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Ce que l'onglet Amis dit d'un ami, au même endroit pour la liste et la
 * fiche : son état, ce qu'il fait (mêmes règles que {@code PresenceLine} du
 * launcher), depuis quand on ne l'a pas vu, sa tête et l'objet du jeu qui
 * illustre son activité.
 */
final class FriendInfo {
    private FriendInfo() {}

    static final UiColor ONLINE = new UiColor(92, 210, 128, 255);
    static final UiColor AWAY = new UiColor(245, 166, 35, 255);
    static final UiColor DND = new UiColor(232, 90, 107, 255);
    static final UiColor OFFLINE = new UiColor(120, 120, 134, 255);

    static UiColor stateColor(GameFriends.Friend f) {
        switch (f.state) {
            case "online": return ONLINE;
            case "away": return AWAY;
            case "dnd": return DND;
            default: return OFFLINE;
        }
    }

    static String stateLabel(GameFriends.Friend f) {
        if (f.inGame) return Lang.tr("En jeu");
        switch (f.state) {
            case "online": return Lang.tr("En ligne");
            case "away": return Lang.tr("Absent");
            case "dnd": return Lang.tr("Ne pas déranger");
            default: return Lang.tr("Hors ligne");
        }
    }

    /** En jeu : « En duel classé - Masse », « Sur play.exemple.fr », sinon « En jeu ». */
    static String activity(GameFriends.Friend f) {
        if (!f.duel.isEmpty()) {
            String[] d = f.duel.split(":", 2);
            String kind = "ranked".equals(d[0]) ? Lang.tr("En duel classé") : Lang.tr("En duel amical");
            String mode = d.length > 1 ? modeName(d[1]) : "";
            return mode.isEmpty() ? kind : kind + " - " + mode;
        }
        if (!f.server.isEmpty()) return Lang.tr("Sur") + " " + f.server;
        return Lang.tr("En jeu");
    }

    /** « Minecraft 1.21.11 - Fabric », vide sans version connue. */
    static String version(GameFriends.Friend f) {
        if (f.version.isEmpty()) return "";
        String text = "Minecraft " + f.version;
        if (!f.loader.isEmpty() && !"vanilla".equals(f.loader)) text += " - " + capitalized(f.loader);
        return text;
    }

    /** La seconde ligne d'un ami qui n'est pas en jeu : son statut perso, le launcher, ou depuis quand il est parti. */
    static String line(GameFriends.Friend f) {
        if (!f.online()) {
            String seen = f.lastSeen > 0 ? Lang.tr("Vu") + " " + ago(f.lastSeen) : Lang.tr("Hors ligne");
            return f.custom.isEmpty() ? seen : f.custom;
        }
        if (!f.custom.isEmpty()) return f.custom;
        if ("launcher".equals(f.activity)) return Lang.tr("Dans le launcher");
        return stateLabel(f);
    }

    static String modeName(String mode) {
        switch (mode) {
            case "mace": return Lang.tr("Masse");
            case "sword": return Lang.tr("Épée");
            default: return "";
        }
    }

    /** « il y a 2 h », « hier »… Le mot de devant peut être vide dans une langue qui met tout derrière : d'où les {@code trim}. */
    static String ago(long unixSeconds) {
        long s = Math.max(0L, System.currentTimeMillis() / 1000L - unixSeconds);
        if (s < 60L) return Lang.tr("à l'instant");
        if (s < 3600L) return (Lang.tr("il y a") + " " + (s / 60L) + " min").trim();
        if (s < 86400L) return (Lang.tr("il y a") + " " + (s / 3600L) + " h").trim();
        long days = s / 86400L;
        if (days == 1L) return Lang.tr("hier");
        return (Lang.tr("il y a") + " " + days + " " + Lang.tr("jours")).trim();
    }

    /** L'objet du jeu de son activité : la masse ou l'épée en duel, la boussole sur un serveur, la carte sinon. */
    static BufferedImage activityIcon(GameFriends.Friend f) {
        if (!f.duel.isEmpty()) return f.duel.endsWith("sword") ? HomeIcons.sword() : HomeIcons.mace();
        if (!f.server.isEmpty()) return HomeIcons.compass();
        return HomeIcons.map();
    }

    static String activityIconKey(GameFriends.Friend f) {
        if (!f.duel.isEmpty()) return f.duel.endsWith("sword") ? "diamond_sword" : "mace";
        if (!f.server.isEmpty()) return "compass_00";
        return "filled_map";
    }

    /** La tête du compte Minecraft principal (mc-heads, comme le launcher), sinon celle de Steve. */
    static void drawHead(UiRenderer r, GameFriends.Friend f, float x, float y, float size, float alpha, int vw, int vh) {
        BufferedImage img = null;
        String key = null;
        if (!f.minecraftUuid.isEmpty()) {
            key = "https://mc-heads.net/avatar/" + f.minecraftUuid.replace("-", "") + "/64";
            img = UiRemoteImage.get(key);
        }
        if (img == null) {
            img = McTexture.defaultFace();
            key = "friend-face-default";
        }
        if (img != null) r.drawIcon(key, img, x, y, size, size, alpha, vw, vh);
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
