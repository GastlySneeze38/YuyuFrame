//! Ce qu'on sait des joueurs croisés, et ce qu'il reste à demander. Aucun
//! réseau ici : l'heure est un paramètre, tout se teste sans attendre.
//!
//! Une réponse sert tant qu'elle est fraîche ; passé ce délai elle sert
//! ENCORE, le temps que la nouvelle arrive — un joueur reconnu ne clignote
//! pas à chaque rafraîchissement. Seuls les joueurs qu'on continue de
//! demander sont redemandés : un joueur qu'on ne croise plus s'oublie seul.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use super::fingerprint::fingerprint;

/// Un joueur présent peut quitter le jeu : le serveur l'oublie 5 minutes
/// après son dernier signal, on redemande au même rythme.
const PRESENT_TTL: Duration = Duration::from_secs(300);
/// Un joueur absent peut lancer YuyuFrame plus tard, mais c'est rare et
/// chaque question compte dans le quota : on attend plus longtemps.
const ABSENT_TTL: Duration = Duration::from_secs(600);
/// Sans question depuis ce délai, le joueur n'est plus dans les parages.
const FORGET_AFTER: Duration = Duration::from_secs(1800);
/// Empreintes par requête — la limite du serveur (`MAX_LOOKUP`).
pub const MAX_BATCH: usize = 200;
/// Empreintes par heure. Le serveur en accepte 4 000 par compte : on garde de
/// la marge pour ne jamais se faire refuser, un refus coûtant dix minutes.
const HOURLY_BUDGET: u32 = 3000;
const BUDGET_WINDOW: Duration = Duration::from_secs(3600);
/// Garde-fou : un serveur de jeu qui ferait défiler des milliers de faux
/// profils ne doit pas faire gonfler la mémoire.
const MAX_ENTRIES: usize = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Answer {
    /// Pas encore de réponse — ou pas de moyen d'en avoir.
    Unknown = 0,
    Present = 1,
    Absent = 2,
}

/// L'identité d'un joueur vu en jeu : l'UUID et le pseudo de son PROFIL.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct PlayerKey {
    uuid: u128,
    name: [u8; 16],
    len: u8,
}

impl PlayerKey {
    /// `None` pour ce qui ne peut pas être un joueur YuyuFrame reconnu, et
    /// pour quoi on ne dépense donc aucune question :
    /// - un UUID qui n'est pas en version 4 — compte hors ligne (version 3,
    ///   dérivé du pseudo, partagé par tous les homonymes), faux joueur d'un
    ///   serveur (PNJ, lignes de mise en page de la liste Tab) ;
    /// - un pseudo qui n'a pas la forme d'un pseudo Minecraft.
    pub fn new(uuid: u128, name: &[u8]) -> Option<Self> {
        let version = (uuid >> 76) & 0xf;
        if version != 4 || name.is_empty() || name.len() > 16 {
            return None;
        }
        if !name.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'_') {
            return None;
        }
        let mut lower = [0u8; 16];
        for (dst, src) in lower.iter_mut().zip(name) {
            *dst = src.to_ascii_lowercase();
        }
        Some(Self { uuid, name: lower, len: name.len() as u8 })
    }

    fn fingerprint(&self) -> [u8; 32] {
        fingerprint(self.uuid, &self.name[..self.len as usize])
    }
}

struct Entry {
    fingerprint: [u8; 32],
    answer: Answer,
    /// Quand la réponse a été reçue ; `None` tant qu'il n'y en a pas.
    checked_at: Option<Instant>,
    /// Déjà dans la file ou dans une requête en cours.
    queued: bool,
    /// Dernière fois que Java a posé la question.
    asked_at: Instant,
}

pub struct Directory {
    entries: HashMap<PlayerKey, Entry>,
    queue: VecDeque<PlayerKey>,
    window_start: Instant,
    spent: u32,
}

impl Directory {
    pub fn new(now: Instant) -> Self {
        Self { entries: HashMap::new(), queue: VecDeque::new(), window_start: now, spent: 0 }
    }

