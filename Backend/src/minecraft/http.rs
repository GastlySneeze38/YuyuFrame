use std::time::Duration;

/// Client HTTP à courte durée de vie (une poignée de requêtes, pas de
/// pooling à gros volume — voir le client partagé de `launcher::orchestrator`
/// pour ça). Bornes temporelles systématiques : sans elles, une connexion qui
/// s'ouvre puis ne répond plus (Wi-Fi qui bascule, CDN qui pend, portail
/// captif) bloque l'appelant indéfiniment.
pub fn short_lived_client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .build()
        .expect("client HTTP par défaut")
}
