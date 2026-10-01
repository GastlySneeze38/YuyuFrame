// Catalogue de skins Ely.by — on parcourt, on ne cherche pas.
//
// ── Pourquoi Ely.by ──────────────────────────────────────────────────────────
// Les trois sources de `skin.rs` supposent toutes qu'on sait déjà quel skin on
// veut : le pseudo d'un joueur, une adresse, un fichier. Il manquait l'écran
// d'avant — celui où on regarde ce qui existe. Ely.by tient un catalogue
// public de plusieurs milliers de skins téléversés par ses utilisateurs, et
// l'expose en JSON sans clé ni compte.
//
// Ce qu'il rend tombe exactement sur notre modèle : `skin_url` est une adresse
// publique et durable, `is_slim` est notre modèle. Un élément du catalogue EST
// donc déjà une référence applicable, sans conversion ni stockage — c'est ce
// qui rend cet écran bon marché.
//
// ── Ce que l'API ne fait pas ─────────────────────────────────────────────────
// Il n'y a pas de recherche par nom : les skins n'ont pas de titre, seulement
// des étiquettes. Ce sont elles la recherche. `limit` est figé à 40 par page
// côté serveur, et aucun paramètre de tri n'a d'effet — l'ordre est celui des
// « meilleurs ». L'interface compose avec ça : elle découpe les 40 éléments en
// pages à sa mesure, pour remplir la fenêtre sans défilement.
//
// ── Deux commandes, et pourquoi pas une ──────────────────────────────────────
// Lister est un seul appel JSON, immédiat. Télécharger les aperçus, c'est 40
// images. Les fusionner ferait payer les 40 pour en montrer une douzaine, et
// c'est précisément ce qu'on vient de corriger ailleurs pour les connexions
// lentes. L'interface demande donc la liste d'un coup, puis les aperçus des
// seuls éléments qu'elle affiche.

use futures::stream::StreamExt as _;
use serde::{Deserialize, Serialize};

use super::skin;

const CATALOG_URL: &str = "https://ely.by/api/legacy/skins";

/// Hôte des skins du catalogue.
///
/// Les adresses passent par le frontend entre les deux commandes, et la
/// seconde les retélécharge : sans ce filtre, `skin_catalog_previews`
/// deviendrait un relais HTTP généraliste utilisable pour n'importe quoi. Le
/// catalogue n'a qu'un hébergeur, autant le dire.
const CATALOG_HOST: &str = "https://ely.by/storage/skins/";

/// Aperçus téléchargés de front.
///
/// Quarante requêtes simultanées sur une liaison lente se gênent entre elles
/// et la première image arrive aussi tard que la dernière. Une poignée à la
/// fois remplit la grille de gauche à droite, ce qui se voit.
const PREVIEW_CONCURRENCY: usize = 6;

// ── Ce que rend Ely.by ───────────────────────────────────────────────────────

#[derive(Deserialize)]
struct RawPage {
    items: Vec<RawSkin>,
    /// Numéro de la dernière page. Ely.by plafonne son index à 10 000
    /// résultats, donc 250 pages : ce n'est pas la taille du catalogue, mais
    /// c'est bien la limite de ce qu'on peut parcourir.
    #[serde(default)]
    last: u32,
    #[serde(default)]
    current: u32,
}

#[derive(Deserialize)]
struct RawSkin {
    id: i64,
    skin_url: String,
    #[serde(default)]
    is_slim: bool,
    #[serde(default)]
    color: Option<String>,
    #[serde(default)]
    tags: Vec<String>,
    /// Les « cubes » d'Ely.by, c'est-à-dire les j'aime.
    #[serde(default)]
    count_cubes: i64,
    #[serde(default)]
    count_wearers: i64,
    #[serde(default)]
    count_views_total: i64,
}

// ── Ce qu'on rend au frontend ────────────────────────────────────────────────

#[derive(Serialize, PartialEq, Debug)]
pub struct CatalogSkin {
    pub id: i64,
    /// Adresse publique du PNG — déjà une référence applicable telle quelle.
    pub url: String,
    /// `classic` ou `slim`, au vocabulaire du reste du launcher.
    pub variant: String,
    /// Couleur dominante annoncée par Ely.by, pour teinter la case avant que
    /// l'image arrive. Souvent absente.
    pub color: Option<String>,
    pub tags: Vec<String>,
    pub wearers: i64,
    pub likes: i64,
    pub views: i64,
}

