//! Ce qui décrit cet appareil au serveur : son nom (liste des appareils du
//! compte, alertes « nouvel appareil ») et la source de l'empreinte
//! matérielle de l'anti-alt (`security::hardware`).
//!
//! **Téléphone (2026-11-10)** : le nom venait de `COMPUTERNAME`/`HOSTNAME`,
//! deux variables qui n'existent que sur ordinateur — un téléphone se
//! présentait « PC » ; et l'empreinte se lisait dans `/etc/machine-id`, absent
//! d'Android — un téléphone n'en envoyait aucune, l'anti-alt ne le voyait pas.
//! Les deux viennent maintenant d'Android lui-même, par la commande `device`
//! du plugin Kotlin `GamePlugin` : le nom choisi dans les réglages (sinon
//! fabricant + modèle) et `ANDROID_ID`. Demandés une fois, au démarrage
//! (`fetch_android`) ; un appel qui part avant la réponse dit « Android » et
//! n'a pas d'empreinte, le suivant (15 minutes au plus) est complet.

#[cfg(target_os = "android")]
use std::sync::OnceLock;

/// La réponse du plugin Kotlin.
#[cfg(target_os = "android")]
#[derive(Clone, Debug, Default, serde::Deserialize)]
pub struct AndroidDevice {
    /// Le nom choisi dans les réglages, sinon fabricant + modèle.
    pub name: String,
    /// Fabricant + modèle (« Samsung SM-A325F »).
    pub model: String,
    /// ANDROID_ID, brut : jamais envoyé tel quel (`security::hardware`).
    pub id: String,
}

#[cfg(target_os = "android")]
static ANDROID: OnceLock<AndroidDevice> = OnceLock::new();

/// Demande au téléphone de se décrire, une fois, en tâche de fond.
#[cfg(target_os = "android")]
pub fn fetch_android(app: tauri::AppHandle) {
    tauri::async_runtime::spawn(async move {
        match crate::minecraft::launcher::horizon::android::device(&app).await {
            Ok(device) => {
                tracing::info!("appareil : {} ({})", device.name, device.model);
                let _ = ANDROID.set(device);
            }
            Err(e) => tracing::warn!("description du téléphone impossible : {e}"),
        }
    });
}

/// Le nom de cet appareil, tel que le compte le montre.
pub fn name() -> String {
    #[cfg(target_os = "android")]
    {
        ANDROID.get().map(|d| d.name.clone()).filter(|n| !n.trim().is_empty()).unwrap_or_else(|| "Android".into())
    }
    #[cfg(not(target_os = "android"))]
    {
        std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_else(|_| "PC".into())
    }
}

/// ANDROID_ID, s'il est déjà connu.
#[cfg(target_os = "android")]
pub fn android_id() -> Option<String> {
    ANDROID.get().map(|d| d.id.trim().to_string()).filter(|id| !id.is_empty())
}

/// Le système, pour un rapport (diagnostic d'un ticket) : « Android 13 ·
/// Samsung SM-A325F » sur téléphone — `sysinfo` y rend le code du modèle à la
/// place du nom du système.
pub fn os_label() -> String {
    let version = sysinfo::System::os_version().unwrap_or_else(|| "?".into());
    #[cfg(target_os = "android")]
    {
        let model = ANDROID.get().map(|d| d.model.clone()).unwrap_or_default();
        if model.is_empty() { format!("Android {version}") } else { format!("Android {version} · {model}") }
    }
    #[cfg(not(target_os = "android"))]
    {
        format!("{} {version}", sysinfo::System::name().unwrap_or_else(|| "?".into()))
    }
}
