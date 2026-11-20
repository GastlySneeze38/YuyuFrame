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
 * <p><b>Entrée et sortie ont chacune leur courbe</b> (2026-11-20). Avant, la
 * sortie rejouait la courbe d'entrée à l'envers : une courbe qui part vite à
 * l'entrée part LENTEMENT à la sortie, donc tout ce qui se fermait traînait,
 * et un {@link UiEasing#BACK_OUT} faisait grossir l'élément avant qu'il ne
 * disparaisse. La sortie suit maintenant sa propre courbe, à l'endroit.
 *
 * <p><b>Interruptible</b> : un changement de cible repart de la valeur
 * AFFICHÉE à cet instant, sur une durée proportionnelle au chemin restant —
 * jamais de saut quand on referme un panneau à moitié ouvert.
 *
 * Une seule instance par élément animé (carte, panneau, écran...), comme
 * {@link UiAnimatedFloat} — {@link #progress()}/{@link #eased()} avancent
 * l'horloge interne, à appeler UNE FOIS par frame.
 */
public final class UiTransition {

    private final float durationSeconds;
    private final float delaySeconds;
    private final UiEasing.Curve curve;
    private final UiEasing.Curve exitCurve;

    private boolean target;
    /** Valeur affichée au dernier changement de cible : point de départ du trajet en cours. */
    private float from;
    /** Avancement du trajet en cours, 0..1 (linéaire). */
    private float phase = 1f;
    private float segmentSeconds;
    private float sinceTargetChanged;
    private long lastNanos = -1;
    /** Dernières valeurs calculées : brute (linéaire) et passée dans la courbe. */
    private float raw;
    private float value;

    public UiTransition(float durationSeconds, float delaySeconds, UiEasing.Curve curve, UiEasing.Curve exitCurve) {
        this.durationSeconds = Math.max(0.001f, durationSeconds);
        this.delaySeconds = Math.max(0f, delaySeconds);
        this.curve = curve;
        this.exitCurve = exitCurve;
        this.segmentSeconds = this.durationSeconds;
        // Pas de délai avant le tout premier show()/hide() — le délai ne
        // s'applique qu'à un changement de cible ultérieur (voir setTarget).
        this.sinceTargetChanged = this.delaySeconds;
    }

    /** Sortie sur la courbe commune de l'interface ({@link UiEasing#UI_OUT}). */
    public UiTransition(float durationSeconds, float delaySeconds, UiEasing.Curve curve) {
        this(durationSeconds, delaySeconds, curve, UiEasing.UI_OUT);
    }

    public UiTransition(float durationSeconds) {
        this(durationSeconds, 0f, UiEasing.UI_OUT);
    }

    public void show() { setTarget(true); }
    public void hide() { setTarget(false); }

    public void setTarget(boolean visible) {
        if (this.target == visible) return;
        this.target = visible;
        this.from = clamp01(value);
        this.phase = 0f;
        float to = visible ? 1f : 0f;
        // Un trajet à moitié fait prend moitié moins de temps, au même rythme.
        this.segmentSeconds = durationSeconds * Math.max(0.2f, Math.abs(to - from));
        this.sinceTargetChanged = 0f;
    }

    /**
     * Repart de 0 puis vise "visible" — CONTRAIREMENT à {@link #show()},
     * fonctionne même si {@code target} est déjà à {@code true} (show()/
     * setTarget() ne font rien dans ce cas). Utile pour rejouer l'animation
     * sur un widget déjà entièrement affiché (éclat de clic, écran réutilisé).
     */
    public void replay() {
        this.target = true;
        this.from = 0f;
        this.phase = 0f;
        this.segmentSeconds = durationSeconds;
        this.raw = 0f;
        this.value = 0f;
        this.sinceTargetChanged = delaySeconds; // pas de délai au replay, voir le constructeur
    }

    /**
     * Place la transition DIRECTEMENT dans son état final "visible", sans
     * l'animer — symétrique de {@link UiAnimatedFloat#snapTo}.
     *
     * <p>Sert à marquer une transition comme DÉJÀ JOUÉE pour un élément qui ne
     * doit pas l'exécuter. Ne PAS appeler {@link #eased()} ne suspend pas la
     * transition : au premier appel ultérieur elle démarrerait intégralement,
     * avec un retard arbitraire. Il faut la terminer explicitement.
     */
    public void snapToEnd() {
        this.target = true;
        this.from = 1f;
        this.phase = 1f;
        this.raw = 1f;
        this.value = 1f;
        this.sinceTargetChanged = delaySeconds;
    }

    private void advance() {
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0f : (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        // Anti-saut après un freeze/lag — même garde-fou que UiAnimatedFloat.
        if (dt > 0.1f) dt = 0.1f;

        sinceTargetChanged += dt;
        if (sinceTargetChanged > delaySeconds && phase < 1f) {
            phase = Math.min(1f, phase + dt / segmentSeconds);
        }
        float to = target ? 1f : 0f;
        raw = from + (to - from) * phase;
        value = from + (to - from) * (target ? curve : exitCurve).apply(phase);
    }

    /**
     * À appeler une fois par frame (avance l'horloge interne). Renvoie la
     * progression BRUTE 0..1 (linéaire) — préférer {@link #eased()} pour tout
     * usage visuel (opacité, échelle, décalage).
     */
    public float progress() {
        advance();
        return raw;
    }

    /** Progression passée dans la courbe d'entrée ou de sortie — À UTILISER pour tout rendu (peut dépasser 1.0 avec {@link UiEasing#BACK_OUT}, c'est voulu). */
    public float eased() {
        advance();
        return value;
    }

    /** {@code true} une fois la transition arrivée à son état cible (1.0 si visible, 0.0 sinon) — pratique pour arrêter de dessiner/mettre à jour un élément totalement caché. */
    public boolean isFinished() {
        return phase >= 1f;
    }

    public boolean isVisible() { return target; }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
