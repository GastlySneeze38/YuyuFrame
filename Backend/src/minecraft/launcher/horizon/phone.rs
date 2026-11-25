//! Ce qu'une partie d'Horizon demande sur les deux téléphones : la ligne de
//! lancement, le compte, le skin. Android la passe au jeu par
//! `horizon-args.txt` (`android.rs`), iOS directement, le jeu vivant dans
//! l'appli (`ios.rs`).

use anyhow::{anyhow, Result};
use horizon_engine_api::launch::{Launch, Player, Session};
use horizon_engine_api::skin::Model;
use std::path::{Path, PathBuf};
use tauri::Manager;

use crate::state::MinecraftSession;

/// La partie selon le contrat de lancement du moteur (`horizon-engine-api`,
/// `launch`), que le jeu promet de garder : `--game-dir`, `--home`, le compte
/// (`--session-file`, sinon `--name` hors ligne), le serveur à rejoindre.
///
/// `YUYUFRAME=1` (variable d'environnement) : le jeu sait qu'il est lancé par
/// YuyuFrame et prépare ses fichiers sans demander de compte Starling (notre
/// correctif dans `horizon-android`, `game/starling/setup/mod.rs`).
///
/// `--resources` n'y est pas : le jeu met d'office ses ressources préparées
/// dans les fichiers de l'appli, et c'est là qu'il les prépare au premier
/// démarrage.
pub(super) fn launch_line(
    game_dir: &Path,
    home: &Path,
    session: &MinecraftSession,
    session_file: Option<&Path>,
    server: Option<&str>,
) -> Launch {
    Launch {
        game_dir: game_dir.to_path_buf(),
        home: home.to_path_buf(),
        player: match session_file {
            Some(path) => Player::Session(path.to_path_buf()),
            None => Player::Offline(session.username.clone()),
        },
        server: server.map(str::to_owned),
        env: vec![("YUYUFRAME".into(), "1".into())],
    }
}

/// Le skin que l'écran Skins fait porter au compte devient celui du joueur en
/// solo (`horizon-engine-api`, `skin::wear`, profil `""` : le jeu n'a pas de
/// profil Starling quand YuyuFrame le lance). Sans skin choisi ici, le profil
/// garde le sien. Un échec n'empêche pas de jouer.
pub(super) async fn wear_skin(app: &tauri::AppHandle, game_dir: &Path, session: &MinecraftSession) {
    let state = app.state::<crate::state::SharedState>();
    let worn = crate::account::skins::host::worn_skin(&state, &session.uuid).await;
    let game = game_dir.to_path_buf();
    let result = tokio::task::spawn_blocking(move || -> Result<(), String> {
        match worn {
            Some((path, slim)) => {
                let png = std::fs::read(&path).map_err(|e| e.to_string())?;
                let model = if slim { Model::Slim } else { Model::Classic };
                horizon_engine_api::skin::wear(&game, "", Some(&png), model)
            }
            None => horizon_engine_api::skin::wear(&game, "", None, Model::Classic),
        }
    })
    .await;
    match result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => tracing::warn!("[Horizon] skin non posé : {e}"),
        Err(e) => tracing::warn!("[Horizon] skin non posé : {e}"),
    }
}

/// Le compte pour `--session-file`, écrit par le moteur lui-même
/// (`horizon-engine-api`) : nom imprévisible, créé neuf, mode 0600.
pub(super) async fn write_session_file(files: &Path, session: &MinecraftSession) -> Result<PathBuf> {
    let dir = files.join("yuyu-sessions");
    let session = Session {
        name: session.username.clone(),
        uuid: session.uuid.clone(),
        access_token: session.access_token.clone(),
        user_type: "msa".into(),
        xuid: None,
    };
    tokio::task::spawn_blocking(move || horizon_engine_api::launch::write_session_file(&dir, &session))
        .await?
        .map_err(|e| anyhow!("fichier de session : {e}"))
}

pub(super) async fn remove(path: Option<&Path>) {
    if let Some(path) = path {
        let _ = tokio::fs::remove_file(path).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(token: &str) -> MinecraftSession {
        MinecraftSession {
            username: "Alex".into(),
            uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5".into(),
            access_token: token.into(),
            refresh_token: None,
            expires_at: 0,
        }
    }

    #[test]
    fn hors_ligne_le_pseudo_sinon_le_fichier_de_session() {
        let dir = Path::new("/data/x/instances/a");
        let home = dir.join("horizon");
        let offline = launch_line(dir, &home, &session("offline"), None, None).arguments();
        assert!(offline.windows(2).any(|w| w == ["--name", "Alex"]));
        let online =
            launch_line(dir, &home, &session("t"), Some(Path::new("/f/s.json")), Some(" play.example.net ")).arguments();
        assert!(!online.iter().any(|a| a == "--name"));
        assert!(online.windows(2).any(|w| w == ["--session-file", "/f/s.json"]));
        assert!(online.windows(2).any(|w| w == ["--quickPlayMultiplayer", "play.example.net"]));
        assert!(online.windows(2).any(|w| w[0] == "--game-dir"));
    }
}
