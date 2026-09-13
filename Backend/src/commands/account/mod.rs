pub mod microsoft;
pub mod minecraft;
pub mod offline;
pub mod skin;
pub mod yuyu;

use crate::db;
use crate::minecraft::auth::{self, RefreshError};
use crate::state::{MinecraftSession, SharedState};
use minecraft::AccountInfo;

// ── Comptes Minecraft : point unique de gestion des sessions ─────────────────
//
// Les comptes appartiennent au PC (voir db::mc_account) et ne dépendent jamais
// d'une session YuyuFrame. Tout ce qui rend un compte actif ou rafraîchit son
// token passe par les fonctions ci-dessous, pour deux garanties :
// - un seul rafraîchissement Microsoft à la fois (`REFRESH_LOCK`), les tokens
//   étant relus en base sous le verrou : deux appels simultanés (lancement,
//   rafraîchissement périodique, changement de compte) ne partent plus chacun
//   avec le même refresh token ;
// - un rafraîchissement ne remplace la session active que si elle est encore
//   celle du compte rafraîchi : avant, un changement de compte pendant l'appel
//   à Microsoft était écrasé par l'ancien compte.

/// Sous cette durée de validité restante, un token est rafraîchi avant usage.
const REFRESH_MARGIN_SECS: i64 = 1800;

static REFRESH_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn session_from_row(row: &db::McSessionRow) -> MinecraftSession {
    MinecraftSession {
        username: row.mc_username.clone(),
        uuid: row.mc_uuid.clone(),
        access_token: row.access_token.clone(),
        refresh_token: (!row.is_offline).then(|| row.ms_refresh_token.clone()),
        expires_at: row.expires_at,
    }
}

/// Liste des comptes du PC, avec le compte actif marqué.
pub(crate) async fn list_accounts(state: &SharedState) -> Result<Vec<AccountInfo>, String> {
    let s = state.read().await;
    let conn = s.db.lock().await;
    let rows = db::list_mc_sessions(&conn).map_err(|e| e.to_string())?;
    let active_uuid = db::get_active_mc_uuid(&conn).map_err(|e| e.to_string())?;
    Ok(rows
        .into_iter()
        .map(|r| AccountInfo {
            is_active: active_uuid.as_deref() == Some(&r.mc_uuid),
            mc_username: r.mc_username,
            mc_uuid: r.mc_uuid,
            is_offline: r.is_offline,
        })
        .collect())
}

/// Session utilisable pour ce compte, token rafraîchi s'il expire bientôt.
///
/// Microsoft injoignable : l'ancien token est gardé (le jeu solo n'en a pas
/// besoin). Session révoquée alors que le token est déjà expiré : erreur
/// explicite, seule une reconnexion du compte peut la rétablir — lancer quand
/// même donnait « session invalide » sur tout serveur.
async fn fresh_session(state: &SharedState, uuid: &str) -> Result<MinecraftSession, String> {
    let _guard = REFRESH_LOCK.lock().await;
    let row = {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::get_mc_session(&conn, uuid)
            .map_err(|e| e.to_string())?
            .ok_or("Compte Minecraft introuvable")?
    };

    let now = chrono::Utc::now().timestamp();
    if row.is_offline || row.expires_at - now >= REFRESH_MARGIN_SECS {
        return Ok(session_from_row(&row));
    }

    tracing::info!("Rafraîchissement du token Minecraft de {}", row.mc_username);
    match auth::refresh_session(&row.ms_refresh_token).await {
        Ok((access_token, username, _profile_uuid, refresh_token, expires_at)) => {
            {
                let s = state.read().await;
                let conn = s.db.lock().await;
                db::update_mc_tokens(&conn, &row.mc_uuid, &access_token, &refresh_token, expires_at)
                    .map_err(|e| format!("Enregistrement du token rafraîchi impossible : {}", e))?;
            }
            Ok(MinecraftSession {
                username,
                uuid: row.mc_uuid,
                access_token,
                refresh_token: Some(refresh_token),
                expires_at,
            })
        }
        Err(RefreshError::Revoked(code)) if row.expires_at <= now => {
            tracing::warn!("Session Microsoft de {} révoquée ({})", row.mc_username, code);
            Err(format!(
                "La session Microsoft de {} a expiré. Reconnecte ce compte depuis la page Comptes.",
                row.mc_username
            ))
        }
        Err(e) => {
            tracing::warn!("Rafraîchissement du token de {} échoué, token actuel conservé : {}", row.mc_username, e);
            Ok(session_from_row(&row))
        }
    }
}

