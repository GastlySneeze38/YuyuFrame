import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { InstanceIcon } from '@/components/instances/InstanceIcon'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { loaderColor } from '@/lib/loader'
import { useT } from '@/i18n'
import { PANEL } from './bits'
import { ServerForm, type ServerFormValues } from './ServerForm'

/**
 * Ajouter un serveur : à gauche le même formulaire que l'écran du jeu (nom,
 * adresse, packs de ressources), à droite l'instance dans laquelle le
 * ranger. Le serveur arrive dans « Mes serveurs » et dans le menu
 * Multijoueur de cette instance.
 */
export function AddServerView({
  instanceId,
  onAdded,
  onCancel,
}: {
  instanceId: string | null
  onAdded: (instanceId: string) => void
  onCancel: () => void
}) {
  const t = useT()
  const instances = useStore((s) => s.instances)
  const [target, setTarget] = useState(instanceId ?? instances[0]?.id ?? '')
  const [saving, setSaving] = useState(false)
  // Une clé neuve vide le formulaire après un ajout réussi.
  const [formKey, setFormKey] = useState(0)

  useEffect(() => {
    if (instanceId) setTarget(instanceId)
  }, [instanceId])

  const submit = async (v: ServerFormValues) => {
    if (!target) return
    setSaving(true)
    try {
      const added = await api.servers.add(target, v.name, v.address, v.resourcePacks)
      const instance = instances.find((i) => i.id === target)?.name ?? ''
      if (added) {
        showNotice(t('servers.page.add.added', { name: v.name.trim() || v.address.trim(), instance }))
        setFormKey((k) => k + 1)
        onAdded(target)
      } else {
        showNotice(t('servers.page.add.duplicate', { instance }))
      }
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  if (instances.length === 0) {
    return <div className="flex flex-1 items-center justify-center text-[14px] font-bold text-white">{t('servers.page.mine.noInstance')}</div>
  }

  return (
    <div className="flex min-h-0 w-full min-w-0 flex-1 gap-4">
      {/* Le formulaire, comme dans le jeu */}
      <div className={`flex w-[clamp(340px,38%,460px)] flex-shrink-0 flex-col overflow-y-auto rounded-2xl p-6 ${PANEL}`}>
        <ServerForm key={formKey} title={t('servers.page.add.title')} submitLabel={t('servers.page.add.submit')} busy={saving} onSubmit={(v) => void submit(v)} onCancel={onCancel} />
      </div>

      {/* L'instance qui le recevra */}
      <div className={`flex min-w-0 flex-1 flex-col overflow-hidden rounded-2xl ${PANEL}`}>
        <div className="px-5 pb-3 pt-5">
          <h3 className="text-[15px] font-black text-white">{t('servers.page.add.instance')}</h3>
          <p className="mt-0.5 text-[11.5px] text-txt-muted">{t('servers.page.form.instanceHint')}</p>
        </div>
        <div className="grid min-h-0 flex-1 auto-rows-min grid-cols-[repeat(auto-fill,minmax(230px,1fr))] gap-2 overflow-y-auto px-4 pb-4">
          {instances.map((i) => {
            const on = target === i.id
            return (
              <button
                type="button"
                key={i.id}
                onClick={() => setTarget(i.id)}
                className={`flex items-center gap-3 rounded-xl border p-3 text-left transition-colors ${
                  on ? 'border-accent bg-accent/20' : 'border-white/[0.06] bg-white/[0.02] hover:border-white/20 hover:bg-white/[0.05]'
                }`}
              >
                <InstanceIcon instance={i} size={44} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="truncate text-[13px] font-bold text-white">{i.name}</span>
                  <span className="flex items-center gap-1.5 text-[11px] text-txt-muted">
                    <span style={{ color: loaderColor(i.loader) }}>{i.loader}</span>
                    <span>·</span>
                    <span>{i.mc_version}</span>
                  </span>
                </span>
                <span
                  className={`flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full border-2 ${on ? 'border-accent bg-accent' : 'border-white/20'}`}
                >
                  {on && (
                    <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth={3.5} width={10} height={10}>
                      <path d="M5 12l5 5L20 7" />
                    </svg>
                  )}
                </span>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
