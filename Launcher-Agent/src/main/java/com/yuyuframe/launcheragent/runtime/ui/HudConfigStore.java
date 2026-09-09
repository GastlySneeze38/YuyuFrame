package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.ui.config.Setting;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Sauvegarde disque des réglages de module — activation, {@link Setting}
 * déclarés par le module, et position/échelle/marges des éléments HUD (voir
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
 * &lt;id&gt;.setting.&lt;idDuRéglage&gt;=&lt;valeur&gt;   (un Setting déclaré par ligne)
 * </pre>
 *
 * La clé d'un réglage est son {@link Setting#id}, choisi explicitement par le
 * module. C'était auparavant {@code .field.<nomDuChampJava>} : renommer un
 * champ effaçait alors silencieusement le réglage chez tous les utilisateurs.
 * <b>Ce changement de clé rend les fichiers antérieurs au 2026-08-30
 * illisibles pour la partie réglages</b> (activation, favoris et position HUD
 * restent lus normalement) — les valeurs concernées repartent sur le défaut du
 * module, choix assumé plutôt que de traîner un pont de compatibilité.
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

    /**
     * Un fichier de config PAR INSTANCE, pas un seul fichier global partagé —
     * chaque instance (1.8.9 PvP, 1.21.11 vanilla, etc.) a ses propres modules
     * actifs et réglages, qui n'ont aucune raison de se partager ou de
     * s'écraser entre eux.
     *
     * L'identifiant d'instance vient du dossier de travail du process Java :
     * {@code Backend/src/minecraft/launcher.rs} lance TOUJOURS le process
     * avec {@code .current_dir(&mc_game_dir)}, où {@code mc_game_dir} =
     * {@code instances/<instanceId>/} (voir {@code launch.rs:50,556}) — donc
     * {@code user.dir} EST le dossier de l'instance, peu importe la version
     * MC. On ne garde que le dernier segment du chemin (le slug d'instance,
     * ex: "vqjgwrmed6mr") comme nom de fichier — déjà un nom de dossier
     * Windows valide, aucune sanitization nécessaire. Pas besoin de toucher
     * au Rust ni d'ajouter un nouvel argument d'agent : cette info est déjà
     * disponible telle quelle.
     */
    private static final String CONFIG_PATH = computeConfigPath();

    private static String computeConfigPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;
        String userDir = System.getProperty("user.dir", "");
        String instanceSlug = userDir.replace('\\', '/');
        int lastSlash = instanceSlug.lastIndexOf('/');
        instanceSlug = lastSlash >= 0 ? instanceSlug.substring(lastSlash + 1) : instanceSlug;
        if (instanceSlug.isEmpty()) instanceSlug = "default";
        return appData + "\\YuyuFrame\\agent\\module-config\\" + instanceSlug + ".properties";
    }

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

    /**
     * Force une relecture depuis le disque au prochain {@link #applyTo}/accès
     * — {@link #ensureLoaded()} ne charge normalement qu'UNE FOIS par
     * process. Voir {@code YfCommands} ("/yf reload-config", Phase 4.5) :
     * l'appelant doit ensuite rappeler {@link #applyTo(LauncherModule)} pour
     * CHAQUE module de {@code ModuleRegistry.all()} pour que les nouvelles
     * valeurs soient effectivement réappliquées.
     */
    public static synchronized void reload() {
        DATA.clear();
        loaded = false;
        ensureLoaded();
    }

    /** Écrase les valeurs par défaut du module (déjà fixées par son constructeur) avec ce qui a été persisté, s'il y a quelque chose. */
    public static void applyTo(LauncherModule module) {
        ensureLoaded();
        String id = module.id;

        String enabledStr = DATA.getProperty(id + ".enabled");
        if (enabledStr != null) module.setEnabled(Boolean.parseBoolean(enabledStr));
        String favoriteStr = DATA.getProperty(id + ".favorite");
        if (favoriteStr != null) module.favorite = Boolean.parseBoolean(favoriteStr);

        if (module instanceof HudElementOwner) {
            HudElement element = ((HudElementOwner) module).hudElement();
            String anchor = DATA.getProperty(id + ".hud.anchor");
            if (anchor != null) {
                try { element.anchor = HudAnchor.valueOf(anchor); } catch (IllegalArgumentException ignored) {}
            }
            element.offsetX = getFloat(id + ".hud.offsetX", element.offsetX);
            element.offsetY = getFloat(id + ".hud.offsetY", element.offsetY);
            element.locked = getBoolean(id + ".hud.locked", element.locked);
            element.opacity = getFloat(id + ".hud.opacity", element.opacity);
            // Couleur de texte : stockée en ARGB packé. Absente = on GARDE
            // celle posée par le module (accent FPS/Ping...), on ne la force
            // pas à blanc — d'où le test de présence plutôt qu'un défaut.
            String packedColor = DATA.getProperty(id + ".hud.textColor");
            if (packedColor != null) {
                try {
                    int argb = Integer.parseInt(packedColor.trim());
                    element.textColor = new com.yuyuframe.launcheragent.apigraphic.value.UiColor(
                        (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
                } catch (NumberFormatException ignored) {}
            }
            element.showWhenScreenOpen = getBoolean(id + ".hud.showWhenScreenOpen", element.showWhenScreenOpen);
            element.paddingX = getFloat(id + ".hud.paddingX", element.paddingX);
            element.paddingY = getFloat(id + ".hud.paddingY", element.paddingY);
            // setScale (pas une assignation directe) : recalcule w/h depuis naturalSize()*scale, voir HudElement.
            element.setScale(getFloat(id + ".hud.scale", element.scale));
        }

        // Réglages déclarés par le module (voir Setting) — chaque type sait
        // se relire lui-même, il n'y a plus de table annotation→type ici.
        // Clé = l'id STABLE du réglage, plus le nom du champ Java : renommer
        // un champ n'efface plus le réglage chez l'utilisateur.
        for (Setting setting : module.settings()) {
            if (!setting.persistent) continue;   // voir Setting.persistent
            String raw = DATA.getProperty(id + ".setting." + setting.id);
            if (raw == null) continue;
            if (!setting.deserialize(raw)) {
                // Valeur illisible (fichier édité à la main, format changé) :
                // le défaut du module reste en place. Journalisé — l'ancienne
                // version avalait ce cas en silence.
                LauncherLog.err("[HudConfigStore] " + id + "." + setting.id
                    + " : valeur ignorée \"" + raw + "\", défaut conservé");
            }
        }
    }

    /** Voir {@link ModuleGroup#favorite} — même principe que {@link #applyTo(LauncherModule)} mais pour un groupe, qui n'a QUE ce champ à persister (pas de {@code enabled}/champs {@code @Config*} annotés, un groupe n'est pas un {@link LauncherModule}). */
    public static void applyFavoriteTo(ModuleGroup group) {
        ensureLoaded();
        String favoriteStr = DATA.getProperty("group." + group.id + ".favorite");
        if (favoriteStr != null) group.favorite = Boolean.parseBoolean(favoriteStr);
    }

    /**
     * Favori d'une carte "action" (voir {@code UiMainMenuScreen.ActionCard}) —
     * demandé explicitement ("on ne peut pas mettre Modrinth en favori").
     * Contrairement à un {@link LauncherModule}/{@link ModuleGroup} (instance
     * UNIQUE créée au démarrage, tenue par {@link ModuleRegistry}), une
     * {@code ActionCard} est reconstruite à CHAQUE {@code rebuildAll()} —
     * son état ne peut donc pas juste être relu depuis un champ persistant
     * comme les deux autres, il doit être explicitement rechargé ICI à
     * chaque reconstruction (voir l'appelant) plutôt qu'une seule fois au
     * démarrage. {@code actionId} : identifiant stable choisi par
     * l'appelant (ex: "modrinth") — pas de champ {@code id} dédié sur
     * ActionCard, une seule carte de ce type existe pour l'instant.
     */
    public static boolean loadActionFavorite(String actionId) {
        ensureLoaded();
        return Boolean.parseBoolean(DATA.getProperty("action." + actionId + ".favorite", "false"));
    }

    /** Voir {@link #loadActionFavorite} — écrit ET sauvegarde immédiatement sur disque (même convention que {@link #save()}, appelé après chaque bascule de favori ailleurs). */
    public static void saveActionFavorite(String actionId, boolean favorite) {
        DATA.setProperty("action." + actionId + ".favorite", String.valueOf(favorite));
        save();
    }

    /**
     * Demande une sauvegarde — <b>différée</b>, pas écrite tout de suite.
     *
     * <p>AUDIT PERF (2026-08-31) : cette méthode est appelée depuis CHAQUE
     * callback de changement de {@code ConfigScreenBuilder}, donc à chaque
     * frappe dans un champ de texte et à chaque cran de curseur pendant un
     * drag. Elle re-sérialisait les 30 modules PUIS réécrivait tout le
     * fichier de façon synchrone : taper {@code /spawn} dans une macro = six
     * sérialisations complètes et six écritures disque. Invisible tant que
     * les réglages n'étaient que des toggles ; les champs de texte l'ont
     * rendu réel.
     *
     * <p>Différer le TOUT (sérialisation comprise, pas seulement l'écriture)
     * est ce qui fait vraiment tomber le coût : une rafale de vingt frappes
     * ne produit plus qu'une seule sérialisation, celle de l'état final.
     *
     * <p>{@link #flush()} force l'écriture immédiate — appelé à la fermeture
     * d'un écran et à l'arrêt de l'agent, les deux moments où on ne peut plus
     * compter sur le tick.
     */
    public static synchronized void save() {
        dirty = true;
        dirtySinceMs = System.currentTimeMillis();
    }

    private static boolean dirty;
    private static long dirtySinceMs;

    /**
     * Délai d'inactivité avant écriture. 400 ms : au-delà du rythme de frappe
     * le plus rapide (une rafale reste donc une seule écriture), bien en deçà
     * du temps de fermer un écran.
     */
    private static final long SAVE_DELAY_MS = 400L;

    /** Appelé à chaque tick client (voir {@code ModuleRegistry.tickAll}) — écrit si la fenêtre d'inactivité est passée. */
    public static synchronized void tick() {
        if (!dirty) return;
        if (System.currentTimeMillis() - dirtySinceMs < SAVE_DELAY_MS) return;
        writeNow();
    }

    /** Écriture immédiate si quelque chose est en attente — fermeture d'écran, arrêt de l'agent. */
    public static synchronized void flush() {
        if (dirty) writeNow();
    }

    private static synchronized void writeNow() {
        dirty = false;
        if (CONFIG_PATH == null) return;
        try {
            for (LauncherModule module : ModuleRegistry.all()) {
                serializeModule(module);
            }
            // GlobalUiSettings n'est JAMAIS dans ModuleRegistry (voir sa
            // javadoc de classe) — sans cette ligne, "Taille de l'interface"
            // et les autres réglages généraux n'étaient JAMAIS écrits sur
            // disque, silencieusement perdus à chaque relance du jeu (voir
            // aussi son constructeur, qui charge maintenant ces valeurs via
            // HudConfigStore.applyTo()).
            serializeModule(GlobalUiSettings.INSTANCE);
            // Voir applyFavoriteTo(ModuleGroup) — même raison (pas dans
            // ModuleRegistry.all()), un seul champ à écrire par groupe.
            for (ModuleGroup group : ModuleRegistry.groups()) {
                DATA.setProperty("group." + group.id + ".favorite", String.valueOf(group.favorite));
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

    private static void serializeModule(LauncherModule module) {
        String id = module.id;
        DATA.setProperty(id + ".enabled", String.valueOf(module.isEnabled()));
        DATA.setProperty(id + ".favorite", String.valueOf(module.favorite));

        if (module instanceof HudElementOwner) {
            HudElement element = ((HudElementOwner) module).hudElement();
            DATA.setProperty(id + ".hud.anchor", element.anchor.name());
            DATA.setProperty(id + ".hud.offsetX", String.valueOf(element.offsetX));
            DATA.setProperty(id + ".hud.offsetY", String.valueOf(element.offsetY));
            DATA.setProperty(id + ".hud.scale", String.valueOf(element.scale));
            DATA.setProperty(id + ".hud.locked", String.valueOf(element.locked));
            DATA.setProperty(id + ".hud.opacity", String.valueOf(element.opacity));
            if (element.textColor != null) {
                com.yuyuframe.launcheragent.apigraphic.value.UiColor c = element.textColor;
                int argb = (Math.round(c.a * 255f) << 24) | (Math.round(c.r * 255f) << 16)
                    | (Math.round(c.g * 255f) << 8) | Math.round(c.b * 255f);
                DATA.setProperty(id + ".hud.textColor", String.valueOf(argb));
            }
            DATA.setProperty(id + ".hud.showWhenScreenOpen", String.valueOf(element.showWhenScreenOpen));
            DATA.setProperty(id + ".hud.paddingX", String.valueOf(element.paddingX));
            DATA.setProperty(id + ".hud.paddingY", String.valueOf(element.paddingY));
        }

        // Pendant exact de la boucle de lecture dans applyTo() — même clé,
        // même source de vérité (la liste déclarée par le module), donc plus
        // aucun risque qu'un réglage s'affiche sans jamais être sauvegardé.
        for (Setting setting : module.settings()) {
            if (!setting.persistent) continue;   // voir Setting.persistent
            String value = setting.serialize();
            if (value != null) DATA.setProperty(id + ".setting." + setting.id, value);
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
