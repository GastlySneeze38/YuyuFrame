import { open as openExternal } from '@tauri-apps/plugin-shell'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/**
 * Demande d'avis, posée après une partie.
 *
 * ── Pourquoi elle ouvre le navigateur ─────────────────────────────────────
 * Les avis vivent sur le site et sont attachés à un compte Google (voir
 * `Server/Website/Backend/src/routes/reviews.rs`), là où le launcher ne
 * connaît qu'un compte YuyuFrame. Écrire l'avis ici demanderait une route
 * d'API et un deuxième chemin d'authentification pour un formulaire de trois
 * champs. On envoie donc sur la page existante, et on le dit avant de le
 * faire — un navigateur qui s'ouvre sans prévenir passe pour une fuite.
 *
 * Le moment est choisi par la source (voir `App.tsx`) : après une partie
 * terminée normalement, jamais après un plantage. Demander « alors, content
 * ? » à quelqu'un dont le jeu vient de se fermer tout seul est le meilleur
 * moyen de récolter un avis sincère et mauvais.
 */

/** La section avis du site. `/avis` redirige vers l'ancre de la page d'accueil. */
const REVIEW_URL = 'https://yuyuframe.eu/avis'

export function ReviewPromptModal({ sessions, onClose, counter }: QueuedModalProps & { sessions: number }) {
  const t = useT()

  function go() {
    openExternal(REVIEW_URL).catch(() => {})
    onClose()
  }

  return (
    <ModalShell title={t('review.title')} onClose={onClose} maxWidth="max-w-sm" counter={counter}>
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-txt-secondary">
          {t('review.intro', { count: sessions })}
        </p>

        <div className="flex items-center gap-2">
          <Button variant="primary" onClick={go} className="flex-1">
            {t('review.open')}
          </Button>
          {/* « Non merci » plutôt que « plus tard » : la demande n'est posée
              qu'une fois, autant que le bouton dise la vérité. */}
          <Button variant="ghost" onClick={onClose}>
            {t('review.no')}
          </Button>
        </div>

        <p className="text-[10px] text-txt-muted">{t('review.note')}</p>
      </div>
    </ModalShell>
  )
}
