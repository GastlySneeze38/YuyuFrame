import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Instance, SyncInstance } from '@/types'
import { PremiumGate } from '@/components/ui/PremiumGate'
import { InstanceSyncCard } from '@/components/sync/InstanceSyncCard'
import { OrphanCloudCard } from '@/components/sync/OrphanCloudCard'
import { PageHeader } from '@/components/ui/PageHeader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError, showApiError } from '@/stores/useErrorToast'
import { isNetworkError } from '@/lib/apiError'
import { SYNC_ENABLED, SYNC_FLAG } from '@/config/features'
import { useFleet } from '@/stores/useFleet'
import { SNAP, listItemVariants, listVariants, press } from '@/lib/motion'
import { useT } from '@/i18n'

/// Au-delà de ce nombre d'instances, la liste se replie et une recherche
/// apparaît. Six tient dans un écran sans dérouler.
const COLLAPSE_ABOVE = 6

// ── Sync content ──────────────────────────────────────────────────────────────

function SyncContent() {
  const t = useT()
  const { instances, yuyuSignedIn, isUltimate, addInstance } = useStore()
  const userIsUltimate = isUltimate()

  const [cloudInstances, setCloudInstances] = useState<SyncInstance[]>([])
  const [cloudLoading, setCloudLoading] = useState(false)
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState(false)
  const cloudLoaded = useRef(false)

  useEffect(() => {
    api.analytics.track('sync_page_viewed')
  }, [])

  useEffect(() => {
    if (!yuyuSignedIn || cloudLoaded.current) return
    cloudLoaded.current = true
    setCloudLoading(true)
    api.sync.list()
      .then(setCloudInstances)
      // Chargement automatique au montage — une panne réseau est déjà
      // signalée par le badge de la TitleBar, pas la peine d'un toast en plus
      // à chaque visite de la page hors-ligne. Les vraies erreurs restent
      // remontées normalement.
      .catch((e) => { if (!isNetworkError(e)) showError(e) })
      .finally(() => setCloudLoading(false))
  }, [yuyuSignedIn])

  const handleCloudUpdate = (updated: SyncInstance) => {
    setCloudInstances((prev) => {
      const idx = prev.findIndex((ci) => ci.id === updated.id)
      return idx >= 0
        ? prev.map((ci, i) => (i === idx ? updated : ci))
        : [updated, ...prev]
    })
  }

  const handleCloudDelete = (id: number) => {
    setCloudInstances((prev) => prev.filter((ci) => ci.id !== id))
  }

  /// Suppression d'une entrée cloud "orpheline" (sans instance locale) — appelle
  /// bien l'API avant de retirer l'entrée localement (bug corrigé : la version
  /// précédente ne faisait que la retirer du state, sans jamais la supprimer
  /// côté serveur, donc elle réapparaissait au rechargement).
  const handleOrphanDelete = async (id: number) => {
    try {
      await api.sync.delete(id)
      handleCloudDelete(id)
    } catch (e) { showApiError(e, t('common.serverUnreachable')) }
  }

  const handleRestore = async (ci: SyncInstance) => {
    try {
      const newInstance = await api.instances.create(ci.instance_name, ci.mc_version, ci.loader, ci.ram_mb)
      addInstance(newInstance)
      await api.sync.pull(ci.id, newInstance.id)
    } catch (e) { showApiError(e, t('common.serverUnreachable')) }
  }

  const orphanCloud = cloudInstances.filter(
    (ci) => !instances.some((i) => i.name === ci.instance_name)
  )

  // Ordre et repliage. Avec vingt instances, la liste d'avant demandait de
  // dérouler tout l'écran pour retrouver celle qu'on synchronise vraiment.
  // On remonte donc ce qui compte — les favorites d'abord, puis ce qui est
  // déjà sur le serveur — et on replie le reste.
  const { shown, hidden } = useMemo(() => {
    const synced = new Set(cloudInstances.map((ci) => ci.instance_name))
    const needle = query.trim().toLowerCase()
    const matching = needle
      ? instances.filter((i) => i.name.toLowerCase().includes(needle) || i.mc_version.includes(needle))
      : instances

    const rank = (i: Instance) => (i.favorite ? 0 : synced.has(i.name) ? 1 : 2)
    const ordered = [...matching].sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name))

    // Une recherche en cours montre tout ce qu'elle trouve : replier des
    // résultats qu'on vient de demander serait absurde.
    if (needle || expanded || ordered.length <= COLLAPSE_ABOVE) {
      return { shown: ordered, hidden: 0 }
    }
    // On ne coupe jamais au milieu de ce qui est mis en avant : si les
    // favorites et les instances déjà synchronisées dépassent le seuil, elles
    // restent toutes visibles.
    const promoted = ordered.filter((i) => rank(i) < 2).length
    const cut = Math.max(COLLAPSE_ABOVE, promoted)
    return { shown: ordered.slice(0, cut), hidden: ordered.length - cut }
  }, [instances, cloudInstances, query, expanded])

  if (cloudLoading) {
    return (
      <div className="flex justify-center py-8">
        <ButtonSpinner size={20} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.08)" />
      </div>
    )
  }

  if (instances.length === 0 && orphanCloud.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-8">
        <div className="text-[24px] opacity-[0.18]">🧱</div>
        <p className="text-[12px] text-[rgba(255,255,255,0.2)] text-center">
          {t('sync.createInstanceLine1')}<br />{t('sync.createInstanceLine2')}
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      {/* Instances locales, rangées et repliées (voir `arrange`). */}
      {instances.length > COLLAPSE_ABOVE && (
        <div className="relative mb-1">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-txt-muted"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('sync.searchPlaceholder')}
            className="h-9 w-full rounded-xl border border-line bg-surface-1 pl-9 pr-3 text-[12px] text-txt-primary outline-none transition-colors duration-150 placeholder:text-txt-muted focus:border-accent/45"
          />
        </div>
      )}

      <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-2">
        <AnimatePresence initial={false} mode="popLayout">
          {shown.map((inst) => (
            <motion.div key={inst.id} variants={listItemVariants} layout initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }} transition={SNAP}>
              <InstanceSyncCard
                instance={inst}
                cloudEntry={cloudInstances.find((ci) => ci.instance_name === inst.name)}
                onCloudUpdate={handleCloudUpdate}
                onCloudDelete={handleCloudDelete}
              />
            </motion.div>
          ))}
        </AnimatePresence>
      </motion.div>

      {hidden > 0 && (
        <motion.button
          {...press}
          onClick={() => setExpanded((v) => !v)}
          className="self-center py-1 text-[11px] font-semibold text-txt-muted transition-colors duration-150 hover:text-accent-hover"
        >
          {expanded ? t('sync.showLess') : t('sync.showAll', { count: hidden })}
        </motion.button>
      )}

      {/* Orphan cloud entries */}
      {orphanCloud.length > 0 && (
        <motion.div variants={listVariants} initial="initial" animate="animate" className="mt-2 flex flex-col gap-2">
          <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.18)]">
            {t('sync.cloudNoLocalInstance')}
          </p>
          {orphanCloud.map((ci) => (
            <motion.div key={ci.id} variants={listItemVariants} layout>
              <OrphanCloudCard ci={ci} onRestore={handleRestore} onDelete={handleOrphanDelete} />
            </motion.div>
          ))}
        </motion.div>
      )}

      <p className="mt-1 text-center text-[10px] text-[rgba(255,255,255,0.1)]">
        {t('sync.quotaFooter', { quota: userIsUltimate ? 20 : 5 })}
      </p>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function Sync() {
  const t = useT()
  const navigate = useNavigate()
  const { yuyuSignedIn, isPremium } = useStore()
  // Deux verrous : celui du code (réécriture en cours) et celui du
  // back-office, qui permet de couper la sync à distance en cas d'incident.
  const allowedByFleet = useFleet((s) => s.isEnabled(SYNC_FLAG))
  const fleetMessage = useFleet((s) => s.flagMessage(SYNC_FLAG))

  if (!SYNC_ENABLED || !allowedByFleet) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-[#09090D]">
        <div className="text-[32px] opacity-[0.15]">
          <svg viewBox="0 0 24 24" fill="white" width={48} height={48}><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>
        </div>
        <p className="text-[14px] font-bold text-[rgba(255,255,255,0.5)]">{t('sync.comingSoon')}</p>
        <p className="text-[11px] text-[rgba(255,255,255,0.2)] text-center max-w-[280px]">
          {/* Message du back-office s'il a coupé la sync, sinon le texte par défaut. */}
          {(!allowedByFleet && fleetMessage) || t('sync.comingSoonDesc')}
        </p>
        <button
          onClick={() => navigate('/home')}
          className="rounded-xl px-5 py-2 text-sm font-semibold transition-all duration-150 bg-[rgba(75,63,207,0.18)] border border-[rgba(75,63,207,0.35)] text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.3)]"
        >
          {t('sync.back')}
        </button>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">
      <PageHeader px={5}>
        <h1 className="font-black text-white text-[16px] tracking-[-0.01em]">
          {t('sync.title')}
        </h1>
      </PageHeader>

      <div className="flex-1 overflow-y-auto p-5">
        {!yuyuSignedIn ? (
          <div className="flex flex-col items-center justify-center gap-3 py-10">
            <div className="text-[28px] opacity-20">🔒</div>
            <p className="text-[13px] text-[rgba(255,255,255,0.3)] font-semibold text-center">
              {t('sync.loginToSyncLine1')}<br />{t('sync.loginToSyncLine2')}
            </p>
            <button
              onClick={() => navigate('/yuyu')}
              className="rounded-xl px-5 py-2 text-sm font-semibold transition-all duration-150 bg-[rgba(75,63,207,0.18)] border border-[rgba(75,63,207,0.35)] text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.3)]"
            >
              {t('sync.loginCta')}
            </button>
          </div>
        ) : !isPremium() ? (
          <PremiumGate
            compact
            onUpgrade={() => navigate('/plans')}
            description={t('sync.gateDescription')}
            features={[
              t('sync.gateFeature1'),
              t('sync.gateFeature2'),
              t('sync.gateFeature3'),
            ]}
          />
        ) : (
          <SyncContent />
        )}
      </div>
    </div>
  )
}
