//! Lecture et écriture des réglages du client intégré (LauncherAgent).
//!
//! L'agent persiste ses modules dans un `.properties` **par instance**, écrit
//! par `HudConfigStore` côté Java : `agent/module-config/<instance>.properties`.
//! Une ligne par réglage, `clé=valeur`, avec trois familles de clés :
//!
//! ```text
//! <module>.enabled=true|false          activation du module
//! <module>.favorite=true|false         épinglé sur l'écran en jeu
//! <module>.hud.anchor=TOP_LEFT         placement du HUD (posé à la souris en jeu)
//! <module>.setting.<réglage>=<valeur>  un réglage déclaré par le module
//! ```
//!
//! ── Pourquoi le launcher y touche ─────────────────────────────────────────
//! Ces réglages ne se changeaient qu'en jeu, écran de l'agent ouvert. Or on
//! décide souvent de ce qu'on veut *avant* de lancer — et une instance qu'on
//! ne lance plus reste réglable. Les mêmes clés, le même fichier : l'agent
//! relit à chaque démarrage, il n'y a pas deux vérités.
//!
//! On réécrit le fichier ligne à ligne, comme pour `options.txt` : l'agent y
//! écrit des clés que le launcher ne connaît pas (positions de HUD, réglages
//! de modules ajoutés depuis) et les régénérer reviendrait à les perdre.
//!
//! ── Ce n'est pas de l'UTF-8 ───────────────────────────────────────────────
//! `Properties.store(OutputStream)` écrit en **ISO-8859-1**, et échappe le
//! reste en `\uXXXX`. Lire ce fichier avec `read_to_string` échouait donc dès
//! qu'un octet dépassait 0x7F (« stream did not contain valid UTF-8 ») —
//! l'onglet entier refusait de s'ouvrir. On lit et on écrit donc octet par
//! octet dans cet encodage, avec les échappements de Java, sans quoi l'agent
//! relirait de travers ce que le launcher a écrit.
//!
//! Le fichier n'existe qu'après un premier lancement avec l'agent : avant ça
//! la lecture rend une liste vide, et l'interface affiche les valeurs par
//! défaut des modules. Écrire crée le fichier — l'agent le relira tel quel.

use crate::commands::instance::options::McOption;

/// Dossier des configurations de modules.
///
/// Suit la racine de données du launcher (`paths::root()`), comme le dépôt du
/// jar de l'agent (`agent_deploy::launcher_agent_dir`). Note : `HudConfigStore`
/// côté Java lit `%APPDATA%` directement, donc les deux ne désignent le même
/// dossier que tant que la racine n'a pas été déplacée depuis les réglages.
fn module_config_dir() -> std::path::PathBuf {
    crate::paths::root().join("agent").join("module-config")
}

/// Le nom du fichier est le dossier de l'instance, pas un identifiant à part :
/// l'agent n'a pas d'autre information, il prend le dernier segment de son
/// répertoire de travail (voir `HudConfigStore.computeConfigPath`).
fn config_path(instance_id: &str) -> std::path::PathBuf {
    module_config_dir().join(format!("{}.properties", instance_id))
}

/// ISO-8859-1 → texte : chaque octet est le point de code du même rang. Ne
/// peut pas échouer, contrairement à l'UTF-8 — c'est tout l'intérêt.
fn decode_latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|b| *b as char).collect()
}

/// Texte → ISO-8859-1. Tout ce qui sort de l'intervalle a déjà été échappé en
/// `\uXXXX` par `escape` : ce qui reste ici tient forcément sur un octet.
fn encode_latin1(text: &str) -> Vec<u8> {
    text.chars().map(|c| if (c as u32) <= 0xFF { c as u8 } else { b'?' }).collect()
}

/// Défait les échappements de `Properties` : `\uXXXX`, `\n`, `\t`… et
/// `\<n'importe quoi>`, qui vaut ce caractère (c'est ainsi que `=`, `:` et
/// `#` voyagent dans une valeur).
fn unescape(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some('f') => out.push('\u{0C}'),
            Some('u') => {
                let hex: String = chars.by_ref().take(4).collect();
                match u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                    Some(decoded) => out.push(decoded),
                    // Séquence tronquée ou invalide : on rend le texte tel
                    // quel plutôt que de l'escamoter.
                    None => { out.push_str("\\u"); out.push_str(&hex); }
                }
            }
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
}

