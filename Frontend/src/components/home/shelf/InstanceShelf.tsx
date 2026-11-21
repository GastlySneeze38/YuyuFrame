import { useEffect, useState } from 'react'
import { isModLoader } from '@/lib/loader'
import type { Instance } from '@/types'
import { ModsPanel } from './ModsPanel'
import { PacksPanel } from './PacksPanel'

/**
 * Les deux panneaux de l'instance sous les serveurs de l'accueil : mods à
 * gauche, packs à droite. Fermés ils tiennent sur une ligne ; ouverts ils
 * montent par-dessus l'accueil pour des retouches rapides, sans passer par
 * l'écran des mods. Un seul ouvert à la fois ; un clic dehors ou Échap
 * referme. Sans chargeur de mods (vanilla, Horizon), seul le panneau des
 * packs reste, sur toute la largeur.
 */
export function InstanceShelf({ instance, running }: { instance: Instance | null; running: boolean }) {
  const [open, setOpen] = useState<'mods' | 'packs' | null>(null)

  // Changer d'instance referme : le panneau ouvert parlait de l'autre.
  useEffect(() => { setOpen(null) }, [instance?.id])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  if (!instance) return null
  const withMods = isModLoader(instance.loader)

  return (
    <>
      {/* Voile sur l'accueil pendant qu'un panneau est ouvert. Il ne
          s'anime qu'en opacité ; un clic dessus referme. */}
      <div
        aria-hidden
        onClick={() => setOpen(null)}
        className={`fixed inset-0 z-30 bg-[rgba(0,0,0,0.5)] transition-opacity duration-200 ${open ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
      />
      <div className="flex gap-2">
        {withMods && (
          <div className="min-w-0 flex-[1.4]">
            <ModsPanel
              instance={instance}
              running={running}
              open={open === 'mods'}
              onOpen={() => setOpen('mods')}
              onClose={() => setOpen(null)}
            />
          </div>
        )}
        <div className="min-w-0 flex-1">
          <PacksPanel
            instance={instance}
            running={running}
            open={open === 'packs'}
            onOpen={() => setOpen('packs')}
            onClose={() => setOpen(null)}
          />
        </div>
      </div>
    </>
  )
}
