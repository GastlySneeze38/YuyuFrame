import { ModalShell } from '@/components/ui/ModalShell'

/** Affichée au lancement suivant une mise à jour (voir pendingPatchNotes,
 * posé par UpdateChecker juste avant relaunch()) — les notes viennent
 * directement du manifeste de mise à jour (`Update.body`, voir
 * @tauri-apps/plugin-updater), aucune source séparée à maintenir. */
export function PatchNotesModal({ version, notes, onClose }: { version: string; notes: string; onClose: () => void }) {
  const lines = notes.split('\n').map((l) => l.trim()).filter(Boolean)

  return (
    <ModalShell title={`Nouveautés — v${version}`} onClose={onClose} maxWidth="max-w-md">
      <div className="flex flex-col gap-4">
        {lines.length > 0 ? (
          <ul className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto pr-1 text-[12px] leading-relaxed text-[rgba(255,255,255,0.6)]">
            {lines.map((line, i) => (
              <li key={i} className="flex gap-2">
                <span className="text-[rgba(75,63,207,0.8)]">•</span>
                <span>{line.replace(/^[-•*]\s*/, '')}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-[12px] text-[rgba(255,255,255,0.4)]">
            Aucune note de version fournie pour cette mise à jour.
          </p>
        )}

        <button
          onClick={onClose}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8]"
        >
          Compris
        </button>
      </div>
    </ModalShell>
  )
}
