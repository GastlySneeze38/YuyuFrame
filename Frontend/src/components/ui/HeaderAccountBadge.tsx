import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { YuyuPlan } from '@/stores/useStore'
import { showApiError } from '@/stores/useErrorToast'
import { PlanBadge } from '@/components/plans/PlanBadge'
import { ButtonSpinner } from './ButtonSpinner'
import { useT } from '@/i18n'

/** Pastille compte + plan affichée à droite de `PageHeader` sur toutes les
 * pages sauf Home (qui a déjà sa propre zone compte dans sa nav) — même
 * esprit que le bouton compte du site web (avatar + pseudo, menu déroulant
 * pour rafraîchir/se déconnecter), remplace l'ancien bloc vertical
 * (badge + lien + lien empilés) qui prenait toute la hauteur du header. */
export function HeaderAccountBadge() {
  const t = useT()
  const navigate = useNavigate()
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

  if (!yuyuUsername) {
    return (
      <button
        onClick={() => navigate('/yuyu')}
        className="ml-auto rounded-lg px-3 py-1.5 text-[11px] font-semibold transition-all duration-150 bg-[rgba(75,63,207,0.18)] border border-[rgba(75,63,207,0.35)] text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.3)]"
      >
        {t('plans.loginCta')}
      </button>
    )
  }

  const effectivePlan = isUltimate() ? 'ultimate' : isPremium() ? 'premium' : 'free'

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
    // yuyu_logout est purement local (JWT stateless, rien à révoquer côté
    // serveur) — vide la session locale (DB + mémoire) côté Backend.
    await api.yuyu.logout().catch(() => {})
    clearYuyuSession()
    setOpen(false)
  }

  return (
    <div ref={ref} className="relative ml-auto flex-shrink-0">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 rounded-lg border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] pl-1.5 pr-2 py-1 transition-all duration-150 hover:border-[rgba(255,255,255,0.18)] hover:bg-[rgba(255,255,255,0.05)]"
      >
        <span className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-[rgba(75,63,207,0.35)] text-[9px] font-bold text-[#c4b8ff]">
          {yuyuUsername.charAt(0).toUpperCase()}
        </span>
        <span className="max-w-[90px] truncate text-[11px] font-semibold text-[rgba(255,255,255,0.7)]">
          {yuyuUsername}
        </span>
        <PlanBadge plan={effectivePlan} />
      </button>

      {open && (
        <div className="absolute right-0 top-full z-10 mt-2 w-44 overflow-hidden rounded-xl border border-[rgba(255,255,255,0.1)] bg-[#111118] shadow-xl shadow-black/50">
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
