import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { api } from '@/api/client'
import type { AgentStatus } from '@/api/client'
import { useT } from '@/i18n'

/**
 * Le client intégré : ce que c'est, et jouer avec ou sans.
 *
 * Cette fenêtre a remplacé le raccourci vers la page des mods sur l'engrenage
 * de l'accueil. Le raccourci n'y manquait à personne — la page des mods est à
 * un clic dans la barre — alors que l'agent, lui, n'était expliqué nulle part :
 * il s'injectait au lancement et on ne découvrait son existence qu'en ouvrant
 * le menu en jeu, ou jamais. C'est donc ici qu'on en parle, et c'est ici qu'on
 * le refuse.
 *
 * Le choix est retenu par instance (voir `agentOptOut` dans le store) : il
 * suit la partie qu'on veut faire — un serveur qui interdit les clients
 * modifiés, une instance laissée strictement vanilla — pas l'utilisateur.
 */
export function AgentModal({
  mcVersion,
  loader,
  enabled,
  onChange,
  onClose,
}: {
  mcVersion: string
  loader: string
  enabled: boolean
  onChange: (enabled: boolean) => void
  onClose: () => void
}) {
  const t = useT()
  const [status, setStatus] = useState<AgentStatus | null>(null)

  useEffect(() => {
    let alive = true
    api.launch
      .agentStatus(mcVersion, loader)
      .then((s) => { if (alive) setStatus(s) })
      // Une erreur ici ne doit pas laisser la fenêtre vide : on retombe sur
      // « compatible », qui est le cas courant, et le Rust reste de toute
      // façon seul juge au lancement.
      .catch(() => { if (alive) setStatus({ available: true, block: null, min_java: 25 }) })
    return () => { alive = false }
  }, [mcVersion, loader])

  const blocked = status ? !status.available : false

  return (
    <ModalShell title={t('agent.title')} onClose={onClose} maxWidth="max-w-lg">
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-txt-secondary">{t('agent.what')}</p>

        <ul className="flex flex-col gap-1.5">
          {/* Les optimisations d'abord : c'est la seule ligne qu'un mod ne
              pourrait pas tenir, et donc ce qui justifie qu'on injecte quoi
              que ce soit. Le reste vient s'ajouter. */}
          {['perf', 'hud', 'zoom', 'packs', 'clean'].map((key) => (
            <li key={key} className="flex items-start gap-2 text-[11px] leading-relaxed text-txt-secondary">
              <span className="mt-[5px] h-1 w-1 flex-shrink-0 rounded-full bg-accent" />
              {t(`agent.feature.${key}`)}
            </li>
          ))}
        </ul>

        {/* L'avertissement passe avant les deux cartes : choisir « avec »
            alors que ce n'est pas possible ici doit être écarté avant d'être
            tenté, pas expliqué après coup. */}
        {blocked && status && (
          <div className="flex flex-col gap-1.5 rounded-xl border border-warning/30 bg-warning/10 p-3">
            <p className="flex items-center gap-2 text-[12px] font-semibold text-warning">
              <WarningIcon className="h-3.5 w-3.5 flex-shrink-0" />
              {t('agent.blocked.title', { version: mcVersion })}
            </p>
            <p className="text-[11px] leading-relaxed text-txt-secondary">
              {t(`agent.blocked.${status.block}`, { version: mcVersion, loader, java: status.min_java })}
            </p>
            {/* Le dire explicitement : « incompatible » se lit facilement
                comme « ça va planter », alors que c'est exactement le
                contraire — rien n'est injecté, le jeu part tel quel. */}
            <p className="text-[11px] leading-relaxed text-txt-muted">{t('agent.blocked.safe')}</p>
          </div>
        )}

        <div className="flex flex-col gap-2 sm:flex-row">
          <Choice
            label={t('agent.with')}
            hint={t('agent.withHint')}
            active={enabled && !blocked}
            disabled={blocked}
            onClick={() => onChange(true)}
          />
          <Choice
            label={t('agent.without')}
            hint={t('agent.withoutHint')}
            active={!enabled || blocked}
            disabled={blocked}
            onClick={() => onChange(false)}
          />
        </div>

        <p className="text-[10px] leading-relaxed text-txt-muted">
          {blocked ? t('agent.keptForLater') : t('agent.remembered')}
        </p>

        <div className="flex justify-end">
          <Button variant="primary" onClick={onClose}>
            {t('agent.done')}
          </Button>
        </div>
      </div>
    </ModalShell>
  )
}

/**
 * Une des deux réponses. Deux cartes plutôt qu'un interrupteur : un
 * interrupteur ne dit que ce qu'on active, jamais ce qu'on obtient en le
 * coupant — ici les deux réponses sont légitimes et méritent la même place.
 */
function Choice({
  label,
  hint,
  active,
  disabled,
  onClick,
}: {
  label: string
  hint: string
  active: boolean
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <motion.button
      type="button"
      onClick={disabled ? undefined : onClick}
      disabled={disabled}
      whileHover={disabled ? undefined : { y: -1 }}
      whileTap={disabled ? undefined : { scale: 0.99 }}
      aria-pressed={active}
      className={`flex flex-1 flex-col items-start gap-1 rounded-xl border p-3 text-left transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-50 ${
        active
          ? 'border-accent/50 bg-accent/15 text-txt-primary'
          : 'border-line bg-surface-1 text-txt-secondary hover:border-line-strong'
      }`}
    >
      <span className="text-[12px] font-semibold">{label}</span>
      <span className="text-[10px] leading-relaxed text-txt-muted">{hint}</span>
    </motion.button>
  )
}

/** Panneau d'attention — le même dessin que la pastille de l'engrenage. */
export function WarningIcon({ className = '' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className} aria-hidden="true">
      <path d="M12 2.5 1.5 21h21L12 2.5Zm0 5.2 6.9 12.1H5.1L12 7.7Zm-.95 3.6v4.3h1.9v-4.3h-1.9Zm0 5.4v1.9h1.9v-1.9h-1.9Z" />
    </svg>
  )
}
