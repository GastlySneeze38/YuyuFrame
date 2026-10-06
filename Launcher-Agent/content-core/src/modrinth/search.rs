use super::urlencoding;

/// Recherche de resource packs/shaders sur Modrinth, avec filtres optionnels
/// (écran de filtres côté Java, voir ModrinthContentScreen) — chaque facet
/// Modrinth est un groupe `[...]` : les valeurs À L'INTÉRIEUR d'un groupe sont
/// en OU, les groupes entre eux sont en ET. `categories` est donc mis un
/// groupe PAR catégorie sélectionnée (ET entre catégories — cohérent avec un
/// filtre qui "affine"/réduit les résultats, pas qui les élargit).
///
/// `categories`/`version`/`sort_index` vides = aucun filtre / tri par défaut
/// (comportement inchangé par rapport à l'ancienne signature 2 paramètres).
///
/// `offset`/`limit` : pagination côté Modrinth (la réponse porte `total_hits`,
/// dont l'écran déduit le nombre de pages). `limit` est borné à 1..=100, le
/// maximum accepté par l'API.
pub fn search_modrinth(
    query: &str,
    project_type: &str,
    categories: &[String],
    version: &str,
    sort_index: &str,
    offset: u32,
    limit: u32,
) -> Result<String, String> {
    let mut facet_groups: Vec<String> = vec![format!("[\"project_type:{}\"]", project_type)];
    if !version.trim().is_empty() {
        facet_groups.push(format!("[\"versions:{}\"]", version.trim()));
    }
    for cat in categories {
        let cat = cat.trim();
        if !cat.is_empty() {
            facet_groups.push(format!("[\"categories:{}\"]", cat));
        }
    }
    let facets = format!("[{}]", facet_groups.join(","));

    // Requête vide = recommandations à l'ouverture de l'écran : trier par
    // popularité plutôt que "relevance" (mal défini sans terme de recherche).
    // Un index explicite (choisi dans l'écran de filtres) prime toujours sur
    // ce repli automatique.
    let index = if !sort_index.trim().is_empty() {
        sort_index.trim()
    } else if query.trim().is_empty() {
        "downloads"
    } else {
        "relevance"
    };
    let url = format!(
        "https://api.modrinth.com/v2/search?query={}&facets={}&index={}&limit={}&offset={}",
        urlencoding(query),
        urlencoding(&facets),
        index,
        limit.clamp(1, 100),
        offset
    );

    let response = ureq::get(&url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("requête Modrinth échouée : {e}"))?;

    response
        .into_string()
        .map_err(|e| format!("lecture réponse Modrinth échouée : {e}"))
}
