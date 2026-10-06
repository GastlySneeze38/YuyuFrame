use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use super::{download_file, get_latest_file, get_project, get_versions, search_modrinth};

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

    let result = search_modrinth(&query, &project_type, &categories, &version, &sort, 0, 24).unwrap_or_else(|e| {
        format!("{{\"error\":\"{}\"}}", e.replace('"', "'"))
    });

    env.new_string(result).unwrap_or_else(|_| {
        env.new_string("{\"error\":\"encodage résultat échoué\"}").unwrap()
    })
}

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_searchModrinthPage
///
/// Même recherche, paginée (`offset`/`limit`). Fonction À PART et non deux
/// paramètres de plus sur `searchModrinth` : un jar récent face à une DLL
/// ancienne (ou l'inverse) garde ainsi l'ancienne entrée intacte au lieu
/// d'appeler une fonction native dont la signature ne correspond plus.
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_searchModrinthPage<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
    project_type: JString<'local>,
    categories_csv: JString<'local>,
    version: JString<'local>,
    sort: JString<'local>,
    offset: jint,
    limit: jint,
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

    let result = search_modrinth(
        &query,
        &project_type,
        &categories,
        &version,
        &sort,
        offset.max(0) as u32,
        limit.max(1) as u32,
    )
    .unwrap_or_else(|e| format!("{{\"error\":\"{}\"}}", e.replace('"', "'")));

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

/// Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_getVersions
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_runtime_content_ContentBridge_getVersions<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    project_id: JString<'local>,
) -> JString<'local> {
    let project_id: String = env.get_string(&project_id).unwrap().into();

    let result = get_versions(&project_id).unwrap_or_else(|e| {
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
