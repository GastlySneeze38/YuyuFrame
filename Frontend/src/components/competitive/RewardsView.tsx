import { useState } from 'react'
import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { SNAP } from '@/lib/motion'
import {
  DIVISIONS_PER_TIER,
  TIER_COLORS,
  TIER_KEYS,
  fallbackSeason,
  tierOf,
  type CosmeticKind,
  type Season,
  type Standing,
} from '@/lib/competitive'
import { RankTile, useRankName } from './RankCard'
import { TopoBackdrop } from './TopoBackdrop'

/**
 * Écran Récompenses : les saisons et leurs cosmétiques.
 *
 * Chaque saison donne, à sa fin, un cosmétique par rang : celui du meilleur
 * rang atteint pendant la saison et tous ceux d'en dessous. On choisit la
 * saison en haut (la plus récente d'abord) ; une saison terminée dit ce qu'on
 * a obtenu, celle en cours ce qui est déjà assuré.
 *
 * Sans route serveur pour les saisons (`seasons === null`), une seule saison
 * sans dates : les logos de rang, qui existent déjà en jeu.
 */
export function RewardsView({ standing, seasons }: { standing: Standing; seasons: Season[] | null }) {
  const list = seasons && seasons.length > 0 ? seasons : [fallbackSeason(standing)]
  const [picked, setPicked] = useState(list[0].id)
  const season = list.find((s) => s.id === picked) ?? list[0]

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <SeasonBanner season={season} seasons={list} onPick={setPicked} />

      <motion.div key={season.id} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={SNAP} className="grid min-h-0 flex-1 grid-cols-7 gap-3">
        {TIER_KEYS.map((tier, i) => (
          <RewardColumn key={tier} season={season} tierIndex={i} />
        ))}
      </motion.div>
    </div>
  )
}

/** Une saison terminée ? (sans date de fin : non). */
function isOver(season: Season): boolean {
  return season.end !== null && season.end < Date.now()
}

/** Le rang (index dans `TIER_KEYS`) atteint dans la saison ; -1 sans placement. */
function peakTier(season: Season): number {
  return season.peak === null ? -1 : TIER_KEYS.indexOf(tierOf(season.peak))
}

