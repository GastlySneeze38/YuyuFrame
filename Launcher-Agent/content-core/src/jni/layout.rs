use jni::objects::{JClass, JString};
use jni::JNIEnv;

use crate::compute_layout_json;

/// Java_com_yuyuframe_launcheragent_apigraphic_layout_TaffyBridge_computeLayout
///
/// Voir `content-core/src/layout.rs` pour le schéma JSON attendu/retourné.
/// `availW`/`availH` <= 0 = contenu illimité (mesure de hauteur naturelle).
#[no_mangle]
pub extern "system" fn Java_com_yuyuframe_launcheragent_apigraphic_layout_TaffyBridge_computeLayout<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tree_json: JString<'local>,
    avail_w: jni::sys::jfloat,
    avail_h: jni::sys::jfloat,
) -> JString<'local> {
    let tree_json: String = match env.get_string(&tree_json) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("{\"error\":\"treeJson invalide (JNI)\"}").unwrap(),
    };

    let result = compute_layout_json(&tree_json, avail_w, avail_h);

    env.new_string(result).unwrap_or_else(|_| {
        env.new_string("{\"error\":\"encodage résultat échoué\"}").unwrap()
    })
}
