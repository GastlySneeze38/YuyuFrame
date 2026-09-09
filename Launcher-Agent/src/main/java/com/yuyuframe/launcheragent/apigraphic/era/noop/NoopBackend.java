package com.yuyuframe.launcheragent.apigraphic.era.noop;

import com.yuyuframe.launcheragent.apigraphic.backend.UiBackend;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

/**
 * Le backend qui ne prend rien en charge — chaque méthode répond « pas mon
 * affaire », donc l'appelant garde son chemin.
 *
 * <h2>Deux rôles, un seul fichier</h2>
 *
 * <ul>
 *   <li><b>Aujourd'hui</b> : repli des ères {@code gl2}/{@code gl3}, dont le
 *       backend n'est pas encore écrit — leur code vit toujours dans les trois
 *       renderers de {@code render/}. Le moteur passe par le contrat, celui-ci
 *       décline, le chemin historique s'exécute. Comportement inchangé.</li>
 *   <li><b>Définitivement</b> : une version reconnue mais qu'aucun backend ne
 *       sert. Le jeu tourne, l'agent tourne, rien n'est dessiné — au lieu d'un
 *       {@code if (backendDisponible)} dispersé dans chaque primitive.</li>
 * </ul>
 *
 * <p>bgfx ({@code renderer_noop}), wgpu-hal ({@code noop/}) et Dear ImGui
 * ({@code imgui_impl_null}) ont tous les trois le leur. C'est le patron « objet
 * nul » : une capacité absente s'exprime par une implémentation vide, jamais
 * par un test chez l'appelant.
 */
public final class NoopBackend implements UiBackend {

    /** Public sans argument : instancié par réflexion depuis {@code UiBackendRegistry}. */
    public NoopBackend() {}

    @Override
    public String id() {
        return "noop";
    }

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                               UiColor color, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean text(com.yuyuframe.launcheragent.apigraphic.value.UiFont font, String content,
                        float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        return false;
    }

    // ── Primitives pas encore découpées ───────────────────────────────────
    // Elles déclinent : l'appelant retombe sur UiPrimitiveRenderer, où le code
    // de cette ère vit encore. Voir render/package-info.java.

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2,
                               float radiusBottomLeft, float radiusBottomRight,
                               float radiusTopLeft, float radiusTopRight,
                               UiColor color, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean roundedRectHud(float x1, float y1, float x2, float y2, float radius,
                                  UiColor color, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                        float alpha, int vpWidth, int vpHeight) {
        return false;
    }
}
