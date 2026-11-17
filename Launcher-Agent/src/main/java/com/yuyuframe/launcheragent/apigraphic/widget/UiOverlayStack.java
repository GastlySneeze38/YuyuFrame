package com.yuyuframe.launcheragent.apigraphic.widget;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ArrayList;
import java.util.List;

/**
 * La pile des éléments flottants d'un écran ({@link UiOverlay}). Une seule par
 * écran ; le dernier ouvert est au-dessus. Elle règle seule :
 * <ul>
 *   <li>l'ordre de dessin, et le voile sous une bloquante ;</li>
 *   <li>à qui va un clic : le plus haut élément qui le couvre le prend, une
 *       bloquante prend tout, rien ne traverse ;</li>
 *   <li>Échap : ferme le plus haut (après avoir rendu le focus d'un champ) ;</li>
 *   <li>l'apparition et la disparition, communes à tous.</li>
 * </ul>
 *
 * <p>Pendant sa sortie, un élément est encore dessiné et continue de couvrir
 * sa surface (un clic qui vient de choisir une ligne d'une liste ne doit pas
 * retomber sur le réglage caché dessous), mais il ne réagit plus.
 *
 * <p>{@link #active()} est la pile de l'écran en cours : un widget qui ouvre
 * un panneau flottant (liste déroulante) la trouve là sans connaître l'écran.
 */
public final class UiOverlayStack {

    private static final class Entry {
        final UiOverlay overlay;
        final long openedAt = System.nanoTime();
        long closedAt;
        /** Où en était l'apparition quand la fermeture a été demandée. */
        float appearAtClose;

        Entry(UiOverlay overlay) { this.overlay = overlay; }

        boolean closing() { return closedAt != 0L; }

        float appear(long now) {
            if (!closing()) {
                float d = Math.max(0.01f, overlay.enterSeconds());
                return Math.min(1f, (now - openedAt) / 1e9f / d);
            }
            float d = Math.max(0.01f, overlay.exitSeconds());
            return Math.max(0f, appearAtClose - (now - closedAt) / 1e9f / d);
        }

        boolean covers(double mx, double my) {
            return overlay.modal() || overlay.contains(mx, my);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private static final UiOverlayStack NONE = new UiOverlayStack();
    private static UiOverlayStack active = NONE;

    /** La pile de l'écran en cours (une pile vide hors de nos écrans). */
    public static UiOverlayStack active() { return active; }

    /** Désigne cette pile comme celle de l'écran en cours — appelé par l'écran à chaque image. */
    public void makeActive() { active = this; }

    // ── Ouvrir, fermer ───────────────────────────────────────────────────

    public void open(UiOverlay overlay) {
        if (overlay == null || isOpen(overlay)) return;
        // Rouvert pendant sa sortie : on repart d'une entrée neuve.
        entries.removeIf(e -> e.overlay == overlay);
        entries.add(new Entry(overlay));
    }

    public void close(UiOverlay overlay) {
        long now = System.nanoTime();
        for (Entry e : entries) {
            if (e.overlay == overlay && !e.closing()) {
                e.appearAtClose = e.appear(now);
                e.closedAt = now;
                try {
                    overlay.onClosed();
                } catch (Throwable t) {
                    LauncherLog.err("[UiOverlayStack] onClosed: " + t);
                }
            }
        }
    }

    public void closeAll() {
        for (Entry e : new ArrayList<>(entries)) close(e.overlay);
    }

    /** Ouvert et pas en train de se fermer. */
    public boolean isOpen(UiOverlay overlay) {
        for (Entry e : entries) if (e.overlay == overlay && !e.closing()) return true;
        return false;
    }

    /** Une bloquante est là (même en train de partir) : ce qui est dessous ne doit ni réagir ni défiler. */
    public boolean blocksInput() {
        for (Entry e : entries) if (e.overlay.modal()) return true;
        return false;
    }

    /** Un élément couvre ce point : le clic est pour lui, pas pour ce qu'il cache. */
    public boolean capturesPoint(double mx, double my) {
        for (Entry e : entries) if (e.covers(mx, my)) return true;
        return justDismissed();
    }

    /** Un clic vient de refermer un élément flottant : il ne doit rien atteindre d'autre. */
    public boolean justDismissed() {
        return lastDismissNanos != 0L && System.nanoTime() - lastDismissNanos < DISMISS_MEMORY_NANOS;
    }

    /** Les widgets qui ont la main au clavier (Tab) : ceux de la bloquante du dessus, sinon {@code null}. */
    public List<UiWidget> focusScope() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.closing() && e.overlay.modal()) return e.overlay.widgets();
        }
        return null;
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    // ── Dessin ───────────────────────────────────────────────────────────

