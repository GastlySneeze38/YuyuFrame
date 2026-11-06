import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useInView, useMotionValueEvent, useReducedMotion, useScroll, useSpring, useTransform } from 'framer-motion'
import { api } from '@/api/client'
import { useStorePick } from '@/stores/useStore'
import { getPlans } from '@/data/plans'
import type { PlanMeta } from '@/data/plans'
import { PlanIcon } from '@/components/plans/PlanIcon'
import { UpgradeModal } from '@/components/plans/UpgradeModal'
import { usePlanCheckout } from '@/components/plans/usePlanCheckout'
import { AnimatedPrice, ARGUMENTS } from '@/pages/Plans'
import { PageGlow } from '@/components/PageGlow'
import { Reveal } from '@/components/Reveal'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { EASE_OUT, transition } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * L'écran des offres sur téléphone. Même parti pris et même thème que le PC
 * (`pages/Plans.tsx`) : une formule mise en avant, les arguments, la
 * comparaison, la transparence, la FAQ, un dernier rappel — le tout se lit en
 * défilant, chaque section apparaissant à son arrivée, sur les mêmes lueurs
 * qui dérivent et la même barre de lecture en haut.
 *
 * Ce qui change pour le téléphone :
 * - une seule colonne partout ; les trois formules de la comparaison
 *   s'empilent au lieu de se serrer côte à côte ;
 * - textes à 15 px au moins, boutons de 48 px pleine largeur ;
 * - plus d'animation en boucle (halo qui respire, colonne qui flotte) : sur
 *   un téléphone elles coûtent de la batterie pour un effet qu'on ne regarde
 *   pas, et rien ne se soulève au survol puisqu'il n'y a pas de survol ;
 * - une barre d'achat apparaît en bas une fois le bouton de l'offre passé,
 *   et s'efface quand le rappel de fin arrive : le bouton reste sous le
 *   pouce sans jamais être en double à l'écran.
 */