function SeasonBanner({ season, seasons, onPick }: { season: Season; seasons: Season[]; onPick: (id: string) => void }) {
  const t = useT()
  const rankName = useRankName()
  const language = useStore((s) => s.language)
  const locale = language === 'sga' ? 'fr' : language
  const over = isOver(season)
  const peak = peakTier(season)
  const color = peak >= 0 ? TIER_COLORS[TIER_KEYS[peak]] : '160 150 190'
  const date = (ms: number) => new Date(ms).toLocaleDateString(locale, { day: 'numeric', month: 'long', year: 'numeric' })
  const daysLeft = season.end !== null ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null
  const progress =
    season.start !== null && season.end !== null ? Math.min(1, Math.max(0, (Date.now() - season.start) / (season.end - season.start))) : null

  return (
    <div className="relative shrink-0 overflow-hidden rounded-2xl border border-line bg-surface-1 px-6 py-5" style={{ isolation: 'isolate' }}>
      <div aria-hidden="true" className="absolute inset-0 -z-10 opacity-70">
        <TopoBackdrop color={color} />
      </div>

      <div className="flex items-center gap-6">
        {/* La saison : nom, dates, temps restant. */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <span className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-txt-muted">
              {t('competitive.seasons.number', { number: season.number })}
            </span>
            <span
              className={`rounded-full px-2 py-0.5 text-[9.5px] font-black uppercase tracking-[0.06em] ${
                over ? 'bg-white/[0.06] text-txt-muted' : 'bg-success/[0.16] text-success'
              }`}
            >
              {over ? t('competitive.seasons.ended') : t('competitive.seasons.live')}
            </span>
          </div>
          <p className="mt-1 text-[26px] font-black leading-tight tracking-[-0.01em]">
            {season.name || t('competitive.seasons.currentFallback')}
          </p>
          {season.start !== null && season.end !== null && (
            <p className="mt-1 text-[12px] text-txt-secondary">
              {date(season.start)} → {date(season.end)}
              {!over && daysLeft !== null && <span className="text-txt-muted"> · {t('competitive.seasons.daysLeft', { count: daysLeft })}</span>}
            </p>
          )}
          {progress !== null && !over && (
            <div className="mt-3 h-1.5 w-full max-w-[360px] overflow-hidden rounded-full bg-white/[0.07]">
              <motion.div
                className="h-full rounded-full bg-accent"
                initial={{ width: 0 }}
                animate={{ width: `${progress * 100}%` }}
                transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1] }}
              />
            </div>
          )}
        </div>

        {/* Ton meilleur rang de la saison : c'est lui qui décide des cosmétiques. */}
        <div className="flex items-center gap-3 rounded-xl border border-white/[0.08] bg-black/30 px-4 py-3 backdrop-blur-sm">
          <div style={{ filter: peak >= 0 ? `drop-shadow(0 0 14px rgb(${color} / 0.5))` : undefined }}>
            <RankTile division={season.peak ?? 0} placed={season.peak !== null} size={44} />
          </div>
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.08em] text-txt-muted">
              {t('competitive.seasons.peakRank')}
            </p>
            <p className="text-[16px] font-black" style={{ color: `rgb(${color})` }}>
              {rankName(season.peak ?? 0, season.peak !== null)}
            </p>
          </div>
        </div>

        {/* Les saisons, la plus récente d'abord. */}
        {seasons.length > 1 && (
          <div className="flex flex-col gap-1.5">
            {seasons.map((s) => (
              <button
                key={s.id}
                onClick={() => onPick(s.id)}
                className={`flex items-center justify-between gap-3 rounded-lg px-3 py-1.5 text-left text-[11.5px] font-bold transition-colors ${
                  s.id === season.id ? 'bg-accent/80 text-white' : 'bg-white/[0.04] text-txt-muted hover:text-txt-secondary'
                }`}
              >
                <span>{t('competitive.seasons.number', { number: s.number })}</span>
                <span className="font-semibold opacity-80">{s.name}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <p className="mt-4 text-[11px] text-txt-muted">
        {over ? t('competitive.seasons.howOver') : t('competitive.seasons.how')}
      </p>
    </div>
  )
}

function RewardColumn({ season, tierIndex }: { season: Season; tierIndex: number }) {
  const t = useT()
  const tier = TIER_KEYS[tierIndex]
  const kind = season.rewards[tierIndex] ?? 'badge'
  const color = TIER_COLORS[tier]
  const rankLabel = t(`competitive.tiers.${tier}`)
  const peak = peakTier(season)
  const over = isOver(season)
  const earned = tierIndex <= peak
  const isPeak = tierIndex === peak

  // Ce que dit la pastille : obtenu / non obtenu pour une saison finie,
  // assuré / ton rang / à atteindre pour celle en cours.
  const status = over
    ? earned
      ? t('competitive.seasons.obtained')
      : t('competitive.seasons.missed')
    : isPeak
      ? t('competitive.rewards.current')
      : earned
        ? t('competitive.seasons.secured')
        : t('competitive.seasons.toReach')

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: tierIndex * 0.04 }}
      className={`relative flex min-h-0 flex-col items-center gap-3 overflow-hidden rounded-2xl border px-3 py-5 text-center ${
        isPeak ? 'border-2' : 'border-line'
      } ${earned ? 'bg-surface-1' : 'bg-white/[0.015]'}`}
      style={{
        borderColor: isPeak ? `rgb(${color} / 0.7)` : undefined,
        backgroundImage: earned ? `radial-gradient(10rem 9rem at 50% 22%, rgb(${color} / 0.2), transparent 70%)` : undefined,
      }}
    >
      {/* Le rang qui donne le cosmétique, en petit. */}
      <div className="flex items-center gap-1.5">
        <RankTile division={tierIndex * DIVISIONS_PER_TIER} placed size={16} />
        <span className="text-[11.5px] font-black" style={{ color: earned ? `rgb(${color})` : 'rgb(255 255 255 / 0.4)' }}>
          {rankLabel}
        </span>
      </div>

      {/* Le cosmétique, en grand. */}
      <div className={earned ? '' : 'opacity-40 grayscale'}>
        <CosmeticIcon kind={kind} color={color} tierIndex={tierIndex} />
      </div>

      <div className="flex flex-1 flex-col items-center gap-1">
        <p className={`text-[12.5px] font-bold leading-snug ${earned ? 'text-txt-primary' : 'text-txt-muted'}`}>
          {t(`competitive.seasons.kinds.${kind}`, { rank: rankLabel })}
        </p>
        <p className="text-[10.5px] text-txt-muted">{t(`competitive.seasons.kindLabels.${kind}`)}</p>
      </div>

      <span
        className={`rounded-full px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.05em] ${
          isPeak && !over ? 'text-black' : earned ? 'bg-success/[0.15] text-success' : 'bg-white/[0.05] text-txt-muted'
        }`}
        style={isPeak && !over ? { background: `rgb(${color})` } : undefined}
      >
        {status}
      </span>
    </motion.div>
  )
}

