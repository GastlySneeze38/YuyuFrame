import { RankTile, useRankName } from '@/components/competitive/RankCard'
import { SeasonFrame } from '@/components/competitive/SeasonRankView'
import { BORDER_LEVELS, TIER_COLORS, TIER_TEXTURES, borderLevel, readStandings, tierOf } from '@/lib/competitive'
import { useContentAsset } from '@/lib/contentAsset'
import { useT } from '@/i18n'
import type { ShowcaseView } from '@/types/friends'

/**
 * La vitrine d'un profil : jusqu'à trois cartes côte à côte — le rang dans un
 * mode (l'insigne du jeu, lu dans l'état du classement comme sur l'onglet
 * Compétitif), le titre en cours, la grille de la saison.
 */
export function ProfileShowcase({ items }: { items: ShowcaseView[] }) {
  if (items.length === 0) return null
  return (
    <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}>
      {items.map((item, i) =>
        item.kind === 'rank' ? (
          <RankItem key={i} mode={item.mode} state={item.state} />
        ) : item.kind === 'season' ? (
          <SeasonItem key={i} mode={item.mode} wins={item.wins} count={item.count} />
        ) : (
          <TitleItem key={i} label={item.label} asset={item.asset ?? null} />
        ),
      )}
    </div>
  )
}

function RankItem({ mode, state }: { mode: 'mace' | 'sword'; state: unknown }) {
  const t = useT()
  const rankName = useRankName()
  const standing = readStandings({ state }).find((s) => s.mode === mode)
  if (!standing) return null
  return (
    <div className="flex flex-col items-center gap-1.5 rounded-xl border border-line bg-surface-1 px-2 py-2.5 text-center">
      <RankTile division={standing.division} placed={standing.placed} size={40} />
      <span className="w-full truncate text-[12px] font-bold">{rankName(standing.division, standing.placed)}</span>
      <span className="text-[10.5px] text-txt-muted">{t(`friends.activity.modes.${mode}`)}</span>
    </div>
  )
}

/** Cases de la grille en petit : les 4 dernières victoires. */
const SHOWCASE_CELLS = 4

/**
 * La saison en petit : les 4 dernières victoires (la plus récente en haut à
 * gauche), chacune avec la texture du rang qu'on avait en la gagnant, dans la
 * bordure de la saison au niveau atteint — la même que sur l'écran Saison.
 * Un serveur plus ancien envoie les 64 meilleures : on en garde 4.
 */
function SeasonItem({ mode, wins, count }: { mode: 'mace' | 'sword'; wins: number[]; count: number }) {
  const t = useT()
  const total = Math.max(count, wins.length)
  const level = borderLevel(total)
  const best = wins.length > 0 ? Math.max(...wins) : null
  const accent = best !== null ? TIER_COLORS[tierOf(best)] : '160 150 190'
  const cells = Array.from({ length: SHOWCASE_CELLS }, (_, i) => wins[i] ?? null)
  return (
    <div className="flex flex-col items-center gap-1 rounded-xl border border-line bg-surface-1 px-2 pb-2.5 pt-1.5 text-center">
      <SeasonFrame level={level} accent={accent} size="64px" still>
        <div className="grid h-full w-full grid-cols-2 gap-[2px] bg-[rgb(10_10_16)] p-[2px]">
          {cells.map((division, i) =>
            division === null ? (
              <span key={i} className="rounded-[1px] border border-white/[0.05] bg-white/[0.02]" />
            ) : (
              <span
                key={i}
                className="relative overflow-hidden rounded-[1px]"
                style={{ boxShadow: 'inset 1px 1px 0 rgb(255 255 255 / 0.3), inset -1px -1px 0 rgb(0 0 0 / 0.4)' }}
              >
                <img src={TIER_TEXTURES[tierOf(division)]} alt="" draggable={false} className="absolute inset-0 h-full w-full [image-rendering:pixelated]" />
              </span>
            ),
          )}
        </div>
      </SeasonFrame>
      <span className="w-full truncate text-[12px] font-bold">{t('friends.profile.seasonWins', { count: total })}</span>
      <span className="text-[10.5px] text-txt-muted">
        {t(`friends.activity.modes.${mode}`)} · {t('competitive.season.levelOf', { level, max: BORDER_LEVELS })}
      </span>
    </div>
  )
}

function TitleItem({ label, asset }: { label: string; asset: string | null }) {
  const t = useT()
  // L'image du titre (back-office) ; sans elle, ou si elle manque, la couronne.
  const image = useContentAsset(asset)
  return (
    <div className="flex flex-col items-center gap-1.5 rounded-xl border border-[#f5b74a]/30 bg-[#f5b74a]/[0.07] px-2 py-2.5 text-center">
      {image ? (
        <img src={image} alt="" draggable={false} className="h-10 w-10 object-contain" />
      ) : (
        <span className="flex h-10 w-10 items-center justify-center rounded-[22%] bg-[#f5b74a]/15 text-[#f5b74a]">
          <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5">
            <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5L3 8z" />
          </svg>
        </span>
      )}
      <span className="w-full truncate text-[12px] font-bold text-[#f5d18a]">{label}</span>
      <span className="text-[10.5px] text-txt-muted">{t('friends.profile.titleLabel')}</span>
    </div>
  )
}
