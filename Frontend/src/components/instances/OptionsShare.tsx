import { useEffect, useState } from 'react'
import { open as openFileDialog, save as saveFileDialog } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'
import type { OptionsLinkInfo } from '@/types'
import { ModalShell } from '@/components/ui/ModalShell'
import { ShareLinkButton } from '@/components/ui/ShareLinkButton'
import { ShareLinkInput } from '@/components/ui/ShareLinkInput'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Options du client intégré et partage des options par lien — onglet
 * « Paramètres du jeu » (`instances/settings/options_share.rs`).
 *
 * Trois blocs, du plus personnel au plus partagé :
 * - le `.properties` du client YuyuFrame en fichier (garder, redonner) ;
 * - un lien `yuyuframe://options/…` avec `options.txt` et/ou le client ;
 * - appliquer un lien reçu à cette instance (aussi ouvert par un lien
 *   cliqué, `OptionsLinkModal`).
 *
 * Les mots de passe du module Macros ne sortent jamais, et un lien ne porte
 * pas les macros elles-mêmes (une macro tape une commande à la place du
 * joueur) : c'est dit à l'écran, pour qu'on ne s'étonne pas de leur absence.
 */

const card = 'flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5'
const cardTitle = 'text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted'
const secondaryButton =
  'shrink-0 rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:border-accent/40 hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40'

function Line({ title, desc, action }: { title: string; desc: string; action: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="text-[12.5px] font-semibold text-txt-primary">{title}</p>
        <p className="text-[11.5px] text-txt-muted">{desc}</p>
      </div>
      {action}
    </div>
  )
}

