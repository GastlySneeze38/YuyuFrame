pub mod deps;
pub mod fabric;
pub mod forge;
pub mod neoforge;
pub mod quilt;

use serde::Serialize;

/// Une version de loader proposée au choix, avec ce qu'il faut pour choisir.
///
/// Les quatre loaders ne filtrent pas de la même façon par version de
/// Minecraft, et c'est la raison d'être de ces deux drapeaux :
///
///   - **Forge** et **NeoForge** publient des builds *par version de MC* : la
///     liste ne contient donc, par construction, que des builds faits pour ce
///     jeu-là.
///   - **Fabric** et **Quilt** publient un loader *indépendant* du jeu — leur
///     meta rend la même liste pour 1.16.5 et pour 1.21.4 (vérifié : 253
///     entrées identiques). « Compatible » y veut dire « n'exclut pas cette
///     version », pas « a été testé avec elle » : un loader de 2019 démarrera
///     mal un jeu de 2025.
///
/// D'où `recommended`, qui est la seule réponse utile à « laquelle prendre »,
/// et `stable`, qui distingue une pré-version d'une version publiée.
#[derive(Serialize, Clone)]
pub struct LoaderVersion {
    pub version: String,
    /// Faux pour une pré-version (beta, rc) — elle reste choisissable, mais
    /// l'interface le dit.
    pub stable: bool,
    /// Celle que le loader lui-même désigne, et celle que le launcher
    /// installerait sans épinglage. Exactement une par liste, quand la liste
    /// n'est pas vide.
    pub recommended: bool,
}

impl LoaderVersion {
    pub fn new(version: impl Into<String>, stable: bool) -> Self {
        Self { version: version.into(), stable, recommended: false }
    }
}

/// Marque comme recommandée la première version qui correspond, à défaut la
/// première de la liste.
///
/// « À défaut » n'est pas de la décoration : une liste sans aucune version
/// stable existe (NeoForge sur un MC tout juste sorti n'a que des beta), et
/// laisser l'utilisateur sans aucun repère serait pire que de lui désigner la
/// plus récente.
pub fn mark_recommended(versions: &mut [LoaderVersion], wanted: Option<&str>) {
    let index = match wanted {
        Some(w) => versions.iter().position(|v| v.version == w),
        None => None,
    }
    .or_else(|| versions.iter().position(|v| v.stable))
    .or(if versions.is_empty() { None } else { Some(0) });

    if let Some(i) = index {
        versions[i].recommended = true;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list() -> Vec<LoaderVersion> {
        vec![
            LoaderVersion::new("3.0-beta", false),
            LoaderVersion::new("2.0", true),
            LoaderVersion::new("1.0", true),
        ]
    }

    #[test]
    fn la_version_demandee_gagne() {
        let mut v = list();
        mark_recommended(&mut v, Some("1.0"));
        assert!(v[2].recommended);
        assert_eq!(v.iter().filter(|x| x.recommended).count(), 1);
    }

    /// Sans version désignée par le loader, la plus récente *stable* — pas la
    /// plus récente tout court, qui peut être une beta.
    #[test]
    fn a_defaut_la_premiere_stable() {
        let mut v = list();
        mark_recommended(&mut v, None);
        assert!(v[1].recommended);
    }

    /// Une version demandée qui n'est pas dans la liste ne doit pas faire
    /// disparaître la recommandation : on retombe sur la première stable.
    #[test]
    fn une_demande_introuvable_retombe_sur_la_stable() {
        let mut v = list();
        mark_recommended(&mut v, Some("9.9"));
        assert!(v[1].recommended);
    }

    /// Tout en beta : on désigne quand même la plus récente, plutôt que de
    /// laisser l'utilisateur sans repère.
    #[test]
    fn sans_aucune_stable_la_plus_recente() {
        let mut v = vec![LoaderVersion::new("3.0-beta", false), LoaderVersion::new("2.0-beta", false)];
        mark_recommended(&mut v, None);
        assert!(v[0].recommended);
    }

    #[test]
    fn une_liste_vide_ne_panique_pas() {
        let mut v: Vec<LoaderVersion> = Vec::new();
        mark_recommended(&mut v, None);
        assert!(v.is_empty());
    }
}
