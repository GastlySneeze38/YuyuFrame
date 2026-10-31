import { RankTile, useRankName } from '@/components/competitive/RankCard'
import { SEASON_GRID, TIER_COLORS, readStandings, tierOf } from '@/lib/competitive'
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

/** La grille de la saison en petit : une case par victoire, à la couleur du
 *  rang qu'on avait en la gagnant ; les cases vides en creux. */
function SeasonItem({ mode, wins, count }: { mode: 'mace' | 'sword'; wins: number[]; count: number }) {
  const t = useT()
  const cells = Array.from({ length: SEASON_GRID * SEASON_GRID }, (_, i) => wins[i] ?? null)
  return (
    <div className="flex flex-col items-center gap-1.5 rounded-xl border border-line bg-surface-1 px-2 py-2.5 text-center">
      <div className="grid grid-cols-8 gap-[1.5px]" style={{ width: 40, height: 40 }}>
        {cells.map((division, i) => (
          <span
            key={i}
            className="rounded-[1px]"
            style={{ background: division === null ? 'rgba(255,255,255,0.06)' : `rgb(${TIER_COLORS[tierOf(division)]})` }}
          />
        ))}
      </div>
      <span className="w-full truncate text-[12px] font-bold">{t('friends.profile.seasonWins', { count })}</span>
      <span className="text-[10.5px] text-txt-muted">{t(`friends.activity.modes.${mode}`)}</span>
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
