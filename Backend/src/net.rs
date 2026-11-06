//! Clients HTTP partagés (2026-11-06, audit des chargements réseau).
//!
//! Avant, la recherche Modrinth, le catalogue de serveurs, les notes de
//! version… construisaient un `reqwest::Client` à CHAQUE requête : chaque
//! recherche refaisait la résolution DNS, la connexion TCP et la poignée de
//! main TLS — trois allers-retours de plus, ce qui se sent sur une connexion
//! lente. Un client garde ses connexions ouvertes et les réutilise : il faut
//! donc le construire une fois.
//!
//! Deux clients, parce que deux usages :
//! - `api()` : réponses JSON. Compression gzip/brotli acceptée (−79 % sur une
//!   recherche Modrinth de 20 résultats, mesuré), délai total de 30 s.
//! - `files()` : fichiers (mods, packs, modpacks). Pas de compression — un
//!   `.jar` est déjà compressé, et un `Content-Length` exact sert à la barre
//!   de progression — et pas de délai total, un gros fichier pouvant prendre
//!   plusieurs minutes : seul un silence de 30 s coupe la requête.
//!
//! Les deux bornent la connexion à 10 s : sans ça, un Wi-Fi qui bascule ou un
//! portail captif bloquerait l'appelant indéfiniment.

use std::sync::OnceLock;
use std::time::Duration;

const USER_AGENT: &str = concat!("YuyuFrame/", env!("CARGO_PKG_VERSION"), " (yuyuframe.eu)");

/// Client des API (Modrinth, CurseForge, site, LauncherAPI hors session…).
pub fn api() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .pool_idle_timeout(Duration::from_secs(90))
            .gzip(true)
            .brotli(true)
            .build()
            .expect("client HTTP des API")
    })
}

/// Client des téléchargements de fichiers.
pub fn files() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(10))
            .read_timeout(Duration::from_secs(30))
            .pool_idle_timeout(Duration::from_secs(90))
            .no_gzip()
            .no_brotli()
            .build()
            .expect("client HTTP des fichiers")
    })
}
