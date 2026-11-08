package fr.yuyuframe.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class PageArgs {
    lateinit var url: String
    lateinit var title: String
    lateinit var close: String
}

/**
 * La page de connexion Microsoft dans l'appli, par-dessus le launcher, au lieu du navigateur du
 * téléphone : ouvrir un autre appli pour un code à recopier, puis revenir, ne marchait pas.
 * Côté Rust : src/account/minecraft/sign_in_page.rs, qui vérifie l'adresse avant de l'envoyer ici.
 *
 * Une fenêtre plein écran avec une barre (titre, « Fermer ») et une vue web. Seules les pages en
 * https s'y ouvrent ; le reste (un lien vers une autre appli) est refusé.
 */
@TauriPlugin
class SignInPlugin(private val activity: Activity) : Plugin(activity) {
    private var dialog: Dialog? = null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    @Command
    fun open(invoke: Invoke) {
        val args = invoke.parseArgs(PageArgs::class.java)
        activity.runOnUiThread {
            dialog?.dismiss()
            val window = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

            val title = TextView(activity).apply {
                text = args.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
            }
            val close = Button(activity).apply {
                text = args.close
                isAllCaps = false
                setOnClickListener { window.dismiss() }
            }
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(9, 9, 13))
                setPadding(dp(16), dp(8), dp(8), dp(8))
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            }
            val web = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"
                }
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(9, 9, 13))
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }

            window.setContentView(root)
            window.setOnDismissListener {
                web.destroy()
                if (dialog === window) dialog = null
            }
            window.show()
            // Plein écran comme le launcher derrière (MainActivity).
            window.window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                val controller = WindowInsetsControllerCompat(it, it.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            web.loadUrl(args.url)
            dialog = window
            invoke.resolve(JSObject())
        }
    }

    /** Ferme la page (connexion réussie) ; rien si elle l'est déjà. */
    @Command
    fun close(invoke: Invoke) {
        activity.runOnUiThread {
            dialog?.dismiss()
            invoke.resolve(JSObject())
        }
    }
}
