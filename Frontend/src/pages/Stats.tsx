import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { Button } from '@/components/ui/Button'
import { ModalShell } from '@/components/ui/ModalShell'
import { StatsToolbar, EMPTY_FILTERS, toQuery, type StatsFilters } from '@/components/stats/StatsToolbar'
import { StatCards } from '@/components/stats/StatCards'
import { ActivityCalendar } from '@/components/stats/ActivityCalendar'
import { HourlyChart } from '@/components/stats/HourlyChart'
import { InstanceRanking } from '@/components/stats/InstanceRanking'
import { SessionList } from '@/components/stats/SessionList'
import { Breakdown } from '@/components/stats/Breakdown'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError } from '@/stores/useErrorToast'
import { useStore, DEFAULT_STAT_CARDS, type StatCardId } from '@/stores/useStore'
import { SNAP, press } from '@/lib/motion'
import { useT } from '@/i18n'
import type { StatsData } from '@/types/stats'

/**
 * Statistiques de jeu.
 *
 * Refonte complète du 2026-09-21, interface et calcul. Ce qui a changé au
 * fond, et qui ne se voit pas :
 *
 * - les données sont **locales** : se déconnecter ne les fait plus
 *   disparaître, alors qu'elles n'ont jamais quitté ce PC ;
 * - une partie à cheval sur minuit est répartie sur les deux jours ;
 * - une partie **en cours** est comptée pendant qu'on joue ;
 * - une partie dont le launcher n'a pas vu la fin n'est plus perdue.
 *
 * Et ce qui se voit : une période, des filtres, des tris, et des cartes qu'on
 * choisit soi-même.
 */

/** Une partie en cours fait monter le compteur : on rafraîchit doucement. */
const LIVE_REFRESH_MS = 30_000

