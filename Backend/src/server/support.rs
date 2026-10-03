//! Tickets de support ouverts depuis le launcher.
//!
//! Tout passe par `/v1/support` (Server/LauncherAPI/src/launcher/support.rs) :
//! les tickets vivent dans la base du back-office, qui ouvre à chacun un salon
//! Discord privé et y recopie les messages. Les réponses de l'équipe, qu'elles
//! viennent de Discord ou du back-office, reviennent par ces mêmes routes.
//!
//! Les corps de réponse traversent tels quels vers le frontend : le launcher
//! n'interprète aucun champ, et recopier ici la forme des tickets ne ferait
//! qu'ajouter un endroit à mettre à jour quand le serveur en gagne un. La
//! forme de référence est celle de `launcher/support.rs`, reprise dans
//! `Frontend/src/types/support.ts`.

use serde_json::{json, Value};

use crate::server as api;
use crate::state::SharedState;

/// Catégories du formulaire. Route publique : la liste s'affiche avant même
/// que la personne ait ouvert un ticket.
#[tauri::command]
pub async fn support_categories(state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    let client = state.read().await.http.clone();
    let resp = client
        .get(format!("{}/support/categories", api::base()))
        .send()
        .await
        .map_err(|e| String::from(api::error::ApiError::network(e)))?;
    resp.json::<Value>().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn support_list(state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    api::get(&state, "/support/tickets", &[]).await.map_err(String::from)
}

/// Ouvre la conversation. Le serveur en profite pour marquer les réponses de
/// l'équipe comme lues : la pastille disparaît donc dès la lecture.
#[tauri::command]
pub async fn support_get(id: String, state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    api::get(&state, &format!("/support/tickets/{id}"), &[]).await.map_err(String::from)
}

#[tauri::command]
pub async fn support_create(
    category: String,
    subject: String,
    message: String,
    diagnostic: Option<String>,
    state: tauri::State<'_, SharedState>,
) -> Result<Value, String> {
    // Le rapport est déjà nettoyé par `support_diagnostic`, mais il repasse
    // par le filtre : rien ne garantit que c'est bien celui-là qui revient.
    let diagnostic = diagnostic.map(|d| redact(&d)).filter(|d| !d.trim().is_empty());
    let body = json!({
        "category": category,
        "subject": subject,
        "message": message,
        "diagnostic": diagnostic,
    });
    api::post(&state, "/support/tickets", body).await.map_err(String::from)
}

#[tauri::command]
pub async fn support_reply(
    id: String,
    message: String,
    state: tauri::State<'_, SharedState>,
) -> Result<Value, String> {
    api::post(&state, &format!("/support/tickets/{id}/messages"), json!({ "message": message }))
        .await
        .map_err(String::from)
}

/// Retire un ticket clos de la liste. Le serveur refuse si le ticket est
/// encore ouvert, et ne supprime rien : l'équipe garde la conversation.
#[tauri::command]
pub async fn support_hide(id: String, state: tauri::State<'_, SharedState>) -> Result<(), String> {
    api::delete(&state, &format!("/support/tickets/{id}")).await.map(|_| ()).map_err(String::from)
}

// ── Rapport de diagnostic ────────────────────────────────────────────────────

/// Rapport joint au ticket : ce que l'équipe redemande toujours en premier.
/// Il est construit ici, montré tel quel avant l'envoi, et la personne peut
/// refuser de le joindre — d'où une commande à part plutôt qu'un ajout
/// silencieux à `support_create`.
#[tauri::command]
pub async fn support_diagnostic(
    instance_id: Option<String>,
    app: tauri::AppHandle,
    state: tauri::State<'_, SharedState>,
) -> Result<String, String> {
    use std::fmt::Write;

    let mut sys = sysinfo::System::new();
    sys.refresh_memory();
    sys.refresh_cpu_usage();

    let mut out = String::new();
    let _ = writeln!(out, "Launcher : YuyuFrame {}", app.package_info().version);
    let _ = writeln!(
        out,
        "Système : {} {} ({})",
        sysinfo::System::name().unwrap_or_else(|| "?".into()),
        sysinfo::System::os_version().unwrap_or_else(|| "?".into()),
        std::env::consts::ARCH,
    );
    if let Some(cpu) = sys.cpus().first() {
        let _ = writeln!(out, "Processeur : {} ({} cœurs logiques)", cpu.brand().trim(), sys.cpus().len());
    }
    let _ = writeln!(
        out,
        "Mémoire : {} Mio installés, {} Mio libres",
        sys.total_memory() / 1024 / 1024,
        sys.available_memory() / 1024 / 1024,
    );

    let (user_id, plan) = {
        let s = state.read().await;
        let plan = s.yuyu_session.as_ref().map(|y| y.plan.clone()).unwrap_or_else(|| "free".into());
        (s.current_yuyu_user_id().unwrap_or(0), plan)
    };
    let _ = writeln!(out, "Compte : plan {plan}");

    let instances = {
        let s = state.read().await;
        let db = s.db.lock().await;
        crate::db::instance_list(&db, user_id).unwrap_or_default()
    };
    let _ = writeln!(out, "\nInstances : {}", instances.len());

    // L'instance concernée en détail ; les autres ne servent qu'à situer.
    let focus = instance_id
        .as_deref()
        .and_then(|id| instances.iter().find(|i| i.id == id))
        .or_else(|| instances.first());
    if let Some(i) = focus {
        let mods = list_mods(&crate::instances::crud::instance_mods_dir(&i.id));
        let actifs = mods.iter().filter(|(_, enabled)| *enabled).count();
        let _ = writeln!(out, "\nInstance « {} »", i.name);
        let _ = writeln!(out, "  Minecraft {} · {}", i.mc_version, i.loader);
        let _ = writeln!(out, "  RAM allouée : {} Mio", i.ram_mb);
        let _ = writeln!(out, "  JVM : {} · GC {}", i.jvm_vendor, i.gc_policy);
        let _ = writeln!(out, "  Mods : {} ({} actifs)", mods.len(), actifs);
        if !mods.is_empty() {
            let _ = writeln!(out, "  Liste :");
            for (raw, enabled) in mods.iter().take(200) {
                let _ = writeln!(out, "    {} {}", if *enabled { "·" } else { "×" }, raw);
            }
            if mods.len() > 200 {
                let _ = writeln!(out, "    … {} autres", mods.len() - 200);
            }
        }
    }

    Ok(redact(&out))
}

/// Noms de fichiers du dossier mods et s'ils sont actifs. La sync a sa propre
/// version, plus riche (`sync::push_pull`), mais elle est interne à ce module :
/// le rapport n'a besoin que du nom et de l'état.
fn list_mods(dir: &std::path::Path) -> Vec<(String, bool)> {
    let Ok(entries) = std::fs::read_dir(dir) else { return vec![] };
    let mut mods: Vec<(String, bool)> = entries
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_str()?.to_string();
            let lower = name.to_ascii_lowercase();
            if lower.ends_with(".jar") {
                Some((name, true))
            } else if lower.ends_with(".jar.disabled") {
                Some((name, false))
            } else {
                None
            }
        })
        .collect();
    mods.sort();
    mods
}

