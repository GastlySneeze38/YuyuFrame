use std::path::PathBuf;
use tauri::Manager;

/// Dossier LauncherAgent dans AppData/YuyuFrame/agent/ — séparé de
/// AppData/YuyuFrame/p2p/ (voir docs/LauncherAgent/index.md).
/// Doit contenir : launcher-agent.jar, content_core.dll, libs/ (mixin.jar, asm-*.jar, jna*.jar)
pub(super) fn launcher_agent_dir() -> PathBuf {
    crate::paths::root().join("agent")
}

/// Sous-dossier libs/ de launcher_agent_dir() — mixin.jar + asm-*.jar, séparés
/// du jar principal pour laisser la racine ouverte au chargement dynamique de
/// mods (voir build.bat de Launcher-Agent).
pub(super) fn launcher_agent_libs_dir() -> PathBuf {
    launcher_agent_dir().join("libs")
}

/// Copie l'agent bundlé dans l'installateur (voir tauri.conf.json,
/// bundle.resources) vers `%AppData%\YuyuFrame\agent\` — sans ce mécanisme,
/// un beta testeur qui installe l'app n'a jamais ces fichiers (jusqu'ici seul
/// build.bat, en dev, les y copiait manuellement). Écrase toujours l'existant
/// : un utilisateur qui met à jour l'app doit récupérer la version de l'agent
/// qui correspond à CETTE version installée, jamais garder une copie d'un
/// ancien build. À appeler une fois au démarrage (voir lib.rs setup()) —
/// échec loggé mais jamais fatal (un dev qui lance `cargo tauri dev` sans
/// avoir buildé l'agent, ou avant que content-core existe, ne doit pas voir
/// l'app planter pour autant).
pub fn deploy_bundled_agent(app: &tauri::AppHandle) {
    let resource_dir = match app.path().resource_dir() {
        Ok(dir) => dir,
        Err(e) => {
            tracing::warn!("[LauncherAgent] resource_dir() indisponible ({}) — agent bundlé non déployé", e);
            return;
        }
    };
    let bundled = resource_dir.join("agent");

    let dest_dir = launcher_agent_dir();
    let dest_libs = launcher_agent_libs_dir();
    if let Err(e) = std::fs::create_dir_all(&dest_libs) {
        tracing::warn!("[LauncherAgent] impossible de créer {}: {}", dest_libs.display(), e);
        return;
    }

    let files: [(&str, PathBuf); 10] = [
        ("launcher-agent.jar", dest_dir.join("launcher-agent.jar")),
        ("content_core.dll", dest_dir.join("content_core.dll")),
        ("libs/mixin.jar", dest_libs.join("mixin.jar")),
        ("libs/asm-9.5.jar", dest_libs.join("asm-9.5.jar")),
        ("libs/asm-tree-9.5.jar", dest_libs.join("asm-tree-9.5.jar")),
        ("libs/asm-util-9.5.jar", dest_libs.join("asm-util-9.5.jar")),
        ("libs/asm-analysis-9.5.jar", dest_libs.join("asm-analysis-9.5.jar")),
        ("libs/asm-commons-9.5.jar", dest_libs.join("asm-commons-9.5.jar")),
        // JNA (module "Fenêtre sans bordure", BorderlessWindowNative) — appel
        // direct de l'API Win32 depuis du Java pur, pas de nouvelle DLL Rust.
        ("libs/jna.jar", dest_libs.join("jna.jar")),
        ("libs/jna-platform.jar", dest_libs.join("jna-platform.jar")),
    ];

    let mut deployed = 0;
    for (rel, dest) in &files {
        let src = bundled.join(rel);
        // content_core.dll notamment n'existe pas toujours (voir build.bat,
        // "non implementee") — absence normale, pas une erreur.
        if !src.exists() { continue; }
        match std::fs::copy(&src, dest) {
            Ok(_) => deployed += 1,
            Err(e) => tracing::warn!("[LauncherAgent] copie {} -> {} échouée: {}", src.display(), dest.display(), e),
        }
    }
    tracing::info!("[LauncherAgent] {} fichier(s) de l'agent bundlé déployé(s) vers {}", deployed, dest_dir.display());
}
