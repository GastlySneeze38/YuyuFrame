//! Du texte d'un journal (`journal.rs`, une ligne JSON par enregistrement)
//! à un duel en mémoire. Rien n'est calculé ici : on range seulement.
//!
//! Les lignes d'un journal ne sont pas dans l'ordre des ticks — les dix
//! secondes gardées avant l'ouverture sont écrites après l'en-tête et les
//! joueurs — donc tout est trié à la sortie.

use std::collections::HashMap;

use serde_json::Value;

use super::super::record::{ME_LEN, ME_LEN_V1, OTHER_LEN, OTHER_LEN_V1};

/// Code d'événement d'entité : un totem vient d'être consommé.
const STATUS_TOTEM: i64 = 35;
/// Code d'événement d'entité : mort.
const STATUS_DEATH: i64 = 3;
/// Le son du coup critique, joué sur celui qui frappe.
const CRIT_SOUND: &str = "entity.player.attack.crit";
/// Le début du nom des autres sons d'un coup (version 4).
const ATTACK_SOUND: &str = "entity.player.attack.";

/// Un tick du duel : notre joueur, et l'adversaire quand il était transmis.
pub struct Sample {
    pub t: u64,
    pub me: [f64; ME_LEN],
    pub foe: Option<[f64; OTHER_LEN]>,
}

/// Un coup confirmé par le serveur entre les deux duellistes.
pub struct Hit {
    pub t: u64,
    pub by_me: bool,
    /// Coup de masse en chute (`mace_smash`), et non un coup ordinaire.
    pub smash: bool,
    /// Le type de dégât tel que le paquet le nomme (`mace_smash`,
    /// `player_attack`, `spear`, `wind_charge`…).
    pub kind: String,
}

/// Un coup que notre joueur a donné (ligne `a`, journal de version 2).
pub struct Attack {
    pub t: u64,
    /// Sur l'adversaire du duel.
    pub on_foe: bool,
    /// L'objet en main AU MOMENT du coup ; vide pour la main vide.
    pub item: String,
}

/// Un son de bouclier joué sur l'un des deux duellistes.
pub struct ShieldSound {
    pub t: u64,
    pub on_me: bool,
    /// `item.shield.break` (bouclier désactivé) plutôt que `item.shield.block`.
    pub broke: bool,
}

/// L'équipement d'un duelliste à partir d'un tick : six cases (tête, torse,
/// jambes, pieds, main principale, main secondaire), chacune
/// « identifiant,enchantement=niveau,… » ou vide.
pub struct Gear {
    pub t: u64,
    pub slots: [String; 6],
}

pub const SLOT_CHEST: usize = 1;
pub const SLOT_HAND: usize = 4;
pub const SLOT_OFFHAND: usize = 5;

impl Gear {
    /// L'identifiant de l'objet d'une case, sans enchantements.
    pub fn item(&self, slot: usize) -> &str {
        item_of(&self.slots[slot])
    }

    /// Le niveau d'un enchantement sur l'objet d'une case (0 sans lui).
    pub fn level(&self, slot: usize, enchantment: &str) -> f64 {
        level_of(&self.slots[slot], enchantment)
    }

    /// Le nombre de pièces d'armure en netherite.
    pub fn netherite_pieces(&self) -> f64 {
        self.slots[..4].iter().filter(|s| item_of(s).starts_with("netherite_")).count() as f64
    }

    /// Le plus haut niveau de Protection contre les explosions porté.
    pub fn blast_protection(&self) -> f64 {
        self.slots[..4].iter().map(|s| level_of(s, "blast_protection")).fold(0.0, f64::max)
    }
}

fn item_of(slot: &str) -> &str {
    slot.split(',').next().unwrap_or("")
}

fn level_of(slot: &str, enchantment: &str) -> f64 {
    slot.split(',')
        .skip(1)
        .filter_map(|part| part.split_once('='))
        .filter(|(name, _)| *name == enchantment)
        .filter_map(|(_, level)| level.parse::<f64>().ok())
        .fold(0.0, f64::max)
}

fn gear(t: u64, text: &str) -> Gear {
    let mut slots: [String; 6] = Default::default();
    for (slot, part) in slots.iter_mut().zip(text.split(';')) {
        *slot = part.to_string();
    }
    Gear { t, slots }
}

