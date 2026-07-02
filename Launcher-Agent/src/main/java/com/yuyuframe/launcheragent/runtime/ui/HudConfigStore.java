package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Properties;

/**
 * Sauvegarde disque des réglages de module — activation, réglages
 * {@code @Config*} annotés, et position/échelle/marges des éléments HUD (voir
 * {@link HudElementOwner}). Avant ce fichier, tout était réinitialisé aux
 * valeurs par défaut à chaque relance de l'agent (voir l'ancien commentaire
 * sur {@code HudElement}, "pas encore de sauvegarde disque, viendra avec le
 * chantier persistance général").
 *
 * Format {@code Properties} (comme {@code launcher-agent.properties}, voir
 * {@link LauncherLog}) plutôt que JSON — aucune lib JSON n'est déployée au
 * runtime (vérifié dans build.bat : seuls Mixin/ASM sont sur le classpath),
 * et {@code Properties} suffit largement pour des paires clé/valeur plates.
 *
 * Clés, préfixées par l'id du module :
 * <pre>
 * &lt;id&gt;.enabled=true|false
 * &lt;id&gt;.hud.anchor=TOP_LEFT
 * &lt;id&gt;.hud.offsetX=0.0041  (FRACTION du viewport, pas des pixels — voir HudElement)
 * &lt;id&gt;.hud.offsetY=0.0074
 * &lt;id&gt;.hud.scale=1.0
 * &lt;id&gt;.hud.locked=false
 * &lt;id&gt;.hud.showWhenScreenOpen=false
 * &lt;id&gt;.hud.paddingX=0.0
 * &lt;id&gt;.hud.paddingY=0.0
 * &lt;id&gt;.field.&lt;nomDuChamp&gt;=&lt;valeur&gt;   (un champ @Config* par ligne)
 * </pre>
 *
 * {@link #applyTo(LauncherModule)} est appelé par {@link ModuleRegistry#register}
 * juste après la construction du module (donc APRÈS que ses valeurs par
 * défaut aient été fixées dans son constructeur — voir FpsModule etc.) : la
 * config persistée écrase simplement ces valeurs par défaut, elle ne les
 * empêche jamais d'exister d'abord.
 *
 * {@link #save()} est appelé depuis {@link ConfigScreenBuilder} après CHAQUE
 * changement (réglage HUD générique, champ annoté, reset position) et depuis
 * le toggle d'activation de {@code UiMainMenuScreen} — pas de debounce, une
 * écriture Properties est de l'ordre de la milliseconde, largement dans le
 * budget d'un clic utilisateur. Un shutdown hook (voir LauncherAgent) sert de
 * filet de sécurité si un point d'accroche futur oublie d'appeler save().
 */
public final class HudConfigStore {
    private HudConfigStore() {}

    private static final String CONFIG_PATH =
        System.getenv("APPDATA") != null
            ? System.getenv("APPDATA") + "\\YuyuFrame\\agent\\module-config.properties"
            : null;

