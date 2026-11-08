package fr.yuyuframe.launcher

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Le contenu passe aussi sous l'encoche : l'interface y garde ses marges
    // (env(safe-area-inset-*), viewport-fit=cover dans index.html).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    hideSystemBars()
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
