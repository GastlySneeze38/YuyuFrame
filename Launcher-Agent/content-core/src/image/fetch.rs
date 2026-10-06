use std::io::Read;

/// Charge une image DEPUIS N'IMPORTE QUELLE URL HTTPS (pas restreinte à
/// cdn.modrinth.com, contrairement à `modrinth::download_file` — voir
/// UiRemoteImage côté Java, JAMAIS un fichier réellement installé sur le
/// disque du joueur, juste un visuel affiché en mémoire, donc un risque bien
/// moindre à élargir) et la décode en pixels RGBA bruts — AUCUNE écriture
/// disque, tout en mémoire du début à la fin. Le crate `image` détecte le
/// format automatiquement (PNG/JPEG/GIF/WebP/BMP/ICO...) — remplace ImageIO
/// (Java), qui ne décode PAS WebP nativement, sans ajouter de dépendance
/// Java tierce à gérer à la main (le projet n'a pas de Maven/Gradle).
///
/// Retourne (largeur, hauteur, pixels RGBA8 bruts, 4 octets/pixel, ligne par
/// ligne depuis le haut) — format volontairement minimal (pas de PNG/JPEG
/// ré-encodé en retour) : Java construit directement un BufferedImage depuis
/// ces octets, sans repasser par un second décodeur côté JVM.
pub fn fetch_image_rgba(url: &str) -> Result<(u32, u32, Vec<u8>), String> {
    if !url.starts_with("https://") {
        return Err("seules les URLs HTTPS sont autorisées".to_string());
    }

    let response = ureq::get(url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("requête image échouée : {e}"))?;

    let mut bytes = Vec::new();
    // Garde-fou contre une réponse énorme (image piégée/serveur compromis) —
    // 20 Mo est largement au-delà de la taille d'une icône réelle, jamais
    // atteint en usage normal.
    response
        .into_reader()
        .take(20 * 1024 * 1024)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("lecture image échouée : {e}"))?;

    // `::image` : le crate, pas notre module du même nom.
    let img = ::image::load_from_memory(&bytes)
        .map_err(|e| format!("décodage image échoué : {e}"))?
        .to_rgba8();

    let (w, h) = img.dimensions();
    Ok((w, h, img.into_raw()))
}
