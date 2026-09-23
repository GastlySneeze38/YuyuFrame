import { ModalShell } from '@/components/ui/ModalShell'
import { useT } from '@/i18n'

/**
 * Point d'entrée unique de l'import depuis la page instance.
 *
 * Tout ce qui entre dans une instance depuis le disque passe par ici : mods,
 * packs de ressources, shaders, modpack, dossier d'un autre launcher, et les
 * réglages Minecraft. Avant, ces gestes étaient éparpillés entre plusieurs
 * boutons de la barre d'outils — celle-ci n'a plus qu'un bouton « Importer »,
 * et c'est cette fenêtre qui distingue les sources.
 *
 * Les packs demandent leur famille (ressources ou shaders) plutôt que de la
 * deviner : les deux sont des `.zip`, rien dans le fichier ne les distingue
 * de façon fiable, et se tromper range le pack dans un dossier où le jeu ne
 * le cherchera jamais.
 */

function ImportRow({ icon, title, desc, onClick }: {
  icon: string
  title: string
  desc: string
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className="flex items-center gap-3 rounded-xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] p-3 text-left transition-all duration-150 hover:border-[rgba(75,63,207,0.35)] hover:bg-[rgba(75,63,207,0.1)]"
    >
      <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl bg-[rgba(75,63,207,0.18)] text-[rgba(180,170,255,0.9)]">
        <svg viewBox="0 0 24 24" fill="currentColor" width={16} height={16}>
          <path d={icon} />
        </svg>
      </div>
      <div className="min-w-0">
        <p className="text-[13px] font-semibold text-white">{title}</p>
        <p className="text-[11px] text-[rgba(255,255,255,0.35)]">{desc}</p>
      </div>
    </button>
  )
}

const ICON = {
  plus: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  modpack: 'M12 2 3 7v10l9 5 9-5V7l-9-5zm0 2.3 6.2 3.4L12 11.4 5.8 8l6.2-3.7zM5 9.7l6 3.4v6.9l-6-3.3V9.7zm8 10.3v-6.9l6-3.4v6.9l-6 3.4z',
  grid: 'M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z',
  star: 'M12 3l2.09 6.26L20.5 9.5l-5 3.8 1.9 6.2L12 15.8 6.6 19.5l1.9-6.2-5-3.8 6.41-.24L12 3z',
  sliders: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
}

export function ImportChoiceModal({
  onClose,
  onPickJars,
  onPickFolder,
  onPickModpack,
  onPickPacks,
  onPickOptions,
  isPlugin,
}: {
  onClose: () => void
  onPickJars: () => void
  onPickFolder: () => void
  onPickModpack: () => void
  /** Archives `.zip` vers `resourcepacks/` ou `shaderpacks/`. */
  onPickPacks: (kind: 'resourcepack' | 'shader') => void
  /** Applique le modèle `shared_options.txt` à cette instance. */
  onPickOptions: () => void
  isPlugin: boolean
}) {
  const t = useT()
  const pick = (fn: () => void) => () => { onClose(); fn() }

  return (
    <ModalShell title={t('import.title')} onClose={onClose} maxWidth="max-w-md">
      <div className="flex flex-col gap-2">
        <ImportRow
          icon={ICON.plus}
          title={isPlugin ? t('import.jarsPlugin') : t('import.jarsMod')}
          desc={t('import.jarsDesc')}
          onClick={pick(onPickJars)}
        />
        <ImportRow
          icon={ICON.grid}
          title={t('import.resourcepackTitle')}
          desc={t('import.resourcepackDesc')}
          onClick={pick(() => onPickPacks('resourcepack'))}
        />
        <ImportRow
          icon={ICON.star}
          title={t('import.shaderTitle')}
          desc={t('import.shaderDesc')}
          onClick={pick(() => onPickPacks('shader'))}
        />
        <ImportRow
          icon={ICON.modpack}
          title={t('import.modpackTitle')}
          desc={t('import.modpackDesc')}
          onClick={pick(onPickModpack)}
        />
        <ImportRow
          icon={ICON.sliders}
          title={t('import.optionsTitle')}
          desc={t('import.optionsDesc')}
          onClick={pick(onPickOptions)}
        />
        <ImportRow
          icon={ICON.download}
          title={t('import.folderTitle')}
          desc={t('import.folderDesc')}
          onClick={pick(onPickFolder)}
        />
      </div>
    </ModalShell>
  )
}
