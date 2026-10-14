//! Du texte d'un journal (`journal.rs`, une ligne JSON par enregistrement)
//! à un duel en mémoire. Rien n'est calculé ici : on range seulement.
//!
//! Les lignes d'un journal ne sont pas dans l'ordre des ticks — les dix
//! secondes gardées avant l'ouverture sont écrites après l'en-tête et les
//! joueurs — donc tout est trié à la sortie.

use std::collections::HashMap;

use serde_json::Value;

use super::super::record::{ME_LEN, OTHER_LEN};

/// Code d'événement d'entité : un totem vient d'être consommé.
const STATUS_TOTEM: i64 = 35;

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
}

pub struct Duel {
    pub samples: Vec<Sample>,
    pub hits: Vec<Hit>,
    /// Ticks des totems consommés.
    pub my_totems: Vec<u64>,
    pub foe_totems: Vec<u64>,
    /// Objets que notre joueur a utilisés : (tick, identifiant).
    pub uses: Vec<(u64, String)>,
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
}

/// Le mode d'un duel, reconnu à l'arme en main des deux duellistes : une
/// masse vue une seule fois dans une main suffit — on la sort pour frapper,
/// on tient une charge de vent ou une élytre le reste du temps. Sans masse,
/// une épée fait un duel à l'épée ; sinon, c'est autre chose.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
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

fn numbers<const N: usize>(value: &Value) -> Option<[f64; N]> {
    let list = value.as_array()?;
    if list.len() != N {
        return None;
    }
    let mut out = [0.0; N];
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
    let me = int(&lines.iter().find(|l| l["k"] == "h")?["me"])?;

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
        foe_uuid: foe_line.and_then(|l| l["uuid"].as_str()).map(|u| u.replace('-', "").to_lowercase()),
        mode: Mode::Other,
        samples: Vec::new(),
        hits: Vec::new(),
        my_totems: Vec::new(),
        foe_totems: Vec::new(),
        uses: Vec::new(),
        my_density: 0.0,
        foe_density: 0.0,
        result: None,
        foe_name: foe_line.and_then(|l| l["name"].as_str()).map(str::to_string),
        time: lines.iter().find(|l| l["k"] == "h").and_then(|l| l["time"].as_u64()),
    };
    let (mut saw_mace, mut saw_sword) = (false, false);
    for line in &lines {
        let t = line["t"].as_u64().unwrap_or(0);
        match line["k"].as_str().unwrap_or("") {
            "s" => {
                let Some(mine) = numbers::<ME_LEN>(&line["me"]) else { continue };
                let other = line["o"].as_array().and_then(|list| {
                    list.iter().filter_map(numbers::<OTHER_LEN>).find(|o| Some(o[0] as i32) == foe)
                });
                duel.samples.push(Sample { t, me: mine, foe: other });
            }
            "d" => {
                let (Some(entity), Some(cause)) = (int(&line["entity"]), int(&line["cause"])) else { continue };
                let by_me = cause == me && Some(entity) == foe;
                let on_me = entity == me && Some(cause) == foe;
                if by_me || on_me {
                    duel.hits.push(Hit { t, by_me, smash: line["type"] == "mace_smash" });
                }
            }
            "v" if line["status"].as_i64() == Some(STATUS_TOTEM) => match int(&line["entity"]) {
                Some(id) if id == me => duel.my_totems.push(t),
                Some(id) if Some(id) == foe => duel.foe_totems.push(t),
                _ => {}
            },
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
                        true
                    }
                    Some(id) if Some(id) == foe => {
                        duel.foe_density = duel.foe_density.max(level);
                        true
                    }
                    _ => false,
                };
                if duellist {
                    let hand = main_hand(equipment);
                    saw_mace |= hand == "mace";
                    saw_sword |= hand.ends_with("_sword");
                }
            }
            "r" => duel.result = line["result"].as_str().map(str::to_string),
            _ => {}
        }
    }
    duel.mode = if saw_mace { Mode::Mace } else if saw_sword { Mode::Sword } else { Mode::Other };
    duel.samples.sort_by_key(|s| s.t);
    duel.hits.sort_by_key(|h| h.t);
    duel.uses.sort_by_key(|u| u.0);
    Some(duel)
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
        // La masse d'un spectateur (id 99) ne compte pas.
        assert_eq!(parse(&journal("bow", "")).unwrap().mode, Mode::Other);
        let duel = parse(&journal("mace", "mace")).unwrap();
        assert_eq!(duel.foe_uuid.as_deref(), Some("b804a4a4df5d"), "sans tirets, en minuscules");
        assert_eq!(duel.foe_name.as_deref(), Some("Rival"));
    }

    #[test]
    fn un_texte_sans_en_tete_n_est_pas_un_journal() {
        assert!(parse("").is_none());
        assert!(parse("{\"k\":\"s\",\"t\":1}\n").is_none());
    }
}
