package com.yuyuframe.launcheragent.apigraphic.anim;

/**
 * Progression d'entrée/sortie (0=caché, 1=visible) pilotée par une DURÉE FIXE
 * — contrairement à {@link UiAnimatedFloat} (convergence exponentielle, jamais
 * "vraiment" terminée), utile pour une transition d'écran/de carte où on veut
 * une durée prévisible ET un état "terminé" détectable ({@link #isFinished()}).
 *
 * Optionnellement retardée ({@code delaySeconds}, fixé à la construction) —
 * pour un effet cascade (stagger) sur une liste d'éléments, voir
 * {@link UiStagger} pour calculer ce délai par index.
 *
 * Une seule instance par élément animé (carte, panneau, écran...), comme
 * {@link UiAnimatedFloat} — {@link #progress()}/{@link #eased()} avancent
 * l'horloge interne, à appeler UNE FOIS par frame.
 */
public final class UiTransition {

    private final float durationSeconds;
    private final float delaySeconds;
    private final UiEasing.Curve curve;

    private boolean target;
    private float progress; // 0..1, valeur BRUTE (avant courbe d'accélération)
    private float sinceTargetChanged;
    private long lastNanos = -1;

    public UiTransition(float durationSeconds, float delaySeconds, UiEasing.Curve curve) {
        this.durationSeconds = Math.max(0.001f, durationSeconds);
        this.delaySeconds = Math.max(0f, delaySeconds);
        this.curve = curve;
        // Pas de délai avant le tout premier show()/hide() — le délai ne
        // s'applique qu'à un changement de cible ultérieur (voir setTarget).
        this.sinceTargetChanged = this.delaySeconds;
    }

    public UiTransition(float durationSeconds) {
        this(durationSeconds, 0f, UiEasing.EASE_OUT_CUBIC);
    }

    public void show() { setTarget(true); }
    public void hide() { setTarget(false); }

    public void setTarget(boolean visible) {
        if (this.target == visible) return;
        this.target = visible;
        this.sinceTargetChanged = 0f;
    }

    /**
     * Repart de 0 puis vise "visible" — CONTRAIREMENT à {@link #show()},
     * fonctionne même si {@code target} est déjà à {@code true} (show()/
     * setTarget() ne font rien dans ce cas, voir leur garde {@code if
     * (this.target == visible) return;}). Utile pour rejouer l'animation sur
     * un widget/écran déjà entièrement affiché depuis longtemps (ex: un Screen
     * réutilisé — pas reconstruit — qui redevient l'écran actif, voir
     * UiScreenBase.lastActiveScreen).
     */
    public void replay() {
        this.progress = 0f;
        this.target = true;
        this.sinceTargetChanged = delaySeconds; // pas de délai au replay, voir le constructeur
    }

    /**
     * Place la transition DIRECTEMENT dans son état final "visible", sans
     * l'animer — symétrique de {@link UiAnimatedFloat#snapTo}.
     *
     * <p>Sert à marquer une transition comme DÉJÀ JOUÉE pour un élément qui ne
     * doit pas l'exécuter. Attention au piège que ce cas révèle : ne PAS
     * appeler {@link #eased()} ne suspend pas la transition, ça la laisse à
     * {@code progress = 0} — donc au premier appel ultérieur elle démarre
     * intégralement, avec un retard arbitraire. Sauter une animation en
     * ignorant sa valeur ne la neutralise pas ; il faut la terminer
     * explicitement.
     */
    public void snapToEnd() {
        this.target = true;
        this.progress = 1f;
        this.sinceTargetChanged = delaySeconds;
    }

    /**
     * À appeler une fois par frame (avance l'horloge interne). Renvoie la
     * progression BRUTE 0..1 (linéaire) — préférer {@link #eased()} pour tout
     * usage visuel (opacité, échelle, décalage).
     */
    public float progress() {
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0f : (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        // Anti-saut après un freeze/lag — même garde-fou que UiAnimatedFloat.
        if (dt > 0.1f) dt = 0.1f;

        sinceTargetChanged += dt;
        if (sinceTargetChanged > delaySeconds) {
            float step = dt / durationSeconds;
            progress = target ? Math.min(1f, progress + step) : Math.max(0f, progress - step);
        }
        return progress;
    }

    /** Progression passée dans la courbe d'accélération — À UTILISER pour tout rendu (peut dépasser 1.0 avec {@link UiEasing#EASE_OUT_BACK}, c'est voulu). */
    public float eased() {
        return curve.apply(progress());
    }

    /** {@code true} une fois la transition arrivée à son état cible (1.0 si visible, 0.0 sinon) — pratique pour arrêter de dessiner/mettre à jour un élément totalement caché. */
    public boolean isFinished() {
        return target ? progress >= 1f : progress <= 0f;
    }

    public boolean isVisible() { return target; }
}
