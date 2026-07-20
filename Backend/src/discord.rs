use discord_rich_presence::{activity, DiscordIpc, DiscordIpcClient};
use std::sync::{Mutex, OnceLock};

/// ID de l'application Discord YuyuFrame (https://discord.com/developers/applications).
const DISCORD_APP_ID: &str = "1357094158103347301";

/// Client IPC gardé en vie pour toute la durée du process — le crate ferme
/// la connexion dès que le client est droppé, donc un simple appel local
/// dans `setup()` sans le stocker quelque part afficherait la présence une
/// fraction de seconde puis la couperait aussitôt.
static DISCORD_CLIENT: OnceLock<Mutex<DiscordIpcClient>> = OnceLock::new();

/// Ouvre la connexion IPC locale vers Discord — named pipe sur Windows,
/// socket Unix sur Linux/Mac, entièrement géré en interne par le crate,
/// aucune distinction de plateforme à faire ici — et publie l'activité
/// initiale au lancement du launcher.
///
/// Appelée depuis un thread séparé (voir `lib.rs`, `std::thread::spawn`,
/// PAS `tokio::spawn` : le crate fait de l'IPC bloquante, pas async) : le
/// handshake peut prendre un instant, et Discord peut tout simplement ne
/// pas être lancé sur la machine — jamais bloquant/fatal pour le reste du
/// démarrage du launcher, juste un log si ça échoue à une étape ou une autre.
pub fn connect_and_announce() {
    // BUG TROUVÉ (voir cargo check) : contrairement à ce que suggérait la
    // doc générale du crate, `DiscordIpcClient::new` en v1.1.0 renvoie
    // directement `Self`, PAS `Result<Self, _>` — aucune I/O n'a lieu à la
    // construction (juste la préparation du handshake), l'échec potentiel
    // (Discord absent) ne survient qu'au vrai `connect()` juste après.
    let mut client = DiscordIpcClient::new(DISCORD_APP_ID);

    if let Err(e) = client.connect() {
        tracing::warn!("[Discord] connexion IPC échouée (Discord probablement fermé) : {e}");
        return;
    }

    let activity = activity::Activity::new()
        .state("Dans le launcher")
        .details("YuyuFrame")
        // Nécessite un asset uploadé sous cette clé exacte dans Discord
        // Developer Portal > Rich Presence > Art Assets — décommenter une
        // fois l'image ajoutée là-bas (sinon Discord ignore juste l'image,
        // sans erreur, mais autant ne pas référencer une clé qui n'existe
        // pas encore).
        // .assets(activity::Assets::new().large_image("yuyuframe_logo"))
        .timestamps(activity::Timestamps::new().start(chrono::Utc::now().timestamp()));

    if let Err(e) = client.set_activity(activity) {
        tracing::warn!("[Discord] envoi de l'activité échoué : {e}");
        return;
    }

    tracing::info!("[Discord] Rich Presence connectée, activité envoyée");
    // set() échoue seulement si déjà initialisé — ne devrait jamais arriver
    // (connect_and_announce n'est appelée qu'une fois au démarrage, voir
    // lib.rs), mais autant ignorer proprement plutôt que paniquer sur un
    // double appel futur.
    let _ = DISCORD_CLIENT.set(Mutex::new(client));
}
