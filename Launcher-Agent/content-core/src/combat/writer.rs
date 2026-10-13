//! Le fil qui tient le fichier du journal. Le fil du jeu dépose ses
//! enregistrements dans une file bornée et repart ; tout le reste — décider
//! quoi garder (`journal`), compresser, écrire — se passe ici.
//!
//! Le fichier est une suite de blocs zstd indépendants, un nouveau chaque
//! seconde : un jeu qui plante ou qu'on ferme d'un coup laisse un journal
//! lisible jusqu'à la dernière seconde, au lieu d'un fichier tronqué que rien
//! ne sait relire. `zstd -dc fichier` (ou tout lecteur zstd) rend les lignes.

use std::fs::File;
use std::io::Write;
use std::path::Path;
use std::sync::mpsc::{sync_channel, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::OnceLock;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use super::journal::{Journal, Out};
use super::record::Record;

/// Enregistrements en attente d'écriture. À 20 ticks par seconde, c'est plus
/// de trois minutes de retard du disque avant de perdre quoi que ce soit.
const QUEUE: usize = 4096;
/// Un bloc zstd est terminé à cette cadence.
const FLUSH_EVERY: Duration = Duration::from_secs(1);
/// Niveau de compression : rapide, le journal est du texte très répétitif.
const ZSTD_LEVEL: i32 = 3;

static SENDER: OnceLock<Option<SyncSender<Record>>> = OnceLock::new();

/// Un duel est-il ouvert ? Tenu à jour par le fil d'écriture après chaque
/// enregistrement, pour que le fil du jeu puisse le lire sans attendre.
static RECORDING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn recording() -> bool {
    RECORDING.load(std::sync::atomic::Ordering::Relaxed)
}

/// Le résumé du dernier duel fermé, et son numéro : l'interface en jeu le
/// relit à chaque tick et n'affiche que ce qui est nouveau.
static LAST_REPORT: std::sync::Mutex<(i32, [f64; super::score::REPORT_LEN])> = std::sync::Mutex::new((0, [0.0; super::score::REPORT_LEN]));

fn publish(numbers: [f64; super::score::REPORT_LEN]) {
    let mut last = LAST_REPORT.lock().unwrap_or_else(|p| p.into_inner());
    *last = (last.0 + 1, numbers);
}

/// Numéro du dernier résumé (0 : aucun duel fermé depuis le lancement) et ses nombres.
pub fn last_report() -> (i32, [f64; super::score::REPORT_LEN]) {
    *LAST_REPORT.lock().unwrap_or_else(|p| p.into_inner())
}

/// Dépose un enregistrement pour le fil d'écriture. Ne bloque jamais : file
/// pleine (disque à l'arrêt), l'enregistrement est perdu plutôt que de figer
/// le jeu.
pub fn send(record: Record) {
    let sender = SENDER.get_or_init(|| {
        let (tx, rx) = sync_channel::<Record>(QUEUE);
        std::thread::Builder::new().name("yf-combat".into()).spawn(move || run(rx)).ok().map(|_| tx)
    });
    if let Some(sender) = sender {
        if let Err(TrySendError::Full(_)) = sender.try_send(record) {
            eprintln!("[YuyuFrame] journal de combat : file pleine, enregistrement perdu");
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// Le fichier ouvert, et les lignes du bloc en cours.
struct Sink {
    file: File,
    pending: Vec<u8>,
    /// Où écrire le résumé du duel, à sa fermeture.
    summary: std::path::PathBuf,
    /// Tout le journal du duel, gardé pour le noter à la fermeture : quelques
    /// centaines de Ko pour un long duel.
    text: String,
}

impl Sink {
    fn open(path: &Path) -> std::io::Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        Ok(Self { file: File::create(path)?, pending: Vec::new(), summary: summary_path(path), text: String::new() })
    }

    fn line(&mut self, line: &str) {
        self.pending.extend_from_slice(line.as_bytes());
        self.pending.push(b'\n');
        self.text.push_str(line);
        self.text.push('\n');
    }

    /// Note le duel et écrit son résumé à côté du journal. Le journal reste
    /// la source : ce fichier se recalcule à volonté.
    fn summarize(&self) {
        let Some(report) = super::score::score_journal(&self.text) else { return };
        publish(report.numbers());
        let written = serde_json::to_string_pretty(&report).map_err(|e| e.to_string()).and_then(|json| std::fs::write(&self.summary, json).map_err(|e| e.to_string()));
        if let Err(e) = written {
            eprintln!("[YuyuFrame] résumé du duel {} impossible à écrire : {e}", self.summary.display());
        }
    }

    /// Termine le bloc en cours : compressé d'un seul tenant, donc lisible seul.
    fn flush(&mut self) -> std::io::Result<()> {
        if self.pending.is_empty() {
            return Ok(());
        }
        let block = zstd::bulk::compress(&self.pending, ZSTD_LEVEL)?;
        self.pending.clear();
        self.file.write_all(&block)?;
        self.file.flush()
    }
}

fn run(records: std::sync::mpsc::Receiver<Record>) {
    let mut journal = Journal::new();
    let mut sink: Option<Sink> = None;
    let mut last_flush = std::time::Instant::now();
    loop {
        let record = match records.recv_timeout(FLUSH_EVERY) {
            Ok(record) => Some(record),
            Err(RecvTimeoutError::Timeout) => None,
            Err(RecvTimeoutError::Disconnected) => break,
        };
        // Une seconde sans rien recevoir : le jeu est en pause ou figé, on
        // met à l'abri ce qui attend.
        let idle = record.is_none();
        for out in record.map(|r| journal.feed(r, now_ms())).unwrap_or_default() {
            match out {
                Out::Open(path) => {
                    close(&mut sink);
                    sink = match Sink::open(&path) {
                        Ok(sink) => Some(sink),
                        Err(e) => {
                            eprintln!("[YuyuFrame] journal de combat : {} impossible à créer : {e}", path.display());
                            None
                        }
                    };
                }
                Out::Line(line) => {
                    if let Some(sink) = sink.as_mut() {
                        sink.line(&line);
                    }
                }
                Out::Close => close(&mut sink),
            }
        }
        RECORDING.store(journal.recording(), std::sync::atomic::Ordering::Relaxed);
        // Un bloc par seconde, à l'horloge : les ticks arrivent vingt fois par
        // seconde, le fil ne connaît donc jamais de silence, et attendre la
        // taille seule laissait la fin d'un duel en mémoire — perdue si le jeu
        // se ferme à ce moment (vu le 2026-10-13 : un journal arrêté en plein
        // combat, sans ligne de résultat).
        if idle || last_flush.elapsed() >= FLUSH_EVERY || sink.as_ref().is_some_and(|s| s.pending.len() >= BLOCK_BYTES) {
            flush(&mut sink);
            last_flush = std::time::Instant::now();
        }
    }
    close(&mut sink);
}

/// Un bloc est aussi terminé dès qu'il atteint cette taille : à 20 ticks par
/// seconde le fil ne connaît jamais une seconde de silence, et sans cette
/// borne tout un combat attendrait en mémoire. Environ une seconde de combat.
const BLOCK_BYTES: usize = 8 * 1024;

fn flush(sink: &mut Option<Sink>) {
    let failed = match sink.as_mut() {
        Some(s) => s.flush().map_err(|e| eprintln!("[YuyuFrame] journal de combat : écriture impossible : {e}")).is_err(),
        None => false,
    };
    // Disque plein ou fichier retiré : on arrête d'écrire dans celui-ci, le
    // prochain combat en ouvrira un autre.
    if failed {
        *sink = None;
    }
}

fn close(sink: &mut Option<Sink>) {
    flush(sink);
    if let Some(sink) = sink.as_ref() {
        sink.summarize();
    }
    *sink = None;
}

/// `…/123.events.ndjson.zst` → `…/123.json`.
pub fn summary_path(journal: &Path) -> std::path::PathBuf {
    let name = journal.file_name().and_then(|n| n.to_str()).unwrap_or("duel");
    journal.with_file_name(format!("{}.json", name.split('.').next().unwrap_or(name)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    #[test]
    fn un_fichier_fait_de_plusieurs_blocs_se_relit_d_un_trait() {
        let dir = std::env::temp_dir().join(format!("yf-combat-test-{}", now_ms()));
        let path = dir.join("yuyuframe").join("matches").join("essai.events.ndjson.zst");
        let mut sink = Sink::open(&path).unwrap();
        sink.pending.extend_from_slice(b"{\"k\":\"h\"}\n");
        sink.flush().unwrap();
        sink.pending.extend_from_slice(b"{\"k\":\"s\"}\n{\"k\":\"d\"}\n");
        sink.flush().unwrap();
        drop(sink);

        let mut text = String::new();
        zstd::stream::read::Decoder::new(File::open(&path).unwrap()).unwrap().read_to_string(&mut text).unwrap();
        assert_eq!(text, "{\"k\":\"h\"}\n{\"k\":\"s\"}\n{\"k\":\"d\"}\n");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn le_resume_s_ecrit_a_cote_du_journal() {
        assert_eq!(summary_path(Path::new("m/1791915779624.events.ndjson.zst")), Path::new("m/1791915779624.json"));
        let dir = std::env::temp_dir().join(format!("yf-combat-resume-{}", now_ms()));
        let mut sink = Some(Sink::open(&dir.join("42.events.ndjson.zst")).unwrap());
        sink.as_mut().unwrap().line("{\"k\":\"h\",\"me\":1}");
        sink.as_mut().unwrap().line("{\"k\":\"r\",\"result\":\"draw\",\"foe\":2}");
        close(&mut sink);
        let report: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(dir.join("42.json")).unwrap()).unwrap();
        assert_eq!(report["result"], "draw");
        assert_eq!(report["reason"], "too_short", "un duel sans échantillon n'est pas noté");
        assert!(report["score"].is_null());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// Outil de mise au point : décompresse un vrai journal en texte, pour le
    /// lire sans installer zstd.
    /// `YF_JOURNAL=<journal.zst> YF_JOURNAL_OUT=<sortie.ndjson> cargo test --lib decompresse_un_journal -- --ignored`
    #[test]
    #[ignore]
    fn decompresse_un_journal() {
        let source = std::env::var("YF_JOURNAL").expect("YF_JOURNAL : le journal à lire");
        let target = std::env::var("YF_JOURNAL_OUT").expect("YF_JOURNAL_OUT : le fichier texte à écrire");
        let mut text = Vec::new();
        zstd::stream::read::Decoder::new(File::open(&source).unwrap()).unwrap().read_to_end(&mut text).unwrap();
        std::fs::write(&target, &text).unwrap();
        println!("{} octets compressés → {} octets, {} lignes", std::fs::metadata(&source).unwrap().len(), text.len(), text.iter().filter(|b| **b == b'\n').count());
    }
}
