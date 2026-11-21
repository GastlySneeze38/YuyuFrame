package com.yuyuframe.launcheragent.runtime.ui.shortcuts;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;

import java.util.EnumMap;
import java.util.Map;

/**
 * Où chaque raccourci se trouve, écran par écran, et le style des boutons.
 *
 * <h2>Une position qui suit la fenêtre</h2>
 *
 * Un raccourci posé librement se souvient du coin, du bord ou du centre dont
 * il est le plus proche, et de sa distance à ce repère, en pixels de GUI (ceux
 * des boutons du jeu). Agrandir la fenêtre ou changer la taille de l'interface
 * du jeu le laisse donc au même endroit par rapport à ce qui l'entoure : collé
 * en haut à droite, il y reste.
 *
 * <h2>Rangement</h2>
 *
 * Dans le fichier des modules ({@link HudConfigStore#loadText}) :
 * {@code shortcuts.<écran>.<raccourci>=mode,ax,ay,dx,dy} et
 * {@code shortcuts.style=minecraft|glass}. Une valeur illisible retombe sur le
 * placement par défaut.
 */
public final class ShortcutLayout {
    private ShortcutLayout() {}

    /** Les écrans qui portent des raccourcis. */
    public enum Screen {
        TITLE("title", "Écran-titre"),
        PAUSE("pause", "Menu Échap");

        public final String id;
        public final String label;

