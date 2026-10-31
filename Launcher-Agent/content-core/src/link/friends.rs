//! Inviter un ami depuis le jeu : la liste des amis et l'invitation passent
//! par le launcher (`Launcher-Client/Backend/src/play/game_link.rs`, questions
//! `friends` et `invite`), qui parle au serveur avec le compte YuyuFrame — le
//! jeu n'a jamais besoin du jeton pour ça.
//!
//! L'invitation est la même que celle du launcher : le serveur la lit dans
//! notre présence (le serveur où l'on joue, annoncé par `activity.rs`), la
//! pousse dans le launcher de l'ami et, s'il a lié son compte Discord, la lui
//! écrit aussi en message privé, avec un bouton qui ouvre YuyuFrame sur la
//! bonne instance et la bonne adresse.
//!
//! Pas d'interface ici : Java dépose la question par `yf_friends_list` ou
//! `yf_friends_invite`, relève la réponse par `yf_link_poll` comme les autres,
//! et peut la lire avec le format décrit sur `parse_friends`.

use super::channel::link;

/// Un ami, tel que le launcher le décrit.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Friend {
    /// Identifiant public, à rendre à `yf_friends_invite`.
    pub id: String,
    pub name: String,
    /// `online`, `away`, `dnd` ou `offline`.
    pub state: String,
    pub in_game: bool,
}

/// Lit `friends⇥(<id>⇥<pseudo>⇥<état>⇥<en jeu 0/1>)…`. `None` : ce n'est pas
/// une liste d'amis (une erreur `error⇥…`, par exemple).
pub fn parse_friends(reply: &str) -> Option<Vec<Friend>> {
    let mut fields = reply.split('\t');
    if fields.next()? != "friends" {
        return None;
    }
    let rest: Vec<&str> = fields.collect();
    if rest.len() % 4 != 0 {
        return None;
    }
    Some(
        rest.chunks(4)
            .map(|c| Friend { id: c[0].to_string(), name: c[1].to_string(), state: c[2].to_string(), in_game: c[3] == "1" })
            .collect(),
    )
}

/// Un identifiant public d'ami (UUID), seul à pouvoir partir dans la question.
pub fn valid_id(id: &str) -> bool {
    id.len() == 36 && id.chars().all(|c| c.is_ascii_hexdigit() || c == '-')
}

/// La question d'invitation, ou rien si l'identifiant n'en est pas un.
pub fn invite_question(id: &str) -> Option<String> {
    valid_id(id).then(|| format!("invite\t{id}"))
}

/// Demande la liste des amis au launcher. Rend le numéro de la question, à
/// relever par `yf_link_poll` (réponse : voir `parse_friends`), ou 0 sans
/// launcher.
#[no_mangle]
pub extern "C" fn yf_friends_list() -> i64 {
    link().map(|l| l.send("friends") as i64).unwrap_or(0)
}

/// Invite l'ami `id` (identifiant public, 36 octets) sur le serveur où l'on
/// joue. Rend le numéro de la question (réponse `ok` ou `error⇥<message>`),
/// ou 0 sans launcher ou si l'identifiant est invalide.
///
/// # Safety
/// `id` doit pointer sur `len` octets lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_friends_invite(id: *const u8, len: i32) -> i64 {
    if id.is_null() || len <= 0 {
        return 0;
    }
    let Ok(id) = std::str::from_utf8(std::slice::from_raw_parts(id, len as usize)) else { return 0 };
    match (link(), invite_question(id)) {
        (Some(link), Some(question)) => link.send(&question) as i64,
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "0f8fad5b-d9cb-469f-a165-70867728950e";

    #[test]
    fn la_liste_se_lit_strictement() {
        let reply = format!("friends\t{ID}\tAlex\tonline\t1");
        assert_eq!(
            parse_friends(&reply),
            Some(vec![Friend { id: ID.into(), name: "Alex".into(), state: "online".into(), in_game: true }])
        );
        assert_eq!(parse_friends("friends"), Some(vec![]));
        assert_eq!(parse_friends("error\tpas de compte"), None);
        assert_eq!(parse_friends("friends\tincomplet"), None);
    }

    #[test]
    fn seul_un_identifiant_part_dans_l_invitation() {
        assert_eq!(invite_question(ID).as_deref(), Some(&*format!("invite\t{ID}")));
        assert_eq!(invite_question("abc\tswitch"), None);
        assert_eq!(invite_question(""), None);
    }

    #[test]
    fn hors_launcher_rien_ne_part() {
        assert_eq!(yf_friends_list(), 0);
        assert_eq!(unsafe { yf_friends_invite(ID.as_ptr(), ID.len() as i32) }, 0);
        assert_eq!(unsafe { yf_friends_invite(std::ptr::null(), 36) }, 0);
    }
}
