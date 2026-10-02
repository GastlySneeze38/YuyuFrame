import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/**
 * « Le jeu a planté » — affichée à la fermeture anormale d'une partie.
 *
 * Le rapport est déjà écrit sur le disque quand cette modale s'ouvre : le
 * launcher a capturé la trace, les mods, la machine et la fin du journal
 * pendant la partie (voir `minecraft::crash`). Il n'y a donc rien à
 * construire ici, seulement une décision à demander — envoyer ou non.
 *
 * C'est le seul moment où la question se pose vraiment : la fenêtre de jeu
 * vient de disparaître, la personne regarde le launcher. Jusqu'ici elle ne
 * recevait qu'un message d'information, et le rapport restait sur le disque
 * sans que rien ne propose d'en faire quelque chose.
 *
 * L'envoi est délibéré et jamais automatique : un rapport contient le journal
 * de quelqu'un. L'écran Support en montre le contenu exact avant l'envoi —
 * d'où le renvoi vers lui pour qui veut lire avant de décider.
 */
export function CrashReportModal({
  reportId,
  instanceId,
  title,
  cause,
  onClose,
  counter,
}: QueuedModalProps & {
  reportId: string
  instanceId: string
  title: string
  cause: string
}) {
  const t = useT()
  const navigate = useNavigate()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const instances = useStore((s) => s.instances)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState<string | null>(null)

  const instanceName = instances.find((i) => i.id === instanceId)?.name ?? instanceId
  // Les genres connus ont un libellé. Pour un genre ajouté côté serveur sans
  // traduction, `t` rend la clé elle-même : on affiche alors le genre brut,
  // plutôt qu'un « crash.kind.machin » en pleine modale.
  const causeKey = `crash.kind.${cause}`
  const causeLabel = t(causeKey) === causeKey ? cause : t(causeKey)

  async function send() {
    setSending(true)
    try {
      const remote = await api.crashes.send(reportId)
      setSent(remote.public_id ?? null)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setSending(false)
    }
  }

  function openReport() {
    onClose()
    navigate('/support?tab=crashes')
  }

  /**
   * Une JVM qui n'a pas démarré se répare, et toujours au même endroit.
   *
   * Les autres genres de plantage n'ont pas de remède connu du launcher : il
   * n'y a rien à proposer, donc rien n'est proposé. Celui-ci est l'exception —
   * « could not find java.dll » ne dit rien à personne, alors que trois clics
   * dans les réglages Java de l'instance suffisent. L'écran y mène au lieu de
   * décrire le chemin.
   */
  const javaProblem = cause === 'java'
  function openJavaSettings() {
    onClose()
    navigate(`/instances?settings=${encodeURIComponent(instanceId)}&tab=java`)
  }

  return (
    <ModalShell title={t('crash.modal.title')} onClose={onClose} counter={counter}>
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">
          {t('crash.modal.intro', { instance: instanceName })}
        </p>

        {/* La cause du plantage est la seule ligne technique de la fenêtre :
            elle est dans un encadré, en police à chasse fixe, pour qu'on
            puisse la recopier sans la confondre avec la prose autour. */}
        <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 px-4 py-3.5">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-txt-muted">
            {causeLabel}
          </span>
          <span className="break-words font-mono text-[13px] font-semibold leading-relaxed text-txt-primary">{title}</span>
        </div>

        {javaProblem && (
          <div className="flex flex-col gap-2.5 rounded-xl border border-accent/30 bg-accent/10 p-3.5">
            <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('crash.java.hint')}</p>
            <Button variant="primary" onClick={openJavaSettings} fullWidth>
              {t('crash.java.action')}
            </Button>
          </div>
        )}

        {sent !== null ? (
          <p className="text-[13px] font-medium text-success">
            {sent ? t('crash.modal.sentWithRef', { ref: sent }) : t('crash.modal.sent')}
          </p>
        ) : (
          <p className="text-[12px] leading-relaxed text-txt-muted">
            {signedIn ? t('crash.modal.privacy') : t('crash.signInFirst')}
          </p>
        )}

        {/* Les trois boutons ne tiennent pas côte à côte sans se tasser dès
            que la langue s'allonge : ils s'enroulent plutôt que de rétrécir
            jusqu'à couper les mots. */}
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Envoyer reste l'action principale tant que rien n'est parti ;
              une fois le rapport envoyé, il n'y a plus qu'à aller lire la
              réponse, donc c'est ce bouton-là qui prend la place.

              Sauf pour un problème de Java : l'action principale est alors
              au-dessus, celle qui le répare. Deux boutons principaux dans la
              même fenêtre ne diraient plus lequel compte. */}
          {sent === null && signedIn && (
            <Button variant={javaProblem ? 'secondary' : 'primary'} onClick={send} loading={sending} className="flex-1">
              {sending ? t('crash.modal.sending') : t('crash.send')}
            </Button>
          )}
          <Button variant="secondary" onClick={openReport} className="flex-1">
            {sent === null ? t('crash.modal.review') : t('crash.modal.openSupport')}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            {t('crash.modal.later')}
          </Button>
        </div>
      </div>
    </ModalShell>
  )
}
