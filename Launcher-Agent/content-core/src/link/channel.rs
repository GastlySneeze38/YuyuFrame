//! Le thread qui tient le tube, et la file des questions que le jeu lui
//! laisse. Le tube lui-même est derrière `Transport`, pour que la file se
//! teste sans launcher.

use std::collections::{HashSet, VecDeque};
use std::io::{BufRead, BufReader, Read, Write};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::presence::token::{self, TokenStore};

/// Nom du tube, posé par le launcher dans l'environnement du jeu (`PIPE_ENV`
/// de son côté).
const PIPE_ENV: &str = "YUYU_GAME_PIPE";
/// Seul un tube créé par le launcher porte ce début de nom : on n'ouvre pas
/// n'importe quel chemin trouvé dans l'environnement.
const PIPE_PREFIX: &str = r"\\.\pipe\yuyuframe-game-";
/// Le jeton d'accès vit 15 minutes et le launcher le renouvelle une minute
/// avant sa fin : le redemander plus souvent que cette minute garantit
/// d'avoir le suivant avant que le sien n'expire.
const TOKEN_EVERY: Duration = Duration::from_secs(20);
/// Une réponse fait au plus quelques kilo-octets (un jeton Minecraft).
const MAX_REPLY: u64 = 64 * 1024;
/// Une question du jeu tient en une ligne courte.
const MAX_QUESTION: usize = 256;
/// Réponses que Java n'est jamais venu relever : au-delà, les plus anciennes partent.
const MAX_UNCLAIMED: usize = 32;

/// Ce que répond le canal quand le launcher n'est plus là.
pub const CLOSED_REPLY: &str = "error\tLe launcher YuyuFrame n'est plus joignable";

/// Une question, une réponse.
pub trait Transport {
    fn ask(&mut self, question: &str) -> std::io::Result<String>;
}

struct Pipe {
    writer: std::fs::File,
    reader: BufReader<std::fs::File>,
}

impl Pipe {
    fn open(name: &str) -> Option<Self> {
        if !name.starts_with(PIPE_PREFIX) {
            return None;
        }
        let writer = std::fs::OpenOptions::new().read(true).write(true).open(name).ok()?;
        let reader = BufReader::new(writer.try_clone().ok()?);
        Some(Self { writer, reader })
    }
}

