import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { CompatFix, CompatProblem, CompatResult, Instance } from '@/types'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { showError } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/**
 * Onglet « Compatibilité » : la seule question à laquelle les autres écrans ne
 * savent pas répondre.
 *
 * « Réparer » dit si les fichiers sont intacts, « Installation » dit si les
 * versions se tiennent — aucun des deux ne dit si **ça démarre**. La
 * résolution des dépendances d'un loader et l'application des Mixins ne se
 * jouent que dans la JVM, avec les mods réellement présents : on lance donc
 * vraiment le jeu, et on le coupe dès qu'il est debout.
 *
 * Trois choses que l'écran doit dire avant de commencer, parce qu'elles
 * surprendraient sinon : c'est long (il faut atteindre le menu principal), la
 * fenêtre du jeu apparaîtra brièvement (Minecraft n'a pas de mode sans
 * affichage), et un échec ne sera pas rangé dans les rapports de plantage —
 * il est attendu ici.
 *
 * ── Les gestes proposés ───────────────────────────────────────────────────
 * Ils viennent du Rust, qui ne décide que de ce qui est *possible* : la
 * version visée existe, le mod à désactiver a été retrouvé dans le dossier.
 * L'interface les rédige et les exécute. Un seul agit sur-le-champ
 * (désactiver un mod, qui se défait d'un clic dans l'onglet Mods) ; les
 * autres **mènent** à l'écran concerné au lieu de décider à la place de la
 * personne — réinstaller une version du jeu ne se fait pas depuis une liste
 * de diagnostics.
 */
