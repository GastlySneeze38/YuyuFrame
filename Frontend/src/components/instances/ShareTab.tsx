import { useEffect, useMemo, useState } from 'react'
import { save as saveFileDialog } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'
import type { Instance, JvmShare, ShareGroup, ShareItem, ShareScan } from '@/types'
import { formatBytes, formatRam } from '@/lib/format'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Onglet « Partager » : donner son instance à quelqu'un sans rien héberger.
 *
 * Deux sorties, une seule sélection :
 * - **le fichier `.mrpack`**, qui marche toujours : ce qui est sur Modrinth ou
 *   CurseForge n'y est qu'une adresse, le reste y est copié ;
 * - **le lien**, sans fichier, possible seulement quand tout ce qui est coché
 *   est sur Modrinth. Il n'est pas caché quand il est impossible : il dit
 *   pourquoi, pour qu'on sache quoi décocher.
 *
 * L'inventaire vient du dossier lui-même (`commands/instance/share.rs`) : ce
 * qu'un mod range dans un dossier à son nom apparaît sous « Autres », décoché.
 */

const GROUP_ORDER: ShareGroup[] = ['mods', 'resourcepacks', 'shaderpacks', 'settings', 'servers', 'saves', 'other']

/** Groupes qu'un lien peut porter : ceux qui ont des fichiers de plateforme. */
const LINKABLE_GROUPS: ShareGroup[] = ['mods', 'resourcepacks', 'shaderpacks']

const VENDOR_LABELS: Record<string, string> = { temurin: 'Temurin', openj9: 'OpenJ9', graal: 'GraalVM' }

/**
 * Résumé d'une configuration Java : mémoire, JVM, ramasse-miettes, puis les
 * arguments en entier — ceux qu'on envoie comme ceux qu'on reçoit se lisent
 * avant d'être acceptés. Partagé par l'onglet et la fenêtre d'import.
 */
export function JvmSummary({ jvm }: { jvm: JvmShare }) {
  const t = useT()
  const auto = t('share.jvmAuto')
  return (
    <div className="flex flex-col gap-1">
      <p className="text-[11.5px] text-txt-secondary">
        {formatRam(jvm.ramMb)} · {VENDOR_LABELS[jvm.vendor] ?? auto} · GC {jvm.gcPolicy === 'auto' ? auto : jvm.gcPolicy}
        {jvm.argsMode === 'replace' && ` · ${t('share.jvmReplace')}`}
      </p>
      {jvm.args.length > 0 ? (
        <p className="break-all font-mono text-[10.5px] leading-relaxed text-txt-muted">{jvm.args.join(' ')}</p>
      ) : (
        <p className="text-[11px] text-txt-muted">{t('share.jvmNoArgs')}</p>
      )}
    </div>
  )
}

function displayName(item: ShareItem): string {
  // Dans les dossiers de contenu, le nom du fichier suffit ; ailleurs, le
  // chemin est ce qui parle (« config », « options.txt », « xaero »).
  return item.group === 'settings' || item.group === 'other' ? item.path : item.path.split('/').pop() ?? item.path
}

