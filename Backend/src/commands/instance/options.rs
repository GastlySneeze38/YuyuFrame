//! Lecture et écriture de `options.txt`, le fichier de réglages de Minecraft.
//!
//! Le format est une ligne par réglage, `clé:valeur`, sans section ni
//! échappement. La valeur peut elle-même contenir des `:` — c'est le cas de
//! `resourcePacks:["vanilla","file/truc.zip"]` — donc la découpe se fait au
//! **premier** deux-points et pas au dernier ni par `split`.
//!
//! ── Pourquoi on réécrit le fichier au lieu de le régénérer ────────────────
//! Minecraft y écrit des dizaines de clés, dont beaucoup dépendent de la
//! version et des mods installés (`key_key.sodium.*`, réglages d'Iris…).
//! Régénérer le fichier à partir de ce que l'interface sait afficher en
//! perdrait silencieusement la moitié — raccourcis clavier compris. On
//! conserve donc l'ordre et les lignes d'origine, et on ne remplace que la
//! valeur des clés effectivement modifiées.
//!
//! Le fichier n'existe qu'après un premier lancement du jeu : c'est Minecraft
//! qui le crée. Avant ça, la lecture rend une liste vide plutôt qu'une
//! erreur, et l'interface le dit.

use serde::{Deserialize, Serialize};

use super::crud::instance_dir;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct McOption {
    pub key: String,
    pub value: String,
}

fn options_path(instance_id: &str) -> std::path::PathBuf {
    instance_dir(instance_id).join("options.txt")
}

/// Découpe le contenu d'`options.txt` en réglages.
///
/// Les lignes vides et celles sans deux-points sont ignorées : ce ne sont pas
/// des réglages, et les garder obligerait tout le reste de la chaîne à savoir
/// quoi en faire.
fn parse(content: &str) -> Vec<McOption> {
    content
        .lines()
        .filter_map(|line| {
            let line = line.trim_end_matches('\r');
            let (key, value) = line.split_once(':')?;
            let key = key.trim();
            if key.is_empty() {
                return None;
            }
            Some(McOption { key: key.to_string(), value: value.to_string() })
        })
        .collect()
}

/// Réécrit le contenu en n'appliquant que les valeurs fournies.
///
/// Une clé absente du fichier est ajoutée à la fin : c'est le cas normal
/// quand Minecraft n'a pas encore écrit un réglage que l'interface propose.
/// Tout le reste — ordre, lignes inconnues, commentaires éventuels — revient
/// intact.
fn apply(content: &str, changes: &[McOption]) -> String {
    let mut remaining: Vec<&McOption> = changes.iter().collect();
    let mut out = String::with_capacity(content.len() + 64);

    for line in content.lines() {
        let line = line.trim_end_matches('\r');
        match line.split_once(':') {
            Some((key, _)) if remaining.iter().any(|c| c.key == key.trim()) => {
                let pos = remaining.iter().position(|c| c.key == key.trim()).unwrap();
                let change = remaining.remove(pos);
                out.push_str(&change.key);
                out.push(':');
                out.push_str(&change.value);
            }
            _ => out.push_str(line),
        }
        out.push('\n');
    }

    for change in remaining {
        out.push_str(&change.key);
        out.push(':');
        out.push_str(&change.value);
        out.push('\n');
    }
    out
}

#[tauri::command]
pub async fn mc_options_read(instance_id: String) -> Result<Vec<McOption>, String> {
    match tokio::fs::read_to_string(options_path(&instance_id)).await {
        Ok(content) => Ok(parse(&content)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(format!("Lecture de options.txt : {}", e)),
    }
}

/// Applique des réglages et rend le fichier relu.
///
/// Relu plutôt que « ce qu'on vient d'écrire » : l'interface affiche ainsi
/// exactement ce qui est sur le disque, y compris les clés qu'elle ne
/// connaissait pas encore.
#[tauri::command]
pub async fn mc_options_write(
    instance_id: String,
    changes: Vec<McOption>,
) -> Result<Vec<McOption>, String> {
    let path = options_path(&instance_id);
    let current = match tokio::fs::read_to_string(&path).await {
        Ok(content) => content,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(format!("Lecture de options.txt : {}", e)),
    };

    let next = apply(&current, &changes);
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::write(&path, next.as_bytes())
        .await
        .map_err(|e| format!("Écriture de options.txt : {}", e))?;

    Ok(parse(&next))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opt(key: &str, value: &str) -> McOption {
        McOption { key: key.into(), value: value.into() }
    }

    #[test]
    fn decoupe_au_premier_deux_points() {
        // `resourcePacks` contient des `:` dans sa valeur : découper ailleurs
        // qu'au premier casserait la liste des packs actifs.
        let parsed = parse("renderDistance:12\nresourcePacks:[\"vanilla\",\"file/a:b.zip\"]");
        assert_eq!(parsed, vec![
            opt("renderDistance", "12"),
            opt("resourcePacks", "[\"vanilla\",\"file/a:b.zip\"]"),
        ]);
    }

    #[test]
    fn ignore_ce_qui_n_est_pas_un_reglage() {
        let parsed = parse("\nrenderDistance:12\n\nligne sans deux points\n:valeur orpheline\n");
        assert_eq!(parsed, vec![opt("renderDistance", "12")]);
    }

    #[test]
    fn les_fins_de_ligne_windows_ne_polluent_pas_la_valeur() {
        assert_eq!(parse("fov:70\r\n"), vec![opt("fov", "70")]);
    }

    #[test]
    fn seules_les_cles_modifiees_changent() {
        let before = "fov:70\nrenderDistance:12\nkey_key.attack:key.mouse.left\n";
        let after = apply(before, &[opt("fov", "90")]);
        assert_eq!(after, "fov:90\nrenderDistance:12\nkey_key.attack:key.mouse.left\n");
    }

    #[test]
    fn une_cle_inconnue_du_fichier_est_ajoutee_a_la_fin() {
        let after = apply("fov:70\n", &[opt("gamma", "1.0")]);
        assert_eq!(after, "fov:70\ngamma:1.0\n");
    }

    #[test]
    fn les_lignes_inconnues_survivent() {
        // Tout ce que le launcher ne sait pas lire doit revenir intact —
        // c'est la raison d'être de cette réécriture ligne à ligne.
        let before = "ligne bizarre\nfov:70\n";
        assert_eq!(apply(before, &[opt("fov", "80")]), "ligne bizarre\nfov:80\n");
    }

    #[test]
    fn ecrire_dans_un_fichier_absent_cree_les_reglages() {
        assert_eq!(apply("", &[opt("fov", "70")]), "fov:70\n");
    }
}
