import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { Instance } from '@/types'
import type { HorizonFileItem, HorizonFolder } from '@/types/horizon'
import { formatBytes, formatDate } from '@/lib/format'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'

/** Les dossiers proposés, dans l'ordre. Pas de mods natifs : l'appli Android
 *  ne les charge pas (Play Store). */
const FOLDERS: HorizonFolder[] = ['worlds', 'resource_packs', 'shader_packs', 'mods', 'screenshots', 'skins', 'configuration']

/**
 * Le gestionnaire de fichiers d'une instance Horizon dans l'appli Android, par
 * l'API du moteur (`horizon-engine-api`, `files`) : seulement les dossiers
 * qu'un joueur peut voir, sans jamais en sortir. Importer passe par le
 * sélecteur d'Android (un `<input type="file">`, seul moyen de lire ses
 * adresses `content://`) et n'écrase jamais rien. Rien ne s'écrit pendant que
 * le jeu tourne dans l'instance : le Rust refuse, et le dit.
 */
export function HorizonFilesTab({ instance }: { instance: Instance }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const [folder, setFolder] = useState<HorizonFolder>('worlds')
  const [path, setPath] = useState<string[]>([])
  const [items, setItems] = useState<HorizonFileItem[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [confirming, setConfirming] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<{ from: string; to: string } | null>(null)
  const [newFolder, setNewFolder] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const reload = useCallback(() => {
    setItems(null)
    api.horizonEngine
      .list(instance.id, folder, path)
      .then(setItems)
      .catch((e) => { setItems([]); showError(e) })
  }, [instance.id, folder, path])

  useEffect(() => { reload() }, [reload])

  const readOnly = folder === 'configuration'
  const label = (f: HorizonFolder) => t(`horizonEngine.folders.${f}`)

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await action()
      reload()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const importFiles = async (files: FileList | null) => {
    if (!files?.length) return
    await run(async () => {
      const taken: string[] = []
      for (const file of Array.from(files)) {
        taken.push(await api.horizonEngine.importFile(instance.id, folder, path, file))
      }
      setNotice(t('horizonEngine.imported', { name: taken.join(', ') }))
    })
    if (input.current) input.current.value = ''
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Les dossiers : des pastilles qui passent à la ligne, toutes visibles. */}
      <div className="flex flex-wrap gap-2">
        {FOLDERS.map((f) => (
          <button
            key={f}
            onClick={() => { setFolder(f); setPath([]); setNotice(''); setConfirming(null) }}
            aria-pressed={folder === f}
            className={`press h-9 rounded-full border px-3.5 text-[13px] font-semibold transition-colors duration-150 ${
              folder === f
                ? 'border-[rgb(var(--accent-hover)/0.5)] bg-[rgb(var(--accent)/0.2)] text-white'
                : 'border-white/[0.08] bg-white/[0.03] text-white/65'
            }`}
          >
            {label(f)}
          </button>
        ))}
      </div>

      {/* Où l'on est, et de quoi remonter. */}
      <div className="flex min-w-0 items-center gap-1 text-[13px] text-txt-secondary">
        <button className="press shrink-0 font-semibold text-txt-primary" onClick={() => setPath([])}>{label(folder)}</button>
        {path.map((p, i) => (
          <span key={i} className="flex min-w-0 items-center gap-1">
            <span className="text-txt-muted">/</span>
            <button className="press truncate" onClick={() => setPath(path.slice(0, i + 1))}>{p}</button>
          </span>
        ))}
      </div>

      {!readOnly && (
        <div className="flex gap-2">
          <button
            disabled={busy}
            onClick={() => input.current?.click()}
            className="press h-10 flex-1 rounded-xl bg-[rgb(var(--accent))] text-[14px] font-semibold text-white disabled:opacity-50"
          >
            {t('horizonEngine.import')}
          </button>
          <button
            disabled={busy}
            onClick={() => setNewFolder('')}
            className="press h-10 flex-1 rounded-xl border border-white/[0.1] bg-white/[0.04] text-[14px] font-semibold text-white/80 disabled:opacity-50"
          >
            {t('horizonEngine.newFolder')}
          </button>
          <input ref={input} type="file" multiple className="hidden" onChange={(e) => void importFiles(e.target.files)} />
        </div>
      )}

      {newFolder !== null && (
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            const name = newFolder.trim()
            if (name) void run(() => api.horizonEngine.mkdir(instance.id, folder, [...path, name]))
            setNewFolder(null)
          }}
        >
          <input
            autoFocus
            value={newFolder}
            onChange={(e) => setNewFolder(e.target.value)}
            placeholder={t('horizonEngine.folderName')}
            className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3 text-[14px] text-txt-primary outline-none focus:border-accent/60"
          />
          <button type="submit" className="press h-10 rounded-xl bg-[rgb(var(--accent))] px-4 text-[14px] font-semibold text-white">{t('horizonEngine.create')}</button>
          <button type="button" onClick={() => setNewFolder(null)} className="press h-10 rounded-xl px-3 text-[14px] text-txt-secondary">{t('common.cancel')}</button>
        </form>
      )}

      {notice && <p className="text-[13px] text-[rgba(179,163,255,0.9)]">{notice}</p>}

      {items === null ? (
        <p className="text-[13px] text-txt-muted">{t('common.loading')}</p>
      ) : items.length === 0 ? (
        <p className="text-[13px] text-txt-muted">{t('horizonEngine.empty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-white/[0.05] rounded-2xl border border-white/[0.06] bg-white/[0.02]">
          {items.map((item) => (
            <li key={item.name} className="flex flex-col gap-2 px-3.5 py-3">
              {renaming?.from === item.name ? (
                <form
                  className="flex gap-2"
                  onSubmit={(e) => {
                    e.preventDefault()
                    const to = renaming.to.trim()
                    if (to && to !== item.name) void run(() => api.horizonEngine.rename(instance.id, folder, [...path, item.name], to))
                    setRenaming(null)
                  }}
                >
                  <input
                    autoFocus
                    value={renaming.to}
                    onChange={(e) => setRenaming({ from: item.name, to: e.target.value })}
                    className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3 text-[14px] text-txt-primary outline-none focus:border-accent/60"
                  />
                  <button type="submit" className="press h-10 rounded-xl bg-[rgb(var(--accent))] px-4 text-[14px] font-semibold text-white">{t('common.save')}</button>
                </form>
              ) : (
                <div className="flex items-center gap-3">
                  <button
                    className="press flex min-w-0 flex-1 items-center gap-3 text-left"
                    disabled={!item.dir}
                    onClick={() => item.dir && setPath([...path, item.name])}
                  >
                    <span className="text-[18px]" aria-hidden>{item.dir ? '📁' : '📄'}</span>
                    <span className="min-w-0">
                      <span className="block truncate text-[14px] font-semibold text-txt-primary">{item.name}</span>
                      <span className="block text-[12px] text-txt-muted">
                        {item.dir ? '' : `${formatBytes(item.size)} · `}
                        {item.modified ? formatDate(item.modified, language, { day: 'numeric', month: 'short', year: 'numeric' }) : ''}
                      </span>
                    </span>
                  </button>
                  {!readOnly && (
                    <div className="flex shrink-0 gap-1">
                      <button
                        disabled={busy}
                        onClick={() => setRenaming({ from: item.name, to: item.name })}
                        className="press h-9 rounded-lg px-2.5 text-[13px] text-txt-secondary"
                      >
                        {t('horizonEngine.rename')}
                      </button>
                      {/* La confirmation dans le bouton : une fenêtre par-dessus
                          la fenêtre serait disproportionnée. */}
                      <button
                        disabled={busy}
                        onClick={() => {
                          if (confirming !== item.name) return setConfirming(item.name)
                          setConfirming(null)
                          void run(() => api.horizonEngine.remove(instance.id, folder, [...path, item.name]))
                        }}
                        className={`press h-9 rounded-lg px-2.5 text-[13px] font-semibold ${
                          confirming === item.name ? 'bg-red-500/20 text-red-300' : 'text-red-300/80'
                        }`}
                      >
                        {confirming === item.name ? t('common.confirm') : t('common.delete')}
                      </button>
                    </div>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
