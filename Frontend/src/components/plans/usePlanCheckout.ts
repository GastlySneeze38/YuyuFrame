import { useState } from 'react'
import { open } from '@tauri-apps/plugin-shell'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { YuyuPlan } from '@/stores/useStore'
import { showApiError } from '@/stores/useErrorToast'
import { errorMessage, isNetworkError } from '@/lib/apiError'
import { useT } from '@/i18n'

import type { CheckoutState } from './UpgradeModal'

/**
 * L'achat d'une offre, commun à l'écran Plans du PC et à celui du téléphone :
 * ouverture de la page de paiement, attente du résultat, relecture du plan.
 * Un seul endroit, pour que les deux écrans ne divergent jamais sur ce qui
 * touche à l'argent.
 */
export function usePlanCheckout() {
  const t = useT()
  const setYuyuPlan = useStore((s) => s.setYuyuPlan)
  const [refreshing, setRefreshing] = useState(false)
  const [upgradeTarget, setUpgradeTarget] = useState<string | null>(null)
  const [checkoutState, setCheckoutState] = useState<CheckoutState>('idle')
  const [checkoutError, setCheckoutError] = useState<string | null>(null)

  /** Utilisé par UpgradeModal après un délai d'attente de paiement dépassé. */
  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      const resp = await api.yuyu.refreshPlan()
      setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setRefreshing(false)
    }
  }

  const handleCheckout = async (planId: string) => {
    setCheckoutState('loading')
    setCheckoutError(null)
    try {
      const { checkout_url } = await api.yuyu.createCheckout(planId)
      // Défense en profondeur : n'ouvrir que des URLs https — au cas où la
      // réponse serait un jour corrompue/interceptée (API compromise,
      // YUYU_API_URL pointé vers un serveur non fiable), on n'ouvre jamais
      // aveuglément un schéma arbitraire (file://, javascript:, etc.).
      if (!checkout_url.startsWith('https://')) {
        throw t('plans.invalidCheckoutUrl')
      }
      await open(checkout_url)
      api.analytics.track('checkout_redirected', { plan: planId })
      setCheckoutState('waiting')
      // Le webhook de paiement arrive sur le serveur : ici on ne peut
      // qu'observer le résultat. Sondage toutes les 3 s, 60 s au plus.
      for (let i = 0; i < 20; i++) {
        await new Promise<void>((r) => setTimeout(r, 3000))
        const resp = await api.yuyu.refreshPlan()
        if (resp.plan !== 'free') {
          setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
          setCheckoutState('success')
          setTimeout(() => { setUpgradeTarget(null); setCheckoutState('idle') }, 2500)
          return
        }
      }
      setCheckoutState('timeout')
    } catch (e) {
      setCheckoutError(isNetworkError(e) ? t('common.serverUnreachable') : errorMessage(e))
      setCheckoutState('error')
    }
  }

  /** Ferme la fenêtre d'achat et remet tout à zéro. */
  const closeUpgrade = () => { setUpgradeTarget(null); setCheckoutState('idle'); setCheckoutError(null) }

  return {
    refreshing, upgradeTarget, setUpgradeTarget, checkoutState, setCheckoutState, checkoutError,
    handleRefresh, handleCheckout, closeUpgrade,
  }
}