#[derive(Serialize)]
pub struct CatalogPage {
    pub items: Vec<CatalogSkin>,
    pub page: u32,
    pub last_page: u32,
}

// ── Fonctions pures (testées en bas de fichier) ──────────────────────────────

fn parse_catalog_page(raw: RawPage) -> CatalogPage {
    CatalogPage {
        items: raw
            .items
            .into_iter()
            // Une entrée sans adresse utilisable n'est pas une erreur de page :
            // on la laisse de côté et on montre le reste.
            .filter(|s| s.skin_url.starts_with("http"))
            .map(|s| CatalogSkin {
                id: s.id,
                url: s.skin_url,
                variant: if s.is_slim { "slim".to_string() } else { "classic".to_string() },
                color: s.color.filter(|c| !c.is_empty()),
                tags: s.tags,
                wearers: s.count_wearers,
                likes: s.count_cubes,
                views: s.count_views_total,
            })
            .collect(),
        page: raw.current.max(1),
        last_page: raw.last.max(1),
    }
}

/// Paramètres de requête, dans la forme qu'attend Ely.by.
///
/// Les étiquettes se répètent sous la clé `tags[]` ; reqwest l'encode en
/// `tags%5B%5D`, que le serveur accepte. `kind` et les tris existent sur leur
/// site mais restent sans effet sur cette adresse, donc on ne les propose pas.
fn catalog_query(page: u32, tags: &[String], color: Option<&str>, slim_only: bool) -> Vec<(String, String)> {
    let mut query = vec![("page".to_string(), page.max(1).to_string())];
    for tag in tags.iter().filter(|t| !t.trim().is_empty()) {
        query.push(("tags[]".to_string(), tag.trim().to_string()));
    }
    if let Some(color) = color.map(str::trim).filter(|c| !c.is_empty()) {
        query.push(("color".to_string(), color.trim_start_matches('#').to_string()));
    }
    if slim_only {
        query.push(("type".to_string(), "slim".to_string()));
    }
    query
}

fn is_catalog_url(url: &str) -> bool {
    url.starts_with(CATALOG_HOST)
}

// ── Commandes ────────────────────────────────────────────────────────────────

