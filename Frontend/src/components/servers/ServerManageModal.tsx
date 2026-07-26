import { ModalShell } from '@/components/ui/ModalShell'
import { ServerCard } from './ServerCard'
import type { SavedServer } from '@/api/client'

/** Liste tous les serveurs enregistrés (servers.dat) de l'instance
 * sélectionnée — ouverte depuis Home.tsx uniquement quand il y en a plus de
 * 3 (voir le raccourci "icône" en bout de rangée). Bouton au survol = épingle
 * jusqu'à 3 favoris affichés directement sur l'accueil (voir
 * useStore.favoriteServers) ; une carte déjà épinglée reste visuellement
 * distincte (teinte violette) même hors survol. */
export function ServerManageModal({
  servers,
  favorites,
  onToggleFavorite,
  onClose,
  onLaunch,
}: {
  servers: SavedServer[]
  favorites: string[]
  onToggleFavorite: (ip: string) => void
  onClose: () => void
  onLaunch: (server: SavedServer) => void
}) {
  return (
    <ModalShell title="Serveurs enregistrés" onClose={onClose} maxWidth="max-w-2xl">
      <div className="flex flex-col gap-3 max-h-[60vh] overflow-y-auto pr-2">
        <p className="px-1 text-[10px] text-[rgba(255,255,255,0.35)]">
          Survole une carte pour l'épingler — jusqu'à 3 serveurs affichés directement sur l'accueil.
        </p>
        <div className="grid grid-cols-3 items-start gap-3">
          {servers.map((s) => (
            <ServerCard
              key={s.ip}
              server={s}
              favorite={favorites.includes(s.ip)}
              onToggleFavorite={() => onToggleFavorite(s.ip)}
              onClick={() => { onLaunch(s); onClose() }}
            />
          ))}
        </div>
      </div>
    </ModalShell>
  )
}
