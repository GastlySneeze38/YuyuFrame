import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { SNAP } from '@/lib/motion'
import { scoreColor, serverStats, type DuelRow } from '@/lib/competitive'
import { ServerAvatar } from './ServerStats'
import { formatLength } from './DuelHistory'
import { TopoBackdrop } from './TopoBackdrop'

/** Duels montrés par le graphique des notes. */
export const CHART_DUELS = 30
/** Résultats montrés par la bande « forme ». */
export const FORM_DUELS = 10
/** En dessous, un serveur n'a pas assez de duels pour être « le meilleur ». */
const MIN_SERVER_DUELS = 3

export const WIN = '74 222 128'
export const LOSS = '239 68 68'
export const DRAW = '160 150 190'

/** Tout ce que l'écran montre, calculé depuis l'historique d'un mode. */
export function summarize(duels: DuelRow[]) {
  const wins = duels.filter((d) => d.result === 'win')
  const losses = duels.filter((d) => d.result === 'loss').length
  const draws = duels.length - wins.length - losses
  const avg = (list: number[]) => (list.length ? Math.round(list.reduce((a, b) => a + b, 0) / list.length) : 0)

  // L'historique va du plus récent au plus ancien.
  let current = 0
  for (const d of duels) {
    if (d.result !== 'win') break
    current++
  }
  let best = 0
  let run = 0
  for (const d of [...duels].reverse()) {
    run = d.result === 'win' ? run + 1 : 0
    best = Math.max(best, run)
  }

  const servers = serverStats(duels)
  const bestServer =
    [...servers].filter((s) => s.duels >= MIN_SERVER_DUELS).sort((a, b) => b.avgScore - a.avgScore)[0] ?? null

  const rivals = new Map<string, number>()
  for (const d of duels) rivals.set(d.opponent, (rivals.get(d.opponent) ?? 0) + 1)
  const rival = [...rivals.entries()].sort((a, b) => b[1] - a[1])[0] ?? null

  return {
    played: duels.length,
    wins: wins.length,
    losses,
    draws,
    winRate: duels.length ? Math.round((wins.length / duels.length) * 100) : 0,
    avgScore: avg(duels.map((d) => d.score)),
    bestScore: Math.max(0, ...duels.map((d) => d.score)),
    currentStreak: current,
    bestStreak: best,
    bestServer,
    serverCount: servers.length,
    avgDuration: avg(duels.map((d) => d.durationSecs)),
    fastestWin: wins.length ? Math.min(...wins.map((d) => d.durationSecs)) : null,
    rival: rival && rival[1] > 1 ? { name: rival[0], count: rival[1] } : null,
  }
}

/**
 * Écran Statistiques d'un mode : quatre chiffres en tête (victoires, note
 * moyenne, meilleur serveur, série), puis les notes des derniers duels et un
 * résumé. Tout vient de l'historique ; `duels === null` : pas encore de route.
 */
