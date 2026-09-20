import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-shell'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { YuyuPlan } from '@/stores/useStore'
import { getPlans } from '@/data/plans'
import { PlanIcon } from '@/components/plans/PlanIcon'
import { UpgradeModal } from '@/components/plans/UpgradeModal'
import { HeaderAccountBadge } from '@/components/ui/HeaderAccountBadge'
import { BackArrowIcon } from '@/components/ui/icons/BackArrowIcon'
import { showApiError } from '@/stores/useErrorToast'
import { errorMessage, isNetworkError } from '@/lib/apiError'
import { useT } from '@/i18n'

export default function Plans() {
  const navigate = useNavigate()
  const t = useT()
  const { yuyuPlanExpiresAt, isPremium, isUltimate, setYuyuPlan, language } = useStore()

  const PLANS = getPlans(t)

  const effectivePlan = isUltimate() ? 'ultimate' : isPremium() ? 'premium' : 'free'

  const [refreshing, setRefreshing] = useState(false)
  const [upgradeTarget, setUpgradeTarget] = useState<string | null>(null)
  const [checkoutState, setCheckoutState] = useState<'idle' | 'loading' | 'waiting' | 'success' | 'timeout' | 'error'>('idle')
  const [checkoutError, setCheckoutError] = useState<string | null>(null)

  useEffect(() => {
    api.analytics.track('plans_page_viewed')
  }, [])

  // Utilisé par le bouton "Rafraîchir mon plan" de UpgradeModal (après un
  // timeout d'attente de paiement) — le rafraîchissement depuis l'en-tête se
  // fait maintenant via HeaderAccountBadge, plus besoin de le dupliquer ici.
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
      // aveuglément un schéma arbitraire (file://, javascript:, etc.) via le
      // shell de l'OS.
      if (!checkout_url.startsWith('https://')) {
        throw t('plans.invalidCheckoutUrl')
      }
      await open(checkout_url)
      api.analytics.track('checkout_redirected', { plan: planId })
      setCheckoutState('waiting')
      // Polling toutes les 3s pendant 60s max
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

  return (
    <div className="flex h-full flex-col overflow-auto bg-[#09090D] text-white">
      <div className="mx-auto w-full max-w-5xl px-6 py-10 flex flex-col gap-10">

        {/* Header — les deux côtés sont en flex-1 pour que le titre reste
            géométriquement centré quel que soit la largeur du bouton retour
            vs celle du badge compte (avant, un `justify-between` avec une
            largeur fixe à droite ne recentrait le titre que par coïncidence). */}
        <div className="flex items-center animate-fade-in-up">
          <div className="flex flex-1 items-center">
            <button
              onClick={() => navigate('/home')}
              className="flex items-center gap-2 transition-colors duration-150 text-[12px] text-[rgba(255,255,255,0.3)] font-medium hover:text-[rgba(255,255,255,0.7)]"
            >
              <BackArrowIcon size={14} />
              {t('plans.back')}
            </button>
          </div>

          <div className="flex flex-shrink-0 flex-col items-center gap-1">
            <h1 className="font-black text-white text-[30px] tracking-[-0.02em] [text-shadow:0_0_40px_rgba(75,63,207,0.5)]">
              {t('plans.title')}
            </h1>
            <p className="text-[13px] text-[rgba(255,255,255,0.35)]">
              {t('plans.subtitle')}
            </p>
          </div>

          <div className="flex flex-1 items-center justify-end">
            <HeaderAccountBadge />
          </div>
        </div>

        {/* Plan expiry warning */}
        {yuyuPlanExpiresAt && effectivePlan !== 'free' && (
          <div className="flex items-center gap-3 rounded-2xl px-5 py-3 bg-[rgba(245,158,11,0.08)] border border-[rgba(245,158,11,0.2)]">
            <svg viewBox="0 0 24 24" fill="#f59e0b" width={16} height={16}>
              <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z" />
            </svg>
            <p className="text-[12px] text-[rgba(255,255,255,0.55)] leading-[1.5]">
              {t('plans.expiresOnPrefix')} <span className="text-[#f59e0b] font-semibold">{effectivePlan}</span> {t('plans.expiresOnSuffix')}{' '}
              <span className="text-[rgba(255,255,255,0.8)] font-semibold">
                {new Date(yuyuPlanExpiresAt * 1000).toLocaleDateString(language === 'fr' ? 'fr-FR' : 'en-US', { day: '2-digit', month: 'long', year: 'numeric' })}
              </span>.
            </p>
          </div>
        )}

        {/* Cards */}
        <div className="grid grid-cols-3 gap-5 items-start">
          {PLANS.map((plan, planIndex) => {
            const isCurrent = plan.id === effectivePlan
            const isFeatured = plan.featured && !plan.comingSoon
            return (
              <div
                key={plan.id}
                className={`group relative flex flex-col rounded-2xl overflow-hidden transition-all duration-300 animate-fade-in-up ${plan.comingSoon ? 'opacity-60 grayscale' : 'hover:-translate-y-1.5'} ${isFeatured ? '-translate-y-2' : ''}`}
                style={{
                  animationDelay: `${planIndex * 90}ms`,
                  background: isFeatured
                    ? `linear-gradient(160deg, rgba(75,63,207,0.14) 0%, ${plan.glowColor} 100%)`
                    : `linear-gradient(145deg, rgba(255,255,255,0.03) 0%, ${plan.glowColor} 100%)`,
                  border: `1px solid ${isCurrent || isFeatured ? plan.borderColor : 'rgba(255,255,255,0.07)'}`,
                  boxShadow: isFeatured
                    ? `0 0 0 1px ${plan.borderColor}, 0 20px 60px rgba(75,63,207,0.25)`
                    : isCurrent
                      ? `0 0 0 1px ${plan.borderColor}, 0 8px 40px ${plan.glowColor}`
                      : 'none',
                }}
              >
                {/* Top accent line */}
                {plan.price && (
                  <div
                    className={`w-full transition-all duration-300 ${isFeatured ? 'h-[3px]' : 'h-0.5'}`}
                    style={{ background: `linear-gradient(90deg, transparent, ${plan.color}, transparent)` }}
                  />
                )}

                {/* Populaire badge */}
                {isFeatured && !isCurrent && (
                  <div className="absolute left-1/2 top-0 -translate-x-1/2 -translate-y-1/2 rounded-full px-3 py-1 text-[9px] font-bold tracking-[0.08em] shadow-[0_4px_16px_rgba(75,63,207,0.5)] animate-pulse-slow bg-[#4B3FCF] text-white">
                    {t('plans.popular').toUpperCase()}
                  </div>
                )}

                {/* Current badge */}
                {isCurrent && (
                  <div
                    className="absolute right-3 top-3 rounded-full px-2 py-0.5 text-[9px] font-bold tracking-[0.06em]"
                    style={{ background: plan.badgeBg, color: plan.badgeColor }}
                  >
                    {t('plans.current')}
                  </div>
                )}

                {/* Coming soon badge */}
                {plan.comingSoon && !isCurrent && (
                  <div className="absolute right-3 top-3 rounded-full px-2 py-0.5 text-[9px] font-bold tracking-[0.06em] bg-[rgba(245,158,11,0.15)] text-[#f59e0b]">
                    {t('plans.comingSoon')}
                  </div>
                )}

                <div className={`flex flex-col gap-6 transition-all duration-300 ${isFeatured ? 'p-7' : 'p-6'} ${!plan.price ? 'opacity-80' : ''}`}>
                  {/* Plan name & price */}
                  <div className="flex flex-col gap-2">
                    <div className="flex items-center gap-2">
                      <PlanIcon plan={plan.id} color={plan.color} />
                      <span className={`font-bold ${isFeatured ? 'text-[17px]' : 'text-[15px]'}`} style={{ color: plan.price ? plan.color : 'rgba(255,255,255,0.55)' }}>
                        {plan.name}
                      </span>
                    </div>

                    {plan.price ? (
                      <div className="flex items-baseline gap-1">
                        <span className={`font-black text-white tracking-[-0.03em] ${isFeatured ? 'text-[38px]' : 'text-[30px]'}`}>
                          {plan.price}€
                        </span>
                        <span className="text-[12px] text-[rgba(255,255,255,0.3)] font-medium">{t('plans.perMonth')}</span>
                      </div>
                    ) : (
                      <div className="flex items-baseline gap-1">
                        <span className="font-black text-[26px] text-[rgba(255,255,255,0.5)] tracking-[-0.03em]">
                          0€
                        </span>
                        <span className="text-[12px] text-[rgba(255,255,255,0.25)] font-medium">{t('plans.perMonth')}</span>
                      </div>
                    )}
                  </div>

                  {/* Features */}
                  <div className={`flex flex-col ${isFeatured ? 'gap-3' : 'gap-2'}`}>
                    {plan.features.map((feat, i) => (
                      <div
                        key={i}
                        className="flex items-start gap-2.5 animate-fade-in-up"
                        style={{ animationDelay: `${planIndex * 90 + 120 + i * 40}ms` }}
                      >
                        <div className="mt-0.5 flex-shrink-0">
                          {feat.ok ? (
                            <svg viewBox="0 0 16 16" fill="none" width={isFeatured ? 15 : 13} height={isFeatured ? 15 : 13}>
                              <circle cx="8" cy="8" r="7" fill={plan.price ? plan.badgeBg : 'rgba(255,255,255,0.06)'} />
                              <path d="M4.5 8l2.5 2.5 4.5-5" stroke={plan.price ? plan.color : 'rgba(255,255,255,0.4)'} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                            </svg>
                          ) : (
                            <svg viewBox="0 0 16 16" fill="none" width={13} height={13}>
                              <circle cx="8" cy="8" r="7" fill="rgba(255,255,255,0.03)" />
                              <path d="M5.5 10.5l5-5M10.5 10.5l-5-5" stroke="rgba(255,255,255,0.15)" strokeWidth="1.5" strokeLinecap="round" />
                            </svg>
                          )}
                        </div>
                        <span className={`leading-[1.4] ${isFeatured ? 'text-[13px]' : 'text-[12px]'} ${feat.ok ? (isFeatured ? 'text-white/80 font-medium' : 'text-white/55') : 'text-white/20'}`}>
                          {feat.label}
                        </span>
                      </div>
                    ))}
                  </div>

                  {/* CTA */}
                  <button
                    className={`w-full rounded-xl font-bold transition-all duration-200 active:scale-95 cursor-pointer disabled:cursor-default enabled:hover:brightness-110 ${isFeatured ? 'h-12 text-[14px] shadow-[0_8px_28px_rgba(75,63,207,0.4)]' : 'h-9 text-[12px]'} ${isCurrent ? 'border' : 'border-0'}`}
                    style={{
                      background: isCurrent
                        ? (plan.price ? plan.badgeBg : 'rgba(255,255,255,0.06)')
                        : (plan.price ? plan.color : 'rgba(255,255,255,0.06)'),
                      color: isCurrent
                        ? (plan.price ? plan.color : 'rgba(255,255,255,0.4)')
                        : (plan.price ? '#09090D' : 'rgba(255,255,255,0.4)'),
                      borderColor: isCurrent
                        ? (plan.price ? plan.borderColor : 'rgba(255,255,255,0.1)')
                        : undefined,
                    }}
                    disabled={isCurrent || !plan.price || plan.comingSoon}
                    onClick={() => { if (!isCurrent && plan.price && !plan.comingSoon) setUpgradeTarget(plan.id) }}
                  >
                    {isCurrent ? t('plans.currentPlan') : plan.comingSoon ? t('plans.comingSoon') : plan.price ? t('plans.upgradeTo', { name: plan.name }) : t('plans.freePlan')}
                  </button>
                </div>
              </div>
            )
          })}
        </div>

        {/* Comparison table */}
        <div className="rounded-2xl overflow-hidden border border-[rgba(255,255,255,0.07)] animate-fade-in-up" style={{ animationDelay: '300ms' }}>
          <div className="px-6 py-4 bg-[rgba(255,255,255,0.02)] border-b border-[rgba(255,255,255,0.06)]">
            <span className="text-[13px] font-bold text-[rgba(255,255,255,0.6)] tracking-[0.05em]">
              {t('plans.quotasComparison')}
            </span>
          </div>

          {/* Plan headers */}
          <div className="grid grid-cols-[1.4fr_1fr_1fr_1fr] px-6 pt-4 pb-3 bg-[rgba(255,255,255,0.015)] border-b border-[rgba(255,255,255,0.06)]">
            <span />
            {[
              { id: 'free', name: 'Free', color: 'rgba(255,255,255,0.5)' },
              { id: 'premium', name: 'Premium', color: '#818cf8' },
              { id: 'ultimate', name: 'Ultimate', color: '#f59e0b' },
            ].map((p) => (
              <div key={p.id} className="flex flex-col items-center gap-1">
                <PlanIcon plan={p.id} color={p.color} />
                <span className="text-[10px] font-bold tracking-[0.06em]" style={{ color: p.color }}>
                  {p.name.toUpperCase()}
                </span>
              </div>
            ))}
          </div>

          <div className="divide-y divide-[rgba(255,255,255,0.05)]">
            {[
              { label: t('plans.rowStats'), free: t('plans.basic'), premium: t('plans.advanced'), ultimate: t('plans.advanced') },
              { label: t('plans.rowLocalInstances'), free: t('plans.unlimitedFem'), premium: t('plans.unlimitedFem'), ultimate: t('plans.unlimitedFem') },
              { label: t('plans.rowSyncedInstances'), free: '—', premium: t('plans.premiumSyncQuota'), ultimate: '10' },
              { label: t('plans.rowSyncedSaves'), free: '—', premium: '3', ultimate: '10' },
            ].map((row, i) => (
              <div key={i} className="grid grid-cols-[1.4fr_1fr_1fr_1fr] items-center px-6 py-3.5 transition-colors duration-150 hover:bg-[rgba(255,255,255,0.02)]">
                <span className="text-[12px] text-[rgba(255,255,255,0.45)] font-medium">{row.label}</span>
                <span className="text-[12px] text-[rgba(255,255,255,0.3)] text-center">{row.free}</span>
                <span className="text-[12px] text-[#818cf8] text-center font-semibold">{row.premium}</span>
                <span className="text-[12px] text-[#f59e0b] text-center font-semibold">{row.ultimate}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Footer note */}
        <div className="text-center pb-4">
          <p className="text-[11px] text-[rgba(255,255,255,0.2)] leading-[1.6]">
            {t('plans.footerNote')}
          </p>
        </div>

      </div>

      {/* Upgrade modal */}
      {upgradeTarget && (
        <UpgradeModal
          plan={upgradeTarget}
          checkoutState={checkoutState}
          checkoutError={checkoutError}
          onClose={() => { setUpgradeTarget(null); setCheckoutState('idle'); setCheckoutError(null) }}
          onCheckout={() => handleCheckout(upgradeTarget)}
          onRefresh={async () => {
            await handleRefresh()
            setCheckoutState('idle')
          }}
          refreshing={refreshing}
        />
      )}
    </div>
  )
}
