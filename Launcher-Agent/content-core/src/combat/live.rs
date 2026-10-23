//! Les bons coups, repérés PENDANT le duel (docs/scoring/classement.md § Les
//! mots des bons coups) : un combo qui s'allonge, un W-tap, un smash de haut,
//! un totem arraché… Chacun devient un « mot » que l'interface fait surgir en
//! haut de l'écran, avec un son.
//!
//! Ce n'est pas la note : elle se calcule à la fin, sur tout le duel, avec ce
//! qu'en tire un joueur moyen (`score`). Ici, des règles simples et
//! immédiates, pour qu'un bon coup se sente au moment où on le fait. Les
//! définitions suivent celles des familles (même délai de combo, même barre
//! pleine, même W-tap) ; les seuils de smash sont à régler sur de vrais duels.
//!
//! Seuls les bons coups font un mot : rien quand on prend un coup.

use std::collections::VecDeque;
use std::sync::Mutex;

use super::journal::Journal;
use super::record::{ME_LEN, EVENT_ATTACK, EVENT_DAMAGE, EVENT_ENTITY, EVENT_SOUND};

/// Les sortes de mots — même liste dans `PvpCallouts.java`.
pub const COMBO: i32 = 1;
pub const WTAP: i32 = 2;
pub const CRIT: i32 = 3;
pub const SMASH: i32 = 4;
pub const BIG_SMASH: i32 = 5;
pub const PERFECT_SMASH: i32 = 6;
pub const SHIELD_BROKEN: i32 = 7;
pub const BLOCKED: i32 = 8;
pub const TOTEM: i32 = 9;
pub const CLUTCH: i32 = 10;

/// Quatre rangs de rareté : 1 bien, 2 très bien, 3 excellent, 4 légendaire.
const GOOD: i32 = 1;
const GREAT: i32 = 2;
const EXCELLENT: i32 = 3;
const LEGENDARY: i32 = 4;

/// Un combo se poursuit si notre coup suivant arrive dans ce délai (1,5 s),
/// sans en prendre un entre-temps — `COMBO_TICKS` des familles de l'épée.
const COMBO_TICKS: u64 = 30;
/// Un combo devient un mot à partir de ce nombre de coups.
const COMBO_FROM: u32 = 3;
/// Au-dessus, la barre compte comme pleine (`FULL_CHARGE` des familles).
const FULL_CHARGE: f64 = 0.9;
/// Un coup suit le clic de quelques ticks au plus (le paquet de dégâts revient du serveur).
const ATTACK_TO_HIT_TICKS: u64 = 10;
/// Hauteur de chute d'un smash (le jeu : plus de 1,5 bloc), d'un gros, d'un parfait.
/// À régler sur de vrais duels.
const SMASH_FALL: f64 = 1.5;
const BIG_SMASH_FALL: f64 = 5.0;
const PERFECT_SMASH_FALL: f64 = 10.0;
/// Une victoire à ce nombre de points de vie ou moins (un cœur) : un clutch.
const CLUTCH_HEALTH: f64 = 2.0;
/// Le même mot (hors combo) pas plus d'une fois par seconde : un bouclier
/// qui bloque en rafale ne doit pas inonder l'écran.
const SAME_KIND_TICKS: u64 = 20;

/// Les mots pas encore lus par Java, au plus 32 (au-delà, les plus vieux tombent).
static QUEUE: Mutex<VecDeque<[i32; 3]>> = Mutex::new(VecDeque::new());
const QUEUE_MAX: usize = 32;

fn push(kind: i32, tier: i32, value: i32) {
    let mut queue = QUEUE.lock().unwrap_or_else(|p| p.into_inner());
    if queue.len() == QUEUE_MAX {
        queue.pop_front();
    }
    queue.push_back([kind, tier, value]);
}

/// Retire jusqu'à `max` mots de la file, dans l'ordre : (sorte, rareté, valeur).
pub fn take(max: usize) -> Vec<[i32; 3]> {
    let mut queue = QUEUE.lock().unwrap_or_else(|p| p.into_inner());
    let n = max.min(queue.len());
    queue.drain(..n).collect()
}

