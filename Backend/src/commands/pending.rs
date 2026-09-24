//! Événements émis pendant que la fenêtre n'existait plus.
//!
//! ── Le problème ───────────────────────────────────────────────────────────
//! Avec « masquer au lancement », la fenêtre n'est pas seulement cachée : elle
//! est FERMÉE, pour rendre vraiment la mémoire de la webview. Il n'y a donc
//! plus personne pour écouter. Tout ce que le launcher annonce pendant la
//! partie — sa fin, un plantage — part dans le vide, et la fenêtre recréée à
//! la fin de la partie repart d'une page blanche qui n'a rien vu.
//!
//! C'est ce qui faisait disparaître la modale de plantage et la demande
//! d'avis : les événements qui les déclenchent tombaient pile pendant le seul
//! moment où plus aucune fenêtre n'écoutait.
//!
//! ── Le principe ───────────────────────────────────────────────────────────
//! Même boîte aux lettres que pour les liens `yuyuframe://` (voir
//! `deep_link.rs`) : l'émetteur ne change pas de comportement, il dépose en
//! plus une copie quand aucune fenêtre n'est ouverte. Le frontend vient les
//! chercher à son montage et les rejoue.
//!
//! On garde l'ordre d'arrivée : un plantage précède la fin de partie, et le
//! frontend s'appuie dessus (voir la demande d'avis, qui se tait après un
//! plantage).

use serde_json::Value;
use std::sync::Mutex;
use tauri::Emitter;

/// Un événement mis de côté, tel qu'il aurait été reçu.
#[derive(Clone, serde::Serialize)]
pub struct PendingEvent {
    pub name: String,
    pub payload: Value,
}

/// Borne haute : une partie ne produit que quelques événements, mais rien ne
/// garantit qu'une fenêtre finira par venir les chercher. Sans plafond, une
/// boucle d'échecs de lancement ferait enfler la liste sans fin.
const MAX: usize = 32;

static PENDING: Mutex<Vec<PendingEvent>> = Mutex::new(Vec::new());

/// Émet l'événement, et en garde une copie si personne ne peut l'entendre.
///
/// Toujours émettre, même sans fenêtre : une deuxième fenêtre (console de
/// jeu) peut écouter, et l'émission ne coûte rien quand il n'y a personne.
pub fn emit_or_stash(app: &tauri::AppHandle, name: &str, payload: Value) {
    let _ = app.emit(name, payload.clone());
    if crate::state::window_open() {
        return;
    }
    let mut pending = PENDING.lock().unwrap();
    if pending.len() >= MAX {
        pending.remove(0);
    }
    pending.push(PendingEvent { name: name.to_string(), payload });
}

/// Vidée par le frontend à son montage — une seule fenêtre les rejoue.
#[tauri::command]
pub fn take_pending_events() -> Vec<PendingEvent> {
    std::mem::take(&mut *PENDING.lock().unwrap())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Le plafond jette les plus anciens, pas les plus récents : c'est la fin
    /// de la dernière partie qui intéresse quelqu'un, pas celle d'avant-hier.
    #[test]
    fn keeps_the_latest_events_when_full() {
        let mut pending: Vec<PendingEvent> = Vec::new();
        for i in 0..(MAX + 5) {
            if pending.len() >= MAX {
                pending.remove(0);
            }
            pending.push(PendingEvent { name: format!("e{i}"), payload: Value::Null });
        }
        assert_eq!(pending.len(), MAX);
        assert_eq!(pending.first().unwrap().name, "e5");
        assert_eq!(pending.last().unwrap().name, format!("e{}", MAX + 4));
    }
}
