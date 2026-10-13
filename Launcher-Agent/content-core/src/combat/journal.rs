//! Quoi écrire dans le journal, et quand. Ni fichier ni horloge ici : le
//! temps est le compteur de ticks, et les décisions sortent sous forme
//! d'ordres (`Out`) que le fil d'écriture exécute. Tout se teste sans disque.
//!
//! **Un duel, un fichier.** Le duel est reconnu seul, d'après ce que Minemen
//! laisse voir (relevé sur de vrais journaux le 2026-10-13 — ce serveur ne
//! change pas de monde entre le lobby et l'arène, et ne tue personne) :
//!
//! - **Il s'ouvre au premier échange de dégâts** entre notre joueur et un
//!   autre vrai joueur (paquet de dégâts du serveur).
//! - **Il se ferme quand le serveur retire son armure à l'un des deux**,
//!   juste après un échange : celui-là a perdu. La vie ne sert pas de
//!   signal — celle du vaincu ne tombe jamais à zéro (le coup fatal est
//!   annulé, elle remonte au maximum au tick suivant), et celle de
//!   l'adversaire n'est pas lue.
//! - **Sans verdict**, il se ferme sur « inconnu » : plus aucun échange
//!   depuis `COMBAT_TICKS`, ou changement de monde.
//!
//! La touche du module reste un recours : elle ouvre ou ferme à la main, avec
//! un résultat « inconnu ».
//!
//! Trois règles pendant un duel :
//!
//! - **Ce qui précède l'ouverture compte.** Les `PREROLL` dernières lignes
//!   sont gardées en mémoire en permanence et écrites à l'ouverture :
//!   l'approche et la première prise de hauteur sont dans le journal.
//! - **Un échantillon n'est gardé que pendant l'engagement** : un autre
//!   joueur à moins d'`ENGAGE_BLOCKS`. Les événements, eux, le sont toujours.
//! - **Seuls les vrais joueurs comptent** (`real_player`).
//!
//! Un changement de monde ferme le duel en cours.

use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;

use serde_json::{json, Value};

use super::record::*;

/// Version du format du journal (`telemetry_version` de docs/scoring/stockage.md).
pub const TELEMETRY_VERSION: u32 = 1;
/// Un autre joueur à moins de cette distance : on est engagé.
const ENGAGE_BLOCKS: f64 = 16.0;
/// Lignes gardées avant l'ouverture du duel (10 s d'échantillons).
const PREROLL: usize = 200;
/// Une ligne du pré-enregistrement plus vieille que cela (10 s) n'est pas
/// écrite. Sans cette borne, la touche pressée vingt secondes après un
/// combat ressortait ce combat-là : vu dans un journal du 2026-10-13.
const PREROLL_TICKS: u64 = 200;
/// Un joueur dont Java n'a plus rien dit depuis ce délai (10 s) n'est plus
/// là. Java redit qui est à portée chaque seconde ; sur un serveur qui ne
/// change pas de monde entre deux manches, l'adversaire revient à chaque
/// manche sous un nouvel identifiant, et les anciens s'accumulaient.
const FORGET_TICKS: u64 = 200;
/// Sans échange de dégâts depuis ce délai (30 s), le duel est tenu pour
/// arrêté, sans résultat.
const COMBAT_TICKS: u64 = 600;
/// L'armure du vaincu disparaît dans ce délai après le dernier coup. Mesuré
/// entre 3 et 14 ticks ; Java ne redit l'équipement qu'une fois par seconde,
/// d'où trois secondes de marge.
const STRIP_TICKS: u64 = 60;
/// Après un duel fermé, un échange n'en ouvre pas un autre avant ce délai
/// (3 s) : un dernier coup part parfois après le verdict.
const QUIET_TICKS: u64 = 60;

/// Un ordre pour le fil d'écriture.
#[derive(Debug, PartialEq)]
pub enum Out {
    /// Ouvrir un nouveau fichier ; la première ligne suit.
    Open(PathBuf),
    Line(String),
    /// Terminer le fichier en cours.
    Close,
}

