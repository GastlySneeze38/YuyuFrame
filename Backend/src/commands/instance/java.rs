//! Le Java d'une instance : lequel il lui faut, lequel elle utilise.
//!
//! Le launcher résout tout ça tout seul au lancement — et c'est bien le
//! problème : quand ça échoue, l'écran ne dit rien et le jeu ne démarre pas.
//! Cet écran rend la résolution visible, avec les trois gestes qui vont avec :
//! installer le runtime recommandé, détecter un Java déjà présent sur la
//! machine, ou en désigner un à la main.
//!
//! La version requise n'est pas un réglage : elle est **imposée par la version
//! du jeu** (et par le loader, voir `java_requirement`). L'écran l'affiche
//! plutôt que de la faire choisir — proposer « Java 17 » à une instance qui
//! exige Java 21 ne mènerait qu'à un plantage au démarrage.

use serde::Serialize;
use std::path::Path;

use crate::db;
use crate::minecraft::launcher::{
    detect_java_major_version, find_system_java_verified, install_java_runtime, java_requirement,
    minecraft_dir, resolve_existing_java,
};
use crate::state::SharedState;

use super::crud::user_id;

/// L'état du Java d'une instance, tel que l'écran l'affiche.
#[derive(Serialize)]
pub struct JavaStatus {
    /// Version majeure exigée par cette instance — 8, 17, 21…
    pub required_major: u32,
    /// Nom du runtime chez Mojang (`java-runtime-delta`…), utile au support.
    pub component: String,
    /// Chemin de l'exécutable qui serait employé, s'il y en a un.
    pub path: Option<String>,
    /// `custom` (désigné à la main), `resolved` (trouvé par le launcher) ou
    /// `none`. La distinction compte : un chemin personnalisé se retire, un
    /// chemin résolu ne s'édite pas.
    pub source: String,
    /// Version réellement rendue par cet exécutable, quand on a pu la lire.
    pub detected_major: Option<u32>,
    /// Vrai seulement si ce qui est là correspond à ce qui est exigé.
    pub ok: bool,
}

/// La version de Java exigée par une instance, d'après son manifeste.
///
/// Le manifeste vient du cache local quand il y est, pour la même raison que
/// le diagnostic : c'est celui-là que le lancement lit.
async fn requirement(mc_version: &str, loader: &str) -> (String, u32) {
    let cache = minecraft_dir()
        .join("versions")
        .join(mc_version)
        .join(format!("{mc_version}.json"));
    let declared = tokio::fs::read_to_string(&cache)
        .await
        .ok()
        .and_then(|text| serde_json::from_str::<crate::minecraft::versions::VersionDetails>(&text).ok())
        .and_then(|d| d.java_version);
    let loader = (loader != "vanilla").then_some(loader);
    java_requirement(mc_version, loader, declared.as_ref())
}

/// Lit l'instance, sans garder le verrou de la base pendant le travail réseau
/// ou disque qui suit.
async fn instance_row(
    state: &tauri::State<'_, SharedState>,
    instance_id: &str,
) -> Result<db::InstanceRow, String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_get(&db, instance_id, uid)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Instance introuvable".into())
}

/// Quel Java cette instance exige, et lequel elle trouverait aujourd'hui.
#[tauri::command]
pub async fn instance_java_status(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<JavaStatus, String> {
    let row = instance_row(&state, &instance_id).await?;
    let (component, required_major) = requirement(&row.mc_version, &row.loader).await;

    // Un chemin personnalisé passe avant tout, exactement comme au lancement —
    // y compris quand il est invalide : c'est ce que l'utilisateur a demandé,
    // et le cacher derrière un repli silencieux lui ferait chercher longtemps
    // pourquoi son choix n'a pas d'effet.
    let custom = row.jvm_custom_path.filter(|p| !p.trim().is_empty());
    let (path, source) = match custom {
        Some(path) => (Some(path), "custom"),
        None => (
            resolve_existing_java(&component, required_major, &minecraft_dir()).await,
            "resolved",
        ),
    };

    let detected_major = match &path {
        Some(p) => detect_java_major_version(p).await,
        None => None,
    };

    Ok(JavaStatus {
        ok: detected_major == Some(required_major),
        required_major,
        component,
        source: if path.is_some() { source.into() } else { "none".into() },
        path,
        detected_major,
    })
}

/// La version majeure que rend cet exécutable, ou rien si ce n'en est pas un.
///
/// Sert au bouton « Parcourir » : on interroge la JVM elle-même plutôt que de
/// deviner d'après le nom du dossier, qui ment régulièrement.
#[tauri::command]
pub async fn java_probe(path: String) -> Result<Option<u32>, String> {
    if !Path::new(&path).exists() {
        return Err("Fichier introuvable".into());
    }
    Ok(detect_java_major_version(&path).await)
}

/// Cherche sur la machine un Java de la version exigée par l'instance.
///
/// Rend le chemin trouvé **sans rien enregistrer** : c'est une proposition,
/// l'utilisateur la confirme en l'appliquant.
#[tauri::command]
pub async fn instance_java_detect(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<Option<String>, String> {
    let row = instance_row(&state, &instance_id).await?;
    let (_, required_major) = requirement(&row.mc_version, &row.loader).await;
    Ok(find_system_java_verified(required_major).await)
}

/// Désigne le Java de cette instance, ou revient à la résolution automatique.
///
/// `path` absent = retour à l'automatique. Le vendeur suit : « custom » tant
/// qu'un chemin est posé, « auto » sinon — les deux doivent rester cohérents,
/// `ensure_java` refusant un vendeur « custom » sans chemin.
#[tauri::command]
pub async fn instance_set_java_path(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    path: Option<String>,
) -> Result<JavaStatus, String> {
    let path = path.map(|p| p.trim().to_string()).filter(|p| !p.is_empty());
    {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_set_java_path(&db, &instance_id, uid, path.as_deref()).map_err(|e| e.to_string())?;
    }
    instance_java_status(state, instance_id).await
}

/// Installe le runtime recommandé pour cette instance.
///
/// C'est `ensure_java` sans chemin personnalisé ni vendeur imposé : la même
/// fonction que le lancement, donc le même runtime, au même endroit. Elle
/// cherche d'abord ce qui est déjà là et ne télécharge qu'en dernier recours —
/// cliquer sur ce bouton alors que tout va bien ne retéléchargera rien.
#[tauri::command]
pub async fn instance_install_java(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    instance_id: String,
) -> Result<JavaStatus, String> {
    let row = instance_row(&state, &instance_id).await?;
    let (component, required_major) = requirement(&row.mc_version, &row.loader).await;

    install_java_runtime(&component, required_major, &app, &instance_id)
        .await
        .map_err(|e| e.to_string())?;

    instance_java_status(state, instance_id).await
}
