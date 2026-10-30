//! Ce que le joueur fait en jeu, pour ses amis : le serveur où il est et le
//! duel en cours. Le jeu l'annonce au launcher (`activity`), qui le joint à son
//! signal de présence (`Launcher-Client/Backend/src/friends/`).
//!
//! Deux sources, chacune sur son fil : l'adresse arrive par le contexte de
//! présence (`presence::bridge::yf_presence_context`, une fois par seconde),
//! le duel par le journal de combat (`combat::writer`). Une ligne ne part que
//! quand quelque chose a changé.
//!
//! L'adresse est déjà filtrée côté Java (`PresenceContext.typedForm`) : jamais
//! une machine locale ni un réseau privé, et rien du tout quand le réglage
//! « Serveurs Microsoft uniquement » du module Joueur est allumé. Le launcher
//! et le serveur refiltrent de leur côté, et c'est le serveur qui décide qui la
//! voit (personne par défaut).

use std::sync::{Mutex, MutexGuard};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct Activity {
    /// « hôte:port ».
    server: Option<String>,
    /// Un duel en cours : compte-t-il pour le classement, et son mode
    /// (`combat::journal::live_mode` : 1 masse, 2 épée, 3 autre, 0 inconnu).
    duel: Option<(bool, u8)>,
}

static CURRENT: Mutex<Activity> = Mutex::new(Activity { server: None, duel: None });

fn current() -> MutexGuard<'static, Activity> {
    CURRENT.lock().unwrap_or_else(|p| p.into_inner())
}

/// La ligne envoyée au launcher : `activity⇥<serveur ou ->⇥<- | casual | ranked>⇥<mode>`.
fn line(activity: &Activity) -> String {
    let server = activity.server.as_deref().filter(|s| !s.contains(['\t', '\r', '\n'])).unwrap_or("-");
    let (duel, mode) = match activity.duel {
        None => ("-", "-"),
        Some((ranked, mode)) => (
            if ranked { "ranked" } else { "casual" },
            match mode {
                1 => "mace",
                2 => "sword",
                _ => "other",
            },
        ),
    };
    format!("activity\t{server}\t{duel}\t{mode}")
}

/// Applique `change` ; rend la ligne à envoyer si quelque chose a changé.
fn update(state: &mut Activity, change: impl FnOnce(&mut Activity)) -> Option<String> {
    let before = state.clone();
    change(state);
    (before != *state).then(|| line(state))
}

fn send(line: Option<String>) {
    if let (Some(line), Some(link)) = (line, super::channel::link()) {
        link.notify(&line);
    }
}

/// Le serveur où l'on joue, `None` en solo, au menu ou quand l'adresse n'est pas partagée.
pub fn set_server(server: Option<String>) {
    let line = update(&mut current(), |a| a.server = server);
    send(line);
}

/// Le duel en cours (compte-t-il, mode) ou `None`. Le mode est celui de
/// l'ouverture : changer d'arme en plein duel ne fait pas partir de ligne.
pub fn set_duel(duel: Option<(bool, u8)>) {
    let line = update(&mut current(), |a| a.duel = merge_duel(a.duel, duel));
    send(line);
}

fn merge_duel(current: Option<(bool, u8)>, next: Option<(bool, u8)>) -> Option<(bool, u8)> {
    match (current, next) {
        (Some((_, mode)), Some((ranked, _))) => Some((ranked, mode)),
        (_, next) => next,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn une_ligne_seulement_quand_ca_change() {
        let mut state = Activity::default();
        assert_eq!(update(&mut state, |a| a.server = Some("play.exemple.fr:25565".into())).as_deref(), Some("activity\tplay.exemple.fr:25565\t-\t-"));
        assert_eq!(update(&mut state, |a| a.server = Some("play.exemple.fr:25565".into())), None);
        assert_eq!(update(&mut state, |a| a.duel = Some((true, 1))).as_deref(), Some("activity\tplay.exemple.fr:25565\tranked\tmace"));
        assert_eq!(update(&mut state, |a| *a = Activity::default()).as_deref(), Some("activity\t-\t-\t-"));
    }

    #[test]
    fn le_mode_d_un_duel_est_celui_de_son_ouverture() {
        let mut state = Activity { server: None, duel: Some((true, 1)) };
        let line = update(&mut state, |a| a.duel = merge_duel(a.duel, Some((true, 2))));
        assert_eq!(line, None, "changer d'arme en duel ne dit rien");
        // Fin du duel, puis un nouveau à l'épée : son mode à lui.
        assert_eq!(merge_duel(merge_duel(Some((true, 1)), None), Some((false, 2))), Some((false, 2)));
    }

    #[test]
    fn une_adresse_ne_peut_pas_decaler_les_champs() {
        assert_eq!(line(&Activity { server: Some("a\tb".into()), duel: None }), "activity\t-\t-\t-");
    }
}
