import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { AnimatePresence } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { api } from '@/api/client'
import { parseApiError } from '@/lib/apiError'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/**
 * Garde de plan d'un écran payant.
 *
 * Le verdict vient du backend (`plan_guard`, Backend/src/commands/plan.rs) et
 * non du plan affiché dans le store : celui-ci n'est qu'un état de rendu, que
 * les outils de développement suffisent à modifier. Tant que la réponse n'est
 * pas là, la page n'est pas montée — on ne montre pas un écran payant le temps
 * d'un aller-retour. Refus : une fenêtre explique et emmène aux offres, la
 * seule autre sortie étant le retour à la page précédente.
 */
export function PlanGate({ feature, children }: { feature: string; children: ReactNode }) {
  const t = useT()
  const navigate = useNavigate()
  // Un achat met le plan à jour dans le store : c'est le signal pour redemander
  // au backend sans quitter la page.
  const plan = useStore((s) => s.yuyuPlan)
  const [verdict, setVerdict] = useState<'checking' | 'allowed' | 'denied'>('checking')
  const [requiredPlan, setRequiredPlan] = useState('premium')

  useEffect(() => {
    let cancelled = false
    setVerdict('checking')
    api.plan
      .guard(feature)
      .then(() => {
        if (!cancelled) setVerdict('allowed')
      })
      .catch((e) => {
        if (cancelled) return
        const err = parseApiError(e)
        const required = err.extra.required_plan
        if (typeof required === 'string') setRequiredPlan(required)
        setVerdict('denied')
      })
    return () => {
      cancelled = true
    }
  }, [feature, plan])

  const planName = requiredPlan === 'ultimate' ? 'Ultimate' : 'Premium'

  return (
    <>
      {verdict === 'allowed' && children}
      <AnimatePresence>
        {verdict === 'denied' && (
          <ModalShell
            title={t('planGate.title')}
            onClose={() => navigate(-1)}
            maxWidth="max-w-sm"
          >
            <p className="text-[13px] leading-relaxed text-txt-secondary">
              {t('planGate.text').replace('{plan}', planName)}
            </p>
            <div className="flex gap-2">
              <Button variant="ghost" fullWidth onClick={() => navigate(-1)}>
                {t('planGate.back')}
              </Button>
              <Button variant="primary" fullWidth onClick={() => navigate('/plans')}>
                {t('planGate.seePlans')}
              </Button>
            </div>
          </ModalShell>
        )}
      </AnimatePresence>
    </>
  )
}
