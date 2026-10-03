//! L'essai de compatibilité, côté commandes.
//!
//! Le raisonnement et la lecture du journal vivent dans `minecraft::compat` ;
//! ici on ne fait que trois choses : réserver l'instance, démarrer le vrai
//! lancement avec une sonde, et transformer ce qu'elle a vu en gestes que
//! l'interface sait proposer.
//!
//! Un seul point mérite d'être lu avant de toucher à ce fichier : **la
//! résolution des gestes se fait ici et pas dans l'analyse**. Le loader parle
//! en identifiants de mods (`sodium`, `iris`), le dossier `mods/` en noms de
//! fichiers (`sodium-fabric-0.6.5+mc1.21.4.jar`), et les deux ne se
//! ressemblent pas toujours. L'analyse reste donc une fonction pure et
//! testable, et c'est la commande — seule à avoir un disque sous la main — qui
//! fait correspondre l'un à l'autre, en lisant l'identifiant **déclaré dans le
//! jar** plutôt qu'en pariant sur son nom.

use std::sync::Arc;

use serde::Serialize;
use tokio::sync::watch;

use crate::minecraft::compat::{diagnose, loader_suggestions, Fix, Probe, Problem, MAX_RUN_SECS};
use crate::minecraft::launcher::{self, LAUNCH_CANCELLED_MSG};
use crate::minecraft::mod_files::is_enabled_jar;
use crate::minecraft::versions::predicate::read_mod_meta;
use crate::state::{MinecraftSession, SharedState};

use super::crud::{instance_dir, instance_mods_dir};

/// Le verdict d'un essai.
#[derive(Serialize)]
pub struct CompatResult {
    /// `ok` (le jeu s'est levé) · `failed` (la JVM s'est arrêtée seule) ·
    /// `timeout` (toujours en train de démarrer au bout du temps imparti) ·
    /// `cancelled` · `error` (le launcher n'a même pas pu préparer le
    /// lancement).
    pub status: String,
    /// Renseigné pour `error` seulement : ce qui a empêché de préparer le
    /// lancement. Ce n'est pas un problème de compatibilité, et le dire comme
    /// tel enverrait chercher la panne au mauvais endroit.
    pub message: Option<String>,
    pub problems: Vec<Problem>,
    /// Ce que le loader propose lui-même, recopié tel quel.
    pub suggestions: Vec<String>,
    /// La fin du journal. Toujours rendue, même en cas de réussite : c'est ce
    /// qu'on colle dans un ticket, et le redemander demanderait de relancer
    /// l'essai.
    pub log: String,
    pub duration_ms: u64,
}

/// Compte employé pour l'essai quand aucun n'est actif.
///
/// Un essai ne doit pas réclamer de connexion : il répond à « est-ce que ça
/// démarre », pas à « puis-je jouer en ligne ». Le jeton hors ligne suffit
/// pour tout ce qui se joue avant le menu principal, et c'est là qu'on
/// s'arrête.
fn probe_session() -> MinecraftSession {
    MinecraftSession {
        username: "CompatTest".into(),
        uuid: "00000000-0000-0000-0000-000000000000".into(),
        access_token: "offline".into(),
        refresh_token: None,
        expires_at: 253_402_300_799,
    }
}