pub struct Journal {
    dir: Option<PathBuf>,
    server: String,
    mc: String,
    tick: u64,
    /// Notre identifiant réseau, connu au premier tick. Pas de valeur
    /// « absente » parmi les entiers : un identifiant peut être négatif.
    me: Option<i32>,
    /// Les vrais joueurs connus dans ce monde : identifiant réseau → (UUID, pseudo).
    players: HashMap<i32, (u128, String)>,
    equipment: HashMap<i32, String>,
    /// Ce qui a déjà été écrit dans le fichier ouvert.
    written_players: HashMap<i32, (u128, String)>,
    written_equipment: HashMap<i32, String>,
    /// Tick où Java a parlé pour la dernière fois de chaque joueur.
    seen: HashMap<i32, u64>,
    /// Les dernières lignes hors duel, avec leur tick.
    preroll: VecDeque<(u64, String)>,
    /// Un duel est ouvert : son fichier aussi.
    recording: bool,
    /// L'adversaire du duel ouvert : l'autre joueur du dernier échange.
    foe: Option<i32>,
    /// Tick du dernier échange de dégâts, et qui l'a reçu.
    last_exchange: Option<(u64, i32)>,
    /// Avant ce tick, un échange n'ouvre pas de duel : celui qui vient de se
    /// terminer laisse parfois partir un dernier coup.
    quiet_until: u64,
}

impl Journal {
    pub fn new() -> Self {
        Self {
            dir: None,
            server: String::new(),
            mc: String::new(),
            tick: 0,
            me: None,
            players: HashMap::new(),
            equipment: HashMap::new(),
            written_players: HashMap::new(),
            written_equipment: HashMap::new(),
            seen: HashMap::new(),
            preroll: VecDeque::new(),
            recording: false,
            foe: None,
            last_exchange: None,
            quiet_until: 0,
        }
    }

    /// `now_ms` : l'heure du système, qui n'entre que dans l'en-tête d'un
    /// fichier et dans son nom.
    pub fn feed(&mut self, record: Record, now_ms: u64) -> Vec<Out> {
        let mut out = Vec::new();
        match record {
            Record::Context { dir, server, mc } => {
                self.end_world(&mut out);
                self.dir = Some(dir);
                self.server = server;
                self.mc = mc;
            }
            Record::World | Record::Close => self.end_world(&mut out),
            Record::Match { start: true } => {
                self.finish("unknown", "key", &mut out);
                self.start_match(now_ms, "key", &mut out);
            }
            Record::Match { start: false } => self.finish("unknown", "key", &mut out),
            Record::Player { id, uuid, name } => {
                if real_player(uuid, &name) {
                    self.players.insert(id, (uuid, name));
                    self.seen.insert(id, self.tick);
                } else {
                    self.players.remove(&id);
                }
            }
            Record::Equipment { id, text } => {
                // L'armure d'un des deux duellistes vient de disparaître : le
                // serveur a rendu son verdict (voir `STRIP_TICKS`).
                let stripped = armor_empty(&text) && self.equipment.get(&id).is_some_and(|old| !armor_empty(old));
                self.equipment.insert(id, text);
                if stripped {
                    self.kit_removed(id, &mut out);
                }
            }
            Record::Tick { me, others } => {
                self.tick += 1;
                self.me = Some(me[ME_ID] as i32);
                self.forget_absent();
                // Seuls les vrais joueurs comptent : les personnages d'un lobby
                // sont des entités « joueur » comme les autres pour le jeu.
                let others: Vec<_> = others.into_iter().filter(|o| self.players.contains_key(&(o[OTHER_ID] as i32))).collect();
                if engaged(&me, &others) {
                    let line = json!({
                        "k": "s", "t": self.tick,
                        "me": rounded(&me),
                        "o": others.iter().map(|o| rounded(o)).collect::<Vec<_>>(),
                    });
                    self.emit(line, &mut out);
                }
                // Plus aucun échange depuis longtemps : le duel s'est arrêté
                // sans que le serveur ne désigne de perdant.
                if self.last_exchange.is_some_and(|(at, _)| self.tick - at >= COMBAT_TICKS) {
                    self.finish("unknown", "timeout", &mut out);
                }
            }
            Record::Event { kind, a, b, c, text } => {
                if kind == EVENT_DAMAGE {
                    if let Some(other) = self.exchange(a, b) {
                        // Le premier échange ouvre le duel ; les dix secondes
                        // d'avant, gardées en mémoire, donnent l'approche.
                        if !self.recording && self.tick >= self.quiet_until {
                            self.start_match(now_ms, "auto", &mut out);
                        }
                        if self.recording {
                            self.foe = Some(other);
                            self.last_exchange = Some((self.tick, a));
                        }
                    }
                }
                let line = match kind {
                    EVENT_ATTACK => json!({ "k": "a", "t": self.tick, "target": a }),
                    EVENT_DAMAGE => json!({ "k": "d", "t": self.tick, "entity": a, "cause": b, "direct": c, "type": text }),
                    EVENT_ENTITY => json!({ "k": "v", "t": self.tick, "entity": a, "status": b }),
                    EVENT_ITEM_USE => json!({ "k": "u", "t": self.tick, "item": text, "main": a == 1 }),
                    _ => return out,
                };
                self.emit(line, &mut out);
            }
        }
        out
    }