export function StatsView({ duels }: { duels: DuelRow[] | null }) {
  const t = useT()

  if (!duels || duels.length === 0) {
    return (
      <div className="flex h-full items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
        <p className="max-w-xs text-center text-[12px] leading-relaxed text-txt-muted">
          {!duels ? t('competitive.stats.soon') : t('competitive.history.empty')}
        </p>
      </div>
    )
  }

  const s = summarize(duels)
  const avgColor = scoreColor(s.avgScore)

  return (
    <div className="grid h-full min-h-0 grid-rows-[auto_minmax(0,1fr)] gap-4">
      <div className="grid grid-cols-4 gap-4">
        {/* Taux de victoire : un anneau, et le bilan en dessous. */}
        <Tile index={0} label={t('competitive.stats.winRate')}>
          <div className="flex items-center gap-4">
            <Ring value={s.winRate / 100} />
            <div className="flex min-w-0 flex-col gap-1.5">
              <span className="text-[30px] font-black leading-none tabular-nums">{s.winRate} %</span>
              <span className="text-[11px] tabular-nums text-txt-muted">
                <span style={{ color: `rgb(${WIN})` }}>
                  {s.wins} {t('competitive.stats.winShort')}
                </span>{' '}
                ·{' '}
                <span style={{ color: `rgb(${LOSS})` }}>
                  {s.losses} {t('competitive.stats.lossShort')}
                </span>
                {s.draws > 0 && ` · ${s.draws} ${t('competitive.stats.drawShort')}`}
              </span>
            </div>
          </div>
        </Tile>

        {/* Note moyenne, dans sa couleur. */}
        <Tile index={1} label={t('competitive.stats.avgScore')} topo={avgColor}>
          <div className="flex items-baseline gap-1.5">
            <span
              className="text-[38px] font-black leading-none tabular-nums"
              style={{ color: `rgb(${avgColor})`, textShadow: `0 0 18px rgb(${avgColor} / 0.45)` }}
            >
              {s.avgScore}
            </span>
            <span className="text-[13px] font-bold text-txt-muted">/ 500</span>
          </div>
          <Bar value={s.avgScore / 500} color={avgColor} index={1} />
        </Tile>

        {/* Meilleur serveur : la meilleure note moyenne, avec assez de duels. */}
        <Tile index={2} label={t('competitive.stats.bestServer')}>
          {s.bestServer ? (
            (() => {
              const c = scoreColor(s.bestServer.avgScore)
              return (
                <div className="flex min-w-0 items-center gap-3.5">
                  {/* L'icône, plus grande, avec une lueur de la couleur de la note. */}
                  <div
                    className="h-14 w-14 shrink-0 rounded-[22%] border p-1"
                    style={{ borderColor: `rgb(${c} / 0.35)`, background: `rgb(${c} / 0.06)`, boxShadow: `0 0 20px rgb(${c} / 0.18)` }}
                  >
                    <ServerAvatar server={s.bestServer.server} />
                  </div>
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <div className="flex items-baseline gap-1">
                      <span
                        className="text-[26px] font-black leading-none tabular-nums"
                        style={{ color: `rgb(${c})`, textShadow: `0 0 14px rgb(${c} / 0.4)` }}
                      >
                        {s.bestServer.avgScore}
                      </span>
                      <span className="text-[11px] font-bold text-txt-muted">/ 500</span>
                    </div>
                    <p className="truncate text-[12px] font-bold text-txt-secondary" title={s.bestServer.server}>
                      {s.bestServer.server}
                    </p>
                    <p className="text-[10.5px] text-txt-muted">{t('competitive.servers.duels', { count: s.bestServer.duels })}</p>
                  </div>
                </div>
              )
            })()
          ) : (
            <p className="text-[12px] text-txt-muted">{t('competitive.stats.noServer')}</p>
          )}
        </Tile>

        {/* Série en cours, et le record. */}
        <Tile index={3} label={t('competitive.stats.currentStreak')} topo={s.currentStreak > 0 ? WIN : DRAW}>
          <div className="flex items-baseline gap-2">
            <span className="text-[38px] font-black leading-none tabular-nums" style={s.currentStreak > 0 ? { color: `rgb(${WIN})` } : undefined}>
              {s.currentStreak}
            </span>
            <span className="text-[11px] text-txt-muted">{t('competitive.stats.wins')}</span>
          </div>
          <span className="text-[11px] text-txt-muted">{t('competitive.stats.bestStreakSub', { count: s.bestStreak })}</span>
        </Tile>
      </div>

      <div className="grid min-h-0 grid-cols-[minmax(0,1fr)_250px] gap-4 lg:grid-cols-[minmax(0,1fr)_300px] xl:grid-cols-[minmax(0,1fr)_340px]">
        <ScoreChart duels={duels} avg={s.avgScore} />

        {/* Le résumé : quelques chiffres de plus, sans en faire un tableau. */}
        <motion.div
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ ...SNAP, delay: 0.15 }}
          className="flex min-h-0 flex-col rounded-2xl border border-line bg-surface-1 p-4 lg:p-5"
        >
          <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.stats.summary')}</span>
          {/* La carte est un conteneur (largeur ET hauteur) : la mise en page
              suit la place qu'elle a, pas la fenêtre.
              Peu de hauteur : une liste, le libellé à gauche et le chiffre à
              droite, une ligne chacun. De la hauteur (ch-lg) : la meilleure
              note en large, quatre chiffres deux par deux, et l'adversaire sur
              toute la largeur (un pseudo est long). */}
          <div className="mt-3 min-h-0 flex-1 [container-type:size]">
          <div className="grid h-full auto-rows-fr grid-cols-1 gap-1.5 ch-lg:grid-cols-2 ch-lg:grid-rows-4 ch-lg:gap-2">
            <Cell wide label={t('competitive.stats.bestScore')}>
              <div className="flex shrink-0 items-baseline gap-1">
                <span
                  className="text-[18px] font-black leading-none tabular-nums ch-lg:text-[24px]"
                  style={{ color: `rgb(${scoreColor(s.bestScore)})`, textShadow: `0 0 14px rgb(${scoreColor(s.bestScore)} / 0.4)` }}
                >
                  {s.bestScore}
                </span>
                <span className="text-[11px] font-bold text-txt-muted">/ 500</span>
              </div>
            </Cell>
            <Cell label={t('competitive.stats.played')}>
              <Value>{s.played}</Value>
            </Cell>
            <Cell label={t('competitive.stats.servers')}>
              <Value>{s.serverCount}</Value>
            </Cell>
            <Cell label={t('competitive.stats.avgDuration')}>
              <Value>{formatLength(s.avgDuration)}</Value>
            </Cell>
            <Cell label={t('competitive.stats.fastestWin')}>
              <Value>{s.fastestWin === null ? '—' : formatLength(s.fastestWin)}</Value>
            </Cell>
            <Cell wide label={t('competitive.stats.rival')}>
              <div className="flex min-w-0 items-baseline gap-2">
                <span className="min-w-0 truncate text-[13px] font-black ch-lg:text-[16px]" title={s.rival?.name}>
                  {s.rival ? s.rival.name : '—'}
                </span>
                {s.rival && (
                  <span className="shrink-0 text-[11px] font-semibold text-txt-muted">
                    {t('competitive.servers.duels', { count: s.rival.count })}
                  </span>
                )}
              </div>
            </Cell>
          </div>
          </div>
        </motion.div>
      </div>
    </div>
  )
}

