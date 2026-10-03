//! Inventaire + empreintes + recherche, tels que l'onglet « Partager » les montre.

use super::*;

/// Inventaire + empreintes + recherche. Partagé par l'aperçu et l'export, qui
/// doivent voir exactement la même chose.
pub(super) struct Scanned {
    pub(super) instance: Instance,
    pub(super) entries: Vec<(Entry, Option<String>)>,
    pub(super) lookup_failed: bool,
    /// Configuration Java déjà filtrée, et ce que le filtre a retiré.
    pub(super) jvm: JvmShare,
    pub(super) jvm_rejected: Vec<String>,
    /// Nom de la config JVM reliée, s'il y en a une.
    pub(super) jvm_profile: Option<String>,
    /// Options du client intégré, déjà filtrées (ni mots de passe ni macros).
    pub(super) client_options: Vec<McOption>,
}

pub(super) async fn scan(state: &tauri::State<'_, SharedState>, instance_id: &str) -> Result<Scanned, String> {
    let (instance, profile) = {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        let instance = db::instance_get(&db, instance_id, uid)
            .map_err(|e| e.to_string())?
            .map(row_to_instance)
            .ok_or("Instance introuvable")?;
        // Une config supprimée laisse un id orphelin : comme au lancement, on
        // retombe sur les réglages de l'instance.
        let profile = instance
            .jvm_profile_id
            .as_deref()
            .and_then(|id| db::jvm_profile_get(&db, id, uid).ok().flatten());
        (instance, profile)
    };
    let (jvm, jvm_rejected) = sanitize_jvm(effective_jvm(&instance, profile.as_ref()));
    let jvm_profile = profile.map(|p| p.name);
    let dir = instance_dir(instance_id);
    let entries = tokio::task::spawn_blocking(move || {
        inventory(&dir)
            .into_iter()
            .map(|e| {
                let sha1 = e.lookup.then(|| sha1_cached(&e.abs)).filter(|h| !h.is_empty());
                (e, sha1)
            })
            .collect::<Vec<_>>()
    })
    .await
    .map_err(|e| e.to_string())?;

    let to_resolve: Vec<(String, PathBuf)> = entries
        .iter()
        .filter_map(|(e, h)| h.as_ref().map(|h| (h.clone(), e.abs.clone())))
        .collect();
    let lookup_failed = resolve(state, &to_resolve).await;
    // Un fichier illisible vaut « pas d'options » : ne bloque pas le reste.
    let client_options: Vec<McOption> = agent_options_read(instance_id.to_string())
        .await
        .unwrap_or_default()
        .into_iter()
        .filter(|o| keep_client(o, true))
        .collect();
    Ok(Scanned { instance, entries, lookup_failed, jvm, jvm_rejected, jvm_profile, client_options })
}

pub(super) fn item_of(entry: &Entry, sha1: Option<&String>) -> ShareItem {
    let source = sha1.and_then(|h| cached(h)).map(|r| r.source).unwrap_or(Source::Embedded);
    ShareItem {
        path: entry.path.clone(),
        group: entry.group,
        size: entry.size,
        files: entry.files,
        source,
        selected: selected_by_default(entry),
    }
}

/// Dossier des réglages de mods : décoché d'office. Il pèse vite plus que
/// tout le reste (138 Ko, 8 parties de lien sur une vraie instance) et
/// contient la configuration personnelle de chaque mod — on le coche quand
/// on veut vraiment la transmettre.
pub(super) const CONFIG_DIR: &str = "config";

pub(super) fn selected_by_default(entry: &Entry) -> bool {
    entry.group.selected_by_default() && entry.path != CONFIG_DIR
}

#[tauri::command]
pub async fn instance_share_scan(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<ShareScan, String> {
    let scanned = scan(&state, &instance_id).await?;
    let items = scanned.entries.iter().map(|(e, h)| item_of(e, h.as_ref())).collect();
    Ok(ShareScan {
        name: scanned.instance.name,
        mc_version: scanned.instance.mc_version,
        loader: scanned.instance.loader,
        items,
        lookup_failed: scanned.lookup_failed,
        jvm: scanned.jvm,
        jvm_rejected: scanned.jvm_rejected,
        jvm_profile: scanned.jvm_profile,
        client_options: scanned.client_options.len() as u32,
    })
}
