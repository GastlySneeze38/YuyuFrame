package fr.yuyuframe.launcher

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.window.SplashScreenView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : TauriActivity() {
  /** L'écran de démarrage d'Android (12 et plus), gardé jusqu'au relais. */
  private var splashView: View? = null
  /** Ce que la page doit savoir pour prendre le relais (voir `YuyuBoot`). */
  @Volatile private var splashInfo: String? = null
  private var handoffAsked = false
  private var webView: WebView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Le contenu passe aussi sous l'encoche : l'interface y garde ses marges
    // (env(safe-area-inset-*), viewport-fit=cover dans index.html).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) keepSplash()
    hideSystemBars()
  }

  /**
   * Yuyu animé (drawable/yuyu_splash_anim.xml) bouge dès le toucher de
   * l'icône, mais Android retire son écran de démarrage au premier dessin de
   * l'appli, quand la page n'est pas encore chargée : Yuyu disparaissait.
   * On garde donc l'écran, on note où est l'icône, à quelle taille et depuis
   * quand elle s'anime, et la page pose son propre Yuyu exactement là, à la
   * même pose, avant de demander qu'on le retire (`YuyuBoot.handoff`).
   */
  private fun keepSplash() {
    splashScreen.setOnExitAnimationListener { view: SplashScreenView ->
      splashView = view
      val start = view.iconAnimationStart?.toEpochMilli() ?: 0L
      val icon = view.iconView
      val density = resources.displayMetrics.density
      splashInfo = if (icon != null && icon.width > 0) {
        val at = IntArray(2)
        icon.getLocationOnScreen(at)
        val origin = IntArray(2)
        webView?.getLocationOnScreen(origin)
        // En pixels CSS de la page : pixels de l'écran divisés par la densité.
        val x = (at[0] - origin[0]) / density
        val y = (at[1] - origin[1]) / density
        """{"x":$x,"y":$y,"w":${icon.width / density},"h":${icon.height / density},"start":$start}"""
      } else {
        """{"start":$start}"""
      }
      if (handoffAsked) removeSplash()
      // Filet : la page n'a jamais pris le relais (erreur de chargement).
      else view.postDelayed({ removeSplash() }, 8000)
    }
  }

  private fun removeSplash() {
    val view = splashView ?: return
    splashView = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (view as SplashScreenView).remove()
  }

  override fun onWebViewCreate(webView: WebView) {
    this.webView = webView
    webView.addJavascriptInterface(YuyuBoot(), "YuyuBoot")
  }

  /** Lu par le script de l'écran de démarrage de la page (index.html). */
  private inner class YuyuBoot {
    /** « none » : pas d'écran de démarrage d'Android (avant Android 12) ;
     *  « wait » : pas encore là ; sinon position, taille et départ. */
    @JavascriptInterface
    fun splash(): String =
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) "none" else splashInfo ?: "wait"

    /** La page a dessiné son Yuyu : on retire celui d'Android. */
    @JavascriptInterface
    fun handoff() {
      runOnUiThread {
        handoffAsked = true
        removeSplash()
      }
    }
  }

  // Android réaffiche les barres quand une fenêtre (clavier, sélecteur de
  // fichiers, connexion Microsoft) passe devant puis s'en va.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) hideSystemBars()
  }

  /**
   * Plein écran : ni barre d'état ni barre de navigation. Un glissé depuis le
   * bord les fait revenir un instant, comme dans un jeu.
   */
  private fun hideSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }
}
