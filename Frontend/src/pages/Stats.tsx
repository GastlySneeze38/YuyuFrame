import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { StatsData } from '@/types'
import { loaderColor } from '@/lib/loader'
import { formatDuration, formatShortDate, formatTime, getLast14Days, formatDayLabel } from '@/lib/format'
import { PremiumGate } from '@/components/ui/PremiumGate'
import { EmptyState } from '@/components/ui/EmptyState'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

export default function Stats() {
  const t = useT()
  const navigate = useNavigate()
  const { isPremium } = useStore()
  const premium = isPremium()

  const [stats, setStats] = useState<StatsData | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.stats.get()
      .then(setStats)
      .catch(showError)
      .finally(() => setLoading(false))
  }, [])

  const days = getLast14Days()
  const dailyMap = new Map(stats?.daily.map((d) => [d.date, d.secs]) ?? [])
  const maxDaySecs = Math.max(...days.map((d) => dailyMap.get(d) ?? 0), 1)

  const maxInstanceSecs = Math.max(...(stats?.per_instance.map((i) => i.total_secs) ?? []), 1)

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">

      <PageHeader>
        <PageHeaderSeparator />
        <h1 className="font-black text-white text-[16px] tracking-[-0.01em]">
          Stats & Analytics
        </h1>
      </PageHeader>

      <div className="flex-1 overflow-auto">
      <div className="mx-auto w-full max-w-5xl px-6 py-8 flex flex-col gap-8">

        {/* Premium gate */}
        {!premium ? (
          <PremiumGate
            onUpgrade={() => navigate('/plans')}
            description={t('stats.gateDescription')}
            features={[
              t('stats.gateFeature1'),
              t('stats.gateFeature2'),
              t('stats.gateFeature3'),
              t('stats.gateFeature4'),
            ]}
          />
        ) : loading ? (
          <div className="flex items-center justify-center py-20">
            <ButtonSpinner size={32} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
          </div>
        ) : !stats ? (
          <div className="flex flex-col items-center justify-center py-20 gap-2">
            <p className="text-[13px] text-white/30">{t('stats.cannotLoadStats')}</p>
          </div>
        ) : (
          <>
            {/* Top stat cards */}
            <div className="grid grid-cols-3 gap-4">
              <StatCard
                delay={0}
                label={t('stats.totalPlaytime')}
                value={formatDuration(stats.total_secs)}
                sub={stats.total_sessions === 0 ? t('stats.noSession') : t('stats.sessionsCount', { count: stats.total_sessions, s: stats.total_sessions > 1 ? 's' : '' })}
                color="#818cf8"
              />
              <StatCard
                delay={70}
                label={t('stats.averagePerSession')}
                value={stats.total_sessions > 0 ? formatDuration(Math.round(stats.total_secs / stats.total_sessions)) : '—'}
                sub={t('stats.averageDuration')}
                color="#818cf8"
              />
              <StatCard
                delay={140}
                compact
                label={t('stats.favoriteModpack')}
                value={stats.per_instance[0]?.instance_name ?? '—'}
                sub={stats.per_instance[0] ? formatDuration(stats.per_instance[0].total_secs) : t('stats.noData')}
                color="#f59e0b"
              />
            </div>

            {/* 14-day activity */}
            <div
              className="rounded-2xl p-6 flex flex-col gap-4 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.07)] transition-colors duration-200 hover:border-[rgba(255,255,255,0.12)] animate-fade-in-up"
              style={{ animationDelay: '210ms' }}
            >
              <span className="text-[11px] font-bold text-white/40 tracking-[0.08em]">{t('stats.activity14Days')}</span>
              <div className="flex items-end gap-1.5 h-20">
                {days.map((day, i) => {
                  const secs = dailyMap.get(day) ?? 0
                  const heightPct = secs > 0 ? Math.max(8, Math.round((secs / maxDaySecs) * 100)) : 0
                  const isToday = day === new Date().toISOString().split('T')[0]
                  const barClasses = isToday
                    ? 'bg-gradient-to-b from-[#818cf8] to-[rgba(75,63,207,0.6)]'
                    : secs > 0
                    ? 'bg-[rgba(129,140,248,0.45)] group-hover/bar:bg-[rgba(129,140,248,0.7)]'
                    : 'bg-[rgba(255,255,255,0.04)]'
                  return (
                    <div key={day} className="group/bar flex flex-1 flex-col items-center gap-1" title={secs > 0 ? `${day}: ${formatDuration(secs)}` : day}>
                      <div className="w-full flex items-end h-16">
                        <div
                          className={`w-full origin-bottom animate-grow-y rounded-sm transition-colors duration-200 ${secs > 0 ? 'min-h-1' : 'min-h-0'} ${barClasses}`}
                          style={{ height: `${heightPct}%`, animationDelay: `${260 + i * 25}ms` }}
                        />
                      </div>
                      <span className={`text-[8px] ${isToday ? 'text-[#818cf8] font-bold' : 'text-white/20 font-normal'}`}>
                        {formatDayLabel(day)}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>

            {/* Bottom two columns */}
            <div className="grid grid-cols-2 gap-5">

              {/* Per-instance breakdown */}
              <div
                className="rounded-2xl p-6 flex flex-col gap-4 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.07)] transition-colors duration-200 hover:border-[rgba(255,255,255,0.12)] animate-fade-in-up"
                style={{ animationDelay: '280ms' }}
              >
                <span className="text-[11px] font-bold text-white/40 tracking-[0.08em]">{t('stats.byModpack')}</span>
                {stats.per_instance.length === 0 ? (
                  <EmptyState
                    compact
                    icon={
                      <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.1)" width={28} height={28}>
                        <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 3c1.93 0 3.5 1.57 3.5 3.5S13.93 13 12 13s-3.5-1.57-3.5-3.5S10.07 6 12 6zm7 13H5v-.23c0-.62.28-1.2.76-1.58C7.47 15.82 9.64 15 12 15s4.53.82 6.24 2.19c.48.38.76.97.76 1.58V19z" />
                      </svg>
                    }
                    title={t('stats.noSessionRecorded')}
                    subtitle={t('stats.launchToStart')}
                  />
                ) : (
                  <div className="flex flex-col gap-4">
                    {stats.per_instance.map((inst, i) => (
                      <div key={inst.instance_id} className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex min-w-0 items-center gap-2">
                            <span className="truncate text-[12px] font-semibold text-white/80" title={inst.instance_name}>{inst.instance_name}</span>
                            <span className="flex-shrink-0 text-[9px] font-bold" style={{ color: loaderColor(inst.loader) }}>{inst.loader}</span>
                            <span className="flex-shrink-0 text-[9px] text-white/25">{inst.mc_version}</span>
                          </div>
                          <span className="flex-shrink-0 text-[11px] font-semibold text-[#818cf8]">{formatDuration(inst.total_secs)}</span>
                        </div>
                        <div className="h-1.5 w-full rounded-full overflow-hidden bg-[rgba(255,255,255,0.06)]">
                          <div
                            className="h-full origin-left animate-grow-x rounded-full bg-gradient-to-r from-[rgba(75,63,207,0.8)] to-[#818cf8]"
                            style={{ width: `${Math.round((inst.total_secs / maxInstanceSecs) * 100)}%`, animationDelay: `${340 + i * 60}ms` }}
                          />
                        </div>
                        <span className="text-[10px] text-white/25">
                          {t('stats.sessionsCount', { count: inst.sessions, s: inst.sessions > 1 ? 's' : '' })}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Recent sessions */}
              <div
                className="rounded-2xl p-6 flex flex-col gap-4 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.07)] transition-colors duration-200 hover:border-[rgba(255,255,255,0.12)] animate-fade-in-up"
                style={{ animationDelay: '280ms' }}
              >
                <span className="text-[11px] font-bold text-white/40 tracking-[0.08em]">{t('stats.recentSessions')}</span>
                {stats.recent_sessions.length === 0 ? (
                  <EmptyState
                    compact
                    icon={
                      <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.1)" width={28} height={28}>
                        <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 3c1.93 0 3.5 1.57 3.5 3.5S13.93 13 12 13s-3.5-1.57-3.5-3.5S10.07 6 12 6zm7 13H5v-.23c0-.62.28-1.2.76-1.58C7.47 15.82 9.64 15 12 15s4.53.82 6.24 2.19c.48.38.76.97.76 1.58V19z" />
                      </svg>
                    }
                    title={t('stats.noSessionRecorded')}
                    subtitle={t('stats.launchToStart')}
                  />
                ) : (
                  <div className="flex flex-col gap-2 overflow-auto max-h-[280px]">
                    {stats.recent_sessions.map((s, i) => (
                      <div
                        key={i}
                        className="flex items-center justify-between rounded-xl px-3 py-2.5 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.05)] transition-all duration-150 hover:bg-[rgba(255,255,255,0.05)] hover:border-[rgba(129,140,248,0.25)] hover:translate-x-0.5"
                      >
                        <div className="flex min-w-0 flex-col gap-0.5">
                          <span className="truncate text-[12px] font-semibold text-white/75" title={s.instance_name}>{s.instance_name}</span>
                          <div className="flex items-center gap-1.5">
                            <span className="flex-shrink-0 text-[9px] font-bold" style={{ color: loaderColor(s.loader) }}>{s.loader}</span>
                            <span className="flex-shrink-0 text-[9px] text-white/20">·</span>
                            <span className="truncate text-[9px] text-white/30">{t('stats.dateAtTime', { date: formatShortDate(s.started_at), time: formatTime(s.started_at) })}</span>
                          </div>
                        </div>
                        <span
                          className="rounded-lg px-2 py-0.5 text-[11px] font-bold text-[#818cf8] bg-[rgba(75,63,207,0.15)] flex-shrink-0"
                        >
                          {formatDuration(s.duration_secs)}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

            </div>
          </>
        )}
      </div>
      </div>
    </div>
  )
}

// ── Sub-components ─────────────────────────────────────────────────────────────

function StatCard({ label, value, sub, color, delay = 0, compact = false }: { label: string; value: string; sub: string; color: string; delay?: number; compact?: boolean }) {
  return (
    <div
      className="flex min-w-0 flex-col gap-2 rounded-2xl p-5 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.07)] transition-all duration-200 hover:-translate-y-0.5 hover:border-[rgba(255,255,255,0.14)] animate-fade-in-up"
      style={{ animationDelay: `${delay}ms` }}
    >
      <span className="text-[10px] font-bold text-white/35 tracking-[0.08em] uppercase truncate">{label}</span>
      <span
        className={`block truncate font-black tracking-[-0.02em] leading-tight ${compact ? 'text-[19px]' : 'text-[28px] leading-none'}`}
        style={{ color }}
        title={value}
      >
        {value}
      </span>
      <span className="text-[11px] text-white/30 truncate">{sub}</span>
    </div>
  )
}
