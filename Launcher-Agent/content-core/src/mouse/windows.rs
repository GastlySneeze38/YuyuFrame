//! La source Windows : un thread à nous, une fenêtre invisible qui ne sert
//! qu'à recevoir la souris brute, et une lecture par lots.
//!
//! Pourquoi un thread à part : Windows livre la souris brute à une fenêtre,
//! message par message. Sur la fenêtre du jeu, une souris à 8 000 Hz noie le
//! thread de rendu (glfw#2684). Ici, rien n'arrive au jeu : ce thread dort
//! jusqu'à ce que des mouvements soient en attente, les lit tous d'un coup
//! (`GetRawInputBuffer`) et les ajoute aux compteurs.
//!
//! L'inscription vise la catégorie « souris » et non un périphérique : toutes
//! les souris sont cumulées, et une souris branchée en cours de partie est
//! prise en compte sans rien refaire.
//!
//! Même montage que le thread de souris brute de SDL3
//! (`SDL_windowsrawinput.c`).

use std::mem::size_of;
use std::ptr::{null, null_mut};
use std::sync::mpsc;
use std::sync::Mutex;
use std::thread::{self, JoinHandle};

use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, HWND, WAIT_OBJECT_0};
use windows_sys::Win32::System::Threading::{CreateEventW, SetEvent, INFINITE};
use windows_sys::Win32::UI::Input::{
    GetRawInputBuffer, RegisterRawInputDevices, MOUSE_MOVE_ABSOLUTE, MOUSE_VIRTUAL_DESKTOP,
    RAWINPUT, RAWINPUTDEVICE, RAWINPUTHEADER, RAWMOUSE, RIDEV_INPUTSINK, RIDEV_REMOVE,
    RIM_TYPEMOUSE,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DestroyWindow, GetQueueStatus, GetSystemMetrics, MsgWaitForMultipleObjects,
    HWND_MESSAGE, QS_RAWINPUT, SM_CXSCREEN, SM_CXVIRTUALSCREEN, SM_CYSCREEN, SM_CYVIRTUALSCREEN,
};

use super::counters;

/// Catégorie « souris » des périphériques d'entrée (HID : page générique, usage souris).
const HID_USAGE_PAGE_GENERIC: u16 = 1;
const HID_USAGE_GENERIC_MOUSE: u16 = 2;

/// Place pour un lot. Un mouvement de souris pèse 48 octets : 64 Kio en
/// tiennent plus de mille, soit plus d'un dixième de seconde à 8 000 Hz.
const BUFFER_BYTES: usize = 64 * 1024;

struct Source {
    /// Événement Windows (un `HANDLE`) qui demande au thread de s'arrêter.
    /// Rangé en entier : un pointeur brut ne passe pas d'un thread à l'autre.
    done: usize,
    thread: JoinHandle<()>,
}

static SOURCE: Mutex<Option<Source>> = Mutex::new(None);

fn source() -> std::sync::MutexGuard<'static, Option<Source>> {
    // Un verrou empoisonné ne doit pas faire paniquer une fonction appelée
    // depuis Java : on reprend la valeur telle qu'elle est.
    SOURCE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Démarre le thread de lecture. Vrai s'il tourne (déjà ou désormais).
pub(super) fn start() -> bool {
    let mut slot = source();
    if slot.is_some() {
        return true;
    }
    // Remise à zéro manuelle, non signalé.
    let done = unsafe { CreateEventW(null(), 1, 0, null()) };
    if done.is_null() {
        return false;
    }
    let done_addr = done as usize;
    let (ready_tx, ready_rx) = mpsc::channel::<bool>();
    let spawned = thread::Builder::new()
        .name("yf-raw-mouse".into())
        .spawn(move || run(done_addr as HANDLE, ready_tx));
    let thread = match spawned {
        Ok(thread) => thread,
        Err(error) => {
            eprintln!("[content-core] souris brute : thread non créé : {error}");
            unsafe { CloseHandle(done) };
            return false;
        }
    };
    // Le thread dit s'il a pu créer sa fenêtre et s'inscrire.
    if !ready_rx.recv().unwrap_or(false) {
        let _ = thread.join();
        unsafe { CloseHandle(done) };
        return false;
    }
    *slot = Some(Source { done: done_addr, thread });
    true
}

/// Arrête le thread de lecture et attend qu'il ait rendu la souris.
pub(super) fn stop() {
    let Some(source) = source().take() else {
        return;
    };
    unsafe { SetEvent(source.done as HANDLE) };
    if source.thread.join().is_err() {
        eprintln!("[content-core] souris brute : le thread de lecture a paniqué");
    }
    unsafe { CloseHandle(source.done as HANDLE) };
}

