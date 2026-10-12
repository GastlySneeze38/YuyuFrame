//! Ce qu'on sait des joueurs croisés, et ce qu'il reste à demander. Aucun
//! réseau ici : l'heure est un paramètre, tout se teste sans attendre.
//!
//! Une réponse sert tant qu'elle est fraîche ; passé ce délai elle sert
//! ENCORE, le temps que la nouvelle arrive — un joueur reconnu ne clignote
//! pas à chaque rafraîchissement. Seuls les joueurs qu'on continue de
//! demander sont redemandés : un joueur qu'on ne croise plus s'oublie seul.
//!
//! Deux sortes de joueurs, deux files, deux routes de l'API :
//! - UUID version 4 : un compte Microsoft, sur un serveur de jeu qui vérifie
//!   les comptes. Son empreinte ne dépend que de lui (`/presence/lookup`).
//! - UUID version 3 : un joueur d'un serveur en mode hors ligne, où l'UUID
//!   est dérivé du pseudo. Son empreinte porte l'adresse du serveur, sous les
//!   formes qu'on en connaît, et ne vaut que tant qu'on y est
//!   (`/presence/offline`, qui sert aussi à s'y annoncer soi-même). Sans
//!   serveur connu — solo, ou réglage « Serveurs Microsoft uniquement » — ces
//!   joueurs répondent « absent » sans que rien ne parte.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use super::fingerprint::{fingerprint, offline_fingerprint};

/// Un joueur présent peut quitter le jeu : le serveur l'oublie 5 minutes
/// après son dernier signal, on redemande au même rythme.
const PRESENT_TTL: Duration = Duration::from_secs(300);
/// Un joueur absent peut lancer YuyuFrame plus tard, mais c'est rare et
/// chaque question compte dans le quota : on attend plus longtemps.
const ABSENT_TTL: Duration = Duration::from_secs(600);
/// Les premiers « absent » d'un joueur sont revérifiés vite. On pose la
/// question à l'instant où il apparaît, souvent AVANT que son propre signal
/// soit arrivé au serveur : sur un serveur hors ligne il ne s'annonce qu'une
/// fois entré, et après un changement de compte en jeu la preuve du nouveau
/// compte prend quelques secondes. Sans cela, il restait sans logo pendant
/// les dix minutes d'`ABSENT_TTL`.
const ABSENT_RECHECKS: [Duration; 2] = [Duration::from_secs(8), Duration::from_secs(40)];
/// Les questions suivent les arrivées. Un joueur qu'on voit pour la première
/// fois est une arrivée ; chaque arrivée fait partir les questions en attente
/// et redemander les arrivés récents restés « absents ». Sur une liste qui
/// bouge beaucoup (lobby d'un grand serveur), on attend trois arrivées pour
/// le faire ; sur une liste calme (survie entre amis), chaque arrivée compte.
const JOINS_PER_ROUND_BUSY: u32 = 3;
/// À partir de ce nombre d'arrivées dans la fenêtre, la liste « bouge beaucoup ».
const BUSY_JOINS: usize = 10;
const BUSY_WINDOW: Duration = Duration::from_secs(60);
/// Sur une liste agitée, des arrivées en attente ne restent jamais plus
/// longtemps sans réponse, même si la troisième ne vient pas.
const HOLD_MAX: Duration = Duration::from_secs(6);
/// Un « absent » n'est redemandé par les arrivées des autres que s'il est
/// lui-même arrivé depuis peu, pas plus de tant de fois, et pas coup sur coup.
const RECENT_ARRIVAL: Duration = Duration::from_secs(600);
const MAX_JOIN_RECHECKS: u8 = 2;
const JOIN_RECHECK_GAP: Duration = Duration::from_secs(3);
/// Sans question depuis ce délai, le joueur n'est plus dans les parages.
const FORGET_AFTER: Duration = Duration::from_secs(1800);
/// Empreintes par requête — la limite du serveur (`MAX_LOOKUP`).
pub const MAX_BATCH: usize = 200;
/// Empreintes par heure. Le serveur en accepte 4 000 par compte : on garde de
/// la marge pour ne jamais se faire refuser, un refus coûtant dix minutes.
const HOURLY_BUDGET: u32 = 3000;
const BUDGET_WINDOW: Duration = Duration::from_secs(3600);
/// Les mêmes bornes pour les serveurs hors ligne (`MAX_OFFLINE_LOOKUP` et
/// `OFFLINE_LOOKUPS_PER_HOUR` côté serveur, 1 000 et 20 000) : toute la liste
/// des joueurs part d'un coup, chacun sous deux formes d'adresse au plus.
pub const MAX_OFFLINE_BATCH: usize = 1000;
const OFFLINE_HOURLY_BUDGET: u32 = 15_000;
/// Cadence de sa propre annonce tant que le serveur n'a pas donné la sienne.
const DEFAULT_RENEW: Duration = Duration::from_secs(120);
/// Bornes de la cadence annoncée par le serveur : une valeur absurde ne doit
/// ni marteler l'API ni faire disparaître le joueur.
const MIN_RENEW_SECS: u64 = 30;
const MAX_RENEW_SECS: u64 = 240;
/// Formes de l'adresse d'un serveur : le nom tapé, l'adresse IP jointe.
pub const MAX_SERVER_FORMS: usize = 2;
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

