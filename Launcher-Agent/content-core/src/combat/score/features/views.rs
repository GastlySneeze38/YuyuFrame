//! Commun à la masse et à l'épée : les nombres d'un tick, et le duel vu du
//! côté de chacun des deux joueurs (`View`), tick par tick.

use super::super::parse::Sample;

/// Deux ticks d'interruption ne coupent pas une fenêtre.
pub(super) const GAP_TICKS: u64 = 2;
/// Angle sous lequel un bouclier arrête un coup : 90° de part et d'autre du
/// regard (`BlocksAttacks.DamageReduction` du bouclier, jar 26.3).
const SHIELD_HALF_ANGLE_COS: f64 = 0.0;

// Les nombres d'un tick de notre joueur (record.rs) et d'un autre joueur.
pub(super) const X: usize = 1;
pub(super) const Y: usize = 2;
pub(super) const Z: usize = 3;
pub(super) const VX: usize = 4;
pub(super) const VY: usize = 5;
pub(super) const VZ: usize = 6;
pub(super) const ME_YAW: usize = 7;
pub(super) const ME_PITCH: usize = 8;
pub(super) const ME_HEALTH: usize = 9;
pub(super) const ME_MAX_HEALTH: usize = 10;
pub(super) const ME_FALL: usize = 11;
pub(super) const ME_CHARGE: usize = 12;
pub(super) const ME_FLAGS: usize = 13;
pub(super) const ME_PING: usize = 14;
pub(super) const ME_FOOD: usize = 15;
pub(super) const ME_GROUND: usize = 18;
pub(super) const ME_STRENGTH: usize = 19;
pub(super) const ME_SPEED: usize = 20;
pub(super) const FOE_FLAGS: usize = 7;
pub(super) const FOE_HEAD_YAW: usize = 8;
pub(super) const FOE_GROUND: usize = 9;

// Les drapeaux (NativeCombat.FLAG_*).
pub(super) const ON_GROUND: i64 = 1;
pub(super) const SPRINTING: i64 = 1 << 1;
pub(super) const GLIDING: i64 = 1 << 2;
pub(super) const IN_WATER: i64 = 1 << 3;
pub(super) const USING_ITEM: i64 = 1 << 4;
pub(super) const PASSENGER: i64 = 1 << 7;
pub(super) const BLOCKING: i64 = 1 << 8;

pub(super) fn flag(flags: f64, which: i64) -> bool {
    flags >= 0.0 && flags as i64 & which != 0
}

/// Un tick vu du côté de l'attaquant.
pub(super) struct View {
    pub(super) t: u64,
    pub(super) fall: f64,
    pub(super) falling: bool,
    /// L'attaquant, la cible : au sol ?
    pub(super) grounded: bool,
    pub(super) target_grounded: bool,
    /// Hauteur de l'attaquant au-dessus de la cible.
    pub(super) above: f64,
    pub(super) horizontal: f64,
    pub(super) distance: f64,
    /// Positions et vitesses (blocs par tick).
    pub(super) pos: [f64; 3],
    pub(super) target: [f64; 3],
    pub(super) vel: [f64; 3],
    pub(super) target_vel: [f64; 3],
    pub(super) target_speed: f64,
    /// La cible tient son bouclier levé, et l'attaquant est devant elle.
    pub(super) shielded: bool,
    pub(super) front: bool,
    /// Hauteur de la cible au-dessus du sol (lecture du terrain), −1 si
    /// inconnue ou sans sol à portée de lecture.
    pub(super) target_ground_height: f64,
    /// L'attaquant plane en élytre, tient un objet en cours d'usage, son regard horizontal.
    pub(super) gliding: bool,
    pub(super) using: bool,
    pub(super) yaw: f64,
}

/// La direction du regard sur le plan horizontal (lacet du jeu, en degrés).
pub(super) fn look(yaw: f64) -> (f64, f64) {
    let r = yaw.to_radians();
    (-r.sin(), r.cos())
}

