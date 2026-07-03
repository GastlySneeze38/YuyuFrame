package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser.HMONITOR;
import com.sun.jna.platform.win32.WinUser.MONITORINFO;
import com.sun.jna.ptr.IntByReference;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

/**
 * Fenêtre sans bordure (borderless windowed fullscreen) via l'API Win32 pure
 * (JNA — jna.jar/jna-platform.jar, PAS de nouvelle DLL Rust : contrairement à
 * content_core.dll/rust_core.dll, aucun code natif à écrire/compiler nous-mêmes
 * ici, JNA embarque déjà son propre pont natif générique). Voir
 * BorderlessWindowModule pour le module/toggle, MixinBorderlessWindow189 pour
 * le point d'entrée (intercepte MinecraftClient.toggleFullscreen(), donc F11
 * reste la touche d'activation).
 *
 * Manipule directement le HWND réel de la fenêtre LWJGL2 (style + taille/
 * position), SANS jamais toucher au contexte OpenGL (aucun Display.destroy()/
 * create() ici, aucune perte de texture) — contrairement au vrai plein écran
 * natif (Display.setFullscreen), qui change de mode vidéo et cause les soucis
 * remontés par l'utilisateur (alt-tab instable, écran noir, minimisation) :
 * ici la fenêtre reste une fenêtre normale aux yeux de Windows (alt-tab/
 * changement d'appli instantané, stabilité d'une fenêtre classique), juste
 * sans cadre ni bordure et redimensionnée/repositionnée sur tout l'écran.
 *
 * IMPORTANT — pourquoi on appelle AUSSI Display.setDisplayMode() (LWJGL2, via
 * réflexion, même technique que UiInputPollerLegacy) : un SetWindowPos Win32
 * pur redimensionne bien la fenêtre RÉELLE, mais LWJGL2 ne surveille pas les
 * resize externes sur une fenêtre non-resizable (Display.setResizable() jamais
 * appelé par vanilla 1.8.9) — sans passer par son API, Display.getWidth()/
 * getHeight() (donc le viewport GL et Minecraft.resize()) restent bloqués sur
 * l'ANCIENNE taille (symptôme observé : bordure enlevée mais zone de rendu pas
 * redimensionnée), et LWJGL peut même re-forcer la fenêtre à son ancienne
 * taille/position au prochain Display.update(). setDisplayMode() est le point
 * d'entrée normal que vanilla utilise déjà pour tout changement de résolution
 * — il redimensionne la fenêtre ET met à jour l'état interne de LWJGL/déclenche
 * le resize de Minecraft, sans jamais recréer le contexte GL (aucune perte de
 * texture, juste un changement de taille de la surface de rendu).
 */
public final class BorderlessWindowNative {
    private BorderlessWindowNative() {}

    private static HWND cachedHwnd;
    private static int savedStyle;
    private static RECT savedRect;
    private static Object savedDisplayMode;
    private static boolean active;

    private static Class<?> displayClass() { return McReflect.rawClass("org.lwjgl.opengl.Display"); }
    private static Class<?> displayModeClass() { return McReflect.rawClass("org.lwjgl.opengl.DisplayMode"); }

    /** {@code Display.setDisplayMode(new DisplayMode(width, height))} — voir la javadoc de classe pour pourquoi c'est nécessaire en plus de SetWindowPos. */
    private static void setLwjglDisplayMode(int width, int height) throws Exception {
        Class<?> displayClass = displayClass(), modeClass = displayModeClass();
        if (displayClass == null || modeClass == null) return;
        Object mode = modeClass.getConstructor(int.class, int.class).newInstance(width, height);
        McReflect.rawMethod(displayClass, "setDisplayMode", modeClass).invoke(null, mode);
    }

    private static Object currentLwjglDisplayMode() throws Exception {
        Class<?> displayClass = displayClass();
        if (displayClass == null) return null;
        return McReflect.rawMethod(displayClass, "getDisplayMode").invoke(null);
    }

