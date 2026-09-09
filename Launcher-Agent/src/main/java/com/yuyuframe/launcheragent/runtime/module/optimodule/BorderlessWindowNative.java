package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Fenêtre sans bordure (borderless windowed fullscreen) via l'API Win32 pure
 * (JNA — jna.jar/jna-platform.jar, PAS de nouvelle DLL Rust). Voir
 * BorderlessWindowModule pour le module/toggle, MixinBorderlessWindow189 pour
 * le point d'entrée (intercepte MinecraftClient.toggleFullscreen(), donc F11
 * reste la touche d'activation).
 *
 * On NE calcule PLUS aucune géométrie nous-mêmes (ni via java.awt.Toolkit, ni
 * via MonitorFromWindow/GetMonitorInfo, ni via Display.setDisplayMode/
 * setLocation — trois variantes essayées, chacune a fini par déraper d'une
 * façon différente : bordure qui reste, fenêtre plus grande que demandée,
 * débordement en bas d'écran). À la place : on retire juste la bordure
 * (SetWindowLong) puis {@code ShowWindow(SW_MAXIMIZE)} — une fenêtre SANS
 * WS_CAPTION/WS_THICKFRAME qu'on maximise remplit NATIVEMENT l'écran entier
 * (pas juste la work area hors barre des tâches, contrairement à une fenêtre
 * décorée normale), Windows s'occupe de tout le calcul. On se contente
 * ensuite de RELIRE le résultat (GetWindowRect) pour mettre à jour l'état
 * interne de Minecraft ({@link #applyMinecraftResize}) — jamais pour
 * redemander à LWJGL/Windows de redimensionner quoi que ce soit une seconde
 * fois. Symétriquement, sortir repasse par {@code ShowWindow(SW_RESTORE)},
 * qui restaure automatiquement la taille/position "normale" que Windows a
 * mémorisée lui-même en entrant dans l'état maximisé — pas besoin de la
 * sauvegarder/réappliquer nous-mêmes non plus.
 *
 * Ne touche JAMAIS au contexte OpenGL (aucun Display.destroy()/create()) —
 * juste la taille/position/bordure de la fenêtre + les champs de cache
 * width/height de Minecraft, donc aucune perte de texture.
 */
public final class BorderlessWindowNative {
    private BorderlessWindowNative() {}

    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1));
    private static final HWND HWND_NOTOPMOST = new HWND(Pointer.createConstant(-2));

    private static final int GWL_STYLE = -16;
    // WS_OVERLAPPEDWINDOW = WS_CAPTION|WS_SYSMENU|WS_THICKFRAME|WS_MINIMIZEBOX|WS_MAXIMIZEBOX
    private static final int WS_OVERLAPPEDWINDOW = User32.WS_CAPTION | User32.WS_SYSMENU
        | User32.WS_THICKFRAME | User32.WS_MINIMIZEBOX | User32.WS_MAXIMIZEBOX;

    private static boolean active;

    /**
     * HWND réel de la fenêtre LWJGL2 — lu directement depuis le champ interne
     * privé {@code Display.display_impl} (implémentation spécifique
     * plateforme, {@code WindowsDisplay} sous Windows) puis sa méthode
     * {@code getHwnd()} — même technique que BorderlessFullscreen (mod Forge
     * 1.8.9 open source, github.com/sky-is-winning/BorderlessFullscreen),
     * plus fiable qu'une énumération EnumWindows (jamais ambigu même si le
     * process a d'autres fenêtres visibles). Jamais mis en cache : résolu à
     * chaque appel, au cas où LWJGL recréerait la fenêtre en interne.
     */
    private static HWND ownWindow() {
        try {
            Class<?> displayClass = McReflect.rawClass("org.lwjgl.opengl.Display");
            if (displayClass == null) return null;
            Field implField = displayClass.getDeclaredField("display_impl");
            implField.setAccessible(true);
            Object displayImpl = implField.get(null);
            if (displayImpl == null) return null;

            Method getHwnd = displayImpl.getClass().getDeclaredMethod("getHwnd");
            getHwnd.setAccessible(true);
            long hwnd = (long) getHwnd.invoke(displayImpl);
            return new HWND(Pointer.createConstant(hwnd));
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] ownWindow: " + t);
            return null;
        }
    }

    // ── org.lwjgl.input.Mouse (API publique, réflexion) — grab/curseur, pour éviter que le mouse-look devienne erratique pendant la transition ──

    private static Class<?> mouseClass() { return McReflect.rawClass("org.lwjgl.input.Mouse"); }

    private static boolean mouseGrabbed() {
        try { return (boolean) McReflect.rawMethod(mouseClass(), "isGrabbed").invoke(null); }
        catch (Throwable t) { return false; }
    }

    private static void setMouseGrabbed(boolean grabbed) {
        try { McReflect.rawMethod(mouseClass(), "setGrabbed", boolean.class).invoke(null, grabbed); }
        catch (Throwable ignored) {}
    }

    private static void centerCursor(int width, int height) {
        try { McReflect.rawMethod(mouseClass(), "setCursorPosition", int.class, int.class).invoke(null, width / 2, height / 2); }
        catch (Throwable ignored) {}
    }

    /**
     * Appelle {@code MinecraftClient.onResolutionChanged(width, height)}
     * (Yarn method_2923, PRIVATE — vérifié par désassemblage bytecode réel du
     * 1.8.9.jar) : c'est LA vraie méthode vanilla de resize, PAS juste
     * resizeFramebuffer() — elle clampe width/height (Math.max(1, ...)),
     * notifie l'écran actuellement ouvert via un objet Window/ScaledResolution
     * (repositionne ses propres widgets à la nouvelle résolution), RECRÉE
     * LoadingScreenRenderer (dépend de la résolution), PUIS appelle
     * resizeFramebuffer() en dernier.
     */
    private static void applyMinecraftResize(int width, int height) {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Method onResolutionChanged = McReflect.method(mc.getClass(), "net/minecraft/client/MinecraftClient", "onResolutionChanged", int.class, int.class);
            if (onResolutionChanged != null) onResolutionChanged.invoke(mc, width, height);

            Method updateDisplay = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "updateDisplay");
            if (updateDisplay != null) updateDisplay.invoke(mc);
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] applyMinecraftResize: " + t);
        }
    }

    /**
     * Dance de focus barre des tâches — sans ça Windows peut laisser la
     * fenêtre TOPMOST fraîchement redimensionnée dans un état visuel
     * incohérent (mal repeinte) pendant un court instant. Même technique que
     * BorderlessFullscreen.
     */
    private static void refocusDance(HWND hwnd) {
        try {
            HWND shell = User32.INSTANCE.FindWindow("Shell_TrayWnd", null);
            if (shell != null) {
                User32.INSTANCE.SetForegroundWindow(shell);
                Thread.sleep(50);
            }
            User32.INSTANCE.SetForegroundWindow(hwnd);
        } catch (Throwable ignored) {}
    }

    /** Retire la bordure puis maximise — voir la javadoc de classe pour le pourquoi. */
    public static void enterBorderless() {
        boolean grabbed = mouseGrabbed();
        if (grabbed) setMouseGrabbed(false);
        try {
            HWND hwnd = ownWindow();
            if (hwnd == null) return;

            int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE);
            int newStyle = style & ~WS_OVERLAPPEDWINDOW;
            User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, newStyle);
            // Point de non-retour : la bordure est déjà enlevée sur la vraie
            // fenêtre — actif à partir d'ici même si la suite échoue, sinon
            // un F11 suivant ne ferait plus rien (exitBorderless() renvoie
            // immédiatement si !active) et l'utilisateur resterait bloqué.
            active = true;

            // Force Windows à recalculer la non-client area sur le NOUVEAU
            // style AVANT de maximiser (sinon ShowWindow peut se baser sur
            // d'anciennes métriques de bordure encore en cache).
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOZORDER | User32.SWP_FRAMECHANGED);
            User32.INSTANCE.ShowWindow(hwnd, User32.SW_MAXIMIZE);
            User32.INSTANCE.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                User32.SWP_NOMOVE | User32.SWP_NOSIZE);

            // On relit la géométrie RÉELLE que Windows a choisie — jamais
            // recalculée/réimposée par nous (voir javadoc de classe).
            RECT rect = new RECT();
            User32.INSTANCE.GetWindowRect(hwnd, rect);
            int width = rect.right - rect.left;
            int height = rect.bottom - rect.top;

            applyMinecraftResize(width, height);
            refocusDance(hwnd);
            centerCursor(width, height);
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] enterBorderless: " + t);
        } finally {
            if (grabbed) setMouseGrabbed(true);
        }
    }

    /** Restaure bordure + taille/position d'origine (mémorisées par Windows lui-même, voir javadoc de classe) — ne fait rien si on n'était pas actif. */
    public static void exitBorderless() {
        if (!active) return;
        boolean grabbed = mouseGrabbed();
        if (grabbed) setMouseGrabbed(false);
        try {
            HWND hwnd = ownWindow();
            if (hwnd != null) {
                // Sort de l'état "maximisé" AVANT de restaurer la bordure —
                // sinon la fenêtre resterait logiquement maximisée avec un
                // style redécoré, incohérent. Restaure aussi automatiquement
                // la taille/position "normale" d'avant (mémorisée par Windows
                // lui-même dans le WINDOWPLACEMENT de la fenêtre).
                User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE);

                int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE);
                style |= WS_OVERLAPPEDWINDOW;
                User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, style);

                User32.INSTANCE.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
                    User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_FRAMECHANGED);

                RECT rect = new RECT();
                User32.INSTANCE.GetWindowRect(hwnd, rect);
                int width = rect.right - rect.left;
                int height = rect.bottom - rect.top;

                applyMinecraftResize(width, height);
                centerCursor(width, height);
            }
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] exitBorderless: " + t);
        } finally {
            active = false;
            if (grabbed) setMouseGrabbed(true);
        }
    }

    public static boolean isActive() { return active; }
}
