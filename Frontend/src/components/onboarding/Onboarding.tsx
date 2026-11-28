import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { useIsMobile } from '@/mobile/useIsMobile'
import { useT } from '@/i18n'
import { Backdrop, preloadScenes, type Scene } from './Backdrop'
import { EASE } from './ui'
import { AccountStep } from './steps/AccountStep'
import { MicrosoftStep } from './steps/MicrosoftStep'
import { InstanceStep } from './steps/InstanceStep'
import { FriendsStep } from './steps/FriendsStep'
import { ProfileStep } from './steps/ProfileStep'
import { DoneStep } from './steps/DoneStep'

type StepId = 'account' | 'microsoft' | 'instance' | 'friends' | 'profile' | 'done'

/** La capture derrière chaque étape. */
const SCENES: Record<StepId, Scene> = {
  account: 'overworld',
  microsoft: 'nether',
  instance: 'redstone',
  friends: 'overworld',
  profile: 'nether',
  done: 'overworld',
}

const OPTIONAL: StepId[] = ['friends', 'profile']

/**
 * L'écran d'accueil du premier démarrage : la première chose qu'on voit de
 * YuyuFrame. Montré par App.tsx quand ce PC n'a ni compte YuyuFrame ni compte
 * Minecraft, à la place des pages.
 *
 * Par étapes : le compte YuyuFrame (et son e-mail), le compte Minecraft, la
 * première instance s'il n'y en a aucune, puis — facultatives, et seulement
 * avec un compte YuyuFrame — les amis et le profil. Chaque étape a sa
 * capture du jeu en fond.
 *
 * Chorégraphie. À l'ouverture : la scène arrive de près (1,8 s), puis le
 * mot-symbole lettre à lettre, le texte, la carte, et le rail des étapes en
 * dernier — un seul mouvement qui se lit de gauche à droite. D'une étape à
 * l'autre : le titre sort par le haut mot à mot pendant que la carte glisse
 * vers la gauche, et la nouvelle capture se pose par-dessus l'ancienne. Tout
 * en `transform`/`opacity` ; ce qui tourne en continu est en CSS.
 */
