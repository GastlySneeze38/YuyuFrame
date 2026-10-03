//! Garde de plan — la décision d'ouvrir une page payante appartient au Rust.
//!
//! Le frontend connaît déjà le plan de la session (`useStore.isPremium`), mais
//! ce n'est qu'un état de rendu : il suffit de l'éditer dans les outils de
//! développement pour se donner l'abonnement. La vraie réponse vient d'ici,
//! où vivent la session et la licence signée (`crate::security::license`), et de
//! toute façon chaque commande payante refait le contrôle de son côté.

use crate::state::SharedState;

/// Plan exigé par chaque fonctionnalité fermée. Une clé absente est ouverte :
/// une page ajoutée sans être inscrite ici reste accessible plutôt que de
/// devenir silencieusement introuvable.
fn required_plan(feature: &str) -> Option<&'static str> {
    match feature {
        "sync" | "backup" => Some("premium"),
        _ => None,
    }
}

/// Renvoie `Ok` si la session en cours a le droit d'ouvrir `feature`, sinon
/// une erreur `plan_required` portant le plan attendu (`extra.required_plan`).
#[tauri::command]
pub async fn plan_guard(
    feature: String,
    state: tauri::State<'_, SharedState>,
) -> Result<(), String> {
    let Some(plan) = required_plan(&feature) else { return Ok(()) };
    let s = state.read().await;
    s.require_plan(plan).map_err(String::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_features_stay_open() {
        assert_eq!(required_plan("sync"), Some("premium"));
        assert_eq!(required_plan("backup"), Some("premium"));
        assert_eq!(required_plan("stats"), None);
        assert_eq!(required_plan("jvm"), None);
    }
}
