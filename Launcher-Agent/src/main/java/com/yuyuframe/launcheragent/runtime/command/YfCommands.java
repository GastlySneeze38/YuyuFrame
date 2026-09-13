package com.yuyuframe.launcheragent.runtime.command;

import com.yuyuframe.launcheragent.agent.LauncherAgent;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.GlobalUiRenderBridge261;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;

import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Commandes intégrées {@code /yf ...} — voir ROADMAP-agent.md Phase 4.5.
 * Enregistrées par {@link ClientCommandRegistry#bootstrap()}.
 *
 * <p>Nettoyage du 2026-09-13 : retrait de {@code /yf perf} et {@code /yf debug}
 * (bascule d'un indicateur que rien ne lisait), {@code /shader-reload} (aucun
 * shader branché) et des 7 preuves de mécanisme {@code /yf shaderpoc},
 * {@code blurpoc}, {@code taffypoc}, {@code richtextpoc}, {@code batchpoc},
 * {@code blendpoc}, {@code particlepoc} (26.1.2 seulement, inutiles au joueur),
 * avec leur code de test dans le moteur.
 *
 * 26.1.2 UNIQUEMENT pour l'instant (comme tout ce chantier) — le pont utilisé
 * ({@code apimixin.v26_1.core.GlobalUiRenderBridge261}) est spécifique à ce
 * bracket ; le hook d'interception ({@code ChatSendMixin261}) aussi.
 *
 * Feedback utilisateur : route par {@link LauncherLog} (visible dans
 * launcher-agent.log) plutôt qu'un écho local dans le chat — construire un
 * canal de message de chat purement client (sans passer par le serveur)
 * aurait exigé la même méthode privée {@code ChatComponent.addMessage} déjà
 * écartée pour {@code ChatEnhancementsModule} (noms de type jamais vérifiés
 * par javap, voir audit ROADMAP-agent.md §3.3) — pas de raison de prendre ce
 * risque ici. À revoir si un vrai système de toast apparaît (Phase 5.6).
 */
final class YfCommands {
    private YfCommands() {}

    private static boolean safeModeActive = false;
    private static final Set<String> safeModeSavedEnabled = new HashSet<>();

    static void registerAll() {
        ClientCommandRegistry.register(reopenMenu());
        ClientCommandRegistry.register(safeMode());
        ClientCommandRegistry.register(report());
        ClientCommandRegistry.register(resetHud());
        ClientCommandRegistry.register(version());
        ClientCommandRegistry.register(modules());
        ClientCommandRegistry.register(reloadConfig());
    }

    private static ClientCommand reopenMenu() {
        return new ClientCommand() {
            public String name() { return "yf"; }
            public String description() { return "Réouvre l'écran principal du launcher"; }
            public void execute(String[] args) {
                try {
                    Object mc = GlobalUiRenderBridge261.getMcInstance();
                    if (mc == null) return;
                    GlobalUiRenderBridge261.setScreen(mc, new UiMainMenuScreen(null));
                } catch (Throwable t) {
                    LauncherLog.err("[YfCommands] /yf: " + t);
                }
            }
        };
    }

    private static ClientCommand safeMode() {
        return new ClientCommand() {
            public String name() { return "yf safe-mode"; }
            public String description() { return "activate|deactivate — désactive/réactive tous les modules d'un coup, sans toucher la config sauvegardée"; }
            public void execute(String[] args) {
                if (args.length == 0) {
                    LauncherLog.info("[YfCommands] Usage : /yf safe-mode activate|deactivate");
                    return;
                }
                if ("activate".equals(args[0])) {
                    if (safeModeActive) return;
                    safeModeActive = true;
                    safeModeSavedEnabled.clear();
                    for (LauncherModule m : ModuleRegistry.all()) {
                        if (m.isEnabled()) {
                            safeModeSavedEnabled.add(m.id);
                            m.setEnabled(false); // volontairement PAS de HudConfigStore.save() — voir description
                        }
                    }
                    LauncherLog.info("[YfCommands] Safe-mode activé — " + safeModeSavedEnabled.size() + " module(s) désactivé(s) temporairement");
                } else if ("deactivate".equals(args[0])) {
                    if (!safeModeActive) return;
                    safeModeActive = false;
                    for (LauncherModule m : ModuleRegistry.all()) {
                        if (safeModeSavedEnabled.contains(m.id)) m.setEnabled(true);
                    }
                    LauncherLog.info("[YfCommands] Safe-mode désactivé — " + safeModeSavedEnabled.size() + " module(s) réactivé(s)");
                    safeModeSavedEnabled.clear();
                } else {
                    LauncherLog.info("[YfCommands] Usage : /yf safe-mode activate|deactivate");
                }
            }
        };
    }

    private static ClientCommand report() {
        return new ClientCommand() {
            public String name() { return "yf report"; }
            public String description() { return "Dump un snapshot (version, bracket MC, modules actifs) prêt pour le support"; }
            public void execute(String[] args) {
                try {
                    String appData = System.getenv("APPDATA");
                    if (appData == null) {
                        LauncherLog.err("[YfCommands] /yf report: APPDATA introuvable");
                        return;
                    }
                    File dir = new File(appData, "YuyuFrame\\agent\\reports");
                    dir.mkdirs();
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    File out = new File(dir, "report-" + timestamp + ".txt");
                    StringBuilder sb = new StringBuilder();
                    sb.append("YuyuFrame LauncherAgent — rapport de diagnostic\n");
                    sb.append("Build agent : ").append(LauncherAgent.buildVersion()).append('\n');
                    sb.append("Version MC : ").append(System.getProperty("launcheragent.mcVersion", "?")).append('\n');
                    sb.append("Modules :\n");
                    for (LauncherModule m : ModuleRegistry.all()) {
                        sb.append("  - ").append(m.id).append(" : ").append(m.isEnabled() ? "actif" : "inactif").append('\n');
                    }
                    try (FileWriter w = new FileWriter(out)) {
                        w.write(sb.toString());
                    }
                    LauncherLog.info("[YfCommands] Rapport écrit : " + out.getAbsolutePath());
                } catch (Throwable t) {
                    LauncherLog.err("[YfCommands] /yf report: " + t);
                }
            }
        };
    }

    private static ClientCommand resetHud() {
        return new ClientCommand() {
            public String name() { return "yf reset-hud"; }
            public String description() { return "Remet les positions HUD par défaut"; }
            public void execute(String[] args) {
                int count = 0;
                for (LauncherModule m : ModuleRegistry.all()) {
                    if (m instanceof HudElementOwner) {
                        ((HudElementOwner) m).hudElement().resetPosition();
                        count++;
                    }
                }
                HudConfigStore.save();
                LauncherLog.info("[YfCommands] " + count + " élément(s) HUD réinitialisé(s)");
            }
        };
    }

    private static ClientCommand version() {
        return new ClientCommand() {
            public String name() { return "yf version"; }
            public String description() { return "Affiche version/build/bracket détecté"; }
            public void execute(String[] args) {
                LauncherLog.info("[YfCommands] Build agent : " + LauncherAgent.buildVersion()
                    + " — MC " + System.getProperty("launcheragent.mcVersion", "?"));
            }
        };
    }

    private static ClientCommand modules() {
        return new ClientCommand() {
            public String name() { return "yf modules"; }
            public String description() { return "Liste rapide des modules actifs/inactifs"; }
            public void execute(String[] args) {
                StringBuilder sb = new StringBuilder("[YfCommands] Modules :");
                for (LauncherModule m : ModuleRegistry.all()) {
                    sb.append("\n  - ").append(m.id).append(" : ").append(m.isEnabled() ? "actif" : "inactif");
                }
                LauncherLog.info(sb.toString());
            }
        };
    }

    private static ClientCommand reloadConfig() {
        return new ClientCommand() {
            public String name() { return "yf reload-config"; }
            public String description() { return "Recharge HudConfigStore/ModuleRegistry depuis le disque sans relancer le jeu"; }
            public void execute(String[] args) {
                try {
                    // Ferme l'écran HUD/config en cours AVANT de recharger — évite
                    // une désync état affiché / état réel (voir ROADMAP-agent.md §Phase 4.5).
                    Object mc = GlobalUiRenderBridge261.getMcInstance();
                    if (mc != null) GlobalUiRenderBridge261.closeScreen(mc, null);
                } catch (Throwable ignored) {}
                try {
                    HudConfigStore.reload();
                    for (LauncherModule m : ModuleRegistry.all()) {
                        HudConfigStore.applyTo(m);
                    }
                    LauncherLog.info("[YfCommands] Config rechargée depuis le disque");
                } catch (Throwable t) {
                    LauncherLog.err("[YfCommands] /yf reload-config: " + t);
                }
            }
        };
    }

}
