//! Le presse-papiers du système, en texte.
//!
//! Fait ici plutôt que dans le jeu : chaque version de Minecraft y accède par
//! une bibliothèque différente (LWJGL 2, GLFW, SDL 3), alors que l'API du
//! système est la même pour toutes. Windows seulement, comme la DLL.

#[cfg(windows)]
mod imp {
    use windows_sys::Win32::Foundation::{GlobalFree, HANDLE};
    use windows_sys::Win32::System::DataExchange::{
        CloseClipboard, EmptyClipboard, GetClipboardData, IsClipboardFormatAvailable, OpenClipboard, SetClipboardData,
    };
    use windows_sys::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock, GMEM_MOVEABLE};

    /// Texte en UTF-16, terminé par un zéro.
    const CF_UNICODETEXT: u32 = 13;
    /// Un lien en douze parties fait moins de 100 Ko ; au-delà ce n'en est pas un.
    const MAX_UNITS: usize = 512 * 1024;

    /// Ferme le presse-papiers en sortant, quel que soit le chemin.
    struct Opened;

    impl Opened {
        fn open() -> Result<Self, String> {
            // Un autre programme peut le tenir un instant : quelques essais.
            for _ in 0..10 {
                if unsafe { OpenClipboard(std::ptr::null_mut()) } != 0 {
                    return Ok(Self);
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            Err("Presse-papiers occupé par un autre programme".into())
        }
    }

    impl Drop for Opened {
        fn drop(&mut self) {
            unsafe { CloseClipboard() };
        }
    }

    pub fn set(text: &str) -> Result<(), String> {
        let units: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
        let _opened = Opened::open()?;
        unsafe {
            if EmptyClipboard() == 0 {
                return Err("Presse-papiers inaccessible".into());
            }
            let memory = GlobalAlloc(GMEM_MOVEABLE, units.len() * 2);
            if memory.is_null() {
                return Err("Mémoire insuffisante pour le presse-papiers".into());
            }
            let target = GlobalLock(memory) as *mut u16;
            if target.is_null() {
                GlobalFree(memory);
                return Err("Presse-papiers inaccessible".into());
            }
            std::ptr::copy_nonoverlapping(units.as_ptr(), target, units.len());
            GlobalUnlock(memory);
            // Accepté : la mémoire appartient désormais au système. Refusé : elle reste à nous.
            if SetClipboardData(CF_UNICODETEXT, memory as HANDLE).is_null() {
                GlobalFree(memory);
                return Err("Presse-papiers inaccessible".into());
            }
        }
        Ok(())
    }

    pub fn get() -> Result<String, String> {
        if unsafe { IsClipboardFormatAvailable(CF_UNICODETEXT) } == 0 {
            return Err("Le presse-papiers ne contient pas de texte".into());
        }
        let _opened = Opened::open()?;
        unsafe {
            let memory = GetClipboardData(CF_UNICODETEXT);
            if memory.is_null() {
                return Err("Le presse-papiers ne contient pas de texte".into());
            }
            let source = GlobalLock(memory) as *const u16;
            if source.is_null() {
                return Err("Presse-papiers inaccessible".into());
            }
            // La taille du bloc borne la lecture : on ne suit pas un texte sans zéro final.
            let capacity = (GlobalSize(memory) / 2).min(MAX_UNITS);
            let units = std::slice::from_raw_parts(source, capacity);
            let length = units.iter().position(|unit| *unit == 0).unwrap_or(capacity);
            let text = String::from_utf16_lossy(&units[..length]);
            GlobalUnlock(memory);
            Ok(text)
        }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn set(_text: &str) -> Result<(), String> {
        Err("Presse-papiers indisponible sur ce système".into())
    }

    pub fn get() -> Result<String, String> {
        Err("Presse-papiers indisponible sur ce système".into())
    }
}

pub use imp::{get, set};
