//! Quoi écrire dans le journal, et quand. Ni fichier ni horloge ici : le
//! temps est le compteur de ticks, et les décisions sortent sous forme
//! d'ordres (`Out`) que le fil d'écriture exécute. Tout se teste sans disque.
//!
//! Trois règles :
//!
//! - **On n'écrit que pendant un combat.** Un combat commence quand notre
//!   joueur et un autre JOUEUR s'infligent des dégâts (paquet de dégâts du
//!   serveur), et dure tant que le dernier de ces échanges a moins de
//!   `COMBAT_TICKS`. Traverser un lobby plein de monde n'écrit rien.
//! - **Ce qui précède le premier coup compte.** Les `PREROLL` dernières lignes
//!   sont gardées en mémoire et écrites d'un coup à l'ouverture du combat :
//!   l'approche et le premier smash, réussi ou manqué, sont dans le journal.
//! - **Un échantillon n'est gardé que pendant l'engagement** : un autre
//!   joueur à moins d'`ENGAGE_BLOCKS`. Les événements, eux, le sont toujours.
//!
//! Un fichier par monde : un changement de monde ferme le journal en cours,
//! et le suivant ne s'ouvre qu'à son premier combat.

use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;

use serde_json::{json, Value};

use super::record::*;

/// Version du format du journal (`telemetry_version` de docs/scoring/stockage.md).
pub const TELEMETRY_VERSION: u32 = 1;
/// Un autre joueur à moins de cette distance : on est engagé.
const ENGAGE_BLOCKS: f64 = 16.0;
/// Un combat dure tant que le dernier échange de dégâts a moins que cela (30 s).
const COMBAT_TICKS: u64 = 600;
/// Lignes gardées avant le premier coup (10 s d'échantillons).
const PREROLL: usize = 200;

/// Un ordre pour le fil d'écriture.
#[derive(Debug, PartialEq)]
pub enum Out {
    /// Ouvrir un nouveau fichier dans ce dossier ; la première ligne suit.
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
    me: i32,
    /// Les joueurs connus dans ce monde : identifiant réseau → (UUID, pseudo).
    players: HashMap<i32, (u128, String)>,
    equipment: HashMap<i32, String>,
    /// Ce qui a déjà été écrit dans le fichier ouvert.
    written_players: HashMap<i32, (u128, String)>,
    written_equipment: HashMap<i32, String>,
    /// Tick du dernier échange de dégâts entre nous et un joueur.
    last_exchange: Option<u64>,
    preroll: VecDeque<String>,
    open: bool,
}

impl Journal {
    pub fn new() -> Self {
        Self {
            dir: None,
            server: String::new(),
            mc: String::new(),
            tick: 0,
            me: -1,
            players: HashMap::new(),
            equipment: HashMap::new(),
            written_players: HashMap::new(),
            written_equipment: HashMap::new(),
            last_exchange: None,
            preroll: VecDeque::new(),
            open: false,
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
            Record::Player { id, uuid, name } => {
                self.players.insert(id, (uuid, name));
            }
            Record::Equipment { id, text } => {
                self.equipment.insert(id, text);
            }
            Record::Tick { me, others } => {
                self.tick += 1;
                self.me = me[ME_ID] as i32;
                if engaged(&me, &others) {
                    let line = json!({
                        "k": "s", "t": self.tick,
                        "me": rounded(&me),
                        "o": others.iter().map(|o| rounded(o)).collect::<Vec<_>>(),
                    });
                    self.emit(line, now_ms, &mut out);
                }
                // Le combat s'est éteint : ce qui suit n'est plus qu'un pré-enregistrement.
                if !self.in_combat() {
                    self.last_exchange = None;
                }
            }
            Record::Event { kind, a, b, c, text } => {
                if kind == EVENT_DAMAGE && self.is_exchange(a, b) {
                    self.last_exchange = Some(self.tick);
                }
                let line = match kind {
                    EVENT_ATTACK => json!({ "k": "a", "t": self.tick, "target": a }),
                    EVENT_DAMAGE => json!({ "k": "d", "t": self.tick, "entity": a, "cause": b, "direct": c, "type": text }),
                    EVENT_ENTITY => json!({ "k": "v", "t": self.tick, "entity": a, "status": b }),
                    EVENT_ITEM_USE => json!({ "k": "u", "t": self.tick, "item": text, "main": a == 1 }),
                    _ => return out,
                };
                self.emit(line, now_ms, &mut out);
            }
        }
        out
    }

