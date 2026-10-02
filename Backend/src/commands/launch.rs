use tauri::{Emitter, Manager};

use crate::commands::instance::crud::instance_dir;
use crate::db;
use crate::minecraft::{launcher, server_ping};
use crate::state::SharedState;

#[tauri::command]
pub async fn launch_game(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    instance_id: String,
    p2p: Option<bool>,
    // Jouer avec le client intégré (LauncherAgent) ou sans lui. Absent =
    // avec : c'est le comportement de toujours, et l'agent se désactive de
    // lui-même là où il ne peut pas se charger (voir `agent_compat`).
    use_agent: Option<bool>,
    avoid_beta: Option<bool>,
    show_console: Option<bool>,
    connect_server: Option<String>,
) -> Result<(), String> {
    // Token rafraîchi s'il expire bientôt — voir account::fresh_session pour
    // ce qui bloque (session révoquée) ou non (Microsoft injoignable).
    let session = crate::commands::account::refresh_active(&state)
        .await?
        .ok_or("Aucun compte Minecraft actif — ajoute ou sélectionne un compte")?;

    if state.read().await.is_instance_running(&instance_id) {
        return Err(format!("L'instance {} est déjà en cours", instance_id));
    }

    let yuyu_user_id = {
        let s = state.read().await;
        s.current_yuyu_user_id().unwrap_or(0)
    };

    let (instance, jvm_profile) = {
        let s = state.read().await;
        let db = s.db.lock().await;
        let row = db::instance_get(&db, &instance_id, yuyu_user_id)
            .map_err(|e| e.to_string())?
            .ok_or("Instance introuvable")?;
        // Une config supprimée entre-temps laisse un id orphelin : on retombe
        // silencieusement sur les réglages de l'instance plutôt que d'échouer
        // le lancement — c'est le comportement par défaut du launcher, jamais
        // des drapeaux inattendus.
        let profile = row.jvm_profile_id.as_deref().and_then(|id| {
            db::jvm_profile_get(&db, id, yuyu_user_id).ok().flatten()
        });
        (row, profile)
    };
    let instance = crate::commands::instance::crud::Instance {
        id: instance.id,
        name: instance.name,
        mc_version: instance.mc_version,
        loader: instance.loader,
        ram_mb: instance.ram_mb,
        favorite: instance.favorite,
        description: instance.description,
        jvm_vendor: instance.jvm_vendor,
        jvm_custom_path: instance.jvm_custom_path,
        gc_policy: instance.gc_policy,
        jvm_extra_args: instance.jvm_extra_args,
        jvm_args_mode: instance.jvm_args_mode,
        jvm_profile_id: instance.jvm_profile_id,
        icon: instance.icon,
    };

    // Une config reliée remplace INTÉGRALEMENT le bloc JVM de l'instance —
    // pas de fusion des deux, qui donnerait une troisième configuration que
    // personne n'a écrite et rendrait tout benchmark ininterprétable. Sa RAM
    // reste facultative : la plupart des configs ne cherchent à imposer que
    // des drapeaux, et écraser la RAM choisie sur l'instance serait une
    // surprise.
    let (jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode, ram_mb) = match &jvm_profile {
        Some(p) => (
            p.jvm_vendor.clone(),
            p.jvm_custom_path.clone(),
            p.gc_policy.clone(),
            p.all_args(),
            p.args_mode.clone(),
            p.ram_mb.unwrap_or(instance.ram_mb),
        ),
        None => (
            instance.jvm_vendor.clone(),
            instance.jvm_custom_path.clone(),
            instance.gc_policy.clone(),
            instance.jvm_extra_args.clone(),
            instance.jvm_args_mode.clone(),
            instance.ram_mb,
        ),
    };

    let game_dir = instance_dir(&instance_id);
    tokio::fs::create_dir_all(&game_dir).await.map_err(|e| e.to_string())?;

    crate::integrations::analytics::capture("launch_started", serde_json::json!({
        "instance_id": &instance_id,
        "mc_version": &instance.mc_version,
        "loader": &instance.loader,
        "p2p": p2p.unwrap_or(false),
        "launcher_agent": use_agent.unwrap_or(true),
        "avoid_beta_dependencies": avoid_beta.unwrap_or(true),
        "connect_server": connect_server.is_some(),
        // "offline" = compte hors ligne (crack), "microsoft" = compte authentifié
        // Mojang/Microsoft — voir mc_add_offline (access_token sentinelle "offline").
        "account_type": if session.access_token == "offline" { "offline" } else { "microsoft" },
    }));

    // Open or reopen the console window (label unique par instance) — sauf si
    // l'utilisateur a désactivé l'affichage de la console dans les réglages.
    // Le jeu se lance identiquement dans les deux cas (log_to_console tombe
    // simplement en broadcast si aucune fenêtre n'écoute, voir progress.rs).
    //
    // Fin de l'id (pas le début) : depuis que l'id est `<nom-slugifié>-<code>`
    // (voir crud.rs gen_id), deux instances au nom similaire partagent le même
    // début (ex: "modpack-" pour "Modpack 1" et "Modpack 2") — prendre les 8
    // premiers caractères aurait fait collisionner leurs labels de fenêtre.
    // La fin est toujours le suffixe aléatoire, garanti unique.
    let tail_len = 8.min(instance_id.len());
    let window_label = format!("mc-console-{}", &instance_id[instance_id.len() - tail_len..]);
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
        crate::state::RUNNING_GAMES.store(s.running_instances.len(), std::sync::atomic::Ordering::Relaxed);
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
        let loader = instance.loader.clone();
        let server_ip = connect_server.clone();
        tokio::task::spawn_blocking(move || crate::integrations::discord::set_playing(instance_name, mc_version, server_ip, loader));
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

        // Instantané avant de démarrer : c'est le moment où une sauvegarde
        // sert, et celui auquel personne ne pense. Bloquant volontairement —
        // sauvegarder pendant que le jeu écrit déjà dans le monde donnerait
        // une copie incohérente — mais jamais fatal : une erreur ici
        // n'empêche pas de jouer.
        crate::commands::backup::before_launch(&state_clone, &instance.id, &instance.name).await;

        let started_at = chrono::Utc::now().timestamp();

        let session_id: Option<i64> = {
            let s = state_clone.read().await;
            let db = s.db.lock().await;
            // Vérifié AVANT session_start (qui insère la ligne courante) —
            // sinon le count inclurait déjà ce lancement et ne serait jamais 0.
            let is_first_launch = db::instance_session_count(&db, &instance.id).unwrap_or(1) == 0;
            if is_first_launch {
                crate::integrations::analytics::capture("instance_first_launch", serde_json::json!({
                    "instance_id": &instance.id,
                    "mc_version": &instance.mc_version,
                    "loader": &instance.loader,
                }));
            }
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
            ram_mb,
            &game_dir,
            app.clone(),
            p2p.unwrap_or(false),
            use_agent.unwrap_or(true),
            avoid_beta.unwrap_or(true),
            &window_label,
            &instance_id,
            &instance.name,
            session_id,
            connect_server.as_deref(),
            cancel_rx,
            &jvm_vendor,
            jvm_custom_path.as_deref(),
            &gc_policy,
            &jvm_extra_args,
            &jvm_args_mode,
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
                crate::integrations::analytics::capture("launch_cancelled", serde_json::json!({
                    "instance_id": &instance_id,
                }));
                let _ = app.emit("launch_cancelled", &instance_id);
            }
            Err(e) => {
                tracing::error!("Erreur de lancement: {}", e);
                crate::integrations::analytics::capture("launch_error", serde_json::json!({
                    "instance_id": &instance_id,
                    "message": e.to_string(),
                }));
                // L'id de l'instance accompagne le message : plusieurs lancements
                // peuvent être en cours, l'accueil doit réinitialiser le bon.
                let _ = app.emit("launch_error", serde_json::json!({
                    "instance_id": &instance_id,
                    "message": e.to_string(),
                }));
            }
        }

        // Filet, pas règle : la fin est normalement écrite par
        // l'orchestrateur, seul à savoir si la partie s'est terminée par un
        // plantage. Ici on ne rattrape que les lancements qui ont échoué avant
        // même que la JVM démarre — `session_end_if_open` n'écrase jamais une
        // fin déjà écrite.
        if let Some(sid) = session_id {
            let ended_at = chrono::Utc::now().timestamp();
            let s = state_clone.read().await;
            let db = s.db.lock().await;
            let _ = db::session_end_if_open(&db, sid, ended_at, ended_at - started_at);
        }

        {
            let mut s = state_clone.write().await;
            s.running_instances.remove(&instance_id);
            s.launch_cancel.remove(&instance_id);
            crate::state::RUNNING_GAMES.store(s.running_instances.len(), std::sync::atomic::Ordering::Relaxed);
            // Discord Rich Presence — retour "dans le launcher" SEULEMENT si
            // plus AUCUNE instance ne tourne (any_running(), voir state.rs) :
            // plusieurs instances peuvent tourner en parallèle
            // (running_instances est un Set), fermer l'une d'elles ne doit
            // pas repasser la présence en idle si une autre est encore active.
            if !s.any_running() {
                tokio::task::spawn_blocking(crate::integrations::discord::set_idle);
            }
        }
        // Déposée si la fenêtre a été fermée au lancement : c'est cet
        // événement qui remet l'interface à zéro et déclenche la demande
        // d'avis, et il tombe exactement pendant le seul moment où plus
        // personne n'écoute (voir commands::pending).
        super::pending::emit_or_stash(&app, "game_state", serde_json::json!({
            "running": false,
            "instance_id": &instance_id,
        }));

        // La fenêtre a pu être fermée ou réduite au lancement : la partie
        // finie, elle revient. Le veilleur d'arrière-plan n'avait de raison
        // d'être que le temps de la partie — il s'efface avec elle, icône de
        // notification comprise.
        crate::restore_after_game(&app);
    });

    Ok(())
}