#[tauri::command]
pub async fn instance_compat_test(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    instance_id: String,
) -> Result<CompatResult, String> {
    {
        let s = state.read().await;
        if s.is_instance_running(&instance_id) {
            return Err("Cette instance est en cours de jeu".to_string());
        }
        if s.compat_running.contains(&instance_id) {
            return Err("Un essai est déjà en cours sur cette instance".to_string());
        }
    }

    let cfg = crate::commands::launch::resolve_launch_config(&state, &instance_id).await?;
    // La session enregistrée telle quelle, sans rafraîchissement : un essai ne
    // doit ni dépendre du réseau, ni consommer un jeton Microsoft.
    let session = state
        .read()
        .await
        .session
        .clone()
        .unwrap_or_else(probe_session);

    let game_dir = instance_dir(&instance_id);
    tokio::fs::create_dir_all(&game_dir).await.map_err(|e| e.to_string())?;

    let (cancel_tx, cancel_rx) = watch::channel(false);
    let probe = Arc::new(Probe::new(cancel_tx.clone(), app.clone(), instance_id.clone()));

    {
        let mut s = state.write().await;
        s.compat_running.insert(instance_id.clone());
        s.launch_cancel.insert(instance_id.clone(), cancel_tx);
    }

    // Aucune fenêtre ne porte ce label : les lignes du jeu partent alors en
    // diffusion générale (voir `log_to_console`), où la console d'une vraie
    // partie ne les confondra pas avec les siennes. La sonde, elle, émet son
    // propre flux pour l'écran de l'essai.
    let tail_len = 8.min(instance_id.len());
    let console_label = format!("compat-{}", &instance_id[instance_id.len() - tail_len..]);

    let started = std::time::Instant::now();
    let launch = launcher::download_and_launch(
        &cfg.instance.mc_version,
        Some(&cfg.instance.loader),
        &session,
        cfg.ram_mb,
        &game_dir,
        app.clone(),
        // Pas de P2P : il ouvre une session réseau, et la question posée ici
        // porte sur les mods et le loader.
        false,
        // Le client intégré, lui, est du voyage : il se charge à chaque vraie
        // partie, donc un essai sans lui ne testerait pas ce qui sera joué.
        true,
        true,
        &console_label,
        &instance_id,
        &cfg.instance.name,
        // Aucune ligne de statistiques : ce n'est pas une partie.
        None,
        None,
        cancel_rx,
        &cfg.jvm_vendor,
        cfg.jvm_custom_path.as_deref(),
        &cfg.gc_policy,
        &cfg.jvm_extra_args,
        &cfg.jvm_args_mode,
        &cfg.instance.loader_version,
        cfg.instance
            .window_custom
            .then_some((cfg.instance.window_width, cfg.instance.window_height)),
        Some(probe.clone()),
    );

    // Le compte à rebours ne court qu'à partir de la première ligne de la JVM
    // (voir `Probe::running_for`) : un modpack qui télécharge huit minutes de
    // bibliothèques n'a pas commencé à démarrer.
    tokio::pin!(launch);
    let outcome = loop {
        tokio::select! {
            res = &mut launch => break res,
            _ = tokio::time::sleep(std::time::Duration::from_secs(5)) => {
                if probe.running_for().is_some_and(|d| d.as_secs() > MAX_RUN_SECS) {
                    probe.give_up();
                }
            }
        }
    };

    {
        let mut s = state.write().await;
        s.compat_running.remove(&instance_id);
        s.launch_cancel.remove(&instance_id);
    }

    let log = probe.log();
    let duration_ms = started.elapsed().as_millis() as u64;

    let (status, message) = match outcome {
        // Arrêt demandé : par la sonde quand le jeu est debout, par le temps
        // imparti, ou par la personne. Les trois arrivent par le même canal,
        // c'est la sonde qui sait lequel.
        Err(e) if e.to_string() == LAUNCH_CANCELLED_MSG => {
            if probe.reached() {
                ("ok", None)
            } else if probe.timed_out() {
                ("timeout", None)
            } else {
                ("cancelled", None)
            }
        }
        // La JVM s'est arrêtée toute seule. Si le marqueur était déjà passé,
        // c'est que le jeu s'est fermé entre-temps — la réponse reste oui.
        Ok(_) => {
            if probe.reached() {
                ("ok", None)
            } else {
                ("failed", None)
            }
        }
        Err(e) => ("error", Some(e.to_string())),
    };

    let problems = if status == "ok" || status == "cancelled" {
        Vec::new()
    } else {
        resolve_fixes(&instance_id, diagnose(&log)).await
    };
    let suggestions = if status == "failed" { loader_suggestions(&log) } else { Vec::new() };

    crate::integrations::analytics::capture("instance_compat_test", serde_json::json!({
        "instance_id": &instance_id,
        "status": status,
        "problems": problems.len(),
    }));

    Ok(CompatResult {
        status: status.to_string(),
        message,
        problems,
        suggestions,
        log,
        duration_ms,
    })
}