/// Une question prête à partir : le joueur et l'une de ses empreintes. Un
/// joueur d'un serveur hors ligne en a une par forme d'adresse, à la suite.
pub type Asked = Vec<(PlayerKey, [u8; 32])>;

/// L'adresse d'un serveur de jeu telle qu'elle entre dans une empreinte :
/// « hôte:port » en minuscules. `None` pour ce qui n'en a pas la forme — on
/// ne hache pas n'importe quoi, deux joueurs doivent tomber sur la même
/// valeur.
pub fn server_form(raw: &[u8]) -> Option<Vec<u8>> {
    if raw.is_empty() || raw.len() > 255 {
        return None;
    }
    if !raw.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'-' | b'_' | b':' | b'[' | b']')) {
        return None;
    }
    Some(raw.to_ascii_lowercase())
}

/// L'identité d'un joueur vu en jeu : l'UUID et le pseudo de son PROFIL.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct PlayerKey {
    uuid: u128,
    name: [u8; 16],
    len: u8,
    /// UUID version 3 : joueur d'un serveur en mode hors ligne.
    offline: bool,
}

impl PlayerKey {
    /// `None` pour ce qui ne peut pas être un joueur YuyuFrame reconnu, et
    /// pour quoi on ne dépense donc aucune question :
    /// - un UUID qui n'est ni en version 4 (compte Microsoft) ni en version 3
    ///   (dérivé du pseudo par un serveur hors ligne) — faux joueur d'un
    ///   serveur : PNJ, lignes de mise en page de la liste Tab ;
    /// - un pseudo qui n'a pas la forme d'un pseudo Minecraft.
    pub fn new(uuid: u128, name: &[u8]) -> Option<Self> {
        let version = (uuid >> 76) & 0xf;
        if !(version == 3 || version == 4) || name.is_empty() || name.len() > 16 {
            return None;
        }
        if !name.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'_') {
            return None;
        }
        let mut lower = [0u8; 16];
        for (dst, src) in lower.iter_mut().zip(name) {
            *dst = src.to_ascii_lowercase();
        }
        Some(Self { uuid, name: lower, len: name.len() as u8, offline: version == 3 })
    }

    #[cfg(test)]
    fn is_offline(&self) -> bool {
        self.offline
    }

    fn name(&self) -> &[u8] {
        &self.name[..self.len as usize]
    }
}

struct Entry {
    /// Une empreinte pour un compte Microsoft ; pour un joueur hors ligne,
    /// une par forme de l'adresse du serveur.
    prints: [[u8; 32]; MAX_SERVER_FORMS],
    forms: u8,
    answer: Answer,
    /// Réponses « absent » d'affilée (voir `ABSENT_RECHECKS`).
    absent_streak: u8,
    /// Première fois qu'on a vu ce joueur : son arrivée, de notre point de vue.
    first_seen: Instant,
    /// Fois où l'arrivée d'un autre l'a fait redemander (`MAX_JOIN_RECHECKS`).
    join_rechecks: u8,
    /// Quand la réponse a été reçue ; `None` tant qu'il n'y en a pas.
    checked_at: Option<Instant>,
    /// Déjà dans la file ou dans une requête en cours.
    queued: bool,
    /// Dernière fois que Java a posé la question.
    asked_at: Instant,
}