/// L'équipement en vigueur au tick `t` : la dernière ligne qui ne lui est pas postérieure.
pub fn gear_at(list: &[Gear], t: u64) -> Option<&Gear> {
    let index = list.partition_point(|g| g.t <= t);
    index.checked_sub(1).map(|i| &list[i])
}

pub struct Duel {
    /// Version du format du journal (1 avant les mesures des familles).
    pub version: u32,
    pub samples: Vec<Sample>,
    pub hits: Vec<Hit>,
    /// Nos coups donnés (version 2) : l'objet au moment du coup.
    pub attacks: Vec<Attack>,
    /// Ticks des totems consommés.
    pub my_totems: Vec<u64>,
    pub foe_totems: Vec<u64>,
    /// Ticks des morts.
    pub my_deaths: Vec<u64>,
    pub foe_deaths: Vec<u64>,
    /// Ticks des dégâts de chute subis par notre joueur.
    pub my_falls: Vec<u64>,
    /// Sons de bouclier sur l'un ou l'autre (version 2).
    pub shield_sounds: Vec<ShieldSound>,
    /// Ticks où notre bouclier a été mis en recharge (hache, version 2).
    pub shield_cooldowns: Vec<u64>,
    /// Ticks des coups critiques, par leur son. Capturés depuis le
    /// 2026-10-15 : un journal plus ancien n'en a aucun ([`Duel::sees_crits`]).
    ///
    /// Le joueur sur qui Java a rattaché le son n'est PAS gardé : le son est
    /// joué aux pieds de celui qui frappe, mais rattaché au joueur le plus
    /// proche, et vu le 2026-10-15 ça ne tient pas — collé à nous,
    /// l'adversaire nous faisait porter ses critiques (notre position est
    /// exacte, la sienne a du retard) ; en chute rapide, ses critiques
    /// n'étaient rattachés à personne. Un critique part avec un coup : c'est
    /// au coup confirmé du même tick qu'il est rattaché (`smash::crit_heard`).
    pub crits: Vec<u64>,
    /// Les autres sons d'un coup (version 4, mode épée) : (tick, fin du nom —
    /// `knockback` coup sprinté, `strong` / `weak` selon la charge, `sweep`,
    /// `nodamage`). Comme les critiques, rattachés au coup confirmé du même
    /// tick, pas au joueur le plus proche.
    pub attack_sounds: Vec<(u64, String)>,
    /// Le serveur n'a envoyé aucun type de dégât `mace_smash` : les smashs
    /// ont été reconnus à la règle du jeu ([`infer_smashes`]).
    pub smashes_inferred: bool,
    /// Le serveur n'envoie pas (ou presque pas) les sons des coups —
    /// critiques, bouclier, charge : leur absence ne dit alors rien
    /// ([`Duel::sees_crits`], [`sounds_missing`]).
    pub sounds_missing: bool,
    /// Objets que notre joueur a utilisés : (tick, identifiant).
    pub uses: Vec<(u64, String)>,
    /// L'équipement de chacun, à chaque changement.
    pub my_gear: Vec<Gear>,
    pub foe_gear: Vec<Gear>,
    /// Niveau de Densité de la masse de chacun, le plus haut vu dans son équipement.
    pub my_density: f64,
    pub foe_density: f64,
    /// `win`, `loss`, `draw`, `unknown` — ou rien si le journal s'arrête avant le verdict.
    pub result: Option<String>,
    /// Le pseudo de l'adversaire, s'il est nommé dans le journal.
    pub foe_name: Option<String>,
    /// Son UUID, sans tirets : ce qui le reconnaît d'un duel à l'autre même s'il change de pseudo.
    pub foe_uuid: Option<String>,
    /// Le mode du duel, reconnu à l'arme en main ([`Mode`]).
    pub mode: Mode,
    /// L'heure d'ouverture du duel, en millisecondes depuis 1970.
    pub time: Option<u64>,
    /// Le tick d'ouverture du duel : le zéro des moments montrés au joueur
    /// (les dix secondes gardées avant sont en négatif).
    pub start: u64,
    /// Joué en casual : noté, mais hors du classement. Un journal d'avant le
    /// choix (sans `ranked` dans l'en-tête) est compétitif.
    pub casual: bool,
}