/// Arrête l'essai en cours. Même canal que `cancel_launch`, à ceci près qu'on
/// refuse d'arrêter une vraie partie par ce bouton-là.
#[tauri::command]
pub async fn instance_compat_cancel(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<(), String> {
    let s = state.read().await;
    if !s.compat_running.contains(&instance_id) {
        return Err("Aucun essai en cours pour cette instance".to_string());
    }
    if let Some(tx) = s.launch_cancel.get(&instance_id) {
        let _ = tx.send(true);
    }
    Ok(())
}

/// Un mod du dossier, tel que les deux mondes le nomment.
struct ModFile {
    file: String,
    id: String,
}

/// Les mods activés de l'instance, avec l'identifiant qu'ils **déclarent**.
///
/// Lecture de jars : sur un thread bloquant. Les mods désactivés sont
/// volontairement ignorés — proposer de désactiver ce qui l'est déjà n'aurait
/// aucun sens, et un mod désactivé n'a pas pu causer l'échec.
async fn mod_files(instance_id: &str) -> Vec<ModFile> {
    let dir = instance_mods_dir(instance_id);
    tokio::task::spawn_blocking(move || {
        let mut out = Vec::new();
        let Ok(entries) = std::fs::read_dir(&dir) else { return out };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if !is_enabled_jar(&name) {
                continue;
            }
            let id = read_mod_meta(&entry.path())
                .map(|m| m.id.to_lowercase())
                .unwrap_or_default();
            out.push(ModFile { file: name, id });
        }
        out
    })
    .await
    .unwrap_or_default()
}

/// Traduit les gestes en quelque chose d'applicable, et jette les autres.
///
/// Un bouton « désactiver ce mod » qui ne sait pas quel fichier renommer est
/// pire que pas de bouton : il promet une réparation et ne peut que
/// l'échouer. Celui-là disparaît, le problème reste affiché avec sa ligne de
/// loader — l'information, elle, garde toute sa valeur.
async fn resolve_fixes(instance_id: &str, problems: Vec<Problem>) -> Vec<Problem> {
    let files = mod_files(instance_id).await;
    problems
        .into_iter()
        .map(|mut p| {
            let mut fixes: Vec<Fix> = Vec::new();
            for fix in p.fixes {
                if fix.action != "disable_mod" {
                    fixes.push(fix);
                    continue;
                }
                if let Some(file) = match_mod(&files, &fix.value) {
                    fixes.push(Fix { action: fix.action, value: file });
                }
            }
            fixes.dedup_by(|a, b| a.action == b.action && a.value == b.value);
            p.fixes = fixes;
            p
        })
        .collect()
}

/// L'identifiant déclaré d'abord, le nom de fichier ensuite.
///
/// L'ordre n'est pas une préférence de style : `sodium` comme nom de fichier
/// apparaît aussi dans `indium`, `sodium-extra` et une poignée d'extensions,
/// et le premier venu serait souvent le mauvais. On ne retombe sur le nom que
/// lorsque le jar ne déclare rien de lisible (vieux mods, archives hybrides).
fn match_mod(files: &[ModFile], wanted: &str) -> Option<String> {
    let wanted = wanted.trim().to_lowercase();
    if wanted.is_empty() {
        return None;
    }
    if let Some(m) = files.iter().find(|m| m.id == wanted) {
        return Some(m.file.clone());
    }
    files
        .iter()
        .find(|m| m.file.to_lowercase().contains(&wanted))
        .map(|m| m.file.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn files() -> Vec<ModFile> {
        vec![
            ModFile { file: "sodium-fabric-0.6.5.jar".into(), id: "sodium".into() },
            ModFile { file: "sodium-extra-6.0.jar".into(), id: "sodium_extra".into() },
            ModFile { file: "Iris-1.8.0.jar".into(), id: "iris".into() },
        ]
    }

    #[test]
    fn l_identifiant_declare_passe_avant_le_nom_de_fichier() {
        // « sodium » apparaît dans deux noms de fichiers : c'est l'identifiant
        // qui tranche, sinon on désactiverait l'extension à la place du mod.
        assert_eq!(match_mod(&files(), "sodium").unwrap(), "sodium-fabric-0.6.5.jar");
        assert_eq!(match_mod(&files(), "sodium_extra").unwrap(), "sodium-extra-6.0.jar");
    }

    #[test]
    fn repli_sur_le_nom_de_fichier_puis_rien() {
        // Un jar qui ne déclare pas d'identifiant lisible reste rattrapable
        // par son nom…
        let mut f = files();
        f.push(ModFile { file: "OptiFine_1.21.4.jar".into(), id: String::new() });
        assert_eq!(match_mod(&f, "optifine").unwrap(), "OptiFine_1.21.4.jar");
        // …et un mod absent ne produit aucun geste, plutôt qu'un bouton qui
        // échouerait.
        assert!(match_mod(&f, "create").is_none());
    }
}