fn run(done: HANDLE, ready: mpsc::Sender<bool>) {
    // « Message » : classe fournie par Windows pour les fenêtres qui ne font
    // que recevoir des messages — rien à déclarer, rien à l'écran.
    let class: Vec<u16> = "Message\0".encode_utf16().collect();
    let window: HWND = unsafe {
        CreateWindowExW(0, class.as_ptr(), null(), 0, 0, 0, 0, 0, HWND_MESSAGE, null_mut(), null_mut(), null())
    };
    if window.is_null() {
        eprintln!("[content-core] souris brute : fenêtre de réception non créée");
        let _ = ready.send(false);
        return;
    }

    // INPUTSINK : recevoir même sans le focus clavier, que cette fenêtre
    // n'aura jamais. Le tri « le jeu a-t-il la souris » est fait par
    // `counters` (capture) et par Java (focus de la fenêtre du jeu).
    //
    // Jamais NOLEGACY : il couperait aussi les clics et la molette de tout
    // le processus, que le jeu reçoit toujours par GLFW.
    let register = RAWINPUTDEVICE {
        usUsagePage: HID_USAGE_PAGE_GENERIC,
        usUsage: HID_USAGE_GENERIC_MOUSE,
        dwFlags: RIDEV_INPUTSINK,
        hwndTarget: window,
    };
    if unsafe { RegisterRawInputDevices(&register, 1, size_of::<RAWINPUTDEVICE>() as u32) } == 0 {
        eprintln!("[content-core] souris brute : inscription refusée par Windows");
        unsafe { DestroyWindow(window) };
        let _ = ready.send(false);
        return;
    }
    let _ = ready.send(true);

    // Tableau de u64 : Windows exige un tampon aligné sur 8.
    let mut buffer = vec![0u64; BUFFER_BYTES / 8];
    let mut absolute = AbsoluteTracker::default();
    loop {
        // Dort jusqu'à la demande d'arrêt (indice 0) ou jusqu'à ce que de la
        // souris brute soit en attente (indice 1).
        let woke = unsafe { MsgWaitForMultipleObjects(1, &done, 0, INFINITE, QS_RAWINPUT) };
        if woke != WAIT_OBJECT_0 + 1 {
            break;
        }
        // Marque l'attente comme vue, sinon le prochain sommeil ne dormirait pas.
        unsafe { GetQueueStatus(QS_RAWINPUT) };
        drain(&mut buffer, &mut absolute);
    }

    let unregister = RAWINPUTDEVICE {
        usUsagePage: HID_USAGE_PAGE_GENERIC,
        usUsage: HID_USAGE_GENERIC_MOUSE,
        dwFlags: RIDEV_REMOVE,
        hwndTarget: null_mut(),
    };
    unsafe {
        RegisterRawInputDevices(&unregister, 1, size_of::<RAWINPUTDEVICE>() as u32);
        DestroyWindow(window);
    }
}

/// Lit tout ce qui est en attente, lot par lot, et l'ajoute aux compteurs.
fn drain(buffer: &mut [u64], absolute: &mut AbsoluteTracker) {
    let header_size = size_of::<RAWINPUTHEADER>() as u32;
    loop {
        let mut size = (buffer.len() * 8) as u32;
        let count = unsafe { GetRawInputBuffer(buffer.as_mut_ptr().cast::<RAWINPUT>(), &mut size, header_size) };
        // 0 : plus rien en attente. u32::MAX : erreur — on rend la main, le
        // prochain réveil réessaiera.
        if count == 0 || count == u32::MAX {
            return;
        }
        let (mut dx, mut dy) = (0i64, 0i64);
        let mut block = buffer.as_ptr().cast::<RAWINPUT>();
        for _ in 0..count {
            // SAFETY : Windows vient d'écrire `count` blocs à la suite dans
            // `buffer`, chacun aligné sur 8 et long de `header.dwSize`.
            let input = unsafe { &*block };
            if input.header.dwType == RIM_TYPEMOUSE {
                let (x, y) = motion(unsafe { &input.data.mouse }, absolute);
                dx += x;
                dy += y;
            }
            block = next_block(block, input.header.dwSize);
        }
        if dx != 0 || dy != 0 {
            counters::accumulate(dx, dy);
        }
    }
}

/// Le bloc suivant d'un lot : après celui-ci, à l'adresse alignée sur 8.
fn next_block(block: *const RAWINPUT, size: u32) -> *const RAWINPUT {
    ((block as usize + size as usize + 7) & !7) as *const RAWINPUT
}

