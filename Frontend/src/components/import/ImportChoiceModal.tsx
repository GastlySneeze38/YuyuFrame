import { ModalShell } from '@/components/ui/ModalShell'

/** Point d'entrée unique du bouton "Importer" de la toolbar Mods — remplace
 * les deux boutons séparés (fichiers .jar / dossier autre launcher) par une
 * seule modal de choix. */
export function ImportChoiceModal({
  onClose,
  onPickJars,
  onPickFolder,
  isPlugin,
}: {
  onClose: () => void
  onPickJars: () => void
  onPickFolder: () => void
  isPlugin: boolean
}) {
  return (
    <ModalShell title="Importer" onClose={onClose} maxWidth="max-w-md">
      <div className="flex flex-col gap-2">
        <button
          onClick={() => { onClose(); onPickJars() }}
          className="flex items-center gap-3 rounded-xl p-3.5 text-left transition-all duration-150 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)] hover:bg-[rgba(75,63,207,0.1)] hover:border-[rgba(75,63,207,0.35)]"
        >
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-[rgba(75,63,207,0.18)] text-[rgba(180,170,255,0.9)]">
            <svg viewBox="0 0 24 24" fill="currentColor" width={17} height={17}>
              <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
            </svg>
          </div>
          <div>
            <p className="font-semibold text-white text-[13px]">
              {isPlugin ? 'Fichiers .jar (plugin)' : 'Fichiers .jar (mod)'}
            </p>
            <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
              Sélectionne un ou plusieurs fichiers directement
            </p>
          </div>
        </button>

        <button
          onClick={() => { onClose(); onPickFolder() }}
          className="flex items-center gap-3 rounded-xl p-3.5 text-left transition-all duration-150 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)] hover:bg-[rgba(75,63,207,0.1)] hover:border-[rgba(75,63,207,0.35)]"
        >
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-[rgba(75,63,207,0.18)] text-[rgba(180,170,255,0.9)]">
            <svg viewBox="0 0 24 24" fill="currentColor" width={17} height={17}>
              <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />
            </svg>
          </div>
          <div>
            <p className="font-semibold text-white text-[13px]">Dossier / autre launcher</p>
            <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
              CurseForge, MultiMC, Prism, ATLauncher, Modrinth App...
            </p>
          </div>
        </button>
      </div>
    </ModalShell>
  )
}
