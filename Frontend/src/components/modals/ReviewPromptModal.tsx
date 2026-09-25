import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/**
 * Demande d'avis, posée après une partie.
 *
 * ── Deux marches, et c'est tout le sujet ──────────────────────────────────
 * La note d'abord : cinq étoiles, un geste, rien à écrire. Elle compte dans
 * la moyenne affichée sur le site mais n'y publie aucune carte — il n'y
 * aurait rien à montrer. Le texte vient après, pour qui veut l'écrire, et
 * c'est lui qui fait paraître l'avis publiquement.
 *
 * Demander les deux d'un coup, c'est demander une rédaction : la plupart des
 * gens ferment la fenêtre, et on perd même la note qu'ils auraient donnée en
 * une seconde.
 *
 * ── Pourquoi ça n'ouvre plus le navigateur ────────────────────────────────
 * Les avis étaient attachés à un compte Google, que le launcher ne connaît
 * pas : il envoyait donc sur la page du site. Depuis que la LauncherAPI
 * accepte les avis signés du compte YuyuFrame (`/v1/reviews`), tout se fait
 * ici. Reste la condition : être connecté à son compte.
 *
 * Le moment est choisi par la source (voir `App.tsx`) : après une partie
 * terminée normalement, jamais après un plantage. Demander « alors, content
 * ? » à quelqu'un dont le jeu vient de se fermer tout seul est le meilleur
 * moyen de récolter un avis sincère et mauvais.
 */
export function ReviewPromptModal({ sessions, onClose, counter }: QueuedModalProps & { sessions: number }) {
  const t = useT()
  const navigate = useNavigate()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const username = useStore((s) => s.yuyuUsername)

  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [writing, setWriting] = useState(false)
  const [sending, setSending] = useState(false)
  /** `true` une fois la note partie : la fenêtre change de discours plutôt
   *  que de se fermer, pour proposer d'écrire sans tout recommencer. */
  const [sent, setSent] = useState(false)

  // Un avis déjà déposé se modifie, il ne se double pas : on part de ce qui
  // existe côté serveur plutôt que d'un formulaire vide qui l'écraserait par
  // surprise.
  useEffect(() => {
    if (!signedIn) return
    let alive = true
    api.reviews
      .mine()
      .then((mine) => {
        if (!alive || !mine) return
        setRating(mine.rating)
        setComment(mine.comment)
        if (mine.comment) setWriting(true)
      })
      .catch(() => {})
    return () => { alive = false }
  }, [signedIn])

  async function send(withComment: boolean) {
    if (rating < 1 || sending) return
    setSending(true)
    try {
      await api.reviews.submit(rating, withComment ? comment.trim() : '')
      if (withComment) onClose()
      else setSent(true)
    } catch (e) {
      showError(e)
    } finally {
      setSending(false)
    }
  }

  // Pas connecté : rien d'autre à proposer que de l'être. Un avis anonyme
  // depuis un launcher installé n'importe où n'aurait aucune valeur, et la
  // page du site existe toujours pour qui préfère passer par Google.
  if (!signedIn) {
    return (
      <ModalShell title={t('review.title')} onClose={onClose} maxWidth="max-w-sm" counter={counter}>
        <div className="flex flex-col gap-4">
          <p className="text-[12px] leading-relaxed text-txt-secondary">{t('review.intro', { count: sessions })}</p>
          <p className="text-[11px] leading-relaxed text-txt-muted">{t('review.needAccount')}</p>
          <div className="flex items-center gap-2">
            <Button variant="primary" className="flex-1" onClick={() => { onClose(); navigate('/yuyu') }}>
              {t('review.signIn')}
            </Button>
            <Button variant="ghost" onClick={onClose}>{t('review.no')}</Button>
          </div>
        </div>
      </ModalShell>
    )
  }

  return (
    <ModalShell title={t('review.title')} onClose={onClose} maxWidth="max-w-sm" counter={counter}>
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-txt-secondary">
          {sent ? t('review.thanks') : t('review.intro', { count: sessions })}
        </p>

        <Stars value={rating} onChange={(v) => { setRating(v); setSent(false) }} />

        {/* Dit AVANT d'envoyer, pas après : c'est ce qui rend la note facile
            à donner — rien de ce qu'on met là n'apparaîtra publiquement. */}
        {!writing && (
          <p className="text-[10px] leading-relaxed text-txt-muted">{t('review.ratingOnly')}</p>
        )}

        {writing && (
          <div className="flex flex-col gap-1.5">
            <textarea
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={1000}
              rows={4}
              placeholder={t('review.placeholder')}
              className="w-full resize-none rounded-xl border border-line bg-surface-1 p-3 text-[12px] leading-relaxed text-txt-primary outline-none transition-colors focus:border-accent/50"
            />
            <p className="text-[10px] leading-relaxed text-txt-muted">
              {t('review.published', { name: username ?? '' })}
            </p>
          </div>
        )}

        <div className="flex items-center gap-2">
          {writing ? (
            <Button
              variant="primary"
              className="flex-1"
              disabled={rating < 1 || comment.trim().length === 0}
              loading={sending}
              onClick={() => send(true)}
            >
              {t('review.publish')}
            </Button>
          ) : sent ? (
            <Button variant="primary" className="flex-1" onClick={() => setWriting(true)}>
              {t('review.addText')}
            </Button>
          ) : (
            <>
              <Button variant="primary" className="flex-1" disabled={rating < 1} loading={sending} onClick={() => send(false)}>
                {t('review.sendRating')}
              </Button>
              <Button variant="secondary" disabled={rating < 1} onClick={() => setWriting(true)}>
                {t('review.addText')}
              </Button>
            </>
          )}
          {/* « Non merci » plutôt que « plus tard » : la demande n'est posée
              qu'une fois, autant que le bouton dise la vérité. */}
          {!sent && !writing && <Button variant="ghost" onClick={onClose}>{t('review.no')}</Button>}
          {sent && <Button variant="ghost" onClick={onClose}>{t('review.done')}</Button>}
        </div>
      </div>
    </ModalShell>
  )
}

/**
 * Les cinq étoiles.
 *
 * Écrites ici plutôt que prises ailleurs : c'est le seul endroit du launcher
 * qui en demande, et le composant tient en quinze lignes. Elles réagissent au
 * survol autant qu'au clic — sans quoi on ne sait pas où on en est avant
 * d'avoir choisi.
 */
function Stars({ value, onChange }: { value: number; onChange: (value: number) => void }) {
  const [hover, setHover] = useState(0)
  const shown = hover || value

  return (
    <div className="flex items-center justify-center gap-1.5" onMouseLeave={() => setHover(0)}>
      {[1, 2, 3, 4, 5].map((n) => (
        <motion.button
          key={n}
          type="button"
          aria-label={`${n}/5`}
          onClick={() => onChange(n)}
          onMouseEnter={() => setHover(n)}
          whileHover={{ scale: 1.12 }}
          whileTap={{ scale: 0.94 }}
          className={`transition-colors duration-150 ${n <= shown ? 'text-warning' : 'text-txt-muted'}`}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" width={26} height={26} aria-hidden="true">
            <path d="m12 2.6 2.9 5.9 6.5.95-4.7 4.6 1.1 6.45L12 17.45 6.2 20.5l1.1-6.45-4.7-4.6 6.5-.95L12 2.6Z" />
          </svg>
        </motion.button>
      ))}
    </div>
  )
}