/// Le déplacement porté par un événement de souris, en comptes.
fn motion(mouse: &RAWMOUSE, absolute: &mut AbsoluteTracker) -> (i64, i64) {
    if mouse.usFlags & MOUSE_MOVE_ABSOLUTE == 0 {
        return (mouse.lLastX as i64, mouse.lLastY as i64);
    }
    // Tablette, bureau à distance, machine virtuelle : une position et non
    // un déplacement, sur une échelle 0..65535 qui couvre l'écran principal
    // ou tout le bureau.
    let virtual_desktop = mouse.usFlags & MOUSE_VIRTUAL_DESKTOP != 0;
    let (width, height) = unsafe {
        if virtual_desktop {
            (GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN))
        } else {
            (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN))
        }
    };
    absolute.delta(mouse.lLastX, mouse.lLastY, width, height)
}

/// Transforme des positions absolues en déplacements, en pixels.
#[derive(Default)]
struct AbsoluteTracker {
    last: Option<(i64, i64)>,
}

impl AbsoluteTracker {
    fn delta(&mut self, x: i32, y: i32, width: i32, height: i32) -> (i64, i64) {
        let position = (x as i64 * width as i64 / 65535, y as i64 * height as i64 / 65535);
        // La première position ne dit pas d'où l'on vient : pas de déplacement.
        let delta = match self.last {
            Some((last_x, last_y)) => (position.0 - last_x, position.1 - last_y),
            None => (0, 0),
        };
        self.last = Some(position);
        delta
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mouse(flags: u16, x: i32, y: i32) -> RAWMOUSE {
        RAWMOUSE { usFlags: flags, lLastX: x, lLastY: y, ..Default::default() }
    }

    #[test]
    fn mouvement_relatif_pris_tel_quel() {
        let mut absolute = AbsoluteTracker::default();
        assert_eq!(motion(&mouse(0, -12, 7), &mut absolute), (-12, 7));
        assert!(absolute.last.is_none(), "un mouvement relatif ne touche pas au suivi absolu");
    }

    #[test]
    fn position_absolue_devient_un_deplacement() {
        let mut absolute = AbsoluteTracker::default();
        // Écran de 1920×1080 : le milieu de l'échelle est le milieu de l'écran.
        assert_eq!(absolute.delta(32768, 32768, 1920, 1080), (0, 0), "première position : pas de saut");
        assert_eq!(absolute.delta(65535, 32768, 1920, 1080), (960, 0));
        assert_eq!(absolute.delta(65535, 0, 1920, 1080), (0, -540));
        assert_eq!(absolute.delta(65535, 0, 1920, 1080), (0, 0), "immobile");
    }

    #[test]
    fn bloc_suivant_aligne_sur_huit() {
        let base = 0x1000 as *const RAWINPUT;
        assert_eq!(next_block(base, 48) as usize, 0x1030);
        assert_eq!(next_block(base, 41) as usize, 0x1030);
    }

    // Le vrai montage : le thread crée sa fenêtre, s'inscrit, puis rend la
    // souris. Ne vérifie pas qu'un mouvement arrive — personne ne bouge la
    // souris pendant un test.
    #[test]
    fn demarre_et_s_arrete() {
        assert!(start(), "fenêtre ou inscription refusée");
        assert!(start(), "un second démarrage ne fait rien");
        stop();
        stop();
        assert!(start(), "redémarrage après arrêt");
        stop();
    }

    // La chaîne entière, avec un mouvement injecté (le pointeur bouge de
    // 5 pixels puis revient). À lancer à la main, seul :
    //   cargo test --lib mouvement_injecte -- --ignored --test-threads=1
    #[test]
    #[ignore]
    fn mouvement_injecte_arrive_aux_compteurs() {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
            SendInput, INPUT, INPUT_0, INPUT_MOUSE, MOUSEEVENTF_MOVE, MOUSEINPUT,
        };
        fn nudge(dx: i32) {
            let input = INPUT {
                r#type: INPUT_MOUSE,
                Anonymous: INPUT_0 {
                    mi: MOUSEINPUT { dx, dy: 0, mouseData: 0, dwFlags: MOUSEEVENTF_MOVE, time: 0, dwExtraInfo: 0 },
                },
            };
            assert_eq!(unsafe { SendInput(1, &input, size_of::<INPUT>() as i32) }, 1);
        }

        assert!(start());
        counters::set_active(true);
        let (x0, _) = counters::totals();
        nudge(5);
        std::thread::sleep(std::time::Duration::from_millis(200));
        let (x1, _) = counters::totals();
        nudge(-5);
        std::thread::sleep(std::time::Duration::from_millis(200));
        let (x2, _) = counters::totals();
        counters::set_active(false);
        stop();
        assert_eq!(x1 - x0, 5, "le mouvement injecté n'est pas arrivé");
        assert_eq!(x2 - x1, -5);
    }
}
