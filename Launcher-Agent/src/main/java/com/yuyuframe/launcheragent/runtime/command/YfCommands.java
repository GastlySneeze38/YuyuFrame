package com.yuyuframe.launcheragent.runtime.command;

import com.yuyuframe.launcheragent.agent.LauncherAgent;
import com.yuyuframe.launcheragent.apigraphic.debug.DebugOverlayState;
import com.yuyuframe.launcheragent.apigraphic.debug.DevShaderLoader;
import com.yuyuframe.launcheragent.apigraphic.shader.UiSolidPipelinePoc;
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
 * Commandes intégrées {@code /yf ...} + {@code /shader-reload} — voir
 * ROADMAP-agent.md Phase 4.5 pour le tableau complet. Enregistrées par
 * {@link ClientCommandRegistry#bootstrap()}.
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
        ClientCommandRegistry.register(perf());
        ClientCommandRegistry.register(debug());
        ClientCommandRegistry.register(shaderPoc());
        ClientCommandRegistry.register(blurPoc());
        ClientCommandRegistry.register(taffyPoc());
        ClientCommandRegistry.register(richTextPoc());
        ClientCommandRegistry.register(batchPoc());
        ClientCommandRegistry.register(blendPoc());
        ClientCommandRegistry.register(particlePoc());
        ClientCommandRegistry.register(reloadConfig());
        ClientCommandRegistry.register(shaderReload());
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

    private static ClientCommand perf() {
        return new ClientCommand() {
            public String name() { return "yf perf"; }
            public String description() { return "Toggle l'overlay FPS/temps de frame de l'UI seule"; }
            public void execute(String[] args) {
                DebugOverlayState.perfOverlayEnabled = !DebugOverlayState.perfOverlayEnabled;
                LauncherLog.info("[YfCommands] Overlay perf UI : " + (DebugOverlayState.perfOverlayEnabled ? "activé" : "désactivé")
                    + " (état seulement pour l'instant — voir DebugOverlayState, rendu réel prévu Phase 5)");
            }
        };
    }

    private static ClientCommand debug() {
        return new ClientCommand() {
            public String name() { return "yf debug"; }
            public String description() { return "Toggle l'overlay debug (wireframe, compteur de draw calls)"; }
            public void execute(String[] args) {
                DebugOverlayState.wireframeEnabled = !DebugOverlayState.wireframeEnabled;
                LauncherLog.info("[YfCommands] Overlay debug rendu : " + (DebugOverlayState.wireframeEnabled ? "activé" : "désactivé")
                    + " (état seulement pour l'instant — voir DebugOverlayState, rendu réel prévu Phase 5)");
            }
        };
    }

    private static ClientCommand shaderPoc() {
        return new ClientCommand() {
            public String name() { return "yf shaderpoc"; }
            public String description() { return "Toggle un quad de test dessiné via un RenderPipeline/GLSL 100% maison (roadmap Phase 5, preuve de mécanisme — voir ShaderPipelineFactory)"; }
            public void execute(String[] args) {
                UiSolidPipelinePoc.testEnabled = !UiSolidPipelinePoc.testEnabled;
                LauncherLog.info("[YfCommands] Test pipeline shader maison : " + (UiSolidPipelinePoc.testEnabled ? "activé" : "désactivé")
                    + " (quad rose plein écran si le mécanisme fonctionne — voir logs en cas d'échec)");
            }
        };
    }

    private static ClientCommand blurPoc() {
        return new ClientCommand() {
            public String name() { return "yf blurpoc"; }
            public String description() { return "Toggle un panneau de test flouté (dual-Kawase, roadmap Phase 5.1) centré à l'écran — voir Blaze3DBlur"; }
            public void execute(String[] args) {
                com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlur.testEnabled =
                    !com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlur.testEnabled;
                LauncherLog.info("[YfCommands] Test panneau flouté : " + (com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlur.testEnabled ? "activé" : "désactivé")
                    + " (panneau violet translucide centré si le mécanisme fonctionne — voir logs en cas d'échec)");
            }
        };
    }

    private static ClientCommand taffyPoc() {
        return new ClientCommand() {
            public String name() { return "yf taffypoc"; }
            public String description() { return "Preuve de mécanisme du moteur de layout Taffy (roadmap Phase 5.2) — construit un petit arbre, calcule son layout via content-core, log le résultat"; }
            public void execute(String[] args) {
                com.yuyuframe.launcheragent.apigraphic.layout.TaffyBridge.ensureLoaded();
                com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle rootStyle =
                    new com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle()
                        .flexDirection("row").gap(0, 10).padding(20);
                com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode root =
                    new com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode("root", rootStyle);

                com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle fixedStyle =
                    new com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle()
                        .size(com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle.px(80),
                              com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle.px(40));
                root.child(new com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode("fixed", fixedStyle));

                com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle growA =
                    new com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle().grow(1f)
                        .size(com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle.AUTO,
                              com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle.px(40));
                root.child(new com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode("growA", growA));

                com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle growB =
                    new com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle().grow(2f)
                        .size(com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle.AUTO,
                              com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle.px(40));
                root.child(new com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode("growB", growB));

                String treeJson = root.toJson();
                String resultJson = com.yuyuframe.launcheragent.apigraphic.layout.TaffyBridge.computeLayout(treeJson, 500f, 0f);
                java.util.Map<String, com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult.Rect> rects =
                    com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult.parse(resultJson);
                if (rects == null) {
                    LauncherLog.err("[YfCommands] taffypoc: échec — " + com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult.lastError
                        + " | JSON brut: " + resultJson);
                    return;
                }
                // Attendu (conteneur 500px, padding 20 de chaque côté -> 460px
                // utiles, gap 10 entre 3 enfants -> 20 de gaps -> 440px à
                // répartir : fixed=80 (fixe), reste 360 réparti 1:2 entre
                // growA(120)/growB(240)) : root=(0,0,500,?), fixed=(20,20,80,40),
                // growA=(110,20,120,40), growB=(240,20,240,40).
                LauncherLog.info("[YfCommands] taffypoc OK — " + rects.size() + " rects : " + rects);
            }
        };
    }

    private static ClientCommand richTextPoc() {
        return new ClientCommand() {
            public String name() { return "yf richtextpoc"; }
            public String description() { return "Toggle un paragraphe de test rich text (gras/couleur/lien mélangés, word-wrap automatique — roadmap Phase 5.3) — voir UiRichText"; }
            public void execute(String[] args) {
                com.yuyuframe.launcheragent.apigraphic.core.UiRichText.testEnabled =
                    !com.yuyuframe.launcheragent.apigraphic.core.UiRichText.testEnabled;
                LauncherLog.info("[YfCommands] Test rich text : " + (com.yuyuframe.launcheragent.apigraphic.core.UiRichText.testEnabled ? "activé" : "désactivé")
                    + " (paragraphe en bas à gauche si le mécanisme fonctionne — voir logs en cas d'échec)");
            }
        };
    }

    private static ClientCommand batchPoc() {
        return new ClientCommand() {
            public String name() { return "yf batchpoc"; }
            public String description() { return "Toggle une grille de test de 40 rects (couleurs/tailles variées, même rayon) dessinés en UN SEUL draw call — roadmap Phase 5.5, voir Blaze3DRect#drawRectBatch"; }
            public void execute(String[] args) {
                com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DRect.batchTestEnabled =
                    !com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DRect.batchTestEnabled;
                LauncherLog.info("[YfCommands] Test rects batchés : " + (com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DRect.batchTestEnabled ? "activé" : "désactivé")
                    + " (grille 10x4 dégradée si le mécanisme fonctionne — voir logs en cas d'échec)");
            }
        };
    }

    private static ClientCommand blendPoc() {
        return new ClientCommand() {
            public String name() { return "yf blendpoc"; }
            public String description() { return "Toggle 3 panneaux de test (multiply/screen/overlay, roadmap Phase 5.4) mélangés avec le fond actuel — voir Blaze3DBlend"; }
            public void execute(String[] args) {
                com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlend.testEnabled =
                    !com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlend.testEnabled;
                LauncherLog.info("[YfCommands] Test modes de fusion : " + (com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlend.testEnabled ? "activé" : "désactivé")
                    + " (3 panneaux orange multiply/screen/overlay si le mécanisme fonctionne — voir logs en cas d'échec)");
            }
        };
    }

    private static ClientCommand particlePoc() {
        return new ClientCommand() {
            public String name() { return "yf particlepoc"; }
            public String description() { return "Toggle un burst de confettis répété (roadmap Phase 5.4) — voir UiParticleSystem"; }
            public void execute(String[] args) {
                com.yuyuframe.launcheragent.apigraphic.core.UiParticleSystem.testEnabled =
                    !com.yuyuframe.launcheragent.apigraphic.core.UiParticleSystem.testEnabled;
                LauncherLog.info("[YfCommands] Test particules : " + (com.yuyuframe.launcheragent.apigraphic.core.UiParticleSystem.testEnabled ? "activé" : "désactivé")
                    + " (confettis en bas au centre, toutes les ~1.5s, si le mécanisme fonctionne)");
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

    private static ClientCommand shaderReload() {
        return new ClientCommand() {
            public String name() { return "shader-reload"; }
            public String description() { return "Hot-reload des shaders GLSL (mode dev, voir DevShaderLoader)"; }
            public void execute(String[] args) {
                if (!DevShaderLoader.isDevModeActive()) {
                    LauncherLog.info("[YfCommands] /shader-reload : mode dev inactif — poser la system property "
                        + "\"launcheragent.shaderDevDir\" (répertoire de .vert/.frag) pour l'activer");
                    return;
                }
                // VOIR DevShaderLoader — le rebranchement des constantes de
                // shader existantes (UiRenderer/UiPrimitiveRenderer/UiTextRenderer)
                // sur ce loader + l'invalidation des programmes GL déjà compilés
                // sont laissés au rework Phase 5 (moteur sur le point d'être
                // repris de zéro). Rien à recompiler pour l'instant.
                LauncherLog.info("[YfCommands] /shader-reload : mode dev actif, mais aucun shader n'est encore "
                    + "branché sur DevShaderLoader (voir sa javadoc) — à faire pendant le rework Phase 5");
            }
        };
    }
}
