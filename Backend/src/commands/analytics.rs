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