/// Rend ce compte actif (base + état). Un rafraîchissement impossible ne
/// bloque pas le changement : le compte devient actif avec ses tokens
/// enregistrés, et le lancement signalera la reconnexion nécessaire.
pub(crate) async fn activate_account(state: &SharedState, uuid: &str) -> Result<AccountInfo, String> {
    let row = {
        let s = state.read().await;
        let conn = s.db.lock().await;
        let row = db::get_mc_session(&conn, uuid)
            .map_err(|e| e.to_string())?
            .ok_or("Compte Minecraft introuvable")?;
        db::set_active_mc(&conn, uuid).map_err(|e| e.to_string())?;
        row
    };
    // Actif tout de suite, pour qu'un lancement immédiat n'utilise jamais
    // l'ancien compte pendant le rafraîchissement.
    state.write().await.session = Some(session_from_row(&row));

    let session = match fresh_session(state, uuid).await {
        Ok(session) => session,
        Err(e) => {
            tracing::warn!("Compte {} activé sans token rafraîchi : {}", row.mc_username, e);
            session_from_row(&row)
        }
    };
    let info = AccountInfo {
        mc_username: session.username.clone(),
        mc_uuid: session.uuid.clone(),
        is_active: true,
        is_offline: row.is_offline,
    };
    replace_if_still_active(state, session).await;
    Ok(info)
}

/// Session du compte actif, token rafraîchi si besoin. `None` = aucun compte.
pub(crate) async fn refresh_active(state: &SharedState) -> Result<Option<MinecraftSession>, String> {
    let Some(uuid) = state.read().await.session.as_ref().map(|s| s.uuid.clone()) else {
        return Ok(None);
    };
    let session = fresh_session(state, &uuid).await?;
    replace_if_still_active(state, session.clone()).await;
    Ok(Some(session))
}

async fn replace_if_still_active(state: &SharedState, session: MinecraftSession) {
    let mut w = state.write().await;
    if w.session.as_ref().map(|s| s.uuid == session.uuid).unwrap_or(false) {
        w.session = Some(session);
    }
}

/// Session à restaurer au démarrage : le compte actif enregistré. S'il pointe
/// vers un compte disparu, le premier compte du PC le remplace ; aucun actif
/// enregistré (déconnexion volontaire, voir `auth_logout`) = aucune session.
pub(crate) fn startup_session(conn: &rusqlite::Connection) -> Option<MinecraftSession> {
    let rows = match db::list_mc_sessions(conn) {
        Ok(rows) => rows,
        Err(e) => {
            tracing::error!("Lecture des comptes Minecraft impossible : {}", e);
            return None;
        }
    };
    let active_uuid = match db::get_active_mc_uuid(conn) {
        Ok(Some(uuid)) => uuid,
        Ok(None) => return None,
        Err(e) => {
            tracing::error!("Lecture du compte Minecraft actif impossible : {}", e);
            return None;
        }
    };
    let row = rows
        .iter()
        .find(|r| r.mc_uuid == active_uuid)
        .or_else(|| rows.first())?;
    if row.mc_uuid != active_uuid {
        if let Err(e) = db::set_active_mc(conn, &row.mc_uuid) {
            tracing::error!("Compte actif de repli non enregistré : {}", e);
        }
    }
    tracing::info!("Session Minecraft restaurée pour {}", row.mc_username);
    Some(session_from_row(row))
}
