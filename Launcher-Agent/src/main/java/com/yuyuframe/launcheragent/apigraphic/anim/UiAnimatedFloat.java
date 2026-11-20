package com.yuyuframe.launcheragent.apigraphic.anim;

/**
 * Valeur flottante qui converge en douceur vers une cible (hover, toggle,
 * scroll...) — approche exponentielle framerate-independent
 * (current += (target-current) * (1 - e^(-speed*dt))) plutôt qu'un lerp à pas
 * fixe par frame, pour rester cohérent quel que soit le framerate du jeu
 * (1.8.9 et 1.21 n'ont pas le même TPS de rendu).
 *
 * Chaque widget/état animé possède sa PROPRE instance (pas de clock globale
 * partagée) : {@link #get()} avance son horloge interne (System.nanoTime())
 * à chaque appel, donc à appeler UNE FOIS par frame par instance.
 */
public final class UiAnimatedFloat {

    private float current;
    private float target;
    private long lastNanos;
    private final float speed;
    /** Vitesse quand la valeur redescend (relâchement, fin de survol). */
    private final float speedDown;

    public UiAnimatedFloat(float initial, float speed) {
        this(initial, speed, speed);
    }

    /**
     * Vitesses différentes à la montée et à la descente : un appui doit
     * claquer, son relâchement se reposer.
     */
    public UiAnimatedFloat(float initial, float speedUp, float speedDown) {
        this.current = initial;
        this.target = initial;
        this.speed = speedUp;
        this.speedDown = speedDown;
        this.lastNanos = System.nanoTime();
    }

    public void setTarget(float target) {
        this.target = target;
    }

    /** Avance l'animation d'une frame et renvoie la valeur courante — à appeler une fois par frame. */
    public float get() {
        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        // Clampé : après une pause/lag, un dt énorme ferait sauter current
        // directement sur target au lieu d'animer — perçu comme un bug de
        // freeze plutôt qu'une transition.
        if (dt > 0.1f) dt = 0.1f;

        float diff = target - current;
        if (Math.abs(diff) < 0.001f) {
            current = target;
        } else {
            float k = diff > 0f ? speed : speedDown;
            current += diff * (1f - (float) Math.exp(-k * dt));
        }
        return current;
    }

    /**
     * Pose la valeur SANS animer (courant ET cible d'un coup) — pour
     * restaurer un état capturé avant une reconstruction (ex: la position de
     * défilement d'une liste reconstruite, voir {@code
     * UiScrollContainer#restoreScroll}).
     *
     * <p>{@link #setTarget} seul ne suffirait pas : il laisserait {@code
     * current} à sa valeur d'origine, donc l'élément partirait de zéro et
     * glisserait jusqu'à la valeur restaurée — exactement le mouvement
     * parasite qu'une restauration cherche à éviter.
     */
    public void snapTo(float value) {
        this.current = value;
        this.target = value;
        this.lastNanos = System.nanoTime();
    }

    public float target() { return target; }
}