/// Ce qui part en une requête vers `/presence/offline` : sa propre annonce
/// (vide si elle n'est pas due, ou si le joueur ne veut pas apparaître) et
/// les joueurs de la liste à demander.
pub struct OfflineRound {
    pub own: Vec<[u8; 32]>,
    pub batch: Asked,
    /// Le serveur de jeu pour lequel ce tour a été préparé : une réponse
    /// arrivée après un changement de serveur ne vaut plus rien.
    epoch: u64,
}

pub struct Directory {
    entries: HashMap<PlayerKey, Entry>,
    queue: VecDeque<PlayerKey>,
    offline_queue: VecDeque<PlayerKey>,
    window_start: Instant,
    spent: u32,
    offline_spent: u32,
    /// Les formes de l'adresse du serveur de jeu courant ; vide hors d'un
    /// serveur, ou quand les serveurs hors ligne sont refusés par réglage.
    server: Vec<Vec<u8>>,
    /// Notre propre joueur sur ce serveur, s'il y est hors ligne.
    own: Option<PlayerKey>,
    epoch: u64,
    next_announce: Instant,
    renew: Duration,
    /// Les arrivées de la dernière minute : ce qui dit si la liste bouge.
    recent_joins: VecDeque<Instant>,
    /// Arrivées pas encore suivies d'un envoi, et l'heure de la plus ancienne.
    pending_joins: u32,
    first_pending: Option<Instant>,
}

impl Directory {
    pub fn new(now: Instant) -> Self {
        Self {
            entries: HashMap::new(),
            queue: VecDeque::new(),
            offline_queue: VecDeque::new(),
            window_start: now,
            spent: 0,
            offline_spent: 0,
            server: Vec::new(),
            own: None,
            epoch: 0,
            next_announce: now,
            renew: DEFAULT_RENEW,
            recent_joins: VecDeque::new(),
            pending_joins: 0,
            first_pending: None,
        }
    }

    fn note_join(&mut self, now: Instant) {
        self.recent_joins.push_back(now);
        self.pending_joins += 1;
        self.first_pending.get_or_insert(now);
    }

    fn quick_allowed(&self, offline: bool) -> bool {
        // Les questions en plus ne se prennent que sur la première moitié du
        // budget de l'heure, pour qu'un gros serveur ne prive pas de réponse
        // les joueurs croisés ensuite.
        match offline {
            true => self.offline_spent < OFFLINE_HOURLY_BUDGET / 2,
            false => self.spent < HOURLY_BUDGET / 2,
        }
    }

    /// Le fil réseau peut-il envoyer maintenant ? Sans arrivée en attente,
    /// oui : les rafraîchissements suivent leur cours. Avec, on attend le
    /// nombre d'arrivées que demande l'agitation de la liste (ou `HOLD_MAX`),
    /// puis les arrivés récents restés « absents » repartent avec le lot.
    pub fn release(&mut self, now: Instant) -> bool {
        while self.recent_joins.front().is_some_and(|at| now.duration_since(*at) >= BUSY_WINDOW) {
            self.recent_joins.pop_front();
        }
        let Some(first) = self.first_pending else { return true };
        let busy = self.recent_joins.len() >= BUSY_JOINS;
        if busy && self.pending_joins < JOINS_PER_ROUND_BUSY && now.duration_since(first) < HOLD_MAX {
            return false;
        }
        self.pending_joins = 0;
        self.first_pending = None;
        self.recheck_recent_arrivals(now);
        true
    }

    fn recheck_recent_arrivals(&mut self, now: Instant) {
        let quick = [self.quick_allowed(false), self.quick_allowed(true)];
        for (key, entry) in self.entries.iter_mut() {
            let due = entry.answer == Answer::Absent
                && !entry.queued
                && quick[key.offline as usize]
                && entry.join_rechecks < MAX_JOIN_RECHECKS
                && now.duration_since(entry.first_seen) < RECENT_ARRIVAL
                && entry.checked_at.is_some_and(|at| now.duration_since(at) >= JOIN_RECHECK_GAP);
            if !due {
                continue;
            }
            entry.join_rechecks += 1;
            entry.queued = true;
            match key.offline {
                true => self.offline_queue.push_back(*key),
                false => self.queue.push_back(*key),
            }
        }
    }

