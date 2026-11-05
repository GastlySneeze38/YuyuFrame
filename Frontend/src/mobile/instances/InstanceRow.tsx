import { memo } from 'react'
import type { Instance } from '@/types'
import { showsRam } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { InstanceIcon } from '@/components/instances/InstanceIcon'
import { loaderName } from '@/mobile/home/InstanceSheet'
import { useT } from '@/i18n'

/**
 * Une instance dans la liste du téléphone : la carte du PC (`InstanceCard`),
 * en ligne pleine largeur.
 *
 * - toucher la ligne la choisit (c'est elle que l'accueil lancera) et ouvre
 *   son contenu, comme cliquer une carte sur PC montre ses mods à droite ;
 * - l'étoile la met en favori, sans confirmation, comme sur PC ;
 * - « … » ouvre la feuille d'actions (le menu à trois points du PC).
 *
 * Trois cibles distinctes de 44 px au moins. Pas d'animation d'entrée : la
 * liste s'ouvre des dizaines de fois par jour.
 */
export const InstanceRow = memo(function InstanceRow({ instance, selected, onOpen, onToggleFavorite, onMore }: {
  instance: Instance
  selected: boolean
  onOpen: (instance: Instance) => void
  onToggleFavorite: (id: string) => void
  onMore: (instance: Instance) => void
}) {
  const t = useT()
  const details = [
    instance.mc_version,
    loaderName(instance.loader),
    showsRam(instance) ? formatRam(instance.ram_mb) : null,
  ].filter(Boolean).join(' · ')

  return (
    <div
      className={`flex h-[72px] items-center rounded-2xl border pr-1 transition-colors duration-150 ${
        selected
          ? 'border-[rgba(120,100,255,0.5)] bg-[rgba(75,63,207,0.16)]'
          : 'border-white/[0.06] bg-white/[0.04]'
      }`}
    >
      <button
        onClick={() => onOpen(instance)}
        aria-current={selected ? 'true' : undefined}
        className="press flex h-full min-w-0 flex-1 items-center gap-3 pl-3 text-left"
      >
        <InstanceIcon
          instance={instance}
          size={44}
          className={selected ? 'bg-[rgba(75,63,207,0.3)]' : 'bg-white/[0.05]'}
        />
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="truncate text-[15px] font-semibold text-white">{instance.name}</span>
          <span className="truncate text-[13px] text-white/55">{details}</span>
        </span>
      </button>

      <button
        onClick={() => onToggleFavorite(instance.id)}
        aria-label={instance.favorite ? t('instancesPage.removeFromFavorites') : t('instancesPage.addToFavorites')}
        aria-pressed={instance.favorite}
        className={`press flex h-11 w-11 shrink-0 items-center justify-center rounded-full transition-colors duration-150 ${
          instance.favorite ? 'text-[#facc15]' : 'text-white/35'
        }`}
      >
        <svg viewBox="0 0 24 24" fill={instance.favorite ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={instance.favorite ? 0 : 1.8} className="h-5 w-5">
          <path strokeLinecap="round" strokeLinejoin="round" d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
        </svg>
      </button>

      <button
        onClick={() => onMore(instance)}
        aria-label={t('instancesPage.moreActions')}
        aria-haspopup="dialog"
        className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-white/55"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5">
          <circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" />
        </svg>
      </button>
    </div>
  )
})
