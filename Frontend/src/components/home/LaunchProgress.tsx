import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useT } from '@/i18n'

/**
 * Ce qui remplace le bouton Jouer pendant un lancement puis une partie.
 *
 * Avant, il n'affichait que « EN JEU… » et un fond qui se remplissait : le
 * message d'étape que le launcher envoie (« Téléchargement d'Horizon… »,
 * « Préparation… ») n'était jamais montré, on ne le trouvait que dans la
 * console. Une attente se supporte mieux quand on sait ce qui se passe et
 * depuis combien de temps.
 *
 * - L'étape en clair à gauche, qui change en glissant d'un cran (seulement
 *   quand l'étape change, pas à chaque pourcentage) ; le pourcentage à part,
 *   en chiffres à largeur fixe pour ne pas trembler ; le temps écoulé dès
 *   qu'il dépasse quelques secondes.
 * - Le remplissage avance par découpage (`clip-path`) et non par largeur :
 *   rien n'est remis en page. Un reflet le parcourt en boucle, en CSS, pour
 *   dire que ça travaille quand une étape longue ne fait plus bouger le chiffre.
 * - En partie : un point vert qui respire, « En jeu » et la durée.
 * - Annuler / Arrêter : une petite croix à droite. Le bloc garde la hauteur du
 *   bouton Jouer, qu'il remplace sans rien décaler.
 */

/** Le pourcentage que le Rust ajoute parfois à son libellé (« … — 42 % ») et
 *  les points de suspension : affichés à part, ou inutiles ici. */
function stepLabel(message: string): string {
  return message.replace(/\s*—\s*\d+\s*%\s*$/, '').replace(/(\.\.\.|…)\s*$/, '').trim()
}

function formatElapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(s / 60)
  if (m >= 60) return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

export function LaunchProgress({ progress, cancelling, onCancel, toneClass }: {
  progress: { current: number; total: number; message: string } | null
  cancelling: boolean
  onCancel: () => void
  /** Fond et ombre du bouton Jouer, pour garder sa couleur (thème, Halloween). */
  toneClass: string
}) {
  const t = useT()
  const playing = !progress
  const percent = progress && progress.total > 0 ? Math.min(100, Math.round((progress.current / progress.total) * 100)) : 0
  const label = progress ? stepLabel(progress.message) || t('home.preparingLaunch') : t('home.inGame')

  // Départ du lancement, puis départ de la partie : le temps affiché repart
  // de zéro quand le jeu est là.
  const launchStart = useRef(Date.now())
  const playStart = useRef<number | null>(null)
  if (playing && playStart.current === null) playStart.current = Date.now()
  if (!playing) playStart.current = null
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const elapsed = now - (playStart.current ?? launchStart.current)

  return (
    <div
      className={`relative flex h-[clamp(38px,6.6vh,52px)] flex-1 items-center gap-3 overflow-hidden rounded-xl pl-4 pr-1.5 text-white ${toneClass}`}
      role="status"
      aria-live="polite"
    >
      {/* Le remplissage, avec son reflet. Découpé, pas redimensionné. */}
      {!playing && (
        <span
          aria-hidden
          className="absolute inset-0 overflow-hidden bg-[rgba(255,255,255,0.2)] transition-[clip-path] duration-500 ease-[cubic-bezier(0.23,1,0.32,1)]"
          style={{ clipPath: `inset(0 ${100 - percent}% 0 0)` }}
        >
          <span className="launch-sheen absolute inset-y-0 left-0 w-1/3 bg-[linear-gradient(90deg,transparent,rgba(255,255,255,0.28),transparent)]" />
        </span>
      )}

      {/* Indicateur : anneau qui tourne pendant le lancement, point vert en partie. */}
      <span className="relative z-10 flex h-4 w-4 flex-shrink-0 items-center justify-center">
        {playing ? (
          <>
            <span className="absolute h-3 w-3 animate-ping rounded-full bg-success/50" />
            <span className="relative h-2 w-2 rounded-full bg-success" />
          </>
        ) : (
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-[rgba(255,255,255,0.25)] border-t-white" />
        )}
      </span>

      {/* L'étape : elle glisse d'un cran quand elle change, pas à chaque pourcentage. */}
      <span className="relative z-10 min-w-0 flex-1 overflow-hidden">
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.span
            key={label}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.18, ease: [0.23, 1, 0.32, 1] }}
            className="block truncate text-[clamp(11.5px,1.65vh,13.5px)] font-semibold"
          >
            {label}
          </motion.span>
        </AnimatePresence>
      </span>

      <span className="relative z-10 flex flex-shrink-0 items-baseline gap-2 tabular-nums">
        {!playing && <span className="text-[clamp(12px,1.7vh,14px)] font-bold">{percent} %</span>}
        {elapsed >= 5000 && (
          <span className="text-[11px] font-medium text-[rgba(255,255,255,0.6)]">{formatElapsed(elapsed)}</span>
        )}
      </span>

      <button
        type="button"
        onClick={onCancel}
        disabled={cancelling}
        title={cancelling ? t('home.cancelling') : playing ? t('home.stopGame') : t('home.cancel')}
        aria-label={cancelling ? t('home.cancelling') : playing ? t('home.stopGame') : t('home.cancel')}
        className="relative z-10 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg text-[rgba(255,255,255,0.7)] transition-[background-color,color,transform] duration-150 hover:bg-[rgba(0,0,0,0.25)] hover:text-white active:scale-[0.94] disabled:cursor-not-allowed disabled:opacity-40"
      >
        {cancelling ? (
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-[rgba(255,255,255,0.25)] border-t-white" />
        ) : (
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" width={14} height={14}>
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        )}
      </button>
    </div>
  )
}