    private static final Properties DATA = new Properties();
    private static boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (CONFIG_PATH == null) return;
        File f = new File(CONFIG_PATH);
        if (!f.exists()) return;
        try (FileInputStream fis = new FileInputStream(f)) {
            DATA.load(fis);
        } catch (Throwable t) {
            LauncherLog.err("[HudConfigStore] load: " + t);
        }
    }

    /** Écrase les valeurs par défaut du module (déjà fixées par son constructeur) avec ce qui a été persisté, s'il y a quelque chose. */
    public static void applyTo(LauncherModule module) {
        ensureLoaded();
        String id = module.id;

        String enabledStr = DATA.getProperty(id + ".enabled");
        if (enabledStr != null) module.setEnabled(Boolean.parseBoolean(enabledStr));

        if (module instanceof HudElementOwner) {
            HudElement element = ((HudElementOwner) module).hudElement();
            String anchor = DATA.getProperty(id + ".hud.anchor");
            if (anchor != null) {
                try { element.anchor = HudAnchor.valueOf(anchor); } catch (IllegalArgumentException ignored) {}
            }
            element.offsetX = getFloat(id + ".hud.offsetX", element.offsetX);
            element.offsetY = getFloat(id + ".hud.offsetY", element.offsetY);
            element.locked = getBoolean(id + ".hud.locked", element.locked);
            element.showWhenScreenOpen = getBoolean(id + ".hud.showWhenScreenOpen", element.showWhenScreenOpen);
            element.paddingX = getFloat(id + ".hud.paddingX", element.paddingX);
            element.paddingY = getFloat(id + ".hud.paddingY", element.paddingY);
            // setScale (pas une assignation directe) : recalcule w/h depuis naturalSize()*scale, voir HudElement.
            element.setScale(getFloat(id + ".hud.scale", element.scale));
        }

        for (Field field : module.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            String key = id + ".field." + field.getName();
            String raw = DATA.getProperty(key);
            if (raw == null) continue;
            try {
                if (field.isAnnotationPresent(ConfigToggle.class)) {
                    field.setBoolean(module, Boolean.parseBoolean(raw));
                } else if (field.isAnnotationPresent(ConfigSlider.class)) {
                    field.setFloat(module, Float.parseFloat(raw));
                } else if (field.isAnnotationPresent(ConfigDropdown.class)) {
                    field.setInt(module, Integer.parseInt(raw));
                } else if (field.isAnnotationPresent(ConfigKeybind.class)) {
                    field.set(module, raw);
                } else if (field.isAnnotationPresent(ConfigColor.class)) {
                    String[] parts = raw.split(",");
                    if (parts.length == 4) {
                        field.set(module, new UiColor(
                            Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]), Float.parseFloat(parts[3])));
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Sérialise l'état COURANT de tous les modules enregistrés — voir ConfigScreenBuilder (chaque callback de changement) et UiMainMenuScreen (toggle d'activation). */
    public static synchronized void save() {
        if (CONFIG_PATH == null) return;
        try {
            for (LauncherModule module : ModuleRegistry.all()) {
                String id = module.id;
                DATA.setProperty(id + ".enabled", String.valueOf(module.isEnabled()));

                if (module instanceof HudElementOwner) {
                    HudElement element = ((HudElementOwner) module).hudElement();
                    DATA.setProperty(id + ".hud.anchor", element.anchor.name());
                    DATA.setProperty(id + ".hud.offsetX", String.valueOf(element.offsetX));
                    DATA.setProperty(id + ".hud.offsetY", String.valueOf(element.offsetY));
                    DATA.setProperty(id + ".hud.scale", String.valueOf(element.scale));
                    DATA.setProperty(id + ".hud.locked", String.valueOf(element.locked));
                    DATA.setProperty(id + ".hud.showWhenScreenOpen", String.valueOf(element.showWhenScreenOpen));
                    DATA.setProperty(id + ".hud.paddingX", String.valueOf(element.paddingX));
                    DATA.setProperty(id + ".hud.paddingY", String.valueOf(element.paddingY));
                }

                for (Field field : module.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    String key = id + ".field." + field.getName();
                    try {
                        if (field.isAnnotationPresent(ConfigToggle.class)) {
                            DATA.setProperty(key, String.valueOf(field.getBoolean(module)));
                        } else if (field.isAnnotationPresent(ConfigSlider.class)) {
                            DATA.setProperty(key, String.valueOf(field.getFloat(module)));
                        } else if (field.isAnnotationPresent(ConfigDropdown.class)) {
                            DATA.setProperty(key, String.valueOf(field.getInt(module)));
                        } else if (field.isAnnotationPresent(ConfigKeybind.class)) {
                            Object v = field.get(module);
                            if (v != null) DATA.setProperty(key, (String) v);
                        } else if (field.isAnnotationPresent(ConfigColor.class)) {
                            UiColor c = (UiColor) field.get(module);
                            if (c != null) DATA.setProperty(key, c.r + "," + c.g + "," + c.b + "," + c.a);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            File f = new File(CONFIG_PATH);
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                DATA.store(fos, "YuyuFrame LauncherAgent - config des modules (généré automatiquement)");
            }
        } catch (Throwable t) {
            LauncherLog.err("[HudConfigStore] save: " + t);
        }
    }

    private static float getFloat(String key, float fallback) {
        String raw = DATA.getProperty(key);
        if (raw == null) return fallback;
        try { return Float.parseFloat(raw); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean getBoolean(String key, boolean fallback) {
        String raw = DATA.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw);
    }
}