    /**
     * Résout le HWND de la fenêtre du jeu par énumération des fenêtres visibles
     * du process courant (EnumWindows + GetWindowThreadProcessId) — PAS via les
     * internes LWJGL2 (Display.getImplementation() est privé/spécifique
     * plateforme, non garanti stable d'une version LWJGL à l'autre). Premier
     * match retenu et mis en cache : Minecraft n'a qu'une seule fenêtre top-level
     * visible.
     */
    private static HWND ownWindow() {
        if (cachedHwnd != null) return cachedHwnd;
        try {
            int pid = Kernel32.INSTANCE.GetCurrentProcessId();
            HWND[] found = new HWND[1];
            User32.INSTANCE.EnumWindows((hWnd, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, owner);
                if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hWnd)) {
                    found[0] = hWnd;
                    return false; // arrête l'énumération
                }
                return true;
            }, null);
            cachedHwnd = found[0];
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] ownWindow: " + t);
        }
        return cachedHwnd;
    }

    /** Retire la bordure et redimensionne/repositionne sur le moniteur courant (celui de la fenêtre, pas forcément le principal). */
    public static void enterBorderless() {
        try {
            HWND hwnd = ownWindow();
            if (hwnd == null) return;

            savedStyle = User32.INSTANCE.GetWindowLong(hwnd, User32.GWL_STYLE);
            savedRect = new RECT();
            User32.INSTANCE.GetWindowRect(hwnd, savedRect);
            try { savedDisplayMode = currentLwjglDisplayMode(); } catch (Throwable ignored) {}

            HMONITOR monitor = User32.INSTANCE.MonitorFromWindow(hwnd, User32.MONITOR_DEFAULTTONEAREST);
            MONITORINFO info = new MONITORINFO();
            info.cbSize = info.size();
            User32.INSTANCE.GetMonitorInfo(monitor, info);
            RECT r = info.rcMonitor;
            int width = r.right - r.left, height = r.bottom - r.top;

            int newStyle = savedStyle & ~(User32.WS_CAPTION | User32.WS_THICKFRAME
                | User32.WS_MINIMIZEBOX | User32.WS_MAXIMIZEBOX | User32.WS_SYSMENU);
            User32.INSTANCE.SetWindowLong(hwnd, User32.GWL_STYLE, newStyle);
            // Point de non-retour : la bordure est déjà enlevée sur la vraie
            // fenêtre — actif à partir d'ici même si la suite (resize LWJGL,
            // repositionnement) échoue, sinon un F11 suivant ne ferait plus
            // rien (exitBorderless() renvoie immédiatement si !active) et
            // l'utilisateur resterait bloqué sans bordure ET sans pouvoir
            // revenir en arrière (bug remonté par l'utilisateur).
            active = true;

            // Redimensionne RÉELLEMENT la surface de rendu (voir javadoc de
            // classe) — sans ça la fenêtre change de taille côté Windows mais
            // Minecraft/LWJGL continuent de dessiner à l'ancienne taille.
            setLwjglDisplayMode(width, height);

            User32.INSTANCE.SetWindowPos(hwnd, null, r.left, r.top, width, height,
                User32.SWP_NOZORDER | User32.SWP_NOACTIVATE | User32.SWP_FRAMECHANGED);
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] enterBorderless: " + t);
        }
    }

    /** Restaure exactement le style/la position/la taille/le mode d'affichage sauvegardés par {@link #enterBorderless()} — ne fait rien si on n'était pas actif. */
    public static void exitBorderless() {
        if (!active) return;
        try {
            HWND hwnd = ownWindow();
            if (hwnd != null && savedRect != null) {
                User32.INSTANCE.SetWindowLong(hwnd, User32.GWL_STYLE, savedStyle);
                if (savedDisplayMode != null) {
                    try {
                        McReflect.rawMethod(displayClass(), "setDisplayMode", displayModeClass()).invoke(null, savedDisplayMode);
                    } catch (Throwable t) {
                        LauncherLog.err("[BorderlessWindowNative] restore setDisplayMode: " + t);
                    }
                }
                User32.INSTANCE.SetWindowPos(hwnd, null, savedRect.left, savedRect.top,
                    savedRect.right - savedRect.left, savedRect.bottom - savedRect.top,
                    User32.SWP_NOZORDER | User32.SWP_NOACTIVATE | User32.SWP_FRAMECHANGED);
            }
        } catch (Throwable t) {
            LauncherLog.err("[BorderlessWindowNative] exitBorderless: " + t);
        } finally {
            active = false;
        }
    }

    public static boolean isActive() { return active; }
}
