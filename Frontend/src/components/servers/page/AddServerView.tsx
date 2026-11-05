import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { InstanceIcon } from '@/components/instances/InstanceIcon'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { loaderColor } from '@/lib/loader'
import { useT } from '@/i18n'
import { NoInstance, PANEL } from './bits'
import { ServerForm, type ServerFormValues } from './ServerForm'
import { useIsMobile } from '@/mobile/useIsMobile'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { CARD } from '@/mobile/ui/kit'

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
  const mobile = useIsMobile()
  const [picking, setPicking] = useState(false)

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
    return (
      <div className="w-full px-4 pt-1 sm:px-0">
        <NoInstance />
      </div>
    )
  }

  // Téléphone : l'instance qui recevra le serveur d'abord (on la choisit dans
  // une feuille), puis le formulaire du jeu. Sur PC, elle est à droite du
  // formulaire ; empilée dessous, on la découvrirait après « Ajouter ».
  if (mobile) {
    const chosen = instances.find((i) => i.id === target)
    return (
      <div className="min-h-0 w-full flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-1">
        <div className="flex flex-col gap-6">
          <div className="flex flex-col gap-2">
            <span className="px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('servers.page.add.instance')}</span>
            <button onClick={() => setPicking(true)} className={`press flex min-h-[64px] items-center gap-3 px-3.5 py-2.5 text-left ${CARD}`}>
              {chosen && <InstanceIcon instance={chosen} size={44} />}
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[15px] font-semibold text-white">{chosen?.name ?? '—'}</span>
                {chosen && (
                  <span className="truncate text-[13px] text-white/55"><span style={{ color: loaderColor(chosen.loader) }}>{chosen.loader}</span> · {chosen.mc_version}</span>
                )}
              </span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-white/50"><path d="M6 9l6 6 6-6" /></svg>
            </button>
            <p className="px-1 text-[13px] leading-relaxed text-white/55">{t('servers.page.form.instanceHint')}</p>
          </div>
          <div className={`px-4 py-5 ${CARD}`}>
            <ServerForm key={formKey} title={t('servers.page.add.title')} submitLabel={t('servers.page.add.submit')} busy={saving} onSubmit={(v) => void submit(v)} onCancel={onCancel} />
          </div>
        </div>

        <Sheet open={picking} onClose={() => setPicking(false)} label={t('servers.page.add.instance')} header={<SheetTitle>{t('servers.page.add.instance')}</SheetTitle>} bodyClassName="flex flex-col gap-1 px-2 pb-3">
          {instances.map((i) => (
            <button
              key={i.id}
              onClick={() => { setTarget(i.id); setPicking(false) }}
              aria-pressed={target === i.id}
              className={`press flex min-h-[64px] items-center gap-3 rounded-2xl px-3 text-left ${target === i.id ? 'bg-accent/[0.14]' : 'active:bg-white/[0.04]'}`}
            >
              <InstanceIcon instance={i} size={40} />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[15px] font-semibold text-white">{i.name}</span>
                <span className="truncate text-[13px] text-white/55"><span style={{ color: loaderColor(i.loader) }}>{i.loader}</span> · {i.mc_version}</span>
              </span>
              {target === i.id && (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.6} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-[#a29bfe]"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
              )}
            </button>
          ))}
        </Sheet>
      </div>
    )
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
