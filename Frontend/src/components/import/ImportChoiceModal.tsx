import { ModalShell } from '@/components/ui/ModalShell'
import { useT } from '@/i18n'

/** Point d'entrée unique du bouton "Importer" de la toolbar Mods — remplace
 * les deux boutons séparés (fichiers .jar / dossier autre launcher) par une
 * seule modal de choix. */
export function ImportChoiceModal({
  onClose,
  onPickJars,
  onPickFolder,
  onPickModpack,
  isPlugin,
}: {
  onClose: () => void
  onPickJars: () => void
  onPickFolder: () => void
  onPickModpack: () => void
  isPlugin: boolean
}) {
  const t = useT()
  return (
    <ModalShell title={t('import.title')} onClose={onClose} maxWidth="max-w-md">
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
              {isPlugin ? t('import.jarsPlugin') : t('import.jarsMod')}
            </p>
            <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
              {t('import.jarsDesc')}
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
            <p className="font-semibold text-white text-[13px]">{t('import.folderTitle')}</p>
            <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
              {t('import.folderDesc')}
            </p>
          </div>
        </button>

        <button
          onClick={() => { onClose(); onPickModpack() }}
          className="flex items-center gap-3 rounded-xl p-3.5 text-left transition-all duration-150 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)] hover:bg-[rgba(75,63,207,0.1)] hover:border-[rgba(75,63,207,0.35)]"
        >
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-[rgba(75,63,207,0.18)] text-[rgba(180,170,255,0.9)]">
            <svg viewBox="0 0 24 24" fill="currentColor" width={17} height={17}>
              <path d="M12 2 3 7v10l9 5 9-5V7l-9-5zm0 2.3 6.2 3.4L12 11.4 5.8 8l6.2-3.7zM5 9.7l6 3.4v6.9l-6-3.3V9.7zm8 10.3v-6.9l6-3.4v6.9l-6 3.4z" />
            </svg>
          </div>
          <div>
            <p className="font-semibold text-white text-[13px]">{t('import.modpackTitle')}</p>
            <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
              {t('import.modpackDesc')}
            </p>
          </div>
        </button>
      </div>
    </ModalShell>
  )
}
