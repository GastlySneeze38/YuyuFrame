import { ModalShell } from '@/components/ui/ModalShell'
import { useStore } from '@/stores/useStore'
import type { SavedServer } from '@/api/client'

/** Confirmation avant un lancement direct sur un serveur — visible seulement
 * si useStore.confirmServerLaunch est actif (réglage activé par défaut). Le
 * toggle ici EST le même réglage (pas une copie) : le décocher ferme aussi
 * la confirmation pour les prochains lancements, voir Settings.tsx. */
export function ServerConfirmModal({
  server,
  onConfirm,
  onClose,
}: {
  server: SavedServer
  onConfirm: () => void
  onClose: () => void
}) {
  const confirmServerLaunch = useStore((s) => s.confirmServerLaunch)
  const setConfirmServerLaunch = useStore((s) => s.setConfirmServerLaunch)

  return (
    <ModalShell title="Lancer sur ce serveur ?" onClose={onClose} maxWidth="max-w-sm">
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <p className="font-bold text-white text-[14px] truncate">{server.name || 'Serveur'}</p>
          <p className="text-[11px] text-[rgba(255,255,255,0.4)] truncate">{server.ip}</p>
        </div>

        <label className="flex items-center gap-2 cursor-pointer select-none">
          <input
            type="checkbox"
            checked={confirmServerLaunch}
            onChange={(e) => setConfirmServerLaunch(e.target.checked)}
            className="accent-[#4B3FCF]"
          />
          <span className="text-[11px] text-[rgba(255,255,255,0.5)]">Toujours demander confirmation</span>
        </label>

        <div className="flex gap-2">
          <button
            onClick={onClose}
            className="flex-1 h-9 rounded-lg text-[12px] font-semibold text-[rgba(255,255,255,0.6)] bg-[rgba(255,255,255,0.05)] transition-colors hover:bg-[rgba(255,255,255,0.09)]"
          >
            Annuler
          </button>
          <button
            onClick={onConfirm}
            className="flex-1 h-9 rounded-lg text-[12px] font-semibold text-white bg-[#4B3FCF] transition-colors hover:bg-[#6155e8]"
          >
            Lancer
          </button>
        </div>
      </div>
    </ModalShell>
  )
}
