import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { fastTransition } from '@/lib/motion'
import { api, type CatalogServer, type SavedServer } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { useSupportWatch } from '@/stores/useSupportWatch'
import { useCrashWatch } from '@/stores/useCrashWatch'
import { skinPreview } from '@/lib/skinCache'
import { homeGreeting } from '@/lib/greeting'
import { acceptsVersion, homeSuggestions } from '@/lib/homeSuggestions'
import { WelcomeSequence } from '@/components/home/WelcomeSequence'
import { HomeBanner, Confetti, useHomeBanner } from '@/components/home/HomeBanner'
import { HomeShortcuts } from '@/components/home/ChallengeShortcuts'
import { HalloweenGround, HalloweenSky } from '@/components/home/Halloween'
import { Skyline } from '@/components/home/Skyline'
import { AgentModal, WarningIcon } from '@/components/home/AgentModal'
import { ServerConfirmModal } from '@/components/servers/ServerConfirmModal'
import { JoinServerModal, type JoinRequest } from '@/components/servers/JoinServerModal'
import { useT } from '@/i18n'
import { NAV_ICONS } from './MobileNav'
import { InstanceSheet, loaderName } from './home/InstanceSheet'
import { MobileProCard, MobileServerCard } from './home/HomeCards'
import { useMobileLaunch } from './home/useMobileLaunch'

/** Mêmes étoiles que la bannière du PC (pages/Home.tsx). */
const STARS = Array.from({ length: 40 }, (_, i) => ({
  x: (i * 37 + ((i * 7 + 13) % 100) * 1.7) % 100,
  y: (i * 23 + ((i * 7 + 13) % 100) * 2.3) % 62,
  r: i % 4 === 0 ? 2 : 1,
  o: 0.15 + (i % 5) * 0.08,
}))

/**
 * L'accueil sur téléphone : celui du PC, en réduit, aux normes d'une appli
 * mobile (iOS et Material).
 *
 *   1. la bannière (titre, information, annonce, personnage, pseudo) ;
 *   2. le bloc de lancement (instance, client intégré, Jouer), qui défile
 *      puis reste collé en haut de l'écran une fois atteint, et sous lui les
 *      raccourcis Missions / Amis / Passe / Saison ;
 *   3. Outils, Support et Compétitif, absents de la barre du bas — le
 *      premier écran s'arrête juste sous eux ;
 *   4. un serveur (ou les fonctionnalités, selon le même réglage que sur
 *      PC), puis l'offre Pro en dernier ;
 *   5. les mentions légales, en attendant l'écran Réglages du téléphone ;
 *   6. la barre d'onglets.
 *
 * Règles tenues partout : texte principal à 15 px et plus, secondaire à 13 px
 * minimum et jamais sous 55 % d'opacité, cibles de 44 px au moins, un seul
 * rythme d'espacement (12 px dans une section, 24 px entre deux), un retour
 * à l'appui sur tout ce qui se touche, rien qui ne s'anime en boucle.
 */
