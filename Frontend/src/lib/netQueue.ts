import { useStore } from '@/stores/useStore'

/**
 * Ordre des requêtes au démarrage (2026-11-06, audit des chargements réseau).
 *
 * Au lancement, une dizaine de requêtes partaient en même temps : session,
 * notes de version, mise à jour, comptes Minecraft, support, plantages… Sur
 * une connexion lente, elles se disputaient le débit, et ce que l'écran
 * affiche (bannière, serveurs, skin) arrivait après ce que personne ne
 * regardait encore.
 *
 * `background(tâche)` met une tâche de côté : rien ne part avant le premier
 * affichage plus un court délai, puis quatre au plus à la fois, dans l'ordre
 * d'arrivée. Ce qui compte pour l'écran courant ne passe PAS par ici.
 */

const START_DELAY_MS = 1500
const MAX_RUNNING = 4

type Task = () => Promise<unknown> | unknown

const waiting: Task[] = []
let running = 0
let open = false

function pump() {
  while (open && running < MAX_RUNNING && waiting.length > 0) {
    const task = waiting.shift()!
    running++
    Promise.resolve()
      .then(task)
      .catch(() => {})
      .finally(() => {
        running--
        pump()
      })
  }
}

// Ouverture : après le premier affichage (deux images, pour être sûr que la
// page a été peinte), puis un court délai qui laisse partir les requêtes de
// l'écran.
if (typeof window !== 'undefined') {
  requestAnimationFrame(() =>
    requestAnimationFrame(() =>
      setTimeout(() => {
        open = true
        pump()
      }, START_DELAY_MS),
    ),
  )
}

/** Lance `task` en arrière-plan, quand le démarrage est passé. */
export function background(task: Task): void {
  waiting.push(task)
  pump()
}

/**
 * Personne ne regarde : fenêtre cachée (réduite, dans la zone de
 * notification) ou partie en cours. Les sondages sautent alors leur tour —
 * ils relisent de toute façon au retour sur le launcher.
 */
export function isQuiet(): boolean {
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return true
  return useStore.getState().runningInstances.length > 0
}

/** `setInterval` qui saute son tour fenêtre cachée seulement — pour ce qui
 *  doit continuer pendant une partie (statistiques en direct). */
export function visibleInterval(fn: () => void, ms: number): () => void {
  const id = setInterval(() => {
    if (document.visibilityState !== 'hidden') fn()
  }, ms)
  return () => clearInterval(id)
}

/** `setInterval` qui saute son tour quand personne ne regarde (`isQuiet`). */
export function politeInterval(fn: () => void, ms: number): () => void {
  const id = setInterval(() => {
    if (!isQuiet()) fn()
  }, ms)
  return () => clearInterval(id)
}