    /// Où l'on joue et sous quelle identité. Appelé souvent avec les mêmes
    /// valeurs : seul un changement a un effet. Changer de serveur oublie les
    /// joueurs hors ligne — leur empreinte portait l'ancien.
    pub fn set_context(&mut self, server: Vec<Vec<u8>>, own: Option<PlayerKey>, now: Instant) {
        let own = own.filter(|key| key.offline);
        if server != self.server {
            self.server = server;
            self.epoch += 1;
            self.entries.retain(|key, _| !key.offline);
            self.offline_queue.clear();
            self.next_announce = now;
        }
        if own != self.own {
            self.own = own;
            self.next_announce = now;
        }
    }

    fn prints(&self, key: &PlayerKey) -> ([[u8; 32]; MAX_SERVER_FORMS], u8) {
        let mut prints = [[0u8; 32]; MAX_SERVER_FORMS];
        if !key.offline {
            prints[0] = fingerprint(key.uuid, key.name());
            return (prints, 1);
        }
        let mut forms = 0;
        for (slot, server) in prints.iter_mut().zip(&self.server) {
            *slot = offline_fingerprint(server, key.uuid, key.name());
            forms += 1;
        }
        (prints, forms)
    }

    /// La question de Java. Ne bloque jamais : rend ce qu'on sait, et met le
    /// joueur en file si la réponse manque ou a vieilli.
    pub fn query(&mut self, key: PlayerKey, now: Instant) -> Answer {
        if key.offline && self.server.is_empty() {
            return Answer::Absent;
        }
        let quick = self.quick_allowed(key.offline);
        if let Some(entry) = self.entries.get_mut(&key) {
            entry.asked_at = now;
            let stale = match (entry.checked_at, entry.answer) {
                (None, _) => true,
                (Some(at), Answer::Present) => now.duration_since(at) >= PRESENT_TTL,
                (Some(at), _) => {
                    let recheck = ABSENT_RECHECKS.get(entry.absent_streak.saturating_sub(1) as usize).filter(|_| quick);
                    now.duration_since(at) >= *recheck.unwrap_or(&ABSENT_TTL)
                }
            };
            if stale && !entry.queued {
                entry.queued = true;
                if key.offline {
                    self.offline_queue.push_back(key);
                } else {
                    self.queue.push_back(key);
                }
            }
            return entry.answer;
        }
        if self.entries.len() >= MAX_ENTRIES {
            return Answer::Unknown;
        }
        let (prints, forms) = self.prints(&key);
        self.entries.insert(key, Entry { prints, forms, answer: Answer::Unknown, absent_streak: 0, first_seen: now, join_rechecks: 0, checked_at: None, queued: true, asked_at: now });
        self.note_join(now);
        if key.offline {
            self.offline_queue.push_back(key);
        } else {
            self.queue.push_back(key);
        }
        Answer::Unknown
    }

    /// Rouvre les budgets quand l'heure est passée.
    fn roll(&mut self, now: Instant) {
        if now.duration_since(self.window_start) >= BUDGET_WINDOW {
            self.window_start = now;
            self.spent = 0;
            self.offline_spent = 0;
        }
    }

    /// Le prochain paquet de comptes Microsoft à demander, dans la limite du
    /// budget de l'heure.
    pub fn take_batch(&mut self, now: Instant) -> Asked {
        self.roll(now);
        let allowed = (HOURLY_BUDGET.saturating_sub(self.spent) as usize).min(MAX_BATCH);
        let mut batch = Vec::new();
        while batch.len() < allowed {
            let Some(key) = self.queue.pop_front() else { break };
            // Oublié entre-temps : rien à demander.
            if let Some(entry) = self.entries.get(&key) {
                batch.push((key, entry.prints[0]));
            }
        }
        self.spent += batch.len() as u32;
        batch
    }