export default function MobileHome() {
  const t = useT()
  const navigate = useNavigate()
  const username = useStore((s) => s.username)
  const uuid = useStore((s) => s.uuid)
  const language = useStore((s) => s.language)
  const instances = useStore((s) => s.instances)
  const setInstances = useStore((s) => s.setInstances)
  const selectedId = useStore((s) => s.selectedInstanceId)
  const setSelectedId = useStore((s) => s.setSelectedInstanceId)
  const runningInstances = useStore((s) => s.runningInstances)
  const confirmServerLaunch = useStore((s) => s.confirmServerLaunch)
  const showHomeServers = useStore((s) => s.showHomeServers)
  const favoriteServers = useStore((s) => s.favoriteServers)
  const isAgentEnabled = useStore((s) => s.isAgentEnabled)
  const setAgentEnabled = useStore((s) => s.setAgentEnabled)
  const supportUnread = useSupportWatch((s) => s.unread)
  const crashUpdates = useCrashWatch((s) => s.updates)
  const instance = instances.find((i) => i.id === selectedId) ?? null

  const { steps, cancelling, launch, cancel } = useMobileLaunch()
  const [sheetOpen, setSheetOpen] = useState(false)
  const [agentOpen, setAgentOpen] = useState(false)
  const [agentBlocked, setAgentBlocked] = useState(false)
  const [skin, setSkin] = useState<string | null>(null)
  const [skinKnown, setSkinKnown] = useState(false)
  const [savedServers, setSavedServers] = useState<SavedServer[]>([])
  const [suggestions, setSuggestions] = useState<CatalogServer[]>([])
  const [pendingServer, setPendingServer] = useState<SavedServer | null>(null)
  const [joinRequest, setJoinRequest] = useState<JoinRequest | null>(null)

  const welcome = useMemo(
    () => (username ? homeGreeting(language, username) : { phrase: '', playIntro: false }),
    [language, username],
  )
  const banner = useHomeBanner()
  const festive = banner?.theme === 'festive'
  const halloween = banner?.theme === 'halloween'

  // ── Le bloc de lancement collé en haut ──
  // Un repère vide juste au-dessus du bloc : quand il sort par le haut de la
  // zone qui défile, le bloc est collé et prend un fond plein. Pas de hauteur
  // forcée sous le bloc : si la page est trop courte pour qu'il atteigne le
  // haut, il ne se colle pas, et c'est très bien — mieux qu'un écran de vide.
  const scrollRef = useRef<HTMLElement>(null)
  const markerRef = useRef<HTMLDivElement>(null)
  const [stuck, setStuck] = useState(false)
  useEffect(() => {
    const marker = markerRef.current
    if (!marker) return
    const observer = new IntersectionObserver(
      ([entry]) => setStuck(entry.intersectionRatio < 1 && entry.boundingClientRect.top < (entry.rootBounds?.top ?? 0)),
      { root: scrollRef.current, threshold: [0, 1] },
    )
    observer.observe(marker)
    return () => observer.disconnect()
  }, [])

  // ── Un premier écran qui tombe juste ──
  // La bannière prend toute la hauteur que laissent le lancement, les
  // raccourcis et les tuiles : le premier écran s'arrête net 24 px sous les
  // tuiles, quelle que soit la taille du téléphone, au lieu de couper une
  // carte en deux. Bornée : jamais sous 150 px (en dessous le personnage ne
  // tient plus ; un iPhone SE tombe juste au-dessus), jamais au-delà de 420 (grands écrans, le personnage n'a pas à
  // devenir géant). Mesures par `offsetTop`/`offsetHeight`, qui ignorent la
  // mise à l'échelle de l'entrée de la bannière ; ce qui suit la bannière ne
  // dépend pas de sa hauteur, donc pas de va-et-vient.
  const bannerRef = useRef<HTMLDivElement>(null)
  const tilesRef = useRef<HTMLDivElement>(null)
  const launchRef = useRef<HTMLDivElement>(null)
  const [bannerH, setBannerH] = useState<number | null>(null)
  useLayoutEffect(() => {
    const main = scrollRef.current
    const banner = bannerRef.current
    const tiles = tilesRef.current
    if (!main || !banner || !tiles) return
    const fit = () => {
      const bannerBottom = banner.offsetTop + banner.offsetHeight
      const rest = tiles.offsetTop + tiles.offsetHeight - bannerBottom
      const room = main.clientHeight - banner.offsetTop - rest - 24
      setBannerH(Math.round(Math.max(150, Math.min(420, room))))
    }
    fit()
    const ro = new ResizeObserver(fit)
    ro.observe(main)
    if (launchRef.current) ro.observe(launchRef.current)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    api.instances.list().then((list) => {
      setInstances(list)
      if (!useStore.getState().selectedInstanceId && list.length > 0) setSelectedId(list[0].id)
    }).catch(showError)
  }, [])

  useEffect(() => {
    setSkinKnown(false)
    if (!uuid) { setSkin(null); return }
    let alive = true
    skinPreview(uuid)
      .then((uri) => { if (alive) { setSkin(uri ?? null); setSkinKnown(true) } })
      .catch(() => { if (alive) { setSkin(null); setSkinKnown(true) } })
    return () => { alive = false }
  }, [uuid])

  // Le client intégré ne se charge pas partout : même pastille que sur PC.
  useEffect(() => {
    if (!instance) { setAgentBlocked(false); return }
    let alive = true
    api.launch.agentStatus(instance.mc_version, instance.loader)
      .then((s) => alive && setAgentBlocked(!s.available))
      .catch(() => alive && setAgentBlocked(false))
    return () => { alive = false }
  }, [instance?.mc_version, instance?.loader])

  useEffect(() => {
    if (!showHomeServers || !selectedId) { setSavedServers([]); return }
    let alive = true
    api.launch.listSavedServers(selectedId)
      .then((list) => alive && setSavedServers(list))
      .catch(() => alive && setSavedServers([]))
    return () => { alive = false }
  }, [showHomeServers, selectedId])

  const mcVersion = instance?.mc_version ?? null
  useEffect(() => {
    if (!showHomeServers) { setSuggestions([]); return }
    let alive = true
    void homeSuggestions(mcVersion).then((list) => alive && setSuggestions(list))
    return () => { alive = false }
  }, [showHomeServers, mcVersion])

  const running = !!selectedId && runningInstances.includes(selectedId)
  const step = selectedId ? steps[selectedId] ?? null : null

  const play = (server?: string) => { if (selectedId) void launch(selectedId, server) }
  const joinServer = (server: SavedServer) => {
    if (running) return
    if (confirmServerLaunch) setPendingServer(server)
    else play(server.ip)
  }

  // Le serveur affiché : la même règle que les trois cartes du PC, réduite à
  // une — le premier enregistré (épinglé quand il y en a plus de trois),
  // sinon la première suggestion du catalogue.
  const favoriteIps = selectedId ? favoriteServers[selectedId] ?? [] : []
  const shownSaved = savedServers.length <= 3 ? savedServers : savedServers.filter((s) => favoriteIps.includes(s.ip))
  const savedIps = new Set(savedServers.map((s) => s.ip.toLowerCase()))
  const suggestion = suggestions.find((s) => !savedIps.has(s.address.toLowerCase())) ?? null

  const openSuggestion = (server: CatalogServer) => {
    if (acceptsVersion(server, mcVersion)) { joinServer({ name: server.name, ip: server.address }); return }
    setJoinRequest({
      ip: server.address,
      mcVersion: server.recommended_version ?? server.game_versions[0],
      loader: 'vanilla',
      versions: server.game_versions,
      anyLoader: true,
      remember: server.name,
    })
  }

  return (
    <div className="flex h-full flex-col bg-bg-primary">
      {/* Pas de marge haute sur la zone qui défile : elle empêcherait le bloc
          de lancement de monter jusqu'au bord. Elle est portée par la bannière. */}
      <main ref={scrollRef} className="relative flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain px-4">

        {/* ── 1. La bannière ── */}
        {/* Pas d'entrée propre : la transition de page s'en charge déjà, et
            entre onglets l'accueil doit être là d'un coup. */}
        <div
          ref={bannerRef}
          style={bannerH ? { height: bannerH } : undefined}
          className={`relative mt-[max(12px,env(safe-area-inset-top))] ${bannerH ? '' : 'h-[clamp(240px,34dvh,320px)]'} shrink-0 overflow-hidden rounded-3xl border border-white/[0.06] shadow-[0_8px_32px_rgba(0,0,0,0.6)]`}
        >
          <div className="absolute inset-0 bg-[linear-gradient(180deg,#020208_0%,#06041a_18%,#0e0932_40%,#1c1250_58%,#130d35_76%,#070512_100%)]" />
          <motion.div
            initial={false}
            animate={{ opacity: festive ? 1 : 0 }}
            transition={{ duration: 0.6 }}
            className="absolute inset-0 bg-[linear-gradient(180deg,#160a14_0%,#3a1230_38%,#7a2340_72%,#c2543c_100%)]"
          />
          {festive && <Confetti />}
          <motion.div
            initial={false}
            animate={{ opacity: halloween ? 1 : 0 }}
            transition={{ duration: 0.6 }}
            className="absolute inset-0 bg-[linear-gradient(180deg,#05020a_0%,#140822_30%,#2a0f2c_55%,#4a1620_78%,#7a2a10_100%)]"
          />
          {halloween && <HalloweenSky />}
          {/* La lueur, fixe : en boucle elle dépensait de la batterie pour de la décoration. */}
          <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_50%_60%,rgba(90,70,255,0.45)_0%,rgba(75,63,207,0.2)_40%,transparent_70%)]" />
          <div className="absolute inset-0">
            {STARS.map((s, i) => (
              <div key={i} className="absolute rounded-full" style={{ left: `${s.x}%`, top: `${s.y}%`, width: s.r, height: s.r, background: `rgba(255,255,255,${s.o})` }} />
            ))}
          </div>
          <Skyline />

          {/* Titre et information, en haut à gauche comme sur PC. */}
          <div className="absolute left-4 right-2 top-2 z-10 flex items-center justify-between">
            <h1 className="select-none text-[28px] font-black leading-none tracking-[-0.015em] text-white [text-shadow:0_0_24px_rgba(75,63,207,0.6)]">
              YuyuFrame
            </h1>
            <button
              onClick={() => navigate('/information')}
              aria-label={t('information.title')}
              className="press flex h-11 w-11 items-center justify-center rounded-full text-white/60 active:bg-white/[0.1]"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-[22px] w-[22px]"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" /></svg>
            </button>
          </div>

          {/* L'annonce de la flotte (Halloween…), sous le titre. */}
          <div className="absolute left-4 top-[54px] z-10 max-w-[calc(100%-32px)]">
            <HomeBanner banner={banner} visible />
          </div>

          <WelcomeSequence
            username={username}
            uuid={username && skinKnown ? uuid : null}
            localSkin={skin}
            greeting={username && welcome.phrase ? welcome.phrase : t('home.welcomeNew')}
            playIntro={welcome.playIntro}
            onAvatarClick={() => navigate('/login')}
          />
          {halloween && <HalloweenGround />}
        </div>

        {/* ── 2. Le bloc de lancement, juste sous la bannière : défile, puis
            reste collé en haut ── */}
        {/* Le repère est remonté de la hauteur de l'encoche, comme le bloc :
            il reste aligné sur son bord haut. */}
        <div ref={markerRef} aria-hidden className="relative top-[calc(env(safe-area-inset-top,0px)*-1)] mt-1 h-px shrink-0" />
        {/* Collé, le bloc passerait sous l'encoche : il porte une bande de la
            hauteur de l'encoche au-dessus de son contenu (padding), retirée de
            sa place dans la page (marge négative). Cette bande laisse passer
            les touchers vers ce qui est dessous. Fond plein et non flouté :
            un flou recalculé à chaque image saccade sur un petit téléphone. */}
        <div
          className={`pointer-events-none sticky top-0 z-30 -mx-4 -mt-[env(safe-area-inset-top,0px)] shrink-0 border-b pt-[env(safe-area-inset-top,0px)] transition-[background-color,border-color,box-shadow] duration-150 ${
            stuck
              ? 'border-white/[0.06] bg-[#09090D] shadow-[0_8px_24px_rgba(0,0,0,0.5)]'
              : 'border-transparent bg-transparent'
          }`}
        >
          <div ref={launchRef} className="pointer-events-auto px-4 py-3">
            <SectionHeader
              title={t('home.instance')}
              action={{ label: t('home.manage'), onClick: () => navigate('/instances') }}
            />

            {instances.length === 0 ? (
              <button
                onClick={() => navigate('/instances')}
                className="press flex h-14 w-full items-center justify-center rounded-2xl border-2 border-dashed border-white/[0.12] text-[15px] font-medium text-white/60"
              >
                {t('home.createInstance')}
              </button>
            ) : (
              <div className="flex gap-2">
                <button
                  onClick={() => setSheetOpen(true)}
                  aria-haspopup="dialog"
                  className="press relative flex h-14 min-w-0 flex-1 items-center gap-3 rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 text-left"
                >
                  {/* Changer d'instance : le nouveau nom arrive en fondu, de
                      4 px plus bas — on voit que le choix a été pris en
                      compte. Rien à l'affichage de l'accueil (`initial`).
                      `popLayout` : l'ancien nom sort du flux sans pousser. */}
                  <AnimatePresence initial={false} mode="popLayout">
                    <motion.span
                      key={instance?.id ?? 'none'}
                      initial={{ opacity: 0, transform: 'translateY(4px)' }}
                      animate={{ opacity: 1, transform: 'translateY(0px)' }}
                      exit={{ opacity: 0, transition: { duration: 0.1 } }}
                      transition={fastTransition}
                      className="flex min-w-0 flex-1 flex-col"
                    >
                      <span className="truncate text-[15px] font-semibold leading-tight text-white">
                        {instance ? instance.name : t('home.chooseInstance')}
                      </span>
                      {instance && (
                        <span className="truncate text-[13px] leading-tight text-white/55">
                          {instance.mc_version} · {loaderName(instance.loader)}
                        </span>
                      )}
                    </motion.span>
                  </AnimatePresence>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-white/50">
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                </button>
                {/* Le client intégré — absent pour Horizon, comme sur PC. */}
                {instance?.loader !== 'horizon' && (
                  <button
                    onClick={() => setAgentOpen(true)}
                    disabled={!instance}
                    aria-label={t('agent.title')}
                    className="press relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl border border-white/[0.1] bg-white/[0.05] text-white/70 disabled:opacity-40"
                  >
                    <svg viewBox="0 0 24 24" fill="currentColor" className="h-6 w-6">{NAV_ICONS.settings}</svg>
                    {agentBlocked && (
                      <span className="absolute -right-1 -top-1 flex h-[18px] w-[18px] items-center justify-center rounded-full bg-bg-primary">
                        <WarningIcon className="h-full w-full text-warning" />
                      </span>
                    )}
                  </button>
                )}
              </div>
            )}

            {/* « Jouer » ↔ lancement en cours : le nouvel état arrive en fondu
                depuis 98 %, au lieu de remplacer l'autre d'un coup. Rien à
                l'affichage de l'accueil (`initial`). */}
            {/* `popLayout` : l'ancien état sort du flux le temps de partir,
                sans quoi les deux s'empileraient une image. */}
            <div className="relative mt-3">
              <AnimatePresence initial={false} mode="popLayout">
                <motion.div
                  key={running || step ? 'busy' : 'idle'}
                  initial={{ opacity: 0, transform: 'scale(0.98)' }}
                  animate={{ opacity: 1, transform: 'scale(1)' }}
                  exit={{ opacity: 0, transition: { duration: 0.1 } }}
                  transition={fastTransition}
                >
                  <LaunchButton
                    hasAccount={!!username}
                    hasInstance={!!instance}
                    running={running}
                    step={step}
                    cancelling={!!(selectedId && cancelling[selectedId])}
                    halloween={halloween}
                    onLaunch={() => (username ? play() : navigate('/login'))}
                    onCancel={() => selectedId && void cancel(selectedId)}
                  />
                </motion.div>
              </AnimatePresence>
            </div>
          </div>
        </div>

        {/* Les raccourcis Missions / Amis / Passe / Saison, sous le lancement. */}
        <div className="mt-3">
          <HomeShortcuts width="w-full" tall />
        </div>

        {/* ── 3. Les entrées de la barre du PC absentes de celle du
            téléphone. Le premier écran s'arrête juste sous elles (la
            bannière prend la hauteur qui reste, voir `bannerH`). ── */}
        <div ref={tilesRef} className="mt-3 grid grid-cols-3 gap-3">
          <PageTile label={t('home.nav.features')} icon={NAV_ICONS.features} onClick={() => navigate('/features')} />
          <PageTile label={t('home.nav.support')} icon={NAV_ICONS.support} badge={supportUnread + crashUpdates} onClick={() => navigate('/support')} />
          <PageTile label={t('home.nav.competitive')} icon={NAV_ICONS.competitive} onClick={() => navigate('/competitive')} />
        </div>

        {/* ── 4. Un serveur (ou les fonctionnalités, selon le réglage
            « serveurs sur l'accueil »), puis l'offre Pro ── */}
        <section className="mt-6">
          {showHomeServers ? (
            <>
              <SectionHeader
                title={t('home.nav.server')}
                action={{ label: t('home.allServers'), onClick: () => navigate('/servers') }}
              />
              {shownSaved[0] ? (
                <MobileServerCard server={shownSaved[0]} onClick={() => joinServer(shownSaved[0])} />
              ) : suggestion ? (
                <MobileServerCard
                  server={{ name: suggestion.name, ip: suggestion.address }}
                  badge={t('home.suggestedServer')}
                  onClick={() => openSuggestion(suggestion)}
                />
              ) : (
                <button
                  onClick={() => navigate(savedServers.length === 0 ? '/servers?tab=catalog' : '/servers')}
                  className="press flex min-h-[76px] w-full items-center justify-center rounded-2xl border-2 border-dashed border-white/[0.12] px-4 text-center text-[14px] text-white/60"
                >
                  {savedServers.length === 0 ? t('home.noServerRegistered') : t('home.noServerPinned')}
                </button>
              )}
            </>
          ) : (
            <FeatureList />
          )}
        </section>

        {/* L'offre, en dernier. */}
        <div className="mt-6">
          <MobileProCard onOpen={() => navigate('/plans')} />
        </div>

        {/* ── 5. Mentions légales — iront dans Réglages › À propos quand
            l'écran Réglages du téléphone existera. ── */}
        <footer className="mt-8 flex shrink-0 flex-col items-center gap-1 pb-6">
          <div className="flex items-center">
            {([
              ['licence', t('home.legal.licence')],
              ['confidentialite', t('home.legal.confidentialite')],
              ['conditions', t('home.legal.conditions')],
            ] as const).map(([tab, label]) => (
              <button key={tab} onClick={() => navigate(`/legal?tab=${tab}`)} className="h-11 px-2.5 text-[13px] font-medium text-white/55 active:text-white/80">
                {label}
              </button>
            ))}
          </div>
          <span className="text-[12px] text-white/40">{t('home.copyright')}</span>
        </footer>
      </main>

      {/* ── 6. La barre d'onglets : posée par la coquille (`App.tsx`). ── */}

      <InstanceSheet
        open={sheetOpen}
        instances={instances}
        selectedId={selectedId}
        onSelect={setSelectedId}
        onManage={() => navigate('/instances')}
        onClose={() => setSheetOpen(false)}
      />
      {agentOpen && instance && (
        <AgentModal
          mcVersion={instance.mc_version}
          loader={instance.loader}
          enabled={isAgentEnabled(instance.id)}
          onChange={(value) => setAgentEnabled(instance.id, value)}
          onClose={() => setAgentOpen(false)}
        />
      )}
      {joinRequest && <JoinServerModal request={joinRequest} onClose={() => setJoinRequest(null)} />}
      {pendingServer && (
        <ServerConfirmModal
          server={pendingServer}
          onConfirm={() => { play(pendingServer.ip); setPendingServer(null) }}
          onClose={() => setPendingServer(null)}
        />
      )}
    </div>
  )
}