    /**
     * Dessine toute la pile, du bas vers le haut. Sous une bloquante, les
     * éléments inférieurs reçoivent une souris hors de l'écran : rien n'y
     * s'allume au survol.
     */
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        long now = System.nanoTime();
        entries.removeIf(e -> e.closing() && e.appear(now) <= 0f);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean covered = false;
            for (int j = i + 1; j < entries.size(); j++) {
                if (entries.get(j).overlay.modal()) { covered = true; break; }
            }
            double mx = covered ? -1e6 : mouseX, my = covered ? -1e6 : mouseY;
            float appear = e.appear(now);
            int depth = renderer.layerDepth();
            try {
                UiColor scrim = e.overlay.scrim();
                if (scrim != null) {
                    renderer.drawRoundedRect(0, 0, vpWidth, vpHeight, 0f,
                        scrim.multiplyAlpha(Math.min(1f, appear * 2f)), vpWidth, vpHeight);
                }
                e.overlay.draw(renderer, mx, my, vpWidth, vpHeight, appear);
            } catch (Throwable t) {
                LauncherLog.err("[UiOverlayStack] draw " + e.overlay.getClass().getSimpleName() + ": " + t);
            } finally {
                while (renderer.layerDepth() > depth) renderer.popLayer();
            }
        }
    }

    /** L'infobulle du widget survolé dans l'élément du dessus, ou {@code null}. */
    public String tooltipAt(double mx, double my) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.closing()) continue;
            if (!e.covers(mx, my)) continue;
            for (UiWidget w : e.overlay.widgets()) {
                if (w.tooltip != null && w.contains(mx, my)) return w.tooltip;
            }
            return null;
        }
        return null;
    }

    // ── Entrées ──────────────────────────────────────────────────────────

    /**
     * Sondage continu, de l'élément du dessus vers le bas ; une bloquante
     * arrête la descente. Appelé avant celui de l'écran.
     */
    public void poll(UiInputPoller input) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.closing()) continue;
            try {
                e.overlay.poll(input);
                for (UiWidget w : new ArrayList<>(e.overlay.widgets())) w.pollContinuous(input);
            } catch (Throwable t) {
                LauncherLog.err("[UiOverlayStack] poll " + e.overlay.getClass().getSimpleName() + ": " + t);
            }
            if (e.overlay.modal()) break;
        }
    }

    /**
     * Un clic : {@code true} s'il a été pris par la pile (rien dessous ne doit
     * le recevoir). Les flottantes qu'il manque se ferment au passage.
     */
    public boolean click(double mx, double my, int button, boolean doubleClick) {
        boolean dismissed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (i >= entries.size()) continue;
            Entry e = entries.get(i);
            if (e.closing()) {
                if (e.covers(mx, my)) return true;
                continue;
            }
            if (!e.covers(mx, my)) {
                if (e.overlay.closeOnOutsideClick()) {
                    close(e.overlay);
                    dismissed = true;
                }
                continue;
            }
            List<UiWidget> widgets = e.overlay.widgets();
            UiHitTest.invalidate();
            UiWidget hit = UiHitTest.find(widgets, mx, my);
            for (UiWidget w : widgets) {
                if (w instanceof UiFocusable && w != hit && ((UiFocusable) w).focused()) ((UiFocusable) w).setFocused(false);
            }
            try {
                if (hit != null) hit.onClick(button, doubleClick);
                else e.overlay.click(mx, my, button, doubleClick);
            } catch (Throwable t) {
                LauncherLog.err("[UiOverlayStack] click " + e.overlay.getClass().getSimpleName() + ": " + t);
            }
            return true;
        }
        if (dismissed) {
            // Le clic qui referme un élément flottant ne fait rien d'autre :
            // il ne doit pas, en plus, ouvrir ce qu'il y avait dessous.
            lastDismissNanos = System.nanoTime();
            return true;
        }
        return false;
    }

    /** Dernier clic qui a refermé un élément flottant (voir {@link #capturesPoint}). */
    private long lastDismissNanos;
    /** Le même clic peut arriver encore par le sondage d'une liste qui défile, juste après. */
    private static final long DISMISS_MEMORY_NANOS = 150_000_000L;

    /**
     * Échap : rend d'abord le focus d'un champ de l'élément du dessus, sinon le
     * ferme. {@code true} si la touche a été prise.
     */
    public boolean escape() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.closing()) continue;
            for (UiWidget w : e.overlay.widgets()) {
                if (w instanceof UiFocusable && ((UiFocusable) w).focused()) {
                    ((UiFocusable) w).setFocused(false);
                    return true;
                }
            }
            if (e.overlay.closeOnEscape()) {
                close(e.overlay);
                return true;
            }
            // Une bloquante qui refuse Échap le garde quand même pour elle.
            if (e.overlay.modal()) return true;
        }
        return false;
    }
}