    /// Le prochain tour vers `/presence/offline`, ou `None` s'il n'y a rien à
    /// dire : notre annonce quand elle est due (`may_announce` : le joueur
    /// veut bien apparaître), et les joueurs hors ligne en attente — toutes
    /// leurs formes ensemble, jamais un joueur coupé entre deux requêtes.
    pub fn take_offline(&mut self, now: Instant, may_announce: bool) -> Option<OfflineRound> {
        if self.server.is_empty() {
            return None;
        }
        self.roll(now);
        let mut own = Vec::new();
        if may_announce && now >= self.next_announce {
            if let Some(key) = self.own {
                own = self.server.iter().map(|server| offline_fingerprint(server, key.uuid, key.name())).collect();
                self.next_announce = now + self.renew;
            }
        }
        let allowed = (OFFLINE_HOURLY_BUDGET.saturating_sub(self.offline_spent) as usize).min(MAX_OFFLINE_BATCH);
        let mut batch = Vec::new();
        while batch.len() + self.server.len() <= allowed {
            let Some(key) = self.offline_queue.pop_front() else { break };
            if let Some(entry) = self.entries.get(&key) {
                for print in &entry.prints[..entry.forms as usize] {
                    batch.push((key, *print));
                }
            }
        }
        self.offline_spent += batch.len() as u32;
        if own.is_empty() && batch.is_empty() {
            return None;
        }
        Some(OfflineRound { own, batch, epoch: self.epoch })
    }

    /// Les réponses du serveur, dans l'ordre du paquet. Un joueur demandé
    /// sous plusieurs formes est présent dès que l'une d'elles l'est.
    pub fn apply(&mut self, batch: &[(PlayerKey, [u8; 32])], present: &[bool], now: Instant) {
        let mut i = 0;
        while i < batch.len() {
            let key = batch[i].0;
            let mut found = false;
            let mut j = i;
            while j < batch.len() && batch[j].0 == key {
                found |= present.get(j).copied().unwrap_or(false);
                j += 1;
            }
            if let Some(entry) = self.entries.get_mut(&key) {
                entry.answer = if found { Answer::Present } else { Answer::Absent };
                entry.absent_streak = if found { 0 } else { entry.absent_streak.saturating_add(1) };
                entry.checked_at = Some(now);
                entry.queued = false;
            }
            i = j;
        }
    }

    /// Les réponses d'un tour hors ligne, et la cadence d'annonce demandée
    /// par le serveur. Ignorées si l'on a changé de serveur de jeu entre-temps.
    pub fn apply_offline(&mut self, round: &OfflineRound, present: &[bool], renew_in: Option<u64>, now: Instant) {
        if round.epoch != self.epoch {
            return;
        }
        self.apply(&round.batch, present, now);
        if let Some(secs) = renew_in {
            self.renew = Duration::from_secs(secs.clamp(MIN_RENEW_SECS, MAX_RENEW_SECS));
        }
        if !round.own.is_empty() {
            self.next_announce = now + self.renew;
        }
    }

    /// La requête a échoué : le paquet repart en tête de file. Le budget
    /// dépensé n'est pas rendu — le serveur a pu compter ces questions.
    pub fn give_back(&mut self, batch: Asked) {
        let mut last = None;
        for (key, _) in batch.into_iter().rev() {
            // Un joueur demandé sous plusieurs formes ne revient qu'une fois.
            if last == Some(key) {
                continue;
            }
            last = Some(key);
            if !self.entries.contains_key(&key) {
                continue;
            }
            if key.offline {
                self.offline_queue.push_front(key);
            } else {
                self.queue.push_front(key);
            }
        }
    }