/// Ce que le détecteur reçoit : une copie de ce qui intéresse, prise avant
/// que le journal ne consomme l'enregistrement.
pub enum Seen {
    Tick([f64; ME_LEN]),
    Event { kind: i32, a: i32, b: i32, text: String },
}

/// Index des nombres de notre joueur (`record.rs`).
const ME_HEALTH: usize = 9;
const ME_FALL: usize = 11;
const ME_ATTACK: usize = 12;
const ME_FLAGS: usize = 13;
const SPRINTING: i64 = 1 << 1;

#[derive(Default)]
pub struct Live {
    tick: u64,
    /// Notre joueur au dernier tick.
    me: Option<[f64; ME_LEN]>,
    /// Le dernier clic : tick, barre, sprint, chute — ce que le coup qui suit en hérite.
    attack: Option<(u64, f64, bool, f64)>,
    /// Le combo en cours : tick de notre dernier coup, nombre de coups.
    combo: Option<(u64, u32)>,
    /// Notre dernier coup, pour le W-tap : tick.
    last_hit: Option<u64>,
    /// Dernier tick où chaque sorte de mot est partie.
    last_kind: [u64; 16],
    recording: bool,
    /// Les mots repérés depuis la dernière publication.
    words: Vec<[i32; 3]>,
}

impl Live {
    /// Met les mots repérés dans la file que Java relit.
    pub fn publish(&mut self) {
        for [kind, tier, value] in self.words.drain(..) {
            push(kind, tier, value);
        }
    }

    /// Après chaque enregistrement : `seen` vient de lui, `journal` l'a déjà lu.
    pub fn observe(&mut self, seen: Option<Seen>, journal: &Journal) {
        let recording = journal.recording();
        if recording && !self.recording {
            self.start();
        }
        if !recording && self.recording {
            self.end(journal);
        }
        self.recording = recording;
        let Some(seen) = seen else { return };
        match seen {
            Seen::Tick(me) => {
                self.tick += 1;
                self.me = Some(me);
            }
            // Le clic se retient même hors duel : c'est le premier coup qui
            // ouvre le duel, son clic est arrivé juste avant.
            Seen::Event { kind: EVENT_ATTACK, .. } => self.click(),
            Seen::Event { kind, a, b, text } if recording => self.event(kind, a, b, &text, journal),
            Seen::Event { .. } => {}
        }
    }

    /// Le clic : la barre, le sprint et la chute de ce moment-là.
    fn click(&mut self) {
        let (charge, sprint, fall) = match self.me {
            Some(s) => (s[ME_ATTACK], (s[ME_FLAGS] as i64) & SPRINTING != 0, s[ME_FALL]),
            None => (0.0, false, 0.0),
        };
        self.attack = Some((self.tick, charge, sprint, fall));
    }

    /// Un duel s'ouvre. Le dernier clic reste : c'est celui du coup qui l'ouvre.
    fn start(&mut self) {
        self.combo = None;
        self.last_hit = None;
        self.last_kind = [0; 16];
    }

    /// Le duel vient de se fermer : gagné à un cœur, c'est un clutch.
    fn end(&mut self, journal: &Journal) {
        let health = self.me.map(|me| me[ME_HEALTH]).unwrap_or(f64::MAX);
        if journal.last_result() == Some("win") && health > 0.0 && health <= CLUTCH_HEALTH {
            self.words.push([CLUTCH, LEGENDARY, health.round() as i32]);
        }
    }

    fn say(&mut self, kind: i32, tier: i32, value: i32) {
        let slot = kind as usize % self.last_kind.len();
        if kind != COMBO && self.last_kind[slot] != 0 && self.tick - self.last_kind[slot] < SAME_KIND_TICKS {
            return;
        }
        self.last_kind[slot] = self.tick.max(1);
        self.words.push([kind, tier, value]);
    }

