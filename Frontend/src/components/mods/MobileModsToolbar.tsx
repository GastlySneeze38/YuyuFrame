import { useT } from '@/i18n'
import { TOOLBAR_ICON } from './toolbarIcons'
import type { Tab } from './modUtils'

type Family = 'mods' | 'packs' | 'options'

function familyOf(tab: Tab): Family {
  if (tab === 'options') return 'options'
  return tab.startsWith('packs-') ? 'packs' : 'mods'
}

/**
 * La barre de l'écran des mods, sur téléphone.
 *
 * Mêmes écrans que la barre du PC, rangés autrement : sur PC, trois menus
 * déroulants (Mods, Packs, Options) ; ici les trois familles forment un
 * commutateur pleine largeur, et les écrans de la famille ouverte des
 * pastilles en dessous — un menu déroulant caché derrière un libellé se
 * découvre mal au doigt, des pastilles se voient. « Tout mettre à jour »
 * reste au bout de la rangée ; Importer est un bouton flottant
 * (`MobileImportFab`).
 *
 * « Jouer à … » n'y est pas : sur téléphone, l'onglet Accueil de la barre du
 * bas fait la même chose, à un pouce de là.
 */
export function MobileModsToolbar({ tab, onTab, isPlugin, hasModpack, modCount, packCount, updatesCount, updatingAll, onUpdateAll, notice }: {
  tab: Tab
  onTab: (tab: Tab) => void
  /** Vanilla ou Horizon : pas de famille Mods (aucun chargeur de mods). */
  isPlugin: boolean
  hasModpack: boolean
  modCount: number
  packCount: number
  updatesCount: number
  updatingAll: boolean
  onUpdateAll: () => void
  notice: string | null
}) {
  const t = useT()
  const family = familyOf(tab)

  const families: { id: Family; label: string; icon: string; first: Tab }[] = [
    ...(!isPlugin ? [{ id: 'mods' as const, label: t('mods.menuMods'), icon: TOOLBAR_ICON.mods, first: 'installed' as const }] : []),
    { id: 'packs', label: t('mods.menuPacks'), icon: TOOLBAR_ICON.grid, first: 'packs-installed' },
    { id: 'options', label: t('mods.menuOptions'), icon: TOOLBAR_ICON.sliders, first: 'options' },
  ]

  const screens: { id: Tab; label: string; badge?: number }[] =
    family === 'mods'
      ? [
          { id: 'installed', label: t('mods.mobile.installed'), badge: modCount },
          { id: 'browse', label: t('mods.mobile.browse') },
          { id: 'modpack', label: hasModpack ? t('mods.replaceModpack') : t('mods.mobile.modpack') },
        ]
      : family === 'packs'
        ? [
            { id: 'packs-installed', label: t('mods.mobile.installed'), badge: packCount },
            { id: 'packs-shader', label: t('mods.mobile.shaders') },
            { id: 'packs-resourcepack', label: t('mods.mobile.resourcepacks') },
          ]
        : []

  return (
    <div className="flex shrink-0 flex-col gap-3 border-b border-white/[0.06] px-4 py-3">
      {/* Les familles : un commutateur, même largeur pour chacune. */}
      <div className="flex rounded-2xl bg-white/[0.04] p-1" role="tablist">
        {families.map((f) => (
          <button
            key={f.id}
            role="tab"
            aria-selected={family === f.id}
            onClick={() => { if (family !== f.id) onTab(f.first) }}
            className={`flex h-10 min-w-0 flex-1 items-center justify-center gap-1.5 rounded-xl text-[14px] font-semibold transition-colors duration-150 ${
              family === f.id ? 'bg-[rgba(75,63,207,0.35)] text-white' : 'text-white/55'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4 shrink-0"><path d={f.icon} /></svg>
            <span className="truncate">{f.label}</span>
          </button>
        ))}
      </div>

      {/* Les écrans de la famille, puis Importer et les mises à jour. */}
      <div className="flex items-center gap-2">
        <div className="-mx-4 flex min-w-0 flex-1 gap-2 overflow-x-auto overscroll-x-contain px-4">
          {screens.map((s) => (
            <button
              key={s.id}
              onClick={() => onTab(s.id)}
              aria-pressed={tab === s.id}
              className={`press flex h-10 shrink-0 items-center gap-1.5 rounded-full border px-4 text-[14px] font-semibold transition-colors duration-150 ${
                tab === s.id
                  ? 'border-[rgba(120,100,255,0.5)] bg-[rgba(75,63,207,0.2)] text-white'
                  : 'border-white/[0.08] bg-white/[0.03] text-white/65'
              }`}
            >
              {s.label}
              {s.badge !== undefined && s.badge > 0 && (
                <span className="rounded-full bg-white/[0.1] px-1.5 text-[12px] tabular-nums text-white/75">{s.badge}</span>
              )}
            </button>
          ))}
          {notice && <span className="flex shrink-0 items-center text-[13px] text-[rgba(179,163,255,0.9)]">{notice}</span>}
        </div>

        {updatesCount > 0 && family === 'mods' && (
          <button
            onClick={onUpdateAll}
            disabled={updatingAll}
            aria-label={t('mods.updateAllTitle')}
            className="press flex h-10 shrink-0 items-center gap-1 rounded-full border border-[rgba(250,204,21,0.3)] bg-[rgba(250,204,21,0.12)] px-3 text-[14px] font-bold text-[rgba(250,204,21,0.95)] disabled:opacity-50"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4"><path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z" /></svg>
            {updatingAll ? '…' : updatesCount}
          </button>
        )}
      </div>
    </div>
  )
}

/**
 * « Importer », en bouton flottant en bas à droite de l'écran des mods :
 * l'action principale, toujours sous le pouce, quel que soit l'écran de la
 * famille ouvert et la longueur de la liste. Posé par `ModsContent`, dont le
 * contenu garde en bas la place de le laisser passer sans rien cacher.
 */
export function MobileImportFab({ uploading, onImport }: { uploading: boolean; onImport: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onImport}
      disabled={uploading}
      className="press absolute bottom-[max(16px,env(safe-area-inset-bottom))] right-4 z-10 flex h-14 items-center gap-2 rounded-full bg-[#4B3FCF] pl-5 pr-6 text-[16px] font-semibold text-white shadow-[0_8px_28px_rgba(75,63,207,0.5),0_2px_8px_rgba(0,0,0,0.4)] disabled:opacity-60"
    >
      <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
      {uploading ? t('mods.importing') : t('mods.import')}
    </button>
  )
}