impl Transport for Pipe {
    fn ask(&mut self, question: &str) -> std::io::Result<String> {
        self.writer.write_all(question.as_bytes())?;
        self.writer.write_all(b"\n")?;
        self.writer.flush()?;
        let mut reply = String::new();
        let read = (&mut self.reader).take(MAX_REPLY).read_line(&mut reply)?;
        if read == 0 || !reply.ends_with('\n') {
            return Err(std::io::ErrorKind::UnexpectedEof.into());
        }
        reply.truncate(reply.trim_end_matches(['\r', '\n']).len());
        Ok(reply)
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Poll {
    /// La réponse n'est pas encore là.
    Pending,
    /// Numéro inconnu : jamais donné, ou réponse déjà relevée.
    Unknown,
    Done(String),
}

#[derive(Default)]
struct Queue {
    last_id: u64,
    /// (numéro, question) ; numéro 0 = personne n'attend la réponse.
    pending: VecDeque<(u64, String)>,
    /// Dans l'ordre d'arrivée, pour jeter les plus anciennes.
    done: VecDeque<(u64, String)>,
    waiting: HashSet<u64>,
    closed: bool,
}

#[derive(Default)]
pub struct Link {
    queue: Mutex<Queue>,
    wake: Condvar,
}

impl Link {
    fn queue(&self) -> MutexGuard<'_, Queue> {
        self.queue.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn valid(question: &str) -> bool {
        !question.is_empty() && question.len() <= MAX_QUESTION && !question.contains(['\r', '\n'])
    }

    /// Dépose une question. Rend son numéro, à présenter à `poll` — ou 0 si
    /// elle ne peut pas partir (canal coupé, question malformée).
    pub fn send(&self, question: &str) -> u64 {
        if !Self::valid(question) {
            return 0;
        }
        let mut queue = self.queue();
        if queue.closed {
            return 0;
        }
        queue.last_id += 1;
        let id = queue.last_id;
        queue.waiting.insert(id);
        queue.pending.push_back((id, question.to_string()));
        drop(queue);
        self.wake.notify_one();
        id
    }

    /// Dépose un message dont la réponse n'intéresse personne.
    pub fn notify(&self, question: &str) -> bool {
        if !Self::valid(question) {
            return false;
        }
        let mut queue = self.queue();
        if queue.closed {
            return false;
        }
        queue.pending.push_back((0, question.to_string()));
        drop(queue);
        self.wake.notify_one();
        true
    }

    /// Relève une réponse. Une réponse relevée ne l'est qu'une fois.
    pub fn poll(&self, id: u64) -> Poll {
        let mut queue = self.queue();
        if let Some(at) = queue.done.iter().position(|(done, _)| *done == id) {
            return Poll::Done(queue.done.remove(at).map(|(_, reply)| reply).unwrap_or_default());
        }
        match queue.waiting.contains(&id) {
            true => Poll::Pending,
            false => Poll::Unknown,
        }
    }

    fn finish(queue: &mut Queue, id: u64, reply: String) {
        if id == 0 || !queue.waiting.remove(&id) {
            return;
        }
        queue.done.push_back((id, reply));
        while queue.done.len() > MAX_UNCLAIMED {
            queue.done.pop_front();
        }
    }

    /// Le launcher n'est plus là : tout ce qui attendait reçoit la même réponse.
    fn close(&self) {
        let mut queue = self.queue();
        queue.closed = true;
        let pending: Vec<u64> = queue.waiting.iter().copied().collect();
        queue.pending.clear();
        for id in pending {
            Self::finish(&mut queue, id, CLOSED_REPLY.to_string());
        }
    }

    /// La prochaine question du jeu, ou `None` quand l'heure de redemander
    /// le jeton arrive la première.
    fn next(&self, token_due: Instant) -> Option<(u64, String)> {
        let mut queue = self.queue();
        loop {
            if let Some(job) = queue.pending.pop_front() {
                return Some(job);
            }
            let now = Instant::now();
            if now >= token_due {
                return None;
            }
            queue = self.wake.wait_timeout(queue, token_due - now).unwrap_or_else(|p| p.into_inner()).0;
        }
    }

    /// Sert la file jusqu'à ce que le tube casse.
    fn run(&self, transport: &mut dyn Transport, tokens: &TokenStore) {
        let mut token_due = Instant::now();
        loop {
            match self.next(token_due) {
                Some((id, question)) => match transport.ask(&question) {
                    Ok(reply) => Self::finish(&mut self.queue(), id, reply),
                    Err(_) => break,
                },
                None => {
                    match transport.ask("token") {
                        Ok(reply) => apply_token(tokens, &reply),
                        Err(_) => break,
                    }
                    token_due = Instant::now() + TOKEN_EVERY;
                }
            }
        }
        // Sans launcher, le dernier jeton reste valable jusqu'à son terme (15 minutes au plus).
        self.close();
    }
}

/// La réponse à `token` : `token⇥<visible 0/1>⇥<jeton ou ->`.
fn apply_token(tokens: &TokenStore, reply: &str) {
    let mut parts = reply.split('\t');
    if parts.next() != Some("token") {
        return;
    }
    let (Some(visible), Some(token)) = (parts.next(), parts.next()) else { return };
    tokens.update((token != "-").then_some(token), visible == "1");
}

/// `None` une fois pour toutes si le jeu n'a pas été lancé par le launcher :
/// l'environnement du processus ne changera pas en cours de partie.
static LINK: OnceLock<Option<Arc<Link>>> = OnceLock::new();

/// Le canal, ouvert à la première demande.
pub fn link() -> Option<&'static Arc<Link>> {
    LINK.get_or_init(|| {
        let name = std::env::var(PIPE_ENV).ok()?;
        let mut pipe = Pipe::open(&name)?;
        let link = Arc::new(Link::default());
        let for_thread = link.clone();
        let tokens = token::store();
        std::thread::Builder::new().name("yf-link".into()).spawn(move || for_thread.run(&mut pipe, &tokens)).ok()?;
        Some(link)
    })
    .as_ref()
}

/// Le jeu a-t-il un launcher au bout du fil ? Vrai aussi après une coupure :
/// les questions répondent alors « indisponible ».
pub fn available() -> bool {
    link().is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un launcher de test : répond à `token`, renvoie le reste en écho, et
    /// raccroche quand on lui dit `bye`.
    struct Fake {
        token: &'static str,
        asked: Vec<String>,
    }

    impl Transport for Fake {
        fn ask(&mut self, question: &str) -> std::io::Result<String> {
            self.asked.push(question.to_string());
            match question {
                "token" => Ok(self.token.to_string()),
                "bye" => Err(std::io::ErrorKind::BrokenPipe.into()),
                other => Ok(format!("echo\t{other}")),
            }
        }
    }

    #[test]
    fn une_question_malformee_ne_part_pas() {
        let link = Link::default();
        assert_eq!(link.send(""), 0);
        assert_eq!(link.send("deux\nlignes"), 0);
        assert_eq!(link.send(&"x".repeat(MAX_QUESTION + 1)), 0);
        assert!(!link.notify("deux\rlignes"));
        assert_eq!(link.poll(1), Poll::Unknown);
    }

    #[test]
    fn le_jeton_d_abord_puis_les_questions_dans_l_ordre() {
        let link = Link::default();
        let tokens = TokenStore::default();
        let accounts = link.send("accounts");
        assert!(link.notify("ready"));
        let switch = link.send("switch\tabc");
        let bye = link.send("bye");
        assert_eq!(link.poll(accounts), Poll::Pending);

        let mut launcher = Fake { token: "token\t1\taaa.bbb.ccc", asked: Vec::new() };
        link.run(&mut launcher, &tokens);

        // Les questions du jeu passent devant ; le jeton n'est demandé que file vide.
        assert_eq!(launcher.asked, ["accounts", "ready", "switch\tabc", "bye"]);
        assert_eq!(link.poll(accounts), Poll::Done("echo\taccounts".into()));
        assert_eq!(link.poll(accounts), Poll::Unknown, "une réponse ne se relève qu'une fois");
        assert_eq!(link.poll(switch), Poll::Done("echo\tswitch\tabc".into()));
        // Le tube a cassé sur « bye » : elle reçoit la réponse de coupure, et plus rien ne part.
        assert_eq!(link.poll(bye), Poll::Done(CLOSED_REPLY.into()));
        assert_eq!(link.send("accounts"), 0);
        assert!(!link.notify("ready"));
    }

    #[test]
    fn file_vide_le_jeton_est_demande_et_range() {
        let link = Arc::new(Link::default());
        let tokens = Arc::new(TokenStore::default());
        let (for_thread, tokens_for_thread) = (link.clone(), tokens.clone());
        let thread = std::thread::spawn(move || {
            let mut launcher = Fake { token: "token\t1\taaa.bbb.ccc", asked: Vec::new() };
            for_thread.run(&mut launcher, &tokens_for_thread);
            launcher.asked
        });
        // Le jeton est la première chose demandée ; « bye » fait ensuite raccrocher.
        while tokens.get().is_none() {
            std::thread::yield_now();
        }
        assert!(tokens.visible());
        link.send("bye");
        assert_eq!(thread.join().unwrap(), ["token", "bye"]);
    }

    #[test]
    fn la_reponse_au_jeton_se_lit_strictement() {
        let tokens = TokenStore::default();
        apply_token(&tokens, "token\t0\taaa.bbb.ccc");
        assert_eq!(tokens.get().as_deref(), Some("aaa.bbb.ccc"));
        assert!(!tokens.visible());
        // Pas connecté à YuyuFrame : plus de jeton, la visibilité suit quand même.
        apply_token(&tokens, "token\t1\t-");
        assert!(tokens.get().is_none());
        assert!(tokens.visible());
        // Une autre réponse (erreur, ligne tronquée) ne touche à rien.
        apply_token(&tokens, "error\tpanne");
        apply_token(&tokens, "token\t0");
        assert!(tokens.visible());
    }

    #[test]
    fn les_reponses_jamais_relevees_ne_s_accumulent_pas() {
        let link = Link::default();
        let tokens = TokenStore::default();
        let first = link.send("un");
        for _ in 0..MAX_UNCLAIMED {
            link.send("suite");
        }
        link.send("bye");
        link.run(&mut Fake { token: "token\t0\t-", asked: Vec::new() }, &tokens);
        assert_eq!(link.poll(first), Poll::Unknown, "la plus ancienne a été jetée");
    }

    #[test]
    fn seul_un_tube_du_launcher_est_ouvert() {
        assert!(Pipe::open(r"C:\Windows\win.ini").is_none());
        assert!(Pipe::open(r"\\.\pipe\autre-chose").is_none());
        assert!(Pipe::open(r"\\.\pipe\yuyuframe-game-inexistant").is_none());
    }
}
