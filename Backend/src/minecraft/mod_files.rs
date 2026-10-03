//! Reconnaissance de fichiers de mod par extension — centralisée ici pour ne
//! pas réimplémenter la même comparaison dans chaque fichier qui liste un
//! dossier `mods/` (instances/*, sync/archive.rs,
//! minecraft/loaders/*).
//!
//! Insensible à la casse : les outils de build produisent presque toujours
//! `.jar` en minuscules, mais Windows ne fait de toute façon pas la
//! différence sur le système de fichiers, et un utilisateur qui renomme un
//! fichier à la main peut très bien taper `.JAR`. Un `.ends_with(".jar")`
//! brut ratait silencieusement ce cas.

/// Mod actif — extension `.jar` (et PAS `.jar.disabled`).
pub fn is_enabled_jar(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".jar") && !lower.ends_with(".jar.disabled")
}

/// Mod désactivé — suffixe `.jar.disabled` (voir `mods_toggle`).
pub fn is_disabled_jar(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".jar.disabled")
}

/// `.jar` actif ou désactivé.
pub fn is_jar_file(name: &str) -> bool {
    is_enabled_jar(name) || is_disabled_jar(name)
}