/// Réapplique les échappements attendus par `Properties.load`.
///
/// Une clé échappe en plus les espaces et les séparateurs : sans ça, `Touche
/// du menu` deviendrait la clé `Touche` avec la valeur `du menu`.
fn escape(text: &str, is_key: bool) -> String {
    let mut out = String::with_capacity(text.len());
    for (i, c) in text.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '=' | ':' => { out.push('\\'); out.push(c) }
            // Dans une valeur, seul un `#`/`!` en tête ferait une ligne de
            // commentaire ; ailleurs il est ordinaire.
            '#' | '!' if is_key || i == 0 => { out.push('\\'); out.push(c) }
            ' ' if is_key || i == 0 => out.push_str("\\ "),
            c if (c as u32) < 0x20 || (c as u32) > 0x7E => {
                out.push_str(&format!("\\u{:04X}", c as u32))
            }
            c => out.push(c),
        }
    }
    out
}

/// Sépare une ligne en clé et valeur bruts (encore échappés).
///
/// `Properties` accepte `=`, `:` ou une simple espace comme séparateur, et le
/// premier des trois qui n'est pas échappé l'emporte.
fn split_line(line: &str) -> Option<(&str, &str)> {
    let mut escaped = false;
    for (i, c) in line.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        match c {
            '\\' => escaped = true,
            '=' | ':' => return Some((&line[..i], &line[i + 1..])),
            ' ' | '\t' => return Some((&line[..i], line[i + 1..].trim_start_matches(['=', ':', ' ', '\t']))),
            _ => {}
        }
    }
    None
}

/// Découpe un `.properties` en réglages, échappements défaits.
///
/// Les commentaires (`#`, `!`) et les lignes vides sont ignorés : ce ne sont
/// pas des réglages.
fn parse(content: &str) -> Vec<McOption> {
    content
        .lines()
        .filter_map(|line| {
            let trimmed = line.trim_end_matches('\r').trim_start();
            if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!') {
                return None;
            }
            let (key, value) = split_line(trimmed)?;
            let key = unescape(key.trim());
            if key.is_empty() {
                return None;
            }
            Some(McOption { key, value: unescape(value.trim_start()) })
        })
        .collect()
}

/// Réécrit le contenu en n'appliquant que les valeurs fournies. Une clé
/// absente est ajoutée à la fin ; tout le reste revient intact.
fn apply(content: &str, changes: &[McOption]) -> String {
    let mut remaining: Vec<&McOption> = changes.iter().collect();
    let mut out = String::with_capacity(content.len() + 64);

    for line in content.lines() {
        let line = line.trim_end_matches('\r');
        let trimmed = line.trim_start();
        let key = if trimmed.starts_with('#') || trimmed.starts_with('!') {
            None
        } else {
            split_line(trimmed).map(|(k, _)| unescape(k.trim()))
        };
        match key.and_then(|k| remaining.iter().position(|c| c.key == k)) {
            Some(pos) => {
                let change = remaining.remove(pos);
                out.push_str(&line_for(change));
            }
            None => out.push_str(line),
        }
        out.push('\n');
    }

    for change in remaining {
        out.push_str(&line_for(change));
        out.push('\n');
    }
    out
}

fn line_for(change: &McOption) -> String {
    format!("{}={}", escape(&change.key, true), escape(&change.value, false))
}

