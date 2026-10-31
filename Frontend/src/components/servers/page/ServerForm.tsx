import { useEffect, useState } from 'react'
import type { ResourcePacks } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { useServerPing } from '@/lib/serverPing'
import { useT } from '@/i18n'
import { ServerRow } from './ServerRow'

export interface ServerFormValues {
  name: string
  address: string
  resourcePacks: ResourcePacks
}

const PACK_ORDER: ResourcePacks[] = ['prompt', 'enabled', 'disabled']

/** Une adresse qui a l'air d'en être une : on ne pingue qu'elle. */
function looksLikeAddress(a: string): boolean {
  return /^[a-z0-9.\-_]+(:\d{1,5})?$/i.test(a) && a.includes('.')
}

/**
 * Le formulaire d'un serveur, dans l'ordre de l'écran « Modification des
 * informations du serveur » du jeu : nom, adresse, packs de ressources, puis
 * Terminé / Annuler. L'aperçu en dessous est la ligne que le serveur aura
 * dans la liste, pinguée pendant la frappe — on voit tout de suite si
 * l'adresse est la bonne.
 */
export function ServerForm({
  title,
  initial,
  submitLabel,
  busy,
  onSubmit,
  onCancel,
}: {
  title: string
  initial?: Partial<ServerFormValues>
  submitLabel: string
  busy?: boolean
  onSubmit: (values: ServerFormValues) => void
  onCancel?: () => void
}) {
  const t = useT()
  const [name, setName] = useState(initial?.name ?? '')
  const [address, setAddress] = useState(initial?.address ?? '')
  const [packs, setPacks] = useState<ResourcePacks>(initial?.resourcePacks ?? 'prompt')
  const [pinged, setPinged] = useState(initial?.address && looksLikeAddress(initial.address) ? initial.address : '')

  useEffect(() => {
    const a = address.trim()
    const id = setTimeout(() => setPinged(looksLikeAddress(a) ? a : ''), 450)
    return () => clearTimeout(id)
  }, [address])

  const { state } = useServerPing(pinged || null)
  const canSubmit = address.trim().length > 0 && !busy

  const submit = () => canSubmit && onSubmit({ name, address, resourcePacks: packs })

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
      className="flex w-full flex-col gap-4"
    >
      <h2 className="text-center text-[16px] font-black text-white">{title}</h2>

      <Labelled label={t('servers.page.form.name')}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={64}
          placeholder={t('servers.page.form.namePlaceholder')}
          className={INPUT}
        />
      </Labelled>
      <Labelled label={t('servers.page.form.address')}>
        <input
          value={address}
          onChange={(e) => setAddress(e.target.value)}
          placeholder={t('servers.page.form.addressPlaceholder')}
          spellCheck={false}
          autoFocus={!initial?.address}
          className={INPUT}
        />
      </Labelled>

      <button
        type="button"
        onClick={() => setPacks(PACK_ORDER[(PACK_ORDER.indexOf(packs) + 1) % PACK_ORDER.length])}
        title={t('servers.page.form.packsHint')}
        className="h-10 rounded-lg border border-white/[0.12] bg-white/[0.05] text-[12.5px] font-semibold text-white transition-colors hover:border-white/30 hover:bg-white/[0.09]"
      >
        {t('servers.page.form.packs', { value: t(`servers.page.form.packs_${packs}`) })}
      </button>

      <div className="flex flex-col gap-1.5">
        <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('servers.page.form.preview')}</span>
        {pinged ? (
          <div className="rounded-xl border border-white/[0.06] bg-black/30">
            <ServerRow name={name.trim() || pinged} address={pinged} state={state} />
          </div>
        ) : (
          <div className="flex h-[86px] items-center justify-center rounded-xl border border-dashed border-white/[0.08] text-[12px] text-txt-muted">
            {t('servers.page.form.previewEmpty')}
          </div>
        )}
      </div>

      <div className="flex flex-col gap-2 pt-1">
        <Button type="submit" variant="primary" loading={busy} disabled={!canSubmit} fullWidth>
          {submitLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" onClick={onCancel} fullWidth>
            {t('servers.page.form.cancel')}
          </Button>
        )}
      </div>
    </form>
  )
}

const INPUT =
  'h-10 w-full rounded-lg border border-white/[0.14] bg-black/50 px-3 text-[13px] text-white outline-none transition-colors placeholder:text-white/25 focus:border-white/60'

function Labelled({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[12px] text-[#a0a0a0]">{label}</span>
      {children}
    </label>
  )
}
