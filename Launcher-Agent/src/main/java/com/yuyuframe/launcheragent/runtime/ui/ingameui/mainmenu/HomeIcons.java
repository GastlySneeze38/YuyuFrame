package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;

import java.awt.image.BufferedImage;

/**
 * Les objets du jeu choisis pour l'accueil, avec leur nom moderne et leurs
 * noms de la 1.8.9. Un objet absent d'une version (la masse en 1.8.9) prend
 * le plus proche qui existe.
 */
public final class HomeIcons {
    private HomeIcons() {}

    public static BufferedImage mace() { return McTexture.item("mace", "iron_sword"); }
    public static BufferedImage sword() { return McTexture.item("diamond_sword", "diamond_sword"); }
    static BufferedImage ironSword() { return McTexture.item("iron_sword", "iron_sword"); }
    public static BufferedImage totem() { return McTexture.item("totem_of_undying", "golden_apple"); }
    static BufferedImage clock() { return McTexture.item("clock_00", "clock"); }
    public static BufferedImage book() { return McTexture.item("writable_book", "book_writable"); }
    public static BufferedImage map() { return McTexture.item("filled_map", "map_filled"); }
    static BufferedImage compass() { return McTexture.item("compass_00", "compass"); }
    static BufferedImage xpBottle() { return McTexture.item("experience_bottle", "experience_bottle"); }
    static BufferedImage emerald() { return McTexture.item("emerald", "emerald"); }
    static BufferedImage nameTag() { return McTexture.item("name_tag", "name_tag"); }
    static BufferedImage feather() { return McTexture.item("feather", "feather"); }

    /** L'objet d'une sorte de mission (voir {@code GameMissions.Mission.kind}). */
    static BufferedImage mission(String kind) {
        if (kind == null) return xpBottle();
        switch (kind) {
            case "winDuels": return sword();
            case "playDuels": return ironSword();
            case "scoreAbove":
            case "scoresAbove": return xpBottle();
            case "streak": return totem();
            case "quickWin": return feather();
            case "longWin": return clock();
            case "foes": return nameTag();
            case "servers": return compass();
            case "bothModes": return mace();
            case "rrGain": return emerald();
            default: return xpBottle();
        }
    }
}