export default function Onboarding({ ready, preview = false, onFinished }: {
  ready: boolean
  /** Ouvert à la main (Ctrl+Maj+O, en développement) : toutes les étapes,
   *  même celles qui sont déjà faites sur ce PC. */
  preview?: boolean
  onFinished: () => void
}) {
  const t = useT()
  const mobile = useIsMobile()
  const signedIn = useStore((s) => s.yuyuSignedIn && !s.yuyuEmailVerificationRequired)
  // Décidé à l'ouverture : ce qu'il faut demander. Une étape déjà faite
  // (compte présent) ne se montre pas.
  const initial = useRef({
    account: preview || !useStore.getState().yuyuSignedIn || useStore.getState().yuyuEmailVerificationRequired,
    microsoft: preview || !useStore.getState().uuid,
  })
  const [needsInstance, setNeedsInstance] = useState<boolean | null>(null)
  const [skippedAccount, setSkippedAccount] = useState(false)
  const [step, setStep] = useState<StepId | null>(null)
  const [leaving, setLeaving] = useState(false)
  // Les étapes faites pour de bon (pas passées) : coche au rail, récapitulatif à la fin.
  const [completed, setCompleted] = useState<StepId[]>([])

  useEffect(() => {
    preloadScenes()
    if (preview) {
      setNeedsInstance(true)
      return
    }
    api.instances.list()
      .then((list) => setNeedsInstance(list.length === 0))
      .catch(() => setNeedsInstance(false))
  }, [])

  // Les étapes, dans l'ordre. Amis et profil n'existent qu'avec un compte :
  // passer le compte les retire.
  const buildSteps = (skipped: boolean): StepId[] => {
    const list: StepId[] = []
    if (initial.current.account) list.push('account')
    if (initial.current.microsoft) list.push('microsoft')
    if (needsInstance) list.push('instance')
    // En aperçu, elles restent quoi qu'il arrive : c'est pour les voir.
    if (preview || (!skipped && (signedIn || initial.current.account))) list.push('friends', 'profile')
    list.push('done')
    return list
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const steps = useMemo(() => buildSteps(skippedAccount), [needsInstance, skippedAccount, signedIn])

  // Premier écran une fois qu'on sait s'il faut une instance.
  useEffect(() => {
    if (step === null && needsInstance !== null) setStep(steps[0])
  }, [step, needsInstance, steps])

  /** L'étape d'après, prise dans la liste telle qu'elle sera APRÈS ce geste :
   *  « Continuer sans compte » retire amis et profil, et la suite ne doit
   *  pas tomber sur l'une d'elles. */
  const next = (done?: StepId, skipAccount = false) => {
    if (done) setCompleted((c) => (c.includes(done) ? c : [...c, done]))
    if (skipAccount) setSkippedAccount(true)
    const list = buildSteps(skipAccount || skippedAccount)
    setStep((cur) => {
      const i = cur ? list.indexOf(cur) : -1
      return list[Math.min(i + 1, list.length - 1)]
    })
  }

  const finish = () => {
    setLeaving(true)
    window.setTimeout(onFinished, 760)
  }

  const current = step ?? 'account'
  const index = steps.indexOf(current)
  const visible = ready && step !== null

  return (
    <div className="relative h-full w-full overflow-hidden bg-black text-white">
      {ready && <Backdrop scene={SCENES[current]} mobile={mobile} leaving={leaving} />}

      {visible && (
        <motion.div
          className={
            mobile
              ? 'relative z-10 flex h-full flex-col overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden px-4 pb-[max(20px,env(safe-area-inset-bottom))] pt-[max(16px,env(safe-area-inset-top))]'
              : 'relative z-10 mx-auto flex h-full w-full max-w-[1240px] items-center gap-12 px-14 py-10'
          }
          animate={leaving ? { opacity: 0, y: mobile ? 24 : 0, scale: 0.97 } : { opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 0.45, ease: [0.7, 0, 0.84, 0] }}
        >
          {mobile && <ProgressBar steps={steps} index={index} />}

          {/* Le texte de l'étape. */}
          <div className={mobile ? 'mt-6 mb-5' : 'flex min-w-0 flex-1 flex-col justify-between self-stretch py-6'}>
            <div className={mobile ? '' : 'my-auto'}>
              <Headline step={current} index={index} total={steps.length} mobile={mobile} />
            </div>
            {!mobile && <StepRail steps={steps} index={index} completed={completed} />}
          </div>

          {/* La carte de l'étape. */}
          <motion.div
            className={mobile ? 'mt-auto' : 'flex max-h-full w-[420px] shrink-0 flex-col'}
            initial={{ opacity: 0, y: 28, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.8, delay: 0.85, ease: EASE }}
          >
            <motion.div
              layout
              transition={{ layout: { duration: 0.5, ease: EASE } }}
              style={{ borderRadius: 24 }}
              className="relative min-h-0 overflow-y-auto overflow-x-hidden overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden border border-white/[0.12] bg-[rgb(12_12_20/0.62)] p-6 shadow-[0_30px_80px_rgb(0_0_0/0.55)] backdrop-blur-2xl max-sm:p-5"
            >
              {/* Un liseré de lumière en haut de la carte : le verre. */}
              <span className="pointer-events-none absolute inset-x-6 top-0 h-px bg-gradient-to-r from-transparent via-white/40 to-transparent" />
              <AnimatePresence mode="popLayout" initial={false}>
                <motion.div
                  key={current}
                  layout="position"
                  initial={{ opacity: 0, x: 48, filter: 'blur(6px)' }}
                  animate={{ opacity: 1, x: 0, filter: 'blur(0px)' }}
                  exit={{ opacity: 0, x: -48, filter: 'blur(6px)', transition: { duration: 0.28, ease: [0.7, 0, 0.84, 0] } }}
                  transition={{ duration: 0.55, ease: EASE }}
                >
                  {current === 'account' && (
                    <AccountStep
                      onDone={() => next('account')}
                      onSkip={() => next(undefined, true)}
                    />
                  )}
                  {current === 'microsoft' && <MicrosoftStep onDone={() => next('microsoft')} onSkip={() => next()} />}
                  {current === 'instance' && <InstanceStep onDone={() => next('instance')} onSkip={() => next()} />}
                  {current === 'friends' && <FriendsStep onNext={() => next()} />}
                  {current === 'profile' && <ProfileStep onNext={() => next()} />}
                  {current === 'done' && <DoneStep onEnter={finish} summary={completed.map((c) => t(`onboarding.steps.${c}`))} />}
                </motion.div>
              </AnimatePresence>
            </motion.div>
          </motion.div>
        </motion.div>
      )}
    </div>
  )
}

/**
 * Le titre de l'étape. Sur la première, le nom de YuyuFrame lettre à lettre ;
 * ensuite, une phrase qui entre mot à mot par le bas, chaque mot derrière son
 * cache (rien ne déborde, on voit le mot sortir de la ligne).
 */
function Headline({ step, index, total, mobile }: { step: StepId; index: number; total: number; mobile: boolean }) {
  const t = useT()
  const first = useRef(true)
  // Le tout premier titre attend que la scène soit posée ; les suivants non.
  const delay = first.current ? 0.35 : 0.12
  useEffect(() => {
    first.current = false
  }, [])
  const optional = OPTIONAL.includes(step)

  return (
    <AnimatePresence mode="wait" initial={true}>
      <motion.div key={step} exit={{ opacity: 0, y: -14, filter: 'blur(6px)', transition: { duration: 0.22, ease: [0.7, 0, 0.84, 0] } }}>
        <motion.p
          className="flex items-center gap-2 text-[11.5px] font-bold uppercase tracking-[0.18em] text-white/55 max-sm:text-[11px]"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay, ease: EASE }}
        >
          {step === 'account' ? t('onboarding.welcome') : t('onboarding.stepOf', { n: String(index + 1), total: String(total) })}
          {optional && (
            <span className="rounded-full border border-white/15 px-2 py-0.5 text-[9.5px] tracking-[0.12em] text-white/50">{t('onboarding.optional')}</span>
          )}
        </motion.p>

        {step === 'account' ? (
          <Wordmark delay={delay + 0.1} mobile={mobile} />
        ) : (
          <Words text={t(`onboarding.headline.${step}`)} delay={delay + 0.05} mobile={mobile} />
        )}

        <motion.p
          className={`mt-4 max-w-[440px] leading-relaxed text-white/70 ${mobile ? 'text-[14.5px]' : 'text-[15.5px]'}`}
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: delay + 0.4, ease: EASE }}
        >
          {t(`onboarding.lead.${step}`)}
        </motion.p>
      </motion.div>
    </AnimatePresence>
  )
}

