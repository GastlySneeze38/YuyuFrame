//! Pays d'où l'on se connecte, pour choisir la langue au tout premier
//! démarrage.
//!
//! ── Pourquoi l'adresse IP ─────────────────────────────────────────────────
//! La langue du système reste le meilleur indice — elle est locale, instantanée
//! et ne dit rien à personne — mais elle se trompe souvent : un Windows acheté
//! en anglais, une machine de récupération, un compte d'entreprise. Le pays
//! d'où l'on se connecte tranche ces cas-là, et c'est le seul moment où on le
//! demande : la valeur n'est jamais stockée, jamais renvoyée au serveur, et
//! une fois la langue choisie la question ne se repose plus (voir
//! `useStore.languagePicked` côté interface).
//!
//! La réponse vient de `cdn-cgi/trace`, un point d'entrée public de Cloudflare
//! qui rend quelques lignes `clé=valeur` dont `loc=FR`. Aucune clé d'API,
//! aucun compte, et surtout **aucun service tiers de plus dans l'histoire** :
//! Cloudflare voit déjà passer une bonne partie du trafic du launcher. On ne
//! lui envoie rien d'autre que la requête elle-même.
//!
//! Tout échec est silencieux : sans réseau au premier lancement, l'interface
//! retombe sur la langue du système, et retentera au démarrage suivant tant
//! que l'utilisateur n'a pas choisi lui-même.

/// Délai volontairement court : c'est un confort au démarrage, pas une étape
/// de lancement. Passé ce temps, l'interface a déjà sa langue système et n'a
/// plus besoin de la réponse.
const TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

const TRACE_URL: &str = "https://www.cloudflare.com/cdn-cgi/trace";

// ── Langue de l'interface, pour les e-mails du compte ────────────────────
// La langue vit dans le magasin du frontend ; le Rust ne la connaît que parce
// que l'interface la lui donne au démarrage et à chaque changement. Elle part
// avec les informations d'appareil (`server::device_info`) à la connexion et
// à chaque rafraîchissement, et LauncherAPI en fait la langue des e-mails du
// compte. En mémoire seulement : l'interface la redonne à chaque démarrage.

static UI_LANGUAGE: std::sync::RwLock<Option<String>> = std::sync::RwLock::new(None);

/// La langue de l'interface, ou rien tant qu'elle ne l'a pas donnée.
pub fn ui_language() -> Option<String> {
    UI_LANGUAGE.read().ok()?.clone()
}

/// La langue cachée (écriture de la table d'enchantement) se lit en
/// français : c'est de lui qu'elle est calculée.
fn normalize_language(language: &str) -> Option<String> {
    let code = language.trim().to_ascii_lowercase();
    let code = if code == "sga" { "fr".to_owned() } else { code };
    (code.len() == 2 && code.bytes().all(|b| b.is_ascii_lowercase())).then_some(code)
}

#[tauri::command]
pub fn set_ui_language(language: String) {
    if let Ok(mut slot) = UI_LANGUAGE.write() {
        *slot = normalize_language(&language);
    }
}

/// Extrait le code pays de la réponse `cdn-cgi/trace`.
///
/// `loc=XX` en majuscules, deux lettres. `loc=XX` peut valoir `XX` (inconnu)
/// quand Cloudflare ne sait pas situer l'adresse : traité comme une absence
/// de réponse, pas comme un pays.
fn parse_country(body: &str) -> Option<String> {
    let code = body
        .lines()
        .find_map(|line| line.strip_prefix("loc="))?
        .trim()
        .to_ascii_uppercase();
    let valid = code.len() == 2 && code.chars().all(|c| c.is_ascii_uppercase()) && code != "XX";
    valid.then_some(code)
}

/// Code pays ISO à deux lettres, ou `None` si la question n'a pas pu être
/// posée. Appelée une seule fois, au premier démarrage.
#[tauri::command]
pub async fn detect_country() -> Option<String> {
    let client = reqwest::Client::builder().timeout(TIMEOUT).build().ok()?;
    let body = client.get(TRACE_URL).send().await.ok()?.text().await.ok()?;
    let country = parse_country(&body);
    match &country {
        Some(code) => tracing::info!("Pays détecté pour la langue : {}", code),
        None => tracing::info!("Pays indéterminé — la langue du système fera foi"),
    }
    country
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "fl=123abc\nh=www.cloudflare.com\nip=1.2.3.4\nts=1700000000\nloc=FR\ntls=TLSv1.3\n";

    #[test]
    fn lit_le_pays_au_milieu_des_autres_lignes() {
        assert_eq!(parse_country(SAMPLE).as_deref(), Some("FR"));
    }

    #[test]
    fn un_pays_inconnu_ne_vaut_pas_un_pays() {
        // Cloudflare rend littéralement « XX » quand il ne sait pas situer
        // l'adresse — le prendre pour un pays choisirait une langue au hasard.
        assert_eq!(parse_country("loc=XX\n"), None);
    }

    #[test]
    fn la_langue_envoyee_au_serveur_est_un_code_simple() {
        assert_eq!(normalize_language("fr").as_deref(), Some("fr"));
        assert_eq!(normalize_language(" PT ").as_deref(), Some("pt"));
        assert_eq!(normalize_language("sga").as_deref(), Some("fr"), "la langue cachée s'écrit en français");
        assert_eq!(normalize_language("fr-FR"), None);
        assert_eq!(normalize_language(""), None);
    }

    #[test]
    fn une_reponse_sans_pays_ne_rend_rien() {
        assert_eq!(parse_country("ip=1.2.3.4\nts=1700000000\n"), None);
    }
}
