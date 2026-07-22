use md5::{Digest, Md5};

use crate::{db, state::SharedState};

use super::minecraft::AccountInfo;

/// UUID hors-ligne — convention standard du client vanilla pour les comptes
/// non-premium (`UUID.nameUUIDFromBytes` sur `"OfflinePlayer:<pseudo>"`, MD5
/// avec les bits de version(3)/variant forcés) : les serveurs en mode
/// `online-mode=false` recalculent exactement le même UUID côté serveur.
fn offline_uuid(username: &str) -> String {
    let input = format!("OfflinePlayer:{}", username);
    let mut hash: [u8; 16] = Md5::digest(input.as_bytes()).into();
    hash[6] = (hash[6] & 0x0f) | 0x30;
    hash[8] = (hash[8] & 0x3f) | 0x80;
    let hex: String = hash.iter().map(|b| format!("{:02x}", b)).collect();
    format!("{}-{}-{}-{}-{}", &hex[0..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32])
}

// Pas de vrai token Microsoft à rafraîchir pour un compte hors ligne — une
// expiration très lointaine fait naturellement prendre le chemin "pas besoin
// de refresh" partout où `expires_at` est déjà vérifié (mc_switch,
// auth_status, refresh_if_needed dans launch.rs), sans aucune branche
// spéciale à y ajouter : le seul contournement nécessaire vit ici.
const NEVER_EXPIRES: i64 = 253_402_300_799; // 9999-12-31

/// Ajoute un compte local "hors ligne" (pas d'authentification Microsoft) —
/// utilisable uniquement sur des serveurs en mode `online-mode=false`.
/// Pseudo revalidé ici (mêmes règles que Mojang : 1-16 caractères
/// alphanumériques/underscore) même si le frontend valide déjà.
#[tauri::command]
pub async fn mc_add_offline(
    state: tauri::State<'_, SharedState>,
    username: String,
) -> Result<AccountInfo, String> {
    let username = username.trim().to_string();
    if username.is_empty() || username.len() > 16 || !username.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err("Pseudo invalide (1-16 caractères, lettres/chiffres/_ uniquement)".to_string());
    }

    let uuid = offline_uuid(&username);

    let yuyu_user_id = {
        let s = state.read().await;
        s.current_yuyu_user_id().ok_or("Non authentifié")?
    };

    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::upsert_mc_session(&conn, yuyu_user_id, &username, &uuid, "offline", "", NEVER_EXPIRES)
            .map_err(|e| e.to_string())?;
        db::set_active_mc(&conn, yuyu_user_id, &uuid).map_err(|e| e.to_string())?;
    }

    state.write().await.session = Some(crate::state::MinecraftSession {
        username: username.clone(),
        uuid: uuid.clone(),
        access_token: "offline".to_string(),
        refresh_token: None,
        expires_at: NEVER_EXPIRES,
    });

    crate::integrations::analytics::capture("offline_account_created", serde_json::json!({}));
    Ok(AccountInfo { mc_username: username, mc_uuid: uuid, is_active: true })
}