/** L'aperçu d'un cosmétique : une icône par sorte, dans la couleur du rang. */
export function CosmeticIcon({ kind, color, tierIndex, size = 72 }: { kind: CosmeticKind; color: string; tierIndex: number; size?: number }) {
  const c = `rgb(${color})`
  const name = useStore((s) => s.username) ?? 'Steve'
  // Le dessin est fait pour 72 px ; une autre taille l'agrandit d'un bloc.
  const frame = (child: React.ReactNode) => (
    <div
      className="flex items-center justify-center rounded-[22%] border"
      style={{
        width: size,
        height: size,
        borderColor: `rgb(${color} / 0.35)`,
        background: `radial-gradient(circle at 50% 35%, rgb(${color} / 0.28), rgb(${color} / 0.05))`,
        filter: `drop-shadow(0 0 12px rgb(${color} / 0.35))`,
      }}
    >
      <div style={{ transform: `scale(${size / 72})` }} className="flex items-center justify-center">
        {child}
      </div>
    </div>
  )
  switch (kind) {
    case 'badge':
      return frame(<RankTile division={tierIndex * DIVISIONS_PER_TIER + DIVISIONS_PER_TIER - 1} placed size={44} />)
    case 'nametag':
      // Une étiquette de pseudo, comme au-dessus d'une tête en jeu.
      return frame(
        <div className="flex flex-col items-center gap-1">
          <span className="max-w-[64px] truncate rounded-[3px] bg-black/50 px-1.5 py-0.5 text-[10px] font-black" style={{ color: c, textShadow: `0 0 8px ${c}` }}>
            {name}
          </span>
          <span className="h-1 w-6 rounded-full" style={{ background: c, opacity: 0.5 }} />
        </div>,
      )
    case 'cape':
      return frame(
        <svg viewBox="0 0 24 24" className="h-10 w-10" fill={c}>
          <path d="M6 3h12l1 3-2 15-5-2.5L7 21 5 6z" opacity="0.95" />
          <path d="M9 7h6v2H9z" fill="rgb(0 0 0 / 0.25)" />
        </svg>,
      )
    case 'aura':
      return frame(
        <svg viewBox="0 0 24 24" className="h-11 w-11" fill="none" stroke={c} strokeWidth="1.6" strokeLinecap="round">
          <circle cx="12" cy="12" r="4" fill={c} stroke="none" opacity="0.9" />
          <circle cx="12" cy="12" r="8" opacity="0.5" />
          <path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2 2M17.4 17.4l2 2M4.6 19.4l2-2M17.4 6.6l2-2" />
        </svg>,
      )
    case 'title':
      return frame(
        <svg viewBox="0 0 24 24" className="h-10 w-10" fill={c}>
          <path d="M3 8l4.5 3.5L12 4l4.5 7.5L21 8l-2 11H5z" />
          <rect x="5" y="20" width="14" height="1.6" rx="0.8" />
        </svg>,
      )
    // Bannière ou couleur de profil sans valeur connue : un bandeau, une pastille.
    case 'banner':
      return frame(<span className="block h-6 w-11 rounded-md" style={{ background: c }} />)
    case 'accent':
      return frame(<span className="block h-9 w-9 rounded-full border-2 border-white/70" style={{ background: c }} />)
  }
}
