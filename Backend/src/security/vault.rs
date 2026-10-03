// Coffre du système pour le refresh token de la session YuyuFrame : Windows
// Credential Manager, trousseau macOS.
//
// Le refresh token EST la session : qui l'a peut en obtenir des jetons
// d'accès pendant 60 jours. Tant qu'il vivait en clair dans la base SQLite,
// il suffisait de lire un fichier — ou d'en copier un sur une autre machine —
// pour l'emporter. Dans le coffre, il est chiffré par le système avec le
// compte de l'utilisateur, et ne suit pas une copie du dossier du launcher.
//
// Ce que ça ne fait pas : un programme lancé par le même utilisateur peut
// toujours demander l'entrée au coffre. La protection porte sur la lecture du
// fichier, pas sur un logiciel malveillant qui tourne déjà dans la session.
//
// Tout est « au mieux » : sans coffre (Linux, coffre verrouillé ou refusé),
// les fonctions disent qu'elles n'ont rien fait et l'appelant garde le jeton
// dans la base, comme avant. Une session doit survivre à un coffre absent.

/// Nom sous lequel l'entrée apparaît dans le coffre du système.
#[cfg(all(any(target_os = "windows", target_os = "macos"), not(test)))]
const SERVICE: &str = "YuyuFrame";
#[cfg(all(any(target_os = "windows", target_os = "macos"), not(test)))]
const ENTRY: &str = "session";

/// La valeur rangée porte le compte : `<compte>:<jeton>`. Une entrée restée
/// d'un autre compte (base remplacée, changement de compte interrompu) n'est
/// alors jamais prise pour la session courante.
fn pack(user_id: i64, refresh_token: &str) -> String {
    format!("{user_id}:{refresh_token}")
}

fn unpack(user_id: i64, value: &str) -> Option<String> {
    let (owner, token) = value.split_once(':')?;
    (owner.parse::<i64>().ok()? == user_id && !token.is_empty()).then(|| token.to_string())
}

// Les tests ne touchent jamais au vrai coffre de la machine qui les lance :
// ils voient un système sans coffre, donc le repli sur la base.
#[cfg(all(any(target_os = "windows", target_os = "macos"), not(test)))]
mod system {
    use super::{ENTRY, SERVICE};

    pub fn set(value: &str) -> Result<(), String> {
        keyring::Entry::new(SERVICE, ENTRY).and_then(|e| e.set_password(value)).map_err(|e| e.to_string())
    }

    /// `Ok(None)` : le coffre répond, mais n'a pas d'entrée.
    pub fn get() -> Result<Option<String>, String> {
        match keyring::Entry::new(SERVICE, ENTRY).and_then(|e| e.get_password()) {
            Ok(value) => Ok(Some(value)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn delete() -> Result<(), String> {
        match keyring::Entry::new(SERVICE, ENTRY).and_then(|e| e.delete_credential()) {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
}

#[cfg(not(all(any(target_os = "windows", target_os = "macos"), not(test))))]
mod system {
    const NONE: &str = "pas de coffre sur ce système";

    pub fn set(_value: &str) -> Result<(), String> {
        Err(NONE.into())
    }

    pub fn get() -> Result<Option<String>, String> {
        Err(NONE.into())
    }

    pub fn delete() -> Result<(), String> {
        Ok(())
    }
}

/// Range le refresh token dans le coffre. `false` : rien n'a été rangé,
/// l'appelant doit garder le jeton ailleurs.
pub fn store(user_id: i64, refresh_token: &str) -> bool {
    match system::set(&pack(user_id, refresh_token)) {
        Ok(()) => true,
        Err(e) => {
            tracing::debug!("refresh token non rangé dans le coffre du système : {e}");
            false
        }
    }
}

/// Refresh token de ce compte, s'il est dans le coffre.
pub fn load(user_id: i64) -> Option<String> {
    match system::get() {
        Ok(value) => value.and_then(|v| unpack(user_id, &v)),
        Err(e) => {
            tracing::debug!("coffre du système illisible : {e}");
            None
        }
    }
}

/// Retire l'entrée (déconnexion, session perdue).
pub fn clear() {
    if let Err(e) = system::delete() {
        tracing::warn!("entrée du coffre du système non retirée : {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn la_valeur_rangee_porte_son_compte() {
        let packed = pack(42, "yfr_abc:def");
        assert_eq!(unpack(42, &packed).as_deref(), Some("yfr_abc:def"), "un « : » dans le jeton ne casse rien");
        assert_eq!(unpack(43, &packed), None, "entrée d'un autre compte");
        assert_eq!(unpack(42, "42:"), None, "jeton vide");
        assert_eq!(unpack(42, "n'importe quoi"), None);
    }

    #[test]
    fn sans_coffre_rien_n_est_range() {
        // En test, le système est vu sans coffre : c'est le repli qui est
        // exercé, jamais le coffre de la machine.
        assert!(!store(1, "yfr_x"));
        assert_eq!(load(1), None);
        clear();
    }
}