impl Duel {
    /// Le journal porte-t-il les mesures des familles (version 2) ?
    pub fn detailed(&self) -> bool {
        self.version >= 2
    }

    /// Le journal porte-t-il les sons des coups critiques (version 3), et le
    /// serveur les envoie-t-il ? Sans eux, un coup n'est ni « critique » ni
    /// « pas critique » : on ne sait pas.
    pub fn sees_crits(&self) -> bool {
        self.version >= 3 && !self.sounds_missing
    }

    /// Le journal porte-t-il les sons de charge et de sprint d'un coup
    /// (version 4), et le serveur les envoie-t-il ?
    pub fn sees_attack_sounds(&self) -> bool {
        self.version >= 4 && !self.sounds_missing
    }
}

/// Le mode d'un duel, reconnu à l'arme en main des deux duellistes : une
/// masse vue une seule fois dans une main suffit — on la sort pour frapper,
/// on tient une charge de vent ou une élytre le reste du temps. Sans masse,
/// un duel à l'épée est celui où chacun n'a QUE son épée (2026-10-22) :
/// rien d'autre en main ni dans l'autre main, aucun objet utilisé, aucun
/// totem. Un kit épée avec totems, pommes ou perles est un autre mode, pas
/// encore noté.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    Mace,
    Sword,
    Other,
}

impl Mode {
    /// Le code du mode dans le résumé en nombres (`Report::numbers`).
    pub fn code(self) -> f64 {
        match self {
            Mode::Mace => 1.0,
            Mode::Sword => 2.0,
            Mode::Other => 3.0,
        }
    }
}

/// L'objet en main dans un texte d'équipement : le cinquième emplacement
/// (tête, torse, jambes, pieds, main, autre main), sans ses enchantements.
fn main_hand(equipment: &str) -> &str {
    equipment.split(';').nth(4).and_then(|slot| slot.split(',').next()).unwrap_or("")
}

fn int(value: &Value) -> Option<i32> {
    value.as_i64().map(|v| v as i32)
}

/// Les nombres d'une ligne `s`. Un journal plus ancien en porte moins
/// (`min`, la longueur de la version 1) : ceux qui manquent valent −1,
/// « inconnu », comme Java les écrit quand il ne sait pas.
fn numbers<const N: usize>(value: &Value, min: usize) -> Option<[f64; N]> {
    let list = value.as_array()?;
    if list.len() < min || list.len() > N {
        return None;
    }
    let mut out = [-1.0; N];
    for (slot, v) in out.iter_mut().zip(list) {
        *slot = v.as_f64()?;
    }
    Some(out)
}

/// Niveau de Densité dans un texte d'équipement (`…;mace,density=5,wind_burst=1;…`).
fn density(equipment: &str) -> f64 {
    equipment
        .split(';')
        .filter(|slot| slot.split(',').next() == Some("mace"))
        .flat_map(|slot| slot.split(','))
        .filter_map(|part| part.strip_prefix("density="))
        .filter_map(|level| level.parse::<f64>().ok())
        .fold(0.0, f64::max)
}

