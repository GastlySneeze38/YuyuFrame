import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-shell'
import { AnimatePresence, animate, motion, useInView, useMotionValue, useScroll, useSpring, useTransform } from 'framer-motion'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { YuyuPlan } from '@/stores/useStore'
import { getPlans } from '@/data/plans'
import type { PlanMeta } from '@/data/plans'
import { PlanIcon } from '@/components/plans/PlanIcon'
import { UpgradeModal } from '@/components/plans/UpgradeModal'
import { HeaderAccountBadge } from '@/components/ui/HeaderAccountBadge'
import { BackArrowIcon } from '@/components/ui/icons/BackArrowIcon'
import { PageGlow } from '@/components/PageGlow'
import { Reveal } from '@/components/Reveal'
import { showApiError } from '@/stores/useErrorToast'
import { errorMessage, isNetworkError } from '@/lib/apiError'
import { listItemVariants, listVariants, transition } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Page des offres.
 *
 * Construite sur ce que font les autres launchers et plateformes de mods
 * (Modrinth Plus, Lunar+) : une seule formule vraiment mise en avant plutôt
 * que trois cartes à égalité, des avantages en titre gras + une phrase, un
 * cadrage honnête sur ce que l'argent finance, une FAQ courte, et un rappel
 * d'achat en fin de page. Le tout se parcourt en défilant, chaque section
 * apparaissant à son arrivée.
 *
 * Aucun aplat clair : les blocs se distinguent par leur bordure et de très
 * légères teintes d'accent, jamais par du blanc.
 */