    /// La question de Java. Ne bloque jamais : rend ce qu'on sait, et met le
    /// joueur en file si la réponse manque ou a vieilli.
    pub fn query(&mut self, key: PlayerKey, now: Instant) -> Answer {
        if let Some(entry) = self.entries.get_mut(&key) {
            entry.asked_at = now;
            let stale = match (entry.checked_at, entry.answer) {
                (None, _) => true,
                (Some(at), Answer::Present) => now.duration_since(at) >= PRESENT_TTL,
                (Some(at), _) => now.duration_since(at) >= ABSENT_TTL,
            };
            if stale && !entry.queued {
                entry.queued = true;
                self.queue.push_back(key);
            }
            return entry.answer;
        }
        if self.entries.len() >= MAX_ENTRIES {
            return Answer::Unknown;
        }
        self.entries.insert(key, Entry { fingerprint: key.fingerprint(), answer: Answer::Unknown, checked_at: None, queued: true, asked_at: now });
        self.queue.push_back(key);
        Answer::Unknown
    }

    /// Le prochain paquet à envoyer, dans la limite du budget de l'heure.
    pub fn take_batch(&mut self, now: Instant) -> Vec<(PlayerKey, [u8; 32])> {
        if now.duration_since(self.window_start) >= BUDGET_WINDOW {
            self.window_start = now;
            self.spent = 0;
        }
        let allowed = (HOURLY_BUDGET.saturating_sub(self.spent) as usize).min(MAX_BATCH);
        let mut batch = Vec::new();
        while batch.len() < allowed {
            let Some(key) = self.queue.pop_front() else { break };
            // Oublié entre-temps : rien à demander.
            if let Some(entry) = self.entries.get(&key) {
                batch.push((key, entry.fingerprint));
            }
        }
        self.spent += batch.len() as u32;
        batch
    }

    /// Les réponses du serveur, dans l'ordre du paquet.
    pub fn apply(&mut self, batch: &[(PlayerKey, [u8; 32])], present: &[bool], now: Instant) {
        for ((key, _), is_present) in batch.iter().zip(present) {
            if let Some(entry) = self.entries.get_mut(key) {
                entry.answer = if *is_present { Answer::Present } else { Answer::Absent };
                entry.checked_at = Some(now);
                entry.queued = false;
            }
        }
    }

    /// La requête a échoué : le paquet repart en tête de file. Le budget
    /// dépensé n'est pas rendu — le serveur a pu compter ces questions.
    pub fn give_back(&mut self, batch: Vec<(PlayerKey, [u8; 32])>) {
        for (key, _) in batch.into_iter().rev() {
            if self.entries.contains_key(&key) {
                self.queue.push_front(key);
            }
        }
    }

    /// Oublie les joueurs qu'on ne croise plus.
    pub fn sweep(&mut self, now: Instant) {
        self.entries.retain(|_, e| now.duration_since(e.asked_at) < FORGET_AFTER);
    }

