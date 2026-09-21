//! Agrégation des sessions de jeu.
//!
//! Tout le calcul est ici, en Rust, plutôt qu'en SQL. Trois raisons :
//!
//! - **Minuit.** Une partie de 23 h à 2 h n'appartient pas à un jour, elle
//!   appartient à deux. L'ancien `GROUP BY date(started_at)` mettait les trois
//!   heures sur la veille, ce qui rendait le graphique d'activité faux pour
//!   quiconque joue le soir. Découper correctement demande de connaître le
//!   fuseau de la personne — SQLite ne le connaît pas.
//! - **Les parties en cours.** Elles n'ont pas encore de durée en base ; leur
//!   temps se calcule depuis maintenant.
//! - **Testable.** Ce sont des fonctions pures sur des `Vec`, pas des chaînes
//!   SQL. Le volume (quelques milliers de lignes dans le pire des cas) tient
//!   largement en mémoire.

use std::collections::{BTreeMap, HashMap};

use chrono::{Duration, Local, NaiveDate, TimeZone};
use serde::Serialize;

use crate::db::stats::SessionRow;

// ── Ce que le frontend reçoit ────────────────────────────────────────────────

#[derive(Serialize, Clone, Debug)]
pub struct DayStat {
    /// Date locale, `AAAA-MM-JJ`.
    pub date: String,
    pub secs: i64,
    pub sessions: i64,
}

#[derive(Serialize, Clone, Debug)]
pub struct InstanceStat {
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub sessions: i64,
    pub total_secs: i64,
    pub crashed: i64,
    pub last_played_at: i64,
    /// Durée moyenne d'une session sur cette instance.
    pub avg_secs: i64,
}

/// Répartition par loader ou par version — même forme pour les deux.
#[derive(Serialize, Clone, Debug)]
pub struct Bucket {
    pub key: String,
    pub secs: i64,
    pub sessions: i64,
}

