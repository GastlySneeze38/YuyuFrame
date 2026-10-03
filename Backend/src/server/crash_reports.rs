//! Rapports de plantage : consultation, envoi, suppression.
//!
//! Un rapport naît sur le disque (voir `minecraft::crash`), jamais sur nos
//! serveurs. Il y reste tant que personne ne clique : on peut le lire, le
//! copier, le jeter. `crash_send` est le seul moment où quelque chose part, et
//! il envoie exactement ce qui a été montré.
//!
//! Une fois envoyé, c'est le serveur qui fait autorité sur le statut : l'équipe
//! le change depuis le back-office, et `crash_remote_list` va le relire. Le
//! fichier local garde seulement de quoi faire le lien (`sent_id`).

use serde::Serialize;
use serde_json::Value;

use crate::server as api;
use crate::minecraft::crash;
use crate::state::SharedState;

/// Ce que la liste affiche. Le rapport complet pèse parfois plusieurs
/// centaines de kilo-octets (le journal) : le charger pour dessiner une liste
/// de quinze lignes serait absurde.
#[derive(Serialize)]
pub struct CrashSummary {
    pub id: String,
    pub instance_id: String,
    pub instance_name: String,
    pub title: String,
    pub kind: String,
    pub signature: String,
    pub mc_version: String,
    pub loader: String,
    pub occurred_at: String,
    pub sent_id: Option<String>,
    pub sent_public_id: Option<String>,
    pub mods_count: usize,
}

fn summarize(r: &crash::CrashReport) -> CrashSummary {
    CrashSummary {
        id: r.id.clone(),
        instance_id: r.instance_id.clone(),
        instance_name: r.instance_name.clone(),
        title: r.title.clone(),
        kind: r.kind.clone(),
        signature: r.signature.clone(),
        mc_version: r.mc_version.clone(),
        loader: r.loader.clone(),
        occurred_at: r.occurred_at.to_rfc3339(),
        sent_id: r.sent_id.clone(),
        sent_public_id: r.sent_public_id.clone(),
        mods_count: r.mods.len(),
    }
}

/// Les rapports gardés sur ce poste, du plus récent au plus ancien.
#[tauri::command]
pub async fn crash_list() -> Result<Vec<CrashSummary>, String> {
    tokio::task::spawn_blocking(|| crash::list().iter().map(summarize).collect())
        .await
        .map_err(|e| e.to_string())
}

/// Le rapport entier — celui qu'on lit avant de décider de l'envoyer.
#[tauri::command]
pub async fn crash_get(id: String) -> Result<crash::CrashReport, String> {
    tokio::task::spawn_blocking(move || crash::get(&id))
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Rapport introuvable".to_string())
}

/// Efface le rapport de ce poste. Ce qui a déjà été envoyé reste chez nous :
/// l'équipe y travaille, et il peut concerner d'autres joueurs — c'est dit
/// dans l'interface avant le clic.
#[tauri::command]
pub async fn crash_delete(id: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || crash::remove(&id))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
}

/// Envoie le rapport à l'équipe. Idempotent : un rapport déjà envoyé n'est pas
/// renvoyé, sinon un double-clic créerait deux fois le même plantage dans le
/// back-office et fausserait le groupement par cause.
#[tauri::command]
pub async fn crash_send(id: String, state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    let report = {
        let id = id.clone();
        tokio::task::spawn_blocking(move || crash::get(&id))
            .await
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "Rapport introuvable".to_string())?
    };
    if let Some(sent) = &report.sent_id {
        // Un rapport retiré de sa liste depuis un autre PC n'existe plus
        // côté serveur : on repart alors sur un envoi neuf plutôt que de
        // rendre une erreur incompréhensible à quelqu'un qui vient de
        // cliquer « Envoyer ».
        if let Ok(existing) = api::get(&state, &format!("/crashes/{sent}"), &[]).await {
            return Ok(existing);
        }
    }

    // Le corps est le rapport tel quel : une seconde forme, construite pour
    // l'envoi, finirait par ne plus décrire le même plantage que celle qu'on
    // a montrée. `install_id` s'y ajoute — il ne concerne pas l'affichage.
    let mut body = serde_json::to_value(&report).map_err(|e| e.to_string())?;
    if let Some(obj) = body.as_object_mut() {
        obj.insert("install_id".into(), Value::String(crate::integrations::analytics::install_id().to_string()));
    }
    let created = api::post(&state, "/crashes", body).await.map_err(String::from)?;

    let remote_id = created.get("id").and_then(Value::as_str).unwrap_or_default().to_string();
    let public_id = created.get("public_id").and_then(Value::as_str).unwrap_or_default().to_string();
    if !remote_id.is_empty() {
        // Un échec d'écriture ici ne perd que le lien vers le statut, pas le
        // rapport : il est déjà arrivé, et le dire serait affoler pour rien.
        let id = id.clone();
        let (remote, public) = (remote_id.clone(), public_id.clone());
        let _ = tokio::task::spawn_blocking(move || crash::mark_sent(&id, &remote, &public)).await;
    }
    Ok(created)
}