    fn in_combat(&self) -> bool {
        self.last_exchange.is_some_and(|at| self.tick - at < COMBAT_TICKS)
    }

    /// Ce paquet de dégâts est-il un coup entre notre joueur et un autre
    /// joueur, dans un sens ou dans l'autre ?
    fn is_exchange(&self, entity: i32, cause: i32) -> bool {
        if self.me < 0 || entity == cause {
            return false;
        }
        (entity == self.me && self.players.contains_key(&cause)) || (cause == self.me && self.players.contains_key(&entity))
    }

    /// Hors combat, la ligne attend dans le pré-enregistrement. En combat,
    /// elle part — précédée, la première fois, de l'ouverture du fichier et
    /// de tout ce qui attendait.
    fn emit(&mut self, line: Value, now_ms: u64, out: &mut Vec<Out>) {
        let line = line.to_string();
        if !self.in_combat() {
            if self.preroll.len() == PREROLL {
                self.preroll.pop_front();
            }
            self.preroll.push_back(line);
            return;
        }
        let Some(dir) = self.dir.clone() else { return };
        if !self.open {
            self.open = true;
            out.push(Out::Open(dir.join("yuyuframe").join("matches").join(format!("{now_ms}.events.ndjson.zst"))));
            let header = json!({ "k": "h", "v": TELEMETRY_VERSION, "server": self.server, "mc": self.mc, "time": now_ms, "me": self.me });
            out.push(Out::Line(header.to_string()));
        }
        self.write_identities(out);
        out.extend(self.preroll.drain(..).map(Out::Line));
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
            if self.written_equipment.get(id) != Some(text) {
                out.push(Out::Line(json!({ "k": "e", "t": self.tick, "id": id, "eq": text }).to_string()));
                self.written_equipment.insert(*id, text.clone());
            }
        }
    }

    /// Fin du monde courant : les identifiants réseau ne valent plus rien, et
    /// le fichier se termine.
    fn end_world(&mut self, out: &mut Vec<Out>) {
        if self.open {
            out.push(Out::Close);
        }
        self.open = false;
        self.tick = 0;
        self.me = -1;
        self.last_exchange = None;
        self.preroll.clear();
        self.players.clear();
        self.equipment.clear();
        self.written_players.clear();
        self.written_equipment.clear();
    }
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

    fn started() -> Journal {
        let mut journal = Journal::new();
        journal.feed(Record::Context { dir: PathBuf::from("instance"), server: "minemen.club:25565".into(), mc: "26.3".into() }, 1000);
        journal.feed(Record::Player { id: FOE, uuid: 0xabc, name: "Rival".into() }, 1000);
        // Un premier tick, adversaire au loin : le journal sait qui nous sommes.
        journal.feed(tick(100.0), 1000);
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

    fn kinds(out: &[Out]) -> String {
        out.iter()
            .map(|o| match o {
                Out::Open(_) => "O".to_string(),
                Out::Close => "C".to_string(),
                Out::Line(line) => serde_json::from_str::<Value>(line).unwrap()["k"].as_str().unwrap().to_string(),
            })
            .collect()
    }

    #[test]
    fn rien_n_est_ecrit_hors_combat() {
        let mut journal = started();
        for _ in 0..50 {
            assert!(journal.feed(tick(3.0), 2000).is_empty(), "croiser un joueur n'ouvre aucun fichier");
        }
        // Des dégâts qui ne nous concernent pas non plus.
        assert!(journal.feed(hit(FOE, 42), 2000).is_empty());
        // Ni un coup porté à autre chose qu'un joueur (un monstre).
        assert!(journal.feed(hit(42, ME), 2000).is_empty());
    }

    #[test]
    fn le_premier_coup_ouvre_le_fichier_et_ecrit_ce_qui_precedait() {
        let mut journal = started();
        journal.feed(tick(3.0), 2000);
        journal.feed(tick(2.0), 2000);
        let out = journal.feed(hit(FOE, ME), 3000);
        // Ouverture, en-tête, le joueur adverse, les deux échantillons d'avant, le coup.
        assert_eq!(kinds(&out), "Ohpssd");
        assert_eq!(out[0], Out::Open(PathBuf::from("instance").join("yuyuframe").join("matches").join("3000.events.ndjson.zst")));
        let Out::Line(header) = &out[1] else { panic!() };
        let header: Value = serde_json::from_str(header).unwrap();
        assert_eq!(header["server"], "minemen.club:25565");
        assert_eq!(header["me"], ME);
        // Ensuite, chaque tick d'engagement part aussitôt, sans rien répéter.
        assert_eq!(kinds(&journal.feed(tick(2.0), 3000)), "s");
    }

    #[test]
    fn un_echantillon_n_est_garde_que_pendant_l_engagement() {
        let mut journal = started();
        journal.feed(hit(ME, FOE), 3000);
        assert_eq!(kinds(&journal.feed(tick(ENGAGE_BLOCKS), 3000)), "s");
        assert!(journal.feed(tick(ENGAGE_BLOCKS + 0.5), 3000).is_empty(), "adversaire trop loin");
        // Un événement, lui, est écrit même à distance.
        let far_use = Record::Event { kind: EVENT_ITEM_USE, a: 1, b: 0, c: 0, text: "wind_charge".into() };
        assert_eq!(kinds(&journal.feed(far_use, 3000)), "u");
    }

    #[test]
    fn le_pre_enregistrement_est_borne() {
        let mut journal = started();
        for _ in 0..(PREROLL + 50) {
            journal.feed(tick(1.0), 2000);
        }
        let out = journal.feed(hit(FOE, ME), 3000);
        let samples = kinds(&out).matches('s').count();
        assert_eq!(samples, PREROLL);
    }

    #[test]
    fn le_combat_s_eteint_sans_echange() {
        let mut journal = started();
        journal.feed(hit(FOE, ME), 3000);
        for _ in 0..(COMBAT_TICKS - 1) {
            assert_eq!(kinds(&journal.feed(tick(1.0), 3000)), "s");
        }
        assert!(journal.feed(tick(1.0), 3000).is_empty(), "30 s sans échange : on n'écrit plus");
        // Un nouvel échange reprend dans le même fichier, sans le rouvrir.
        let out = journal.feed(hit(ME, FOE), 4000);
        assert_eq!(kinds(&out), "sd");
    }

    #[test]
    fn un_changement_de_monde_termine_le_fichier() {
        let mut journal = started();
        journal.feed(hit(FOE, ME), 3000);
        assert_eq!(journal.feed(Record::World, 4000), vec![Out::Close]);
        // Sans fichier ouvert, il n'y a rien à fermer.
        assert!(journal.feed(Record::World, 4000).is_empty());
        // Les identifiants de l'ancien monde ne valent plus : ce coup n'ouvre
        // rien, il attend avec le reste du pré-enregistrement.
        assert!(journal.feed(hit(FOE, ME), 5000).is_empty());
        // Le joueur revu dans le nouveau monde, le combat rouvre un fichier.
        journal.feed(Record::Player { id: FOE, uuid: 0xabc, name: "Rival".into() }, 5000);
        journal.feed(tick(1.0), 5000);
        assert_eq!(kinds(&journal.feed(hit(FOE, ME), 6000)), "Ohpdsd");
    }

    #[test]
    fn un_equipement_n_est_ecrit_qu_a_son_changement() {
        let mut journal = started();
        journal.feed(Record::Equipment { id: FOE, text: "netherite_helmet,protection=4;;;;mace,density=5;".into() }, 2000);
        assert_eq!(kinds(&journal.feed(hit(FOE, ME), 3000)), "Ohped");
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
