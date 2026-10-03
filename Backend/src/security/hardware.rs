// Empreinte matérielle de ce poste, pour l'anti-alt du serveur : un compte
// banni l'est avec les appareils où il a été vu, et recréer un compte depuis
// le même PC est refusé.
//
// `device_id` (le fichier des statistiques) ne peut pas tenir ce rôle : le
// supprimer en donne un nouveau. Ici la source est l'identifiant que le
// système garde pour lui-même — `MachineGuid` sous Windows, `machine-id` sous
// Linux —, qui survit à une réinstallation du launcher.
//
// Ce qui part au serveur est un SHA-256 salé de cet identifiant, jamais
// l'identifiant : le serveur sait reconnaître deux fois le même poste, pas
// lire ce qui le désigne. Sans source lisible, rien n'est envoyé — le
// serveur traite alors ce launcher comme un ancien, sans empreinte.

use sha2::{Digest, Sha256};
use std::sync::OnceLock;

/// Sel propre à YuyuFrame : la même machine donne une autre empreinte à tout
/// autre logiciel qui hacherait le même identifiant. Ne jamais le changer —
/// toutes les empreintes déjà bannies cesseraient de correspondre.
const SALT: &[u8] = b"yuyuframe-hardware-v1\0";

pub fn hardware_id() -> Option<&'static str> {
    static ID: OnceLock<Option<String>> = OnceLock::new();
    ID.get_or_init(|| machine_id().map(|id| fingerprint(&id))).as_deref()
}

fn fingerprint(machine_id: &str) -> String {
    let mut h = Sha256::new();
    h.update(SALT);
    h.update(machine_id.trim().to_ascii_lowercase().as_bytes());
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(target_os = "windows")]
fn machine_id() -> Option<String> {
    use std::ffi::c_void;

    #[link(name = "advapi32")]
    extern "system" {
        fn RegGetValueW(hkey: isize, subkey: *const u16, value: *const u16, flags: u32, kind: *mut u32, data: *mut c_void, len: *mut u32) -> i32;
    }

    const HKEY_LOCAL_MACHINE: isize = 0x8000_0002_u32 as i32 as isize;
    const RRF_RT_REG_SZ: u32 = 0x0000_0002;
    // Vue 64 bits du registre, quelle que soit l'architecture du launcher :
    // la vue 32 bits n'a pas cette valeur.
    const RRF_SUBKEY_WOW6464KEY: u32 = 0x0001_0000;

    let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    let subkey = wide("SOFTWARE\\Microsoft\\Cryptography");
    let value = wide("MachineGuid");
    // Un GUID fait 36 caractères ; la marge couvre une valeur inattendue.
    let mut buf = [0u16; 128];
    let mut len = std::mem::size_of_val(&buf) as u32;
    let status = unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            subkey.as_ptr(),
            value.as_ptr(),
            RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY,
            std::ptr::null_mut(),
            buf.as_mut_ptr().cast(),
            &mut len,
        )
    };
    if status != 0 {
        tracing::debug!("MachineGuid illisible (code {status}) : pas d'empreinte matérielle");
        return None;
    }
    let chars = (len as usize / 2).min(buf.len());
    let id = String::from_utf16_lossy(&buf[..chars]);
    let id = id.trim_end_matches('\0').trim();
    (!id.is_empty()).then(|| id.to_string())
}

#[cfg(not(target_os = "windows"))]
fn machine_id() -> Option<String> {
    ["/etc/machine-id", "/var/lib/dbus/machine-id"]
        .iter()
        .filter_map(|p| std::fs::read_to_string(p).ok())
        .map(|s| s.trim().to_string())
        .find(|s| !s.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empreinte_stable_et_opaque() {
        let a = fingerprint("9B2F7A10-1234-4C6D-8E9F-ABCDEF012345");
        assert_eq!(a.len(), 64);
        assert!(a.bytes().all(|b| b.is_ascii_hexdigit()));
        assert_eq!(a, fingerprint(" 9b2f7a10-1234-4c6d-8e9f-abcdef012345\n"), "ni la casse ni les blancs ne comptent");
        assert_ne!(a, fingerprint("9B2F7A10-1234-4C6D-8E9F-ABCDEF012346"));
        assert!(!a.contains("9b2f7a10"), "l'identifiant du système ne se lit pas dans l'empreinte");
    }

    #[test]
    fn empreinte_de_ce_poste() {
        // Windows a toujours un MachineGuid : ne rien lire y serait un bug de
        // l'appel au registre. Ailleurs, un poste peut ne pas avoir de source.
        #[cfg(target_os = "windows")]
        assert!(hardware_id().is_some(), "MachineGuid illisible");
        if let Some(id) = hardware_id() {
            assert_eq!(id.len(), 64);
            assert_eq!(Some(id), hardware_id(), "la même à chaque appel");
        }
    }
}
