import { create } from 'zustand'
import { api } from '@/api/client'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { t, useT } from '@/i18n'

/** Où en est le lancement d'une instance : un pourcentage et ce qui se passe. */
export interface LaunchStep {
  percent: number
  message: string
}

/**
 * Le lancement, vu du téléphone.
 *
 * Mêmes commandes et mêmes événements que l'accueil du PC (`pages/Home.tsx`),
 * sans ce qui n'y a de sens que sur un ordinateur : masquer la fenêtre au
 * départ du jeu, animer la seconde moitié de la barre d'après la durée des
 * lancements précédents. Sur téléphone le jeu prend tout l'écran : la barre
 * s'arrête à la fin des téléchargements et le bouton dit « préparation ».
 *
 * **Partagé (2026-11-10)** : la progression vit dans un magasin et non plus
 * dans l'accueil, parce que deux autres endroits la lisent — l'anneau autour
 * du bouton ☰ de chaque écran (`MenuButton`) et la ligne « Jouer » en tête du
 * menu (`NavDrawer`), qui peut aussi lancer. Les événements du Rust sont donc
 * écoutés **une seule fois**, par `useLaunchEvents` (posé dans `NavDrawer`,
 * toujours monté sur téléphone) : écoutés à deux endroits, une erreur de
 * lancement s'afficherait deux fois.
 *
 * L'état « en jeu » vient du store principal, tenu par `App.tsx`.
 */
interface LaunchState {
  steps: Record<string, LaunchStep>
  cancelling: Record<string, boolean>
  /** « Choisir l'instance » demandé par le menu : l'accueil ouvre sa feuille
   *  et remet le drapeau à faux (aussi s'il n'était pas encore monté). */
  pickPending: boolean
}

export const useLaunchProgress = create<LaunchState>(() => ({ steps: {}, cancelling: {}, pickPending: false }))

function setStep(id: string, step: LaunchStep) {
  useLaunchProgress.setState((s) => ({ steps: { ...s.steps, [id]: step } }))
}

function clear(id: string) {
  useLaunchProgress.setState(({ steps: { [id]: _s, ...steps }, cancelling: { [id]: _c, ...cancelling } }) => ({ steps, cancelling }))
}

/** Demande à l'accueil d'ouvrir le choix de l'instance. */
export function requestInstancePick() {
  useLaunchProgress.setState({ pickPending: true })
}

/** Lance une instance (rien si elle tourne déjà ou sans compte). */
export async function launchInstance(instanceId: string, connectServer?: string) {
  const store = useStore.getState()
  if (!store.username || store.isInstanceRunning(instanceId)) return
  // Une étape à 0 % tout de suite : sans elle, entre l'appui et le premier
  // événement du Rust, l'instance passerait pour « en jeu » (anneau vert).
  setStep(instanceId, { percent: 0, message: t('home.launching') })
  try {
    await api.launch.start(
      instanceId,
      store.avoidBetaDependencies,
      store.showConsole,
      connectServer,
      store.isAgentEnabled(instanceId),
    )
    store.setInstanceRunning(instanceId, true)
    const instance = store.instances.find((i) => i.id === instanceId)
    if (instance) store.setLastSession({ instanceName: instance.name, at: new Date().toISOString() })
  } catch (e) {
    clear(instanceId)
    showError(e instanceof Error ? e.message : typeof e === 'string' ? e : t('home.launchError'))
  }
}

export async function cancelLaunch(instanceId: string) {
  if (useLaunchProgress.getState().cancelling[instanceId]) return
  useLaunchProgress.setState((s) => ({ cancelling: { ...s.cancelling, [instanceId]: true } }))
  try {
    await api.launch.cancel(instanceId)
  } catch (e) {
    showError(e)
    useLaunchProgress.setState(({ cancelling: { [instanceId]: _, ...cancelling } }) => ({ cancelling }))
  }
}

/** Les événements du lancement. À poser **une seule fois** (`NavDrawer`). */
export function useLaunchEvents() {
  const tr = useT()
  useTauriEvent<{ instance_id: string; current: number; total: number; message: string }>(
    'download_progress',
    ({ instance_id, current, total, message }) => {
      setStep(instance_id, { percent: total > 0 ? Math.round((current / total) * 100) : 0, message })
    },
  )
  useTauriEvent<{ instance_id: string }>('game_ready', ({ instance_id }) => clear(instance_id))
  useTauriEvent<{ running: boolean; instance_id: string }>('game_state', ({ running, instance_id }) => {
    if (!running) clear(instance_id)
  })
  useTauriEvent<{ instance_id: string; message: string }>('launch_error', ({ instance_id, message }) => {
    showError(message)
    useStore.getState().setInstanceRunning(instance_id, false)
    clear(instance_id)
  })
  useTauriEvent<string>('launch_cancelled', (instanceId) => {
    showNotice(tr('home.launchCancelled'))
    clear(instanceId)
  })
}

/** Ce que l'accueil lit et fait : même forme qu'avant le partage. */
export function useMobileLaunch() {
  const steps = useLaunchProgress((s) => s.steps)
  const cancelling = useLaunchProgress((s) => s.cancelling)
  return { steps, cancelling, launch: launchInstance, cancel: cancelLaunch }
}

/**
 * L'état montré autour du ☰ : la progression du lancement en cours, sinon
 * « en jeu » si une instance tourne, sinon rien.
 */
export function useLaunchBadge(): { kind: 'launching'; percent: number } | { kind: 'playing' } | null {
  const step = useLaunchProgress((s) => Object.values(s.steps)[0] ?? null)
  const playing = useStore((s) => s.runningInstances.length > 0)
  if (step) return { kind: 'launching', percent: step.percent }
  return playing ? { kind: 'playing' } : null
}
