import { ModalShell } from '@/components/ui/ModalShell'
import type { QueuedModalProps } from '@/stores/useModalQueue'

/**
 * Notes de version, publiées depuis le back-office.
 *
 * ── D'où vient le texte ───────────────────────────────────────────────────
 * De la table `patch_notes` du site (back-office → Contenu → Patch notes),
 * lue par `api.patchNotes.latest()`. C'est exactement la note affichée sur
 * yuyuframe.eu et annoncée sur Discord : une seule écriture, trois surfaces.
 *
 * Avant, c'était une annonce de flotte à l'emplacement `modal`, créée dans un
 * écran différent — d'où deux « notes de version » dans le back-office, dont
 * une seule atteignait le launcher. Et avant encore, le manifeste de mise à
 * jour, qui figeait le texte au moment de la publication.
 *
 * ── Le rendu ──────────────────────────────────────────────────────────────
 * Le corps est du Markdown, puisqu'il est écrit pour le site. On n'embarque
 * pas de bibliothèque pour autant : une note de version n'utilise que des
 * titres, des puces et des paragraphes, et `**gras**` de temps en temps. Tout
 * ce qui n'est pas reconnu s'affiche tel quel plutôt que de disparaître —
 * c'est le bon compromis pour un texte qu'on relit avant de publier.
 */

type Block =
  | { kind: 'heading'; text: string }
  | { kind: 'bullet'; text: string }
  | { kind: 'para'; text: string }

/** Découpe le Markdown en blocs. Les lignes vides séparent, elles ne
 *  s'affichent pas : l'espacement vient de la mise en page. */
function parse(body: string): Block[] {
  return body
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map<Block>((line) => {
      const heading = line.match(/^#{1,6}\s+(.*)$/)
      if (heading) return { kind: 'heading', text: heading[1] }
      const bullet = line.match(/^[-*•]\s+(.*)$/)
      if (bullet) return { kind: 'bullet', text: bullet[1] }
      return { kind: 'para', text: line }
    })
}

/** `**gras**` — le seul style en ligne qu'on rende. Le reste passe tel quel. */
function inline(text: string) {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') && part.endsWith('**') && part.length > 4
      ? <strong key={i} className="font-semibold text-[rgba(255,255,255,0.85)]">{part.slice(2, -2)}</strong>
      : <span key={i}>{part}</span>,
  )
}

export function PatchNotesModal({
  title,
  kicker,
  body,
  onClose,
  counter,
}: QueuedModalProps & { title: string; kicker: string | null; body: string }) {
  const blocks = parse(body)

  return (
    <ModalShell title={title} onClose={onClose} maxWidth="max-w-md" counter={counter}>
      <div className="flex flex-col gap-4">
        {kicker && (
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-accent-hover">
            {kicker}
          </span>
        )}

        {blocks.length > 0 ? (
          <div className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto pr-1 text-[12px] leading-relaxed text-[rgba(255,255,255,0.6)]">
            {blocks.map((block, i) => {
              if (block.kind === 'heading') {
                return (
                  <h3
                    key={i}
                    // Marge en haut sauf pour le premier : deux titres qui se
                    // suivent ne doivent pas se coller, mais un titre en tête
                    // de note n'a rien à repousser.
                    className={`text-[11px] font-bold uppercase tracking-[0.08em] text-[rgba(255,255,255,0.45)] ${i > 0 ? 'mt-2' : ''}`}
                  >
                    {inline(block.text)}
                  </h3>
                )
              }
              if (block.kind === 'bullet') {
                return (
                  <div key={i} className="flex gap-2">
                    <span className="text-[rgba(75,63,207,0.8)]">•</span>
                    <span>{inline(block.text)}</span>
                  </div>
                )
              }
              return <p key={i}>{inline(block.text)}</p>
            })}
          </div>
        ) : (
          <p className="text-[12px] text-[rgba(255,255,255,0.4)]">
            Aucune note de version fournie.
          </p>
        )}

        <button
          onClick={onClose}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8]"
        >
          Compris
        </button>
      </div>
    </ModalShell>
  )
}