    pub fn recording(&self) -> bool {
        self.recording
    }

    /// Ce paquet de dégâts est-il un coup entre notre joueur et un autre vrai
    /// joueur, dans un sens ou dans l'autre ? Rend l'autre joueur.
    fn exchange(&self, entity: i32, cause: i32) -> Option<i32> {
        let me = self.me?;
        let other = match (entity == me, cause == me) {
            (true, false) => cause,
            (false, true) => entity,
            _ => return None,
        };
        self.players.contains_key(&other).then_some(other)
    }

    /// L'armure de ce joueur vient de disparaître. Si c'est l'un des deux
    /// duellistes, juste après un échange, c'est lui le perdant : Minemen ne
    /// tue personne, il retire son équipement au vaincu (vu sur trois fins
    /// de manche le 2026-10-13 : 3, 7 et 14 ticks après le dernier coup).
    fn kit_removed(&mut self, id: i32, out: &mut Vec<Out>) {
        let (Some(me), Some(foe), Some((at, victim))) = (self.me, self.foe, self.last_exchange) else { return };
        if !self.recording || self.tick - at > STRIP_TICKS || (id != me && id != foe) {
            return;
        }
        let result = if id == me { "loss" } else { "win" };
        // Le dernier coup est-il bien tombé sur le perdant ? Sinon le
        // résultat tient, mais il est marqué comme moins sûr.
        let line = json!({ "k": "r", "t": self.tick, "result": result, "end": "kit", "foe": foe, "last_hit_on_loser": victim == id });
        self.close_with(line, out);
    }

    /// Ferme le duel ouvert sur ce résultat. Sans duel ouvert, ne fait rien.
    fn finish(&mut self, result: &str, end: &str, out: &mut Vec<Out>) {
        let line = json!({ "k": "r", "t": self.tick, "result": result, "end": end, "foe": self.foe });
        self.close_with(line, out);
    }

    fn close_with(&mut self, result: Value, out: &mut Vec<Out>) {
        if !self.recording {
            return;
        }
        // L'équipement qui vient de changer d'abord : c'est lui qui explique le résultat.
        self.write_identities(out);
        out.push(Out::Line(result.to_string()));
        self.end_match(out);
        self.quiet_until = self.tick + QUIET_TICKS;
    }

    /// Ouvre un duel : un nouveau fichier, son en-tête, qui est là, et les
    /// dernières secondes. `by` : `auto` (premier échange) ou `key` (touche).
    fn start_match(&mut self, now_ms: u64, by: &str, out: &mut Vec<Out>) {
        // Hors d'un monde, il n'y a nulle part où écrire ni rien à écrire.
        let Some(dir) = self.dir.clone() else { return };
        self.recording = true;
        out.push(Out::Open(dir.join("yuyuframe").join("matches").join(format!("{now_ms}.events.ndjson.zst"))));
        let header = json!({ "k": "h", "v": TELEMETRY_VERSION, "server": self.server, "mc": self.mc, "time": now_ms, "me": self.me, "t": self.tick, "open": by });
        out.push(Out::Line(header.to_string()));
        self.write_identities(out);
        let oldest = self.tick.saturating_sub(PREROLL_TICKS);
        out.extend(self.preroll.drain(..).filter(|(at, _)| *at >= oldest).map(|(_, line)| Out::Line(line)));
    }

    /// Oublie les joueurs dont Java ne parle plus.
    fn forget_absent(&mut self) {
        let tick = self.tick;
        let seen = &self.seen;
        self.players.retain(|id, _| seen.get(id).is_some_and(|at| tick - at < FORGET_TICKS));
        let players = &self.players;
        self.equipment.retain(|id, _| players.contains_key(id));
        self.seen.retain(|id, _| players.contains_key(id));
    }

