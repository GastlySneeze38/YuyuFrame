package com.yuyuframe.launcheragent.runtime.ipc;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Signal « prêt » vers le launcher : le menu principal est atteint. Il part
 * par le canal du jeu ({@link NativeLink}, message {@code ready}) — le tube
 * nommé qui a remplacé l'événement Windows ouvert par son nom.
 *
 * <p>Best-effort : sans canal (jeu lancé hors launcher, DLL absente), rien ne
 * part et rien ne bloque. Le launcher garde de toute façon le repli par la
 * sortie du jeu et le journal de l'agent (marqueur {@code [YUYUFRAME_READY]},
 * voir orchestrator.rs/progress.rs).
 */
public final class ReadyEventSignal {
    private ReadyEventSignal() {}

    private static volatile boolean sent = false;

    /**
     * Sûr à appeler plusieurs fois (le hook TitleScreen.init() se redéclenche
     * à chaque retour au menu principal) — ne signale qu'une fois par JVM.
     *
     * @param unused l'ancien nom de l'événement Windows ; le canal n'a besoin
     *               de rien, le paramètre reste pour les appelants de chaque version
     */
    public static synchronized void signalOnce(String unused) {
        if (sent) return;
        sent = true;
        try {
            if (NativeLink.notify("ready")) {
                LauncherLog.info("[ReadyEventSignal] Signal envoyé par le canal du jeu");
            } else {
                LauncherLog.info("[ReadyEventSignal] Pas de canal vers le launcher — repli sur le marqueur du journal");
            }
        } catch (Throwable t) {
            LauncherLog.warn("[ReadyEventSignal] échec : " + t);
        }
    }
}
