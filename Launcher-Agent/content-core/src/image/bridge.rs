use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;

use super::fetch_image_rgba;

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_fetchImageRgba
///
/// Retourne un jbyteArray : 8 premiers octets = largeur/hauteur (int32
/// BIG-ENDIAN chacun), puis largeur*hauteur*4 octets RGBA — voir
/// UiRemoteImage côté Java pour le parsing. Tableau VIDE (longueur 0) en cas
/// d'échec (réseau, décodage, URL non-HTTPS...) plutôt que null — null
/// nécessiterait une vérification JNI supplémentaire à chaque appel côté
/// Java, un tableau vide se distingue déjà trivialement (length < 8).
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_fetchImageRgba<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> JByteArray<'local> {
    let url: String = env.get_string(&url).unwrap().into();

    let payload: Vec<u8> = match fetch_image_rgba(&url) {
        Ok((w, h, pixels)) => {
            let mut out = Vec::with_capacity(8 + pixels.len());
            out.extend_from_slice(&w.to_be_bytes());
            out.extend_from_slice(&h.to_be_bytes());
            out.extend_from_slice(&pixels);
            out
        }
        Err(e) => {
            // Pas de LauncherLog côté Rust — Java verra un tableau vide et
            // journalisera lui-même l'échec avec plus de contexte (quelle
            // icône/URL, quel appelant) — ce eprintln reste utile pour un
            // diagnostic bas niveau (ex: crash avant même le retour à Java).
            eprintln!("[content-core] fetchImageRgba({url}) échoué : {e}");
            Vec::new()
        }
    };

    env.byte_array_from_slice(&payload)
        .unwrap_or_else(|_| env.new_byte_array(0).unwrap())
}
