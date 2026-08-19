use serde::Serialize;
use sysinfo::System;

#[derive(Serialize)]
pub struct SystemMemoryInfo {
    pub total_mb: u64,
    pub available_mb: u64,
    /// RAM conseillée pour une instance, en Mo, alignée sur les paliers
    /// proposés dans l'UI (2048/3072/4096/5120/6144/7168/8192) — jusqu'ici
    /// aucune détection matérielle n'existait, le launcher proposait 4096 Mo
    /// par défaut à tout le monde sans regarder la machine, ce qui fait
    /// "galérer" un PC modeste dès le lancement.
    pub suggested_mb: u32,
}

/// Arrondit `mb` au multiple de 1024 inférieur (jamais au-dessus — on ne
/// veut jamais suggérer plus que ce qui a été calculé comme sûr), borné à
/// `[min_mb, max_mb]`.
fn align_to_1024(mb: u32, min_mb: u32, max_mb: u32) -> u32 {
    ((mb / 1024).max(min_mb / 1024) * 1024).min(max_mb)
}

/// RAM conseillée pour une instance, en Mo — c'est le cœur du rework RAM
/// demandé après la Phase 4 : la recommandation générique (RAM système
/// seule) ne suffisait pas, un modpack Forge à 80 mods n'a pas les mêmes
/// besoins qu'un vanilla nu.
///
/// - `loader`/`mod_count` connus (`Some`) : palier par nombre de mods
///   (≤5 → 5 Go, 6-69 → 6 Go, ≥70 → 8 Go max) combiné à un plancher par
///   loader (Forge/NeoForge, overhead ModLauncher/FML nettement plus lourd
///   que Fabric/Quilt/vanilla, même avec peu de mods) — le plus grand des
///   deux l'emporte.
/// - Dans tous les cas, la RAM RÉELLEMENT DISPONIBLE reste prioritaire :
///   recommander 6 Go pour Forge sur une machine qui n'a que 2 Go de libre
///   mènerait droit à l'OOM. On ne recommande jamais plus que ce que la
///   machine peut fournir maintenant, quel que soit le palier mods/loader.
/// - `loader`/`mod_count` absents (`None`) : repli sur la suggestion
///   générique RAM-système seule (ancien comportement).
#[tauri::command]
pub fn system_memory_info(loader: Option<String>, mod_count: Option<u32>) -> SystemMemoryInfo {
    let mut sys = System::new();
    sys.refresh_memory();

    let total_mb = sys.total_memory() / 1024 / 1024;
    let available_mb = sys.available_memory() / 1024 / 1024;

    let half_total = (total_mb / 2) as u32;
    let os_reserve_mb = ((total_mb / 4) as u32).max(2048);
    let generic_mb = half_total.min((total_mb as u32).saturating_sub(os_reserve_mb)).max(1024);

    let suggested_mb = if loader.is_none() && mod_count.is_none() {
        align_to_1024(generic_mb, 1024, 8192)
    } else {
        let mod_tier_mb: u32 = match mod_count.unwrap_or(0) {
            0..=5 => 5120,
            6..=69 => 6144,
            _ => 8192,
        };
        let loader_floor_mb: u32 = match loader.as_deref() {
            Some("forge") | Some("neoforge") => 6144,
            _ => 0,
        };
        let raw_mb = mod_tier_mb.max(loader_floor_mb);
        let available_cap_mb = (available_mb as u32).max(1024);
        align_to_1024(raw_mb.min(available_cap_mb), 1024, 8192)
    };

    SystemMemoryInfo { total_mb, available_mb, suggested_mb }
}
