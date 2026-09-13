package com.yuyuframe.launcheragent.runtime.ipc;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Signal "prêt" vers le launcher Rust via un Named Event Win32 — canal
 * minimal (juste un booléen "je suis prêt", pas de payload), pas de port
 * réseau, pas de fichier à surveiller par polling. Le launcher crée l'event
 * (CreateEventW, voir ready_event.rs) avant de spawner Java et attend dessus
 * (WaitForSingleObject) ; ce côté-ci se contente de l'ouvrir par son nom
 * (OpenEventW) et de le signaler (SetEvent) — toute la synchronisation/
 * l'attente reste côté Rust.
 *
 * Même principe JNA que MumbleLinkBridge — appel
 * direct à kernel32.dll (jna.jar/jna-platform.jar, voir build.bat), pas de
 * nouvelle DLL Rust.
 *
 * Best-effort : toute erreur (event absent, JNA indisponible, OS non-Windows...)
 * est avalée et loggée — jamais bloquant pour le jeu. Le launcher garde de
 * toute façon le fallback stdout+fichier (voir orchestrator.rs/progress.rs)
 * si ce canal échoue.
 */
public final class ReadyEventSignal {
    private ReadyEventSignal() {}

    private static final int EVENT_MODIFY_STATE = 0x0002;

    private interface Kernel32 extends Library {
        Pointer OpenEventW(int dwDesiredAccess, boolean bInheritHandle, WString lpName);
        boolean SetEvent(Pointer hEvent);
        boolean CloseHandle(Pointer hObject);
    }

    private static volatile boolean sent = false;

    /** Sûr à appeler plusieurs fois (le hook TitleScreen.init() se redéclenche
     * à chaque retour au menu principal) — ne signale qu'une fois par JVM. */
    public static synchronized void signalOnce(String eventName) {
        // Log inconditionnel (avant tout early-return) — sans ça, un
        // eventName absent/vide est indiscernable d'un appel qui n'a jamais
        // eu lieu (deux causes racines très différentes) dans les logs.
        LauncherLog.ui(1, "[ReadyEventSignal] signalOnce appelé, sent=" + sent + ", eventName=" + eventName);
        if (sent || eventName == null || eventName.isEmpty()) return;
        sent = true;
        try {
            Kernel32 k32 = Native.load("kernel32", Kernel32.class);
            Pointer hEvent = k32.OpenEventW(EVENT_MODIFY_STATE, false, new WString(eventName));
            if (hEvent == null || Pointer.nativeValue(hEvent) == 0) {
                LauncherLog.warn("[ReadyEventSignal] OpenEventW a échoué pour " + eventName);
                return;
            }
            try {
                k32.SetEvent(hEvent);
                LauncherLog.info("[ReadyEventSignal] Signal envoyé (" + eventName + ")");
            } finally {
                k32.CloseHandle(hEvent);
            }
        } catch (Throwable t) {
            LauncherLog.warn("[ReadyEventSignal] échec : " + t);
        }
    }
}
