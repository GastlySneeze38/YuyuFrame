//! Lecture et écriture de `options.txt`, le fichier de réglages de Minecraft.
//!
//! Le format est une ligne par réglage, `clé:valeur`, sans section ni
//! échappement. La valeur peut elle-même contenir des `:` — c'est le cas de
//! `resourcePacks:["vanilla","file/truc.zip"]` — donc la découpe se fait au
//! **premier** deux-points et pas au dernier ni par `split`.
//!
//! ── Pourquoi on réécrit le fichier au lieu de le régénérer ────────────────
//! Minecraft y écrit des dizaines de clés, dont beaucoup dépendent de la
//! version et des mods installés (`key_key.sodium.*`, réglages d'Iris…).
//! Régénérer le fichier à partir de ce que l'interface sait afficher en
//! perdrait silencieusement la moitié — raccourcis clavier compris. On
//! conserve donc l'ordre et les lignes d'origine, et on ne remplace que la
//! valeur des clés effectivement modifiées.
//!
//! Le fichier n'existe qu'après un premier lancement du jeu : c'est Minecraft
//! qui le crée. Avant ça, la lecture rend une liste vide plutôt qu'une
//! erreur, et l'interface le dit.

use serde::{Deserialize, Serialize};

use crate::minecraft::launcher::minecraft_dir;
use super::crud::instance_dir;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct McOption {
    pub key: String,
    pub value: String,
}

fn options_path(instance_id: &str) -> std::path::PathBuf {
    instance_dir(instance_id).join("options.txt")
}

/// Modèle global, copié dans les nouvelles instances quand le réglage est
/// actif. Un seul pour tout le launcher : c'est « mes réglages », pas un jeu
/// de préréglages.
fn template_path() -> std::path::PathBuf {
    minecraft_dir().join("shared_options.txt")
}

/// Découpe le contenu d'`options.txt` en réglages.
///
/// Les lignes vides et celles sans deux-points sont ignorées : ce ne sont pas
/// des réglages, et les garder obligerait tout le reste de la chaîne à savoir
/// quoi en faire.
fn parse(content: &str) -> Vec<McOption> {
    content
        .lines()
        .filter_map(|line| {
            let line = line.trim_end_matches('\r');
            let (key, value) = line.split_once(':')?;
            let key = key.trim();
            if key.is_empty() {
                return None;
            }
            Some(McOption { key: key.to_string(), value: value.to_string() })
        })
        .collect()
}

/// Réécrit le contenu en n'appliquant que les valeurs fournies.
///
/// Une clé absente du fichier est ajoutée à la fin : c'est le cas normal
/// quand Minecraft n'a pas encore écrit un réglage que l'interface propose.
/// Tout le reste — ordre, lignes inconnues, commentaires éventuels — revient
/// intact.
fn apply(content: &str, changes: &[McOption]) -> String {
    let mut remaining: Vec<&McOption> = changes.iter().collect();
    let mut out = String::with_capacity(content.len() + 64);

    for line in content.lines() {
        let line = line.trim_end_matches('\r');
        match line.split_once(':') {
            Some((key, _)) if remaining.iter().any(|c| c.key == key.trim()) => {
                let pos = remaining.iter().position(|c| c.key == key.trim()).unwrap();
                let change = remaining.remove(pos);
                out.push_str(&change.key);
                out.push(':');
                out.push_str(&change.value);
            }
            _ => out.push_str(line),
        }
        out.push('\n');
    }

    for change in remaining {
        out.push_str(&change.key);
        out.push(':');
        out.push_str(&change.value);
        out.push('\n');
    }
    out
}

