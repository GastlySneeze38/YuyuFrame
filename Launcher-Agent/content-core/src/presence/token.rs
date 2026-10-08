//! Le jeton d'accès du compte, reçu du launcher.
//!
//! Le launcher l'écrit sur l'entrée standard du jeu, une ligne à chaque
//! renouvellement (`Launcher-Client/Backend/src/play/presence.rs`,
//! `feed_tokens`) : un tube que seul ce processus a reçu, rien sur le disque,
//! rien sur la ligne de commande. Il est lu ICI, par un thread à nous, et ne
//! passe jamais par Java.
//!
//! Il vit 15 minutes. Le jeton de rafraîchissement ne quitte pas le launcher :
//! launcher fermé, il n'y a plus de ligne, le dernier jeton expire, et plus
//! personne n'est reconnu jusqu'à la prochaine partie.

//!
//! Le même tube porte le réglage « Apparaître comme joueur YuyuFrame » du
//! launcher (`yf-presence-visible 0|1`, réécrit quand il change). Le jeu en a
//! besoin depuis qu'il s'annonce lui-même sur les serveurs en mode hors ligne
//! (`worker.rs`) ; tant que le launcher n'a rien dit, il ne s'annonce pas.

use std::io::BufRead;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

/// Posée par le launcher : sans elle, l'entrée standard n'est pas ce canal
/// (jeu lancé à la main, autre launcher) et on n'y touche pas — la lire
/// volerait ses lignes à une console.
const CHANNEL_ENV: &str = "YUYU_PRESENCE_STDIN";
/// Début de chaque ligne du launcher (`TOKEN_LINE_PREFIX` de son côté).
const LINE_PREFIX: &str = "yf-presence-token ";
/// Début de la ligne du réglage de visibilité (`VISIBLE_LINE_PREFIX` côté launcher).
const VISIBLE_PREFIX: &str = "yf-presence-visible ";
/// Un jeton d'accès fait quelques centaines de caractères.
const MAX_TOKEN_LEN: usize = 4096;

#[derive(Default)]
pub struct TokenStore {
    current: Mutex<Option<Arc<str>>>,
    visible: AtomicBool,
}

impl TokenStore {
    pub fn get(&self) -> Option<Arc<str>> {
        self.current.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    fn set(&self, token: &str) {
        *self.current.lock().unwrap_or_else(|p| p.into_inner()) = Some(token.into());
    }

    /// Le joueur veut-il apparaître aux autres ? Faux tant que le launcher ne l'a pas dit.
    pub fn visible(&self) -> bool {
        self.visible.load(Ordering::Relaxed)
    }
}

/// Le réglage d'une ligne `yf-presence-visible`, ou `None` si ce n'en est pas une.
fn parse_visible(line: &str) -> Option<bool> {
    match line.trim_end_matches(['\r', '\n']).strip_prefix(VISIBLE_PREFIX)? {
        "1" => Some(true),
        "0" => Some(false),
        _ => None,
    }
}

/// Le jeton d'une ligne du launcher. N'accepte que la forme d'un JWT : la
/// valeur part telle quelle dans un en-tête HTTP, où un retour à la ligne ou
/// un espace n'ont rien à faire.
fn parse_line(line: &str) -> Option<&str> {
    let token = line.trim_end_matches(['\r', '\n']).strip_prefix(LINE_PREFIX)?;
    let well_formed = !token.is_empty()
        && token.len() <= MAX_TOKEN_LEN
        && token.bytes().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'-' | b'_'));
    well_formed.then_some(token)
}

pub fn channel_open() -> bool {
    std::env::var(CHANNEL_ENV).is_ok_and(|v| v == "1")
}

/// Démarre la lecture. Faux si le thread n'a pas pu être créé.
pub fn start_reader(store: Arc<TokenStore>) -> bool {
    std::thread::Builder::new()
        .name("yf-presence-token".into())
        .spawn(move || {
            // Se termine seul quand le launcher ferme le tube (fin de fichier).
            for line in std::io::stdin().lock().lines() {
                let Ok(line) = line else { return };
                if let Some(token) = parse_line(&line) {
                    store.set(token);
                } else if let Some(visible) = parse_visible(&line) {
                    store.visible.store(visible, Ordering::Relaxed);
                }
            }
        })
        .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seule_une_ligne_du_launcher_donne_un_jeton() {
        assert_eq!(parse_line("yf-presence-token aaa.bbb-c_d.eee\n"), Some("aaa.bbb-c_d.eee"));
        assert_eq!(parse_line("yf-presence-token aaa.bbb.ccc\r\n"), Some("aaa.bbb.ccc"));
        assert_eq!(parse_line("aaa.bbb.ccc"), None);
        assert_eq!(parse_line("yf-presence-token "), None);
        // Rien qui puisse casser un en-tête HTTP.
        assert_eq!(parse_line("yf-presence-token aaa bbb"), None);
        assert_eq!(parse_line("yf-presence-token aaa\rX-Autre: 1"), None);
        assert_eq!(parse_line(&format!("yf-presence-token {}", "a".repeat(MAX_TOKEN_LEN + 1))), None);
    }

    #[test]
    fn la_visibilite_ne_se_lit_que_sur_sa_ligne() {
        assert_eq!(parse_visible("yf-presence-visible 1\n"), Some(true));
        assert_eq!(parse_visible("yf-presence-visible 0\r\n"), Some(false));
        assert_eq!(parse_visible("yf-presence-visible oui"), None);
        assert_eq!(parse_visible("yf-presence-token aaa.bbb.ccc"), None);
        // Sans un mot du launcher, le joueur ne s'annonce pas.
        assert!(!TokenStore::default().visible());
    }

    #[test]
    fn le_dernier_jeton_recu_remplace_le_precedent() {
        let store = TokenStore::default();
        assert!(store.get().is_none());
        store.set("un");
        store.set("deux");
        assert_eq!(store.get().as_deref(), Some("deux"));
    }
}