/// Une page du catalogue : les références, sans les images.
#[tauri::command]
pub async fn skin_catalog_browse(
    page: u32,
    tags: Vec<String>,
    color: Option<String>,
    slim_only: bool,
) -> Result<CatalogPage, String> {
    let resp = skin::http()
        .get(CATALOG_URL)
        .query(&catalog_query(page, &tags, color.as_deref(), slim_only))
        .send()
        .await
        .map_err(|e| format!("Catalogue injoignable : {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("Le catalogue a répondu {}", resp.status()));
    }
    let raw: RawPage = resp
        .json()
        .await
        .map_err(|e| format!("Réponse du catalogue illisible : {}", e))?;
    Ok(parse_catalog_page(raw))
}

/// Aperçus des skins affichés, dans l'ordre demandé.
///
/// Un élément vaut `None` quand son image n'a pas pu être obtenue : la case
/// reste à l'écran avec son repli, plutôt que de faire échouer la page entière
/// pour un skin dont l'hébergement a bougé.
#[tauri::command]
pub async fn skin_catalog_previews(urls: Vec<String>) -> Result<Vec<Option<String>>, String> {
    Ok(futures::stream::iter(urls)
        .map(|url| async move {
            if !is_catalog_url(&url) {
                tracing::warn!("Aperçu de catalogue refusé pour une adresse hors catalogue");
                return None;
            }
            match skin::fetch_preview(&url).await {
                Ok(uri) => Some(uri),
                Err(e) => {
                    tracing::warn!("Aperçu de catalogue indisponible : {}", e);
                    None
                }
            }
        })
        // `buffered` et non `buffer_unordered` : l'interface associe les
        // réponses aux cases par leur rang.
        .buffered(PREVIEW_CONCURRENCY)
        .collect()
        .await)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn raw(json: &str) -> RawPage {
        serde_json::from_str(json).expect("réponse de test valide")
    }

    #[test]
    fn traduit_le_modele_et_les_compteurs() {
        // `r##"…"##` et non `r#"…"#` : la couleur contient `"#`, qui fermerait
        // la chaîne brute au milieu du JSON.
        let page = parse_catalog_page(raw(
            r##"{"items":[{"id":42,"skin_url":"https://ely.by/storage/skins/a.png","is_slim":true,
                 "color":"#1F1F1F","tags":["Girl"],"count_cubes":7,"count_wearers":9,
                 "count_views_total":11}],"current":2,"last":250}"##,
        ));
        assert_eq!(
            page.items,
            vec![CatalogSkin {
                id: 42,
                url: "https://ely.by/storage/skins/a.png".to_string(),
                variant: "slim".to_string(),
                color: Some("#1F1F1F".to_string()),
                tags: vec!["Girl".to_string()],
                wearers: 9,
                likes: 7,
                views: 11,
            }]
        );
        assert_eq!(page.page, 2);
        assert_eq!(page.last_page, 250);
    }

    #[test]
    fn un_skin_non_fin_est_classique() {
        let page = parse_catalog_page(raw(
            r#"{"items":[{"id":1,"skin_url":"https://ely.by/storage/skins/a.png","is_slim":false}],
                "current":1,"last":1}"#,
        ));
        assert_eq!(page.items[0].variant, "classic");
    }

    /// Ely.by omet `color` et `tags` sur la plupart des skins : leur absence
    /// est la normale, pas une réponse cassée.
    #[test]
    fn les_champs_absents_ne_cassent_rien() {
        let page = parse_catalog_page(raw(
            r#"{"items":[{"id":1,"skin_url":"https://ely.by/storage/skins/a.png"}],"current":1,"last":3}"#,
        ));
        assert_eq!(page.items[0].color, None);
        assert!(page.items[0].tags.is_empty());
        assert_eq!(page.items[0].likes, 0);
    }

    #[test]
    fn une_entree_sans_adresse_est_ecartee_sans_perdre_la_page() {
        let page = parse_catalog_page(raw(
            r#"{"items":[{"id":1,"skin_url":""},{"id":2,"skin_url":"https://ely.by/storage/skins/b.png"}],
                "current":1,"last":1}"#,
        ));
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items[0].id, 2);
    }

    /// Une page vide ne doit pas annoncer « page 0 sur 0 » à l'interface, qui
    /// compte à partir de 1.
    #[test]
    fn la_pagination_commence_a_un() {
        let page = parse_catalog_page(raw(r#"{"items":[],"current":0,"last":0}"#));
        assert_eq!((page.page, page.last_page), (1, 1));
    }

    #[test]
    fn les_etiquettes_se_repetent_sous_la_meme_cle() {
        let query = catalog_query(3, &["Girl".to_string(), " Dark ".to_string()], None, false);
        assert_eq!(
            query,
            vec![
                ("page".to_string(), "3".to_string()),
                ("tags[]".to_string(), "Girl".to_string()),
                ("tags[]".to_string(), "Dark".to_string()),
            ]
        );
    }

    /// Le sélecteur de couleur rend `#1F1F1F`, Ely.by attend `1F1F1F`.
    #[test]
    fn la_couleur_perd_son_diese() {
        let query = catalog_query(1, &[], Some("#1F1F1F"), true);
        assert!(query.contains(&("color".to_string(), "1F1F1F".to_string())));
        assert!(query.contains(&("type".to_string(), "slim".to_string())));
    }

    #[test]
    fn une_etiquette_vide_n_est_pas_envoyee() {
        let query = catalog_query(1, &["  ".to_string()], Some("  "), false);
        assert_eq!(query, vec![("page".to_string(), "1".to_string())]);
    }

    #[test]
    fn la_page_zero_devient_la_premiere() {
        assert_eq!(catalog_query(0, &[], None, false)[0].1, "1");
    }

    /// Les adresses font l'aller-retour par le frontend : seules celles du
    /// catalogue reviennent, sinon la commande serait un relais ouvert.
    #[test]
    fn seules_les_adresses_du_catalogue_sont_acceptees() {
        assert!(is_catalog_url("https://ely.by/storage/skins/a.png"));
        assert!(!is_catalog_url("http://ely.by/storage/skins/a.png"));
        assert!(!is_catalog_url("https://exemple.test/a.png"));
        assert!(!is_catalog_url("file:///C:/secret.png"));
    }
}
