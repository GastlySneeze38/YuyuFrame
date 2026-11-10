package fr.yuyuframe.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import fr.yuyuframe.game.YuyuGameActivity

/**
 * Le pont entre le launcher et l'écran du jeu Horizon (YuyuGameActivity, module `starling`).
 * Côté Rust : src/minecraft/launcher/horizon/android.rs.
 *
 * Il ne transporte aucune option de partie : le launcher les écrit avant dans horizon-args.txt,
 * que le jeu lit au démarrage. Ici on ne fait que ce que seul Android sait faire : ouvrir
 * l'écran, savoir si son processus (« :game ») tourne encore, l'arrêter.
 */
@TauriPlugin
class GamePlugin(private val activity: Activity) : Plugin(activity) {
    private val gameProcess: String get() = activity.packageName + ":game"

    /** Le processus du jeu, s'il tourne. Android ne liste à une appli que ses propres processus. */
    private fun game(): ActivityManager.RunningAppProcessInfo? {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses?.firstOrNull { it.processName == gameProcess }
    }

    @Command
    fun start(invoke: Invoke) {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(activity, YuyuGameActivity::class.java))
                // Droit dans la première image noire du jeu, sans animation entre les deux.
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
                invoke.resolve(JSObject())
            } catch (e: Exception) {
                invoke.reject("L'écran du jeu n'a pas pu s'ouvrir : ${e.message}")
            }
        }
    }

    @Command
    fun running(invoke: Invoke) {
        val result = JSObject()
        result.put("running", game() != null)
        invoke.resolve(result)
    }

    /** Arrête la partie (annulation depuis le launcher) : même utilisateur Linux, donc permis. */
    @Command
    fun stop(invoke: Invoke) {
        game()?.let { android.os.Process.killProcess(it.pid) }
        invoke.resolve(JSObject())
    }

    /**
     * Ce qui décrit ce téléphone au serveur (src/app/device.rs) — rien à voir avec le jeu, mais
     * c'est le seul pont vers Android du launcher.
     *
     * - `name` : le nom choisi dans les réglages (« Galaxy A32 de … »), sinon fabricant + modèle.
     *   Sans lui, la liste des appareils du compte montrait « PC ».
     * - `id` : ANDROID_ID, propre à l'appli sur ce téléphone et qui survit à une réinstallation
     *   (même clé de signature) : l'équivalent du MachineGuid de Windows pour l'anti-alt. Le Rust
     *   n'en envoie qu'un hachage salé. Aucune permission requise.
     */
    @Command
    fun device(invoke: Invoke) {
        val resolver = activity.contentResolver
        val result = JSObject()
        val chosen = try {
            android.provider.Settings.Global.getString(resolver, "device_name")
        } catch (e: Exception) {
            null
        }
        val brand = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = android.os.Build.MODEL.orEmpty()
        val fallback = if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model".trim()
        result.put("name", chosen?.takeIf { it.isNotBlank() } ?: fallback)
        result.put("model", fallback)
        result.put("id", android.provider.Settings.Secure.getString(resolver, android.provider.Settings.Secure.ANDROID_ID) ?: "")
        invoke.resolve(result)
    }

    /** La version du moteur embarqué (écrite par horizon-android/yuyu/build_engine.py). */
    @Command
    fun engine(invoke: Invoke) {
        val result = JSObject()
        result.put("version", activity.getString(io.github.juanluci.starling.R.string.yuyu_engine_version))
        invoke.resolve(result)
    }
}