export default function Stats() {
  const t = useT()
  const rangeDays = useStore((s) => s.statsRangeDays)
  const setRangeDays = useStore((s) => s.setStatsRangeDays)
  const cards = useStore((s) => s.statsCards)
  const setCards = useStore((s) => s.setStatsCards)
  const sort = useStore((s) => s.statsInstanceSort)
  const setSort = useStore((s) => s.setStatsInstanceSort)

  const [data, setData] = useState<StatsData | null>(null)
  const [loading, setLoading] = useState(true)
  const [filters, setFilters] = useState<StatsFilters>(EMPTY_FILTERS)
  const [customizing, setCustomizing] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  // Première session connue : mémorisée pour que « Tout » ne reparte pas de
  // l'époque Unix à chaque changement de filtre.
  const firstSeen = useRef<number | null>(null)

  const load = useCallback(
    async (silent = false) => {
      if (!silent) setLoading(true)
      try {
        const next = await api.stats.get(toQuery(rangeDays, filters, firstSeen.current))
        firstSeen.current = next.first_session_at
        setData(next)
      } catch (e) {
        showError(errorMessage(e))
      } finally {
        setLoading(false)
      }
    },
    [rangeDays, filters],
  )

  useEffect(() => {
    load()
  }, [load])

  // Rafraîchissement discret tant qu'une partie tourne — et seulement dans ce
  // cas : sonder la base toutes les trente secondes pour des chiffres qui ne
  // bougent pas serait du gaspillage pur.
  const live = (data?.running.length ?? 0) > 0
  useEffect(() => {
    if (!live) return
    const timer = setInterval(() => load(true), LIVE_REFRESH_MS)
    return () => clearInterval(timer)
  }, [live, load])

  function toggleCard(id: StatCardId) {
    const next = cards.includes(id) ? cards.filter((c) => c !== id) : [...cards, id]
    // Jamais zéro carte : une rangée vide ne ressemble pas à un choix, elle
    // ressemble à un bug.
    setCards(next.length > 0 ? next : DEFAULT_STAT_CARDS)
  }

  async function clearHistory() {
    try {
      await api.stats.clear()
      firstSeen.current = null
      setConfirmClear(false)
      await load()
    } catch (e) {
      showError(errorMessage(e))
    }
  }

  const empty = data !== null && data.totals.sessions === 0 && data.known_instances.length === 0

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{t('stats.title')}</h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('stats.subtitle')}</p>
        </div>
      </PageHeader>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-6">
          <StatsToolbar
            rangeDays={rangeDays}
            onRange={setRangeDays}
            filters={filters}
            onFilters={setFilters}
            data={data}
            customizing={customizing}
            onCustomize={() => setCustomizing((v) => !v)}
          />

          {loading && data === null ? (
            <div className="flex items-center justify-center py-24">
              <ButtonSpinner size={32} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            </div>
          ) : !data ? (
            <p className="py-24 text-center text-[13px] text-txt-muted">{t('stats.cannotLoadStats')}</p>
          ) : empty ? (
            <div className="flex flex-col items-center justify-center gap-2 py-24 text-center">
              <p className="text-[13px] font-semibold">{t('stats.emptyTitle')}</p>
              <p className="max-w-md text-[11.5px] leading-relaxed text-txt-secondary">{t('stats.emptyText')}</p>
            </div>
          ) : (
            <>
              {/* Ce qui tourne maintenant, en premier : c'est la seule ligne
                  de la page qui change pendant qu'on la regarde. */}
              <AnimatePresence initial={false}>
                {data.running.length > 0 && (
                  <motion.div
                    initial={{ opacity: 0, y: -6 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -6 }}
                    transition={SNAP}
                    className="flex items-center gap-2.5 rounded-xl border border-success/30 bg-success/[0.07] px-3.5 py-2.5"
                  >
                    <motion.span
                      className="h-1.5 w-1.5 rounded-full bg-success"
                      animate={{ opacity: [1, 0.3, 1] }}
                      transition={{ duration: 1.6, repeat: Infinity }}
                    />
                    <span className="text-[12px] font-semibold text-success">
                      {t('stats.liveNow', { names: data.running.map((r) => r.instance_name).join(', ') })}
                    </span>
                  </motion.div>
                )}
              </AnimatePresence>

              <StatCards data={data} selected={cards} customizing={customizing} onToggle={toggleCard} />

              <ActivityCalendar daily={data.daily} />

              {/* Le classement prend plus large que les deux graphiques :
                  ses lignes portent un nom d'instance, un loader, une version
                  et une durée, là où une barre horaire n'a besoin que de sa
                  hauteur. */}
              <div className="grid grid-cols-[1.4fr_1fr] gap-5">
                <InstanceRanking
                  instances={data.per_instance}
                  sort={sort}
                  onSort={setSort}
                  onPick={(instanceId) =>
                    setFilters((f) => ({ ...f, instanceId: f.instanceId === instanceId ? '' : instanceId }))
                  }
                />
                <div className="flex min-w-0 flex-col gap-5">
                  <HourlyChart hourly={data.hourly} />
                  <Breakdown loaders={data.per_loader} versions={data.per_version} />
                </div>
              </div>

              <SessionList sessions={data.recent} />

              {/* En bas, discret : effacer son historique est un droit, pas
                  une action qu'on met sous le nez. */}
              <div className="flex justify-end pb-2">
                <motion.button
                  {...press}
                  onClick={() => setConfirmClear(true)}
                  className="text-[11px] text-txt-muted transition-colors duration-150 hover:text-danger"
                >
                  {t('stats.clear.action')}
                </motion.button>
              </div>
            </>
          )}
        </div>
      </div>

      <AnimatePresence>
        {confirmClear && (
          <ModalShell onClose={() => setConfirmClear(false)} title={t('stats.clear.title')}>
            <div className="flex flex-col gap-4">
              <p className="text-[12px] leading-relaxed text-txt-secondary">{t('stats.clear.text')}</p>
              <div className="flex justify-end gap-2">
                <Button size="sm" variant="ghost" onClick={() => setConfirmClear(false)}>
                  {t('common.cancel')}
                </Button>
                <Button size="sm" variant="danger" onClick={clearHistory}>
                  {t('stats.clear.confirm')}
                </Button>
              </div>
            </div>
          </ModalShell>
        )}
      </AnimatePresence>
    </div>
  )
}
