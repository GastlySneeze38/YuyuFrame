// Passerelle générique pour les événements PostHog qui n'ont pas de
// contrepartie backend naturelle (clic, ouverture de modal, recherche...) —
// tout ce qui passe par une vraie commande Tauri (lancement, création
// d'instance, install de mod...) capture directement côté Rust, voir les
// autres modules `commands/*`.

#[tauri::command]
pub async fn track_event(event: String, properties: Option<serde_json::Value>) -> Result<(), String> {
    crate::integrations::analytics::capture(&event, properties.unwrap_or_else(|| serde_json::json!({})));
    Ok(())
}

/// État actuel de l'opt-out analytics — lu par Settings.tsx au montage.
#[tauri::command]
pub async fn analytics_get_disabled() -> Result<bool, String> {
    Ok(crate::integrations::analytics::is_disabled())
}

/// Active/désactive l'envoi d'événements PostHog (voir Settings.tsx,
/// section Confidentialité) — persisté immédiatement, sans redémarrage requis.
#[tauri::command]
pub async fn analytics_set_disabled(disabled: bool) -> Result<(), String> {
    crate::integrations::analytics::set_disabled(disabled);
    Ok(())
}
