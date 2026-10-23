package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * L'éclairage d'un modèle : une lumière ambiante, des lumières directionnelles
 * fixes par rapport à l'écran, et des lumières ponctuelles attachées au modèle.
 *
 * <p>Calculé par sommet, sur le processeur ({@link UiMeshProjector}) : les
 * faces sont découpées assez fin pour qu'une lumière proche dessine une tache
 * et pas un aplat. Une face reçoit
 * {@code ambiante + somme(couleur × intensité × max(0, n·l) × atténuation)}.
 *
 * <ul>
 *   <li><b>Directionnelle</b> : direction dans le repère de l'écran (X à
 *       droite, Y en haut, Z vers l'œil), vers la lumière. La même pour toute
 *       la face ; elle ne bouge pas quand le modèle tourne — c'est le soleil
 *       de l'interface.</li>
 *   <li><b>Ponctuelle</b> : position dans le repère du modèle (pixels, au
 *       repos), elle tourne avec lui ; portée en pixels, au-delà elle
 *       n'éclaire plus. C'est la lueur au fond d'un coffre.</li>
 * </ul>
 *
 * <p>Une lumière plus forte que 1 <b>éclaircit</b> au-delà de la texture : le
 * dépassement est dessiné par-dessus en couleur de la lumière (voir
 * {@link UiMeshProjector}), une face peut donc briller.
 *
 * <p>Objet réutilisable : {@link #clear} puis on repose les lumières à chaque
 * image, pour animer leur intensité.
 */
public final class UiLighting {

    /** Une lumière. Les champs sont lus par le projecteur, posés par les méthodes de {@link UiLighting}. */
    public static final class Light {
        private final boolean point;
        private final float x, y, z;
        private final float r, g, b;
        private final float intensity;
        private final float range;

        private Light(boolean point, float x, float y, float z, float r, float g, float b, float intensity, float range) {
            this.point = point;
            if (point) {
                this.x = x; this.y = y; this.z = z;
            } else {
                float length = (float) Math.sqrt(x * x + y * y + z * z);
                if (length < 1e-6f) length = 1f;
                this.x = x / length; this.y = y / length; this.z = z / length;
            }
            this.r = r; this.g = g; this.b = b;
            this.intensity = intensity;
            this.range = range;
        }

        public boolean isPoint() { return point; }
        public float x() { return x; }
        public float y() { return y; }
        public float z() { return z; }
        public float r() { return r; }
        public float g() { return g; }
        public float b() { return b; }
        public float intensity() { return intensity; }
        public float range() { return range; }
    }

    private float ambientR = 1f, ambientG = 1f, ambientB = 1f;
    private final List<Light> lights = new ArrayList<>();

    /** Pas d'ombrage du tout : la texture telle quelle. */
    public static UiLighting flat() {
        return new UiLighting();
    }

    /**
     * L'éclairage par défaut des objets du jeu dans un menu : le dessus clair,
     * la face avant un peu moins, les côtés plus sombres — lumière venue d'en
     * haut, légèrement de face.
     */
    public static UiLighting gui() {
        return new UiLighting().ambient(0.42f, 0.42f, 0.44f)
            .directional(-0.35f, 1f, 0.6f, 1f, 0.98f, 0.94f, 0.62f);
    }

    /** Lumière ambiante, de 0 à 1 par canal. */
    public UiLighting ambient(float r, float g, float b) {
        ambientR = r; ambientG = g; ambientB = b;
        return this;
    }

    /** Lumière directionnelle, direction VERS la lumière dans le repère de l'écran. */
    public UiLighting directional(float dirX, float dirY, float dirZ, float r, float g, float b, float intensity) {
        lights.add(new Light(false, dirX, dirY, dirZ, r, g, b, intensity, 0f));
        return this;
    }

    /** Lumière ponctuelle, position dans le repère du modèle au repos, portée en pixels du modèle. */
    public UiLighting point(float x, float y, float z, float r, float g, float b, float intensity, float range) {
        if (intensity > 0f && range > 0f) lights.add(new Light(true, x, y, z, r, g, b, intensity, range));
        return this;
    }

    /** Retire toutes les lumières (l'ambiante reste). */
    public UiLighting clear() {
        lights.clear();
        return this;
    }

    public float ambientR() { return ambientR; }
    public float ambientG() { return ambientG; }
    public float ambientB() { return ambientB; }
    public List<Light> lights() { return lights; }
}