    fn end_match(&mut self, out: &mut Vec<Out>) {
        if self.recording {
            out.push(Out::Close);
        }
        self.recording = false;
        self.foe = None;
        self.last_exchange = None;
        self.written_players.clear();
        self.written_equipment.clear();
    }

    /// Hors duel, la ligne attend dans le pré-enregistrement. Pendant un
    /// duel, elle part, précédée de ce qui a changé chez les joueurs.
    fn emit(&mut self, line: Value, out: &mut Vec<Out>) {
        let line = line.to_string();
        if !self.recording {
            if self.preroll.len() == PREROLL {
                self.preroll.pop_front();
            }
            self.preroll.push_back((self.tick, line));
            return;
        }
        self.write_identities(out);
        out.push(Out::Line(line));
    }

    /// Les joueurs et les équipements nouveaux ou changés depuis la dernière
    /// écriture : une ligne chacun, jamais répétée à l'identique.
    fn write_identities(&mut self, out: &mut Vec<Out>) {
        for (id, player) in &self.players {
            if self.written_players.get(id) != Some(player) {
                out.push(Out::Line(json!({ "k": "p", "t": self.tick, "id": id, "uuid": format!("{:032x}", player.0), "name": player.1 }).to_string()));
                self.written_players.insert(*id, player.clone());
            }
        }
        for (id, text) in &self.equipment {
            // Celui d'un vrai joueur seulement : les personnages du serveur
            // envoient aussi le leur, et n'ont rien à faire dans le journal.
            if self.players.contains_key(id) && self.written_equipment.get(id) != Some(text) {
                out.push(Out::Line(json!({ "k": "e", "t": self.tick, "id": id, "eq": text }).to_string()));
                self.written_equipment.insert(*id, text.clone());
            }
        }
    }

    /// Fin du monde courant : le duel en cours se ferme, et les identifiants
    /// réseau ne valent plus rien.
    fn end_world(&mut self, out: &mut Vec<Out>) {
        self.finish("unknown", "world", out);
        self.quiet_until = 0;
        self.tick = 0;
        self.me = None;
        self.preroll.clear();
        self.players.clear();
        self.equipment.clear();
        self.seen.clear();
    }
}

/// Un vrai joueur, et non un personnage posé par le serveur (vendeur, file
/// d'attente, hologramme). Vu sur Minemen le 2026-10-13 : six personnages
/// immobiles autour du point d'arrivée du lobby comptaient comme adversaires
/// à portée. Même règle que la présence (`presence/directory.rs`) : un UUID
/// de compte Microsoft (version 4) ou dérivé du pseudo par un serveur hors
/// ligne (version 3), et un pseudo qui a la forme d'un pseudo.
fn real_player(uuid: u128, name: &str) -> bool {
    let version = (uuid >> 76) & 0xf;
    (version == 3 || version == 4)
        && !name.is_empty()
        && name.len() <= 16
        && name.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
}

/// Les quatre cases d'armure d'un équipement (`tête;torse;jambes;pieds;…`)
/// sont-elles toutes vides ?
fn armor_empty(equipment: &str) -> bool {
    equipment.split(';').take(4).all(str::is_empty)
}

fn engaged(me: &[f64; ME_LEN], others: &[[f64; OTHER_LEN]]) -> bool {
    others.iter().any(|o| {
        let (dx, dy, dz) = (o[OTHER_X] - me[ME_X], o[OTHER_X + 1] - me[ME_X + 1], o[OTHER_X + 2] - me[ME_X + 2]);
        dx * dx + dy * dy + dz * dz <= ENGAGE_BLOCKS * ENGAGE_BLOCKS
    })
}