#[tauri::command]
pub async fn mc_options_read(instance_id: String) -> Result<Vec<McOption>, String> {
    match tokio::fs::read_to_string(options_path(&instance_id)).await {
        Ok(content) => Ok(parse(&content)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(format!("Lecture de options.txt : {}", e)),
    }
}

/// Applique des réglages et rend le fichier relu.
///
/// Relu plutôt que « ce qu'on vient d'écrire » : l'interface affiche ainsi
/// exactement ce qui est sur le disque, y compris les clés qu'elle ne
/// connaissait pas encore.
#[tauri::command]
pub async fn mc_options_write(
    instance_id: String,
    changes: Vec<McOption>,
) -> Result<Vec<McOption>, String> {
    let path = options_path(&instance_id);
    let current = match tokio::fs::read_to_string(&path).await {
        Ok(content) => content,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(format!("Lecture de options.txt : {}", e)),
    };

    let next = apply(&current, &changes);
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::write(&path, next.as_bytes())
        .await
        .map_err(|e| format!("Écriture de options.txt : {}", e))?;

    Ok(parse(&next))
}

/// Impose le plein écran dans `options.txt` juste avant un lancement.
///
/// Par `options.txt` et non par un argument de ligne de commande : Minecraft
/// n'en a pas pour le plein écran, c'est ce fichier qui en décide. Et comme le
/// jeu le réécrit en quittant, le réglage doit être reposé à **chaque**
/// lancement — sinon une sortie du plein écran en jeu annulerait pour toujours
/// ce que l'instance demande.
///
/// N'est appelé que si l'instance impose sa fenêtre (voir `window_custom`) :
/// sans ça, écrire `fullscreen:false` effacerait le choix fait en jeu.
///
/// Best-effort : un `options.txt` illisible ne doit pas empêcher de jouer.
pub(crate) async fn force_fullscreen(instance_id: &str, fullscreen: bool) {
    let path = options_path(instance_id);
    let current = match tokio::fs::read_to_string(&path).await {
        Ok(content) => content,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => {
            tracing::warn!("Plein écran non appliqué ({} illisible) : {}", path.display(), e);
            return;
        }
    };
    let next = apply(
        &current,
        &[McOption { key: "fullscreen".into(), value: fullscreen.to_string() }],
    );
    if let Some(parent) = path.parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }
    if let Err(e) = tokio::fs::write(&path, next.as_bytes()).await {
        tracing::warn!("Écriture du plein écran dans {} échouée : {}", path.display(), e);
    }
}

/// Enregistre le réglage « synchroniser les paramètres Minecraft ».
///
/// Poussé par le frontend au démarrage et à chaque changement, comme pour
/// l'autorisation d'arrière-plan. Le backend ne peut pas lire le stockage du
/// frontend, et c'est lui qui applique la règle à la création — il faut donc
/// qu'il en ait sa propre copie.
#[tauri::command]
pub async fn set_sync_game_settings(
    state: tauri::State<'_, crate::state::SharedState>,
    enabled: bool,
) -> Result<(), String> {
    let s = state.read().await;
    let db = s.db.lock().await;
    crate::db::prefs::set_bool(&db, crate::db::prefs::SYNC_GAME_SETTINGS, enabled)
        .map_err(|e| e.to_string())
}

// ── Modèle partagé entre instances ──────────────────────────────────────────

#[derive(Serialize)]
pub struct SharedOptionsStatus {
    pub exists: bool,
    /// Horodatage de l'enregistrement, en secondes. `None` si absent.
    pub saved_at: Option<i64>,
    /// Nombre de réglages qu'il contient — de quoi vérifier d'un coup d'œil
    /// qu'on a bien exporté un vrai fichier et pas une coquille vide.
    pub option_count: usize,
}

/// État du modèle.
///
/// Existe parce que le réglage « synchroniser les paramètres Minecraft »
/// avait une condition invisible : sans modèle enregistré, il ne fait rien et
/// ne le dit pas. C'était la première cause de « ça ne marche pas tout le
/// temps » — l'interrupteur était sur oui, mais il n'y avait rien à copier.
#[tauri::command]
pub async fn shared_options_status() -> Result<SharedOptionsStatus, String> {
    let path = template_path();
    let Ok(meta) = tokio::fs::metadata(&path).await else {
        return Ok(SharedOptionsStatus { exists: false, saved_at: None, option_count: 0 });
    };
    let saved_at = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64);
    let option_count = tokio::fs::read_to_string(&path)
        .await
        .map(|c| parse(&c).len())
        .unwrap_or(0);
    Ok(SharedOptionsStatus { exists: true, saved_at, option_count })
}

/// Enregistre l'`options.txt` d'une instance comme modèle.
#[tauri::command]
pub async fn instance_export_settings(instance_id: String) -> Result<(), String> {
    let src = options_path(&instance_id);
    if !src.exists() {
        return Err("Aucun fichier options.txt dans cette instance — lance le jeu au moins une fois pour le générer".into());
    }
    tokio::fs::copy(&src, template_path()).await.map_err(|e| e.to_string())?;
    Ok(())
}

