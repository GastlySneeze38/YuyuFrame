package com.yuyuframe.launcheragent.apimixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.apimixin.version.MinecraftVersionDetector;
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

    @Override
    public void onLoad(String mixinPackage) {
        // MixinExtras (ROADMAP-agent.md Phase 3) — s'enregistre dans L'INSTANCE
        // Mixin qui appelle onLoad() ici, jamais celle de Fabric : sous Fabric,
        // ce plugin est chargé par le classloader isolé (voir LauncherAgent.
        // startIsolated()/IsolatedBootstrap), donc cet appel rejoint notre
        // propre instance Mixin isolée — pas de partage de singleton avec
        // Fabric (même raisonnement que l'isolation de Mixin lui-même, voir
        // docs/LauncherAgent/index.md). Appelé une seule fois par lancement
        // (un seul profil résolu, voir VersionProfileRegistry), pas besoin
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

        // Le filtrage déclaratif apimixin/HookPoint (ROADMAP-agent.md §3.2)
        // ne vit PLUS ici (2026-08-25, §12) — voir
        // IsolatedBootstrap.filterConfigByHookPoints(), appelé avant même
        // Mixins.addConfiguration(). Un mixin écarté n'apparaît plus du tout
        // dans le JSON que ce plugin reçoit : shouldApplyMixin() n'est donc
        // jamais sollicité pour lui, il n'y a rien à décider ici.
        //
        // Historique du pourquoi ce n'est PAS ici : ce plugin est chargé par
        // Mixin via le classloader de CONTEXTE au moment de la sélection des
        // configs, qui vaut 'app' à cet instant (launcher-agent.jar étant en
        // -javaagent, il y est AUSSI présent) — alors que Mixin tourne depuis
        // isolatedCl. Le cast échoue systématiquement sur tout bracket isolé
        // (Fabric/Quilt/Forge/NeoForge) : voir LauncherMixinService.findClass
        // pour l'historique complet (quatre tentatives de réveil du plugin,
        // toutes annulées) et VanillaHookRegistry.usedPoints()/auditDeclarations
        // pour le mécanisme qui a remplacé la gate.
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
        applyLegacyLwjglApi(targetClass);
    }

    private static final String LEGACY_ALIAS = "Lcom/yuyuframe/launcheragent/lwjgl2compat/LegacyAlias;";
    private static final String LEGACY_PUBLIC = "Lcom/yuyuframe/launcheragent/lwjgl2compat/LegacyPublic;";

    /**
     * Couche LWJGL 2 → 3 de la 1.8.9 : ajoute aux classes LWJGL 3 les méthodes
     * statiques PUBLIQUES que Minecraft 1.8.9 appelle encore. Mixin refuse
     * d'en créer lui-même (une méthode statique ajoutée par mixin doit être
     * privée) ; on les produit donc ici, après tissage :
     * <ul>
     *   <li>{@code @LegacyAlias("nomLwjgl2")} sur un {@code @Shadow} → nouvelle
     *       méthode {@code public static nomLwjgl2} au même descripteur, qui
     *       délègue à la méthode LWJGL 3 ;</li>
     *   <li>{@code @LegacyPublic} sur un {@code @Unique private static} →
     *       rendu public tel quel.</li>
     * </ul>
     * Mécanisme de {@code Lwjgl3MixinPostProcessor} (legacy-lwjgl3, moehreag,
     * LGPL-2.1). Sans effet sur toute classe qui ne porte aucune des deux
     * annotations — donc sur tous les autres mixins, toutes versions.
     */
    private static void applyLegacyLwjglApi(org.objectweb.asm.tree.ClassNode target) {
        for (org.objectweb.asm.tree.MethodNode method : new java.util.ArrayList<>(target.methods)) {
            if ((method.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) continue;
            if (hasInvisibleAnnotation(method, LEGACY_PUBLIC)) {
                method.access &= ~(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_PROTECTED);
                method.access |= org.objectweb.asm.Opcodes.ACC_PUBLIC;
                LauncherLog.asm(1, "[MixinPlugin] API LWJGL 2 rendue publique : " + target.name + "." + method.name + method.desc);
            }
            String alias = legacyAliasOf(method);
            if (alias != null && !hasMethod(target, alias, method.desc)) {
                target.methods.add(delegatingStaticMethod(target, method, alias));
                LauncherLog.asm(1, "[MixinPlugin] API LWJGL 2 recréée : " + target.name + "." + alias + method.desc
                    + " → " + method.name);
            }
        }
    }

    private static boolean hasInvisibleAnnotation(org.objectweb.asm.tree.MethodNode method, String desc) {
        if (method.invisibleAnnotations == null) return false;
        for (org.objectweb.asm.tree.AnnotationNode a : method.invisibleAnnotations) {
            if (desc.equals(a.desc)) return true;
        }
        return false;
    }

    private static String legacyAliasOf(org.objectweb.asm.tree.MethodNode method) {
        if (method.invisibleAnnotations == null) return null;
        for (org.objectweb.asm.tree.AnnotationNode a : method.invisibleAnnotations) {
            if (!LEGACY_ALIAS.equals(a.desc) || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if ("value".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String) {
                    return (String) a.values.get(i + 1);
                }
            }
        }
        return null;
    }

    private static boolean hasMethod(org.objectweb.asm.tree.ClassNode target, String name, String desc) {
        for (org.objectweb.asm.tree.MethodNode m : target.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        }
        return false;
    }

    /** {@code public static alias(args) { return target(args); }} — aucun branchement, donc aucun frame à calculer. */
    private static org.objectweb.asm.tree.MethodNode delegatingStaticMethod(
            org.objectweb.asm.tree.ClassNode owner, org.objectweb.asm.tree.MethodNode target, String alias) {
        org.objectweb.asm.tree.MethodNode stub = new org.objectweb.asm.tree.MethodNode(
            org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
            alias, target.desc, target.signature,
            target.exceptions == null ? null : target.exceptions.toArray(new String[0]));
        int slot = 0;
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(target.desc)) {
            stub.instructions.add(new org.objectweb.asm.tree.VarInsnNode(arg.getOpcode(org.objectweb.asm.Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        boolean isInterface = (owner.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
        stub.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
            org.objectweb.asm.Opcodes.INVOKESTATIC, owner.name, target.name, target.desc, isInterface));
        org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(target.desc);
        stub.instructions.add(new org.objectweb.asm.tree.InsnNode(ret.getOpcode(org.objectweb.asm.Opcodes.IRETURN)));
        stub.maxLocals = slot;
        stub.maxStack = Math.max(slot, ret.getSize());
        return stub;
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