/// L'attaquant est-il devant la cible qui regarde vers `yaw` ?
pub(super) fn in_front(target: &[f64; 3], target_yaw: f64, attacker: &[f64; 3]) -> bool {
    let (lx, lz) = look(target_yaw);
    let (dx, dz) = (attacker[0] - target[0], attacker[2] - target[2]);
    let norm = (dx * dx + dz * dz).sqrt();
    norm < 1e-6 || (lx * dx + lz * dz) / norm >= SHIELD_HALF_ANGLE_COS
}

/// La vitesse de l'adversaire, lue dans ses positions : pour un autre joueur,
/// le client ne tient pas la sienne à jour d'un tick à l'autre (vu le
/// 2026-10-13 — avec elle, aucune de ses descentes n'était reconnue).
fn foe_velocities(samples: &[Sample]) -> Vec<[f64; 3]> {
    let mut out = Vec::with_capacity(samples.len());
    let mut last: Option<(u64, [f64; 3])> = None;
    for sample in samples {
        let Some(foe) = &sample.foe else {
            out.push([0.0; 3]);
            continue;
        };
        let pos = [foe[X], foe[Y], foe[Z]];
        let vel = match last {
            Some((t, prev)) if sample.t > t && sample.t - t <= GAP_TICKS => {
                let dt = (sample.t - t) as f64;
                [(pos[0] - prev[0]) / dt, (pos[1] - prev[1]) / dt, (pos[2] - prev[2]) / dt]
            }
            _ => [0.0; 3],
        };
        out.push(vel);
        last = Some((sample.t, pos));
    }
    out
}

/// Les ticks du duel vus par l'un des deux joueurs. Pour nous, la distance
/// de chute est celle du jeu ; pour l'adversaire, que le client ne simule
/// pas, elle est reconstruite : la hauteur perdue depuis son dernier sommet.
pub(super) fn views(samples: &[Sample], mine: bool) -> Vec<View> {
    let foe_vel = foe_velocities(samples);
    let mut out = Vec::new();
    let mut peak = f64::NAN;
    let mut last_t = 0;
    let mut last_y = 0.0;
    for (sample, fv) in samples.iter().zip(&foe_vel) {
        let Some(foe) = &sample.foe else { continue };
        let me = &sample.me;
        let my_pos = [me[X], me[Y], me[Z]];
        let foe_pos = [foe[X], foe[Y], foe[Z]];
        let (dx, dz) = (foe[X] - me[X], foe[Z] - me[Z]);
        let horizontal = (dx * dx + dz * dz).sqrt();
        let dy = me[Y] - foe[Y];
        let distance = (horizontal * horizontal + dy * dy).sqrt();
        let my_grounded = flag(me[ME_FLAGS], ON_GROUND);
        let foe_grounded = flag(foe[FOE_FLAGS], ON_GROUND);
        // La tête de l'adversaire n'est connue que depuis la version 2 (−1 avant, et son bouclier n'est pas lu).
        let foe_shield = flag(foe[FOE_FLAGS], BLOCKING);
        let my_shield = flag(me[ME_FLAGS], BLOCKING);
        let view = if mine {
            View {
                t: sample.t,
                fall: me[ME_FALL],
                falling: me[VY] < 0.0 && !my_grounded,
                grounded: my_grounded,
                target_grounded: foe_grounded,
                above: dy,
                horizontal,
                distance,
                pos: my_pos,
                target: foe_pos,
                vel: [me[VX], me[VY], me[VZ]],
                target_vel: *fv,
                target_speed: (fv[0] * fv[0] + fv[2] * fv[2]).sqrt(),
                shielded: foe_shield,
                front: foe_shield && in_front(&foe_pos, foe[FOE_HEAD_YAW], &my_pos),
                target_ground_height: foe[FOE_GROUND],
                gliding: flag(me[ME_FLAGS], GLIDING),
                using: flag(me[ME_FLAGS], USING_ITEM),
                yaw: me[ME_YAW],
            }
        } else {
            // Sa montée ou sa descente se lit dans ses positions.
            let continuous = !peak.is_nan() && sample.t - last_t <= GAP_TICKS;
            let rising = !continuous || foe[Y] >= last_y - 0.001;
            // Un trou dans les ticks (adversaire hors de portée) : on repart de zéro.
            if foe_grounded || rising {
                peak = foe[Y];
            }
            last_t = sample.t;
            last_y = foe[Y];
            View {
                t: sample.t,
                fall: peak - foe[Y],
                falling: !foe_grounded && !rising,
                grounded: foe_grounded,
                target_grounded: my_grounded,
                above: -dy,
                horizontal,
                distance,
                pos: foe_pos,
                target: my_pos,
                vel: *fv,
                target_vel: [me[VX], me[VY], me[VZ]],
                target_speed: (me[VX] * me[VX] + me[VZ] * me[VZ]).sqrt(),
                shielded: my_shield,
                front: my_shield && in_front(&my_pos, me[ME_YAW], &foe_pos),
                target_ground_height: me[ME_GROUND],
                gliding: flag(foe[FOE_FLAGS], GLIDING),
                using: flag(foe[FOE_FLAGS], USING_ITEM),
                yaw: foe[FOE_HEAD_YAW],
            }
        };
        out.push(view);
    }
    out
}