/** « YuyuFrame », chaque lettre qui monte de sous la ligne et se dé-floute. */
function Wordmark({ delay, mobile }: { delay: number; mobile: boolean }) {
  const letters = 'YuyuFrame'.split('')
  return (
    <h1
      aria-label="YuyuFrame"
      className={`mt-2 flex font-black leading-[0.95] tracking-[-0.04em] ${mobile ? 'text-[48px]' : 'text-[clamp(52px,6.4vw,96px)]'}`}
    >
      {letters.map((l, i) => (
        <span key={i} className="inline-block pb-[0.08em] [overflow-y:clip]">
          <motion.span
            aria-hidden
            className={`inline-block ${i >= 4 ? 'bg-gradient-to-br from-white to-[rgb(var(--accent-text))] bg-clip-text text-transparent' : ''}`}
            initial={{ y: '105%', opacity: 0, filter: 'blur(10px)' }}
            animate={{ y: '0%', opacity: 1, filter: 'blur(0px)' }}
            transition={{ duration: 0.9, delay: delay + i * 0.04, ease: EASE }}
          >
            {l}
          </motion.span>
        </span>
      ))}
    </h1>
  )
}

/** Une phrase mot à mot, chaque mot sortant de derrière la ligne. */
function Words({ text, delay, mobile }: { text: string; delay: number; mobile: boolean }) {
  const words = text.split(' ')
  return (
    <h1 className={`mt-3 font-black leading-[1.02] tracking-[-0.03em] ${mobile ? 'text-[34px]' : 'text-[clamp(38px,4.6vw,66px)]'}`}>
      {words.map((w, i) => (
        <span key={i} className="mr-[0.24em] inline-block pb-[0.1em] align-bottom [overflow-y:clip]">
          <motion.span
            className="inline-block"
            initial={{ y: '110%', rotate: 4 }}
            animate={{ y: '0%', rotate: 0 }}
            transition={{ duration: 0.75, delay: delay + i * 0.055, ease: EASE }}
          >
            {w}
          </motion.span>
        </span>
      ))}
    </h1>
  )
}