    fn event(&mut self, kind: i32, a: i32, b: i32, text: &str, journal: &Journal) {
        let Some(me) = journal.me() else { return };
        match kind {
            EVENT_DAMAGE => {
                let (entity, cause) = (a, b);
                if cause == me && entity != me && journal.is_player(entity) {
                    self.landed(journal);
                } else if entity == me && cause != me && journal.is_player(cause) {
                    // On prend un coup : le combo s'arrête, le W-tap aussi.
                    self.combo = None;
                    self.last_hit = None;
                }
            }
            // Le totem de l'adversaire saute.
            EVENT_ENTITY if b == 35 && a != me && journal.is_player(a) => self.say(TOTEM, EXCELLENT, 1),
            EVENT_SOUND => match text {
                "item.shield.break" if a != me => self.say(SHIELD_BROKEN, GREAT, 1),
                "item.shield.block" if a == me => self.say(BLOCKED, GOOD, 1),
                // Le son du critique est joué sur celui qui frappe ; la masse
                // a son propre mot (le smash), le critique y serait en double.
                "entity.player.attack.crit" if a == me && !holds_mace(journal) => self.say(CRIT, GOOD, 1),
                _ => {}
            },
            _ => {}
        }
    }

    /// Un de nos coups vient de toucher.
    fn landed(&mut self, journal: &Journal) {
        let stroke = self.attack.filter(|(t, ..)| self.tick - t <= ATTACK_TO_HIT_TICKS);
        // Le combo : coup après coup, sans en prendre un, chacun dans le délai.
        let count = match self.combo {
            Some((t, n)) if self.tick - t <= COMBO_TICKS => n + 1,
            _ => 1,
        };
        self.combo = Some((self.tick, count));
        if count >= COMBO_FROM {
            let tier = if count >= 8 { LEGENDARY } else if count >= 5 { EXCELLENT } else { GREAT };
            self.say(COMBO, tier, count as i32);
        }
        // Le W-tap : après un de nos coups, le suivant sprinté et barre pleine,
        // avant que l'autre ne touche (sinon `last_hit` aurait été effacé).
        if let (Some(previous), Some((_, charge, sprint, _))) = (self.last_hit, stroke) {
            if self.tick - previous <= COMBO_TICKS && sprint && charge > FULL_CHARGE && !holds_mace(journal) {
                self.say(WTAP, GOOD, 1);
            }
        }
        self.last_hit = Some(self.tick);
        // Le smash : un coup de masse en tombant ; plus la chute est haute
        // et la barre pleine, plus il vaut.
        if let Some((_, charge, _, fall)) = stroke {
            if holds_mace(journal) && fall > SMASH_FALL {
                let tenths = (fall * 10.0).round() as i32;
                if fall >= PERFECT_SMASH_FALL && charge > FULL_CHARGE {
                    self.say(PERFECT_SMASH, EXCELLENT, tenths);
                } else if fall >= BIG_SMASH_FALL && charge > FULL_CHARGE {
                    self.say(BIG_SMASH, GREAT, tenths);
                } else {
                    self.say(SMASH, GOOD, tenths);
                }
            }
        }
    }
}

