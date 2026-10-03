import { useState } from 'react'
import { open as openFileDialog } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'
import type { SkinVariant } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ShareLinkButton } from '@/components/ui/ShareLinkButton'
import { ShareLinkInput } from '@/components/ui/ShareLinkInput'
import { SkinFace } from '@/components/ui/SkinFace'
import { decodeSkin, skinDataUri } from '@/lib/skinLink'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Les deux fenêtres de l'éditeur de skin qui font entrer ou sortir un skin
 * entier (boutons posés sur la vue 3D) :
 * - `SkinBaseModal` : partir d'une base — le skin d'un joueur par son pseudo,
 *   un PNG du disque, ou un lien `yuyuframe://skin/…` (`lib/skinLink.ts`) ;
 * - `SkinShareModal` : le dessin en cours, en lien.
 *
 * Aucune des trois sources ne range quoi que ce soit : une base n'est qu'un
 * point de départ, elle devient une étape de l'historique de l'éditeur (donc
 * annulable), et rien n'est enregistré avant « Enregistrer ce skin ».
 */

/** Charge une base dans l'éditeur. `variant` nul : à déduire de l'image. */
export type LoadSkinBase = (dataUri: string, variant: SkinVariant | null) => Promise<void>

export function SkinBaseModal({
  initialLink,
  onLoad,
  onClose,
}: {
  /** Lien cliqué (`App.tsx`) : ses parties s'ajoutent ici une à une. */
  initialLink?: string
  onLoad: LoadSkinBase
  onClose: () => void
}) {
  const t = useT()
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  /** Skin lu dans un lien, montré avant d'être pris. */
  const [linked, setLinked] = useState<{ dataUri: string; variant: SkinVariant } | null>(null)

  /** Une seule importation à la fois ; la fenêtre se ferme quand c'est fait. */
  const run = async (source: () => Promise<{ dataUri: string; variant: SkinVariant | null } | null>) => {
    if (busy) return
    setBusy(true)
    try {
      const base = await source()
      if (!base) return
      await onLoad(base.dataUri, base.variant)
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const fromName = () =>
    run(async () => {
      const skin = await api.skin.resolvePlayer(name.trim())
      return { dataUri: skin.data_uri, variant: skin.variant }
    })

  const fromFile = () =>
    run(async () => {
      const path = await openFileDialog({ multiple: false, filters: [{ name: 'PNG', extensions: ['png'] }] })
      if (typeof path !== 'string') return null
      return { dataUri: await api.skin.readFile(path), variant: null }
    })

  const readLink = async (text: string) => {
    setLinked(null)
    try {
      const skin = decodeSkin(await api.shareLink.readBytes('skin', text))
      const dataUri = skin && skinDataUri(skin.image)
      if (!skin || !dataUri) throw new Error(t('skinEditor.baseLinkBroken'))
      setLinked({ dataUri, variant: skin.slim ? 'slim' : 'classic' })
    } catch (e) {
      showError(e)
    }
  }

  return (
    <ModalShell title={t('skinEditor.baseTitle')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('skinEditor.baseHint')}</p>

        <Section title={t('skinEditor.baseFromName')} hint={t('skinEditor.baseNameHint')}>
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (name.trim()) void fromName()
            }}
          >
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('skinEditor.baseNamePlaceholder')}
              maxLength={16}
              spellCheck={false}
              className="min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3 py-2 text-[12.5px] text-txt-primary outline-none placeholder:text-txt-muted focus:border-accent/60"
            />
            <button type="submit" disabled={busy || !name.trim()} className={ACTION}>
              {t('skinEditor.baseImport')}
            </button>
          </form>
        </Section>

        <Section title={t('skinEditor.baseFromFile')} hint={t('skinEditor.baseFileHint')}>
          <button onClick={() => void fromFile()} disabled={busy} className={`w-fit ${ACTION}`}>
            {t('skinEditor.baseChooseFile')}
          </button>
        </Section>

        <Section title={t('skinEditor.baseFromLink')} hint={t('skinEditor.baseLinkHint')}>
          <ShareLinkInput kind="skin" value={initialLink} placeholder="yuyuframe://skin/…" onReady={readLink} />
          {linked && (
            <div className="flex items-center gap-3">
              <SkinFace dataUri={linked.dataUri} size={48} className="rounded-lg" />
              <button
                onClick={() => void run(async () => linked)}
                disabled={busy}
                className="w-fit rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
              >
                {t('skinEditor.baseUseLink')}
              </button>
            </div>
          )}
        </Section>
      </div>
    </ModalShell>
  )
}

const ACTION =
  'shrink-0 rounded-xl border border-line bg-surface-2 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40'

function Section({ title, hint, children }: { title: string; hint: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2 rounded-xl border border-line bg-surface-2/40 p-3.5">
      <div>
        <p className="text-[12.5px] font-semibold text-txt-primary">{title}</p>
        <p className="text-[11.5px] text-txt-muted">{hint}</p>
      </div>
      {children}
    </div>
  )
}

/** Le dessin en cours, en lien : `make` vient de l'éditeur, seul à tenir les pixels. */
export function SkinShareModal({ make, onClose }: { make: () => Promise<string[]>; onClose: () => void }) {
  const t = useT()
  return (
    <ModalShell title={t('skinEditor.shareTitle')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('skinEditor.shareDesc')}</p>
        <ShareLinkButton make={make} />
      </div>
    </ModalShell>
  )
}
