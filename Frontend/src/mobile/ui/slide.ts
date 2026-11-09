/**
 * La position d'un panneau qui glisse (menu, feuille, écran poussé) et
 * l'opacité de son fond, tenues ensemble.
 *
 * Les trajets (ouvrir, fermer, revenir en place) passent par
 * `element.animate` : ils tournent sur la carte graphique, même quand la page
 * occupe le fil principal (le montage de ce qu'on vient d'ouvrir, en
 * général). Pendant un glissé, la position est écrite directement.
 *
 * Tout trajet s'interrompt : `stop()` lit où en est vraiment le panneau
 * (matrice calculée, animation comprise), annule l'animation et le pose là.
 * C'est ce qui permet d'attraper un panneau en pleine ouverture.
 */

/** Courbe des tiroirs d'iOS (Ionic) : départ franc, arrivée douce. */
export const DRAWER_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)'

/** Animations réduites (système ou réglage Apparence) : trajets instantanés. */
export function motionReduced(): boolean {
  return document.documentElement.dataset.motion === 'reduced' || window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export class Slide {
  private anims: Animation[] = []

  constructor(
    private panel: HTMLElement,
    private axis: 'x' | 'y',
    private backdrop: HTMLElement | null = null,
    /** Opacité du fond pour une position donnée. */
    private opacityAt: (v: number) => number = () => 1,
  ) {}

  private transform(v: number): string {
    return this.axis === 'x' ? `translate3d(${v}px,0,0)` : `translate3d(0,${v}px,0)`
  }

  /** Où est le panneau à cet instant, animation en cours comprise. */
  position(): number {
    const t = getComputedStyle(this.panel).transform
    if (!t || t === 'none') return 0
    const m = new DOMMatrixReadOnly(t)
    return this.axis === 'x' ? m.m41 : m.m42
  }

  /**
   * Pose le panneau (et son fond) à `v`, sans animation. En place, aucune
   * transformation : elle ferait du panneau le repère des éléments `fixed`
   * qu'il contient.
   */
  apply(v: number): void {
    this.panel.style.transform = Math.abs(v) < 0.5 ? '' : this.transform(v)
    if (this.backdrop) this.backdrop.style.opacity = String(Math.max(0, Math.min(1, this.opacityAt(v))))
  }

  /** Arrête le trajet en cours là où il en est ; rend cette position. */
  stop(): number {
    const v = this.position()
    for (const a of this.anims) a.cancel()
    this.anims = []
    this.apply(v)
    return v
  }

  /**
   * Va à `v`. Rend `true` si le trajet est allé au bout, `false` s'il a été
   * interrompu (un geste, un autre trajet) — on ne démonte alors rien.
   */
  to(v: number, duration: number, easing = DRAWER_EASING): Promise<boolean> {
    const from = this.stop()
    const ms = motionReduced() ? 0 : duration
    if (Math.abs(from - v) < 0.5 || ms <= 0) {
      this.apply(v)
      return Promise.resolve(true)
    }
    // La position d'arrivée est posée tout de suite : l'animation joue
    // par-dessus, et quand elle se termine le style est déjà le bon.
    const fromOpacity = this.backdrop ? Number(this.backdrop.style.opacity || 1) : 0
    this.apply(v)
    const opts: KeyframeAnimationOptions = { duration: ms, easing }
    const main = this.panel.animate([{ transform: this.transform(from) }, { transform: this.transform(v) }], opts)
    this.anims = [main]
    if (this.backdrop) {
      const toOpacity = Number(this.backdrop.style.opacity)
      this.anims.push(this.backdrop.animate([{ opacity: fromOpacity }, { opacity: toOpacity }], opts))
    }
    return main.finished.then(
      () => {
        this.anims = this.anims.filter((a) => a !== main && a.playState !== 'finished')
        return true
      },
      () => false,
    )
  }
}

/**
 * Durée d'un trajet selon la distance qui reste : un panneau déjà presque
 * fermé ne met pas 200 ms à finir. Bornée pour rester perceptible.
 */
export function remaining(from: number, to: number, size: number, full: number, min = 120): number {
  if (size <= 0) return full
  return Math.max(min, Math.round(full * Math.min(1, Math.abs(to - from) / size)))
}