/// `None` si le texte n'a pas d'en-tête lisible : ce n'est pas un journal.
pub fn parse(text: &str) -> Option<Duel> {
    let lines: Vec<Value> = text.lines().filter_map(|line| serde_json::from_str(line).ok()).collect();
    let header = lines.iter().find(|l| l["k"] == "h")?;
    let me = int(&header["me"])?;

    // L'adversaire : celui que nomme le verdict, sinon celui avec qui on a
    // échangé le plus de coups.
    let named = lines.iter().find(|l| l["k"] == "r").and_then(|l| int(&l["foe"]));
    let foe = named.or_else(|| {
        let mut partners: HashMap<i32, u32> = HashMap::new();
        for line in lines.iter().filter(|l| l["k"] == "d") {
            let (Some(entity), Some(cause)) = (int(&line["entity"]), int(&line["cause"])) else { continue };
            let other = if entity == me { cause } else { entity };
            if (entity == me) != (cause == me) && other >= 0 {
                *partners.entry(other).or_default() += 1;
            }
        }
        partners.into_iter().max_by_key(|(_, count)| *count).map(|(id, _)| id)
    });

    let foe_line = lines.iter().find(|l| l["k"] == "p" && foe.is_some() && int(&l["id"]) == foe);
    let mut duel = Duel {
        version: header["v"].as_u64().unwrap_or(1) as u32,
        foe_uuid: foe_line.and_then(|l| l["uuid"].as_str()).map(|u| u.replace('-', "").to_lowercase()),
        mode: Mode::Other,
        samples: Vec::new(),
        hits: Vec::new(),
        attacks: Vec::new(),
        my_totems: Vec::new(),
        foe_totems: Vec::new(),
        my_deaths: Vec::new(),
        foe_deaths: Vec::new(),
        my_falls: Vec::new(),
        shield_sounds: Vec::new(),
        shield_cooldowns: Vec::new(),
        crits: Vec::new(),
        attack_sounds: Vec::new(),
        smashes_inferred: false,
        sounds_missing: false,
        uses: Vec::new(),
        my_gear: Vec::new(),
        foe_gear: Vec::new(),
        my_density: 0.0,
        foe_density: 0.0,
        result: None,
        foe_name: foe_line.and_then(|l| l["name"].as_str()).map(str::to_string),
        time: header["time"].as_u64(),
        start: header["t"].as_u64().unwrap_or(0),
        casual: header["ranked"].as_bool() == Some(false),
    };
    let (mut saw_mace, mut saw_sword, mut saw_other) = (false, false, false);
    for line in &lines {
        let t = line["t"].as_u64().unwrap_or(0);
        match line["k"].as_str().unwrap_or("") {
            "s" => {
                let Some(mine) = numbers::<ME_LEN>(&line["me"], ME_LEN_V1) else { continue };
                let other = line["o"].as_array().and_then(|list| {
                    list.iter().filter_map(|o| numbers::<OTHER_LEN>(o, OTHER_LEN_V1)).find(|o| Some(o[0] as i32) == foe)
                });
                duel.samples.push(Sample { t, me: mine, foe: other });
            }
            "d" => {
                let (Some(entity), Some(cause)) = (int(&line["entity"]), int(&line["cause"])) else { continue };
                let kind = line["type"].as_str().unwrap_or("");
                if entity == me && kind == "fall" {
                    duel.my_falls.push(t);
                }
                let by_me = cause == me && Some(entity) == foe;
                let on_me = entity == me && Some(cause) == foe;
                if by_me || on_me {
                    duel.hits.push(Hit { t, by_me, smash: kind == "mace_smash", kind: kind.to_string() });
                }
            }
            "a" => duel.attacks.push(Attack {
                t,
                on_foe: foe.is_some() && int(&line["target"]) == foe,
                item: line["item"].as_str().unwrap_or("").to_string(),
            }),
            "n" => {
                let sound = line["sound"].as_str().unwrap_or("");
                let on_me = int(&line["entity"]) == Some(me);
                if sound == CRIT_SOUND {
                    // Quel que soit le joueur sur qui Java l'a rattaché (voir `crits`).
                    duel.crits.push(t);
                } else if let Some(kind) = sound.strip_prefix(ATTACK_SOUND) {
                    duel.attack_sounds.push((t, kind.to_string()));
                } else if sound.starts_with("item.shield") && (on_me || (foe.is_some() && int(&line["entity"]) == foe)) {
                    duel.shield_sounds.push(ShieldSound { t, on_me, broke: sound.ends_with("break") });
                }
            }
            // Le jeu envoie la recharge à son début (sa durée) et à sa fin
            // (0) : seule la première est une garde cassée.
            "c" if line["group"].as_str().is_some_and(|g| g.ends_with("shield")) && line["ticks"].as_i64().is_some_and(|d| d > 0) => {
                duel.shield_cooldowns.push(t)
            }
            "v" => {
                let status = line["status"].as_i64();
                let list = match int(&line["entity"]) {
                    Some(id) if id == me => match status {
                        Some(STATUS_TOTEM) => Some(&mut duel.my_totems),
                        Some(STATUS_DEATH) => Some(&mut duel.my_deaths),
                        _ => None,
                    },
                    Some(id) if Some(id) == foe => match status {
                        Some(STATUS_TOTEM) => Some(&mut duel.foe_totems),
                        Some(STATUS_DEATH) => Some(&mut duel.foe_deaths),
                        _ => None,
                    },
                    _ => None,
                };
                if let Some(list) = list {
                    list.push(t);
                }
            }
            "u" => {
                if let Some(item) = line["item"].as_str() {
                    duel.uses.push((t, item.to_string()));
                }
            }
            "e" => {
                let equipment = line["eq"].as_str().unwrap_or("");
                let level = density(equipment);
                let duellist = match int(&line["id"]) {
                    Some(id) if id == me => {
                        duel.my_density = duel.my_density.max(level);
                        duel.my_gear.push(gear(t, equipment));
                        true
                    }
                    Some(id) if Some(id) == foe => {
                        duel.foe_density = duel.foe_density.max(level);
                        duel.foe_gear.push(gear(t, equipment));
                        true
                    }
                    _ => false,
                };
                if duellist {
                    let hand = main_hand(equipment);
                    saw_mace |= hand == "mace";
                    saw_sword |= hand.ends_with("_sword");
                    let offhand = equipment.split(';').nth(5).map(item_of).unwrap_or("");
                    saw_other |= !(hand.is_empty() || hand.ends_with("_sword")) || !offhand.is_empty();
                }
            }
            "r" => duel.result = line["result"].as_str().map(str::to_string),
            _ => {}
        }
    }
    // Un clic droit avec l'épée est un « objet utilisé » comme un autre pour
    // le jeu : il ne fait pas sortir du kit épée (vu le 2026-10-23, deux clics
    // droits dans les secondes d'avant le duel le faisaient classer « autre »).
    let only_sword_uses = duel.uses.iter().all(|(_, item)| item.ends_with("_sword"));
    let only_swords = !saw_other && only_sword_uses && duel.my_totems.is_empty() && duel.foe_totems.is_empty();
    duel.mode = if saw_mace {
        Mode::Mace
    } else if saw_sword && only_swords {
        Mode::Sword
    } else {
        Mode::Other
    };
    duel.samples.sort_by_key(|s| s.t);
    duel.hits.sort_by_key(|h| h.t);
    duel.attacks.sort_by_key(|a| a.t);
    duel.uses.sort_by_key(|u| u.0);
    duel.shield_sounds.sort_by_key(|s| s.t);
    duel.shield_cooldowns.sort_unstable();
    duel.crits.sort_unstable();
    duel.attack_sounds.sort_by_key(|s| s.0);
    duel.my_falls.sort_unstable();
    duel.my_totems.sort_unstable();
    duel.foe_totems.sort_unstable();
    // Un tri stable : deux lignes du même tick gardent l'ordre du fichier, la dernière est la bonne.
    duel.my_gear.sort_by_key(|g| g.t);
    duel.foe_gear.sort_by_key(|g| g.t);
    duel.sounds_missing = sounds_missing(&duel);
    if duel.mode == Mode::Mace {
        infer_smashes(&mut duel);
    }
    Some(duel)
}

