import { ModalShell } from '@/components/ui/ModalShell'
import type { QueuedModalProps } from '@/stores/useModalQueue'

/**
 * Notes de version, publiées depuis le back-office.
 *
 * ── D'où vient le texte ───────────────────────────────────────────────────
 * D'une annonce de flotte à l'emplacement `modal` (`GET /v1/config`), pas du
 * manifeste de mise à jour comme avant. Le manifeste figeait le texte au
 * moment de la release : impossible de corriger une note après coup,
 * impossible d'en publier une sans sortir une version, et le contenu écrit
 * dans le back-office n'atteignait jamais le launcher.
 *
 * Le ciblage — OS, plan, version, fenêtre de validité — est déjà fait côté
 * serveur. Ici on ne fait qu'afficher.
 *
 * Le corps est une ligne par point. Les puces éventuellement tapées par
 * l'auteur sont retirées : la liste en pose déjà une, et personne ne veut
 * relire une note pour enlever des tirets en double.
 */
export function PatchNotesModal({
  title,
  kicker,
  body,
  onClose,
  counter,
}: QueuedModalProps & { title: string; kicker: string | null; body: string }) {
  const lines = body.split('\n').map((l) => l.trim()).filter(Boolean)

  return (
    <ModalShell title={title} onClose={onClose} maxWidth="max-w-md" counter={counter}>
      <div className="flex flex-col gap-4">
        {kicker && (
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-accent-hover">
            {kicker}
          </span>
        )}

        {lines.length > 0 ? (
          <ul className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto pr-1 text-[12px] leading-relaxed text-[rgba(255,255,255,0.6)]">
            {lines.map((line, i) => (
              <li key={i} className="flex gap-2">
                <span className="text-[rgba(75,63,207,0.8)]">•</span>
                <span>{line.replace(/^[-•*]\s*/, '')}</span>
              </li>
            ))}
          </ul>
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