    /// (joueurs en mémoire, reconnus YuyuFrame, en attente de réponse).
    pub fn counts(&self) -> (usize, usize, usize) {
        let present = self.entries.values().filter(|e| e.answer == Answer::Present).count();
        let pending = self.entries.values().filter(|e| e.queued).count();
        (self.entries.len(), present, pending)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// UUID version 4 (le chiffre de version est le 13e chiffre hexadécimal).
    fn online(n: u128) -> u128 {
        (n << 80) | (4u128 << 76) | n
    }

    fn key(n: u128, name: &str) -> PlayerKey {
        PlayerKey::new(online(n), name.as_bytes()).unwrap()
    }

    #[test]
    fn seuls_les_comptes_microsoft_au_pseudo_valide_sont_demandes() {
        assert!(PlayerKey::new(online(1), b"Steve").is_some());
        // UUID hors ligne de « Steve » (version 3) et UUID de PNJ (version 2).
        assert!(PlayerKey::new(0x5627dd98e6be3c21b8a8e92344183641, b"Steve").is_none());
        assert!(PlayerKey::new(0x5627dd98e6be2c21b8a8e92344183641, b"Steve").is_none());
        assert!(PlayerKey::new(online(1), b"").is_none());
        assert!(PlayerKey::new(online(1), b"beaucouptroplongpseudo").is_none());
        assert!(PlayerKey::new(online(1), "§aSteve".as_bytes()).is_none());
    }

    #[test]
    fn la_casse_du_pseudo_ne_compte_pas() {
        assert_eq!(key(1, "Steve"), key(1, "sTEVE"));
        assert_ne!(key(1, "Steve"), key(1, "Alex"));
    }

    #[test]
    fn une_question_est_mise_en_file_une_seule_fois() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        assert_eq!(dir.query(key(1, "Steve"), now), Answer::Unknown);
        assert_eq!(dir.query(key(1, "Steve"), now), Answer::Unknown);
        let batch = dir.take_batch(now);
        assert_eq!(batch.len(), 1);
        // Requête en cours : la question n'est pas redoublée.
        dir.query(key(1, "Steve"), now);
        assert!(dir.take_batch(now).is_empty());

        dir.apply(&batch, &[true], now);
        assert_eq!(dir.query(key(1, "Steve"), now), Answer::Present);
        assert!(dir.take_batch(now).is_empty(), "une réponse fraîche n'est pas redemandée");
    }

    #[test]
    fn une_reponse_vieillie_sert_encore_le_temps_d_etre_rafraichie() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.query(key(1, "Steve"), now);
        dir.query(key(2, "Alex"), now);
        let batch = dir.take_batch(now);
        dir.apply(&batch, &[true, false], now);

        let later = now + PRESENT_TTL;
        assert_eq!(dir.query(key(1, "Steve"), later), Answer::Present, "pas de clignotement");
        // L'absent attend plus longtemps avant d'être redemandé.
        assert_eq!(dir.query(key(2, "Alex"), later), Answer::Absent);
        let refresh = dir.take_batch(later);
        assert_eq!(refresh.len(), 1);
        assert_eq!(refresh[0].0, key(1, "Steve"));

        dir.apply(&refresh, &[false], later);
        assert_eq!(dir.query(key(1, "Steve"), later), Answer::Absent);
        dir.query(key(2, "Alex"), now + ABSENT_TTL);
        assert_eq!(dir.take_batch(now + ABSENT_TTL).len(), 1);
    }

    #[test]
    fn un_echec_remet_le_paquet_en_tete() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.query(key(1, "Steve"), now);
        let batch = dir.take_batch(now);
        dir.query(key(2, "Alex"), now);
        dir.give_back(batch);
        let again = dir.take_batch(now);
        assert_eq!(again.iter().map(|(k, _)| *k).collect::<Vec<_>>(), vec![key(1, "Steve"), key(2, "Alex")]);
    }

    #[test]
    fn les_paquets_et_le_budget_de_l_heure_sont_bornes() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        for n in 0..(MAX_BATCH as u128 + 50) {
            dir.query(key(n + 1, "Joueur"), now);
        }
        assert_eq!(dir.take_batch(now).len(), MAX_BATCH);
        assert_eq!(dir.take_batch(now).len(), 50);

        // Budget de l'heure épuisé : plus rien ne part, puis la fenêtre se rouvre.
        dir.spent = HOURLY_BUDGET;
        dir.query(key(9999, "Tardif"), now);
        assert!(dir.take_batch(now).is_empty());
        assert_eq!(dir.take_batch(now + BUDGET_WINDOW).len(), 1);
    }

    #[test]
    fn un_joueur_qu_on_ne_croise_plus_est_oublie() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.query(key(1, "Steve"), now);
        dir.query(key(2, "Alex"), now);
        let later = now + FORGET_AFTER;
        dir.query(key(2, "Alex"), later);
        dir.sweep(later);
        assert_eq!(dir.counts().0, 1);
        // La file peut encore nommer l'oublié : il est sauté.
        assert_eq!(dir.take_batch(later).len(), 1);
    }
}