function Tile({
  label,
  index,
  topo,
  children,
}: {
  label: string
  index: number
  /** Couleur du relief topographique en fond ; sans elle, carte unie. */
  topo?: string
  children: React.ReactNode
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SNAP, delay: index * 0.04 }}
      // `isolate` : le fond animé se glisse sous le contenu sans passer sous la carte.
      className="relative isolate flex min-w-0 flex-col justify-between gap-3 overflow-hidden rounded-2xl border border-line bg-surface-1 px-5 py-4"
      style={topo ? { borderColor: `rgb(${topo} / 0.3)` } : undefined}
    >
      {topo && (
        <div aria-hidden="true" className="absolute inset-0 -z-10">
          <TopoBackdrop color={topo} />
          <div
            className="absolute inset-0"
            style={{ background: `radial-gradient(14rem 8rem at 30% 70%, rgb(${topo} / 0.16), transparent 70%)` }}
          />
        </div>
      )}
      <span className="text-[10.5px] font-bold uppercase tracking-[0.07em] text-txt-muted">{label}</span>
      {children}
    </motion.div>
  )
}

function Bar({ value, color, index }: { value: number; color: string; index: number }) {
  return (
    <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.06]">
      <motion.div
        className="h-full rounded-full"
        style={{ background: `linear-gradient(90deg, rgb(${color} / 0.5), rgb(${color}))` }}
        initial={{ width: 0 }}
        animate={{ width: `${Math.min(1, value) * 100}%` }}
        transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1], delay: 0.1 + index * 0.04 }}
      />
    </div>
  )
}

/** L'anneau du taux de victoire : vert pour les victoires, rouge pour le reste. */
function Ring({ value }: { value: number }) {
  const r = 22
  const c = 2 * Math.PI * r
  return (
    <svg viewBox="0 0 56 56" className="h-14 w-14 shrink-0 -rotate-90">
      <circle cx="28" cy="28" r={r} fill="none" stroke={`rgb(${LOSS} / 0.35)`} strokeWidth="6" />
      <motion.circle
        cx="28"
        cy="28"
        r={r}
        fill="none"
        stroke={`rgb(${WIN})`}
        strokeWidth="6"
        strokeLinecap="round"
        strokeDasharray={c}
        initial={{ strokeDashoffset: c }}
        animate={{ strokeDashoffset: c * (1 - Math.min(1, value)) }}
        transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1], delay: 0.1 }}
      />
    </svg>
  )
}