export default function Plans() {
  const navigate = useNavigate()
  const t = useT()
  const { yuyuPlanExpiresAt, isPremium, isUltimate, setYuyuPlan, language } = useStore()

  const PLANS = getPlans(t)
  const effectivePlan = isUltimate() ? 'ultimate' : isPremium() ? 'premium' : 'free'
  const comparisonRef = useRef<HTMLDivElement>(null)

  const [refreshing, setRefreshing] = useState(false)
  const [upgradeTarget, setUpgradeTarget] = useState<string | null>(null)
  const [checkoutState, setCheckoutState] = useState<'idle' | 'loading' | 'waiting' | 'success' | 'timeout' | 'error'>('idle')
  const [checkoutError, setCheckoutError] = useState<string | null>(null)

  useEffect(() => {
    api.analytics.track('plans_page_viewed')
  }, [])

  /** Utilisé par UpgradeModal après un délai d'attente de paiement dépassé. */
  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      const resp = await api.yuyu.refreshPlan()
      setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setRefreshing(false)
    }
  }

  const handleCheckout = async (planId: string) => {
    setCheckoutState('loading')
    setCheckoutError(null)
    try {
      const { checkout_url } = await api.yuyu.createCheckout(planId)
      // Défense en profondeur : n'ouvrir que des URLs https — au cas où la
      // réponse serait un jour corrompue/interceptée (API compromise,
      // YUYU_API_URL pointé vers un serveur non fiable), on n'ouvre jamais
      // aveuglément un schéma arbitraire (file://, javascript:, etc.).
      if (!checkout_url.startsWith('https://')) {
        throw t('plans.invalidCheckoutUrl')
      }
      await open(checkout_url)
      api.analytics.track('checkout_redirected', { plan: planId })
      setCheckoutState('waiting')
      // Le webhook de paiement arrive sur le serveur : ici on ne peut
      // qu'observer le résultat. Sondage toutes les 3 s, 60 s au plus.
      for (let i = 0; i < 20; i++) {
        await new Promise<void>((r) => setTimeout(r, 3000))
        const resp = await api.yuyu.refreshPlan()
        if (resp.plan !== 'free') {
          setYuyuPlan(resp.plan as YuyuPlan, resp.plan_expires_at)
          setCheckoutState('success')
          setTimeout(() => { setUpgradeTarget(null); setCheckoutState('idle') }, 2500)
          return
        }
      }
      setCheckoutState('timeout')
    } catch (e) {
      setCheckoutError(isNetworkError(e) ? t('common.serverUnreachable') : errorMessage(e))
      setCheckoutState('error')
    }
  }

  // Animations liées au défilement : barre de progression, dérive des
  // lueurs de fond, envol de l'accroche. `container` : c'est cette div qui
  // défile, pas la fenêtre.
  const scrollRef = useRef<HTMLDivElement>(null)
  const { scrollYProgress } = useScroll({ container: scrollRef })
  const progress = useSpring(scrollYProgress, { stiffness: 140, damping: 26, mass: 0.4 })
  const glowY = useTransform(scrollYProgress, [0, 1], [0, -120])
  const heroY = useTransform(scrollYProgress, [0, 0.14], [0, -40])
  const heroOpacity = useTransform(scrollYProgress, [0, 0.12], [1, 0.25])

  const premium = PLANS.find((p) => p.id === 'premium')
  const expiryDate = yuyuPlanExpiresAt
    ? new Date(yuyuPlanExpiresAt * 1000).toLocaleDateString(language === 'fr' ? 'fr-FR' : 'en-US', {
        day: '2-digit',
        month: 'long',
        year: 'numeric',
      })
    : null

  return (
    <div ref={scrollRef} className="relative h-full overflow-y-auto bg-bg-primary text-txt-primary">
      {/* Les lueurs de fond dérivent doucement pendant le défilement : la
          page semble avoir de la profondeur sans rien flouter. */}
      <motion.div style={{ y: glowY }} className="pointer-events-none absolute inset-0 -z-10">
        <PageGlow />
      </motion.div>

      {/* Barre de progression de lecture, collée en haut de la page. */}
      <motion.div
        style={{ scaleX: progress }}
        className="sticky top-0 z-30 h-[2px] origin-left bg-accent"
      />

      {/* Retour et compte, posés simplement : la fenêtre a déjà sa barre de
          titre, pas besoin d'une barre collante de site web. */}
      <div className="flex items-center px-7 pt-5">
        <button
          onClick={() => navigate('/home')}
          className="flex items-center gap-2 text-[12px] font-medium text-txt-muted transition-colors duration-150 hover:text-txt-primary"
        >
          <BackArrowIcon size={14} />
          {t('plans.back')}
        </button>
        <div className="flex-1" />
        <HeaderAccountBadge />
      </div>

      {/* ── Accroche + l'offre mise en avant ──────────────────────────── */}
      <section className="px-7 pb-24 pt-14">
        {/* L'accroche monte et s'efface quand on descend : elle laisse la
            place à l'offre au lieu de rester collée en haut. */}
        <motion.div style={{ y: heroY, opacity: heroOpacity }}>
          <motion.div
            variants={listVariants}
            initial="initial"
            animate="animate"
            className="mx-auto mb-12 flex max-w-2xl flex-col items-center gap-5 text-center"
          >
            <motion.span
              variants={listItemVariants}
              className="rounded-full border border-line px-4 py-1 text-[11px] font-medium tracking-wide text-txt-secondary"
            >
              {effectivePlan === 'free' ? t('plans.heroBadgeFree') : t('plans.heroBadgeSubscriber')}
            </motion.span>
            {/* Le titre arrive mot par mot. */}
            <h1 className="text-[40px] font-bold leading-[1.08] tracking-tight text-balance">
              {t('plans.heroTitle').split(' ').map((word, i) => (
                <motion.span
                  key={`${word}-${i}`}
                  initial={{ opacity: 0, y: 14 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: 0.12 + i * 0.035, duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
                  className="inline-block"
                >
                  {word}&nbsp;
                </motion.span>
              ))}
            </h1>
            <motion.p variants={listItemVariants} className="text-[15px] leading-relaxed text-txt-secondary">
              {t('plans.heroSubtitle')}
            </motion.p>
            {expiryDate && effectivePlan !== 'free' && (
              <motion.p variants={listItemVariants} className="text-[12px] text-txt-muted">
                {t('plans.expiresOnPrefix')} {effectivePlan} {t('plans.expiresOnSuffix')}{' '}
                <span className="font-semibold text-txt-secondary">{expiryDate}</span>
              </motion.p>
            )}
          </motion.div>
        </motion.div>

        {premium && (
          <Reveal delay={0.08}>
            <FeaturedOffer
              plan={premium}
              current={effectivePlan === 'premium'}
              onChoose={() => setUpgradeTarget('premium')}
              onCompare={() => comparisonRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
            />
          </Reveal>
        )}
      </section>

      {/* ── Ce que l'abonnement apporte ───────────────────────────────── */}
      <section className="border-t border-line-soft px-7 py-24">
        <div className="mx-auto max-w-4xl">
          <Reveal className="mb-12 flex flex-col items-center gap-3 text-center">
            <span className="text-[12px] font-semibold uppercase tracking-widest text-accent-hover">
              {t('plans.sell.kicker')}
            </span>
            <h2 className="text-[30px] font-bold tracking-tight">{t('plans.sell.title')}</h2>
            <p className="max-w-xl text-[14px] leading-relaxed text-txt-secondary">{t('plans.sell.subtitle')}</p>
          </Reveal>

          {/* Trois colonnes depuis que les statistiques sont ouvertes à tout
              le monde : mieux vaut trois arguments vrais qu'un quatrième qui
              vend ce qui est déjà gratuit. */}
          <div className="grid grid-cols-3 gap-x-10 gap-y-9">
            {ARGUMENTS.map((arg, i) => (
              // Les colonnes arrivent de leur propre côté, en quinconce.
              <motion.div
                key={arg.key}
                initial={{ opacity: 0, x: i % 2 ? 24 : -24, y: 10 }}
                whileInView={{ opacity: 1, x: 0, y: 0 }}
                viewport={{ once: false, margin: '0px 0px -12% 0px' }}
                transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1], delay: (i % 2) * 0.06 }}
                whileHover="hover"
                className="group flex gap-4"
              >
                <motion.div
                  variants={{ hover: { scale: 1.12, rotate: -6 } }}
                  transition={{ type: 'spring', stiffness: 420, damping: 18 }}
                  className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent/12 text-accent-hover"
                >
                  {/* Le tracé se dessine à l'arrivée. */}
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
                    <motion.path
                      d={arg.path}
                      initial={{ pathLength: 0, opacity: 0 }}
                      whileInView={{ pathLength: 1, opacity: 1 }}
                      viewport={{ once: false }}
                      transition={{ duration: 0.9, ease: 'easeInOut', delay: 0.15 + i * 0.08 }}
                    />
                  </svg>
                </motion.div>
                <div className="flex flex-col gap-1.5">
                  <h3 className="text-[15px] font-semibold transition-colors duration-150 group-hover:text-accent-hover">
                    {t(`plans.sell.${arg.key}Title`)}
                  </h3>
                  <p className="text-[13px] leading-relaxed text-txt-secondary">{t(`plans.sell.${arg.key}Text`)}</p>
                </div>
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      {/* ── Comparaison des formules ──────────────────────────────────── */}
      <section ref={comparisonRef} className="scroll-mt-4 border-t border-line-soft px-7 py-24">
        <div className="mx-auto max-w-5xl">
          <Reveal className="mb-12 flex flex-col items-center gap-3 text-center">
            <span className="text-[12px] font-semibold uppercase tracking-widest text-accent-hover">
              {t('plans.compareKicker')}
            </span>
            <h2 className="text-[30px] font-bold tracking-tight">{t('plans.compareTitle')}</h2>
          </Reveal>

          <div className="grid grid-cols-3 items-stretch gap-4">
            {PLANS.map((plan, i) => (
              <Reveal key={plan.id} delay={i * 0.08} className="h-full">
                <PlanColumn
                  plan={plan}
                  current={plan.id === effectivePlan}
                  onChoose={() => setUpgradeTarget(plan.id)}
                />
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ── Transparence ──────────────────────────────────────────────── */}
      <section className="border-t border-line-soft px-7 py-24">
        <Reveal className="mx-auto flex max-w-2xl flex-col items-center gap-3 text-center">
          <span className="text-[12px] font-semibold uppercase tracking-widest text-accent-hover">
            {t('plans.funding.kicker')}
          </span>
          <h2 className="text-[26px] font-bold tracking-tight">{t('plans.funding.title')}</h2>
          <p className="text-[14px] leading-relaxed text-txt-secondary">{t('plans.funding.text')}</p>
        </Reveal>
      </section>

      {/* ── Questions fréquentes ──────────────────────────────────────── */}
      <section className="border-t border-line-soft px-7 py-24">
        <div className="mx-auto max-w-2xl">
          <Reveal className="mb-10 text-center">
            <h2 className="text-[26px] font-bold tracking-tight">{t('plans.faq.title')}</h2>
          </Reveal>
          <div className="flex flex-col">
            {['stop', 'limited', 'devices', 'cancel'].map((key, i) => (
              <Reveal key={key} delay={i * 0.05}>
                <FaqItem question={t(`plans.faq.${key}Q`)} answer={t(`plans.faq.${key}A`)} />
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ── Rappel d'achat, comme sur les pages des autres launchers ───── */}
      {effectivePlan === 'free' && (
        <section className="border-t border-line-soft px-7 py-20">
          <Reveal className="mx-auto flex max-w-2xl flex-col items-center gap-4 text-center">
            <h2 className="text-[24px] font-bold tracking-tight">{t('plans.finalTitle')}</h2>
            <p className="text-[13px] text-txt-secondary">{t('plans.finalText')}</p>
            <motion.button
              onClick={() => setUpgradeTarget('premium')}
              // Halo qui pulse doucement, et réaction franche au clic.
              animate={{ boxShadow: ['0 0 0 rgba(75,63,207,0)', '0 0 32px rgba(75,63,207,0.45)', '0 0 0 rgba(75,63,207,0)'] }}
              transition={{ duration: 3.4, repeat: Infinity, ease: 'easeInOut' }}
              whileHover={{ scale: 1.04 }}
              whileTap={{ scale: 0.97 }}
              className="rounded-xl bg-accent px-7 py-3 text-[14px] font-semibold text-txt-primary transition-colors duration-150 hover:bg-accent-hover"
            >
              {t('plans.finalCta')}
            </motion.button>
          </Reveal>
        </section>
      )}

      <p className="px-7 pb-16 text-center text-[11px] leading-relaxed text-txt-muted">{t('plans.footerNote')}</p>

      {upgradeTarget && (
        <UpgradeModal
          plan={upgradeTarget}
          checkoutState={checkoutState}
          checkoutError={checkoutError}
          onClose={() => { setUpgradeTarget(null); setCheckoutState('idle'); setCheckoutError(null) }}
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

/**
 * L'offre mise en avant, seule en haut de page : c'est le parti pris de
 * Modrinth Plus (une formule, un prix, trois bénéfices, un bouton) plutôt
 * que d'obliger à comparer trois colonnes avant de comprendre l'offre.
 */
function FeaturedOffer({
  plan,
  current,
  onChoose,
  onCompare,
}: {
  plan: PlanMeta
  current: boolean
  onChoose: () => void
  onCompare: () => void
}) {
  const t = useT()

  return (
    <motion.div
      // Respiration très lente de la lueur : l'offre « vit » sans clignoter.
      animate={{ boxShadow: ['0 0 0 rgba(75,63,207,0)', '0 0 42px rgba(75,63,207,0.16)', '0 0 0 rgba(75,63,207,0)'] }}
      transition={{ duration: 6, repeat: Infinity, ease: 'easeInOut' }}
      className="mx-auto grid max-w-4xl grid-cols-[1.1fr_1fr] items-center gap-10 rounded-2xl border border-accent/30 bg-accent/[0.06] p-9"
    >
      <div className="flex flex-col gap-4">
        <div className="flex items-center gap-2.5">
          <PlanIcon plan={plan.id} color={plan.color} />
          <span className="text-[17px] font-semibold" style={{ color: plan.color }}>
            {plan.name}
          </span>
          {current && (
            <span className="rounded-full px-2 py-0.5 text-[10px] font-bold tracking-wider" style={{ background: plan.badgeBg, color: plan.badgeColor }}>
              {t('plans.current')}
            </span>
          )}
        </div>
        <div className="flex items-end gap-1.5">
          <AnimatedPrice value={Number(plan.price)} className="text-[44px] font-bold leading-none tracking-tight" />
          <span className="mb-1.5 text-[14px] text-txt-secondary">{t('plans.perMonth')}</span>
        </div>
        <p className="text-[12px] leading-relaxed text-txt-muted">{t('plans.heroPriceLine')}</p>
        <div className="mt-1 flex flex-wrap gap-3">
          <button
            onClick={current ? undefined : onChoose}
            disabled={current}
            className="rounded-xl bg-accent px-6 py-2.5 text-[14px] font-semibold text-txt-primary transition-colors duration-150 hover:bg-accent-hover disabled:cursor-default disabled:opacity-60"
          >
            {current ? t('plans.currentPlan') : t('plans.upgradeTo', { name: plan.name })}
          </button>
          <button
            onClick={onCompare}
            className="rounded-xl border border-line px-6 py-2.5 text-[13px] font-medium text-txt-secondary transition-colors duration-150 hover:border-line-strong hover:text-txt-primary"
          >
            {t('plans.heroCta')}
          </button>
        </div>
      </div>

      <motion.ul
        variants={listVariants}
        initial="initial"
        whileInView="animate"
        viewport={{ once: false }}
        className="flex flex-col gap-3"
      >
        {plan.features
          .filter((f) => f.ok)
          .map((feat) => (
            <motion.li
              key={feat.label}
              variants={listItemVariants}
              whileHover={{ x: 3 }}
              transition={transition}
              className="flex items-start gap-2.5 text-[13px] leading-snug text-txt-secondary"
            >
              <Check />
              {feat.label}
            </motion.li>
          ))}
      </motion.ul>
    </motion.div>
  )
}

/**
 * Prix qui s'incrémente à l'arrivée à l'écran. Les deux décimales restent
 * lisibles pendant tout le compte : on anime la valeur, pas le texte.
 */
function AnimatedPrice({ value, className }: { value: number; className?: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const inView = useInView(ref, { margin: '0px 0px -10% 0px' })
  const count = useMotionValue(0)
  const [shown, setShown] = useState('0,00')

  useEffect(() => {
    // Sortie de l'écran : on remet le compteur à zéro pour que le prix se
    // recompte à chaque fois qu'on revient dessus.
    if (!inView) {
      count.set(0)
      setShown('0,00')
      return
    }
    const unsubscribe = count.on('change', (v) => setShown(v.toFixed(2).replace('.', ',')))
    const controls = animate(count, value, { duration: 0.9, ease: [0.16, 1, 0.3, 1] })
    return () => {
      controls.stop()
      unsubscribe()
    }
  }, [inView, value, count])

  return (
    <span ref={ref} className={className}>
      {shown}€
    </span>
  )
}

/** Colonne d'une formule dans la comparaison. */
function PlanColumn({ plan, current, onChoose }: { plan: PlanMeta; current: boolean; onChoose: () => void }) {
  const t = useT()
  const highlighted = Boolean(plan.featured) && !plan.comingSoon
  const buyable = Boolean(plan.price) && !plan.comingSoon && !current

  return (
    <motion.div
      // La colonne mise en avant flotte doucement, en continu ; toutes se
      // soulèvent au survol, sauf celle qui n'est pas encore disponible.
      animate={highlighted ? { y: [0, -5, 0] } : undefined}
      transition={highlighted ? { duration: 5, repeat: Infinity, ease: 'easeInOut' } : { duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
      whileHover={plan.comingSoon ? undefined : { y: -8, scale: 1.015 }}
      className={`relative flex h-full flex-col gap-5 rounded-2xl border p-6 ${
        plan.comingSoon
          ? 'border-line-soft opacity-55'
          : highlighted
            ? 'border-accent/45 bg-accent/[0.05]'
            : 'border-line'
      }`}
    >
      {(current || plan.comingSoon) && (
        <span
          className="absolute -top-2.5 left-6 rounded-full px-2.5 py-0.5 text-[10px] font-bold tracking-wider"
          style={{ background: plan.badgeBg, color: plan.badgeColor }}
        >
          {current ? t('plans.current') : t('plans.comingSoon')}
        </span>
      )}

      <div className="flex items-center gap-2">
        <PlanIcon plan={plan.id} color={plan.color} />
        <span className="text-[15px] font-semibold" style={{ color: plan.price ? plan.color : undefined }}>
          {plan.name}
        </span>
      </div>

      <div className="flex items-end gap-1">
        <span className="text-[26px] font-bold leading-none tracking-tight">{plan.price ? `${plan.price}€` : '0€'}</span>
        {plan.price && <span className="mb-0.5 text-[12px] text-txt-muted">{t('plans.perMonth')}</span>}
      </div>

      {/* Les quotas, à l'endroit où on se pose la question : dans la colonne
          de l'offre, pas dans un tableau à part. */}
      <p className="rounded-lg border border-line-soft px-3 py-2 text-[11px] leading-relaxed text-txt-muted">
        {plan.id === 'free'
          ? t('plans.quotaFree')
          : t('plans.quotaLine', {
              instances: plan.id === 'ultimate' ? '10' : t('plans.premiumSyncQuota'),
              saves: plan.id === 'ultimate' ? '10' : '3',
            })}
      </p>

      <motion.ul
        variants={listVariants}
        initial="initial"
        whileInView="animate"
        viewport={{ once: false }}
        className="flex flex-1 flex-col gap-2.5"
      >
        {plan.features.map((feat) => (
          <motion.li
            key={feat.label}
            variants={listItemVariants}
            className={`flex items-start gap-2.5 text-[12.5px] leading-snug ${feat.ok ? 'text-txt-secondary' : 'text-txt-muted'}`}
          >
            {feat.ok ? <Check /> : <span className="mt-[3px] text-[11px]">—</span>}
            <span className={feat.ok ? '' : 'line-through'}>{feat.label}</span>
          </motion.li>
        ))}
      </motion.ul>

      <button
        onClick={buyable ? onChoose : undefined}
        disabled={!buyable}
        className={`h-10 w-full rounded-xl border text-[13px] font-semibold transition-colors duration-150 disabled:cursor-default ${
          buyable
            ? highlighted
              ? 'border-transparent bg-accent text-txt-primary hover:bg-accent-hover'
              : 'border-line text-txt-secondary hover:border-line-strong hover:text-txt-primary'
            : 'border-line-soft text-txt-muted'
        }`}
      >
        {current
          ? t('plans.currentPlan')
          : plan.comingSoon
            ? t('plans.comingSoon')
            : plan.price
              ? t('plans.upgradeTo', { name: plan.name })
              : t('plans.freePlan')}
      </button>
    </motion.div>
  )
}

/**
 * Question de la FAQ. Un `<details>` natif ne sait pas animer sa hauteur :
 * on gère l'ouverture nous-mêmes pour que la réponse se déplie franchement.
 */
function FaqItem({ question, answer }: { question: string; answer: string }) {
  const [open, setOpen] = useState(false)

  return (
    <div className="border-b border-line-soft">
      <motion.button
        onClick={() => setOpen((o) => !o)}
        whileHover={{ x: 2 }}
        transition={transition}
        className="flex w-full items-center justify-between gap-4 py-4 text-left text-[14px] font-medium text-txt-primary"
      >
        {question}
        <motion.span
          animate={{ rotate: open ? 135 : 0, color: open ? 'rgb(var(--accent-hover))' : 'rgb(var(--txt-muted))' }}
          transition={transition}
          className="text-[18px] font-normal leading-none"
        >
          +
        </motion.span>
      </motion.button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={transition}
            className="overflow-hidden"
          >
            <p className="pb-4 text-[13px] leading-relaxed text-txt-secondary">{answer}</p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

/** Coche qui se trace à l'arrivée à l'écran, plutôt que d'apparaître d'un bloc. */
function Check() {
  return (
    <svg viewBox="0 0 16 16" fill="none" className="mt-[3px] h-3.5 w-3.5 shrink-0 text-accent-hover">
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

/** Arguments de vente — tracés d'icônes en ligne, pas de dépendance en plus. */
const ARGUMENTS = [
  { key: 'sync', path: 'M7 18a5 5 0 01-.5-9.97A6 6 0 0118 8.5 4.5 4.5 0 0117.5 18H7z' },
  { key: 'restore', path: 'M3 12a9 9 0 109-9 9 9 0 00-7 3.3M3 4v4h4' },
  { key: 'badge', path: 'M12 3l2.6 5.5 6 .8-4.4 4.2 1.1 6-5.3-2.9-5.3 2.9 1.1-6L3.4 9.3l6-.8L12 3z' },
] as const