fn holds_mace(journal: &Journal) -> bool {
    journal.my_hands().iter().any(|item| item.ends_with("mace"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::combat::record::{Record, ME_ID, OTHER_ID, OTHER_LEN, OTHER_X};
    use std::path::PathBuf;

    const ME: i32 = 7;
    const FOE: i32 = 9;
    const FOE_UUID: u128 = 0x4a50fccd83ef44eb842805d66f1dc106;
    const SWORD: &str = "netherite_helmet;netherite_chestplate;netherite_leggings;netherite_boots;netherite_sword;";
    const MACE: &str = "netherite_helmet;netherite_chestplate;netherite_leggings;netherite_boots;mace;";

    /// Le journal et le détecteur, nourris comme le fil d'écriture les nourrit.
    struct Duel {
        journal: Journal,
        live: Live,
        /// Notre joueur au prochain tick.
        me: [f64; ME_LEN],
    }

    impl Duel {
        fn new(weapon: &str) -> Duel {
            let mut me = [0.0; ME_LEN];
            me[ME_ID] = ME as f64;
            me[ME_HEALTH] = 20.0;
            let mut duel = Duel { journal: Journal::new(), live: Live::default(), me };
            duel.feed(Record::Context { dir: PathBuf::from("instance"), server: "minemen.club:25565".into(), mc: "26.3".into() });
            duel.feed(Record::Player { id: FOE, uuid: FOE_UUID, name: "Rival".into() });
            // Comme le jeu, qui nous décrit aussi nous-mêmes (sinon notre équipement serait oublié).
            duel.feed(Record::Player { id: ME, uuid: FOE_UUID + 1, name: "Moi".into() });
            duel.tick();
            duel.feed(Record::Equipment { id: ME, text: weapon.into() });
            duel.feed(Record::Equipment { id: FOE, text: SWORD.into() });
            duel
        }

        fn feed(&mut self, record: Record) {
            let seen = match &record {
                Record::Tick { me, .. } => Some(Seen::Tick(*me)),
                Record::Event { kind, a, b, text, .. } => Some(Seen::Event { kind: *kind, a: *a, b: *b, text: text.clone() }),
                _ => None,
            };
            self.journal.feed(record, 1000);
            self.live.observe(seen, &self.journal);
        }

        fn tick(&mut self) {
            let mut foe = [0.0; OTHER_LEN];
            foe[OTHER_ID] = FOE as f64;
            foe[OTHER_X] = 2.0;
            self.feed(Record::Tick { me: self.me, others: vec![foe] });
        }

        fn event(&mut self, kind: i32, a: i32, b: i32, text: &str) {
            self.feed(Record::Event { kind, a, b, c: b, text: text.into() });
        }

        /// Notre coup : le clic, puis le paquet de dégâts sur lui deux ticks plus tard.
        fn strike(&mut self) {
            self.event(EVENT_ATTACK, FOE, 0, "");
            self.tick();
            self.tick();
            self.event(EVENT_DAMAGE, FOE, ME, "player_attack");
        }

        fn words(&mut self) -> Vec<[i32; 3]> {
            std::mem::take(&mut self.live.words)
        }
    }

    #[test]
    fn trois_coups_d_affilee_font_un_combo_et_un_coup_recu_le_coupe() {
        let mut duel = Duel::new(SWORD);
        for _ in 0..3 {
            duel.strike();
            duel.tick();
        }
        assert_eq!(duel.words(), vec![[COMBO, GREAT, 3]]);
        duel.event(EVENT_DAMAGE, ME, FOE, "player_attack");
        duel.strike();
        duel.strike();
        assert!(duel.words().is_empty(), "le combo repart de zéro");
    }

    #[test]
    fn un_coup_sprinte_barre_pleine_apres_le_notre_est_un_w_tap() {
        let mut duel = Duel::new(SWORD);
        duel.strike();
        duel.me[ME_ATTACK] = 1.0;
        duel.me[ME_FLAGS] = SPRINTING as f64;
        duel.tick();
        duel.strike();
        assert_eq!(duel.words(), vec![[WTAP, GOOD, 1]]);
    }

    #[test]
    fn un_coup_de_masse_de_haut_barre_pleine_est_un_smash_parfait() {
        let mut duel = Duel::new(MACE);
        duel.me[ME_ATTACK] = 1.0;
        duel.me[ME_FALL] = 12.0;
        duel.tick();
        duel.strike();
        assert_eq!(duel.words(), vec![[PERFECT_SMASH, EXCELLENT, 120]]);
    }

    #[test]
    fn son_totem_qui_saute_est_un_mot_mais_prendre_des_coups_n_en_fait_aucun() {
        let mut duel = Duel::new(SWORD);
        duel.strike();
        duel.event(EVENT_DAMAGE, ME, FOE, "player_attack");
        duel.event(EVENT_DAMAGE, ME, FOE, "player_attack");
        duel.event(EVENT_ENTITY, FOE, 35, "");
        assert_eq!(duel.words(), vec![[TOTEM, EXCELLENT, 1]]);
    }

    #[test]
    fn gagner_a_un_coeur_est_un_clutch() {
        let mut duel = Duel::new(SWORD);
        duel.me[ME_HEALTH] = 2.0;
        duel.tick();
        duel.strike();
        duel.words();
        // Le serveur lui retire son armure : il a perdu.
        duel.feed(Record::Equipment { id: FOE, text: ";;;;paper;".into() });
        duel.tick();
        assert_eq!(duel.words(), vec![[CLUTCH, LEGENDARY, 2]]);
    }
}