export default function MobilePlans() {
  const t = useT()
  const reduce = useReducedMotion()
  const { yuyuPlanExpiresAt, isPremium, isUltimate, language } = useStorePick('yuyuPlanExpiresAt', 'isPremium', 'isUltimate', 'language', 'yuyuPlan')
  const PLANS = getPlans(t)
  const effectivePlan = isUltimate() ? 'ultimate' : isPremium() ? 'premium' : 'free'
  const premium = PLANS.find((p) => p.id === 'premium')

  const {
    refreshing, upgradeTarget, setUpgradeTarget, checkoutState, setCheckoutState, checkoutError,
    handleRefresh, handleCheckout, closeUpgrade,
  } = usePlanCheckout()

  useEffect(() => {
    api.analytics.track('plans_page_viewed')
  }, [])

  // Défilement : barre de lecture, lueurs qui dérivent, accroche qui s'efface.
  const scrollRef = useRef<HTMLDivElement>(null)
  const comparisonRef = useRef<HTMLDivElement>(null)
  const { scrollY, scrollYProgress } = useScroll({ container: scrollRef })
  const progress = useSpring(scrollYProgress, { stiffness: 140, damping: 26, mass: 0.4 })
  const glowY = useTransform(scrollYProgress, [0, 1], [0, -120])
  const heroY = useTransform(scrollYProgress, [0, 0.1], [0, -32])
  const heroOpacity = useTransform(scrollYProgress, [0, 0.09], [1, 0.25])

  // Barre d'achat du bas : visible une fois le bouton de l'offre passé (au-
  // dessus de l'écran), effacée quand le rappel de fin arrive. « Passé » se
  // lit sur la position de défilement plutôt que sur la visibilité : un
  // défilement rapide peut sauter par-dessus le bouton sans qu'il ait jamais
  // été vu. L'état ne change qu'au franchissement, pas à chaque image.
  const offerBtnRef = useRef<HTMLButtonElement>(null)
  const finalRef = useRef<HTMLDivElement>(null)
  const finalVisible = useInView(finalRef, { root: scrollRef })
  const [pastOffer, setPastOffer] = useState(false)
  useMotionValueEvent(scrollY, 'change', () => {
    const btn = offerBtnRef.current
    const box = scrollRef.current
    // Positions à l'écran : `offsetTop` se mesure depuis le parent transformé
    // le plus proche (le bloc qui apparaît au défilement), pas depuis la zone
    // qui défile.
    if (btn && box) setPastOffer(btn.getBoundingClientRect().bottom < box.getBoundingClientRect().top)
  })
  const showBar = effectivePlan === 'free' && !!premium && pastOffer && !finalVisible && !upgradeTarget

  const expiryDate = yuyuPlanExpiresAt
    ? new Date(yuyuPlanExpiresAt * 1000).toLocaleDateString(language === 'fr' ? 'fr-FR' : 'en-US', {
        day: '2-digit',
        month: 'long',
        year: 'numeric',
      })
    : null

  const kicker = 'text-[13px] font-semibold uppercase tracking-[0.12em] text-accent-hover'
  const section = 'border-t border-line-soft px-5 py-14'

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <header className="flex shrink-0 items-center gap-1 pb-1 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
        <MenuButton />
        <h1 className="min-w-0 flex-1 truncate text-[28px] font-bold tracking-tight">{t('home.nav.plans')}</h1>
      </header>

      <div ref={scrollRef} className="relative min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <motion.div style={{ y: glowY }} className="pointer-events-none absolute inset-0 -z-10">
          <PageGlow />
        </motion.div>

        {/* Barre de lecture, collée en haut de la partie qui défile. */}
        <motion.div style={{ scaleX: progress }} className="sticky top-0 z-30 h-[2px] origin-left bg-accent" />

        {/* ── Accroche ─────────────────────────────────────────────────── */}
        <section className="px-5 pb-8 pt-8">
          <motion.div style={{ y: heroY, opacity: heroOpacity }} className="flex flex-col items-center gap-4 text-center">
            <motion.span
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.4, ease: EASE_OUT }}
              className="rounded-full border border-line px-4 py-1.5 text-[13px] font-medium text-txt-secondary"
            >
              {effectivePlan === 'free' ? t('plans.heroBadgeFree') : t('plans.heroBadgeSubscriber')}
            </motion.span>
            {/* Le titre arrive mot par mot, comme sur PC : l'écran se visite
                rarement, il peut se permettre d'entrer en scène. */}
            <h2 className="text-[32px] font-bold leading-[1.1] tracking-tight text-balance">
              {t('plans.heroTitle').split(' ').map((word, i) => (
                <motion.span
                  key={`${word}-${i}`}
                  initial={reduce ? { opacity: 0 } : { opacity: 0, y: 12 }}
                  animate={reduce ? { opacity: 1 } : { opacity: 1, y: 0 }}
                  transition={{ delay: 0.08 + i * 0.035, duration: 0.45, ease: EASE_OUT }}
                  className="inline-block"
                >
                  {word}&nbsp;
                </motion.span>
              ))}
            </h2>
            <motion.p
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ delay: 0.3, duration: 0.4 }}
              className="text-[15px] leading-relaxed text-txt-secondary"
            >
              {t('plans.heroSubtitle')}
            </motion.p>
            {expiryDate && effectivePlan !== 'free' && (
              <p className="text-[13px] text-txt-muted">
                {t('plans.expiresOnPrefix')} {effectivePlan} {t('plans.expiresOnSuffix')}{' '}
                <span className="font-semibold text-txt-secondary">{expiryDate}</span>
              </p>
            )}
          </motion.div>
        </section>

        {/* ── L'offre mise en avant ────────────────────────────────────── */}
        {premium && (
          <div className="px-4 pb-14">
            <Reveal delay={0.12}>
              <FeaturedOffer
                plan={premium}
                current={effectivePlan === 'premium'}
                buttonRef={offerBtnRef}
                onChoose={() => setUpgradeTarget('premium')}
                onCompare={() => comparisonRef.current?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })}
              />
            </Reveal>
          </div>
        )}

        {/* ── Ce que l'abonnement apporte ──────────────────────────────── */}
        <section className={section}>
          <Reveal className="mb-8 flex flex-col gap-2.5">
            <span className={kicker}>{t('plans.sell.kicker')}</span>
            <h2 className="text-[24px] font-bold leading-tight tracking-tight">{t('plans.sell.title')}</h2>
            <p className="text-[15px] leading-relaxed text-txt-secondary">{t('plans.sell.subtitle')}</p>
          </Reveal>
          <div className="flex flex-col gap-6">
            {ARGUMENTS.map((arg, i) => (
              <Reveal key={arg.key} delay={i * 0.05}>
                <div className="flex gap-4">
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent/12 text-accent-hover">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-[22px] w-[22px]">
                      <motion.path
                        d={arg.path}
                        initial={{ pathLength: 0, opacity: 0 }}
                        whileInView={{ pathLength: 1, opacity: 1 }}
                        viewport={{ once: false }}
                        transition={{ duration: 0.8, ease: 'easeInOut', delay: 0.15 }}
                      />
                    </svg>
                  </div>
                  <div className="flex min-w-0 flex-col gap-1">
                    <h3 className="text-[16px] font-semibold">{t(`plans.sell.${arg.key}Title`)}</h3>
                    <p className="text-[15px] leading-relaxed text-txt-secondary">{t(`plans.sell.${arg.key}Text`)}</p>
                  </div>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        {/* ── Comparaison : les trois formules l'une sous l'autre ──────── */}
        <section ref={comparisonRef} className={`${section} scroll-mt-2`}>
          <Reveal className="mb-8 flex flex-col gap-2.5">
            <span className={kicker}>{t('plans.compareKicker')}</span>
            <h2 className="text-[24px] font-bold leading-tight tracking-tight">{t('plans.compareTitle')}</h2>
          </Reveal>
          <div className="flex flex-col gap-4">
            {PLANS.map((plan) => (
              <Reveal key={plan.id}>
                <PlanCard plan={plan} current={plan.id === effectivePlan} onChoose={() => setUpgradeTarget(plan.id)} />
              </Reveal>
            ))}
          </div>
        </section>

        {/* ── Transparence ─────────────────────────────────────────────── */}
        <section className={section}>
          <Reveal className="flex flex-col gap-2.5">
            <span className={kicker}>{t('plans.funding.kicker')}</span>
            <h2 className="text-[22px] font-bold leading-tight tracking-tight">{t('plans.funding.title')}</h2>
            <p className="text-[15px] leading-relaxed text-txt-secondary">{t('plans.funding.text')}</p>
          </Reveal>
        </section>

        {/* ── Questions fréquentes ─────────────────────────────────────── */}
        <section className={section}>
          <Reveal className="mb-4">
            <h2 className="text-[22px] font-bold tracking-tight">{t('plans.faq.title')}</h2>
          </Reveal>
          <div className="flex flex-col">
            {['stop', 'limited', 'devices', 'cancel'].map((key) => (
              <FaqItem key={key} question={t(`plans.faq.${key}Q`)} answer={t(`plans.faq.${key}A`)} />
            ))}
          </div>
        </section>

        {/* ── Rappel d'achat ───────────────────────────────────────────── */}
        {effectivePlan === 'free' && (
          <section className={section}>
            <div ref={finalRef}>
              <Reveal className="flex flex-col items-center gap-4 text-center">
                <h2 className="text-[22px] font-bold tracking-tight">{t('plans.finalTitle')}</h2>
                <p className="text-[15px] leading-relaxed text-txt-secondary">{t('plans.finalText')}</p>
                <button
                  onClick={() => setUpgradeTarget('premium')}
                  className="press mt-1 h-12 w-full rounded-2xl bg-accent text-[16px] font-bold text-txt-primary shadow-[0_8px_28px_rgba(75,63,207,0.35)]"
                >
                  {t('plans.finalCta')}
                </button>
              </Reveal>
            </div>
          </section>
        )}

        <p className="px-6 pb-[max(32px,env(safe-area-inset-bottom))] pt-2 text-center text-[13px] leading-relaxed text-txt-muted">
          {t('plans.footerNote')}
        </p>
      </div>

      {/* Barre d'achat du bas : monte quand le bouton de l'offre sort de
          l'écran, redescend (plus vite) quand on y revient ou au rappel. */}
      <AnimatePresence>
        {showBar && premium && (
          <motion.div
            initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(110%)' }}
            animate={reduce ? { opacity: 1 } : { opacity: 1, transform: 'translateY(0%)' }}
            exit={reduce
              ? { opacity: 0, transition: { duration: 0.15 } }
              : { opacity: 0, transform: 'translateY(110%)', transition: { duration: 0.16, ease: EASE_OUT } }}
            transition={{ duration: 0.26, ease: EASE_OUT }}
            className="absolute inset-x-3 bottom-[max(12px,env(safe-area-inset-bottom))] z-20 flex items-center gap-3 rounded-2xl border border-accent/30 bg-bg-secondary p-2 pl-4 shadow-[0_10px_32px_rgba(0,0,0,0.55)]"
          >
            <PlanIcon plan={premium.id} color={premium.color} />
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[15px] font-semibold" style={{ color: premium.color }}>{premium.name}</span>
              <span className="truncate text-[13px] text-txt-secondary">
                {premium.price?.replace('.', ',')}€ {t('plans.perMonth')}
              </span>
            </div>
            <button
              onClick={() => setUpgradeTarget('premium')}
              className="press h-11 shrink-0 rounded-xl bg-accent px-5 text-[15px] font-bold text-txt-primary"
            >
              {t('plans.upgradeTo', { name: premium.name })}
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {upgradeTarget && (
        <UpgradeModal
          plan={upgradeTarget}
          checkoutState={checkoutState}
          checkoutError={checkoutError}
          onClose={closeUpgrade}
          onCheckout={() => handleCheckout(upgradeTarget)}
          onRefresh={async () => {
            await handleRefresh()
            setCheckoutState('idle')
          }}
          refreshing={refreshing}
        />
      )}
    </div>
  )
}

/** L'offre mise en avant : nom, prix qui se compte, bénéfices, deux boutons. */
function FeaturedOffer({ plan, current, buttonRef, onChoose, onCompare }: {
  plan: PlanMeta
  current: boolean
  buttonRef: React.RefObject<HTMLButtonElement>
  onChoose: () => void
  onCompare: () => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-5 rounded-3xl border border-accent/30 bg-accent/[0.06] p-5 shadow-[0_0_42px_rgba(75,63,207,0.12)]">
      <div className="flex items-center gap-2.5">
        <PlanIcon plan={plan.id} color={plan.color} />
        <span className="text-[17px] font-semibold" style={{ color: plan.color }}>{plan.name}</span>
        {current && <CurrentBadge plan={plan} label={t('plans.current')} />}
      </div>
      <div className="flex flex-col gap-1.5">
        <div className="flex items-end gap-1.5">
          <AnimatedPrice value={Number(plan.price)} className="text-[44px] font-bold leading-none tracking-tight" />
          <span className="mb-1.5 text-[15px] text-txt-secondary">{t('plans.perMonth')}</span>
        </div>
        <p className="text-[13px] leading-relaxed text-txt-muted">{t('plans.heroPriceLine')}</p>
      </div>
      <ul className="flex flex-col gap-3">
        {plan.features.filter((f) => f.ok).map((feat) => (
          <li key={feat.label} className="flex items-start gap-3 text-[15px] leading-snug text-txt-secondary">
            <Check />
            {feat.label}
          </li>
        ))}
      </ul>
      <div className="flex flex-col gap-2">
        <button
          ref={buttonRef}
          onClick={current ? undefined : onChoose}
          disabled={current}
          className="press h-12 w-full rounded-2xl bg-accent text-[16px] font-bold text-txt-primary disabled:opacity-60"
        >
          {current ? t('plans.currentPlan') : t('plans.upgradeTo', { name: plan.name })}
        </button>
        <button
          onClick={onCompare}
          className="press h-11 w-full rounded-2xl border border-line text-[15px] font-medium text-txt-secondary"
        >
          {t('plans.heroCta')}
        </button>
      </div>
    </div>
  )
}

/** Une formule de la comparaison, pleine largeur. */
function PlanCard({ plan, current, onChoose }: { plan: PlanMeta; current: boolean; onChoose: () => void }) {
  const t = useT()
  const highlighted = Boolean(plan.featured) && !plan.comingSoon
  const buyable = Boolean(plan.price) && !plan.comingSoon && !current
  return (
    <div
      className={`flex flex-col gap-4 rounded-2xl border p-5 ${
        plan.comingSoon ? 'border-line-soft opacity-60' : highlighted ? 'border-accent/45 bg-accent/[0.05]' : 'border-line'
      }`}
    >
      <div className="flex items-center gap-2.5">
        <PlanIcon plan={plan.id} color={plan.color} />
        <span className="min-w-0 flex-1 truncate text-[17px] font-semibold" style={{ color: plan.price ? plan.color : undefined }}>
          {plan.name}
        </span>
        {(current || plan.comingSoon) && (
          <CurrentBadge plan={plan} label={current ? t('plans.current') : t('plans.comingSoon')} />
        )}
      </div>
      <div className="flex items-end gap-1">
        <span className="text-[30px] font-bold leading-none tracking-tight">{plan.price ? `${plan.price.replace('.', ',')}€` : '0€'}</span>
        {plan.price && <span className="mb-0.5 text-[14px] text-txt-muted">{t('plans.perMonth')}</span>}
      </div>
      <p className="rounded-xl border border-line-soft px-3.5 py-2.5 text-[13px] leading-relaxed text-txt-secondary">
        {plan.id === 'free'
          ? t('plans.quotaFree')
          : t('plans.quotaLine', {
              instances: plan.id === 'ultimate' ? '10' : t('plans.premiumSyncQuota'),
              saves: plan.id === 'ultimate' ? '10' : '3',
            })}
      </p>
      <ul className="flex flex-col gap-3">
        {plan.features.map((feat) => (
          <li key={feat.label} className={`flex items-start gap-3 text-[15px] leading-snug ${feat.ok ? 'text-txt-secondary' : 'text-txt-muted'}`}>
            {feat.ok ? <Check /> : <span className="mt-px w-[18px] shrink-0 text-center text-[15px]">—</span>}
            <span className={feat.ok ? '' : 'line-through'}>{feat.label}</span>
          </li>
        ))}
      </ul>
      {/* Seule une offre qu'on peut prendre a un bouton : « offre actuelle »
          et « bientôt » sont déjà dits par la pastille du titre. */}
      {buyable && (
        <button
          onClick={onChoose}
          className={`press h-12 w-full rounded-2xl text-[16px] font-bold ${
            highlighted ? 'bg-accent text-txt-primary' : 'border border-line text-txt-secondary'
          }`}
        >
          {t('plans.upgradeTo', { name: plan.name })}
        </button>
      )}
    </div>
  )
}

function CurrentBadge({ plan, label }: { plan: PlanMeta; label: string }) {
  return (
    <span className="shrink-0 rounded-full px-2.5 py-0.5 text-[13px] font-bold tracking-wide" style={{ background: plan.badgeBg, color: plan.badgeColor }}>
      {label}
    </span>
  )
}

/** Une question de la FAQ : toute la ligne se touche, la réponse se déplie. */
function FaqItem({ question, answer }: { question: string; answer: string }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="border-b border-line-soft">
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex min-h-[56px] w-full items-center justify-between gap-4 py-3 text-left text-[16px] font-medium text-txt-primary"
      >
        {question}
        <motion.span
          animate={{ rotate: open ? 135 : 0, color: open ? 'rgb(var(--accent-hover))' : 'rgb(var(--txt-muted))' }}
          transition={transition}
          className="shrink-0 text-[22px] font-normal leading-none"
        >
          +
        </motion.span>
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={transition}
            className="overflow-hidden"
          >
            <p className="pb-4 text-[15px] leading-relaxed text-txt-secondary">{answer}</p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

/** Coche qui se trace à l'arrivée à l'écran. */
function Check() {
  return (
    <svg viewBox="0 0 16 16" fill="none" className="mt-[2px] h-[18px] w-[18px] shrink-0 text-accent-hover">
      <motion.path
        d="M3 8.5l3.2 3.2L13 5"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        initial={{ pathLength: 0 }}
        whileInView={{ pathLength: 1 }}
        viewport={{ once: false }}
        transition={{ duration: 0.4, ease: 'easeOut' }}
      />
    </svg>
  )
}
