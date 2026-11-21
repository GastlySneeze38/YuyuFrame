package com.yuyuframe.launcheragent.runtime.ui.shortcuts;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Les boutons de l'écran du jeu sous les raccourcis : ceux de Minecraft et
 * ceux des autres mods, lus par {@link AccessPoint#SCREEN_WIDGETS}.
 *
 * <p>Trois usages : savoir où il y a de la place (un raccourci ne se pose
 * jamais sur un bouton), trouver la rangée d'icônes du centre pour y glisser
 * les nôtres, et rendre les boutons inactifs le temps de l'édition.
 *
 * <p>Tout est en pixels de GUI, y vers le bas, comme le jeu.
 */
public final class ScreenButtons {
    private ScreenButtons() {}

    /** Accès aux boutons — remplacé par l'aperçu de l'interface, qui n'a pas de jeu. */
    public interface Source {
        List<Object> widgets(Object screen);
        /** {@code x, y, largeur, hauteur, visible, actif}, ou {@code null}. */
        int[] bounds(Object widget);
        void setX(Object widget, int x);
        void setActive(Object widget, boolean active);
    }

    /** L'aperçu de l'interface donne ses faux boutons ; le jeu garde la source par défaut. */
    public static void useSource(Source replacement) {
        source = replacement;
    }

    private static Source source = new Source() {
        @Override
        @SuppressWarnings("unchecked")
        public List<Object> widgets(Object screen) {
            Object list = AccessorRegistry.get(AccessPoint.SCREEN_WIDGETS, screen);
            return list instanceof List ? (List<Object>) list : Collections.emptyList();
        }

        @Override
        public int[] bounds(Object widget) {
            Object b = AccessorRegistry.get(AccessPoint.WIDGET_BOUNDS, widget);
            return b instanceof int[] ? (int[]) b : null;
        }

        @Override
        public void setX(Object widget, int x) {
            AccessorRegistry.invoke(AccessPoint.WIDGET_SET_X, widget, Integer.valueOf(x));
        }

        @Override
        public void setActive(Object widget, boolean active) {
            AccessorRegistry.invoke(AccessPoint.WIDGET_SET_ACTIVE, widget, Boolean.valueOf(active));
        }
    };

    /** Un bouton visible : sa poignée et son rectangle, au moment de la lecture. */
    static final class Button {
        final Object handle;
        final int x, y, w, h;
        final boolean active;

        Button(Object handle, int[] b) {
            this.handle = handle;
            this.x = b[0];
            this.y = b[1];
            this.w = b[2];
            this.h = b[3];
            this.active = b[5] != 0;
        }
    }

    /** La rangée d'icônes du centre, telle qu'elle était avant qu'on la décale. */
    static final class Row {
        /** Les icônes du jeu (ou d'autres mods), de gauche à droite. */
        final List<Object> members = new ArrayList<>();
        /** Leur {@code x} d'origine, dans le même ordre. */
        final List<Integer> originX = new ArrayList<>();
        int y, size, gap;
        /** Bord gauche et bord droit de la rangée d'origine. */
        int left, right;
        /** Rangée créée par nous, faute d'en trouver une : aucune icône du jeu à décaler. */
        boolean own;
    }

    /** Pour chaque icône déplacée : {x d'origine, dernier x posé par nous}. */
    private static final Map<Object, int[]> moved = new IdentityHashMap<>();
    /** État {@code active} d'origine des boutons rendus inactifs pour l'édition. */
    private static final Map<Object, Boolean> disabled = new IdentityHashMap<>();
    private static Object lastScreen;

    /** Les boutons visibles de l'écran. Oublie ce qu'on savait de l'écran précédent. */
    static List<Button> read(Object screen) {
        if (screen != lastScreen) {
            lastScreen = screen;
            moved.clear();
            // Un écran quitté en pleine édition : ses boutons retrouvent leur état, au cas où il reviendrait.
            setAllInactive(null, false);
        }
        List<Button> out = new ArrayList<>();
        for (Object widget : source.widgets(screen)) {
            int[] b = source.bounds(widget);
            if (b == null || b.length < 6 || b[4] == 0 || b[2] <= 0 || b[3] <= 0) continue;
            out.add(new Button(widget, b));
        }
        return out;
    }

    /**
     * La rangée d'icônes : au moins deux boutons carrés (16 à 24 pixels) de
     * même taille, sur la même ligne ET côte à côte (écart d'au plus la
     * moitié d'une icône). En 26.1.2, les icônes de langue et d'accessibilité
     * de l'écran-titre sont sur une même ligne mais aux deux bouts de l'écran
     * (retour du 2026-11-21) : ce n'est pas une rangée.
     *
     * <p>Leur {@code x} est lu avant nos décalages — un bouton que le jeu a
     * replacé (fenêtre redimensionnée) reprend sa nouvelle place comme
     * origine. Sans rangée, la nôtre ({@code ownCount} icônes) se pose sous
     * les boutons qu'elle croiserait au milieu de l'écran, sinon au-dessus.
     */
    static Row findRow(List<Button> buttons, float screenW, float screenH, int ownCount) {
        Map<Integer, List<Button>> byY = new java.util.TreeMap<>();
        for (Button b : buttons) {
            if (b.w == b.h && b.w >= 16 && b.w <= 24) byY.computeIfAbsent(b.y, k -> new ArrayList<>()).add(b);
        }
        List<Button> best = null;
        for (List<Button> line : byY.values()) {
            line.sort((a, b) -> Integer.compare(a.x, b.x));
            List<Button> cluster = new ArrayList<>();
            for (Button b : line) {
                Button prev = cluster.isEmpty() ? null : cluster.get(cluster.size() - 1);
                if (prev != null && (b.w != prev.w || b.x - (prev.x + prev.w) > Math.max(8, prev.w / 2))) {
                    if (cluster.size() >= 2 && (best == null || cluster.size() > best.size())) best = cluster;
                    cluster = new ArrayList<>();
                }
                cluster.add(b);
            }
            if (cluster.size() >= 2 && (best == null || cluster.size() > best.size())) best = cluster;
        }
        Row row = new Row();
        if (best == null) {
            row.own = true;
            row.size = 20;
            row.gap = 4;
            row.y = ownRowY(buttons, screenW, screenH, ownCount, row.size, row.gap);
            row.left = row.right = Math.round(screenW / 2f);
            return row;
        }
        row.y = best.get(0).y;
        row.size = best.get(0).w;
        int gapSum = 0;
        for (int i = 0; i < best.size(); i++) {
            Button b = best.get(i);
            int[] m = moved.get(b.handle);
            int origin = m != null && m[1] == b.x ? m[0] : b.x;
            row.members.add(b.handle);
            row.originX.add(origin);
            if (i > 0) gapSum += origin - row.originX.get(i - 1) - row.size;
        }
        row.gap = Math.max(2, gapSum / (best.size() - 1));
        row.left = row.originX.get(0);
        row.right = row.originX.get(row.originX.size() - 1) + row.size;
        return row;
    }

    /**
     * Le haut de notre rangée quand l'écran n'en a pas : juste sous le plus
     * bas des boutons qu'elle croiserait (même bande horizontale, centrée),
     * pour ne mordre sur aucun — en 1.8.9, l'espace entre Realms et
     * « Options » est trop court pour elle. Sans place en dessous, juste
     * au-dessus du plus haut de ces boutons.
     */
    private static int ownRowY(List<Button> buttons, float screenW, float screenH, int count, int size, int gap) {
        float total = Math.max(1, count) * size + Math.max(0, count - 1) * gap;
        float left = (screenW - total) / 2f - 2, right = (screenW + total) / 2f + 2;
        int bottom = -1, top = Integer.MAX_VALUE;
        for (Button b : buttons) {
            if (b.x + b.w <= left || b.x >= right) continue;
            bottom = Math.max(bottom, b.y + b.h);
            top = Math.min(top, b.y);
        }
        if (bottom < 0) return Math.round(screenH * 0.7f);
        if (bottom + 4 + size <= screenH - 2) return bottom + 4;
        if (top - 4 - size >= 2) return top - 4 - size;
        return Math.max(2, Math.round(screenH - size - 2));
    }

    /** Décale les icônes de la rangée de {@code shift} pixels vers la gauche (0 = place d'origine). */
    static void shiftRow(Row row, int shift) {
        for (int i = 0; i < row.members.size(); i++) {
            Object handle = row.members.get(i);
            int origin = row.originX.get(i);
            int target = origin - shift;
            int[] m = moved.get(handle);
            if (m == null) {
                m = new int[]{ origin, origin };
                moved.put(handle, m);
            }
            m[0] = origin;
            if (m[1] != target) {
                source.setX(handle, target);
                m[1] = target;
            }
        }
    }

    /** Rend tous les boutons inactifs (édition, liste dépliée) ou leur rend leur état d'origine. */
    static void setAllInactive(List<Button> buttons, boolean inactive) {
        if (inactive) {
            for (Button b : buttons) {
                if (!disabled.containsKey(b.handle)) {
                    disabled.put(b.handle, b.active);
                    if (b.active) source.setActive(b.handle, false);
                }
            }
        } else if (!disabled.isEmpty()) {
            for (Map.Entry<Object, Boolean> e : disabled.entrySet()) {
                if (e.getValue()) source.setActive(e.getKey(), true);
            }
            disabled.clear();
        }
    }
}
