import { useEffect } from 'react'
import type { Instance } from '@/types'
import { loaderColor } from '@/lib/loader'
import { useT } from '@/i18n'
import { SheetFrame } from '@/mobile/ui/SheetFrame'

/**
 * Le choix de l'instance, en volet qui monte du bas de l'écran.
 *
 * C'est la forme attendue sur téléphone pour une liste de choix : elle s'ouvre
 * sous le pouce, se ferme d'un geste comme toutes les feuilles
 * (`mobile/ui/SheetFrame`), en touchant le fond ou par le bouton retour du
 * clavier (Échap). Une ligne fait 60 px de haut, au-delà des 48 px recommandés
 * pour une cible tactile.
 */
export function InstanceSheet({ open, instances, selectedId, onSelect, onManage, onClose }: {
  open: boolean
  instances: Instance[]
  selectedId: string | null
  onSelect: (id: string) => void
  onManage: () => void
  onClose: () => void
}) {
  const t = useT()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  // Favoris d'abord, comme partout ailleurs dans le launcher.
  const sorted = [...instances].sort((a, b) => Number(b.favorite) - Number(a.favorite))

  // Le cadre, la poignée et les gestes sont ceux de toutes les feuilles.
  return (
    <SheetFrame
      open={open}
      onClose={onClose}
      label={t('home.mobile.switchInstance')}
      className="max-h-[75dvh] border-t border-line bg-bg-secondary pb-[max(12px,env(safe-area-inset-bottom))]"
    >
            <h2 className="shrink-0 select-none px-5 pb-2 text-[15px] font-bold text-txt-primary">{t('home.mobile.switchInstance')}</h2>

            <ul className="flex-1 overflow-y-auto overscroll-contain px-3">
              {sorted.map((inst) => {
                const selected = inst.id === selectedId
                return (
                  <li key={inst.id}>
                    <button
                      onClick={() => { onSelect(inst.id); onClose() }}
                      className={`flex h-[60px] w-full items-center gap-3 rounded-2xl px-3 text-left transition-colors active:bg-white/[0.06] ${selected ? 'bg-accent/15' : ''}`}
                    >
                      <span
                        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white/[0.05]"
                        style={{ color: loaderColor(inst.loader) }}
                      >
                        <span className="h-2.5 w-2.5 rounded-full" style={{ background: loaderColor(inst.loader) }} />
                      </span>
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span className="truncate text-[15px] font-semibold text-txt-primary">
                          {inst.favorite && <span className="mr-1 text-warning">★</span>}
                          {inst.name}
                        </span>
                        <span className="truncate text-[12.5px] text-txt-muted">
                          {inst.mc_version} · {loaderName(inst.loader)}
                        </span>
                      </span>
                      {selected && (
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-accent-hover">
                          <path d="M5 12.5l4.5 4.5L19 7.5" />
                        </svg>
                      )}
                    </button>
                  </li>
                )
              })}
            </ul>

            <div className="px-4 pt-2">
              <button
                onClick={() => { onClose(); onManage() }}
                className="flex h-12 w-full items-center justify-center rounded-2xl border border-line text-[14px] font-semibold text-txt-secondary active:bg-white/[0.05]"
              >
                {t('home.mobile.manageInstances')}
              </button>
            </div>
    </SheetFrame>
  )
}

/** Nom affiché d'un loader : le nom propre, pas la valeur stockée. */
export function loaderName(loader: string): string {
  switch (loader) {
    case 'fabric': return 'Fabric'
    case 'forge': return 'Forge'
    case 'neoforge': return 'NeoForge'
    case 'quilt': return 'Quilt'
    case 'horizon': return 'Horizon'
    default: return 'Vanilla'
  }
}
