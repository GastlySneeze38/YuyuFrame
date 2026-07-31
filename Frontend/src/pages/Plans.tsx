import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-shell'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { YuyuPlan } from '@/stores/useStore'
import { getPlans } from '@/data/plans'
import { PlanBadge } from '@/components/plans/PlanBadge'
import { PlanIcon } from '@/components/plans/PlanIcon'
import { DevPaymentSimulator } from '@/components/plans/DevPaymentSimulator'
import { UpgradeModal } from '@/components/plans/UpgradeModal'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { BackArrowIcon } from '@/components/ui/icons/BackArrowIcon'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

export default function Plans() {
  const navigate = useNavigate()
  const t = useT()
  const { yuyuPlanExpiresAt, yuyuUsername, isPremium, isUltimate, setYuyuPlan, language } = useStore()

  const PLANS = getPlans(t)

  const effectivePlan = isUltimate() ? 'ultimate' : isPremium() ? 'premium' : 'free'

  const [refreshing, setRefreshing] = useState(false)
  const [refreshMsg, setRefreshMsg] = useState<string | null>(null)
  const [upgradeTarget, setUpgradeTarget] = useState<string | null>(null)
  const [checkoutState, setCheckoutState] = useState<'idle' | 'loading' | 'waiting' | 'success' | 'timeout' | 'error'>('idle')
  const [checkoutError, setCheckoutError] = useState<string | null>(null)

  useEffect(() => {
    api.analytics.track('plans_page_viewed')
  }, [])

  const handleRefresh = async () => {
    setRefreshing(true)
    setRefreshMsg(null)
    try {
      const resp = await api.yuyu.refreshPlan()
      setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
      setRefreshMsg(t('plans.planUpdated', { plan: resp.plan }))
      setTimeout(() => setRefreshMsg(null), 4000)
    } catch (e) {
      showError(t('plans.serverUnreachable'))
    } finally {
      setRefreshing(false)
    }
  }

  const handleCheckout = async (planId: string) => {
    setCheckoutState('loading')
    setCheckoutError(null)
    try {
      if (import.meta.env.DEV) {
        const resp = await api.yuyu.devSimulatePayment(planId)
        setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
        setCheckoutState('success')
        setTimeout(() => { setUpgradeTarget(null); setCheckoutState('idle') }, 2500)
        return
      }
      const { checkout_url } = await api.yuyu.createCheckout(planId)
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
      setCheckoutError(String(e))
      setCheckoutState('error')
    }
  }

  return (
    <div className="flex h-full flex-col overflow-auto bg-[#09090D] text-white">
      <div className="mx-auto w-full max-w-5xl px-6 py-10 flex flex-col gap-10">

        {/* Header */}
        <div className="flex items-center justify-between">
          <button
            onClick={() => navigate('/home')}
            className="flex items-center gap-2 transition-colors duration-150 text-[12px] text-[rgba(255,255,255,0.3)] font-medium hover:text-[rgba(255,255,255,0.7)]"
          >
            <BackArrowIcon size={14} />
            {t('plans.back')}
          </button>

          <div className="flex flex-col items-center gap-1">
            <h1 className="font-black text-white text-[30px] tracking-[-0.02em] [text-shadow:0_0_40px_rgba(75,63,207,0.5)]">
              {t('plans.title')}
            </h1>
            <p className="text-[13px] text-[rgba(255,255,255,0.35)]">
              {t('plans.subtitle')}
            </p>
          </div>

          {/* Current plan badge + refresh */}
          <div className="flex flex-col items-end gap-2 w-[100px]">
            {yuyuUsername && (
              <>
                <div className="flex flex-col items-end gap-0.5">
                  <span className="text-[10px] text-[rgba(255,255,255,0.25)] font-medium">{t('plans.yourPlan')}</span>
                  <PlanBadge plan={effectivePlan} />
                </div>
                <button
                  onClick={handleRefresh}
                  disabled={refreshing}
                  className={`flex items-center gap-1.5 transition-colors duration-150 text-[10px] font-semibold ${refreshing ? 'text-[rgba(255,255,255,0.2)] cursor-not-allowed' : 'text-[rgba(75,63,207,0.7)] cursor-pointer hover:text-[#818cf8]'}`}
                >
                  {refreshing ? (
                    <ButtonSpinner size={12} color="rgba(75,63,207,0.6)" trackColor="rgba(255,255,255,0.1)" />
                  ) : (
                    <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}>
                      <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
                    </svg>
                  )}
                  {t('plans.refresh')}
                </button>
              </>
            )}
          </div>
        </div>

        {/* Refresh feedback */}
        {refreshMsg && (
          <div className="flex items-center gap-2 rounded-xl px-4 py-2.5 bg-[rgba(74,222,128,0.07)] border border-[rgba(74,222,128,0.2)]">
            <span className="text-[12px] text-[rgb(74,222,128)] font-semibold">
              {refreshMsg}
            </span>
          </div>
        )}

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

        {/* Free notice banner */}
        <div className="flex items-center gap-3 rounded-2xl px-5 py-3 bg-[rgba(75,63,207,0.1)] border border-[rgba(75,63,207,0.25)]">
          <svg viewBox="0 0 24 24" fill="#818cf8" width={16} height={16}>
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
          </svg>
          <p className="text-[12px] text-[rgba(255,255,255,0.55)] leading-[1.5]">
            <span className="text-[#818cf8] font-semibold">{t('plans.freeNoticeHighlight')}</span>{' '}
            {t('plans.freeNoticeRest')}
          </p>
        </div>

        {/* Cards */}
        <div className="grid grid-cols-3 gap-5">
          {PLANS.map((plan) => {
            const isCurrent = plan.id === effectivePlan
            return (
              <div
                key={plan.id}
                className="relative flex flex-col rounded-2xl overflow-hidden transition-[border-color,box-shadow] duration-200"
                style={{
                  background: `linear-gradient(145deg, rgba(255,255,255,0.03) 0%, ${plan.glowColor} 100%)`,
                  border: `1px solid ${isCurrent ? plan.borderColor : 'rgba(255,255,255,0.07)'}`,
                  boxShadow: isCurrent ? `0 0 0 1px ${plan.borderColor}, 0 8px 40px ${plan.glowColor}` : 'none',
                }}
              >
                {/* Top accent line */}
                {plan.price && (
                  <div className="h-0.5 w-full" style={{ background: `linear-gradient(90deg, transparent, ${plan.color}, transparent)` }} />
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

                <div className="flex flex-col gap-6 p-6">
                  {/* Plan name & price */}
                  <div className="flex flex-col gap-2">
                    <div className="flex items-center gap-2">
                      <PlanIcon plan={plan.id} color={plan.color} />
                      <span className="font-bold text-[16px]" style={{ color: plan.price ? plan.color : 'rgba(255,255,255,0.7)' }}>
                        {plan.name}
                      </span>
                    </div>

                    {plan.price ? (
                      <div className="flex items-baseline gap-1">
                        <span className="font-black text-[32px] text-white tracking-[-0.03em]">
                          {plan.price}€
                        </span>
                        <span className="text-[12px] text-[rgba(255,255,255,0.3)] font-medium">{t('plans.perMonth')}</span>
                        <span className="ml-1 rounded-full px-1.5 py-0.5 text-[9px] font-bold text-[#4ade80] bg-[rgba(74,222,128,0.1)] tracking-[0.05em]">
                          {t('plans.free')}
                        </span>
                      </div>
                    ) : (
                      <div className="flex items-baseline gap-1">
                        <span className="font-black text-[32px] text-white tracking-[-0.03em]">
                          0€
                        </span>
                        <span className="text-[12px] text-[rgba(255,255,255,0.3)] font-medium">{t('plans.perMonth')}</span>
                      </div>
                    )}
                  </div>

                  {/* Features */}
                  <div className="flex flex-col gap-2.5">
                    {plan.features.map((feat, i) => (
                      <div key={i} className="flex items-start gap-2.5">
                        <div className="mt-0.5 flex-shrink-0">
                          {feat.ok ? (
                            <svg viewBox="0 0 16 16" fill="none" width={14} height={14}>
                              <circle cx="8" cy="8" r="7" fill={plan.price ? plan.badgeBg : 'rgba(255,255,255,0.06)'} />
                              <path d="M4.5 8l2.5 2.5 4.5-5" stroke={plan.price ? plan.color : 'rgba(255,255,255,0.4)'} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                            </svg>
                          ) : (
                            <svg viewBox="0 0 16 16" fill="none" width={14} height={14}>
                              <circle cx="8" cy="8" r="7" fill="rgba(255,255,255,0.03)" />
                              <path d="M5.5 10.5l5-5M10.5 10.5l-5-5" stroke="rgba(255,255,255,0.15)" strokeWidth="1.5" strokeLinecap="round" />
                            </svg>
                          )}
                        </div>
                        <span className={`text-[12px] leading-[1.4] ${feat.ok ? 'text-[rgba(255,255,255,0.65)]' : 'text-[rgba(255,255,255,0.22)]'}`}>
                          {feat.label}
                        </span>
                      </div>
                    ))}
                  </div>

                  {/* CTA */}
                  <button
                    className={`w-full rounded-xl font-bold transition-all duration-200 active:scale-95 h-10 text-[13px] cursor-pointer disabled:cursor-default enabled:hover:brightness-110 ${isCurrent ? 'border' : 'border-0'}`}
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
                    disabled={isCurrent || !plan.price}
                    onClick={() => { if (!isCurrent && plan.price) setUpgradeTarget(plan.id) }}
                  >
                    {isCurrent ? t('plans.currentPlan') : plan.price ? t('plans.upgradeTo', { name: plan.name }) : t('plans.freePlan')}
                  </button>
                </div>
              </div>
            )
          })}
        </div>

        {/* Comparison table */}
        <div className="rounded-2xl overflow-hidden border border-[rgba(255,255,255,0.07)]">
          <div className="px-6 py-4 bg-[rgba(255,255,255,0.02)] border-b border-[rgba(255,255,255,0.06)]">
            <span className="text-[13px] font-bold text-[rgba(255,255,255,0.6)] tracking-[0.05em]">
              {t('plans.quotasComparison')}
            </span>
          </div>
          <div className="divide-y divide-[rgba(255,255,255,0.05)]">
            {[
              { label: t('plans.rowMcAccounts'), free: t('plans.max2'), premium: t('plans.unlimited'), ultimate: t('plans.unlimited') },
              { label: t('plans.rowStats'), free: t('plans.basic'), premium: t('plans.advanced'), ultimate: t('plans.advanced') },
              { label: t('plans.rowLocalInstances'), free: t('plans.unlimitedFem'), premium: t('plans.unlimitedFem'), ultimate: t('plans.unlimitedFem') },
              { label: t('plans.rowSyncedInstances'), free: '—', premium: t('plans.premiumSyncQuota'), ultimate: '10' },
              { label: t('plans.rowSyncedSaves'), free: '—', premium: '3', ultimate: '10' },
            ].map((row, i) => (
              <div key={i} className="grid grid-cols-4 px-6 py-3.5">
                <span className="text-[12px] text-[rgba(255,255,255,0.45)] font-medium">{row.label}</span>
                <span className="text-[12px] text-[rgba(255,255,255,0.3)] text-center">{row.free}</span>
                <span className="text-[12px] text-[#818cf8] text-center font-medium">{row.premium}</span>
                <span className="text-[12px] text-[#f59e0b] text-center font-medium">{row.ultimate}</span>
              </div>
            ))}
          </div>
          <div className="grid grid-cols-4 px-6 py-2 bg-[rgba(255,255,255,0.02)] border-t border-[rgba(255,255,255,0.06)]">
            <span className="text-[10px] text-[rgba(255,255,255,0.18)]" />
            {['Free', 'Premium', 'Ultimate'].map((name, i) => (
              <span
                key={i}
                className={`text-[10px] text-center font-bold tracking-[0.06em] ${i === 0 ? 'text-[rgba(255,255,255,0.25)]' : i === 1 ? 'text-[#818cf8]' : 'text-[#f59e0b]'}`}
              >
                {name.toUpperCase()}
              </span>
            ))}
          </div>
        </div>

        {/* Dev simulator — visible uniquement en mode développement Vite */}
        {import.meta.env.DEV && (
          <DevPaymentSimulator
            onSimulate={async (planId) => {
              const resp = await api.yuyu.devSimulatePayment(planId)
              setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
            }}
          />
        )}

        {/* Footer note */}
        <div className="text-center pb-4">
          <p className="text-[11px] text-[rgba(255,255,255,0.2)] leading-[1.6]">
            {t('plans.footerNote')}<br />
            {t('plans.footerNote2')}
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