// --- Les serveurs qui n'envoient pas tout --------------------------------
//
// Vu le 2026-11-03 sur 584 duels partagés d'un même serveur (26.1.2 et
// 26.3) : aucun type de dégât `mace_smash` (le coup de masse arrive en
// `player_attack`), aucune recharge d'objet, presque aucun son de coup. Les
// autres serveurs en envoient 1 à 4 smashs, 13 à 37 recharges et 5 à 25 sons
// par duel. Sans ce qui suit, les smashs réussis n'y existaient pas : la
// qualité du smash tombait à −5 écarts DES DEUX CÔTÉS, et la note moyenne à
// 90 sur 500.

/// La chute au-delà de laquelle un coup de masse est un smash
/// (`MaceItem.canSmashAttack` : `fallDistance > 1.5`, hors élytre).
const SMASH_MIN_FALL: f64 = 1.5;
/// Du clic au paquet de dégâts, ping compris : la chute se lit sur ces
/// ticks-là (le smash la remet à zéro juste après).
const SMASH_WINDOW_TICKS: u64 = 6;
/// Retard de l'équipement de l'adversaire sur son coup.
const FOE_GEAR_LAG_TICKS: u64 = 3;
/// Assez de coups pour juger qu'un serveur se tait…
const MUTE_MIN_HITS: usize = 15;
/// … quand moins d'un coup sur vingt a fait entendre un son (critique,
/// bouclier, charge). Un serveur qui les envoie en fait entendre un sur
/// sept au moins, même en épée sans saut.
const MUTE_MAX_RATIO: f64 = 0.05;