/// Les rapports envoyés depuis N'IMPORTE quel poste de ce compte, avec le
/// statut que l'équipe leur a donné. C'est la liste qui fait foi dans l'onglet
/// Support : changer de PC ne doit pas faire disparaître le suivi d'un
/// plantage déjà signalé.
#[tauri::command]
pub async fn crash_remote_list(state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    api::get(&state, "/crashes", &[]).await.map_err(String::from)
}

/// Le détail d'un rapport envoyé, vu par le serveur (avec la réponse de
/// l'équipe). Sert quand le rapport vient d'un autre poste et n'existe plus
/// en local.
#[tauri::command]
pub async fn crash_remote_get(id: String, state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    api::get(&state, &format!("/crashes/{id}"), &[]).await.map_err(String::from)
}

/// Retire un rapport envoyé de sa propre liste, et le fichier local avec.
#[tauri::command]
pub async fn crash_remote_hide(
    id: String,
    local_id: Option<String>,
    state: tauri::State<'_, SharedState>,
) -> Result<(), String> {
    api::delete(&state, &format!("/crashes/{id}")).await.map_err(String::from)?;
    if let Some(local_id) = local_id {
        let _ = tokio::task::spawn_blocking(move || crash::remove(&local_id)).await;
    }
    Ok(())
}

/// Le rapport mis en forme pour être collé ailleurs (salon Discord, message à
/// un auteur de mod). Il y a des plantages qui ne nous concernent pas : la
/// bonne réponse est parfois d'aller voir l'auteur du mod, et le rapport doit
/// pouvoir partir là-bas aussi.
#[tauri::command]
pub async fn crash_as_text(id: String) -> Result<String, String> {
    use std::fmt::Write;
    let r = tokio::task::spawn_blocking(move || crash::get(&id))
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Rapport introuvable".to_string())?;

    let mut out = String::new();
    let _ = writeln!(out, "=== Rapport de plantage YuyuFrame ===");
    if let Some(public_id) = &r.sent_public_id {
        let _ = writeln!(out, "Référence : {public_id}");
    }
    let _ = writeln!(out, "{}", r.title);
    let _ = writeln!(out, "Empreinte : {}", r.signature);
    let _ = writeln!(out, "Survenu le : {}", r.occurred_at.to_rfc3339());
    let _ = writeln!(out, "Après : {} s de jeu", r.uptime_ms / 1000);
    if let Some(code) = r.exit_code {
        let _ = writeln!(out, "Code de sortie : {code}");
    }
    let _ = writeln!(out, "\n--- Machine ---");
    let _ = writeln!(out, "Launcher : YuyuFrame {}", r.launcher_version);
    let _ = writeln!(out, "Système : {} {} ({})", r.os, r.os_version.as_deref().unwrap_or("?"), r.arch);
    if let Some(cpu) = &r.cpu {
        let _ = writeln!(out, "Processeur : {cpu}");
    }
    if let Some(gpu) = &r.gpu {
        let _ = writeln!(out, "Carte graphique : {gpu}");
    }
    if let Some(ram) = r.ram_total_mb {
        let _ = writeln!(out, "Mémoire : {ram} Mio");
    }
    let _ = writeln!(out, "\n--- Lancement ---");
    let _ = writeln!(out, "Instance : {} — Minecraft {} · {}", r.instance_name, r.mc_version, r.loader);
    let _ = writeln!(out, "Java : {}", r.java_version.as_deref().unwrap_or("?"));
    let _ = writeln!(out, "RAM allouée : {} Mio", r.ram_alloc_mb.unwrap_or(0));
    let _ = writeln!(out, "Drapeaux JVM : {}", r.jvm_args.join(" "));
    let actifs = r.mods.iter().filter(|m| m.enabled).count();
    let _ = writeln!(out, "\n--- Mods ({} dont {} actifs) ---", r.mods.len(), actifs);
    for m in &r.mods {
        let _ = writeln!(out, "  {} {}", if m.enabled { "·" } else { "×" }, m.name);
    }
    if let Some(trace) = &r.stack_trace {
        let _ = writeln!(out, "\n--- Trace ---\n{trace}");
    }
    let _ = writeln!(out, "\n--- Fin du journal ---\n{}", r.log_tail);
    Ok(out)
}
