import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { useIsMobile } from '@/mobile/useIsMobile'
import { useT } from '@/i18n'
import { internetReachable, serverReachable, setAccountPending } from '@/lib/onboardingAccount'
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
  // Notre serveur répond-il ? Sans lui, pas de compte possible : l'étape
  // n'est pas montrée, l'erreur est dite, et l'accueil reviendra la proposer
  // dès qu'il répondra (`lib/onboardingAccount.ts`).
  const [serverUp, setServerUp] = useState<boolean | null>(initial.current.account ? null : true)
  // Internet tout court (la page de Microsoft répond-elle ?). Sans lui, pas
  // d'écran de connexion Microsoft : l'étape du compte Minecraft ne propose
  // que le compte hors ligne, le seul qu'on puisse créer sans réseau.
  const [online, setOnline] = useState<boolean | null>(initial.current.microsoft && !preview ? null : true)
  const [skippedAccount, setSkippedAccount] = useState(false)
  const [step, setStep] = useState<StepId | null>(null)
  const [leaving, setLeaving] = useState(false)
  // Les étapes faites pour de bon (pas passées) : coche au rail, récapitulatif à la fin.
  const [completed, setCompleted] = useState<StepId[]>([])

  useEffect(() => {
    if (online === null) void internetReachable().then(setOnline)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (serverUp !== null) return
    void serverReachable().then((up) => {
      setServerUp(up)
      if (!up && !preview) setAccountPending(true)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Une session ouverte (ici ou par un lien) : plus rien d'attente.
  useEffect(() => {
    if (signedIn) setAccountPending(false)
  }, [signedIn])

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
  // Serveur muet : l'étape du compte n'existe pas cette fois (l'aperçu la
  // garde, pour voir son écran d'erreur).
  const accountOffered = initial.current.account && (preview || serverUp !== false)
  const buildSteps = (skipped: boolean): StepId[] => {
    const list: StepId[] = []
    if (accountOffered) list.push('account')
    if (initial.current.microsoft) list.push('microsoft')
    if (needsInstance) list.push('instance')
    // En aperçu, elles restent quoi qu'il arrive : c'est pour les voir.
    if (preview || (!skipped && (signedIn || accountOffered))) list.push('friends', 'profile')
    list.push('done')
    return list
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const steps = useMemo(() => buildSteps(skippedAccount), [needsInstance, skippedAccount, signedIn, serverUp])

  // Premier écran une fois qu'on sait s'il faut une instance et si le
  // serveur répond.
  useEffect(() => {
    if (step === null && needsInstance !== null && serverUp !== null && online !== null) setStep(steps[0])
  }, [step, needsInstance, serverUp, online, steps])

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

  // Téléphone : chaque étape repart du haut de la colonne.
  const column = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (mobile) column.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }, [current, mobile])

  return (
    <div className="relative h-full w-full overflow-hidden bg-black text-white">
      {ready && <Backdrop scene={SCENES[current]} mobile={mobile} leaving={leaving} />}

      {visible && (
        <motion.div
          ref={column}
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
              <Headline step={current} index={index} total={steps.length} mobile={mobile} offline={online === false} />
              {/* Le compte attendra : dit une fois, et tenu sur toutes les étapes. */}
              {serverUp === false && initial.current.account && !preview && (
                <motion.div
                  className="mt-5 flex max-w-[440px] items-start gap-3 rounded-2xl border border-amber-300/30 bg-amber-400/10 px-4 py-3"
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.6, delay: 0.9, ease: EASE }}
                >
                  <svg viewBox="0 0 24 24" className="mt-0.5 h-[18px] w-[18px] shrink-0 text-amber-300" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" />
                  </svg>
                  <span className="text-[13px] leading-relaxed text-amber-100/90 max-sm:text-[14px]">
                    <span className="block font-semibold text-amber-100">{t('onboarding.offline.title')}</span>
                    {t('onboarding.offline.text')}
                  </span>
                </motion.div>
              )}
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
            {/* Téléphone : la carte ne défile pas elle-même, c'est la colonne
                qui défile. Une carte en `overflow-y: auto` + `overscroll-contain`
                attrapait le doigt sans rien avoir à faire défiler, et le bas du
                formulaire restait hors d'atteinte. Pas de flou de verre non plus
                (une carte opaque) : flouter la scène qui bouge derrière coûtait
                des images au téléphone. */}
            <motion.div
              layout={!mobile}
              transition={{ layout: { duration: 0.5, ease: EASE } }}
              style={{ borderRadius: 24 }}
              className={`relative overflow-x-hidden border border-white/[0.12] p-6 shadow-[0_30px_80px_rgb(0_0_0/0.55)] max-sm:p-5 ${
                mobile
                  ? 'bg-[rgb(12_12_20/0.9)]'
                  : 'min-h-0 overflow-y-auto overscroll-contain bg-[rgb(12_12_20/0.62)] backdrop-blur-2xl [scrollbar-width:none] [&::-webkit-scrollbar]:hidden'
              }`}
            >
              {/* Un liseré de lumière en haut de la carte : le verre. */}
              <span className="pointer-events-none absolute inset-x-6 top-0 h-px bg-gradient-to-r from-transparent via-white/40 to-transparent" />
              <AnimatePresence mode="popLayout" initial={false}>
                <motion.div
                  key={current}
                  layout={mobile ? false : 'position'}
                  // Le flou ne sert qu'à l'ordinateur : sur téléphone, flouter
                  // toute la carte à chaque étape faisait sauter des images.
                  initial={mobile ? { opacity: 0, x: 32 } : { opacity: 0, x: 48, filter: 'blur(6px)' }}
                  animate={mobile ? { opacity: 1, x: 0 } : { opacity: 1, x: 0, filter: 'blur(0px)' }}
                  exit={
                    mobile
                      ? { opacity: 0, x: -32, transition: { duration: 0.22, ease: [0.7, 0, 0.84, 0] } }
                      : { opacity: 0, x: -48, filter: 'blur(6px)', transition: { duration: 0.28, ease: [0.7, 0, 0.84, 0] } }
                  }
                  transition={{ duration: mobile ? 0.4 : 0.55, ease: EASE }}
                >
                  {current === 'account' && (
                    <AccountStep
                      onDone={() => next('account')}
                      // Serveur tombé en cours de route : même chose qu'au
                      // départ, le compte sera reproposé quand il répondra.
                      onSkip={() => { if (!preview) setAccountPending(true); next(undefined, true) }}
                    />
                  )}
                  {current === 'microsoft' && <MicrosoftStep onDone={() => next('microsoft')} offlineOnly={online === false} />}
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
function Headline({ step, index, total, mobile, offline }: { step: StepId; index: number; total: number; mobile: boolean; offline: boolean }) {
  const t = useT()
  const first = useRef(true)
  // Le tout premier titre attend que la scène soit posée ; les suivants non.
  const delay = first.current ? 0.35 : 0.12
  useEffect(() => {
    first.current = false
  }, [])
  const optional = OPTIONAL.includes(step)
  // Sans réseau, l'étape Minecraft ne parle pas de Microsoft : on y choisit un pseudo.
  const textKey = step === 'microsoft' && offline ? 'microsoftOffline' : step

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
          <Words text={t(`onboarding.headline.${textKey}`)} delay={delay + 0.05} mobile={mobile} />
        )}

        <motion.p
          className={`mt-4 max-w-[440px] leading-relaxed text-white/70 ${mobile ? 'text-[14.5px]' : 'text-[15.5px]'}`}
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: delay + 0.4, ease: EASE }}
        >
          {t(`onboarding.lead.${textKey}`)}
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
