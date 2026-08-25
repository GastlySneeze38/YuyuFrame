package com.yuyuframe.launcheragent.mixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.MixinHookPointRegistry;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.version.MinecraftVersionDetector;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Plugin Mixin — lit launcher-agent.properties (externe puis embarqué) et
 * configure LauncherLog + les options de debug du framework Mixin.
 *
 * Cet appel est REDONDANT avec LauncherLog.loadConfigFromDefaultLocations()
 * (appelé bien plus tôt dans LauncherAgent.premain(), avant le moindre autre
 * log — voir ce fichier) mais reste nécessaire ici pour deux raisons : capter
 * un fichier externe déposé/modifié entre les deux appels, et lire les clés
 * mixin.debug.* (propres à ce plugin, pas à LauncherLog).
 *
 * Copie indépendante de com.p2pminecraft.mixin.P2PMixinConfigPlugin — propre
 * fichier de config, propre sous-dossier %APPDATA%.
 */
public class LauncherMixinConfigPlugin implements IMixinConfigPlugin {

    private Properties cfg = new Properties();

    /**
     * Gate déclarative {@link HookPoint} — DÉSACTIVÉE par défaut (2026-08-25, §12).
     *
     * Elle n'avait jamais tourné : le plugin échouait à se charger
     * ({@code ClassCastException} app/isolatedCl). Une fois ce blocage corrigé,
     * elle s'est révélée structurellement inopérante — au premier lancement où
     * elle a réellement décidé : <b>51 mixins IGNORÉ, 0 tissé</b>.
     *
     * Cause : {@code shouldApplyMixin()} est appelé au TISSAGE (préparation des
     * configs, au boot), alors que les modules ne s'enregistrent qu'à
     * l'EXÉCUTION — {@code VanillaHookRegistry.register(...)} est appelé depuis
     * l'init de {@code GlobalUiRenderMixin261}, à la première frame rendue.
     * Au moment de la décision, le registre est donc TOUJOURS vide et
     * {@code isUsed()} renvoie systématiquement {@code false} : HUD, fog, chat,
     * keybind, level, clock, screen — tout est écarté.
     *
     * Pour la rendre viable il faut une déclaration STATIQUE, connue avant le
     * tissage : le câblage {@code super("mon-module", HookPoint.X)} au
     * constructeur de {@code LauncherModule} évoqué dans la javadoc de
     * {@link VanillaHookRegistry} (jamais implémenté). La gate lirait alors ce
     * catalogue statique au lieu du registre runtime.
     *
     * En attendant, on journalise la décision qu'elle AURAIT prise sans
     * l'appliquer — utile pour mesurer le gain potentiel. Réactivable par
     * {@code -Dlauncheragent.hookpointGate=true} une fois la déclaration
     * statique en place.
     */
    private static final boolean GATE_ENABLED =
        "true".equalsIgnoreCase(System.getProperty("launcheragent.hookpointGate", "false"));

