import { useSyncExternalStore } from 'react'

/**
 * L'écran de démarrage du téléphone (`#yuyu-splash`, écrit dans `index.html`
 * pour s'afficher avant même que ce code soit chargé).
 *
 * Trois temps :
 *   1. `splash`    Yuyu boucle, l'application charge. La page n'est pas montée.
 *   2. `mounting`  L'application est prête (`finishSplash`) : la page monte
 *                  sous Yuyu, qui continue de boucler. Monter coûte de quelques
 *                  centaines de millisecondes à une seconde sur téléphone ; le
 *                  faire pendant le fondu laissait un écran noir, et figer Yuyu
 *                  pendant ce temps le laissait immobile.
 *   3. `revealed`  La page est dessinée : Yuyu joue sa fin (yeux rieurs,
 *                  crochets qui éclatent) en partant de la pose où il en est,
 *                  s'efface, et la page fait son entrée en même temps
 *                  (`PageTransition`, `hold`).
 *
 * Sur PC il n'est pas affiché (`display: none` au-delà de 640 px) : il est
 * retiré tout de suite et l'on part du troisième temps.
 */

type Phase = 'splash' | 'mounting' | 'revealed'

/** Durée de la fin avant le fondu (yeux rieurs, saut, crochets qui partent). */
const FINALE_MS = 180
/** Fondu de l'écran, le même que `#yuyu-splash.out` dans index.html. */
const FADE_MS = 260

const splash = typeof document === 'undefined' ? null : document.getElementById('yuyu-splash')
let phase: Phase = !splash || getComputedStyle(splash).display === 'none' ? 'revealed' : 'splash'
if (phase === 'revealed') splash?.remove()

const listeners = new Set<() => void>()

function setPhase(next: Phase) {
  if (phase === next) return
  phase = next
  listeners.forEach((l) => l())
}

/** Vrai tant que l'application n'a pas dit qu'elle était prête. */
export function splashActive(): boolean {
  return phase === 'splash'
}

function useSplashPhase(): Phase {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => { listeners.delete(cb) }
    },
    () => phase,
  )
}

/** Vrai dès que la page peut être montée (sous Yuyu, ou sans lui). */
export function useSplashGone(): boolean {
  return useSplashPhase() !== 'splash'
}

/** Vrai quand Yuyu s'efface : la page fait alors son entrée. */
export function useSplashRevealed(): boolean {
  return useSplashPhase() === 'revealed'
}

/**
 * Fige un élément là où sa boucle CSS l'a amené et l'anime de là vers
 * `to`. Couper la boucle et repartir d'une pose fixe ferait sauter Yuyu.
 */
function from(el: Element | null, to: Keyframe, duration: number, easing: string) {
  if (!(el instanceof SVGElement || el instanceof HTMLElement)) return
  const now = getComputedStyle(el)
  const start: Keyframe = { transform: now.transform === 'none' ? 'none' : now.transform, opacity: now.opacity }
  el.style.animation = 'none'
  el.animate([start, to], { duration, easing, fill: 'forwards' })
}

/** Résolue quand ce que React vient de monter a été dessiné : la tâche du
 *  rendu, la reprise de la page chargée à la demande, puis trois images. */
function afterPaint(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(() => {
      requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    }, 0)
  })
}

export function finishSplash(): void {
  if (phase !== 'splash' || !splash) return
  setPhase('mounting')

  void afterPaint().then(() => {
    // Le filet d'index.html l'a déjà effacé (application très lente).
    const forced = splash.classList.contains('out')
    const reduced = document.documentElement.dataset.motion === 'reduced'
      || window.matchMedia('(prefers-reduced-motion: reduce)').matches

    const finale = forced || reduced ? 0 : FINALE_MS
    if (!forced && !reduced) {
      const q = (s: string) => splash.querySelector(s)
      const total = FINALE_MS + FADE_MS
      // Les crochets éclatent vers l'extérieur en tournant, le visage saute
      // de joie, la lueur s'ouvre.
      from(q('.ys-ring'), { transform: 'rotate(110deg) scale(1.55)', opacity: 0 }, total, 'cubic-bezier(0.4, 0, 0.2, 1)')
      from(q('.ys-face'), { transform: 'translateY(-8px) scale(1.12, 1.12)' }, total, 'cubic-bezier(0.2, 0.9, 0.3, 1.3)')
      from(q('.ys-glow'), { transform: 'scale(1.6)', opacity: 0 }, total, 'ease-out')
      splash.querySelectorAll('.ys-br, .ys-pupil, .ys-eyes, .ys-smile, .ys-antenna').forEach((el) => {
        from(el, { transform: 'none' }, FINALE_MS, 'ease-out')
      })
      splash.classList.add('happy')
    }

    // Le fondu est programmé tout de suite, avec son délai, et non par un
    // minuteur : la page, tout juste montée, occupe encore le fil principal
    // (chargements, effets), et un minuteur partait jusqu'à près d'une
    // seconde en retard — Yuyu restait figé en visage rieur. Une animation
    // d'opacité tourne sur la carte graphique, à l'heure.
    const fade = splash.animate([{ opacity: 1 }, { opacity: 0 }], {
      delay: finale, duration: FADE_MS, easing: 'ease-out', fill: 'forwards',
    })
    splash.style.pointerEvents = 'none'
    // Rend son fond à la page (index.html, règle sur `.leaving`).
    splash.classList.add('leaving')
    // La page fait son entrée pendant la fin de Yuyu et son fondu.
    setPhase('revealed')
    void fade.finished.then(() => splash.remove(), () => splash.remove())
  })
}
