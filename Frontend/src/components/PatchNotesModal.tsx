import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import Markdown, { LAUNCHER_THEME } from '@/components/ui/Markdown'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/**
 * Notes de version, publiées depuis le back-office.
 *
 * ── D'où vient le texte ───────────────────────────────────────────────────
 * De la table `patch_notes` du site (back-office → Contenu → Patch notes),
 * lue par `api.patchNotes.latest()`. C'est exactement la note affichée sur
 * yuyuframe.eu et annoncée sur Discord : une seule écriture, trois surfaces.
 *
 * ── Le rendu ──────────────────────────────────────────────────────────────
 * Le même que celui du site, volontairement : pastille de version, mention
 * « dernière version », date, titre, puis le corps rendu par le **même**
 * composant Markdown (copie de `Server/Website/Frontend/src/components`). Une
 * note relue sur le site doit être reconnaissable au premier coup d'œil, et
 * surtout se comporter pareil — un titre de section reste un titre, une liste
 * reste une liste.
 *
 * Avant, le corps était découpé en lignes transformées chacune en puce : les
 * `##` et les `**` d'un texte écrit pour le site s'affichaient tels quels, au
 * milieu d'une liste à puces qui n'en était pas une.
 */

/** Date longue, dans la langue du système — comme sur le site. */
function formatDate(iso: string | null): string | null {
  if (!iso) return null
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' })
}

export function PatchNotesModal({
  title,
  version,
  body,
  publishedAt,
  onClose,
  counter,
}: QueuedModalProps & { title: string; version: string; body: string; publishedAt: string | null }) {
  const t = useT()
  const date = formatDate(publishedAt)

  return (
    <ModalShell title={t('patchNotes.title')} onClose={onClose} maxWidth="max-w-2xl" counter={counter}>
      <div className="flex flex-col gap-5">
        {/* Même en-tête que la carte du site : la version d'abord, puis ce
            qu'elle est, puis quand. */}
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="rounded-full border border-accent/40 bg-accent/15 px-3 py-1 text-[12px] font-semibold text-accent-hover">
            {version}
          </span>
          <span className="rounded-full border border-line bg-surface-2 px-3 py-1 text-[12px] font-medium text-txt-secondary">
            {t('patchNotes.latest')}
          </span>
          {date && <span className="text-[12px] text-txt-muted">{date}</span>}
        </div>

        <h2 className="text-[17px] font-bold leading-snug text-txt-primary">{title}</h2>

        {/* Le défilement porte sur le corps seul : l'en-tête et le bouton
            restent en place, on ne perd pas le fil en remontant. */}
        <div className="-mr-2 max-h-[52vh] overflow-y-auto pr-2">
          {body.trim() ? (
            <Markdown text={body} theme={LAUNCHER_THEME} />
          ) : (
            <p className="text-[13px] text-txt-muted">{t('patchNotes.empty')}</p>
          )}
        </div>

        <Button variant="primary" onClick={onClose} fullWidth>
          {t('patchNotes.done')}
        </Button>
      </div>
    </ModalShell>
  )
}
