import { useState } from 'react'
import { api } from '@/api/client'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/** Où en est le lancement d'une instance : un pourcentage et ce qui se passe. */
export interface LaunchStep {
  percent: number
  message: string
}

/**
 * Le lancement, vu de l'accueil du téléphone.
 *
 * Mêmes commandes et mêmes événements que l'accueil du PC (`pages/Home.tsx`),
 * sans ce qui n'y a de sens que sur un ordinateur : masquer la fenêtre au
 * départ du jeu, animer la seconde moitié de la barre d'après la durée des
 * lancements précédents. Sur téléphone le jeu prend tout l'écran : la barre
 * s'arrête à la fin des téléchargements et le bouton dit « préparation ».
 *
 * L'état « en jeu » vient du store, tenu par `App.tsx` : il survit à la
 * navigation, ce hook ne garde que ce qui est propre à l'écran.
 */
export function useMobileLaunch() {
  const t = useT()
  const [steps, setSteps] = useState<Record<string, LaunchStep>>({})
  const [cancelling, setCancelling] = useState<Record<string, boolean>>({})

  const clear = (id: string) => {
    setSteps(({ [id]: _, ...rest }) => rest)
    setCancelling(({ [id]: _, ...rest }) => rest)
  }

  useTauriEvent<{ instance_id: string; current: number; total: number; message: string }>(
    'download_progress',
    ({ instance_id, current, total, message }) => {
      const percent = total > 0 ? Math.round((current / total) * 100) : 0
      setSteps((prev) => ({ ...prev, [instance_id]: { percent, message } }))
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
    showNotice(t('home.launchCancelled'))
    clear(instanceId)
  })

  const launch = async (instanceId: string, connectServer?: string) => {
    const store = useStore.getState()
    if (!store.username || store.isInstanceRunning(instanceId)) return
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
      showError(e instanceof Error ? e.message : typeof e === 'string' ? e : t('home.launchError'))
    }
  }

  const cancel = async (instanceId: string) => {
    if (cancelling[instanceId]) return
    setCancelling((prev) => ({ ...prev, [instanceId]: true }))
    try {
      await api.launch.cancel(instanceId)
    } catch (e) {
      showError(e)
      setCancelling(({ [instanceId]: _, ...rest }) => rest)
    }
  }

  return { steps, cancelling, launch, cancel }
}
