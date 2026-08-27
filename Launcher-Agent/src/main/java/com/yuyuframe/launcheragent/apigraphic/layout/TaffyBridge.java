package com.yuyuframe.launcheragent.apigraphic.layout;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;

/**
 * Pont JNI vers le moteur de layout Flexbox/Grid (roadmap Phase 5.2, crate
 * Rust {@code taffy} — même lib que Bevy UI/Dioxus/Zed). Fonction native
 * implémentée dans {@code content-core/src/jni/layout.rs} — MÊME DLL que
 * {@link ContentBridge} (aucune dépendance partagée entre les deux, juste le
 * même binaire chargé une fois, voir {@code content-core/src/lib.rs}), donc
 * {@link #ensureLoaded()} délègue simplement à {@code ContentBridge}.
 *
 * Sans état côté Rust : chaque appel reconstruit un arbre Taffy neuf depuis
 * {@code treeJson} (voir {@link TaffyNode#toJson()} pour le format) — pas de
 * handle à gérer/libérer côté Java. Volontairement PAS appelé à chaque frame
 * (voir ROADMAP-agent.md §5.2) : {@code computeLayout} n'est à invoquer que
 * sur un changement réel (resize, ajout/suppression de widget), l'appelant
 * doit mettre en cache le résultat ({@link TaffyLayoutResult}) entre-temps.
 */
public final class TaffyBridge {
    private TaffyBridge() {}

    public static boolean ensureLoaded() {
        return ContentBridge.ensureLoaded();
    }

    /**
     * @param treeJson JSON d'un {@link TaffyNode} racine (voir son
     *                 {@code toJson()}).
     * @param availW   largeur disponible en pixels, <= 0 = illimité (mesure
     *                 de contenu naturel).
     * @param availH   idem, hauteur.
     * @return tableau JSON plat {@code [{"id":"...","x":0,"y":0,"w":0,"h":0}, ...]}
     * en coordonnées ABSOLUES (déjà accumulées depuis la racine côté Rust),
     * ou {@code {"error":"..."}} en cas d'échec — voir {@link TaffyLayoutResult#parse}.
     */
    public static native String computeLayout(String treeJson, float availW, float availH);
}