/** Une case du résumé : le libellé en petit, le chiffre en dessous. */
function Cell({ label, wide, children }: { label: string; wide?: boolean; children: React.ReactNode }) {
  return (
    // Dans une carte basse : une ligne, libellé à gauche, chiffre à droite.
    // Dans une carte haute (ch-lg) : une case, libellé au-dessus du chiffre.
    <div
      className={`flex min-h-0 min-w-0 items-center justify-between gap-3 overflow-hidden rounded-xl border border-white/[0.06] bg-white/[0.025] px-3 ch-lg:flex-col ch-lg:items-start ch-lg:justify-center ch-lg:gap-1.5 ch-lg:px-3.5 ${
        wide ? 'ch-lg:col-span-2' : ''
      }`}
    >
      {/* Une seule ligne : le libellé entier est dans la bulle. */}
      <span title={label} className="min-w-0 truncate text-[10px] font-bold uppercase leading-tight tracking-[0.06em] text-txt-muted">
        {label}
      </span>
      {children}
    </div>
  )
}

function Value({ children }: { children: React.ReactNode }) {
  return <span className="shrink-0 text-[15px] font-black leading-none tabular-nums ch-lg:text-[20px]">{children}</span>
}

/**
 * Les notes des derniers duels, une barre par duel (le plus ancien à gauche),
 * dans la couleur de la note ; un trait marque la moyenne. En tête, la forme :
 * les derniers résultats en pastilles.
 */
function ScoreChart({ duels, avg }: { duels: DuelRow[]; avg: number }) {
  const t = useT()
  const recent = duels.slice(0, CHART_DUELS).reverse()
  const form = duels.slice(0, FORM_DUELS).reverse()

  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SNAP, delay: 0.12 }}
      className="flex min-h-0 flex-col gap-4 rounded-2xl border border-line bg-surface-1 p-5"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.stats.chartTitle')}</p>
          <p className="mt-0.5 text-[11px] text-txt-muted">{t('competitive.stats.chartHint', { count: recent.length })}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="text-[10px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.stats.form')}</span>
          <div className="flex gap-1">
            {form.map((d, i) => {
              const c = d.result === 'win' ? WIN : d.result === 'loss' ? LOSS : DRAW
              return (
                <motion.span
                  key={d.id}
                  initial={{ opacity: 0, scale: 0.6 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ delay: 0.2 + i * 0.03 }}
                  className="flex h-5 w-5 items-center justify-center rounded-md text-[9.5px] font-black"
                  style={{ background: `rgb(${c} / 0.16)`, color: `rgb(${c})` }}
                >
                  {t(d.result === 'win' ? 'competitive.stats.winShort' : d.result === 'loss' ? 'competitive.stats.lossShort' : 'competitive.stats.drawShort')}
                </motion.span>
              )
            })}
          </div>
        </div>
      </div>

      <div className="relative min-h-0 flex-1">
        {/* La moyenne, en pointillé à travers les barres. */}
        <div
          className="pointer-events-none absolute inset-x-0 z-10 border-t border-dashed border-white/25"
          style={{ bottom: `${(avg / 500) * 100}%` }}
        />
        <div className="absolute inset-0 flex items-end gap-[3px]">
          {recent.map((d, i) => {
            const c = scoreColor(d.score)
            return (
              <div key={d.id} className="group relative flex h-full min-w-0 flex-1 items-end">
                <motion.div
                  className="w-full rounded-t-[4px]"
                  style={{
                    background: `linear-gradient(180deg, rgb(${c}), rgb(${c} / 0.35))`,
                    // Une défaite reste lisible, mais en retrait.
                    opacity: d.result === 'win' ? 1 : 0.55,
                  }}
                  initial={{ height: 0 }}
                  animate={{ height: `${(d.score / 500) * 100}%` }}
                  transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1], delay: 0.15 + i * 0.015 }}
                />
                {/* Au survol : la note et l'adversaire. */}
                <span
                  style={{ bottom: `calc(${(d.score / 500) * 100}% + 6px)` }}
                  className="pointer-events-none absolute left-1/2 z-20 hidden -translate-x-1/2 whitespace-nowrap rounded-md border border-line bg-bg-card px-2 py-1 text-[10.5px] group-hover:block">
                  <span className="font-black tabular-nums" style={{ color: `rgb(${c})` }}>
                    {d.score}
                  </span>{' '}
                  · {t('competitive.history.vs', { name: d.opponent })}
                </span>
              </div>
            )
          })}
        </div>
      </div>
    </motion.div>
  )
}
