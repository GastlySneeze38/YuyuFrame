use serde::Serialize;
use sysinfo::System;

#[derive(Serialize)]
pub struct SystemMemoryInfo {
    pub total_mb: u64,
    pub available_mb: u64,
    /// RAM conseillée pour une instance, en Mo : on ne propose jamais plus de
    /// 50% de la RAM totale, ni plus que la RAM réellement disponible moins
    /// une marge de sécurité (2 Go) pour l'OS et le reste des apps —
    /// jusqu'ici aucune détection matérielle n'existait, le launcher
    /// proposait 4096 Mo par défaut à tout le monde sans regarder la machine,
    /// ce qui fait "galérer" un PC modeste dès le lancement.
    pub suggested_mb: u32,
    /// Préréglage OptiFine suggéré selon RAM+CPU : "performance" / "normal" / "quality"
    pub suggested_optifine_preset: String,
}

#[tauri::command]
pub fn system_memory_info() -> SystemMemoryInfo {
    let mut sys = System::new();
    sys.refresh_memory();
    sys.refresh_cpu_all();

    let total_mb = sys.total_memory() / 1024 / 1024;
    let available_mb = sys.available_memory() / 1024 / 1024;
    let cpu_cores = sys.cpus().len();

    let half_total = (total_mb / 2) as u32;
    let safe_available = available_mb.saturating_sub(2048) as u32;
    let mut suggested_mb = half_total.min(safe_available).max(1024);
    // Aligne sur les paliers proposés dans l'UI (1024/2048/4096/6144/8192)
    suggested_mb = ((suggested_mb / 1024).max(1) * 1024).min(8192);

    let suggested_optifine_preset = if total_mb <= 4096 || cpu_cores <= 2 {
        "performance"
    } else if total_mb <= 8192 {
        "normal"
    } else {
        "quality"
    }.to_string();

    SystemMemoryInfo { total_mb, available_mb, suggested_mb, suggested_optifine_preset }
}