/// Trois décimales : le millième de bloc suffit, et le journal y gagne la
/// moitié de sa taille. Une valeur qui n'est pas un nombre devient 0.
fn rounded(values: &[f64]) -> Vec<f64> {
    values.iter().map(|v| if v.is_finite() { (v * 1000.0).round() / 1000.0 } else { 0.0 }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const ME: i32 = 7;
    const FOE: i32 = 9;
    /// UUID version 4, comme en porte un compte Microsoft.
    const FOE_UUID: u128 = 0x4a50fccd83ef44eb842805d66f1dc106;

    fn started() -> Journal {
        let mut journal = Journal::new();
        journal.feed(Record::Context { dir: PathBuf::from("instance"), server: "minemen.club:25565".into(), mc: "26.3".into() }, 1000);
        journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() }, 1000);
        journal
    }

    fn tick(distance: f64) -> Record {
        let mut me = [0.0; ME_LEN];
        me[ME_ID] = ME as f64;
        let mut foe = [0.0; OTHER_LEN];
        foe[OTHER_ID] = FOE as f64;
        foe[OTHER_X] = distance;
        Record::Tick { me, others: vec![foe] }
    }

    fn hit(entity: i32, cause: i32) -> Record {
        Record::Event { kind: EVENT_DAMAGE, a: entity, b: cause, c: cause, text: "mace_smash".into() }
    }

    const START: Record = Record::Match { start: true };
    const END: Record = Record::Match { start: false };

    fn kinds(out: &[Out]) -> String {
        out.iter()
            .map(|o| match o {
                Out::Open(_) => "O".to_string(),
                Out::Close => "C".to_string(),
                Out::Line(line) => serde_json::from_str::<Value>(line).unwrap()["k"].as_str().unwrap().to_string(),
            })
            .collect()
    }

    const ARMOR: &str = "netherite_helmet;netherite_chestplate;netherite_leggings;netherite_boots;mace;";
    const STRIPPED: &str = ";;;;paper;";

    fn equip(id: i32, text: &str) -> Record {
        Record::Equipment { id, text: text.into() }
    }

    /// Le résultat écrit dans la ligne `r` d'une sortie.
    fn result(out: &[Out]) -> Value {
        out.iter()
            .filter_map(|o| match o {
                Out::Line(line) => serde_json::from_str::<Value>(line).ok().filter(|v| v["k"] == "r"),
                _ => None,
            })
            .next()
            .expect("une ligne de résultat")
    }

    /// Un duel ouvert par un premier échange, les deux joueurs en armure.
    fn fighting() -> Journal {
        let mut journal = started();
        journal.feed(tick(3.0), 2000);
        journal.feed(equip(ME, ARMOR), 2000);
        journal.feed(equip(FOE, ARMOR), 2000);
        journal.feed(hit(FOE, ME), 3000);
        journal
    }

    #[test]
    fn rien_n_est_ecrit_hors_duel() {
        let mut journal = started();
        journal.feed(tick(3.0), 2000);
        for _ in 0..50 {
            assert!(journal.feed(tick(3.0), 2000).is_empty(), "croiser un joueur n'ouvre rien");
        }
        // Des dégâts qui ne nous concernent pas, ou portés à autre chose qu'un joueur.
        assert!(journal.feed(hit(FOE, 42), 2000).is_empty());
        assert!(journal.feed(hit(42, ME), 2000).is_empty());
        // Fermer un duel qui n'est pas ouvert ne fait rien.
        assert!(journal.feed(END, 2000).is_empty());
    }

    #[test]
    fn le_premier_echange_ouvre_le_duel_et_ecrit_ce_qui_precedait() {
        let mut journal = started();
        journal.feed(tick(3.0), 2000);
        journal.feed(tick(2.0), 2000);
        let out = journal.feed(hit(FOE, ME), 3000);
        // Ouverture, en-tête, l'adversaire, les deux échantillons d'avant, le coup.
        assert_eq!(kinds(&out), "Ohpssd");
        assert_eq!(out[0], Out::Open(PathBuf::from("instance").join("yuyuframe").join("matches").join("3000.events.ndjson.zst")));
        let Out::Line(header) = &out[1] else { panic!() };
        let header: Value = serde_json::from_str(header).unwrap();
        assert_eq!(header["server"], "minemen.club:25565");
        assert_eq!(header["me"], ME);
        assert_eq!(header["open"], "auto");
        // Ensuite, tout part aussitôt, sans rien répéter.
        assert_eq!(kinds(&journal.feed(tick(2.0), 3000)), "s");
        assert_eq!(kinds(&journal.feed(hit(ME, FOE), 3000)), "d");
    }

    #[test]
    fn l_armure_retiree_a_l_adversaire_est_une_victoire() {
        let mut journal = fighting();
        journal.feed(tick(1.0), 3000);
        let out = journal.feed(equip(FOE, STRIPPED), 4000);
        // Son équipement vidé, le résultat, la fermeture.
        assert_eq!(kinds(&out), "erC");
        let verdict = result(&out);
        assert_eq!(verdict["result"], "win");
        assert_eq!(verdict["end"], "kit");
        assert_eq!(verdict["last_hit_on_loser"], true);
        assert!(!journal.recording());
    }

    #[test]
    fn notre_armure_retiree_est_une_defaite() {
        let mut journal = fighting();
        journal.feed(hit(ME, FOE), 3000);
        let verdict = result(&journal.feed(equip(ME, STRIPPED), 4000));
        assert_eq!(verdict["result"], "loss");
        assert_eq!(verdict["last_hit_on_loser"], true);
        // Le dernier coup n'était pas sur le perdant : le résultat tient, mais il est marqué.
        let mut journal = fighting();
        assert_eq!(result(&journal.feed(equip(ME, STRIPPED), 4000))["last_hit_on_loser"], false);
    }

    #[test]
    fn une_armure_retiree_longtemps_apres_le_dernier_coup_ne_dit_rien() {
        let mut journal = fighting();
        for _ in 0..(STRIP_TICKS + 1) {
            journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() }, 3000);
            journal.feed(tick(1.0), 3000);
        }
        assert_eq!(kinds(&journal.feed(equip(FOE, STRIPPED), 4000)), "", "trop tard pour être un verdict");
        assert!(journal.recording());
        // Ni celle d'un joueur qui n'est pas du duel.
        let mut journal = fighting();
        journal.feed(Record::Player { id: 33, uuid: FOE_UUID, name: "Passant".into() }, 3000);
        journal.feed(equip(33, ARMOR), 3000);
        journal.feed(equip(33, STRIPPED), 3000);
        assert!(journal.recording());
    }

    #[test]
    fn sans_echange_le_duel_se_ferme_sans_resultat() {
        let mut journal = fighting();
        let mut out = Vec::new();
        for _ in 0..COMBAT_TICKS {
            journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() }, 3000);
            out = journal.feed(tick(1.0), 3000);
        }
        let verdict = result(&out);
        assert_eq!(verdict["result"], "unknown");
        assert_eq!(verdict["end"], "timeout");
        assert!(!journal.recording());
    }

    #[test]
    fn un_dernier_coup_apres_le_verdict_n_ouvre_pas_un_duel() {
        let mut journal = fighting();
        journal.feed(equip(FOE, STRIPPED), 4000);
        assert!(journal.feed(hit(FOE, ME), 4000).is_empty(), "juste après la fin : ignoré");
        for _ in 0..QUIET_TICKS {
            journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() }, 4000);
            journal.feed(tick(1.0), 4000);
        }
        // La manche suivante, elle, ouvre son propre fichier.
        assert!(kinds(&journal.feed(hit(FOE, ME), 5000)).starts_with("Ohp"));
    }

    #[test]
    fn la_touche_ouvre_et_ferme_a_la_main() {
        let mut journal = started();
        journal.feed(tick(1.0), 2000);
        let out = journal.feed(START, 3000);
        assert_eq!(kinds(&out), "Ohps");
        let Out::Line(header) = &out[1] else { panic!() };
        assert_eq!(serde_json::from_str::<Value>(header).unwrap()["open"], "key");
        let out = journal.feed(END, 4000);
        assert_eq!(kinds(&out), "rC");
        assert_eq!(result(&out)["end"], "key");
        assert!(journal.feed(tick(1.0), 4000).is_empty(), "duel fermé : plus rien ne part");
        // Rouvrir sans avoir fermé : l'ancien fichier se termine d'abord.
        journal.feed(START, 5000);
        assert_eq!(kinds(&journal.feed(START, 6000)), "rCOhp");
    }

    #[test]
    fn hors_d_un_monde_la_touche_ne_fait_rien() {
        let mut journal = Journal::new();
        assert!(journal.feed(START, 1000).is_empty());
        assert!(journal.feed(tick(1.0), 1000).is_empty());
    }

    #[test]
    fn un_echantillon_n_est_garde_que_pendant_l_engagement() {
        let mut journal = started();
        journal.feed(START, 3000);
        assert_eq!(kinds(&journal.feed(tick(ENGAGE_BLOCKS), 3000)), "s");
        assert!(journal.feed(tick(ENGAGE_BLOCKS + 0.5), 3000).is_empty(), "adversaire trop loin");
        // Un événement, lui, est écrit même à distance.
        let far_use = Record::Event { kind: EVENT_ITEM_USE, a: 1, b: 0, c: 0, text: "wind_charge".into() };
        assert_eq!(kinds(&journal.feed(far_use, 3000)), "u");
    }

    #[test]
    fn un_personnage_du_serveur_n_est_pas_un_adversaire() {
        let mut journal = started();
        // Même identifiant que l'adversaire, mais un UUID version 2 : un personnage.
        journal.feed(Record::Player { id: FOE, uuid: 0x4a50fccd83ef24eb842805d66f1dc106, name: "Rival".into() }, 1000);
        journal.feed(Record::Equipment { id: FOE, text: ";;;;;".into() }, 1000);
        // Ni lui ni son équipement ne sont écrits, et il n'engage personne.
        assert_eq!(kinds(&journal.feed(START, 3000)), "Oh");
        assert!(journal.feed(tick(1.0), 3000).is_empty());
        // Un pseudo qui n'en est pas un non plus.
        journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "§aDuels".into() }, 3000);
        assert!(journal.feed(tick(1.0), 3000).is_empty());
    }

    #[test]
    fn le_pre_enregistrement_est_borne() {
        let mut journal = started();
        for _ in 0..(PREROLL + 50) {
            // Comme le jeu, qui redit régulièrement qui est à portée.
            journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() }, 2000);
            journal.feed(tick(1.0), 2000);
        }
        let out = journal.feed(START, 3000);
        assert_eq!(kinds(&out).matches('s').count(), PREROLL);
    }

    #[test]
    fn un_vieux_combat_ne_ressort_pas_a_l_ouverture() {
        let mut journal = started();
        journal.feed(tick(1.0), 2000);
        journal.feed(tick(1.0), 2000);
        // Vingt secondes passent, l'adversaire au loin : rien de neuf en mémoire.
        for _ in 0..(PREROLL_TICKS * 2) {
            journal.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() }, 2000);
            journal.feed(tick(100.0), 2000);
        }
        assert_eq!(kinds(&journal.feed(START, 3000)), "Ohp");
    }

    #[test]
    fn un_joueur_dont_on_ne_parle_plus_est_oublie() {
        let mut journal = started();
        // L'adversaire revient sous un nouvel identifiant ; l'ancien n'est plus redit.
        const AGAIN: i32 = 12;
        for _ in 0..FORGET_TICKS {
            journal.feed(Record::Player { id: AGAIN, uuid: FOE_UUID, name: "Rival".into() }, 2000);
            journal.feed(tick(100.0), 2000);
        }
        let out = journal.feed(START, 3000);
        assert_eq!(kinds(&out), "Ohp", "un seul joueur nommé : celui qui est là");
        let Out::Line(player) = &out[2] else { panic!() };
        assert_eq!(serde_json::from_str::<Value>(player).unwrap()["id"], AGAIN);
    }

    #[test]
    fn un_changement_de_monde_ferme_le_duel() {
        let mut journal = started();
        journal.feed(START, 3000);
        let out = journal.feed(Record::World, 4000);
        assert_eq!(kinds(&out), "rC");
        assert_eq!(result(&out)["end"], "world");
        // Sans duel ouvert, il n'y a rien à fermer.
        assert!(journal.feed(Record::World, 4000).is_empty());
        // Les joueurs de l'ancien monde sont oubliés : le duel suivant ne les nomme pas.
        assert_eq!(kinds(&journal.feed(START, 5000)), "Oh");
    }

    #[test]
    fn un_equipement_n_est_ecrit_qu_a_son_changement() {
        let mut journal = started();
        journal.feed(Record::Equipment { id: FOE, text: "netherite_helmet,protection=4;;;;mace,density=5;".into() }, 2000);
        assert_eq!(kinds(&journal.feed(START, 3000)), "Ohpe");
        journal.feed(Record::Equipment { id: FOE, text: "netherite_helmet,protection=4;;;;mace,density=5;".into() }, 3000);
        assert_eq!(kinds(&journal.feed(tick(1.0), 3000)), "s");
        journal.feed(Record::Equipment { id: FOE, text: ";;;;mace,density=5;".into() }, 3000);
        assert_eq!(kinds(&journal.feed(tick(1.0), 3000)), "es");
    }

    #[test]
    fn les_nombres_sont_arrondis_et_jamais_invalides() {
        assert_eq!(rounded(&[1.23456, f64::NAN, f64::INFINITY, -0.0004]), vec![1.235, 0.0, 0.0, -0.0]);
    }
}