#[derive(Serialize, Clone, Debug)]
pub struct RecentSession {
    pub id: i64,
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub started_at: i64,
    pub duration_secs: i64,
    pub crashed: bool,
    pub crash_report_id: Option<String>,
    /// La partie tourne encore : la durée grandit.
    pub running: bool,
    /// Fin déduite après coup, pas observée — la durée est un minimum.
    pub recovered: bool,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct Totals {
    pub secs: i64,
    pub sessions: i64,
    pub crashed: i64,
    pub avg_secs: i64,
    pub longest_secs: i64,
    /// Jours où au moins une partie a été jouée, sur la période.
    pub active_days: i64,
    /// Jours consécutifs jusqu'à aujourd'hui.
    pub current_streak: i64,
    pub longest_streak: i64,
    pub best_day: Option<DayStat>,
}

#[derive(Serialize, Clone, Debug)]
pub struct StatsPayload {
    pub from: i64,
    pub to: i64,
    pub totals: Totals,
    /// Un point par jour de la période, les jours sans jeu compris : c'est le
    /// calendrier qui les dessine, et un trou n'est pas l'absence de donnée.
    pub daily: Vec<DayStat>,
    /// Temps joué par heure de la journée, 0 h → 23 h.
    pub hourly: Vec<i64>,
    pub per_instance: Vec<InstanceStat>,
    pub per_loader: Vec<Bucket>,
    pub per_version: Vec<Bucket>,
    pub recent: Vec<RecentSession>,
    /// Ce qui tourne en ce moment.
    pub running: Vec<RecentSession>,
    /// Valeurs présentes dans TOUT l'historique, pour remplir les filtres —
    /// pas seulement celles de la période affichée, sinon filtrer ferait
    /// disparaître l'option qu'on vient de choisir.
    pub known_instances: Vec<InstanceRef>,
    pub known_loaders: Vec<String>,
    pub known_versions: Vec<String>,
    pub first_session_at: Option<i64>,
}

#[derive(Serialize, Clone, Debug)]
pub struct InstanceRef {
    pub id: String,
    pub name: String,
}

/// Filtres appliqués avant tout calcul.
#[derive(Clone, Debug, Default)]
pub struct Filters {
    pub instance_id: Option<String>,
    pub loader: Option<String>,
    pub mc_version: Option<String>,
}

impl Filters {
    fn keeps(&self, s: &SessionRow) -> bool {
        self.instance_id.as_ref().is_none_or(|v| &s.instance_id == v)
            && self.loader.as_ref().is_none_or(|v| &s.loader == v)
            && self.mc_version.as_ref().is_none_or(|v| &s.mc_version == v)
    }
}

// ── Découpage du temps ───────────────────────────────────────────────────────

fn local_date(ts: i64) -> NaiveDate {
    Local.timestamp_opt(ts, 0).earliest().map(|d| d.date_naive()).unwrap_or_default()
}

/// Instant du prochain minuit local strictement après `ts`.
fn next_midnight(ts: i64) -> i64 {
    let date = local_date(ts);
    let mut day = date;
    // Boucle plutôt que `+ 1 jour` en dur : sur un changement d'heure, le
    // minuit suivant peut ne pas exister (heure sautée) ou tomber avant `ts`.
    for _ in 0..3 {
        day = day.succ_opt().unwrap_or(day);
        if let Some(midnight) = day.and_hms_opt(0, 0, 0).and_then(|d| Local.from_local_datetime(&d).earliest()) {
            if midnight.timestamp() > ts {
                return midnight.timestamp();
            }
        }
    }
    ts + 86_400
}

/// Répartit `[start, end)` sur les jours locaux qu'il traverse.
fn split_days(start: i64, end: i64) -> Vec<(NaiveDate, i64)> {
    let mut out = Vec::new();
    let mut cursor = start;
    // Une session absurde (horloge remise à l'heure en plein jeu) ne doit pas
    // faire tourner cette boucle des milliers de fois.
    for _ in 0..400 {
        if cursor >= end {
            break;
        }
        let boundary = next_midnight(cursor).min(end);
        out.push((local_date(cursor), boundary - cursor));
        cursor = boundary;
    }
    out
}

/// Répartit `[start, end)` sur les 24 heures de la journée.
fn add_hours(bins: &mut [i64; 24], start: i64, end: i64) {
    let mut cursor = start;
    for _ in 0..(400 * 24) {
        if cursor >= end {
            break;
        }
        // Frontière de l'heure suivante, calculée en local : certains fuseaux
        // sont décalés de 30 ou 45 minutes.
        let local = match Local.timestamp_opt(cursor, 0).earliest() {
            Some(v) => v,
            None => break,
        };
        let minute_offset = i64::from(local.timestamp().rem_euclid(3600));
        let boundary = (cursor - minute_offset + 3600).max(cursor + 1).min(end);
        let hour = local.format("%H").to_string().parse::<usize>().unwrap_or(0).min(23);
        bins[hour] += boundary - cursor;
        cursor = boundary;
    }
}

// ── Le calcul ────────────────────────────────────────────────────────────────

/// Intervalle réellement joué d'une session, et son état.
struct Span {
    start: i64,
    end: i64,
    running: bool,
    recovered: bool,
}

/// Une session n'a pas toujours de fin écrite. L'ordre de préférence dit à
/// quel point on est sûr de la durée : la fin observée d'abord, puis la
/// dernière trace de vie (on sait qu'on a joué au moins jusque-là), puis le
/// temps écoulé depuis le début pour une partie en cours.
fn span_of(s: &SessionRow, now: i64) -> Span {
    let running = s.duration_secs.is_none() && s.end_reason.as_deref() == Some("running") && s.ended_at.is_none();
    match (s.ended_at, s.duration_secs) {
        (Some(end), _) => Span {
            start: s.started_at,
            end: end.max(s.started_at),
            running: false,
            recovered: s.end_reason.as_deref() == Some("recovered"),
        },
        // Session d'avant la refonte : une durée sans fin datée.
        (None, Some(d)) => Span { start: s.started_at, end: s.started_at + d.max(0), running: false, recovered: false },
        (None, None) => Span {
            start: s.started_at,
            end: if running { now.max(s.started_at) } else { s.last_seen_at.unwrap_or(s.started_at).max(s.started_at) },
            running,
            recovered: !running,
        },
    }
}

/// Calcule tout ce que la page Stats affiche.
///
/// `sessions` vient de `db::sessions_in_range` (donc déjà borné à la période,
/// chevauchements compris) ; `all_known` sert seulement à remplir les listes
/// de filtres, qui ne doivent pas dépendre de la période choisie.
pub fn compute(
    sessions: &[SessionRow],
    all_known: &[SessionRow],
    filters: &Filters,
    from: i64,
    to: i64,
    now: i64,
    first_session_at: Option<i64>,
) -> StatsPayload {
    let kept: Vec<&SessionRow> = sessions.iter().filter(|s| filters.keeps(s)).collect();

    let mut per_day: BTreeMap<NaiveDate, (i64, i64)> = BTreeMap::new();
    let mut hourly = [0i64; 24];
    let mut per_instance: HashMap<&str, InstanceStat> = HashMap::new();
    let mut per_loader: HashMap<&str, (i64, i64)> = HashMap::new();
    let mut per_version: HashMap<&str, (i64, i64)> = HashMap::new();
    let mut totals = Totals::default();

    for s in &kept {
        let span = span_of(s, now);
        // Ne compter que la part qui tombe DANS la période : une session à
        // cheval sur sa frontière compterait sinon du temps qu'on n'affiche pas.
        let (start, end) = (span.start.max(from), span.end.min(to));
        if end <= start {
            continue;
        }
        let secs = end - start;

        totals.secs += secs;
        totals.sessions += 1;
        totals.crashed += s.crashed as i64;
        totals.longest_secs = totals.longest_secs.max(secs);

        for (day, d) in split_days(start, end) {
            let slot = per_day.entry(day).or_insert((0, 0));
            slot.0 += d;
        }
        // Le compteur de sessions du jour se pose sur le jour de DÉBUT : une
        // partie à cheval sur minuit reste une seule partie.
        per_day.entry(local_date(start)).or_insert((0, 0)).1 += 1;
        add_hours(&mut hourly, start, end);

        let entry = per_instance.entry(&s.instance_id).or_insert_with(|| InstanceStat {
            instance_id: s.instance_id.clone(),
            instance_name: s.instance_name.clone(),
            mc_version: s.mc_version.clone(),
            loader: s.loader.clone(),
            sessions: 0,
            total_secs: 0,
            crashed: 0,
            last_played_at: 0,
            avg_secs: 0,
        });
        entry.sessions += 1;
        entry.total_secs += secs;
        entry.crashed += s.crashed as i64;
        // Le nom affiché est celui de la session la plus récente : renommer
        // une instance ne doit pas faire réapparaître son ancien nom au hasard,
        // ce que faisait le `GROUP BY` d'avant.
        if s.started_at > entry.last_played_at {
            entry.last_played_at = s.started_at;
            entry.instance_name = s.instance_name.clone();
            entry.mc_version = s.mc_version.clone();
            entry.loader = s.loader.clone();
        }

        let l = per_loader.entry(&s.loader).or_insert((0, 0));
        l.0 += secs;
        l.1 += 1;
        let v = per_version.entry(&s.mc_version).or_insert((0, 0));
        v.0 += secs;
        v.1 += 1;
    }

    totals.avg_secs = if totals.sessions > 0 { totals.secs / totals.sessions } else { 0 };

    // Série complète de la période, trous compris.
    let daily = fill_days(&per_day, from, to);
    totals.active_days = daily.iter().filter(|d| d.secs > 0).count() as i64;
    totals.best_day = daily.iter().filter(|d| d.secs > 0).max_by_key(|d| d.secs).cloned();
    let (current, longest) = streaks(&daily, local_date(now));
    totals.current_streak = current;
    totals.longest_streak = longest;

    let mut per_instance: Vec<InstanceStat> = per_instance.into_values().collect();
    for i in &mut per_instance {
        i.avg_secs = if i.sessions > 0 { i.total_secs / i.sessions } else { 0 };
    }
    per_instance.sort_by(|a, b| b.total_secs.cmp(&a.total_secs));

    let to_buckets = |map: HashMap<&str, (i64, i64)>| {
        let mut v: Vec<Bucket> = map.into_iter().map(|(k, (secs, sessions))| Bucket { key: k.to_string(), secs, sessions }).collect();
        v.sort_by(|a, b| b.secs.cmp(&a.secs));
        v
    };

    let to_recent = |s: &&SessionRow| {
        let span = span_of(s, now);
        RecentSession {
            id: s.id,
            instance_id: s.instance_id.clone(),
            instance_name: s.instance_name.clone(),
            mc_version: s.mc_version.clone(),
            loader: s.loader.clone(),
            started_at: s.started_at,
            duration_secs: (span.end - span.start).max(0),
            crashed: s.crashed,
            crash_report_id: s.crash_report_id.clone(),
            running: span.running,
            recovered: span.recovered,
        }
    };
    let running: Vec<RecentSession> = kept.iter().filter(|s| span_of(s, now).running).map(&to_recent).collect();
    let recent: Vec<RecentSession> = kept.iter().take(50).map(&to_recent).collect();

    // Listes de filtres : tirées de TOUT l'historique, et sans appliquer les
    // filtres en cours.
    let mut instances: Vec<InstanceRef> = Vec::new();
    let mut loaders: Vec<String> = Vec::new();
    let mut versions: Vec<String> = Vec::new();
    for s in all_known {
        if !instances.iter().any(|i| i.id == s.instance_id) {
            instances.push(InstanceRef { id: s.instance_id.clone(), name: s.instance_name.clone() });
        }
        if !loaders.contains(&s.loader) {
            loaders.push(s.loader.clone());
        }
        if !versions.contains(&s.mc_version) {
            versions.push(s.mc_version.clone());
        }
    }
    instances.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    loaders.sort();
    versions.sort();

    StatsPayload {
        from,
        to,
        totals,
        daily,
        hourly: hourly.to_vec(),
        per_instance,
        per_loader: to_buckets(per_loader),
        per_version: to_buckets(per_version),
        recent,
        running,
        known_instances: instances,
        known_loaders: loaders,
        known_versions: versions,
        first_session_at,
    }
}

/// Série continue de jours, les jours sans jeu à zéro. Bornée : afficher dix
/// ans de cases vides parce que quelqu'un a choisi « depuis le début » ne
/// rendrait service à personne.
fn fill_days(per_day: &BTreeMap<NaiveDate, (i64, i64)>, from: i64, to: i64) -> Vec<DayStat> {
    const MAX_DAYS: i64 = 400;
    let first = local_date(from);
    let last = local_date(to);
    let span = (last - first).num_days().clamp(0, MAX_DAYS);
    let start = last - Duration::days(span);

    let mut out = Vec::with_capacity(span as usize + 1);
    let mut day = start;
    while day <= last {
        let (secs, sessions) = per_day.get(&day).copied().unwrap_or((0, 0));
        out.push(DayStat { date: day.to_string(), secs, sessions });
        day = match day.succ_opt() {
            Some(d) => d,
            None => break,
        };
    }
    out
}

/// Série en cours et plus longue série de jours joués d'affilée.
///
/// La série en cours tolère qu'aujourd'hui soit encore vide : à 9 h du matin,
/// personne n'a encore joué, et annoncer que la série est cassée serait faux.
fn streaks(daily: &[DayStat], today: NaiveDate) -> (i64, i64) {
    let mut longest = 0;
    let mut run = 0;
    for d in daily {
        if d.secs > 0 {
            run += 1;
            longest = longest.max(run);
        } else {
            run = 0;
        }
    }

    let played: Vec<&DayStat> = daily.iter().rev().collect();
    let mut current = 0;
    let mut expected = today;
    for d in played {
        let Ok(date) = d.date.parse::<NaiveDate>() else { continue };
        if date > expected {
            continue;
        }
        if date == expected && d.secs > 0 {
            current += 1;
            expected = expected.pred_opt().unwrap_or(expected);
        } else if date == expected && d.secs == 0 && date == today {
            // Journée pas encore entamée : la série n'est pas rompue pour autant.
            expected = expected.pred_opt().unwrap_or(expected);
        } else {
            break;
        }
    }
    (current, longest)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(id: i64, start: i64, dur: i64, instance: &str, loader: &str) -> SessionRow {
        SessionRow {
            id,
            instance_id: instance.into(),
            instance_name: instance.into(),
            mc_version: "1.21.1".into(),
            loader: loader.into(),
            started_at: start,
            ended_at: Some(start + dur),
            duration_secs: Some(dur),
            last_seen_at: Some(start + dur),
            end_reason: Some("normal".into()),
            crashed: false,
            crash_report_id: None,
        }
    }

    /// Minuit local d'une date donnée, pour écrire des tests qui ne dépendent
    /// pas du fuseau de la machine qui les exécute.
    fn midnight(y: i32, m: u32, d: u32) -> i64 {
        NaiveDate::from_ymd_opt(y, m, d)
            .and_then(|d| d.and_hms_opt(0, 0, 0))
            .and_then(|d| Local.from_local_datetime(&d).earliest())
            .unwrap()
            .timestamp()
    }

    #[test]
    fn an_evening_session_is_split_across_midnight() {
        // 23 h → 2 h : une heure la veille, deux heures le lendemain.
        let start = midnight(2026, 3, 10) + 23 * 3600;
        let parts = split_days(start, start + 3 * 3600);
        assert_eq!(parts.len(), 2, "deux jours touchés");
        assert_eq!(parts[0].1, 3600);
        assert_eq!(parts[1].1, 2 * 3600);
        assert_eq!(parts[0].0, NaiveDate::from_ymd_opt(2026, 3, 10).unwrap());
        assert_eq!(parts[1].0, NaiveDate::from_ymd_opt(2026, 3, 11).unwrap());
    }

    #[test]
    fn a_session_inside_one_day_stays_whole() {
        let start = midnight(2026, 3, 10) + 14 * 3600;
        let parts = split_days(start, start + 5400);
        assert_eq!(parts.len(), 1);
        assert_eq!(parts[0].1, 5400);
    }

    #[test]
    fn hours_are_spread_over_the_clock() {
        let start = midnight(2026, 3, 10) + 22 * 3600;
        let mut bins = [0i64; 24];
        add_hours(&mut bins, start, start + 3 * 3600);
        assert_eq!(bins[22], 3600);
        assert_eq!(bins[23], 3600);
        assert_eq!(bins[0], 3600, "la troisième heure tombe après minuit");
        assert_eq!(bins.iter().sum::<i64>(), 3 * 3600);
    }

    #[test]
    fn a_running_session_counts_up_to_now() {
        let now = midnight(2026, 3, 10) + 12 * 3600;
        let live = SessionRow {
            ended_at: None,
            duration_secs: None,
            end_reason: Some("running".into()),
            ..session(1, now - 5400, 0, "coco", "fabric")
        };
        let span = span_of(&live, now);
        assert!(span.running);
        assert_eq!(span.end - span.start, 5400, "une partie en cours ne vaut pas zéro minute");
    }

    #[test]
    fn an_orphan_session_is_worth_its_last_heartbeat() {
        let now = midnight(2026, 3, 11) + 12 * 3600;
        let start = midnight(2026, 3, 10) + 20 * 3600;
        let orphan = SessionRow {
            ended_at: None,
            duration_secs: None,
            last_seen_at: Some(start + 7200),
            // Plus de `running` : le launcher est reparti depuis.
            end_reason: None,
            ..session(1, start, 0, "coco", "fabric")
        };
        let span = span_of(&orphan, now);
        assert!(!span.running);
        assert!(span.recovered);
        assert_eq!(span.end - span.start, 7200, "jamais jusqu'à maintenant, juste jusqu'au dernier signe de vie");
    }

    #[test]
    fn the_displayed_name_is_the_most_recent_one() {
        let from = midnight(2026, 3, 1);
        let to = midnight(2026, 3, 12);
        let old = SessionRow { instance_name: "Ancien nom".into(), ..session(1, from + 3600, 600, "coco", "fabric") };
        let new = SessionRow { instance_name: "Nouveau nom".into(), ..session(2, from + 100_000, 600, "coco", "fabric") };
        let rows = vec![new.clone(), old];
        let out = compute(&rows, &rows, &Filters::default(), from, to, to, None);
        assert_eq!(out.per_instance.len(), 1);
        assert_eq!(out.per_instance[0].instance_name, "Nouveau nom");
    }

    #[test]
    fn filters_narrow_the_totals_but_not_the_filter_lists() {
        let from = midnight(2026, 3, 1);
        let to = midnight(2026, 3, 12);
        let rows = vec![
            session(1, from + 3600, 1800, "coco", "fabric"),
            session(2, from + 7200, 3600, "autre", "forge"),
        ];
        let filters = Filters { loader: Some("fabric".into()), ..Default::default() };
        let out = compute(&rows, &rows, &filters, from, to, to, None);
        assert_eq!(out.totals.sessions, 1);
        assert_eq!(out.totals.secs, 1800);
        assert_eq!(out.known_loaders.len(), 2, "filtrer ne doit pas retirer l'option qu'on vient de choisir");
    }

    #[test]
    fn time_outside_the_window_is_not_counted() {
        let from = midnight(2026, 3, 10);
        let to = midnight(2026, 3, 11);
        // Commencée deux heures avant la fenêtre, finie une heure dedans.
        let s = session(1, from - 7200, 10_800, "coco", "fabric");
        let out = compute(&[s.clone()], &[s], &Filters::default(), from, to, to, None);
        assert_eq!(out.totals.secs, 3600, "seule la part visible compte");
    }

    #[test]
    fn empty_days_are_present_in_the_series() {
        let from = midnight(2026, 3, 8);
        let to = midnight(2026, 3, 11);
        let s = session(1, midnight(2026, 3, 10) + 3600, 600, "coco", "fabric");
        let out = compute(&[s.clone()], &[s], &Filters::default(), from, to, to, None);
        assert_eq!(out.daily.len(), 4, "un point par jour, trous compris");
        assert_eq!(out.daily.iter().filter(|d| d.secs > 0).count(), 1);
        assert_eq!(out.totals.active_days, 1);
    }

    #[test]
    fn streaks_count_consecutive_days_and_forgive_an_untouched_today() {
        let day = |d: u32, secs| DayStat { date: NaiveDate::from_ymd_opt(2026, 3, d).unwrap().to_string(), secs, sessions: 1 };
        let today = NaiveDate::from_ymd_opt(2026, 3, 12).unwrap();
        let daily = vec![day(8, 100), day(9, 0), day(10, 100), day(11, 100), day(12, 0)];
        let (current, longest) = streaks(&daily, today);
        assert_eq!(longest, 2);
        assert_eq!(current, 2, "aujourd'hui pas encore joué ne casse pas la série d'hier");
    }

    #[test]
    fn a_crash_is_visible_in_the_totals_and_on_its_instance() {
        let from = midnight(2026, 3, 1);
        let to = midnight(2026, 3, 12);
        let mut boom = session(1, from + 3600, 600, "coco", "fabric");
        boom.crashed = true;
        boom.crash_report_id = Some("abc".into());
        let rows = vec![boom, session(2, from + 7200, 600, "coco", "fabric")];
        let out = compute(&rows, &rows, &Filters::default(), from, to, to, None);
        assert_eq!(out.totals.crashed, 1);
        assert_eq!(out.per_instance[0].crashed, 1);
        assert_eq!(out.per_instance[0].sessions, 2);
        assert!(out.recent[0].crashed || out.recent[1].crashed);
    }
}
