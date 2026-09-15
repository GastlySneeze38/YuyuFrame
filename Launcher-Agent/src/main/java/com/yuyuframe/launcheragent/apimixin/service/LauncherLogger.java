package com.yuyuframe.launcheragent.apimixin.service;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;

/** Logger minimaliste pour Mixin — redirige vers System.out/err. */
public class LauncherLogger implements ILogger {

    private final String id;

    public LauncherLogger(String id) {
        this.id = id;
    }

    @Override public String getId()   { return id; }
    @Override public String getType() { return "System"; }

    @Override public void catching(Level lvl, Throwable t) { log(lvl, "exception attrapée par Mixin", t); }
    @Override public void catching(Throwable t)            { log(Level.ERROR, "exception attrapée par Mixin", t); }

    @Override public void debug(String msg, Object... args) { log(Level.DEBUG, msg, args); }
    @Override public void debug(String msg, Throwable t)    { log(Level.DEBUG, msg, t); }

    @Override public void info(String msg, Object... args) { log(Level.INFO, msg, args); }
    @Override public void info(String msg, Throwable t)    { log(Level.INFO, msg, t); }

    @Override public void warn(String msg, Object... args) { log(Level.WARN, msg, args); }
    @Override public void warn(String msg, Throwable t)    { log(Level.WARN, msg, t); }

    @Override public void error(String msg, Object... args) { log(Level.ERROR, msg, args); }
    @Override public void error(String msg, Throwable t)    { log(Level.ERROR, msg, t); }

    @Override public void fatal(String msg, Object... args) { log(Level.FATAL, msg, args); }
    @Override public void fatal(String msg, Throwable t)    { log(Level.FATAL, msg, t); }

    @Override public void trace(String msg, Object... args) {}
    @Override public void trace(String msg, Throwable t)    {}

    @Override
    public void log(Level lvl, String msg, Object... args) {
        String formatted = args.length == 0 ? msg : String.format(msg.replace("{}", "%s"), (Object[]) args);
        // ⚠️ COMPARAISON INVERSÉE CORRIGÉE (2026-08-25, §12) — dans
        // org.spongepowered.asm.logging.Level, le plus GRAVE a l'ordinal le
        // plus BAS : FATAL=0, ERROR=1, WARN=2, INFO=3, DEBUG=4, TRACE=5
        // (vérifié au bytecode sur le mixin.jar embarqué). Le test précédent,
        // "ordinal() >= WARN.ordinal()", sélectionnait donc WARN/INFO/DEBUG/
        // TRACE et EXCLUAIT ERROR et FATAL — exactement l'inverse de
        // l'intention. Conséquence : depuis la création de cette classe, aucun
        // ERROR ni FATAL de Sponge Mixin n'a jamais atteint launcher-agent.log
        // (0 occurrence de "[Mixin/ERROR]" sur ~29 jours de logs, contre 607
        // WARN et 3576 INFO), et ces messages partaient sur System.out au lieu
        // de System.err. C'est ce qui a masqué le "Error loading companion
        // plugin class [...]" expliquant pourquoi LauncherMixinConfigPlugin
        // était instancié puis jamais appelé.
        java.io.PrintStream out = (lvl.ordinal() <= Level.WARN.ordinal()) ? System.err : System.out;
        out.println("[Mixin/" + lvl + "] [" + id + "] " + formatted);
        // Miroir vers notre fichier de log persistant — le flux System.out/err
        // de ce logger n'atteint PAS launcher-agent.log (voir toFile() de
        // LauncherLog, indépendant de la capture stdout du launcher Rust,
        // jugée peu fiable).
        if (lvl.ordinal() <= Level.WARN.ordinal()) {
            com.yuyuframe.launcheragent.base.log.LauncherLog.err("[Mixin/" + lvl + "] [" + id + "] " + formatted);
        }
    }

    /**
     * Message + pile COMPLÈTE (causes comprises) par {@code LauncherLog.err},
     * quel que soit le niveau Mixin : une exception jointe est toujours une
     * cause qu'on veut lire. Avant (2026-09-15), seul {@code getStackTrace()}
     * de l'exception de tête partait au fichier — les « Caused by », où Mixin
     * range la vraie raison d'un échec d'application, étaient perdus.
     */
    @Override
    public void log(Level lvl, String msg, Throwable t) {
        String formatted = "[Mixin/" + lvl + "] [" + id + "] " + msg;
        if (t == null) {
            log(lvl, msg);
            return;
        }
        com.yuyuframe.launcheragent.base.log.LauncherLog.err(formatted, t);
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        log(Level.ERROR, "exception relancée par Mixin", t);
        return t;
    }
}
