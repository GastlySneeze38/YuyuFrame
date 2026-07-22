use tauri::{Emitter, Manager};

use crate::commands::instance::crud::instance_dir;
use crate::db;
use crate::minecraft::{auth, launcher, server_ping};
use crate::state::{MinecraftSession, SharedState};

#[tauri::command]
pub async fn launch_game(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    instance_id: String,
    p2p: Option<bool>,
    avoid_beta: Option<bool>,
    show_console: Option<bool>,
    connect_server: Option<String>,
) -> Result<(), String> {
    let session = {
        let s = state.read().await;
        s.session.clone().ok_or("Non connecté à Minecraft")?
    };

    // Refresh the MC token if it expires within the next 5 minutes
    let session = refresh_if_needed(session, &state).await?;

    if state.read().await.is_instance_running(&instance_id) {
        return Err(format!("L'instance {} est déjà en cours", instance_id));
    }

    let yuyu_user_id = {
        let s = state.read().await;
        s.current_yuyu_user_id().unwrap_or(0)
    };

    let instance = {
        let s = state.read().await;
        let db = s.db.lock().await;
        db::instance_get(&db, &instance_id, yuyu_user_id)
            .map_err(|e| e.to_string())?
            .ok_or("Instance introuvable")?
    };
    let instance = crate::commands::instance::crud::Instance {
        id: instance.id,
        name: instance.name,
        mc_version: instance.mc_version,
        loader: instance.loader,
        ram_mb: instance.ram_mb,
        favorite: instance.favorite,
        description: instance.description,
    };

    let game_dir = instance_dir(&instance_id);
    tokio::fs::create_dir_all(&game_dir).await.map_err(|e| e.to_string())?;

    // Open or reopen the console window (label unique par instance) — sauf si
    // l'utilisateur a désactivé l'affichage de la console dans les réglages.
    // Le jeu se lance identiquement dans les deux cas (log_to_console tombe
    // simplement en broadcast si aucune fenêtre n'écoute, voir progress.rs).
    let window_label = format!("mc-console-{}", &instance_id[..8.min(instance_id.len())]);
    let show_console = show_console.unwrap_or(true);
    let console_ready = if show_console {
        if let Some(existing) = app.get_webview_window(&window_label) {
            let _ = existing.close();
        }
        // Enregistré AVANT le build() de la fenêtre : garantit qu'aucun signal
        // console_ready (invoqué par Console.tsx dès que son listener game_log
        // est attaché) ne peut arriver avant que ce Notify n'existe déjà dans
        // le registre — voir launcher::register_console_waiter.
        let ready = launcher::register_console_waiter(&window_label);

        let _ = tauri::WebviewWindowBuilder::new(
            &app,
            &window_label,
            tauri::WebviewUrl::App(std::path::PathBuf::from("console")),
        )
        .title(format!("Console — {}", instance.name))
        .inner_size(960.0, 620.0)
        .decorations(false)
        .build();

        Some(ready)
    } else {
        None
    };

    let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);
    {
        let mut s = state.write().await;
        s.running_instances.insert(instance_id.clone());
        s.launch_cancel.insert(instance_id.clone(), cancel_tx);
    }
    let _ = app.emit("game_state", serde_json::json!({
        "running": true,
        "instance_id": &instance_id,
    }));

    // Discord Rich Presence — bascule sur "en jeu" (voir integrations/discord.rs). Clonés
    // AVANT le move de `instance` dans le bloc async ci-dessous (sinon plus
    // accessible ici). spawn_blocking : set_activity fait de l'IPC bloquante
    // (écriture sur la pipe/socket Discord), jamais directement sur le
    // runtime async.
    {
        let instance_name = instance.name.clone();
        let mc_version = instance.mc_version.clone();
        tokio::task::spawn_blocking(move || crate::integrations::discord::set_playing(instance_name, mc_version));
    }

    let state_clone = state.inner().clone();

    tokio::spawn(async move {
        // Attend que Console.tsx ait fini d'attacher son listener game_log
        // avant d'émettre le moindre log — sans ça, un lancement rapide (tout
        // en cache, ex: 1.8.9 vanilla) peut atteindre les premiers
        // log_to_console() avant que le webview n'ait fini son démarrage
        // React, perdant silencieusement ces lignes (aucun listener encore
        // attaché côté frontend). Timeout de sécurité : ne bloque jamais
        // indéfiniment si Console.tsx ne signale jamais (vieux build frontend
        // sans cet appel, ou fenêtre fermée avant d'avoir eu le temps).
        // Rien à attendre si la console est désactivée (pas de fenêtre créée).
        if let Some(console_ready) = console_ready {
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), console_ready.notified()).await;
        }

        let started_at = chrono::Utc::now().timestamp();

        let session_id: Option<i64> = {
            let s = state_clone.read().await;
            let db = s.db.lock().await;
            db::session_start(
                &db,
                yuyu_user_id,
                &instance.id,
                &instance.name,
                &instance.mc_version,
                &instance.loader,
            )
            .ok()
        };

        match launcher::download_and_launch(
            &instance.mc_version,
            Some(&instance.loader),
            &session,
            instance.ram_mb,
            &game_dir,
            app.clone(),
            state_clone.clone(),
            p2p.unwrap_or(false),
            avoid_beta.unwrap_or(true),
            &window_label,
            &instance_id,
            connect_server.as_deref(),
            cancel_rx,
        )
        .await
        {
            Ok(warnings) if !warnings.is_empty() => {
                // Lancement réussi mais avec des libs/dépendances manquantes
                // (voir orchestrator::LoaderSetup) — le jeu peut planter ou
                // manquer une fonctionnalité sans que rien n'ait "échoué" au
                // sens strict, donc pas d'événement launch_error ici, mais
                // l'utilisateur doit quand même le savoir.
                tracing::warn!("Lancement de {} avec avertissements : {:?}", instance_id, warnings);
                let _ = app.emit("launch_warning", serde_json::json!({
                    "instance_id": &instance_id,
                    "warnings": warnings,
                }));
            }
            Ok(_) => {}
            Err(e) if e.to_string() == launcher::LAUNCH_CANCELLED_MSG => {
                let _ = app.emit("launch_cancelled", &instance_id);
            }
            Err(e) => {
                tracing::error!("Erreur de lancement: {}", e);
                let _ = app.emit("launch_error", e.to_string());
            }
        }

        if let Some(sid) = session_id {
            let duration = chrono::Utc::now().timestamp() - started_at;
            let s = state_clone.read().await;
            let db = s.db.lock().await;
            let _ = db::session_end(&db, sid, duration);
        }

        {
            let mut s = state_clone.write().await;
            s.running_instances.remove(&instance_id);
            s.launch_cancel.remove(&instance_id);
            // Discord Rich Presence — retour "dans le launcher" SEULEMENT si
            // plus AUCUNE instance ne tourne (any_running(), voir state.rs) :
            // plusieurs instances peuvent tourner en parallèle
            // (running_instances est un Set), fermer l'une d'elles ne doit
            // pas repasser la présence en idle si une autre est encore active.
            if !s.any_running() {
                tokio::task::spawn_blocking(crate::integrations::discord::set_idle);
            }
        }
        let _ = app.emit("game_state", serde_json::json!({
            "running": false,
            "instance_id": &instance_id,
        }));
    });

    Ok(())
}

