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

export default function Stats() {
  const navigate = useNavigate()
  const { isPremium, yuyuPlan } = useStore()
  const premium = isPremium()
  const planLabel = yuyuPlan === 'ultimate' ? 'ULTIMATE' : 'PREMIUM'
  const planColor = yuyuPlan === 'ultimate' ? { color: '#f59e0b', bg: 'rgba(245,158,11,0.15)' } : { color: '#818cf8', bg: 'rgba(75,63,207,0.18)' }

  const [stats, setStats] = useState<StatsData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.stats.get()
      .then(setStats)
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }, [])

  const days = getLast14Days()
  const dailyMap = new Map(stats?.daily.map((d) => [d.date, d.secs]) ?? [])
  const maxDaySecs = Math.max(...days.map((d) => dailyMap.get(d) ?? 0), 1)

  const maxInstanceSecs = Math.max(...(stats?.per_instance.map((i) => i.total_secs) ?? []), 1)

  return (
    <div className="flex h-full flex-col overflow-hidden" style={{ background: '#09090D' }}>

      <PageHeader>
        <PageHeaderSeparator />
        <h1 className="font-black text-white" style={{ fontSize: 16, letterSpacing: '-0.01em' }}>
          Stats & Analytics
        </h1>

        <span style={{ fontSize: 10, fontWeight: 700, color: planColor.color, background: planColor.bg, padding: '2px 8px', borderRadius: 6, letterSpacing: '0.05em' }}>
          {planLabel}
        </span>
      </PageHeader>

      <div className="flex-1 overflow-auto">
      <div className="mx-auto w-full max-w-5xl px-6 py-8 flex flex-col gap-8">

        {/* Premium gate */}
        {!premium ? (
          <PremiumGate
            onUpgrade={() => navigate('/plans')}
            description="Les stats & analytics détaillées sont réservées aux abonnés Premium et Ultimate."
            features={[
              'Temps de jeu total & par modpack',
              'Historique des 20 dernières sessions',
              'Activité sur les 14 derniers jours',
              'Modpack et session favorites',
            ]}
          />
        ) : loading ? (
          <div className="flex items-center justify-center py-20">
            <ButtonSpinner size={32} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
          </div>
        ) : error ? (
          <div className="rounded-2xl px-5 py-4" style={{ background: 'rgba(200,50,50,0.08)', border: '1px solid rgba(200,50,50,0.2)' }}>
            <p style={{ fontSize: 13, color: 'rgb(248,113,113)' }}>{error}</p>
          </div>
        ) : stats && (
          <>
            {/* Top stat cards */}
            <div className="grid grid-cols-3 gap-4">
              <StatCard
                label="Temps de jeu total"
                value={formatDuration(stats.total_secs)}
                sub={stats.total_sessions === 0 ? 'Aucune session' : `${stats.total_sessions} session${stats.total_sessions > 1 ? 's' : ''}`}
                color="#818cf8"
              />
              <StatCard
                label="Moyenne par session"
                value={stats.total_sessions > 0 ? formatDuration(Math.round(stats.total_secs / stats.total_sessions)) : '—'}
                sub="Durée moyenne"
                color="#818cf8"
              />
              <StatCard
                label="Modpack favori"
                value={stats.per_instance[0]?.instance_name ?? '—'}
                sub={stats.per_instance[0] ? formatDuration(stats.per_instance[0].total_secs) : 'Aucune donnée'}
                color="#f59e0b"
              />
            </div>

            {/* 14-day activity */}
            <div className="rounded-2xl p-6 flex flex-col gap-4" style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.07)' }}>
              <span style={{ fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.08em' }}>ACTIVITÉ — 14 DERNIERS JOURS</span>
              <div className="flex items-end gap-1.5" style={{ height: 80 }}>
                {days.map((day) => {
                  const secs = dailyMap.get(day) ?? 0
                  const heightPct = secs > 0 ? Math.max(8, Math.round((secs / maxDaySecs) * 100)) : 0
                  const isToday = day === new Date().toISOString().split('T')[0]
                  return (
                    <div key={day} className="flex flex-1 flex-col items-center gap-1" title={secs > 0 ? `${day}: ${formatDuration(secs)}` : day}>
                      <div className="w-full flex items-end" style={{ height: 64 }}>
                        <div
                          className="w-full rounded-sm transition-all duration-300"
                          style={{
                            height: `${heightPct}%`,
                            minHeight: secs > 0 ? 4 : 0,
                            background: isToday
                              ? 'linear-gradient(180deg, #818cf8, rgba(75,63,207,0.6))'
                              : secs > 0
                              ? 'rgba(129,140,248,0.45)'
                              : 'rgba(255,255,255,0.04)',
                          }}
                        />
                      </div>
                      <span style={{ fontSize: 8, color: isToday ? '#818cf8' : 'rgba(255,255,255,0.2)', fontWeight: isToday ? 700 : 400 }}>
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
              <div className="rounded-2xl p-6 flex flex-col gap-4" style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.07)' }}>
                <span style={{ fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.08em' }}>PAR MODPACK</span>
                {stats.per_instance.length === 0 ? (
                  <EmptyState
                    compact
                    icon={
                      <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.1)" width={28} height={28}>
                        <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 3c1.93 0 3.5 1.57 3.5 3.5S13.93 13 12 13s-3.5-1.57-3.5-3.5S10.07 6 12 6zm7 13H5v-.23c0-.62.28-1.2.76-1.58C7.47 15.82 9.64 15 12 15s4.53.82 6.24 2.19c.48.38.76.97.76 1.58V19z" />
                      </svg>
                    }
                    title="Aucune session enregistrée"
                    subtitle="Lance une partie pour commencer"
                  />
                ) : (
                  <div className="flex flex-col gap-4">
                    {stats.per_instance.map((inst) => (
                      <div key={inst.instance_id} className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <span style={{ fontSize: 12, fontWeight: 600, color: 'rgba(255,255,255,0.8)' }}>{inst.instance_name}</span>
                            <span style={{ fontSize: 9, fontWeight: 700, color: loaderColor(inst.loader) }}>{inst.loader}</span>
                            <span style={{ fontSize: 9, color: 'rgba(255,255,255,0.25)' }}>{inst.mc_version}</span>
                          </div>
                          <span style={{ fontSize: 11, fontWeight: 600, color: '#818cf8' }}>{formatDuration(inst.total_secs)}</span>
                        </div>
                        <div className="h-1.5 w-full rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,0.06)' }}>
                          <div
                            className="h-full rounded-full"
                            style={{
                              width: `${Math.round((inst.total_secs / maxInstanceSecs) * 100)}%`,
                              background: 'linear-gradient(90deg, rgba(75,63,207,0.8), #818cf8)',
                              transition: 'width 0.4s ease',
                            }}
                          />
                        </div>
                        <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.25)' }}>
                          {inst.sessions} session{inst.sessions > 1 ? 's' : ''}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Recent sessions */}
              <div className="rounded-2xl p-6 flex flex-col gap-4" style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.07)' }}>
                <span style={{ fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.08em' }}>SESSIONS RÉCENTES</span>
                {stats.recent_sessions.length === 0 ? (
                  <EmptyState
                    compact
                    icon={
                      <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.1)" width={28} height={28}>
                        <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 3c1.93 0 3.5 1.57 3.5 3.5S13.93 13 12 13s-3.5-1.57-3.5-3.5S10.07 6 12 6zm7 13H5v-.23c0-.62.28-1.2.76-1.58C7.47 15.82 9.64 15 12 15s4.53.82 6.24 2.19c.48.38.76.97.76 1.58V19z" />
                      </svg>
                    }
                    title="Aucune session enregistrée"
                    subtitle="Lance une partie pour commencer"
                  />
                ) : (
                  <div className="flex flex-col gap-2 overflow-auto" style={{ maxHeight: 280 }}>
                    {stats.recent_sessions.map((s, i) => (
                      <div
                        key={i}
                        className="flex items-center justify-between rounded-xl px-3 py-2.5"
                        style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.05)' }}
                      >
                        <div className="flex flex-col gap-0.5">
                          <span style={{ fontSize: 12, fontWeight: 600, color: 'rgba(255,255,255,0.75)' }}>{s.instance_name}</span>
                          <div className="flex items-center gap-1.5">
                            <span style={{ fontSize: 9, color: loaderColor(s.loader), fontWeight: 700 }}>{s.loader}</span>
                            <span style={{ fontSize: 9, color: 'rgba(255,255,255,0.2)' }}>·</span>
                            <span style={{ fontSize: 9, color: 'rgba(255,255,255,0.3)' }}>{formatShortDate(s.started_at)} à {formatTime(s.started_at)}</span>
                          </div>
                        </div>
                        <span
                          className="rounded-lg px-2 py-0.5"
                          style={{ fontSize: 11, fontWeight: 700, color: '#818cf8', background: 'rgba(75,63,207,0.15)', flexShrink: 0 }}
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

function StatCard({ label, value, sub, color }: { label: string; value: string; sub: string; color: string }) {
  return (
    <div
      className="flex flex-col gap-2 rounded-2xl p-5"
      style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.07)' }}
    >
      <span style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.35)', letterSpacing: '0.08em', textTransform: 'uppercase' }}>{label}</span>
      <span className="font-black" style={{ fontSize: 26, color, letterSpacing: '-0.02em', lineHeight: 1 }}>{value}</span>
      <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.3)' }}>{sub}</span>
    </div>
  )
}