/// Les instances qui tournent en ce moment.
///
/// L'interface tient sa propre liste, alimentée par les événements
/// `game_state`. Elle vit en mémoire de la webview — donc elle disparaît avec
/// elle quand « masquer au lancement » la ferme. La fenêtre recréée repartait
/// alors d'une ardoise vide et proposait de relancer une instance déjà en
/// train de tourner.
///
/// Le Rust, lui, n'a rien oublié : c'est lui qui tient la vérité (voir
/// `AppState::running_instances`). L'interface vient la lui demander à son
/// montage plutôt que de la déduire d'événements qu'elle n'a pas entendus.
#[tauri::command]
pub async fn running_instances(state: tauri::State<'_, SharedState>) -> Result<Vec<String>, String> {
    Ok(state.read().await.running_instances.iter().cloned().collect())
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

#[derive(serde::Serialize)]
pub struct JvmConfigPreview {
    pub java_path: String,
    pub java_major: u32,
    pub jvm_args: Vec<String>,
}

/// P1-6 (audit launcher, Phase 6) — bouton "Voir la configuration appliquée"
/// des paramètres avancés d'instance : résout la JVM et génère les flags
/// exactement comme un vrai lancement (voir `preview_jvm_config`), sans
/// spawner Minecraft.
#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn preview_jvm_config(
    app: tauri::AppHandle,
    instance_id: String,
    mc_version: String,
    ram_mb: u32,
    jvm_vendor: String,
    jvm_custom_path: Option<String>,
    gc_policy: String,
    jvm_extra_args: Option<String>,
    jvm_args_mode: Option<String>,
) -> Result<JvmConfigPreview, String> {
    let game_dir = instance_dir(&instance_id);
    let (java_path, java_major, jvm_args) = launcher::preview_jvm_config(
        &instance_id, &mc_version, ram_mb, &game_dir, app,
        &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy,
        jvm_extra_args.as_deref().unwrap_or(""),
        jvm_args_mode.as_deref().unwrap_or("append"),
    ).await.map_err(|e| e.to_string())?;
    Ok(JvmConfigPreview { java_path, java_major, jvm_args })
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

/// État du client intégré (LauncherAgent) pour une instance — ce que la
/// fenêtre de l'agent, sur l'accueil, a besoin de savoir pour ne pas mentir.
///
/// L'interface ne peut pas le déduire : la liste des versions tissables vit
/// dans le code de l'agent, et la contrainte de JVM dans le launcher. Elle
/// demande donc, et met des mots sur la réponse dans la langue de
/// l'utilisateur.
#[derive(serde::Serialize)]
pub struct AgentStatus {
    /// `true` si l'agent se chargera pour cette instance (sous réserve de la
    /// JVM réellement obtenue au lancement, inconnue d'ici — voir
    /// `agent_compat::blocked_by`).
    pub available: bool,
    /// Ce qui l'en empêche, `null` quand rien ne l'empêche.
    pub block: Option<launcher::AgentBlock>,
    /// Version de Java qu'exige l'agent, pour l'expliquer sans la coder en
    /// dur dans l'interface.
    pub min_java: u32,
}

#[tauri::command]
pub fn launcher_agent_status(mc_version: String, loader: Option<String>) -> AgentStatus {
    let block = launcher::agent_blocked_by(&mc_version, loader.as_deref());
    AgentStatus {
        available: block.is_none(),
        block,
        min_java: launcher::AGENT_MIN_JAVA,
    }
}
