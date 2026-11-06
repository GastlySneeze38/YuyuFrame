import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { YuyuPlan } from '@/stores/useStore'
import { showApiError } from '@/stores/useErrorToast'
import { PlanBadge } from '@/components/plans/PlanBadge'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'

/** Le fond de l'initiale selon le plan : gris en gratuit, violet en Premium, ambre en Ultimate (les couleurs de `PlanBadge`). */
const PLAN_COLORS: Record<'free' | 'premium' | 'ultimate', string> = {
  free: 'bg-[rgba(255,255,255,0.12)] text-[rgba(255,255,255,0.75)] ring-[rgba(255,255,255,0.15)]',
  premium: 'bg-[#4b3fcf] text-white ring-[rgba(129,140,248,0.55)]',
  ultimate: 'bg-[#d97706] text-white ring-[rgba(245,158,11,0.6)]',
}

/**
 * Le compte YuyuFrame, dans l'en-tête du panneau Amis : l'initiale du
 * pseudo sur la couleur du plan. Au clic, le pseudo et le plan, puis
 * rafraîchir le plan et se déconnecter. Rien sans compte connecté (le
 * panneau a déjà de quoi se connecter).
 */
export function AccountMenu() {
  const t = useT()
  const { yuyuUsername, isPremium, isUltimate, setYuyuPlan, clearYuyuSession } = useStore()
  const [open, setOpen] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onClickOutside = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [open])

  if (!yuyuUsername) return null
  const plan = isUltimate() ? 'ultimate' : isPremium() ? 'premium' : 'free'

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

  const handleLogout = async () => {
    // Vide la session locale (base + coffre + mémoire) côté Backend.
    await api.yuyu.logout().catch(() => {})
    clearYuyuSession()
    setOpen(false)
  }

  return (
    <div ref={ref} className="relative flex-shrink-0">
      <button
        onClick={() => setOpen((v) => !v)}
        title={yuyuUsername}
        aria-label={yuyuUsername}
        aria-expanded={open}
        className={`flex h-7 w-7 items-center justify-center rounded-full text-[12px] font-black ring-2 transition-transform duration-150 hover:scale-105 max-sm:h-10 max-sm:w-10 max-sm:text-[15px] ${PLAN_COLORS[plan]}`}
      >
        {yuyuUsername.charAt(0).toUpperCase()}
      </button>

      {open && (
        <div className="absolute right-0 top-full z-20 mt-2 w-48 overflow-hidden rounded-xl border border-[rgba(255,255,255,0.1)] bg-[#111118] shadow-xl shadow-black/50">
          {/* Qui je suis et mon plan. */}
          <div className="flex items-center gap-2 border-b border-[rgba(255,255,255,0.07)] px-3 py-2.5">
            <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-white">{yuyuUsername}</span>
            <PlanBadge plan={plan} />
          </div>
          <button
            onClick={handleRefresh}
            disabled={refreshing}
            className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-[12px] text-[rgba(255,255,255,0.55)] transition-colors duration-150 hover:bg-[rgba(255,255,255,0.05)] hover:text-white disabled:cursor-not-allowed disabled:opacity-50"
          >
            {refreshing ? (
              <ButtonSpinner size={12} color="rgba(75,63,207,0.6)" trackColor="rgba(255,255,255,0.1)" />
            ) : (
              <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
                <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
              </svg>
            )}
            {t('plans.refresh')}
          </button>
          <button
            onClick={handleLogout}
            className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-[12px] text-[rgba(255,100,100,0.7)] transition-colors duration-150 hover:bg-[rgba(200,50,50,0.1)] hover:text-[rgb(248,113,113)]"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
              <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z" />
            </svg>
            {t('plans.logout')}
          </button>
        </div>
      )}
    </div>
  )
}