    @Override
    public void onLoad(String mixinPackage) {
        // MixinExtras (ROADMAP-agent.md Phase 3) — s'enregistre dans L'INSTANCE
        // Mixin qui appelle onLoad() ici, jamais celle de Fabric : sous Fabric,
        // ce plugin est chargé par le classloader isolé (voir LauncherAgent.
        // startIsolated()/IsolatedBootstrap), donc cet appel rejoint notre
        // propre instance Mixin isolée — pas de partage de singleton avec
        // Fabric (même raisonnement que l'isolation de Mixin lui-même, voir
        // docs/LauncherAgent/index.md). Appelé une seule fois par lancement
        // (un seul bracket résolu, voir VersionBracketRegistry), pas besoin
        // de garde d'idempotence.
        initMixinExtrasIfHostDidNot();
        cfg = LauncherLog.loadPropertiesFromDefaultLocations(getClass().getClassLoader());
        applyMixinDebugProperties();
        LauncherLog.loadConfig(cfg);
        LauncherLog.asm(1, "[MixinPlugin] onLoad — package=" + mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if ("true".equalsIgnoreCase(cfg.getProperty("disable_mixin." + simpleName))) {
            LauncherLog.asm(3, "[MixinPlugin] désactivé par config : " + simpleName);
            return false;
        }
        // Les Mixins 1.8.9 dépendent des Yarn Legacy Fabric pour résoudre les
        // noms obfusqués. Sans mappings, @Shadow et les cibles de classe échoueraient
        // au runtime avec un ClassTransformException difficile à diagnostiquer.
        if (mixinClassName.contains(".v1_8.") && !MappingsRegistry.isLoaded()) {
            String mcVer = System.getProperty("launcheragent.mcVersion", "unknown");
            LauncherLog.warn("[MixinPlugin] " + simpleName + " ignoré — Yarn 1.8.9 non chargé "
                + "(fournissez yarn=<legacy-yarn-" + mcVer + "-mergedv2.jar>)");
            return false;
        }

        // Tissage déclaratif apimixin/ (ROADMAP-agent.md §3.2) — un mixin
        // apimixin backé par un HookPoint (voir MixinHookPointRegistry) ne
        // weave QUE si au moins un module s'est enregistré dessus via
        // VanillaHookRegistry.register(...). Un mixin apimixin absent du
        // registre (hub/infra, accessors/invokers, freelook — hors système
        // HookPoint) continue de weave sans condition, comme avant.
        if (mixinClassName.startsWith("com.yuyuframe.launcheragent.apimixin.")) {
            HookPoint point = MixinHookPointRegistry.resolve(simpleName);
            if (point != null) {
                boolean used = VanillaHookRegistry.isUsed(point);
                LauncherLog.asm(2, "[MixinPlugin] " + simpleName + " → " + point
                    + " : " + (used ? "module(s) enregistré(s)" : "aucun module enregistré")
                    + (GATE_ENABLED ? (used ? " → tissé" : " → IGNORÉ") : " → tissé (gate désactivée)"));
                if (GATE_ENABLED) return used;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        // Niveau 3 (toujours visible en console, pas seulement en verbeux) —
        // confirmation explicite que CE Mixin s'est bien tissé dans sa cible
        // réelle, pas juste "bootstrap réussi" au sens large.
        LauncherLog.asm(3, "[MixinPlugin] Mixin initialisé avec succès : " + mixinClassName + " → " + targetClassName);
    }

    /**
     * N'initialise MixinExtras que si l'hôte ne l'a pas déjà fait (2026-08-25, §12).
     *
     * Historique : {@code MixinExtrasBootstrap.init()} était la première
     * instruction d'{@link #onLoad}, mais {@code onLoad} n'a JAMAIS été appelé
     * sur le bracket 26.1 — le plugin échouait à se charger
     * ({@code ClassCastException} app/isolatedCl, corrigé via
     * {@code LauncherMixinService.findClass}). C'était donc du code mort, et
     * nos ~33 injecteurs MixinExtras ({@code @WrapOperation}, {@code @WrapMethod},
     * {@code @Local}, {@code @WrapWithCondition}) fonctionnaient malgré tout,
     * parce que Fabric initialise MixinExtras pour toute la JVM
     * ({@code Initializing MixinExtras via MixinExtrasServiceImpl}).
     *
     * Dès que le plugin s'est mis à fonctionner (v737/v738), cet appel a produit
     * un SECOND enregistrement de MixinExtras dans un pipeline partagé, et le
     * jeu ne démarrait plus : pendant notre retransform, les injecteurs
     * MixinExtras des mods Fabric ({@code wrapOperation$…},
     * {@code modifyExpressionValue$…}, {@code wrapMethod$…}) échouaient en
     * cascade sur « cannot overwrite method … @Overwrite is required », suivis
     * de {@code ClassFormatError}.
     *
     * Détection : {@code KnotClient} est chargé par le classloader SYSTÈME sous
     * Fabric/Quilt (c'est lui qui contient le {@code main}), donc visible d'ici.
     * Hors Fabric (vanilla), personne n'a initialisé MixinExtras et l'appel
     * reste nécessaire.
     */
    private void initMixinExtrasIfHostDidNot() {
        boolean hostAlreadyInit = false;
        for (String knot : new String[]{
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "org.quiltmc.loader.impl.launch.knot.KnotClient"}) {
            try {
                Class.forName(knot, false, ClassLoader.getSystemClassLoader());
                hostAlreadyInit = true;
                break;
            } catch (Throwable ignored) {}
        }
        if (hostAlreadyInit) {
            LauncherLog.asm(3, "[MixinPlugin] MixinExtras déjà initialisé par le loader hôte — init() ignoré");
            return;
        }
        MixinExtrasBootstrap.init();
        LauncherLog.asm(3, "[MixinPlugin] MixinExtras initialisé par nous (hôte sans MixinExtras)");
    }

    private void applyMixinDebugProperties() {
        applyIfTrue("mixin.debug.export",   "mixin.debug.export");
        applyIfTrue("mixin.debug.verify",   "mixin.debug.verify");
        applyIfTrue("mixin.debug.verbose",  "mixin.debug.verbose");
        applyIfTrue("mixin.debug.strict",   "mixin.debug.strict");
        applyIfTrue("mixin.debug.profiler", "mixin.debug.profiler");
        applyIfTrue("mixin.dump_on_failure", "mixin.dumpTargetOnFailure");
    }

    private void applyIfTrue(String propKey, String sysProp) {
        String val = cfg.getProperty(propKey, "false");
        if ("true".equalsIgnoreCase(val)) {
            System.setProperty(sysProp, "true");
            LauncherLog.asm(1, "[MixinPlugin] " + sysProp + "=true");
        }
    }
}
