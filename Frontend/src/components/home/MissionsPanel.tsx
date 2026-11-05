import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { DrawerPanel } from '@/components/ui/DrawerPanel'
import { Missions } from '@/components/competitive/Challenges'
import type { BattlePass, Challenge, MissionsMeta } from '@/lib/competitive'
import { useT } from '@/i18n'

/**
 * Les missions en tiroir, ouvert par le raccourci de l'accueil : le même
 * tiroir que celui des amis (à droite, par-dessus la page, Échap ou un clic
 * à côté referme), et dedans le même panneau que le volet de la page du
 * passe (`Missions` de `Challenges.tsx`) — un seul panneau, deux endroits.
 *
 * Rendu dans `document.body`, pour la même raison que celui des amis : la
 * bannière d'où il s'ouvre est transformée par ses animations.
 */
export function MissionsPanel({ open, onClose, challenges, pass, meta, onReroll }: {
  open: boolean
  onClose: () => void
  challenges: Challenge[] | null
  pass: BattlePass | null
  meta?: MissionsMeta | null
  onReroll?: (id: string) => void
}) {
  const t = useT()
  const navigate = useNavigate()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [open, onClose])

  const go = (path: string) => {
    onClose()
    navigate(path)
  }

  // Panneau à droite sur PC, feuille du bas sur téléphone (`DrawerPanel`).
  return (
    <DrawerPanel open={open} onClose={onClose} label={t('home.shortcuts.missions')}>
            {/* En-tête : comme celui des amis, plus la page entière. */}
            <div className="flex shrink-0 items-center gap-1 border-b border-line-soft px-3 py-2.5">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="ml-1 mr-1 h-[18px] w-[18px] text-txt-secondary">
                <path d="M4 21V4M4 4h12l-2 4 2 4H4" />
              </svg>
              <span className="flex-1 text-[14px] font-bold">{t('home.shortcuts.missions')}</span>
              <HeaderButton label={t('home.shortcuts.missionsOpen')} onClick={() => go('/missions')} path="M14 4h6v6M20 4l-8 8M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1h5" />
              <HeaderButton label={t('common.close')} onClick={onClose} path="M6 6l12 12M18 6L6 18" />
            </div>

            {/* Le panneau des missions, sur toute la hauteur du tiroir. */}
            <div className="flex min-h-0 flex-1 flex-col p-3 [&>*]:min-h-0 [&>*]:flex-1">
              <Missions challenges={challenges} pass={pass} meta={meta} onReroll={onReroll} />
            </div>

            {/* Le passe, où va l'XP gagnée. */}
            {pass && (
              <div className="shrink-0 border-t border-line-soft p-3">
                <button
                  onClick={() => go('/missions/pass')}
                  className="flex w-full items-center justify-center gap-1.5 rounded-xl bg-accent/20 py-2.5 text-[12px] font-bold text-white transition-colors hover:bg-accent/30"
                >
                  {t('competitive.pass.seeTiers')}
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                    <path d="M9 6l6 6-6 6" />
                  </svg>
                </button>
              </div>
            )}
    </DrawerPanel>
  )
}

function HeaderButton({ label, onClick, path }: { label: string; onClick: () => void; path: string }) {
  return (
    <button
      onClick={onClick}
      title={label}
      aria-label={label}
      className="flex h-8 w-8 items-center justify-center rounded-lg text-txt-muted transition-colors hover:bg-white/[0.06] hover:text-white"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-[17px] w-[17px]">
        <path d={path} />
      </svg>
    </button>
  )
}
