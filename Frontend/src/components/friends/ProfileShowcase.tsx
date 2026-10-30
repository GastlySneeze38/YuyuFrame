import { RankTile, useRankName } from '@/components/competitive/RankCard'
import { readStandings } from '@/lib/competitive'
import { useT } from '@/i18n'
import type { ShowcaseView } from '@/types/friends'

/**
 * La vitrine d'un profil : jusqu'à trois cartes côte à côte — le rang dans un
 * mode (l'insigne du jeu, lu dans l'état du classement comme sur l'onglet
 * Compétitif), le titre en cours.
 */
export function ProfileShowcase({ items }: { items: ShowcaseView[] }) {
  if (items.length === 0) return null
  return (
    <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}>
      {items.map((item, i) => (item.kind === 'rank' ? <RankItem key={i} mode={item.mode} state={item.state} /> : <TitleItem key={i} label={item.label} />))}
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

function TitleItem({ label }: { label: string }) {
  const t = useT()
  return (
    <div className="flex flex-col items-center gap-1.5 rounded-xl border border-[#f5b74a]/30 bg-[#f5b74a]/[0.07] px-2 py-2.5 text-center">
      <span className="flex h-10 w-10 items-center justify-center rounded-[22%] bg-[#f5b74a]/15 text-[#f5b74a]">
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5">
          <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5L3 8z" />
        </svg>
      </span>
      <span className="w-full truncate text-[12px] font-bold text-[#f5d18a]">{label}</span>
      <span className="text-[10.5px] text-txt-muted">{t('friends.profile.titleLabel')}</span>
    </div>
  )
}