/** Le `.properties` du client YuyuFrame, en fichier. */
export function ClientOptionsTransfer({ instanceId }: { instanceId: string }) {
  const t = useT()
  const [busy, setBusy] = useState<'export' | 'import' | null>(null)
  const [result, setResult] = useState<string | null>(null)

  const exportFile = async () => {
    const path = await saveFileDialog({
      defaultPath: `yuyuframe-${instanceId}.properties`,
      filters: [{ name: 'Properties', extensions: ['properties'] }],
    })
    if (!path) return
    setBusy('export')
    setResult(null)
    try {
      const count = await api.optionsShare.exportClient(instanceId, path)
      setResult(t('optionsShare.clientExported', { count }))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const importFile = async () => {
    const path = await openFileDialog({ filters: [{ name: 'Properties', extensions: ['properties'] }] })
    if (typeof path !== 'string') return
    setBusy('import')
    setResult(null)
    try {
      const count = await api.optionsShare.importClient(instanceId, path)
      setResult(t('optionsShare.clientImported', { count }))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className={card}>
        <p className={cardTitle}>{t('optionsShare.clientTitle')}</p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('optionsShare.clientDesc')}</p>
        <p className="mt-1 text-[11.5px] text-txt-muted">{t('optionsShare.passwordsNever')}</p>
      </div>
      <Line
        title={t('optionsShare.clientExport')}
        desc={t('optionsShare.clientExportDesc')}
        action={
          <button onClick={exportFile} disabled={busy !== null} className={secondaryButton}>
            {busy === 'export' ? t('common.loading') : t('instancesPage.optionsDownloadAction')}
          </button>
        }
      />
      <Line
        title={t('optionsShare.clientImport')}
        desc={t('optionsShare.clientImportDesc')}
        action={
          <button onClick={importFile} disabled={busy !== null} className={secondaryButton}>
            {busy === 'import' ? t('common.loading') : t('optionsShare.importAction')}
          </button>
        }
      />
      {result && <p className="text-[12px] text-[rgba(134,239,172,0.85)]">{result}</p>}
    </div>
  )
}

/** Créer un lien avec les options de cette instance. */
export function OptionsLinkCreate({ instanceId }: { instanceId: string }) {
  const t = useT()
  const [game, setGame] = useState(true)
  const [client, setClient] = useState(true)

  return (
    <div className="flex flex-col gap-3">
      <div className={card}>
        <p className={cardTitle}>{t('optionsShare.linkTitle')}</p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('optionsShare.linkDesc')}</p>
      </div>
      <div className="flex flex-wrap gap-4">
        <Check checked={game} onChange={setGame} label={t('optionsShare.game')} />
        <Check checked={client} onChange={setClient} label={t('optionsShare.client')} />
      </div>
      <p className="text-[11px] text-txt-muted">{t('optionsShare.linkPrivacy')}</p>
      <ShareLinkButton make={() => api.optionsShare.link(instanceId, game, client)} disabled={!game && !client} />
    </div>
  )
}

function Check({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-[12.5px] text-txt-secondary">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="h-3.5 w-3.5 accent-accent"
      />
      {label}
    </label>
  )
}

/**
 * Appliquer un lien d'options reçu à une instance. Le lien se colle, ou
 * arrive déjà rempli (`initialLink`) quand on l'a cliqué.
 */
export function OptionsLinkApply({ instanceId, initialLink }: { instanceId: string; initialLink?: string }) {
  const t = useT()
  const [link, setLink] = useState<string | null>(null)
  const [info, setInfo] = useState<OptionsLinkInfo | null>(null)
  const [game, setGame] = useState(true)
  const [client, setClient] = useState(true)
  const [busy, setBusy] = useState(false)
  const [applied, setApplied] = useState<OptionsLinkInfo | null>(null)

  useEffect(() => {
    if (!link) return
    let alive = true
    setInfo(null)
    setApplied(null)
    api.optionsShare
      .preview(link)
      .then((i) => alive && setInfo(i))
      .catch((e) => {
        if (!alive) return
        showError(e)
        setLink(null)
      })
    return () => { alive = false }
  }, [link])

  const apply = async () => {
    if (!link) return
    setBusy(true)
    try {
      setApplied(await api.optionsShare.apply(instanceId, link, game, client))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1.5">
        {!initialLink && <p className="text-[12.5px] font-semibold text-txt-primary">{t('optionsShare.applyTitle')}</p>}
        <ShareLinkInput kind="options" value={initialLink} placeholder="yuyuframe://options/…" onReady={setLink} />
      </div>

      {info && !applied && (
        <>
          <div className="flex flex-col gap-2">
            {info.game !== null && (
              <Check checked={game} onChange={setGame} label={t('optionsShare.gameCount', { count: info.game })} />
            )}
            {info.client !== null && (
              <Check checked={client} onChange={setClient} label={t('optionsShare.clientCount', { count: info.client })} />
            )}
          </div>
          <p className="text-[11px] text-txt-muted">{t('optionsShare.applyMerge')}</p>
          <button
            onClick={apply}
            disabled={busy || (!(game && info.game !== null) && !(client && info.client !== null))}
            className="w-fit rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
          >
            {busy ? t('common.loading') : t('optionsShare.applyAction')}
          </button>
        </>
      )}

      {applied && (
        <p className="text-[12px] text-[rgba(134,239,172,0.85)]">
          {t('optionsShare.applied', { game: applied.game ?? 0, client: applied.client ?? 0 })}
        </p>
      )}
    </div>
  )
}

/** Fenêtre ouverte par un lien `yuyuframe://options/…` cliqué : choisir
 *  l'instance qui le reçoit, puis le même écran que dans l'onglet. */
export function OptionsLinkModal({ link, onClose }: { link: string; onClose: () => void }) {
  const t = useT()
  const instances = useStore((s) => s.instances)
  const selectedInstanceId = useStore((s) => s.selectedInstanceId)
  const [target, setTarget] = useState(selectedInstanceId ?? instances[0]?.id ?? '')

  return (
    <ModalShell title={t('optionsShare.modalTitle')} onClose={onClose}>
      <div className="flex flex-col gap-4 py-2">
        {instances.length === 0 ? (
          <p className="text-[12.5px] text-txt-secondary">{t('optionsShare.noInstance')}</p>
        ) : (
          <>
            <div className="flex flex-col gap-1.5">
              <p className={cardTitle}>{t('optionsShare.targetInstance')}</p>
              <select
                value={target}
                onChange={(e) => setTarget(e.target.value)}
                className="h-10 rounded-xl border border-line bg-black/40 px-3 text-[13px] text-txt-primary outline-none focus:border-accent/60"
              >
                {instances.map((i) => (
                  <option key={i.id} value={i.id}>{i.name}</option>
                ))}
              </select>
            </div>
            {target && <OptionsLinkApply key={target} instanceId={target} initialLink={link} />}
          </>
        )}
      </div>
    </ModalShell>
  )
}