/// Le dernier tick enregistré qui ne soit pas postérieur à `t`.
pub(super) fn sample_at(samples: &[Sample], t: u64) -> Option<&Sample> {
    let index = samples.partition_point(|s| s.t <= t);
    index.checked_sub(1).map(|i| &samples[i])
}

/// La dernière vue qui ne soit pas postérieure à `t`, à `within` ticks près.
pub(super) fn view_at(views: &[View], t: u64, within: u64) -> Option<&View> {
    let index = views.partition_point(|v| v.t <= t);
    index.checked_sub(1).map(|i| &views[i]).filter(|v| t - v.t <= within)
}

/// Le ping moyen en ticks, pour relier un coup donné à son paquet de dégâts.
pub(super) fn ping_ticks(samples: &[Sample]) -> u64 {
    let pings: Vec<f64> = samples.iter().map(|s| s.me[ME_PING]).filter(|p| *p >= 0.0).collect();
    if pings.is_empty() {
        return 2;
    }
    ((pings.iter().sum::<f64>() / pings.len() as f64) / 50.0).ceil() as u64
}

#[cfg(test)]
pub(super) mod tests {
    use super::*;

    /// Une vue d'essai : l'attaquant à `above` blocs au-dessus de la cible,
    /// à `horizontal` blocs d'elle, en chute de `fall` blocs.
    pub(in super::super) fn view(t: u64, fall: f64, above: f64, horizontal: f64) -> View {
        View {
            t,
            fall,
            falling: fall > 0.0,
            grounded: fall <= 0.0,
            target_grounded: true,
            above,
            horizontal,
            distance: (above * above + horizontal * horizontal).sqrt(),
            pos: [0.0, above, 0.0],
            target: [horizontal, 0.0, 0.0],
            vel: [0.0; 3],
            target_vel: [0.0; 3],
            target_speed: 0.0,
            shielded: false,
            front: false,
            target_ground_height: -1.0,
            gliding: false,
            using: false,
            yaw: 0.0,
        }
    }

    #[test]
    fn le_regard_dit_qui_est_devant() {
        // Lacet 0 : le jeu regarde vers +Z.
        let target = [0.0, 0.0, 0.0];
        assert!(in_front(&target, 0.0, &[0.0, 0.0, 3.0]));
        assert!(!in_front(&target, 0.0, &[0.0, 0.0, -3.0]));
        assert!(in_front(&target, 90.0, &[-3.0, 0.0, 0.0]), "lacet 90 : vers −X");
    }
}