    /// Le tour hors ligne a échoué : ses questions repartent en file, et
    /// notre annonce, si elle en était, est à refaire.
    pub fn give_back_offline(&mut self, round: OfflineRound, now: Instant) {
        if round.epoch != self.epoch {
            return;
        }
        if !round.own.is_empty() {
            self.next_announce = now;
        }
        self.give_back(round.batch);
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

    /// UUID version 3, comme en donne un serveur hors ligne.
    fn offline(n: u128) -> u128 {
        (n << 80) | (3u128 << 76) | n
    }

    fn key(n: u128, name: &str) -> PlayerKey {
        PlayerKey::new(online(n), name.as_bytes()).unwrap()
    }

    fn offline_key(n: u128, name: &str) -> PlayerKey {
        PlayerKey::new(offline(n), name.as_bytes()).unwrap()
    }

    fn two_forms() -> Vec<Vec<u8>> {
        vec![b"play.example.fr:25565".to_vec(), b"203.0.113.7:25565".to_vec()]
    }

    #[test]
    fn seuls_les_vrais_joueurs_au_pseudo_valide_sont_demandes() {
        assert!(!PlayerKey::new(online(1), b"Steve").unwrap().is_offline());
        // UUID hors ligne de « Steve » (version 3) : demandé, par l'autre route.
        assert!(PlayerKey::new(0x5627dd98e6be3c21b8a8e92344183641, b"Steve").unwrap().is_offline());
        // UUID de PNJ (version 2) : jamais.
        assert!(PlayerKey::new(0x5627dd98e6be2c21b8a8e92344183641, b"Steve").is_none());
        assert!(PlayerKey::new(online(1), b"").is_none());
        assert!(PlayerKey::new(online(1), b"beaucouptroplongpseudo").is_none());
        assert!(PlayerKey::new(online(1), "§aSteve".as_bytes()).is_none());
    }

    #[test]
    fn une_adresse_de_serveur_a_une_forme_stricte() {
        assert_eq!(server_form(b"Play.Example.FR:25565"), Some(b"play.example.fr:25565".to_vec()));
        assert_eq!(server_form(b"[2001:db8::1]:25565"), Some(b"[2001:db8::1]:25565".to_vec()));
        assert_eq!(server_form(b""), None);
        assert_eq!(server_form(b"play example.fr:25565"), None);
        assert_eq!(server_form("jeu.exémple.fr:25565".as_bytes()), None);
        assert_eq!(server_form(&[b'a'; 256]), None);
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
        // Les revérifications rapides de l'absent sont passées : il attend le délai long.
        for after in ABSENT_RECHECKS {
            dir.query(key(2, "Alex"), now + after);
            let again = dir.take_batch(now + after);
            dir.apply(&again, &[false], now);
        }

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
    fn un_joueur_qui_vient_d_arriver_est_reverifie_vite() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        // Demandé à l'instant où il apparaît : son signal n'est pas encore au serveur.
        dir.query(key(1, "Steve"), now);
        let batch = dir.take_batch(now);
        dir.apply(&batch, &[false], now);

        let soon = now + ABSENT_RECHECKS[0];
        assert_eq!(dir.query(key(1, "Steve"), soon - Duration::from_secs(1)), Answer::Absent);
        assert!(dir.take_batch(soon).is_empty(), "pas avant le premier délai");
        dir.query(key(1, "Steve"), soon);
        let batch = dir.take_batch(soon);
        assert_eq!(batch.len(), 1);
        dir.apply(&batch, &[true], soon);
        assert_eq!(dir.query(key(1, "Steve"), soon), Answer::Present);

        // Budget de l'heure à moitié dépensé : plus de revérification rapide.
        dir.query(key(2, "Alex"), soon);
        let batch = dir.take_batch(soon);
        dir.apply(&batch, &[false], soon);
        dir.spent = HOURLY_BUDGET / 2;
        dir.query(key(2, "Alex"), soon + ABSENT_RECHECKS[0]);
        assert!(dir.take_batch(soon + ABSENT_RECHECKS[0]).is_empty());
    }

    #[test]
    fn sur_une_liste_calme_chaque_arrivee_fait_partir_les_questions() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        assert!(dir.release(now), "rien en attente : les rafraîchissements passent");
        // Un ami arrive, demandé avant que son signal soit au serveur.
        dir.query(key(1, "Steve"), now);
        assert!(dir.release(now));
        let batch = dir.take_batch(now);
        dir.apply(&batch, &[false], now);

        // Un second arrive un peu plus tard : le premier repart avec lui.
        let later = now + JOIN_RECHECK_GAP;
        dir.query(key(2, "Alex"), later);
        assert!(dir.release(later));
        let batch = dir.take_batch(later);
        assert_eq!(batch.iter().map(|(k, _)| *k).collect::<Vec<_>>(), vec![key(2, "Alex"), key(1, "Steve")]);
        dir.apply(&batch, &[false, true], later);
        assert_eq!(dir.query(key(1, "Steve"), later), Answer::Present);
    }

