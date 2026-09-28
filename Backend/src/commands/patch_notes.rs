//! Notes de version, lues chez le site.
//!
//! ── Pourquoi le site et pas la configuration de flotte ────────────────────
//! Le launcher affichait jusqu'ici une annonce de flotte à l'emplacement
//! `modal`, créée dans Plateforme → Bannières. Il y avait donc **deux**
//! endroits nommés « notes de version » dans le back-office : celui-là, et
//! Contenu → Patch notes, qui alimente le site et l'annonce Discord. Publier
//! dans le second — le réflexe naturel — n'affichait rien dans le launcher.
//!
//! Une seule source désormais : la table `patch_notes` du site, servie par
//! `GET /api/patch-notes`. La même note part sur le site, sur Discord et dans
//! le launcher, écrite une fois.
//!
//! ── Pourquoi passer par le site et non par LauncherAPI ────────────────────
//! Les patch notes vivent dans la base du site, que LauncherAPI ne lit pas
//! (elle n'y a qu'un rôle restreint pour les avis). Ajouter une route qui
//! relaie aurait demandé un droit de plus sur cette base, pour recopier une
//! liste déjà publique, déjà en cache 60 s et déjà servie à tout le monde.
//!
//! Aucune authentification, aucune donnée envoyée : c'est la même requête que
//! celle du site. Un échec est silencieux — pas de note affichée, rien de
//! cassé.

use serde::{Deserialize, Serialize};

const TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

/// Site public. Surchargeable pour pointer un site de test, comme
/// `YUYU_API_URL` le fait pour LauncherAPI.
fn site_base() -> String {
    std::env::var("YUYU_SITE_URL").unwrap_or_else(|_| "https://yuyuframe.eu".into())
}

/// Une note telle que le site la sert. Les champs qu'on n'utilise pas sont
/// simplement ignorés par serde.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatchNote {
    pub id: String,
    pub version: String,
    pub title: String,
    /// Markdown, le même que celui affiché sur le site.
    pub body: String,
    /// ISO 8601 — affiché dans l'en-tête de la modale, comme sur le site.
    pub published_at: String,
}

/// Combien de versions l'écran Support propose de relire. Au-delà, c'est de
/// l'archéologie : le site garde l'historique complet.
const HISTORY: &str = "30";

/// Les notes les plus récentes d'abord. Liste vide si le site n'a pas
/// répondu — un écran sans historique vaut mieux qu'un écran en erreur.
async fn fetch(limit: &str) -> Vec<PatchNote> {
    let client = match reqwest::Client::builder().timeout(TIMEOUT).build() {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("client HTTP indisponible pour les notes de version : {e}");
            return Vec::new();
        }
    };

    let url = format!("{}/api/patch-notes", site_base());
    match client.get(&url).query(&[("limit", limit)]).send().await {
        Ok(resp) => match resp.json().await {
            Ok(notes) => notes,
            Err(e) => {
                tracing::warn!("notes de version illisibles : {e}");
                Vec::new()
            }
        },
        Err(e) => {
            // Hors ligne, site en maintenance : sans intérêt à signaler.
            tracing::debug!("notes de version non chargées : {e}");
            Vec::new()
        }
    }
}

/// La note la plus récente, ou `None` s'il n'y en a pas.
///
/// La plus récente seulement : quelqu'un qui revient après trois versions n'a
/// pas besoin de fermer trois fenêtres au démarrage, et c'est la dernière qui
/// décrit le launcher qu'il vient d'ouvrir. C'est l'interface qui retient
/// celles déjà lues (voir `useModalQueue`), pas cette commande.
#[tauri::command]
pub async fn patch_notes_latest() -> Result<Option<PatchNote>, String> {
    Ok(fetch("1").await.into_iter().next())
}

/// L'historique, pour l'onglet « Notes de version » de l'écran Support.
#[tauri::command]
pub async fn patch_notes_list() -> Result<Vec<PatchNote>, String> {
    Ok(fetch(HISTORY).await)
}
