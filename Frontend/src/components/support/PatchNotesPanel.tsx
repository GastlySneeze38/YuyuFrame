import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import type { PatchNote } from '@/api/client'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import Markdown, { LAUNCHER_THEME } from '@/components/ui/Markdown'
import { useT } from '@/i18n'
import { useIsMobile } from '@/mobile/useIsMobile'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'

/**
 * Notes de version, dans l'onglet Support.
 *
 * ── Le même écran que le site ─────────────────────────────────────────────
 * Une seule note affichée — la dernière — et un menu pour remonter le temps.
 * Le site a pris cette forme pour une raison qui vaut ici aussi : une page
 * qui les empile répond très bien à « qu'est-ce qui a changé depuis deux
 * ans » et très mal à « qu'est-ce qui a changé », qui est la question que
 * presque tout le monde se pose en arrivant.
 *
 * Le contenu vient du même endroit (`/api/patch-notes`, voir
 * `server/patch_notes.rs`) et passe par le même composant Markdown que le
 * site et l'aperçu du back-office : une note relue ici doit être
 * reconnaissable, pas seulement ressemblante.
 */
export function PatchNotesPanel() {
  const t = useT()
  const [notes, setNotes] = useState<PatchNote[] | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)

  useEffect(() => {
    api.patchNotes.list().then(setNotes).catch(() => setNotes([]))
  }, [])

  if (notes === null) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <ButtonSpinner size={28} color="rgb(var(--accent-text))" trackColor="rgba(255,255,255,0.08)" />
      </div>
    )
  }

  if (notes.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 px-10 text-center">
        <p className="text-[13px] font-semibold">{t('patchNotes.emptyTitle')}</p>
        <p className="max-w-md text-[12px] leading-relaxed text-txt-secondary">{t('patchNotes.emptyText')}</p>
      </div>
    )
  }

  // Le serveur trie du plus récent au plus ancien : la première est donc
  // celle qu'on montre tant que personne n'a choisi.
  const note = notes.find((n) => n.id === chosen) ?? notes[0]
  const isLatest = note.id === notes[0].id

  return (
    /*
     * Colonne centrée, bornée à 48 rem — la même largeur que la page du site.
     *
     * Sans borne, la carte occupait toute la fenêtre alors que le texte, lui,
     * ne la remplissait pas : les notes sont écrites avec des retours à la
     * ligne (le rendu les conserve, comme Discord et comme le site), donc les
     * lignes s'arrêtent où l'auteur les a coupées. Résultat : une colonne de
     * texte collée à gauche dans un cadre trois fois plus large. Borner la
     * colonne fait coïncider les deux, et centrer évite de laisser tout le
     * vide du même côté.
     */
    <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5 max-sm:overscroll-contain max-sm:px-4 max-sm:pb-[max(24px,env(safe-area-inset-bottom))] max-sm:pt-3">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
        {/* Le menu n'apparaît qu'à partir de deux versions : seul, il ne
            proposerait que ce qui est déjà à l'écran. */}
        {notes.length > 1 && <VersionPicker notes={notes} current={note} onPick={setChosen} />}

        <article
          // Remontée par sa clé : passer d'une version à l'autre rejoue
          // l'apparition, sinon le changement peut passer inaperçu quand deux
          // notes se ressemblent.
          key={note.id}
          className="rounded-2xl border border-line bg-surface-1 p-5 max-sm:p-4"
        >
          <div className="flex flex-wrap items-center gap-2.5">
            <span className="rounded-full border border-accent/40 bg-accent/15 px-3 py-1 text-[12px] font-semibold text-accent-hover max-sm:text-[13px]">
              {note.version}
            </span>
            {isLatest && (
              <span className="rounded-full border border-line bg-surface-2 px-3 py-1 text-[12px] font-medium text-txt-secondary max-sm:text-[13px]">
                {t('patchNotes.latest')}
              </span>
            )}
            <span className="text-[12px] text-txt-muted max-sm:text-[13px] max-sm:text-white/55">{formatDate(note.published_at)}</span>
          </div>

          <h2 className="mt-3 text-[16px] font-bold leading-snug text-txt-primary max-sm:text-[20px]">{note.title}</h2>

          <div className="mt-2">
            <Markdown text={note.body} theme={LAUNCHER_THEME} />
          </div>
        </article>
      </div>
    </div>
  )
}

function formatDate(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' })
}

/**
 * Choix de la version à lire.
 *
 * Un menu écrit à la main plutôt qu'un `<select>` : celui du système ne se
 * met pas aux couleurs du launcher et n'affiche qu'une ligne de texte, alors
 * qu'on veut la version **et** sa date — deux repères pour retrouver la bonne
 * sans l'ouvrir. Même raisonnement que sur le site.
 */
