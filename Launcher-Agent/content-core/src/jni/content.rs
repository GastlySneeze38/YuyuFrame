use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::{download_file, fetch_image_rgba, get_latest_file, get_project, search_modrinth};

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_searchModrinth
///
/// `categories` : liste jointe par des virgules côté Java (voir
/// ContentBridge.searchModrinth) — évite de marshaler un `String[]` via JNI
/// pour un besoin aussi simple ; splittée ici. `version`/`sort` vides =
/// aucun filtre/tri par défaut (voir search_modrinth).
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_searchModrinth<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
    project_type: JString<'local>,
    categories_csv: JString<'local>,
    version: JString<'local>,
    sort: JString<'local>,
) -> JString<'local> {
    let query: String = env.get_string(&query).unwrap().into();
    let project_type: String = env.get_string(&project_type).unwrap().into();
    let categories_csv: String = env.get_string(&categories_csv).unwrap().into();
    let version: String = env.get_string(&version).unwrap().into();
    let sort: String = env.get_string(&sort).unwrap().into();
    let categories: Vec<String> = categories_csv
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();

    let result = search_modrinth(&query, &project_type, &categories, &version, &sort).unwrap_or_else(|e| {
        format!("{{\"error\":\"{}\"}}", e.replace('"', "'"))
    });

    env.new_string(result).unwrap_or_else(|_| {
        env.new_string("{\"error\":\"encodage résultat échoué\"}").unwrap()
    })
}

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_getLatestFile
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_getLatestFile<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    project_id: JString<'local>,
) -> JString<'local> {
    let project_id: String = env.get_string(&project_id).unwrap().into();

    let result = get_latest_file(&project_id).unwrap_or_else(|e| {
        format!("{{\"error\":\"{}\"}}", e.replace('"', "'"))
    });

    env.new_string(result).unwrap_or_else(|_| {
        env.new_string("{\"error\":\"encodage résultat échoué\"}").unwrap()
    })
}

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_getProject
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_getProject<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    project_id: JString<'local>,
) -> JString<'local> {
    let project_id: String = env.get_string(&project_id).unwrap().into();

    let result = get_project(&project_id).unwrap_or_else(|e| {
        format!("{{\"error\":\"{}\"}}", e.replace('"', "'"))
    });

    env.new_string(result).unwrap_or_else(|_| {
        env.new_string("{\"error\":\"encodage résultat échoué\"}").unwrap()
    })
}

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_downloadFile
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_downloadFile<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    dest_path: JString<'local>,
) -> jboolean {
    let url: String = env.get_string(&url).unwrap().into();
    let dest_path: String = env.get_string(&dest_path).unwrap().into();

    match download_file(&url, &dest_path) {
        Ok(()) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

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