// ── En-tête de section ─────────────────────────────────────────────────────

/** Titre de section à gauche, lien « … › » à droite (cible de 44 px). */
function SectionHeader({ title, action }: { title: string; action?: { label: string; onClick: () => void } }) {
  return (
    <div className="mb-1 flex h-11 items-center justify-between">
      <h2 className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{title}</h2>
      {action && (
        <button
          onClick={action.onClick}
          className="-mr-2 flex h-11 items-center gap-0.5 px-2 text-[14px] font-semibold text-[#a29bfe] transition-opacity active:opacity-60"
        >
          {action.label}
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
            <path d="M9 6l6 6-6 6" />
          </svg>
        </button>
      )}
    </div>
  )
}

// ── Le bouton de lancement ─────────────────────────────────────────────────

/** Le bouton d'action principal : un verbe court (« Jouer »), le nom de
 *  l'instance étant juste au-dessus. Mêmes états que sur PC : se connecter,
 *  aucune instance, lancement en cours avec Annuler à côté. */
function LaunchButton({ hasAccount, hasInstance, running, step, cancelling, halloween, onLaunch, onCancel }: {
  hasAccount: boolean
  hasInstance: boolean
  running: boolean
  step: { percent: number; message: string } | null
  cancelling: boolean
  halloween: boolean
  onLaunch: () => void
  onCancel: () => void
}) {
  const t = useT()
  const canLaunch = hasAccount && hasInstance && !running
  const bg = canLaunch && halloween
    ? 'bg-[linear-gradient(135deg,#ff8a1f_0%,#d9480f_50%,#6d1d5c_100%)]'
    : canLaunch || !hasAccount
      ? 'bg-[#4B3FCF]'
      : 'bg-white/[0.08]'
  const shadow = canLaunch
    ? halloween ? 'shadow-[0_6px_24px_rgba(255,110,20,0.35)]' : 'shadow-[0_6px_24px_rgba(75,63,207,0.4)]'
    : ''

  if (running || step) {
    return (
      <div className="flex gap-2">
        <div className="relative flex h-14 min-w-0 flex-1 items-center justify-center gap-2.5 overflow-hidden rounded-2xl bg-[#4B3FCF]/70 px-4 text-[15px] font-semibold text-white">
          {step && (
            <span
              // Pleine largeur, réduite par `scaleX` depuis la gauche : animer
              // `width` recalculerait la mise en page à chaque image, pendant
              // que le lancement occupe déjà le processeur. Linéaire : c'est
              // une progression continue.
              className="absolute inset-0 origin-left bg-white/[0.18] transition-transform duration-200 ease-linear"
              style={{ transform: `scaleX(${step.percent / 100})` }}
            />
          )}
          <span className="relative h-[18px] w-[18px] shrink-0 animate-spin rounded-full border-2 border-white/25 border-t-white" />
          <span className="relative truncate">
            {step && step.percent < 60 ? `${step.message} ${step.percent}%` : t('home.launching')}
          </span>
        </div>
        <button
          onClick={onCancel}
          disabled={cancelling}
          className="press h-14 shrink-0 rounded-2xl border border-[rgba(248,113,113,0.35)] bg-[rgba(200,50,50,0.14)] px-4 text-[15px] font-semibold text-[#fca5a5] disabled:border-white/[0.08] disabled:bg-transparent disabled:text-white/40"
        >
          {cancelling ? t('home.cancelling') : t('home.cancel')}
        </button>
      </div>
    )
  }

  return (
    <button
      onClick={onLaunch}
      disabled={hasAccount && !hasInstance}
      className={`press flex h-14 w-full items-center justify-center gap-2 rounded-2xl px-4 text-[17px] font-bold text-white disabled:text-white/50 ${bg} ${shadow}`}
    >
      {canLaunch && (
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5"><path d="M8 5.14v13.72a1 1 0 001.52.85l10.6-6.86a1 1 0 000-1.7L9.52 4.29A1 1 0 008 5.14z" /></svg>
      )}
      <span className="truncate">
        {!hasAccount ? t('home.connect') : !hasInstance ? t('home.noInstance') : t('home.mobile.play')}
      </span>
    </button>
  )
}

