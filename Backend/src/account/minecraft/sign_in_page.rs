//! La page de connexion Microsoft (code d'appareil) ouverte dans l'appli sur
//! téléphone, par le plugin Kotlin `SignInPlugin` (`gen/android/app`).
//!
//! Sur Android, renvoyer le joueur vers le navigateur pour taper un code puis
//! revenir ne marchait pas (essai du 2026-11-08). La page s'ouvre donc dans
//! une fenêtre de l'appli, le code déjà rempli, et se ferme toute seule quand
//! la connexion a réussi.
//!
//! Sur ordinateur rien ne change : `sign_in_page_open` répond `false` et
//! l'interface ouvre le navigateur comme avant.

/// Les seules adresses ouvertes : la page du code d'appareil de Microsoft. La
/// commande vient de l'interface, mais une vue web ouverte sur n'importe
/// quelle adresse serait un navigateur dans le launcher.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn allowed(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("https://") else {
        return false;
    };
    let host = rest.split(['/', '?', '#']).next().unwrap_or_default();
    ["microsoft.com", "live.com"]
        .iter()
        .any(|domain| host == *domain || host.ends_with(&format!(".{domain}")))
}

/// L'adresse avec le code déjà rempli (`?otc=`, que lit microsoft.com/link).
/// Un code qui n'a pas la forme d'un code d'appareil n'est pas ajouté.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn with_code(url: &str, code: &str) -> String {
    if code.is_empty() || !code.chars().all(|c| c.is_ascii_alphanumeric()) || url.contains('?') {
        return url.to_string();
    }
    format!("{url}?otc={code}")
}

/// Ouvre la page dans l'appli. `true` si c'est fait, `false` là où l'appli
/// n'en a pas (ordinateur) : l'interface ouvre alors le navigateur.
#[tauri::command]
pub async fn sign_in_page_open(app: tauri::AppHandle, url: String, code: String, title: String, close: String) -> Result<bool, String> {
    #[cfg(target_os = "android")]
    {
        if !allowed(&url) {
            return Err("adresse de connexion refusée".into());
        }
        let page = with_code(&url, &code);
        tokio::task::spawn_blocking(move || android::open(&app, page, title, close))
            .await
            .map_err(|e| e.to_string())??;
        Ok(true)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, url, code, title, close);
        Ok(false)
    }
}

/// Ferme la page ouverte par [`sign_in_page_open`], si elle l'est encore.
#[tauri::command]
pub async fn sign_in_page_close(app: tauri::AppHandle) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        tokio::task::spawn_blocking(move || android::close(&app)).await.map_err(|e| e.to_string())?
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub mod android {
    use tauri::plugin::{Builder, PluginHandle, TauriPlugin};
    use tauri::{Manager, Runtime};

    pub struct SignInBridge<R: Runtime>(PluginHandle<R>);

    /// Enregistré par `lib.rs`.
    pub fn init<R: Runtime>() -> TauriPlugin<R> {
        Builder::new("yuyu-sign-in")
            .setup(|app, api| {
                let handle = api.register_android_plugin("fr.yuyuframe.launcher", "SignInPlugin")?;
                app.manage(SignInBridge(handle));
                Ok(())
            })
            .build()
    }

    #[derive(serde::Serialize)]
    struct Page {
        url: String,
        title: String,
        close: String,
    }

    pub fn open(app: &tauri::AppHandle, url: String, title: String, close: String) -> Result<(), String> {
        app.state::<SignInBridge<tauri::Wry>>()
            .0
            .run_mobile_plugin::<serde_json::Value>("open", Page { url, title, close })
            .map(drop)
            .map_err(|e| e.to_string())
    }

    pub fn close(app: &tauri::AppHandle) -> Result<(), String> {
        app.state::<SignInBridge<tauri::Wry>>()
            .0
            .run_mobile_plugin::<serde_json::Value>("close", serde_json::json!({}))
            .map(drop)
            .map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seule_la_page_de_microsoft_s_ouvre() {
        assert!(allowed("https://www.microsoft.com/link"));
        assert!(allowed("https://login.live.com/oauth20_remoteconnect.srf"));
        assert!(!allowed("http://www.microsoft.com/link"));
        assert!(!allowed("https://microsoft.com.example.net/link"));
        assert!(!allowed("https://evil-microsoft.com/link"));
    }

    #[test]
    fn le_code_est_rempli_s_il_en_a_la_forme() {
        assert_eq!(with_code("https://www.microsoft.com/link", "AB12CD34"), "https://www.microsoft.com/link?otc=AB12CD34");
        assert_eq!(with_code("https://www.microsoft.com/link", "AB 12"), "https://www.microsoft.com/link");
    }
}
