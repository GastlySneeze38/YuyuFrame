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

/// Vrai si une étiquette du skin tombe sous la liste — voir
/// `BLOCKED_TAG_TERMS`.
fn is_blocked(tags: &[String]) -> bool {
    tags.iter().any(|tag| {
        let tag = tag.to_lowercase();
        BLOCKED_TAG_TERMS.iter().any(|term| tag.contains(term))
    })
}

/// `block` applique `BLOCKED_TAG_TERMS`. Il suit le réglage de modération :
/// demander à voir le contenu sensible lève **les deux** filtres, le nôtre et
/// celui d'Ely.by — un seul interrupteur, pas deux niveaux à deviner.
///
/// Les entrées écartées le sont ici, donc une page peut rendre moins de 40
/// éléments. L'interface ne suppose pas de taille fixe : elle accumule les
/// lots et y découpe ses pages, justement pour que ce filtre ne laisse pas de
/// trous à l'écran.
fn parse_catalog_page(raw: RawPage, block: bool) -> CatalogPage {
    CatalogPage {
        items: raw
            .items
            .into_iter()
            // Une entrée sans adresse utilisable n'est pas une erreur de page :
            // on la laisse de côté et on montre le reste.
            .filter(|s| s.skin_url.starts_with("http"))
            .filter(|s| !block || !is_blocked(&s.tags))
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

/// Termes qui écartent un skin, cherchés dans ses étiquettes.
///
/// ── Pourquoi une liste à nous ────────────────────────────────────────────
/// `showSensitiveContent` est le signalement d'Ely.by, et il ne couvre pas
/// tout : le skin le plus en vue du genre, étiqueté `adolf, pornhub, wtf,
/// WW2`, arrivait en tête de la première page sans être signalé. Cette liste
/// est donc un second filtre, pas un doublon.
///
/// ── Ce qu'elle ne fait pas ───────────────────────────────────────────────
/// Elle ne juge que les étiquettes déclarées par celui qui a téléversé, et
/// **35 % des entrées n'en ont aucune** (mesuré sur 200). Un skin qui
/// représente la même chose sans le dire passe donc. C'est un filtre qui
/// réduit, jamais une garantie — et la grille n'étant pas la nôtre, il n'y en
/// a pas d'autre possible.
///
/// ── Choix des termes ─────────────────────────────────────────────────────
/// Russes et anglais, le catalogue étant surtout russophone, et assez longs
/// pour ne pas mordre ailleurs : chercher `ss` écartait « zenless zone zero »
/// et « Simon Henriksson », mesuré sur un échantillon réel. `sex` et `hub`
/// ont été vérifiés sur 400 entrées sans un seul faux positif. Les termes
/// sont comparés en minuscules, sous-chaîne comprise, pour attraper les
/// déclinaisons : `наци` couvre `нацист`, `sex` couvre `Sexy`, `porn` couvre
/// `pornhub`.
const BLOCKED_TAG_TERMS: [&str; 28] = [
    // Nazisme. `adolf` à côté de `hitler` : l'entrée la plus populaire du
    // genre n'était étiquetée que du prénom.
    "hitler",
    "гитлер",
    "adolf",
    "адольф",
    "nazi",
    "наци",
    "wehrmacht",
    "вермахт",
    "reich",
    "рейх",
    "swastika",
    "свастик",
    "fuhrer",
    "führer",
    "фюрер",
    // Contenu pour adultes.
    "porn",
    "порно",
    "nsfw",
    "18+",
    "sex",
    "секс",
    "hentai",
    "хентай",
    "nude",
    "naked",
    "boobs",
    "onlyfans",
    // Injures.
    "негр",
];

/// Formats acceptés par Ely.by.
///
/// Leurs quatre options sont EXCLUSIVES, et `new` ne rend que des bras
/// classiques — vérifié, 40 sur 40, sur plusieurs pages. C'est ce qui permet
/// d'offrir un vrai choix classique/fin plutôt qu'une simple case « fin
/// seulement ».
///
/// Il n'existe en revanche aucune valeur couvrant les deux formats classiques
/// à la fois : `new` laisse de côté les 64×32 d'origine, une poignée de
/// téléversements très anciens. C'est le prix d'un choix à trois entrées.
const FORMATS: [&str; 3] = ["old", "new", "slim"];

/// Catégories d'Ely.by, telles que leur propre filtre les orthographie — la
/// casse compte : `kind=fantasy` est ignoré là où `kind=Fantasy` filtre.
const KINDS: [&str; 10] = [
    "Comics",
    "Adventure",
    "Heroes",
    "Evildoers",
    "Weekend",
    "Characters",
    "Historical",
    "Fantasy",
    "Scientific",
    "Other",
];

/// Paramètres de requête, dans la forme qu'attend Ely.by.
///
/// Les étiquettes se répètent sous la clé `tags[]` ; reqwest l'encode en
/// `tags%5B%5D`, que le serveur accepte. Format et catégorie sont filtrés sur
/// les valeurs connues : une valeur inventée serait ignorée par Ely.by, et
/// l'interface montrerait alors un filtre actif qui ne filtre rien.
fn catalog_query(
    page: u32,
    tags: &[String],
    color: Option<&str>,
    format: Option<&str>,
    kind: Option<&str>,
    show_sensitive: bool,
) -> Vec<(String, String)> {
    let mut query = vec![
        ("page".to_string(), page.max(1).to_string()),
        // Modération d'Ely.by, écrite même quand elle vaut leur défaut :
        // dépendre d'un défaut, c'est accepter qu'il change un jour sans nous
        // prévenir. L'activer ajoute 8 à 26 entrées par page de 40, mesuré.
        //
        // C'est LEUR signalement, et il ne couvre pas tout — d'où
        // `BLOCKED_TAG_TERMS`, qui s'applique en plus et suit le même réglage.
        (
            "showSensitiveContent".to_string(),
            if show_sensitive { "1" } else { "0" }.to_string(),
        ),
    ];
    for tag in tags.iter().filter(|t| !t.trim().is_empty()) {
        query.push(("tags[]".to_string(), tag.trim().to_string()));
    }
    if let Some(color) = color.map(str::trim).filter(|c| !c.is_empty()) {
        query.push(("color".to_string(), color.trim_start_matches('#').to_string()));
    }
    if let Some(format) = format.map(str::trim).filter(|f| FORMATS.contains(f)) {
        query.push(("type".to_string(), format.to_string()));
    }
    if let Some(kind) = kind.map(str::trim).filter(|k| KINDS.contains(k)) {
        query.push(("kind".to_string(), kind.to_string()));
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
    // `format` : `old`, `new` (= bras classiques) ou `slim` ; rien = tous.
    // `kind` : une des catégories d'Ely.by ; rien = toutes.
    // `show_sensitive` : lève les deux filtres à la fois, celui d'Ely.by et
    // le nôtre. Un seul interrupteur : afficher le contenu sensible ne
    // laisserait aucun sens à une liste qui continuerait d'en retirer.
    format: Option<String>,
    kind: Option<String>,
    show_sensitive: bool,
) -> Result<CatalogPage, String> {
    let resp = skin::http()
        .get(CATALOG_URL)
        .query(&catalog_query(
            page,
            &tags,
            color.as_deref(),
            format.as_deref(),
            kind.as_deref(),
            show_sensitive,
        ))
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
    Ok(parse_catalog_page(raw, !show_sensitive))
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

    /// Les valeurs envoyées sous une clé donnée — plus lisible que comparer la
    /// requête entière, qui porte aussi `page` et la modération.
    fn values(query: &[(String, String)], key: &str) -> Vec<String> {
        query.iter().filter(|(k, _)| k == key).map(|(_, v)| v.clone()).collect()
    }

    fn tagged(query: &[(String, String)]) -> Vec<String> {
        values(query, "tags[]")
    }

    #[test]
    fn traduit_le_modele_et_les_compteurs() {
        // `r##"…"##` et non `r#"…"#` : la couleur contient `"#`, qui fermerait
        // la chaîne brute au milieu du JSON.
        let page = parse_catalog_page(raw(
            r##"{"items":[{"id":42,"skin_url":"https://ely.by/storage/skins/a.png","is_slim":true,
                 "color":"#1F1F1F","tags":["Girl"],"count_cubes":7,"count_wearers":9,
                 "count_views_total":11}],"current":2,"last":250}"##,
        ), true);
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
        ), true);
        assert_eq!(page.items[0].variant, "classic");
    }

    /// Ely.by omet `color` et `tags` sur la plupart des skins : leur absence
    /// est la normale, pas une réponse cassée.
    #[test]
    fn les_champs_absents_ne_cassent_rien() {
        let page = parse_catalog_page(raw(
            r#"{"items":[{"id":1,"skin_url":"https://ely.by/storage/skins/a.png"}],"current":1,"last":3}"#,
        ), true);
        assert_eq!(page.items[0].color, None);
        assert!(page.items[0].tags.is_empty());
        assert_eq!(page.items[0].likes, 0);
    }

    #[test]
    fn une_entree_sans_adresse_est_ecartee_sans_perdre_la_page() {
        let page = parse_catalog_page(raw(
            r#"{"items":[{"id":1,"skin_url":""},{"id":2,"skin_url":"https://ely.by/storage/skins/b.png"}],
                "current":1,"last":1}"#,
        ), true);
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items[0].id, 2);
    }

    /// Une page vide ne doit pas annoncer « page 0 sur 0 » à l'interface, qui
    /// compte à partir de 1.
    #[test]
    fn la_pagination_commence_a_un() {
        let page = parse_catalog_page(raw(r#"{"items":[],"current":0,"last":0}"#), true);
        assert_eq!((page.page, page.last_page), (1, 1));
    }

    #[test]
    fn les_etiquettes_se_repetent_sous_la_meme_cle() {
        let query = catalog_query(3, &["Girl".to_string(), " Dark ".to_string()], None, None, None, false);
        assert_eq!(
            tagged(&query),
            vec!["Girl".to_string(), "Dark".to_string()],
        );
    }

    /// Le sélecteur de couleur rend `#1F1F1F`, Ely.by attend `1F1F1F`.
    #[test]
    fn la_couleur_perd_son_diese() {
        let query = catalog_query(1, &[], Some("#1F1F1F"), Some("slim"), None, false);
        assert!(query.contains(&("color".to_string(), "1F1F1F".to_string())));
        assert!(query.contains(&("type".to_string(), "slim".to_string())));
    }

    /// « Classique » n'est pas une valeur d'Ely.by : c'est `new`, leur format
    /// 64×64, qui ne contient que des bras classiques.
    #[test]
    fn le_format_classique_est_new() {
        let query = catalog_query(1, &[], None, Some("new"), None, false);
        assert!(query.contains(&("type".to_string(), "new".to_string())));
    }

    /// Une valeur inventée serait ignorée par Ely.by : l'interface afficherait
    /// un filtre actif qui ne filtre rien. Mieux vaut ne rien envoyer.
    #[test]
    fn un_format_inconnu_n_est_pas_envoye() {
        let query = catalog_query(1, &[], None, Some("classic"), None, false);
        assert!(values(&query, "type").is_empty());
    }

    #[test]
    fn la_categorie_part_telle_quelle() {
        let query = catalog_query(1, &[], None, None, Some("Fantasy"), false);
        assert!(query.contains(&("kind".to_string(), "Fantasy".to_string())));
    }

    /// La casse compte chez Ely.by : `fantasy` est ignoré, `Fantasy` filtre.
    #[test]
    fn une_categorie_mal_capitalisee_n_est_pas_envoyee() {
        let query = catalog_query(1, &[], None, None, Some("fantasy"), false);
        assert!(values(&query, "kind").is_empty());
    }

    #[test]
    fn une_etiquette_vide_n_est_pas_envoyee() {
        let query = catalog_query(1, &["  ".to_string()], Some("  "), None, None, false);
        assert!(tagged(&query).is_empty());
        assert!(values(&query, "color").is_empty());
    }

    #[test]
    fn la_page_zero_devient_la_premiere() {
        assert_eq!(catalog_query(0, &[], None, None, None, false)[0].1, "1");
    }

    /// Le réglage de modération part sur CHAQUE requête, quels que soient les
    /// autres filtres : c'est un défaut d'Ely.by qu'on refuse de subir.
    #[test]
    fn la_moderation_est_toujours_declaree() {
        let hidden = ("showSensitiveContent".to_string(), "0".to_string());
        assert!(catalog_query(1, &[], None, None, None, false).contains(&hidden));
        assert!(
            catalog_query(7, &["Girl".to_string()], Some("#000"), Some("slim"), Some("Fantasy"), false)
                .contains(&hidden)
        );
        assert!(catalog_query(1, &[], None, None, None, true)
            .contains(&("showSensitiveContent".to_string(), "1".to_string())));
    }

    /// Notre liste écarte ce que le signalement d'Ely.by laisse passer : six
    /// skins étiquetés « Adolf Hitler » sur cinq pages, mesuré.
    #[test]
    fn les_etiquettes_bannies_ecartent_le_skin() {
        // Toutes vues telles quelles dans le catalogue.
        for tag in [
            "Adolf Hitler", "adolf", "nazi", "Гитлер", "Третий рейх", "негр",
            "pornhub", "porn", "NSFW", "18+", "Sexy", "sex", "сексуальный", "Hentai",
            "Nude", "naked", "boobs",
        ] {
            assert!(is_blocked(&[tag.to_string()]), "{} aurait dû être écarté", tag);
        }
    }

    /// Chercher des sous-chaînes trop courtes mord ailleurs : `ss` écartait
    /// « zenless zone zero » et « Simon Henriksson », vus dans un échantillon
    /// réel. `sex` et `hub` ont été vérifiés sur 400 entrées sans faux
    /// positif. Ce test garde la liste honnête si quelqu'un l'étoffe.
    #[test]
    fn les_etiquettes_ordinaires_passent() {
        for tag in [
            "zenless zone zero", "mindless self indulgence", "Simon Henriksson",
            "Girl", "Cute", "black suit", "WW2", "Historical",
        ] {
            assert!(!is_blocked(&[tag.to_string()]), "{} n'aurait pas dû être écarté", tag);
        }
    }

    /// Une seule étiquette suffit. Le cas est réel : l'entrée la plus en vue
    /// du genre portait `adolf, pornhub, wtf, WW2`, et passait tant que la
    /// liste n'avait que `hitler`.
    #[test]
    fn une_seule_etiquette_bannie_suffit() {
        let page = parse_catalog_page(
            raw(
                r#"{"items":[
                 {"id":1,"skin_url":"https://ely.by/storage/skins/a.png","tags":["adolf","pornhub","wtf","WW2"]},
                 {"id":2,"skin_url":"https://ely.by/storage/skins/b.png","tags":["WW2"]}
               ],"current":1,"last":1}"#,
            ),
            true,
        );
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items[0].id, 2);
    }

    /// Demander le contenu sensible lève les DEUX filtres : garder le nôtre
    /// actif ferait un bouton qui ne tient qu'à moitié sa promesse.
    #[test]
    fn afficher_le_contenu_sensible_leve_aussi_notre_liste() {
        let json = r#"{"items":[
                 {"id":1,"skin_url":"https://ely.by/storage/skins/a.png","tags":["adolf","pornhub"]},
                 {"id":2,"skin_url":"https://ely.by/storage/skins/b.png","tags":["WW2"]}
               ],"current":1,"last":1}"#;
        assert_eq!(parse_catalog_page(raw(json), true).items.len(), 1);
        assert_eq!(parse_catalog_page(raw(json), false).items.len(), 2);
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