// ── Les liens de la page ───────────────────────────────────────────────────

/** Une entrée de la barre du PC posée dans la page : icône au-dessus du
 *  libellé, comme une tuile d'appli — trois de front tiennent sans tronquer. */
function PageTile({ label, icon, badge = 0, onClick }: {
  label: string
  icon: ReactNode
  badge?: number
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className="press flex h-[76px] min-w-0 flex-col items-center justify-center gap-2 rounded-2xl border border-white/[0.08] bg-white/[0.04] px-2 text-white/90"
    >
      <span className="relative flex h-6 w-6 shrink-0">
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-full w-full">{icon}</svg>
        {badge > 0 && (
          <span className="absolute -right-2.5 -top-1.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-danger px-1 text-[11px] font-bold leading-none tabular-nums text-white shadow-[0_0_0_2px_#09090D]">
            {badge > 9 ? '9+' : badge}
          </span>
        )}
      </span>
      <span className="max-w-full truncate text-[13px] font-semibold">{label}</span>
    </button>
  )
}

// ── Les fonctionnalités, quand les serveurs ne sont pas sur l'accueil ──────

/** Les trois cartes du PC, en liste de lignes pleine largeur. */
function FeatureList() {
  const t = useT()
  const navigate = useNavigate()
  const rows = [
    { title: t('home.features.syncTitle'), soon: true, path: null },
    { title: t('home.features.statsTitle'), soon: false, path: '/stats' },
    { title: t('home.features.proTitle'), soon: false, path: null },
  ]
  return (
    <div className="overflow-hidden rounded-2xl border border-white/[0.08] bg-white/[0.04]">
      {rows.map((r, i) => (
        <button
          key={r.title}
          disabled={!r.path}
          onClick={() => r.path && navigate(r.path)}
          className={`flex h-[52px] w-full items-center gap-2 px-4 text-left transition-colors enabled:active:bg-white/[0.06] ${i > 0 ? 'border-t border-white/[0.06]' : ''}`}
        >
          <span className="min-w-0 flex-1 truncate text-[15px] font-semibold text-white/90">{r.title}</span>
          {r.soon ? (
            <span className="shrink-0 rounded-full bg-white/[0.08] px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-white/55">
              {t('features.soonBadge')}
            </span>
          ) : r.path ? (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-white/40">
              <path d="M9 6l6 6-6 6" />
            </svg>
          ) : null}
        </button>
      ))}
    </div>
  )
}