/** PC : la liste des étapes en bas à gauche, l'avancée qui descend le long. */
function StepRail({ steps, index, completed }: { steps: StepId[]; index: number; completed: StepId[] }) {
  const t = useT()
  const shown = steps.filter((s) => s !== 'done')
  return (
    <div className="relative flex flex-col gap-3">
      {shown.map((s, i) => {
        // Derrière nous : cochée si faite, un tiret si passée.
        const state = i === index ? 'active' : i < index ? (completed.includes(s) ? 'done' : 'skipped') : 'todo'
        return (
          <motion.div
            key={s}
            className="flex items-center gap-3"
            initial={{ opacity: 0, x: -12 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.6, delay: 1.05 + i * 0.06, ease: EASE }}
          >
            <span
              className={`relative flex h-7 w-7 items-center justify-center rounded-full text-[11.5px] font-bold transition-[background-color,color,box-shadow] duration-500 ${
                state === 'active'
                  ? 'bg-[rgb(var(--accent))] text-white shadow-[0_0_22px_rgb(var(--accent)/0.65)]'
                  : state === 'done'
                    ? 'bg-white/90 text-black'
                    : state === 'skipped'
                      ? 'border border-white/25 text-white/50'
                      : 'border border-white/20 text-white/45'
              }`}
            >
              {state === 'done' ? (
                <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
              ) : state === 'skipped' ? (
                '–'
              ) : (
                i + 1
              )}
            </span>
            <span className={`text-[13px] font-semibold transition-colors duration-500 ${state === 'active' ? 'text-white' : state === 'done' ? 'text-white/70' : state === 'skipped' ? 'text-white/40 line-through decoration-white/25' : 'text-white/40'}`}>
              {t(`onboarding.steps.${s}`)}
            </span>
            {OPTIONAL.includes(s) && <span className="text-[10.5px] text-white/35">· {t('onboarding.optional')}</span>}
          </motion.div>
        )
      })}
    </div>
  )
}

/** Téléphone : des segments en haut, chacun se remplit quand on le passe. */
function ProgressBar({ steps, index }: { steps: StepId[]; index: number }) {
  const shown = steps.filter((s) => s !== 'done')
  return (
    <motion.div
      className="flex gap-1.5"
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.9, ease: EASE }}
    >
      {shown.map((s, i) => (
        <div key={s} className="h-1 flex-1 overflow-hidden rounded-full bg-white/15">
          <motion.div
            className="h-full origin-left rounded-full bg-white"
            initial={false}
            animate={{ scaleX: i < index ? 1 : i === index ? 0.5 : 0 }}
            transition={{ duration: 0.6, ease: EASE }}
          />
        </div>
      ))}
    </motion.div>
  )
}