function VersionPicker({
  notes,
  current,
  onPick,
}: {
  notes: PatchNote[]
  current: PatchNote
  onPick: (id: string) => void
}) {
  const t = useT()
  const mobile = useIsMobile()
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  // Un clic à côté et Échap referment : les deux sorties qu'on essaie devant
  // un menu ouvert. (Sur téléphone, la feuille gère elle-même sa fermeture.)
  useEffect(() => {
    if (!open || mobile) return
    const onDown = (e: MouseEvent) => {
      if (!boxRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open, mobile])

  // Téléphone : le bouton ouvre une feuille du bas avec toutes les versions,
  // comme les autres choix de l'appli, plutôt qu'un menu qui pend sous lui.
  if (mobile) {
    return (
      <>
        <button
          type="button"
          onClick={() => setOpen(true)}
          aria-haspopup="dialog"
          className="press flex min-h-[60px] w-full items-center justify-between gap-3 rounded-2xl border border-white/[0.08] bg-white/[0.04] px-4 text-left"
        >
          <span className="flex min-w-0 flex-col">
            <span className="text-[15px] font-semibold text-white">{current.version}</span>
            <span className="truncate text-[13px] text-white/55">{formatDate(current.published_at)}</span>
          </span>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-white/55">
            <path d="M6 9l6 6 6-6" />
          </svg>
        </button>
        <Sheet
          open={open}
          onClose={() => setOpen(false)}
          label={t('support.tab.patchNotes')}
          header={<SheetTitle>{t('support.tab.patchNotes')}</SheetTitle>}
          bodyClassName="flex flex-col gap-1 px-3 pb-3"
        >
          {notes.map((n, i) => {
            const active = n.id === current.id
            return (
              <button
                key={n.id}
                type="button"
                onClick={() => { onPick(n.id); setOpen(false) }}
                aria-pressed={active}
                className={`press flex min-h-[60px] w-full flex-col justify-center rounded-2xl px-3 py-2 text-left ${active ? 'bg-[rgb(var(--accent)/0.2)]' : 'active:bg-white/[0.06]'}`}
              >
                <span className="flex items-center gap-2">
                  <span className="text-[16px] font-semibold text-white">{n.version}</span>
                  {i === 0 && <span className="text-[12px] font-semibold uppercase tracking-wide text-accent-hover">{t('patchNotes.latestShort')}</span>}
                  <span className="ml-auto text-[13px] text-white/55">{formatDate(n.published_at)}</span>
                </span>
                <span className="mt-0.5 block truncate text-[13px] text-white/65">{n.title}</span>
              </button>
            )
          })}
        </Sheet>
      </>
    )
  }

  return (
    <div ref={boxRef} className="relative w-full self-start sm:w-80">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-haspopup="listbox"
        className="flex w-full items-center justify-between gap-3 rounded-xl border border-line bg-surface-1 px-4 py-2.5 text-left transition-colors hover:border-accent/50"
      >
        <span className="flex min-w-0 flex-col">
          <span className="text-[13px] font-semibold text-txt-primary">{current.version}</span>
          <span className="truncate text-[11.5px] text-txt-muted">{formatDate(current.published_at)}</span>
        </span>
        <svg
          viewBox="0 0 24 24"
          width={16}
          height={16}
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={`shrink-0 text-txt-secondary transition-transform ${open ? 'rotate-180' : ''}`}
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      <AnimatePresence>
        {open && (
          <motion.ul
            role="listbox"
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.15 }}
            // `z-20` : la carte de la note passe juste en dessous, et le menu
            // doit la recouvrir au lieu de disparaître derrière.
            className="absolute z-20 mt-2 max-h-80 w-full overflow-y-auto rounded-xl border border-line bg-bg-card p-1 shadow-xl shadow-black/50"
          >
            {notes.map((n, i) => {
              const active = n.id === current.id
              return (
                <li key={n.id}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    onClick={() => { onPick(n.id); setOpen(false) }}
                    className={`w-full rounded-lg px-3 py-2.5 text-left transition-colors ${
                      active ? 'bg-accent/15 text-txt-primary' : 'hover:bg-surface-2'
                    }`}
                  >
                    <span className="flex items-center gap-2">
                      <span className="text-[13px] font-semibold">{n.version}</span>
                      {i === 0 && (
                        <span className="text-[10.5px] font-semibold uppercase tracking-wide text-accent-hover">
                          {t('patchNotes.latestShort')}
                        </span>
                      )}
                      <span className="ml-auto text-[11.5px] text-txt-muted">{formatDate(n.published_at)}</span>
                    </span>
                    <span className="mt-0.5 block truncate text-[11.5px] text-txt-secondary">{n.title}</span>
                  </button>
                </li>
              )
            })}
          </motion.ul>
        )}
      </AnimatePresence>
    </div>
  )
}