/// Liste les serveurs multijoueur enregistrés dans `servers.dat` de
/// l'instance (voir `minecraft::launcher::servers`) — utilisé pour proposer
/// un lancement direct sur l'un d'eux via `launch_game(connect_server: ...)`.
/// Liste vide (pas d'erreur) si l'instance n'a encore aucun serveur enregistré.
#[tauri::command]
pub async fn list_saved_servers(instance_id: String) -> Result<Vec<launcher::SavedServer>, String> {
    let game_dir = instance_dir(&instance_id);
    launcher::read_saved_servers(&game_dir)
}

/// Server List Ping (voir `minecraft::server_ping`) — MOTD, joueurs en ligne,
/// favicon et latence, affichés sur les cartes serveur de l'accueil.
#[tauri::command]
pub async fn ping_server(address: String) -> Result<server_ping::ServerPingInfo, String> {
    server_ping::ping_server(&address).await
}

/// Demande l'annulation d'un lancement en cours — best-effort : coupe le
/// téléchargement au prochain point de contrôle s'il est encore en cours, ou
/// tue la JVM si elle a déjà démarré (voir les points de contrôle dans
/// `download_and_launch` et le `tokio::select!` autour de `child.wait()`).
#[tauri::command]
pub async fn cancel_launch(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<(), String> {
    let s = state.read().await;
    let tx = s
        .launch_cancel
        .get(&instance_id)
        .ok_or("Aucun lancement en cours pour cette instance")?;
    let _ = tx.send(true);
    Ok(())
}

/// Invoquée par Console.tsx dès que son listener `game_log` est attaché —
/// débloque l'attente posée dans `launch_game` (voir `register_console_waiter`)
/// pour que le lancement ne commence à émettre des logs qu'une fois le
/// frontend prêt à les recevoir.
#[tauri::command]
pub async fn console_ready(console_label: String) -> Result<(), String> {
    launcher::signal_console_ready(&console_label);
    Ok(())
}

async fn refresh_if_needed(
    session: MinecraftSession,
    state: &tauri::State<'_, SharedState>,
) -> Result<MinecraftSession, String> {
    let now = chrono::Utc::now().timestamp();
    // Refresh if the token expires within 5 minutes
    if session.expires_at > now + 300 {
        return Ok(session);
    }

    let refresh_token = session
        .refresh_token
        .as_deref()
        .ok_or("Token MC expiré mais pas de refresh_token — reconnectez-vous")?
        .to_string();

    tracing::info!("Token MC expiré — rafraîchissement en cours...");

    let result = auth::refresh_session(&refresh_token)
        .await
        .map_err(|e| format!("Échec du rafraîchissement du token MC : {}", e))?;

    // Persist to state and DB
    let new_session = {
        let s = state.read().await;
        let yuyu_user_id = s.current_yuyu_user_id().unwrap_or(0);
        let db = s.db.lock().await;
        crate::commands::account::apply_refreshed_tokens(&db, yuyu_user_id, result)
    };
    state.write().await.session = Some(new_session.clone());

    tracing::info!("Token MC rafraîchi — expire dans 24h");
    Ok(new_session)
}

#[tauri::command]
pub async fn reload_agent() -> Result<(), String> {
    let appdata = std::env::var("APPDATA").map_err(|_| "Variable APPDATA introuvable".to_string())?;
    let trigger = std::path::Path::new(&appdata)
        .join("YuyuFrame")
        .join("p2p")
        .join("reload.trigger");

    if let Some(parent) = trigger.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }

    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
        .to_string();

    tokio::fs::write(&trigger, ts).await.map_err(|e| e.to_string())?;
    Ok(())
}