export function ShareTab({ instance }: { instance: Instance }) {
  const t = useT()
  const [scan, setScan] = useState<ShareScan | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [open, setOpen] = useState<Set<ShareGroup>>(new Set())
  const [includeJvm, setIncludeJvm] = useState(true)
  const [busy, setBusy] = useState<'file' | 'link' | null>(null)
  const [done, setDone] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api.share
      .scan(instance.id)
      .then((result) => {
        if (!alive) return
        setScan(result)
        setSelected(new Set(result.items.filter((i) => i.selected).map((i) => i.path)))
      })
      .catch(showError)
    return () => { alive = false }
  }, [instance.id])

  const groups = useMemo(() => {
    if (!scan) return []
    return GROUP_ORDER.map((group) => ({ group, items: scan.items.filter((i) => i.group === group) })).filter(
      (g) => g.items.length > 0,
    )
  }, [scan])

  const chosen = useMemo(() => scan?.items.filter((i) => selected.has(i.path)) ?? [], [scan, selected])
  const linked = chosen.filter((i) => i.source !== 'embedded')
  const embedded = chosen.filter((i) => i.source === 'embedded')
  const embeddedSize = embedded.reduce((sum, i) => sum + i.size, 0)
  const notLinkable = chosen.filter((i) => i.source !== 'modrinth' || !LINKABLE_GROUPS.includes(i.group))
  const serversChosen = chosen.some((i) => i.group === 'servers')

  const toggle = (path: string) => {
    setDone(null)
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }

  const toggleGroup = (items: ShareItem[]) => {
    setDone(null)
    setSelected((prev) => {
      const next = new Set(prev)
      const all = items.every((i) => next.has(i.path))
      for (const i of items) {
        if (all) next.delete(i.path)
        else next.add(i.path)
      }
      return next
    })
  }

  const toggleOpen = (group: ShareGroup) =>
    setOpen((prev) => {
      const next = new Set(prev)
      if (next.has(group)) next.delete(group)
      else next.add(group)
      return next
    })

  const exportFile = async () => {
    const safeName = instance.name.replace(/[\\/:*?"<>|]+/g, '').trim() || 'instance'
    const target = await saveFileDialog({
      defaultPath: `${safeName}.mrpack`,
      filters: [{ name: 'Modrinth pack', extensions: ['mrpack'] }],
    })
    if (!target) return
    setBusy('file')
    setDone(null)
    try {
      const result = await api.share.exportFile(instance.id, [...selected], includeJvm, target)
      setDone(t('share.fileDone', { linked: result.linked, embedded: result.embedded, size: formatBytes(result.size) }))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const copyLink = async () => {
    setBusy('link')
    setDone(null)
    try {
      const link = await api.share.link(instance.id, [...selected], includeJvm)
      await navigator.clipboard.writeText(link)
      setDone(t('share.linkDone', { count: link.length }))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  if (!scan) {
    return <p className="text-[12.5px] text-txt-muted">{t('share.scanning')}</p>
  }

  const sourceLabel: Record<ShareItem['source'], string> = {
    modrinth: 'Modrinth',
    curseforge: 'CurseForge',
    embedded: t('share.embedded'),
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5">
        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">{t('share.title')}</p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('share.desc')}</p>
      </div>

      {scan.lookupFailed && <p className="text-[11.5px] text-warning">⚠ {t('share.lookupFailed')}</p>}

      {/* La configuration Java : celle que le lancement emploie, donc la
          config JVM reliée quand il y en a une. */}
      <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-line bg-surface-2 px-3.5 py-2.5">
        <input
          type="checkbox"
          checked={includeJvm}
          onChange={() => { setDone(null); setIncludeJvm((v) => !v) }}
          className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-accent"
        />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <p className="text-[12.5px] font-semibold text-txt-primary">
            {t('share.jvmTitle')}
            {scan.jvmProfile && <span className="font-normal text-txt-muted"> · {scan.jvmProfile}</span>}
          </p>
          <JvmSummary jvm={scan.jvm} />
          {scan.jvmRejected.length > 0 && (
            <p className="text-[11px] text-warning">
              ⚠ {t('share.jvmRejectedOut', { count: scan.jvmRejected.length })}{' '}
              <span className="break-all font-mono text-[10.5px]">{scan.jvmRejected.join(' ')}</span>
            </p>
          )}
        </div>
      </label>

      <div className="flex flex-col gap-2">
        {groups.map(({ group, items }) => {
          const count = items.filter((i) => selected.has(i.path)).length
          const all = count === items.length
          const size = items.reduce((sum, i) => sum + i.size, 0)
          const expanded = open.has(group)
          return (
            <div key={group} className="rounded-xl border border-line bg-surface-2">
              <div className="flex items-center gap-3 px-3.5 py-2.5">
                <input
                  type="checkbox"
                  checked={all}
                  ref={(el) => { if (el) el.indeterminate = count > 0 && !all }}
                  onChange={() => toggleGroup(items)}
                  className="h-3.5 w-3.5 shrink-0 accent-accent"
                />
                <button onClick={() => toggleOpen(group)} className="flex min-w-0 flex-1 items-center gap-2 text-left">
                  <span className="truncate text-[12.5px] font-semibold text-txt-primary">{t(`share.group_${group}`)}</span>
                  <span className="shrink-0 text-[11px] text-txt-muted">
                    {count}/{items.length} · {formatBytes(size)}
                  </span>
                  <svg
                    viewBox="0 0 10 6" fill="currentColor" width={8} height={5}
                    className={`ml-auto shrink-0 text-txt-muted transition-transform ${expanded ? 'rotate-0' : '-rotate-90'}`}
                  >
                    <path d="M0 0l5 6 5-6z" />
                  </svg>
                </button>
              </div>
              {(group === 'servers' || group === 'saves' || group === 'other') && (
                <p className="-mt-1 px-3.5 pb-2.5 pl-[38px] text-[11px] text-txt-muted">{t(`share.hint_${group}`)}</p>
              )}
              {expanded && (
                <div className="flex flex-col border-t border-line-soft py-1">
                  {items.map((item) => (
                    <label
                      key={item.path}
                      className="flex cursor-pointer items-center gap-3 px-3.5 py-1.5 hover:bg-white/[0.03]"
                    >
                      <input
                        type="checkbox"
                        checked={selected.has(item.path)}
                        onChange={() => toggle(item.path)}
                        className="h-3.5 w-3.5 shrink-0 accent-accent"
                      />
                      <span className="min-w-0 flex-1 truncate text-[12px] text-txt-secondary">{displayName(item)}</span>
                      <span
                        className={`shrink-0 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${
                          item.source === 'embedded' ? 'bg-white/[0.06] text-txt-muted' : 'bg-accent/15 text-accent-hover'
                        }`}
                      >
                        {sourceLabel[item.source]}
                      </span>
                      <span className="w-16 shrink-0 text-right text-[11px] text-txt-muted">{formatBytes(item.size)}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>

      <p className="text-[12px] text-txt-secondary">
        {t('share.summary', { linked: linked.length, embedded: embedded.length, size: formatBytes(embeddedSize) })}
      </p>
      {serversChosen && <p className="text-[11.5px] text-warning">⚠ {t('share.serversWarning')}</p>}

      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap gap-2">
          <button
            onClick={exportFile}
            disabled={busy !== null || chosen.length === 0}
            className="rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy === 'file' ? t('share.exporting') : t('share.saveFile')}
          </button>
          <button
            onClick={copyLink}
            disabled={busy !== null || linked.length === 0 || notLinkable.length > 0}
            className="rounded-xl border border-line bg-surface-2 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy === 'link' ? t('share.linking') : t('share.copyLink')}
          </button>
        </div>
        {notLinkable.length > 0 && (
          <p className="text-[11px] text-txt-muted">{t('share.linkUnavailable', { count: notLinkable.length })}</p>
        )}
        {done && <p className="text-[12px] text-[rgba(134,239,172,0.85)]">{done}</p>}
        <p className="text-[11px] text-txt-muted">{t('share.howToReceive')}</p>
      </div>
    </div>
  )
}