/// Applique le modèle à une instance, à la demande explicite de
/// l'utilisateur. Écrase un `options.txt` existant — c'est le sens du geste.
///
/// Rend `false` quand aucun modèle n'a été enregistré.
#[tauri::command]
pub async fn instance_apply_settings(instance_id: String) -> Result<bool, String> {
    copy_template(&instance_id, true).await
}

/// Pose le modèle dans une instance.
///
/// `overwrite` distingue les deux usages. À la demande de l'utilisateur, on
/// écrase : il a cliqué pour ça. À la création d'une instance, non — un
/// modpack ou un import peut avoir déposé son propre `options.txt`, et
/// l'écraser reviendrait à défaire en silence ce que l'utilisateur vient
/// d'installer.
pub(super) async fn copy_template(instance_id: &str, overwrite: bool) -> Result<bool, String> {
    let src = template_path();
    if !src.exists() {
        return Ok(false);
    }
    let dest = options_path(instance_id);
    if !overwrite && dest.exists() {
        return Ok(false);
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::copy(&src, &dest).await.map_err(|e| e.to_string())?;
    Ok(true)
}

/// Applique le modèle à une instance qui vient d'être créée, si le réglage
/// est actif.
///
/// Appelée par le backend lui-même, depuis chaque chemin de création. C'est
/// tout l'objet du correctif : la règle ne peut plus être oubliée par un
/// appelant, puisqu'aucun appelant n'en décide plus.
///
/// Best-effort par construction : une instance créée mais sans ses réglages
/// reste utilisable, alors qu'une création annulée parce qu'un fichier
/// d'options n'a pas pu être copié serait absurde.
pub(super) async fn apply_template_on_create(conn_flag: bool, instance_id: &str) {
    if !conn_flag {
        return;
    }
    match copy_template(instance_id, false).await {
        Ok(true) => tracing::info!("Réglages Minecraft appliqués à la nouvelle instance {}", instance_id),
        Ok(false) => tracing::info!(
            "Réglages Minecraft non appliqués à {} : aucun modèle enregistré, ou l'instance en a déjà un",
            instance_id
        ),
        Err(e) => tracing::warn!("Copie des réglages Minecraft vers {} échouée : {}", instance_id, e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opt(key: &str, value: &str) -> McOption {
        McOption { key: key.into(), value: value.into() }
    }

    #[test]
    fn decoupe_au_premier_deux_points() {
        // `resourcePacks` contient des `:` dans sa valeur : découper ailleurs
        // qu'au premier casserait la liste des packs actifs.
        let parsed = parse("renderDistance:12\nresourcePacks:[\"vanilla\",\"file/a:b.zip\"]");
        assert_eq!(parsed, vec![
            opt("renderDistance", "12"),
            opt("resourcePacks", "[\"vanilla\",\"file/a:b.zip\"]"),
        ]);
    }

    #[test]
    fn ignore_ce_qui_n_est_pas_un_reglage() {
        let parsed = parse("\nrenderDistance:12\n\nligne sans deux points\n:valeur orpheline\n");
        assert_eq!(parsed, vec![opt("renderDistance", "12")]);
    }

    #[test]
    fn les_fins_de_ligne_windows_ne_polluent_pas_la_valeur() {
        assert_eq!(parse("fov:70\r\n"), vec![opt("fov", "70")]);
    }

    #[test]
    fn seules_les_cles_modifiees_changent() {
        let before = "fov:70\nrenderDistance:12\nkey_key.attack:key.mouse.left\n";
        let after = apply(before, &[opt("fov", "90")]);
        assert_eq!(after, "fov:90\nrenderDistance:12\nkey_key.attack:key.mouse.left\n");
    }

    #[test]
    fn une_cle_inconnue_du_fichier_est_ajoutee_a_la_fin() {
        let after = apply("fov:70\n", &[opt("gamma", "1.0")]);
        assert_eq!(after, "fov:70\ngamma:1.0\n");
    }

    #[test]
    fn les_lignes_inconnues_survivent() {
        // Tout ce que le launcher ne sait pas lire doit revenir intact —
        // c'est la raison d'être de cette réécriture ligne à ligne.
        let before = "ligne bizarre\nfov:70\n";
        assert_eq!(apply(before, &[opt("fov", "80")]), "ligne bizarre\nfov:80\n");
    }

    #[test]
    fn ecrire_dans_un_fichier_absent_cree_les_reglages() {
        assert_eq!(apply("", &[opt("fov", "70")]), "fov:70\n");
    }
}