        Screen(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Où va un raccourci sur un écran. */
    public enum Mode {
        /** Posé où l'on veut, à la souris. */
        FREE("free", "Libre"),
        /** Une icône de plus dans la rangée d'icônes du centre de l'écran. */
        ROW("row", "Rangée"),
        /** Absent de cet écran. */
        HIDDEN("hidden", "Masqué");

        public final String id;
        public final String label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** Le mode suivant, pour le bouton de l'éditeur qui les fait défiler. */
        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        static Mode of(String id) {
            for (Mode m : values()) if (m.id.equals(id)) return m;
            return null;
        }
    }

    /** Le style des boutons, commun aux deux écrans. */
    public enum Style {
        /** Le bouton du jeu, sa texture et sa police : se fond dans le menu. */
        MINECRAFT("minecraft", "Minecraft"),
        /** Le verre de l'interface YuyuFrame. */
        GLASS("glass", "Verre");

        public final String id;
        public final String label;

        Style(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /**
     * La place d'un raccourci. {@code ax}/{@code ay} : 0 = bord gauche/haut,
     * 1 = centre, 2 = bord droit/bas. {@code dx}/{@code dy} : distance à ce
     * repère en pixels de GUI, vers l'intérieur de l'écran (au centre : un
     * décalage vers la droite/le bas).
     */
    public static final class Place {
        public Mode mode;
        public int ax, ay, dx, dy;

        Place(Mode mode, int ax, int ay, int dx, int dy) {
            this.mode = mode;
            this.ax = ax;
            this.ay = ay;
            this.dx = dx;
            this.dy = dy;
        }

        /** Coin haut-gauche dans un écran de {@code sw x sh} pixels de GUI, y vers le bas. */
        public float x(float sw, float w) {
            return ax == 0 ? dx : ax == 2 ? sw - w - dx : (sw - w) / 2f + dx;
        }

        public float y(float sh, float h) {
            return ay == 0 ? dy : ay == 2 ? sh - h - dy : (sh - h) / 2f + dy;
        }

        /** Retient la position {@code (x, y)} (coin haut-gauche) par rapport au repère le plus proche. */
        public void moveTo(float x, float y, float w, float h, float sw, float sh) {
            float cx = x + w / 2f, cy = y + h / 2f;
            ax = cx < sw / 3f ? 0 : cx > sw * 2f / 3f ? 2 : 1;
            ay = cy < sh / 3f ? 0 : cy > sh * 2f / 3f ? 2 : 1;
            dx = Math.round(ax == 0 ? x : ax == 2 ? sw - w - x : x - (sw - w) / 2f);
            dy = Math.round(ay == 0 ? y : ay == 2 ? sh - h - y : y - (sh - h) / 2f);
            mode = Mode.FREE;
        }

        String encode() {
            return mode.id + "," + ax + "," + ay + "," + dx + "," + dy;
        }
    }

    private static final Map<Screen, Map<Shortcut, Place>> PLACES = new EnumMap<>(Screen.class);
    private static Style style;

    /** La place d'un raccourci sur un écran ; toujours {@code HIDDEN} s'il n'y est pas permis. */
    public static synchronized Place place(Screen screen, Shortcut shortcut) {
        Map<Shortcut, Place> map = PLACES.computeIfAbsent(screen, s -> new EnumMap<>(Shortcut.class));
        Place place = map.get(shortcut);
        if (place == null) {
            place = load(screen, shortcut);
            map.put(shortcut, place);
        }
        if (!shortcut.allowedOn(screen)) place.mode = Mode.HIDDEN;
        return place;
    }

    /** À appeler après avoir modifié une {@link Place} : écrite à la prochaine sauvegarde. */
    public static synchronized void save(Screen screen, Shortcut shortcut) {
        HudConfigStore.saveText(key(screen, shortcut), place(screen, shortcut).encode());
    }

    /** Remet un écran à son placement d'origine. */
    public static synchronized void reset(Screen screen) {
        Map<Shortcut, Place> map = PLACES.computeIfAbsent(screen, s -> new EnumMap<>(Shortcut.class));
        for (Shortcut s : Shortcut.values()) {
            map.put(s, defaultPlace(screen, s));
            HudConfigStore.saveText(key(screen, s), null);
        }
    }

    public static synchronized Style style() {
        if (style == null) {
            style = "glass".equals(HudConfigStore.loadText("shortcuts.style", "minecraft")) ? Style.GLASS : Style.MINECRAFT;
        }
        return style;
    }

    public static synchronized void setStyle(Style value) {
        style = value;
        HudConfigStore.saveText("shortcuts.style", value.id);
    }

    private static String key(Screen screen, Shortcut shortcut) {
        return "shortcuts." + screen.id + "." + shortcut.id;
    }

    private static Place load(Screen screen, Shortcut shortcut) {
        String text = HudConfigStore.loadText(key(screen, shortcut), null);
        if (text != null) {
            String[] p = text.split(",");
            Mode mode = p.length == 5 ? Mode.of(p[0]) : null;
            if (mode != null) {
                try {
                    return new Place(mode, clamp(Integer.parseInt(p[1])), clamp(Integer.parseInt(p[2])),
                        Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                } catch (NumberFormatException e) {
                    LauncherLog.warn("[Raccourcis] placement illisible pour " + key(screen, shortcut) + " : " + text);
                }
            } else {
                LauncherLog.warn("[Raccourcis] placement illisible pour " + key(screen, shortcut) + " : " + text);
            }
        }
        return defaultPlace(screen, shortcut);
    }

    private static int clamp(int anchor) {
        return Math.max(0, Math.min(2, anchor));
    }

    /**
     * Le placement de départ. Écran-titre : le compte en haut à gauche, comme
     * avant ; le menu, les missions et le compétitif dans la rangée d'icônes ;
     * les amis en haut à droite. Menu Échap : le menu et les modules dans la
     * rangée, les missions et le rang en haut à gauche.
     */
    private static Place defaultPlace(Screen screen, Shortcut shortcut) {
        if (screen == Screen.TITLE) {
            switch (shortcut) {
                case ACCOUNT: return new Place(Mode.FREE, 0, 0, 6, 6);
                case MENU: return new Place(Mode.ROW, 0, 0, 0, 0);
                case MISSIONS: return new Place(Mode.ROW, 0, 0, 0, 0);
                case RANKED: return new Place(Mode.ROW, 0, 0, 0, 0);
                case FRIENDS: return new Place(Mode.FREE, 2, 0, 6, 6);
                default: return new Place(Mode.HIDDEN, 0, 0, 6, 6);
            }
        }
        switch (shortcut) {
            case MENU: return new Place(Mode.ROW, 0, 0, 0, 0);
            case MODULES: return new Place(Mode.ROW, 0, 0, 0, 0);
            case MISSIONS: return new Place(Mode.FREE, 0, 0, 6, 6);
            case RANKED: return new Place(Mode.FREE, 0, 0, 6, 30);
            default: return new Place(Mode.HIDDEN, 0, 0, 6, 54);
        }
    }
}
