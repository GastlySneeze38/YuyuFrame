//! Les réglages du client dans un lien `yuyuframe://options/…` — la même sorte
//! que le launcher (`Backend/src/instances/settings/options_share.rs`), dont
//! on n'écrit que la moitié « client » : la section du jeu (`options.txt`)
//! reste vide, et celle d'un lien venu du launcher est ignorée ici.
//!
//! Texte transporté : lignes d'`options.txt` (`clé:valeur`), un séparateur
//! U+001E, puis celles du client (`clé=valeur`). Un lien fait dans le jeu se
//! colle donc aussi dans le launcher, et inversement.
//!
//! Les mêmes règles que le launcher, à l'envoi comme à la réception :
//! - `macros.setting.logins` (connexions automatiques, **mots de passe en
//!   clair**) ne part jamais et n'entre jamais ;
//! - `macros.setting.macros` non plus : une macro tape des commandes à la place
//!   du joueur, on n'en reçoit pas d'un lien venu de quelqu'un d'autre ;
//! - une valeur avec un retour à la ligne est refusée.
//!
//! Une règle de plus, propre au jeu : la langue de l'interface ne voyage pas.

use super::codec;

const KIND: &str = "options";
const SECTION_SEPARATOR: char = '\u{1E}';

/// Jamais dans un lien, dans aucun sens. La langue de l'interface en fait
/// partie : recevoir les réglages de quelqu'un ne doit pas changer la langue
/// dans laquelle on lit son propre jeu (le launcher l'écarte aussi de ses
/// propres liens de paramètres).
const EXCLUDED: &[&str] = &["macros.setting.logins", "macros.setting.macros", "ui-settings.setting.language"];
/// Au-delà, ce ne sont plus des réglages.
const MAX_ENTRIES: usize = 5_000;

fn keep(key: &str, value: &str) -> bool {
    !key.is_empty()
        && key.len() <= 200
        // Toutes les clés du client ont la forme `<module>.<…>`.
        && key.contains('.')
        && !key.contains(['\n', '\r', SECTION_SEPARATOR])
        && !value.contains(['\n', '\r', SECTION_SEPARATOR])
        && !EXCLUDED.contains(&key)
}

/// Les lignes `clé=valeur` qui ont le droit de voyager, dans l'ordre reçu.
fn kept_lines(lines: &str) -> Vec<String> {
    lines
        .lines()
        .filter_map(|line| line.split_once('='))
        .filter(|(key, value)| keep(key, value))
        .take(MAX_ENTRIES)
        .map(|(key, value)| format!("{key}={value}"))
        .collect()
}

/// Le lien (ou ses parties, une par ligne) des réglages donnés en `clé=valeur`.
pub fn link_of(settings: &str) -> Result<Vec<String>, String> {
    let kept = kept_lines(settings);
    if kept.is_empty() {
        return Err("Rien à partager".into());
    }
    codec::build_text(KIND, &format!("{SECTION_SEPARATOR}{}", kept.join("\n")))
}

/// Les réglages du client que porte un lien collé, en lignes `clé=valeur`.
pub fn settings_of(pasted: &str) -> Result<Vec<String>, String> {
    let text = codec::read_text(pasted, KIND)?;
    let (_game, client) = text.split_once(SECTION_SEPARATOR).ok_or("Lien de partage abîmé")?;
    let kept = kept_lines(client);
    if kept.is_empty() {
        return Err("Ce lien ne contient pas de réglages du client".into());
    }
    Ok(kept)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aller_retour_filtre() {
        let settings = "fps.enabled=true\nmacros.setting.logins=serveur|motdepasse\nmacros.setting.macros=F6|/op moi\n\
                        ui-settings.setting.language=3\nzoom.setting.zoomKey=C\nsans_point=x\ncoords.setting.label=a=b";
        let link = link_of(settings).unwrap().join("\n");
        assert_eq!(
            settings_of(&link).unwrap(),
            ["fps.enabled=true", "zoom.setting.zoomKey=C", "coords.setting.label=a=b"]
        );
    }

    /// Un lien fabriqué à la main avec des données interdites : elles sont
    /// écartées à la lecture, pas seulement à l'envoi.
    #[test]
    fn un_lien_etranger_est_filtre() {
        let text = format!("fov:0.5{SECTION_SEPARATOR}fps.enabled=true\nmacros.setting.logins=secret\nmacros.setting.macros=F6|/op moi");
        let link = codec::build_text(KIND, &text).unwrap().join("\n");
        assert_eq!(settings_of(&link).unwrap(), ["fps.enabled=true"]);
    }

    #[test]
    fn rien_a_partager_ou_a_lire() {
        assert!(link_of("macros.setting.logins=secret").is_err());
        let only_game = codec::build_text(KIND, &format!("fov:0.5{SECTION_SEPARATOR}")).unwrap().join("\n");
        assert!(settings_of(&only_game).is_err());
        assert!(settings_of("pas un lien").is_err());
    }
}