#[tauri::command]
pub async fn agent_options_read(instance_id: String) -> Result<Vec<McOption>, String> {
    match tokio::fs::read(config_path(&instance_id)).await {
        Ok(bytes) => Ok(parse(&decode_latin1(&bytes))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(format!("Lecture des réglages du client intégré : {}", e)),
    }
}

/// Applique des réglages et rend le fichier relu — même contrat que
/// `mc_options_write` : l'interface affiche ce qui est sur le disque, pas ce
/// qu'elle croit avoir écrit.
#[tauri::command]
pub async fn agent_options_write(
    instance_id: String,
    changes: Vec<McOption>,
) -> Result<Vec<McOption>, String> {
    let path = config_path(&instance_id);
    let current = match tokio::fs::read(&path).await {
        Ok(bytes) => decode_latin1(&bytes),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(format!("Lecture des réglages du client intégré : {}", e)),
    };

    let next = apply(&current, &changes);
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::write(&path, encode_latin1(&next))
        .await
        .map_err(|e| format!("Écriture des réglages du client intégré : {}", e))?;

    Ok(parse(&next))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opt(key: &str, value: &str) -> McOption {
        McOption { key: key.into(), value: value.into() }
    }

    #[test]
    fn lit_les_trois_familles_de_cles() {
        let parsed = parse("fps.enabled=true\nfps.hud.anchor=TOP_LEFT\nfps.setting.couleur=#FFFFFF\n");
        assert_eq!(parsed, vec![
            opt("fps.enabled", "true"),
            opt("fps.hud.anchor", "TOP_LEFT"),
            opt("fps.setting.couleur", "#FFFFFF"),
        ]);
    }

    #[test]
    fn ignore_commentaires_et_lignes_vides() {
        // Properties écrit un en-tête daté en commentaire : le prendre pour
        // un réglage remplirait la vue avancée d'une ligne fantôme.
        let parsed = parse("#Wed Sep 24 12:00:00 CEST 2026\n\nfps.enabled=true\n!autre commentaire\n");
        assert_eq!(parsed, vec![opt("fps.enabled", "true")]);
    }

    #[test]
    fn la_valeur_garde_ses_egals() {
        // Une macro est une commande brute, elle peut contenir n'importe quoi.
        // Java échappe le `=` dans la valeur : on doit le rendre au texte.
        assert_eq!(
            parse("macros.setting.cmd=/msg a b\\=c\n"),
            vec![opt("macros.setting.cmd", "/msg a b=c")],
        );
    }

    #[test]
    fn les_accents_passent_par_les_echappements_java() {
        // `Properties.store` n'écrit jamais d'UTF-8 : un accent part en
        // \uXXXX, et doit revenir tel qu'il a été tapé.
        assert_eq!(
            parse("macros.setting.cmd=/dire r\\u00E9ponse\n"),
            vec![opt("macros.setting.cmd", "/dire réponse")],
        );
        assert_eq!(
            apply("", &[opt("macros.setting.cmd", "/dire réponse")]),
            "macros.setting.cmd=/dire r\\u00E9ponse\n",
        );
    }

    #[test]
    fn un_fichier_en_latin1_se_lit_quand_meme() {
        // Le cas qui faisait échouer l'onglet entier : un octet > 0x7F, donc
        // pas de l'UTF-8. En ISO-8859-1 il n'y a rien d'ambigu.
        let bytes = [b'a', b'=', 0xE9, b'\n'];
        assert_eq!(parse(&decode_latin1(&bytes)), vec![opt("a", "é")]);
    }

    #[test]
    fn les_deux_points_separent_aussi() {
        // Format accepté par Properties.load, donc possible dans un fichier
        // édité à la main.
        assert_eq!(parse("fps.enabled:true\n"), vec![opt("fps.enabled", "true")]);
    }

    #[test]
    fn seules_les_cles_modifiees_changent() {
        let before = "fps.enabled=true\ncoords.enabled=true\nfps.hud.anchor=TOP_LEFT\n";
        let after = apply(before, &[opt("coords.enabled", "false")]);
        assert_eq!(after, "fps.enabled=true\ncoords.enabled=false\nfps.hud.anchor=TOP_LEFT\n");
    }

    #[test]
    fn les_commentaires_et_les_cles_inconnues_survivent() {
        // Tout ce que le launcher ne sait pas lire — placements de HUD,
        // modules plus récents que lui — doit revenir intact.
        let before = "#en-tête\nzoom.setting.niveau=4.0\nfps.enabled=false\n";
        let after = apply(before, &[opt("fps.enabled", "true")]);
        assert_eq!(after, "#en-tête\nzoom.setting.niveau=4.0\nfps.enabled=true\n");
    }

    #[test]
    fn ecrire_dans_un_fichier_absent_cree_les_reglages() {
        assert_eq!(apply("", &[opt("zoom.enabled", "true")]), "zoom.enabled=true\n");
    }
}