export function CompatTab({
  instance,
  onGoTo,
  onClose,
}: {
  instance: Instance
  /** Change d'onglet, en portant éventuellement la version suggérée. */
  onGoTo: (tab: string, suggestedVersion?: string) => void
  onClose: () => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<CompatResult | null>(null)
  const [tail, setTail] = useState<string[]>([])
  const [showLog, setShowLog] = useState(false)
  const [disabled, setDisabled] = useState<string[]>([])
  const [stopping, setStopping] = useState(false)

  // Les lignes arrivent par milliers, et par rafales : le chargement des mods
  // en crache plusieurs centaines par seconde. Elles s'accumulent donc dans
  // une ref et ne sont affichées que cinq fois par seconde — repeindre à
  // chaque ligne ferait ramer l'interface pendant précisément le moment où on
  // la regarde. Seules les dernières comptent : le journal entier reste côté
  // Rust jusqu'au verdict.
  const pending = useRef<string[]>([])
  useTauriEvent<{ instance_id: string; line: string }>(
    'compat_log',
    ({ instance_id, line }) => {
      if (instance_id !== instance.id) return
      pending.current = [...pending.current.slice(-11), line]
    },
    [instance.id],
  )
  useEffect(() => {
    if (!running) return
    const id = window.setInterval(() => {
      if (pending.current.length > 0) setTail(pending.current)
    }, 200)
    return () => window.clearInterval(id)
  }, [running])

  // Un essai en cours ne doit pas survivre à la fermeture de la modale sans
  // que personne ne puisse plus l'arrêter : la JVM resterait seule avec la
  // réservation posée sur l'instance.
  const runningRef = useRef(false)
  runningRef.current = running
  useEffect(
    () => () => {
      if (runningRef.current) api.instances.compatCancel(instance.id).catch(() => {})
    },
    [instance.id],
  )

  const run = async () => {
    setRunning(true)
    setResult(null)
    setTail([])
    pending.current = []
    setDisabled([])
    setStopping(false)
    try {
      setResult(await api.instances.compatTest(instance.id))
    } catch (e) {
      showError(e)
    } finally {
      setRunning(false)
    }
  }

  const stop = async () => {
    setStopping(true)
    try {
      await api.instances.compatCancel(instance.id)
    } catch {
      // L'essai vient peut-être de se terminer de lui-même : rien à dire.
    }
  }

  const applyFix = async (fix: CompatFix) => {
    switch (fix.action) {
      case 'mc_version':
        onGoTo('installation', fix.value || undefined)
        break
      case 'loader_version':
        onGoTo('installation')
        break
      case 'ram':
      case 'java':
        onGoTo('java')
        break
      case 'install_mod':
        useStore.getState().setSelectedInstanceId(instance.id)
        onClose()
        navigate('/mods')
        break
      case 'disable_mod':
        try {
          await api.mods.toggle(instance.id, fix.value)
          setDisabled((prev) => [...prev, fix.value])
        } catch (e) {
          showError(e)
        }
        break
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5">
        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">
          {t('instancesPage.compatTitle')}
        </p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('instancesPage.compatDesc')}</p>
        <p className="mt-1 text-[11.5px] text-txt-muted">{t('instancesPage.compatWindowNotice')}</p>
      </div>

      {running ? (
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-3">
            <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-accent border-t-transparent" />
            <p className="text-[12.5px] font-semibold text-txt-primary">{t('instancesPage.compatRunning')}</p>
            <button
              onClick={stop}
              disabled={stopping}
              className="ml-auto rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:opacity-50"
            >
              {t('instancesPage.compatStop')}
            </button>
          </div>
          <div className="h-[168px] overflow-hidden rounded-xl border border-line bg-black/40 p-3">
            {tail.length === 0 ? (
              <p className="text-[11.5px] text-txt-muted">{t('instancesPage.compatPreparing')}</p>
            ) : (
              tail.map((line, i) => (
                <p key={i} className="truncate font-mono text-[10.5px] leading-[14px] text-txt-muted">
                  {line}
                </p>
              ))
            )}
          </div>
        </div>
      ) : (
        <button
          onClick={run}
          className="w-fit rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover"
        >
          {result ? t('instancesPage.compatRunAgain') : t('instancesPage.compatRun')}
        </button>
      )}

      {result && !running && (
        <div className="flex flex-col gap-3">
          <Verdict result={result} />

          {result.problems.map((problem, i) => (
            <ProblemCard
              key={i}
              problem={problem}
              disabled={disabled}
              onFix={applyFix}
            />
          ))}

          {result.suggestions.length > 0 && (
            <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5">
              <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">
                {t('instancesPage.compatSuggestions')}
              </p>
              {result.suggestions.map((s, i) => (
                <p key={i} className="font-mono text-[11px] leading-relaxed text-txt-secondary">
                  {s}
                </p>
              ))}
            </div>
          )}

          {result.log.length > 0 && (
            <div className="flex flex-col gap-2">
              <button
                onClick={() => setShowLog((v) => !v)}
                className="w-fit text-[11.5px] font-semibold text-txt-muted underline-offset-2 transition-colors hover:text-txt-secondary hover:underline"
              >
                {showLog ? t('instancesPage.compatHideLog') : t('instancesPage.compatShowLog')}
              </button>
              {showLog && (
                <>
                  <div className="max-h-[220px] overflow-auto rounded-xl border border-line bg-black/40 p-3">
                    {result.log.split('\n').slice(-200).map((line, i) => (
                      <p key={i} className="whitespace-pre-wrap break-all font-mono text-[10.5px] leading-[14px] text-txt-muted">
                        {line}
                      </p>
                    ))}
                  </div>
                  <button
                    onClick={() => navigator.clipboard.writeText(result.log).catch(() => {})}
                    className="w-fit rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary"
                  >
                    {t('instancesPage.compatCopyLog')}
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** Le verdict, et seulement lui. Les quatre issues ne disent pas la même
 *  chose : « ça ne démarre pas » est un constat, « on n'a pas attendu assez
 *  longtemps » n'en est pas un. */
function Verdict({ result }: { result: CompatResult }) {
  const t = useT()
  const minutes = Math.max(1, Math.round(result.duration_ms / 60000))

  if (result.status === 'ok') {
    return (
      <Box tone="ok" title={t('instancesPage.compatOk')}>
        {t('instancesPage.compatOkHint')}
      </Box>
    )
  }
  if (result.status === 'timeout') {
    return (
      <Box tone="warn" title={t('instancesPage.compatTimeout')}>
        {t('instancesPage.compatTimeoutHint', { minutes })}
      </Box>
    )
  }
  if (result.status === 'cancelled') {
    return <Box tone="neutral" title={t('instancesPage.compatCancelled')}>{t('instancesPage.compatCancelledHint')}</Box>
  }
  if (result.status === 'error') {
    return (
      <Box tone="bad" title={t('instancesPage.compatError')}>
        {result.message ?? ''}
      </Box>
    )
  }
  return (
    <Box tone="bad" title={t('instancesPage.compatFailed')}>
      {result.problems.length === 0
        ? t('instancesPage.compatFailedBlind')
        : t('instancesPage.compatFailedHint', { count: result.problems.length })}
    </Box>
  )
}

const TONES: Record<string, string> = {
  ok: 'border-[rgba(134,239,172,0.35)] bg-[rgba(134,239,172,0.08)] text-[rgba(134,239,172,0.9)]',
  warn: 'border-[rgba(240,180,90,0.35)] bg-[rgba(240,180,90,0.08)] text-[rgba(240,180,90,0.9)]',
  bad: 'border-[rgba(248,113,113,0.35)] bg-[rgba(248,113,113,0.08)] text-[rgba(248,113,113,0.9)]',
  neutral: 'border-line bg-surface-2 text-txt-secondary',
}

function Box({ tone, title, children }: { tone: string; title: string; children: React.ReactNode }) {
  return (
    <div className={`flex flex-col gap-1 rounded-xl border p-3.5 ${TONES[tone]}`}>
      <p className="text-[12.5px] font-bold">{title}</p>
      <p className="text-[12px] leading-relaxed text-txt-secondary">{children}</p>
    </div>
  )
}

/** Un problème : ce que le loader a refusé, sa ligne exacte, et ce qu'on peut
 *  y faire. La ligne du loader est gardée telle quelle — c'est elle qu'on
 *  colle dans une recherche quand le launcher ne sait pas aider. */
function ProblemCard({
  problem,
  disabled,
  onFix,
}: {
  problem: CompatProblem
  disabled: string[]
  onFix: (fix: CompatFix) => void
}) {
  const t = useT()
  const subject = problem.subject || t('instancesPage.compatUnknownMod')

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-line bg-surface-2 p-3.5">
      <p className="text-[12.5px] font-bold text-txt-primary">
        {t(`instancesPage.compatKind_${problem.kind}`, {
          mod: subject,
          target: problem.target,
          version: problem.expected,
        })}
      </p>
      {problem.detail && (
        <p className="whitespace-pre-wrap break-all font-mono text-[10.5px] leading-relaxed text-txt-muted">
          {problem.detail}
        </p>
      )}
      {problem.fixes.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {problem.fixes.map((fix, i) => {
            const done = fix.action === 'disable_mod' && disabled.includes(fix.value)
            // Le bouton n'annonce la version que lorsqu'on la connaît : « 1.20.x »
            // ou « 0.16 ou plus » ne sont pas des entrées du menu déroulant,
            // et un bouton qui les nommerait promettrait un choix inexistant.
            const key = fix.action === 'mc_version' && fix.value ? 'compatFix_mc_version_to' : `compatFix_${fix.action}`
            return (
              <button
                key={i}
                onClick={() => onFix(fix)}
                disabled={done}
                className="rounded-xl border border-line bg-black/30 px-3 py-1.5 text-[11.5px] font-semibold text-txt-secondary transition-colors hover:border-accent/40 hover:text-txt-primary disabled:cursor-default disabled:opacity-50"
              >
                {done
                  ? t('instancesPage.compatFixDisabled', { mod: fix.value })
                  : t(`instancesPage.${key}`, { value: fix.value })}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