/// Le serveur n'envoie pas les sons des coups.
fn sounds_missing(duel: &Duel) -> bool {
    let hits = duel.hits.iter().filter(|h| h.kind == "player_attack" || h.kind == "mace_smash").count();
    let heard = duel.crits.len() + duel.shield_sounds.len() + duel.attack_sounds.len();
    hits >= MUTE_MIN_HITS && (heard as f64) < MUTE_MAX_RATIO * hits as f64
}

/// Sur un serveur qui n'envoie jamais `mace_smash`, un coup est un smash à
/// la règle du jeu : masse en main et plus de 1,5 bloc de chute au coup. La
/// chute est celle que lisent les familles : la nôtre telle que le jeu la
/// compte, celle de l'adversaire depuis son dernier sommet.
pub(super) fn infer_smashes(duel: &mut Duel) {
    if duel.hits.iter().any(|h| h.kind == "mace_smash") {
        return;
    }
    let mine = super::features::falls(&duel.samples, true);
    let theirs = super::features::falls(&duel.samples, false);
    let fall_before = |falls: &[(u64, f64)], t: u64| {
        let from = t.saturating_sub(SMASH_WINDOW_TICKS);
        falls.iter().filter(|(ft, _)| *ft >= from && *ft <= t).map(|(_, f)| *f).fold(0.0, f64::max)
    };
    let mut any = false;
    for i in 0..duel.hits.len() {
        let (t, by_me) = (duel.hits[i].t, duel.hits[i].by_me);
        if duel.hits[i].kind != "player_attack" {
            continue;
        }
        // Le clic s'il est connu (version 2) : son objet, et sa chute — le
        // jeu juge le smash au clic. Sinon l'équipement, et la chute de la fenêtre.
        let click = duel.attacks.iter().rev().find(|a| a.on_foe && a.t <= t && t - a.t <= SMASH_WINDOW_TICKS);
        let held = if by_me {
            click.map(|a| a.item.clone()).or_else(|| gear_at(&duel.my_gear, t).map(|g| g.item(SLOT_HAND).to_string()))
        } else {
            // L'équipement de l'adversaire arrive APRÈS le coup qu'il a
            // permis : sur les vrais `mace_smash` du serveur témoin, la masse
            // s'affiche au tick du coup ou 2 ticks plus tard (318 sur 346).
            let later = duel.foe_gear.iter().any(|g| g.t > t && g.t <= t + FOE_GEAR_LAG_TICKS && g.item(SLOT_HAND) == "mace");
            if later {
                Some("mace".to_string())
            } else {
                gear_at(&duel.foe_gear, t).map(|g| g.item(SLOT_HAND).to_string())
            }
        };
        if held.as_deref() != Some("mace") {
            continue;
        }
        let fall = match (by_me, click) {
            // La chute du dernier tick relevé avant le clic.
            (true, Some(a)) => mine.iter().rev().find(|(ft, _)| *ft <= a.t).map(|(_, f)| *f).unwrap_or(0.0),
            (true, None) => fall_before(&mine, t),
            (false, _) => fall_before(&theirs, t),
        };
        if fall > SMASH_MIN_FALL {
            duel.hits[i].smash = true;
            any = true;
        }
    }
    duel.smashes_inferred = any;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn la_densite_se_lit_sur_la_masse_seulement() {
        assert_eq!(density("netherite_helmet,protection=4;;;;mace,density=5,wind_burst=1;totem_of_undying"), 5.0);
        assert_eq!(density(";;;;netherite_sword;"), 0.0);
        assert_eq!(density(";;;;mace;"), 0.0);
    }

    #[test]
    fn l_arme_en_main_se_lit_au_cinquieme_emplacement() {
        assert_eq!(main_hand("netherite_helmet,protection=4;;;;mace,density=5,wind_burst=1;totem_of_undying"), "mace");
        assert_eq!(main_hand("diamond_helmet;diamond_chestplate;diamond_leggings;diamond_boots;diamond_sword;"), "diamond_sword");
        assert_eq!(main_hand(";;;;;"), "");
        assert_eq!(main_hand(""), "");
    }

    #[test]
    fn l_equipement_se_lit_case_par_case_et_au_bon_tick() {
        let list = vec![
            gear(10, "netherite_helmet,blast_protection=2;elytra;;netherite_boots;mace,breach=4,density=5;totem_of_undying"),
            gear(20, ";netherite_chestplate;;;wind_charge;"),
        ];
        let first = &list[0];
        assert_eq!((first.item(SLOT_CHEST), first.item(SLOT_HAND), first.item(SLOT_OFFHAND)), ("elytra", "mace", "totem_of_undying"));
        assert_eq!((first.level(SLOT_HAND, "breach"), first.level(SLOT_HAND, "density"), first.level(SLOT_HAND, "wind_burst")), (4.0, 5.0, 0.0));
        assert_eq!((first.netherite_pieces(), first.blast_protection()), (2.0, 2.0));
        assert!(gear_at(&list, 9).is_none());
        assert_eq!(gear_at(&list, 19).unwrap().t, 10);
        assert_eq!(gear_at(&list, 20).unwrap().item(SLOT_HAND), "wind_charge");
    }

    #[test]
    fn un_duel_est_casual_seulement_si_l_en_tete_le_dit() {
        assert!(parse("{\"k\":\"h\",\"me\":1,\"t\":0,\"ranked\":false}").unwrap().casual);
        assert!(!parse("{\"k\":\"h\",\"me\":1,\"t\":0,\"ranked\":true}").unwrap().casual);
        // Un journal d'avant le choix : compétitif.
        assert!(!parse("{\"k\":\"h\",\"me\":1,\"t\":0}").unwrap().casual);
    }

    #[test]
    fn le_mode_suit_l_arme_des_duellistes() {
        let journal = |me_hand: &str, foe_hand: &str| {
            format!(
                "{{\"k\":\"h\",\"me\":1,\"t\":0}}\n{{\"k\":\"p\",\"id\":7,\"name\":\"Rival\",\"uuid\":\"B804A4A4-DF5D\",\"t\":0}}\n\
                 {{\"k\":\"r\",\"foe\":7,\"result\":\"win\",\"t\":9}}\n\
                 {{\"k\":\"e\",\"id\":1,\"eq\":\";;;;{me_hand};\",\"t\":1}}\n{{\"k\":\"e\",\"id\":7,\"eq\":\";;;;{foe_hand};\",\"t\":1}}\n\
                 {{\"k\":\"e\",\"id\":99,\"eq\":\";;;;mace;\",\"t\":1}}\n"
            )
        };
        assert_eq!(parse(&journal("wind_charge", "mace")).unwrap().mode, Mode::Mace, "une masse dans une main suffit");
        assert_eq!(parse(&journal("diamond_sword", "netherite_sword")).unwrap().mode, Mode::Sword);
        // Une épée et autre chose (un totem dans l'autre main, une pomme mangée) : pas un duel à l'épée seule.
        assert_eq!(parse(&journal("diamond_sword", "netherite_sword;totem_of_undying")).unwrap().mode, Mode::Other);
        let apple = journal("diamond_sword", "netherite_sword") + "{\"k\":\"u\",\"item\":\"golden_apple\",\"t\":5}\n";
        assert_eq!(parse(&apple).unwrap().mode, Mode::Other);
        // Un clic droit avec l'épée reste un duel à l'épée.
        let right_click = journal("diamond_sword", "netherite_sword") + "{\"k\":\"u\",\"item\":\"diamond_sword\",\"main\":true,\"t\":5}\n";
        assert_eq!(parse(&right_click).unwrap().mode, Mode::Sword);
        // La masse d'un spectateur (id 99) ne compte pas.
        assert_eq!(parse(&journal("bow", "")).unwrap().mode, Mode::Other);
        let duel = parse(&journal("mace", "mace")).unwrap();
        assert_eq!(duel.foe_uuid.as_deref(), Some("b804a4a4df5d"), "sans tirets, en minuscules");
        assert_eq!(duel.foe_name.as_deref(), Some("Rival"));
        assert_eq!(duel.version, 1, "sans version dans l'en-tête : un journal de version 1");
        assert_eq!((duel.my_gear.len(), duel.foe_gear.len()), (1, 1), "pas l'équipement du spectateur");
    }

    #[test]
    fn les_lignes_de_la_version_2_sont_rangees() {
        let text = "{\"k\":\"h\",\"v\":2,\"me\":1,\"t\":0}\n{\"k\":\"r\",\"foe\":7,\"result\":\"win\",\"t\":90}\n\
                    {\"k\":\"a\",\"t\":5,\"target\":7,\"slot\":2,\"item\":\"mace\"}\n\
                    {\"k\":\"n\",\"t\":6,\"entity\":7,\"sound\":\"item.shield.break\"}\n\
                    {\"k\":\"n\",\"t\":8,\"entity\":1,\"sound\":\"item.shield.block\"}\n\
                    {\"k\":\"n\",\"t\":8,\"entity\":42,\"sound\":\"item.shield.block\"}\n\
                    {\"k\":\"n\",\"t\":10,\"entity\":7,\"sound\":\"entity.player.attack.crit\"}\n\
                    {\"k\":\"c\",\"t\":9,\"group\":\"minecraft:shield\",\"ticks\":100}\n\
                    {\"k\":\"c\",\"t\":109,\"group\":\"minecraft:shield\",\"ticks\":0}\n\
                    {\"k\":\"d\",\"t\":12,\"entity\":1,\"cause\":-1,\"direct\":-1,\"type\":\"fall\"}\n\
                    {\"k\":\"d\",\"t\":13,\"entity\":7,\"cause\":1,\"direct\":55,\"type\":\"wind_charge\"}\n\
                    {\"k\":\"v\",\"t\":14,\"entity\":7,\"status\":35}\n{\"k\":\"v\",\"t\":15,\"entity\":1,\"status\":3}\n";
        let duel = parse(text).unwrap();
        assert!(duel.detailed());
        assert_eq!((duel.attacks.len(), duel.attacks[0].on_foe, duel.attacks[0].item.as_str()), (1, true, "mace"));
        assert_eq!(duel.shield_sounds.len(), 2, "le son sur un spectateur ne compte pas, le critique est rangé à part");
        assert_eq!(duel.crits, vec![10]);
        assert!(!duel.sees_crits(), "version 2 : les critiques n'étaient pas capturés");
        assert!(duel.shield_sounds[0].broke && !duel.shield_sounds[0].on_me);
        assert!(!duel.shield_sounds[1].broke && duel.shield_sounds[1].on_me);
        assert_eq!(duel.shield_cooldowns, vec![9]);
        assert_eq!(duel.my_falls, vec![12]);
        assert_eq!((duel.hits.len(), duel.hits[0].kind.as_str(), duel.hits[0].by_me), (1, "wind_charge", true));
        assert_eq!((duel.foe_totems.clone(), duel.my_deaths.clone()), (vec![14], vec![15]));
    }

    #[test]
    fn les_journaux_des_deux_versions_se_lisent() {
        let v1 = [0.5; ME_LEN_V1];
        let v2 = [0.5; ME_LEN];
        let read_v1 = numbers::<ME_LEN>(&serde_json::json!(v1), ME_LEN_V1).expect("version 1");
        assert_eq!(read_v1[ME_LEN_V1 - 1], 0.5);
        assert_eq!(read_v1[ME_LEN - 1], -1.0, "les mesures absentes d'un journal de version 1 sont inconnues");
        assert_eq!(numbers::<ME_LEN>(&serde_json::json!(v2), ME_LEN_V1).unwrap(), v2);
        assert!(numbers::<ME_LEN>(&serde_json::json!(vec![0.5; 3]), ME_LEN_V1).is_none(), "trop court");
        assert!(numbers::<ME_LEN>(&serde_json::json!(vec![0.5; ME_LEN + 1]), ME_LEN_V1).is_none(), "trop long");
        let other = numbers::<OTHER_LEN>(&serde_json::json!(vec![7.0; OTHER_LEN_V1]), OTHER_LEN_V1).unwrap();
        assert_eq!((other[0], other[OTHER_LEN - 1]), (7.0, -1.0));
    }

    #[test]
    fn un_texte_sans_en_tete_n_est_pas_un_journal() {
        assert!(parse("").is_none());
        assert!(parse("{\"k\":\"s\",\"t\":1}\n").is_none());
    }
}