/// Masque ce qui ne doit jamais partir dans un ticket : adresses e-mail et
/// jetons. Le rapport ci-dessus n'en contient pas, mais il finit dans un salon
/// Discord partagé par l'équipe — le filtre reste, au cas où un nom de fichier
/// ou un champ ajouté plus tard en amènerait.
///
/// Partagé avec les rapports de plantage (`minecraft::crash`), qui emportent
/// des journaux bruts : deux définitions de « ce qui ne doit jamais sortir »
/// finiraient par ne plus dire la même chose.
pub(crate) fn redact(text: &str) -> String {
    text.split_inclusive(|c: char| c.is_whitespace())
        .map(|word| {
            let trimmed = word.trim();
            if looks_like_email(trimmed) {
                word.replace(trimmed, "[adresse masquée]")
            } else if looks_like_token(trimmed) {
                word.replace(trimmed, "[jeton masqué]")
            } else {
                word.to_string()
            }
        })
        .collect()
}

fn looks_like_email(w: &str) -> bool {
    let mut parts = w.splitn(2, '@');
    let (local, domain) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
    !local.is_empty() && domain.contains('.') && !domain.ends_with('.')
}

/// Les jetons du launcher : refresh `yfr_…`, jeton d'accès JWT en trois
/// morceaux.
fn looks_like_token(w: &str) -> bool {
    w.starts_with("yfr_") || (w.starts_with("ey") && w.matches('.').count() == 2 && w.len() > 40)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emails_and_tokens_never_reach_the_ticket() {
        let out = redact("Compte joueur@example.com jeton yfr_abcdef ok");
        assert!(out.contains("[adresse masquée]"));
        assert!(out.contains("[jeton masqué]"));
        assert!(out.contains("Compte") && out.contains("ok"));
    }

    #[test]
    fn ordinary_text_is_left_alone() {
        let text = "Minecraft 1.21.1 · neoforge\n  · sodium-fabric-0.6.jar\n";
        assert_eq!(redact(text), text, "ni la ponctuation ni la mise en forme ne bougent");
    }

    #[test]
    fn jwts_are_caught_but_not_file_names() {
        let jwt = format!("ey{}.{}.{}", "a".repeat(20), "b".repeat(20), "c".repeat(20));
        assert!(looks_like_token(&jwt));
        assert!(!looks_like_token("NeoForge-1.21.1-21.1.72.jar"));
    }
}
