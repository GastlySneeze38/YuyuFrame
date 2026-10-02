import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ShareLinkButton } from '@/components/ui/ShareLinkButton'
import { ShareLinkInput } from '@/components/ui/ShareLinkInput'
import {
  applySettingChanges,
  currentSettingsText,
  settingsChanges,
  SHARED_SETTINGS,
  type SettingChange,
} from '@/lib/launcherSettingsShare'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Partage des paramètres du launcher (`lib/launcherSettingsShare.ts`) :
 * - `SettingsShareModal` : « Partager » dans l'en-tête de la page — ce qui
 *   part, le lien, et un champ pour appliquer un lien reçu ;
 * - `SettingsLinkModal` : un lien à appliquer (paramètres optimisés du
 *   créateur, ou lien cliqué, `App.tsx`).
 *
 * Rien ne s'applique sans être montré : la liste des changements (« actuel →
 * nouveau ») précède le bouton, et un réglage déjà à la bonne valeur n'y
 * figure pas.
 */

/** Appliquer un lien de paramètres : aperçu des changements, puis application. */
export function SettingsLinkApply({ initialLink, onApplied }: { initialLink?: string; onApplied?: () => void }) {
  const t = useT()
  const [changes, setChanges] = useState<SettingChange[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [applied, setApplied] = useState<number | null>(null)

  const load = async (text: string) => {
    setChanges(null)
    setApplied(null)
    try {
      setChanges(await settingsChanges(await api.shareLink.read('settings', text)))
    } catch (e) {
      showError(e)
    }
  }

  const apply = async () => {
    if (!changes) return
    setBusy(true)
    try {
      await applySettingChanges(changes)
      setApplied(changes.length)
      setChanges(null)
      onApplied?.()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <ShareLinkInput kind="settings" value={initialLink} placeholder="yuyuframe://settings/…" onReady={load} />

      {changes && changes.length === 0 && <p className="text-[12px] text-txt-muted">{t('settingsShare.nothingChanges')}</p>}

      {changes && changes.length > 0 && (
        <>
          <div className="flex flex-col divide-y divide-white/5 rounded-xl border border-line bg-surface-2">
            {changes.map(({ setting, current, next }) => (
              <div key={setting.key} className="flex items-center justify-between gap-3 px-3.5 py-2">
                <span className="min-w-0 truncate text-[12px] text-txt-secondary">{t(setting.label)}</span>
                <span className="shrink-0 text-[12px]">
                  <span className="text-txt-muted">{setting.format(current, t)}</span>
                  <span className="px-1.5 text-txt-muted">→</span>
                  <span className="font-semibold text-txt-primary">{setting.format(next, t)}</span>
                </span>
              </div>
            ))}
          </div>
          <button
            onClick={apply}
            disabled={busy}
            className="w-fit rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
          >
            {busy ? t('common.loading') : t('settingsShare.applyAction', { count: changes.length })}
          </button>
        </>
      )}

      {applied !== null && (
        <p className="text-[12px] text-[rgba(134,239,172,0.85)]">{t('settingsShare.applied', { count: applied })}</p>
      )}
    </div>
  )
}

export function SettingsShareModal({ onClose, onApplied }: { onClose: () => void; onApplied?: () => void }) {
  const t = useT()
  const [current, setCurrent] = useState<Record<string, string> | null>(null)

  // Ce qui partira, affiché tel quel : on sait ce qu'on envoie.
  useEffect(() => {
    let alive = true
    currentSettingsText()
      .then((text) => {
        if (!alive) return
        const values: Record<string, string> = {}
        for (const line of text.split('\n')) {
          const sep = line.indexOf('=')
          values[line.slice(0, sep)] = line.slice(sep + 1)
        }
        setCurrent(values)
      })
      .catch(() => setCurrent({}))
    return () => { alive = false }
  }, [])

  return (
    <ModalShell title={t('settingsShare.shareTitle')} onClose={onClose}>
      <div className="flex flex-col gap-4 py-2">
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('settingsShare.shareDesc')}</p>

        {current && (
          <div className="flex flex-col divide-y divide-white/5 rounded-xl border border-line bg-surface-2">
            {SHARED_SETTINGS.map((s) => {
              const raw = current[s.key]
              const value = raw === undefined ? undefined : s.parse(raw)
              return (
                <div key={s.key} className="flex items-center justify-between gap-3 px-3.5 py-1.5">
                  <span className="min-w-0 truncate text-[12px] text-txt-secondary">{t(s.label)}</span>
                  <span className="shrink-0 text-[12px] font-semibold text-txt-primary">
                    {value === undefined ? '—' : s.format(value, t)}
                  </span>
                </div>
              )
            })}
          </div>
        )}
        <p className="text-[11px] text-txt-muted">{t('settingsShare.notShared')}</p>

        <ShareLinkButton make={async () => api.shareLink.build('settings', await currentSettingsText())} />

        <div className="h-px bg-white/6" />

        <p className="text-[12.5px] font-semibold text-txt-primary">{t('settingsShare.applyTitle')}</p>
        <SettingsLinkApply onApplied={onApplied} />
      </div>
    </ModalShell>
  )
}

/** Un lien précis à appliquer : paramètres optimisés, ou lien cliqué. */
export function SettingsLinkModal({
  link,
  title,
  desc,
  onClose,
  onApplied,
}: {
  link: string
  /** Par défaut : « Paramètres partagés » (lien cliqué). */
  title?: string
  desc?: string
  onClose: () => void
  /** La page Paramètres relit ce qu'elle garde hors du magasin (statistiques). */
  onApplied?: () => void
}) {
  const t = useT()
  return (
    <ModalShell title={title ?? t('settingsShare.receivedTitle')} onClose={onClose}>
      <div className="flex flex-col gap-4 py-2">
        {desc && <p className="text-[12.5px] leading-relaxed text-txt-secondary">{desc}</p>}
        {link ? (
          <SettingsLinkApply initialLink={link} onApplied={onApplied} />
        ) : (
          <p className="text-[12.5px] text-txt-muted">{t('settingsShare.optimizedSoon')}</p>
        )}
      </div>
    </ModalShell>
  )
}
