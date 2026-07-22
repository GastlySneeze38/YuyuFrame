pub mod microsoft;
pub mod minecraft;
pub mod offline;
pub mod yuyu;

/// Persiste en DB le résultat d'un `minecraft::auth::refresh_session` (tuple
/// `(access_token, username, uuid, refresh_token, expires_at)`) et construit
/// la `MinecraftSession` correspondante. Partagé par tous les points qui
/// rafraîchissent un token MC proche de l'expiration (auth_status, mc_switch,
/// yuyu_login, launch_game).
pub(crate) fn apply_refreshed_tokens(
    conn: &rusqlite::Connection,
    yuyu_user_id: i64,
    result: (String, String, String, String, i64),
) -> crate::state::MinecraftSession {
    let (access_token, username, uuid, refresh_token, expires_at) = result;
    crate::db::update_mc_tokens(conn, yuyu_user_id, &uuid, &access_token, &refresh_token, expires_at).ok();
    crate::state::MinecraftSession {
        username,
        uuid,
        access_token,
        refresh_token: Some(refresh_token),
        expires_at,
    }
}
