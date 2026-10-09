//! Le jeton d'accès du compte YuyuFrame, et le réglage « Apparaître comme
//! joueur YuyuFrame », tels que le launcher les donne.
//!
//! Ils arrivent par le canal du jeu (`crate::link`, question `token`,
//! redemandée à intervalle régulier) : un tube nommé que seul ce processus
//! peut ouvrir. Rien sur le disque, rien sur la ligne de commande, et le
//! jeton ne passe jamais par Java.
//!
//! Il vit 15 minutes. Le jeton de rafraîchissement ne quitte pas le launcher :
//! launcher fermé, le canal se coupe, le dernier jeton expire, et plus
//! personne n'est reconnu jusqu'à la prochaine partie.
//!
//! Tant que le launcher n'a rien dit de la visibilité, le jeu ne s'annonce
//! pas (serveurs en mode hors ligne, voir `worker.rs`).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

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

    /// Le joueur veut-il apparaître aux autres ? Faux tant que le launcher ne l'a pas dit.
    pub fn visible(&self) -> bool {
        self.visible.load(Ordering::Relaxed)
    }

    /// Ce que le launcher vient de répondre. `token` à `None` : personne
    /// n'est connecté à YuyuFrame, l'ancien jeton ne doit plus servir.
    ///
    /// N'accepte que la forme d'un JWT : la valeur part telle quelle dans un
    /// en-tête HTTP, où un retour à la ligne ou un espace n'ont rien à faire.
    pub fn update(&self, token: Option<&str>, visible: bool) {
        self.visible.store(visible, Ordering::Relaxed);
        let token = token.filter(|t| well_formed(t));
        let mut current = self.current.lock().unwrap_or_else(|p| p.into_inner());
        if current.as_deref() != token {
            *current = token.map(Into::into);
        }
    }
}

fn well_formed(token: &str) -> bool {
    !token.is_empty() && token.len() <= MAX_TOKEN_LEN && token.bytes().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'-' | b'_'))
}

/// Le magasin unique du processus : rempli par le canal du jeu, lu par le
/// thread de présence.
pub fn store() -> Arc<TokenStore> {
    static STORE: OnceLock<Arc<TokenStore>> = OnceLock::new();
    STORE.get_or_init(Default::default).clone()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seul_un_jeton_bien_forme_est_garde() {
        let store = TokenStore::default();
        store.update(Some("aaa.bbb-c_d.eee"), true);
        assert_eq!(store.get().as_deref(), Some("aaa.bbb-c_d.eee"));
        // Rien qui puisse casser un en-tête HTTP : le jeton précédent ne sert plus non plus.
        store.update(Some("aaa bbb"), true);
        assert!(store.get().is_none());
        store.update(Some("aaa\rX-Autre: 1"), true);
        assert!(store.get().is_none());
        store.update(Some(&"a".repeat(MAX_TOKEN_LEN + 1)), true);
        assert!(store.get().is_none());
    }

    #[test]
    fn le_dernier_jeton_recu_remplace_le_precedent() {
        let store = TokenStore::default();
        assert!(store.get().is_none());
        store.update(Some("un"), false);
        store.update(Some("deux"), false);
        assert_eq!(store.get().as_deref(), Some("deux"));
        // Déconnexion de YuyuFrame dans le launcher : plus de jeton.
        store.update(None, false);
        assert!(store.get().is_none());
    }

    #[test]
    fn sans_un_mot_du_launcher_le_joueur_ne_s_annonce_pas() {
        let store = TokenStore::default();
        assert!(!store.visible());
        store.update(None, true);
        assert!(store.visible());
    }
}
