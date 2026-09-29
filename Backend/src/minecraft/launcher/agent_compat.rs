//! Ce que le LauncherAgent sait faire, et sur quoi il ne sait rien faire.
//!
//! Ce fichier existe parce que la réponse était jusqu'ici dispersée : la
//! liste des versions tissables vivait côté Java (`VersionProfileRegistry`),
//! la contrainte de JVM côté Rust (`agents.rs`), et l'interface n'en voyait
//! rien du tout — elle proposait l'agent partout, y compris là où il ne
//! s'accroche à rien.
//!
//! Il sert deux appelants qui doivent dire exactement la même chose :
//! `setup_launcher_agent`, qui décide si `-javaagent:` entre dans la ligne de
//! commande, et la commande `launcher_agent_status`, qui explique à
//! l'utilisateur pourquoi il n'y entre pas.

use serde::Serialize;

use super::legacy_lwjgl3::uses_legacy_lwjgl3;

/// Version de Java minimale de `launcher-agent.jar` — celle de son
/// `--release` dans `Launcher-Agent/build.bat`. Les deux doivent bouger
/// ensemble.
pub const MIN_JAVA: u32 = 25;

/// Versions Minecraft que l'agent sait tisser — miroir Rust de
/// `VersionProfileRegistry` (Java), qui reste la source de vérité : une
/// version ajoutée là-bas et oubliée ici resterait simplement sans agent.
/// L'inverse serait plus grave (agent injecté dans une version dont aucun
/// mixin ne connaît les noms), d'où le choix de littéraux stricts des deux
/// côtés plutôt que d'une règle « tout ce qui est récent ».
///
/// `*` couvre une famille entière, comme côté Java.
const SUPPORTED: [&str; 5] = ["1.8.*", "1.21.11", "26.1", "26.1.1", "26.1.2"];

/// Ce qui empêche l'agent de se charger, quand quelque chose l'empêche.
///
/// Sérialisé tel quel vers l'interface (`launcher_agent_status`) : c'est elle
/// qui met des mots dessus, dans la langue de l'utilisateur.
#[derive(Serialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "snake_case")]
pub enum AgentBlock {
    /// Aucun profil de mixins n'existe pour cette version de Minecraft.
    Version,
    /// La version est couverte, mais ce loader-là la fait tourner sur une JVM
    /// trop ancienne pour l'agent (1.8.9 + Forge reste en Java 8).
    Loader,
    /// L'installation du launcher n'a pas les fichiers de l'agent.
    Files,
}

/// `true` si un profil de mixins couvre cette version.
pub fn version_supported(version_id: &str) -> bool {
    SUPPORTED.iter().any(|pattern| match pattern.strip_suffix('*') {
        Some(prefix) => version_id.starts_with(prefix),
        None => *pattern == version_id,
    })
}

/// Ce qui bloque l'agent pour ce couple version/loader, `None` s'il peut se
/// charger.
///
/// Ne regarde que ce qui se sait sans réseau : la version, le loader et les
/// fichiers sur le disque. La version de Java réellement obtenue n'est connue
/// qu'au lancement (elle dépend du manifeste de la version) et reste
/// vérifiée là-bas — ici on n'anticipe que le cas déterministe du 1.8.9
/// moddé, dont on sait d'avance qu'il partira en Java 8.
pub fn blocked_by(version_id: &str, loader: Option<&str>) -> Option<AgentBlock> {
    if !version_supported(version_id) {
        return Some(AgentBlock::Version);
    }
    // Dérogation Java 25 du 1.8.9 : vanilla seulement (voir `java_requirement`).
    if version_id.starts_with("1.8.") && !uses_legacy_lwjgl3(version_id, loader) {
        return Some(AgentBlock::Loader);
    }
    if !files_present() {
        return Some(AgentBlock::Files);
    }
    None
}

/// `true` si les deux jars indispensables sont déployés. Le reste (ASM, JNA)
/// dégrade des fonctionnalités sans empêcher le chargement — `setup_launcher_agent`
/// s'en plaint dans les logs, pas ici.
fn files_present() -> bool {
    super::agent_deploy::launcher_agent_dir().join("launcher-agent.jar").exists()
        && super::agent_deploy::launcher_agent_libs_dir().join("mixin.jar").exists()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn familles_et_versions_exactes() {
        assert!(version_supported("1.8.9"));
        assert!(version_supported("1.8.1"));
        assert!(version_supported("1.21.11"));
        assert!(version_supported("26.1"));
        assert!(version_supported("26.1.1"));
        assert!(version_supported("26.1.2"));
        // Pas de famille sur la ligne 26.1 : une 26.1.3 n'aurait pas sa tranche.
        assert!(!version_supported("26.1.3"));
        // Une 1.21 non testée ne doit PAS hériter du profil de la 1.21.11 —
        // même règle que côté Java, où élargir une tranche se fait version
        // testée par version testée.
        assert!(!version_supported("1.21.4"));
        assert!(!version_supported("1.20.1"));
        assert!(!version_supported("1.18"));
    }

    #[test]
    fn le_1_8_9_modde_reste_en_java_8() {
        assert_eq!(blocked_by("1.8.9", Some("forge")), Some(AgentBlock::Loader));
        assert_eq!(blocked_by("1.20.1", None), Some(AgentBlock::Version));
    }
}
