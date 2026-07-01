package com.yuyuframe.launcheragent.mixin;

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

    @Override
    public void onLoad(String mixinPackage) {
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
        LauncherLog.asm(1, "[MixinPlugin] appliqué : " + mixinClassName + " → " + targetClassName);
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