    #[test]
    fn sur_une_liste_agitee_on_attend_trois_arrivees() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        // L'entrée dans un lobby : tout le monde d'un coup, envoyé d'un coup.
        for n in 0..BUSY_JOINS as u128 {
            dir.query(key(n + 1, "Joueur"), now);
        }
        assert!(dir.release(now));
        let batch = dir.take_batch(now);
        dir.apply(&batch, &vec![false; batch.len()], now);

        let t = now + Duration::from_secs(10);
        dir.query(key(101, "Un"), t);
        assert!(!dir.release(t), "une arrivée ne suffit pas");
        dir.query(key(102, "Deux"), t);
        assert!(!dir.release(t));
        dir.query(key(103, "Trois"), t);
        assert!(dir.release(t));
        // Les trois arrivées, et les arrivés récents restés absents.
        assert_eq!(dir.take_batch(t).len(), 3 + BUSY_JOINS);

        // Une arrivée isolée ne reste pas en attente indéfiniment.
        let t = t + Duration::from_secs(1);
        dir.query(key(104, "Seul"), t);
        assert!(!dir.release(t));
        assert!(dir.release(t + HOLD_MAX));

        // La liste se calme : chaque arrivée compte de nouveau.
        let calm = t + HOLD_MAX + BUSY_WINDOW;
        dir.query(key(105, "Tard"), calm);
        assert!(dir.release(calm));
    }

    #[test]
    fn les_arrivees_ne_font_pas_redemander_sans_fin() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.query(key(1, "Steve"), now);
        dir.release(now);
        let batch = dir.take_batch(now);
        dir.apply(&batch, &[false], now);
        let mut asked = 0;
        for n in 0..5u64 {
            let t = now + JOIN_RECHECK_GAP * (n as u32 + 1);
            dir.query(key(10 + n as u128, "Autre"), t);
            dir.release(t);
            let batch = dir.take_batch(t);
            asked += batch.iter().filter(|(k, _)| *k == key(1, "Steve")).count();
            dir.apply(&batch, &vec![false; batch.len()], t);
        }
        assert_eq!(asked, MAX_JOIN_RECHECKS as usize);
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

    #[test]
    fn sans_serveur_connu_un_joueur_hors_ligne_n_est_jamais_demande() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        assert_eq!(dir.query(offline_key(1, "Steve"), now), Answer::Absent);
        assert_eq!(dir.counts().0, 0);
        assert!(dir.take_offline(now, true).is_none());
        assert!(dir.take_batch(now).is_empty());
    }

    #[test]
    fn hors_ligne_l_annonce_et_la_liste_partent_ensemble() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.set_context(two_forms(), Some(offline_key(9, "Moi")), now);
        assert_eq!(dir.query(offline_key(1, "Steve"), now), Answer::Unknown);
        assert_eq!(dir.query(offline_key(2, "Alex"), now), Answer::Unknown);
        // Un compte Microsoft croisé sur le même serveur garde sa route.
        dir.query(key(3, "Premium"), now);

        let round = dir.take_offline(now, true).unwrap();
        assert_eq!(round.own, vec![
            offline_fingerprint(b"play.example.fr:25565", offline(9), b"moi"),
            offline_fingerprint(b"203.0.113.7:25565", offline(9), b"moi"),
        ]);
        // Deux joueurs, deux formes chacun, à la suite.
        assert_eq!(round.batch.len(), 4);
        assert_eq!(round.batch[0].1, offline_fingerprint(b"play.example.fr:25565", offline(1), b"steve"));
        assert_eq!(round.batch[1].1, offline_fingerprint(b"203.0.113.7:25565", offline(1), b"steve"));
        assert_eq!(dir.take_batch(now).len(), 1);

        // Steve n'est connu que par l'adresse IP : cela suffit.
        dir.apply_offline(&round, &[false, true, false, false], Some(120), now);
        assert_eq!(dir.query(offline_key(1, "Steve"), now), Answer::Present);
        assert_eq!(dir.query(offline_key(2, "Alex"), now), Answer::Absent);
        assert!(dir.take_offline(now, true).is_none(), "rien à dire avant la prochaine annonce");
    }

    #[test]
    fn l_annonce_suit_la_cadence_du_serveur_et_le_choix_du_joueur() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.set_context(two_forms(), Some(offline_key(9, "Moi")), now);
        // Le joueur ne veut pas apparaître : on demande, on ne s'annonce pas.
        assert!(dir.take_offline(now, false).is_none());
        dir.query(offline_key(1, "Steve"), now);
        let round = dir.take_offline(now, false).unwrap();
        assert!(round.own.is_empty());
        dir.apply_offline(&round, &[false, false], None, now);

        // Il change d'avis : l'annonce part au tour suivant, puis à la cadence donnée.
        let round = dir.take_offline(now, true).unwrap();
        assert_eq!(round.own.len(), 2);
        dir.apply_offline(&round, &[], Some(60), now);
        assert!(dir.take_offline(now + Duration::from_secs(59), true).is_none());
        assert_eq!(dir.take_offline(now + Duration::from_secs(60), true).unwrap().own.len(), 2);

        // Un compte Microsoft (UUID version 4) n'a rien à annoncer ici : son launcher s'en charge.
        dir.set_context(two_forms(), Some(key(9, "Moi")), now);
        assert!(dir.take_offline(now + Duration::from_secs(600), true).is_none());
    }

    #[test]
    fn changer_de_serveur_oublie_les_joueurs_hors_ligne() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.set_context(two_forms(), None, now);
        dir.query(offline_key(1, "Steve"), now);
        dir.query(key(2, "Premium"), now);
        let round = dir.take_offline(now, true).unwrap();

        dir.set_context(vec![b"autre.example.fr:25565".to_vec()], None, now);
        assert_eq!(dir.counts().0, 1, "le compte Microsoft reste, il ne dépend pas du serveur");
        // Le même pseudo sur le nouveau serveur est une autre question…
        assert_eq!(dir.query(offline_key(1, "Steve"), now), Answer::Unknown);
        // …et la réponse de l'ancien serveur, arrivée en retard, ne le concerne pas.
        dir.apply_offline(&round, &[true, true], None, now);
        assert_eq!(dir.query(offline_key(1, "Steve"), now), Answer::Unknown);
        let fresh = dir.take_offline(now, true).unwrap();
        assert_eq!(fresh.batch, vec![(offline_key(1, "Steve"), offline_fingerprint(b"autre.example.fr:25565", offline(1), b"steve"))]);

        // Le même contexte renvoyé chaque seconde ne remet rien à zéro.
        dir.set_context(vec![b"autre.example.fr:25565".to_vec()], None, now);
        assert_eq!(dir.counts().0, 2);
    }

    #[test]
    fn un_tour_hors_ligne_en_echec_est_a_refaire() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.set_context(two_forms(), Some(offline_key(9, "Moi")), now);
        dir.query(offline_key(1, "Steve"), now);
        let round = dir.take_offline(now, true).unwrap();
        assert!(dir.take_offline(now, true).is_none());

        dir.give_back_offline(round, now);
        let again = dir.take_offline(now, true).unwrap();
        assert_eq!(again.own.len(), 2, "l'annonce repart aussi");
        assert_eq!(again.batch.len(), 2, "le joueur revient une fois, sous ses deux formes");
    }

    #[test]
    fn un_joueur_hors_ligne_n_est_jamais_coupe_entre_deux_requetes() {
        let now = Instant::now();
        let mut dir = Directory::new(now);
        dir.set_context(two_forms(), None, now);
        for n in 0..(MAX_OFFLINE_BATCH as u128 / 2 + 10) {
            dir.query(offline_key(n + 1, "Joueur"), now);
        }
        assert_eq!(dir.take_offline(now, true).unwrap().batch.len(), MAX_OFFLINE_BATCH);
        assert_eq!(dir.take_offline(now, true).unwrap().batch.len(), 20);

        dir.offline_spent = OFFLINE_HOURLY_BUDGET - 1;
        dir.query(offline_key(99_999, "Tardif"), now);
        assert!(dir.take_offline(now, true).is_none(), "il reste une empreinte de budget, il en faut deux");
        assert_eq!(dir.take_offline(now + BUDGET_WINDOW, true).unwrap().batch.len(), 2);
    }
}
