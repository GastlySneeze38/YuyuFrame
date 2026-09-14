//! Plateforme de la refonte 1.8.9 : Java 25 + LWJGL 3 (décisions D7/D9,
//! `docs/LauncherAgent/v1.8.9/README.md` § 3).
//!
//! Minecraft 1.8.9 appelle toujours l'API LWJGL 2 (`Display`, `Keyboard`,
//! `Mouse`, `GL11.glGetFloat`…). Le launcher la remplace ici par LWJGL 3.4.1 —
//! la même que Minecraft 26.1.2 — et ajoute `lwjgl2-compat.jar`, la couche de
//! compatibilité reprise de legacy-lwjgl3 (LGPL-2.1) que construit
//! `Launcher-Agent/build.bat`. Les méthodes LWJGL 2 manquantes sur les classes
//! LWJGL 3 sont recréées par les mixins `apimixin/v1_8_9/lwjgl` de l'agent :
//! sans agent, le jeu ne démarre pas sur cette plateforme.

use std::path::PathBuf;

use anyhow::{anyhow, Result};

use crate::minecraft::versions::Library;
use super::agent_deploy::launcher_agent_libs_dir;

/// Entrées de bibliothèque LWJGL 3.4.1, recopiées telles quelles (URL, sha1,
/// taille, règles d'OS) du manifeste Mojang de la 26.1.2 : même pipeline de
/// téléchargement vérifié que n'importe quelle bibliothèque vanilla.
const LWJGL3_LIBRARIES: &str = include_str!("legacy_lwjgl3_libraries.json");

/// Nom du jar de la couche de compatibilité, déployé avec l'agent.
const COMPAT_JAR: &str = "lwjgl2-compat.jar";

/// `true` si ce lancement tourne sur la plateforme refondue : 1.8.9 en loader
/// vanilla uniquement. Forge 1.8.9 reste sur Java 8 + LWJGL 2 (LaunchWrapper,
/// voir `java_requirement`), et la refonte ne vise pas les autres loaders.
pub(super) fn uses_legacy_lwjgl3(version_id: &str, loader: Option<&str>) -> bool {
    version_id == "1.8.9" && loader.unwrap_or("vanilla") == "vanilla"
}

/// Sous-dossier des natives de la version.
///
/// PIÈGE : l'ancien `versions/1.8.9/natives` contient encore les DLL de
/// LWJGL 2, dont un `lwjgl.dll` 32 bits — le nom exact que LWJGL 3 cherche en
/// premier dans `java.library.path`. Le réutiliser ferait charger la mauvaise
/// bibliothèque au démarrage. La plateforme LWJGL 3 a donc son propre dossier,
/// qui ne reçoit que les natives restantes (Twitch) ; LWJGL 3 extrait les
/// siennes depuis ses jars `natives-*`.
pub(super) fn natives_dir_name(version_id: &str, loader: Option<&str>) -> &'static str {
    if uses_legacy_lwjgl3(version_id, loader) { "natives-lwjgl3" } else { "natives" }
}

/// Retire LWJGL 2 et ses dépendances d'entrée (jinput, jutils) de la liste de
/// bibliothèques de la version, puis ajoute LWJGL 3.4.1.
///
/// Retirer AVANT de télécharger évite aussi l'extraction des natives LWJGL 2
/// (`lwjgl-platform`, `jinput-platform`), qui n'ont plus rien à faire là.
pub(super) fn swap_libraries(libraries: &mut Vec<Library>) -> Result<()> {
    let before = libraries.len();
    libraries.retain(|lib| !is_lwjgl2_stack(&lib.name));
    let removed = before - libraries.len();

    let lwjgl3: Vec<Library> = serde_json::from_str(LWJGL3_LIBRARIES)
        .map_err(|e| anyhow!("Liste LWJGL 3 embarquée illisible : {}", e))?;
    let added = lwjgl3.len();
    libraries.extend(lwjgl3);

    tracing::info!("[LWJGL 3] {} bibliothèque(s) LWJGL 2 retirée(s), {} ajoutée(s)", removed, added);
    Ok(())
}

/// LWJGL 2 (groupe `org.lwjgl.lwjgl`, dont `lwjgl_util` et `lwjgl-platform`)
/// et la pile d'entrée qu'il tirait avec lui.
fn is_lwjgl2_stack(name: &str) -> bool {
    let group = name.split(':').next().unwrap_or("");
    group == "org.lwjgl.lwjgl" || group == "net.java.jinput" || group == "net.java.jutils"
}

/// Chemin de `lwjgl2-compat.jar`. Erreur franche s'il manque : sans lui,
/// Minecraft 1.8.9 plante au premier `Display.create()` avec un
/// `NoClassDefFoundError` bien moins parlant.
pub(super) fn compat_jar() -> Result<PathBuf> {
    let jar = launcher_agent_libs_dir().join(COMPAT_JAR);
    if jar.exists() {
        Ok(jar)
    } else {
        Err(anyhow!(
            "{} introuvable dans {} — couche LWJGL 3 de la 1.8.9 absente, réinstallez le launcher",
            COMPAT_JAR,
            launcher_agent_libs_dir().display()
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_list_parses_with_all_modules() {
        let libs: Vec<Library> = serde_json::from_str(LWJGL3_LIBRARIES).unwrap();
        for module in ["lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal"] {
            let main = format!("org.lwjgl:{}:3.4.1", module);
            assert!(libs.iter().any(|l| l.name == main), "{} manquant", main);
        }
    }

    #[test]
    fn only_lwjgl2_stack_is_removed() {
        assert!(is_lwjgl2_stack("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"));
        assert!(is_lwjgl2_stack("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209"));
        assert!(is_lwjgl2_stack("net.java.jinput:jinput:2.0.5"));
        assert!(!is_lwjgl2_stack("org.lwjgl:lwjgl:3.4.1"));
        assert!(!is_lwjgl2_stack("com.paulscode:librarylwjglopenal:20100824"));
    }

    #[test]
    fn only_vanilla_189_uses_the_platform() {
        assert!(uses_legacy_lwjgl3("1.8.9", None));
        assert!(uses_legacy_lwjgl3("1.8.9", Some("vanilla")));
        assert!(!uses_legacy_lwjgl3("1.8.9", Some("forge")));
        assert!(!uses_legacy_lwjgl3("1.21.11", None));
    }
}
