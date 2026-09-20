import { useEffect, useRef, useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { AnimatePresence, motion } from 'framer-motion'
import type { Variants } from 'framer-motion'
import { EASE_OUT } from '@/lib/motion'
import { P2P_ENABLED } from '@/config/features'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { loaderColor } from '@/lib/loader'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { showError, showNotice } from '@/stores/useErrorToast'
import { InstanceSwitchModal } from '@/components/instances/InstanceSwitchModal'
import { WelcomeSequence } from '@/components/home/WelcomeSequence'
import { homeGreeting } from '@/lib/greeting'
import { HomeBanner, Confetti, useHomeBanner } from '@/components/home/HomeBanner'
import { ServerCard } from '@/components/servers/ServerCard'
import { ServerManageModal } from '@/components/servers/ServerManageModal'
import { ServerConfirmModal } from '@/components/servers/ServerConfirmModal'
import { useT } from '@/i18n'
import type { SavedServer } from '@/api/client'

// ── Animations d'entrée de l'accueil ────────────────────────────────────────
// Le panneau de droite glisse depuis le bord, puis ses éléments se posent
// l'un après l'autre. Les trois cartes du pied de page font de même.

const panelVariants: Variants = {
  initial: { opacity: 0, x: 24 },
  animate: {
    opacity: 1,
    x: 0,
    transition: { duration: 0.4, ease: EASE_OUT, staggerChildren: 0.07, delayChildren: 0.08 },
  },
}

const panelItem: Variants = {
  initial: { opacity: 0, y: 10 },
  animate: { opacity: 1, y: 0, transition: { duration: 0.32, ease: EASE_OUT } },
}

const cardsVariants: Variants = {
  initial: {},
  animate: { transition: { staggerChildren: 0.06, delayChildren: 0.2 } },
}

const cardItem: Variants = {
  initial: { opacity: 0, y: 10 },
  animate: { opacity: 1, y: 0, transition: { duration: 0.34, ease: EASE_OUT } },
}

interface LaunchProgress {
  current: number
  total: number
  message: string
}

/// Plusieurs instances peuvent se lancer en parallèle : chaque événement de
/// lancement porte l'id de son instance, et l'accueil n'affiche que l'état de
/// celle sélectionnée.
interface DownloadProgress extends LaunchProgress {
  instance_id: string
}

/// Ajoute (`value` non nul) ou retire l'entrée d'une instance.
function withEntry<T>(map: Record<string, T>, id: string, value: T | null): Record<string, T> {
  const next = { ...map }
  if (value === null) delete next[id]
  else next[id] = value
  return next
}

function useFeatures(t: ReturnType<typeof useT>) {
  return [
    {
      title: t('home.features.syncTitle'),
      desc: t('home.features.syncDesc'),
      icon: <svg viewBox="0 0 24 24" fill="currentColor" width="1em" height="1em"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>,
      path: '/sync',
    },
    {
      title: t('home.features.statsTitle'),
      desc: t('home.features.statsDesc'),
      icon: <svg viewBox="0 0 24 24" fill="currentColor" width="1em" height="1em"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z" /></svg>,
      path: '/stats',
    },
    {
      title: t('home.features.proTitle'),
      desc: t('home.features.proDesc'),
      icon: <svg viewBox="0 0 24 24" fill="currentColor" width="1em" height="1em"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 4l5 2.18V11c0 3.5-2.33 6.79-5 7.93-2.67-1.14-5-4.43-5-7.93V7.18L12 5z" /></svg>,
      path: null,
    },
  ] as const
}

const STARS = Array.from({ length: 55 }, (_, i) => ({
  x: (i * 37 + ((i * 7 + 13) % 100) * 1.7) % 100,
  y: (i * 23 + ((i * 7 + 13) % 100) * 2.3) % 62,
  r: i % 4 === 0 ? 2 : 1,
  o: 0.15 + (i % 5) * 0.08,
}))

export default function Home() {
  const navigate = useNavigate()
  const location = useLocation()
  const t = useT()
  const FEATURES = useFeatures(t)
  const {
    username, uuid, isOffline,
    clearUser,
    instances, setInstances,
    selectedInstanceId, setSelectedInstanceId, selectedInstance,
    isInstanceRunning, setInstanceRunning,
    setLastSession,
    closeOnLaunch,
    p2pEnabled, setP2pEnabled,
    avoidBetaDependencies,
    showConsole,
    recordLaunchPhaseDuration,
    showHomeServers,
    confirmServerLaunch,
    favoriteServers, toggleFavoriteServer,
  } = useStore()

  const gameRunning = !!selectedInstanceId && isInstanceRunning(selectedInstanceId)

  const [progressByInstance, setProgressByInstance] = useState<Record<string, LaunchProgress>>({})
  const [cancellingByInstance, setCancellingByInstance] = useState<Record<string, true>>({})
  const progress = selectedInstanceId ? progressByInstance[selectedInstanceId] ?? null : null
  const cancelling = !!selectedInstanceId && !!cancellingByInstance[selectedInstanceId]
  const setProgress = (id: string, value: LaunchProgress | null) =>
    setProgressByInstance((prev) => withEntry(prev, id, value))
  const setCancelling = (id: string, value: boolean) =>
    setCancellingByInstance((prev) => withEntry(prev, id, value ? true : null))
  const [showInstanceSwitch, setShowInstanceSwitch] = useState(false)
  const [bannerPulse, setBannerPulse] = useState(false)
  const [savedServers, setSavedServers] = useState<SavedServer[]>([])
  const [showServerManage, setShowServerManage] = useState(false)
  const [pendingServer, setPendingServer] = useState<SavedServer | null>(null)
  const [customFaceUri, setCustomFaceUri] = useState<string | null>(null)

  const instance = selectedInstance()

  // Phrase d'accueil : tirée au sort une fois par lancement du launcher, pas
  // à chaque passage sur l'accueil (voir `lib/greeting.ts`). `playIntro` dit
  // s'il reste la séquence d'arrivée à jouer.
  const language = useStore((st) => st.language)
  const [welcome, setWelcome] = useState({ phrase: '', playIntro: false })
  useEffect(() => {
    if (username) setWelcome(homeGreeting(language, username))
  }, [username, language])

  // Bannière publiée depuis le back-office : c'est elle qui décide si le
  // panneau passe en habillage festif.
  const banner = useHomeBanner()
  const festive = banner?.theme === 'festive'
  // La bannière attend la fin de la séquence — sauf quand il n'y a plus de
  // séquence à attendre, auquel cas elle est là tout de suite.
  const [bannerReady, setBannerReady] = useState(false)
  useEffect(() => {
    if (!welcome.playIntro) {
      setBannerReady(true)
      return
    }
    setBannerReady(false)
    const timer = setTimeout(() => setBannerReady(true), 3350)
    return () => clearTimeout(timer)
  }, [welcome])

  // Avatar d'un compte hors ligne : mc-heads.net n'a rien pour un UUID inventé
  // (voir Login.tsx pour le même souci sur l'aperçu 3D), donc les deux avatars
  // ci-dessous restaient sur le rendu de repli (initiale/icône) même après
  // avoir défini un skin custom. On recadre nous-mêmes la zone "visage" du PNG
  // 64×64 (8,8)-(16,16) + son calque "hat" (40,8)-(48,16) — même position dans
  // le template quel que soit le format (64×64 moderne ou 64×32 legacy, la
  // tête ne change jamais) — pour obtenir un carré affichable comme
  // `mc-heads.net/avatar` le ferait pour un vrai compte.
  useEffect(() => {
    if (!uuid || !isOffline) { setCustomFaceUri(null); return }
    let cancelled = false
    api.mc.getSkin(uuid).then((dataUri) => {
      if (cancelled || !dataUri) { if (!cancelled) setCustomFaceUri(null); return }
      const img = new Image()
      img.onload = () => {
        if (cancelled) return
        const canvas = document.createElement('canvas')
        canvas.width = 64
        canvas.height = 64
        const ctx = canvas.getContext('2d')
        if (!ctx) return
        ctx.imageSmoothingEnabled = false
        ctx.drawImage(img, 8, 8, 8, 8, 0, 0, 64, 64)
        ctx.drawImage(img, 40, 8, 8, 8, 0, 0, 64, 64)
        setCustomFaceUri(canvas.toDataURL('image/png'))
      }
      img.src = dataUri
    }).catch(() => { if (!cancelled) setCustomFaceUri(null) })
    return () => { cancelled = true }
  }, [uuid, isOffline])

  // Phase 2 ("lancement", 60-100%) — les téléchargements (backend) s'arrêtent
  // pile à 60%, le reste (démarrage JVM + chargement interne Minecraft
  // jusqu'au menu principal, voir game_ready) n'a pas de progression réelle
  // connue. On mesure la durée réelle à chaque lancement (voir game_ready
  // ci-dessous) et on la réutilise pour animer la barre les fois suivantes,
  // au lieu de la laisser figée à 60% pendant tout ce temps.
  // Un départ et un minuteur par instance en cours de lancement.
  const phase2StartRef = useRef<Record<string, number>>({})
  const phase2TimerRef = useRef<Record<string, ReturnType<typeof setInterval>>>({})

  const clearPhase2 = (instanceId: string) => {
    const timer = phase2TimerRef.current[instanceId]
    if (timer) clearInterval(timer)
    delete phase2TimerRef.current[instanceId]
    delete phase2StartRef.current[instanceId]
  }

  const startPhase2 = (instanceId: string) => {
    const start = Date.now()
    phase2StartRef.current[instanceId] = start
    // Lu dans le store au moment du démarrage : les listeners d'événements
    // ci-dessous sont posés une seule fois, leur closure serait périmée.
    const remembered = useStore.getState().launchPhaseDurations[instanceId]
    if (!remembered) return // pas encore de mesure — reste figé à 60%, rien à animer
    const previous = phase2TimerRef.current[instanceId]
    if (previous) clearInterval(previous)
    phase2TimerRef.current[instanceId] = setInterval(() => {
      const frac = Math.min(0.975, (Date.now() - start) / remembered)
      setProgress(instanceId, { current: 60 + Math.round(frac * 40), total: 100, message: t('home.startingMinecraft') })
    }, 250)
  }

  useEffect(() => () => {
    for (const timer of Object.values(phase2TimerRef.current)) clearInterval(timer)
  }, [])

  useEffect(() => {
    api.instances.list().then((list) => {
      setInstances(list)
      if (!selectedInstanceId && list.length > 0) {
        setSelectedInstanceId(list[0].id)
      }
    }).catch(showError)
  }, [])

  // Liste des serveurs enregistrés (servers.dat) de l'instance sélectionnée —
  // seulement si le réglage "afficher mes serveurs sur l'accueil" est actif,
  // pour ne pas faire cet appel IPC inutilement sinon (voir Settings.tsx).
  useEffect(() => {
    if (!showHomeServers || !selectedInstanceId) {
      setSavedServers([])
      return
    }
    let cancelled = false
    api.launch.listSavedServers(selectedInstanceId).then((list) => {
      if (!cancelled) setSavedServers(list)
    }).catch(() => { if (!cancelled) setSavedServers([]) })
    return () => { cancelled = true }
  }, [showHomeServers, selectedInstanceId])

  useTauriEvent<DownloadProgress>('download_progress', ({ instance_id, ...value }) => {
    setProgress(instance_id, value)
    // Palier exact émis par le backend une fois tous les téléchargements
    // finis (voir DOWNLOAD_PHASE_PERCENT côté Rust) — démarre la phase 2.
    if (value.current === 60 && value.total === 100 && !phase2StartRef.current[instance_id]) {
      startPhase2(instance_id)
    }
  })

  const resetLaunchUi = (instanceId: string) => {
    clearPhase2(instanceId)
    setProgress(instanceId, null)
    setCancelling(instanceId, false)
  }

  // La mise à jour du store (setInstanceRunning) et le show() de la fenêtre
  // sont gérés globalement dans App.tsx (survit à la navigation hors de cette
  // page) — ici on ne garde que les resets propres à l'UI de lancement de
  // cette page (barre de progression, bouton Annuler).
  useTauriEvent<{ running: boolean; instance_id: string }>('game_state', (payload) => {
    if (!payload.running) resetLaunchUi(payload.instance_id)
  })

  // Émis par le backend quand le hook TitleScreen.init() du LauncherAgent se
  // déclenche (voir TitleScreenMixin*.java) — Minecraft a fini son propre
  // chargement interne (splash + ressources) et affiche enfin le menu
  // principal. Avant ça, la barre restait figée à 60% pendant tout ce temps
  // mort (aucun signal entre la fin de nos téléchargements et le jeu
  // réellement visible). On mesure la durée réelle de cette phase pour
  // affiner l'animation des prochains lancements de cette instance.
  // Traité quelle que soit l'instance sélectionnée : ignorer celui d'une autre
  // instance laissait sa barre figée à ~97% pour toujours.
  useTauriEvent<{ instance_id: string }>('game_ready', ({ instance_id }) => {
    const start = phase2StartRef.current[instance_id]
    if (start) recordLaunchPhaseDuration(instance_id, Date.now() - start)
    clearPhase2(instance_id)
    setProgress(instance_id, { current: 100, total: 100, message: t('home.minecraftReady') })
    setTimeout(() => setProgress(instance_id, null), 900)
  })

  useTauriEvent<{ instance_id: string; message: string }>('launch_error', ({ instance_id, message }) => {
    showError(message)
    setInstanceRunning(instance_id, false)
    resetLaunchUi(instance_id)
  })

  useTauriEvent<string>('launch_cancelled', (instanceId) => {
    showNotice(t('home.launchCancelled'))
    resetLaunchUi(instanceId)
  })

  const handleCancelLaunch = async () => {
    const instanceId = selectedInstanceId
    if (!instanceId || cancelling) return
    setCancelling(instanceId, true)
    try {
      await api.launch.cancel(instanceId)
    } catch (e) {
      showError(e)
      setCancelling(instanceId, false)
    }
  }

  const handleLogout = async () => {
    try {
      await api.auth.logout()
      clearUser()
      navigate('/login', { replace: true })
    } catch (e) { showError(e) }
  }

  const launch = async (connectServer?: string) => {
    // Figé ici : l'utilisateur peut changer d'instance pendant l'appel.
    const instanceId = selectedInstanceId
    if (!instanceId || gameRunning || !username) return
    setBannerPulse(true)
    setTimeout(() => setBannerPulse(false), 900)
    try {
      if (p2pEnabled && P2P_ENABLED) await api.launch.startP2p(instanceId, avoidBetaDependencies, showConsole, connectServer)
      else await api.launch.start(instanceId, avoidBetaDependencies, showConsole, connectServer)
      setInstanceRunning(instanceId, true)
      if (instance) setLastSession({ instanceName: instance.name, at: new Date().toISOString() })
      if (closeOnLaunch) getCurrentWindow().hide()
    } catch (e) {
      // invoke() de Tauri rejette avec une simple chaîne (pas un Error JS)
      // quand une commande Rust renvoie Err(String) — sans ce cas, le vrai
      // message d'erreur était masqué par un texte générique inutile pour
      // diagnostiquer le problème (cf. même bug corrigé sur Login.tsx).
      const message = e instanceof Error ? e.message : typeof e === 'string' ? e : t('home.launchError')
      showError(message)
    }
  }

  // Wrapper sans argument — passé tel quel à onClick du bouton principal, qui
  // lui injecterait sinon l'événement du clic à la place de `connectServer`.
  const handleLaunch = () => launch()

  const handleServerClick = (server: SavedServer) => {
    api.analytics.track('server_card_clicked', { instance_id: selectedInstanceId })
    if (confirmServerLaunch) setPendingServer(server)
    else launch(server.ip)
  }

  const handleToggleFavorite = (ip: string) => {
    if (!selectedInstanceId) return
    if (!toggleFavoriteServer(selectedInstanceId, ip)) {
      showNotice(t('home.maxPinnedServers'))
    }
  }

  const favoriteIps = selectedInstanceId ? favoriteServers[selectedInstanceId] ?? [] : []
  const displayedServers = savedServers.length <= 3
    ? savedServers
    : savedServers.filter((s) => favoriteIps.includes(s.ip))
  const serverSlots: (SavedServer | null)[] = Array.from({ length: 3 }, (_, i) => displayedServers[i] ?? null)

  const canLaunch = !!selectedInstanceId && !!username && !gameRunning
  const percent = progress && progress.total > 0
    ? Math.round(progress.current / progress.total * 100)
    : 0

  const p2pToggleClasses = !P2P_ENABLED
    ? 'bg-[rgba(255,255,255,0.01)] border border-[rgba(255,255,255,0.04)]'
    : p2pEnabled
      ? 'bg-[rgba(75,63,207,0.18)] border border-[rgba(120,100,255,0.35)]'
      : 'bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.06)]'

  const launchBtnBg = canLaunch
    ? 'bg-[#4B3FCF] hover:bg-[#6155e8]'
    : !username
      ? 'bg-[rgba(75,63,207,0.45)] hover:bg-[rgba(75,63,207,0.65)]'
      : 'bg-[rgba(40,38,65,0.7)]'

  const launchBtnShadow = canLaunch
    ? 'shadow-[0_4px_28px_rgba(75,63,207,0.42)] hover:shadow-[0_6px_32px_rgba(75,63,207,0.62)]'
    : 'shadow-none'

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">

      {/* ── Main area ── */}
      <div className="flex gap-4 overflow-hidden p-4 flex-[1_1_0] min-h-0">

        {/* LEFT: Cinematic Minecraft banner */}
        <motion.div
          // La bannière s'installe : elle arrive légèrement réduite puis se
          // pose, comme un écran qu'on allume.
          initial={{ opacity: 0, scale: 0.985 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.45, ease: EASE_OUT }}
          className="relative flex-1 overflow-hidden rounded-[20px] border border-[rgba(200,200,220,0.08)] shadow-[0_8px_40px_rgba(0,0,0,0.7)]"
        >
          <div className="absolute inset-0 bg-[linear-gradient(180deg,#020208_0%,#06041a_18%,#0e0932_40%,#1c1250_58%,#130d35_76%,#070512_100%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_38%_55%,rgba(75,63,207,0.09)_0%,transparent_55%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_68%_58%,rgba(80,210,80,0.06)_0%,transparent_38%)]" />

          {/* Ciel de fête : le dégradé chaud se fond par-dessus la nuit plutôt
              que de la remplacer, sinon les étoiles et le relief sauteraient
              le temps de la bascule. */}
          <motion.div
            animate={{ opacity: festive ? 1 : 0 }}
            transition={{ duration: 1.1 }}
            className="absolute inset-0 bg-[linear-gradient(180deg,#160a14_0%,#3a1230_38%,#7a2340_72%,#c2543c_100%)]"
          />
          {festive && <Confetti />}

          {/* Aurore et étoiles : le panneau respire en permanence. Il n'y a
              plus d'interrupteur — un fond vivant n'est pas une option qu'on
              va chercher, c'est l'état normal de l'écran. */}
          <div className="absolute inset-0 pointer-events-none animate-banner-glow bg-[radial-gradient(ellipse_at_38%_60%,rgba(90,70,255,0.7)_0%,rgba(75,63,207,0.35)_40%,transparent_70%)]" />

          <div className="absolute inset-0 animate-star-pulse">
            {STARS.map((s, i) => (
              <div key={i} className="absolute rounded-full" style={{ left: `${s.x}%`, top: `${s.y}%`, width: s.r, height: s.r, background: `rgba(255,255,255,${s.o})` }} />
            ))}
          </div>

          {/* Flash violet au lancement */}
          {bannerPulse && (
            <div className="absolute inset-0 pointer-events-none animate-banner-flash rounded-[20px] z-10 bg-[radial-gradient(ellipse_at_50%_50%,rgba(160,130,255,0.95)_0%,rgba(90,70,255,0.6)_35%,transparent_72%)]" />
          )}

          <div className="absolute bottom-0 left-0 right-0 h-[38%] animate-terrain-float">
            <svg viewBox="0 0 800 220" preserveAspectRatio="none" className="absolute inset-0 h-full w-full">
              <path d="M0 220 L0 110 L16 110 L16 90 L32 90 L32 110 L48 110 L48 130 L64 130 L64 100 L80 100 L80 78 L96 78 L96 100 L112 100 L112 120 L128 120 L128 95 L144 95 L144 78 L160 78 L160 95 L176 95 L176 115 L192 115 L192 135 L208 135 L208 115 L224 115 L224 98 L240 98 L240 78 L256 78 L256 95 L272 95 L272 115 L288 115 L288 100 L304 100 L304 82 L320 82 L320 100 L336 100 L336 120 L352 120 L352 100 L368 100 L368 82 L384 82 L384 100 L400 100 L400 118 L416 118 L416 135 L432 135 L432 115 L448 115 L448 95 L464 95 L464 78 L480 78 L480 92 L496 92 L496 110 L512 110 L512 128 L528 128 L528 108 L544 108 L544 88 L560 88 L560 108 L576 108 L576 125 L592 125 L592 140 L608 140 L608 120 L624 120 L624 100 L640 100 L640 80 L656 80 L656 98 L672 98 L672 115 L688 115 L688 100 L704 100 L704 82 L720 82 L720 100 L736 100 L736 118 L752 118 L752 105 L768 105 L768 120 L784 120 L784 140 L800 140 L800 220 Z" fill="rgba(4,3,12,0.88)" />
            </svg>
          </div>

          <div className="absolute bottom-0 left-0 right-0 h-24 bg-[linear-gradient(to_top,rgba(9,9,13,0.95),transparent)]" />

          {/* Instance badge — bottom right. Change d'instance : l'ancien
              badge s'efface pendant que le nouveau monte. */}
          <AnimatePresence mode="wait">
            {instance && (
              <motion.div
                key={instance.id}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
                transition={{ duration: 0.22, ease: EASE_OUT }}
                className="absolute bottom-4 right-4 flex items-center gap-1.5"
              >
                <span className="text-[10px] font-semibold" style={{ color: loaderColor(instance.loader) }}>{instance.loader}</span>
                <span className="text-[11px] text-[rgba(255,255,255,0.18)] font-medium">{instance.mc_version}</span>
              </motion.div>
            )}
          </AnimatePresence>

          <WelcomeSequence
            username={username}
            avatarUrl={username ? (customFaceUri ?? `https://mc-heads.net/avatar/${uuid}/108`) : null}
            greeting={username && welcome.phrase ? welcome.phrase : t('home.welcomeNew')}
            playIntro={welcome.playIntro}
          />

          {/* La bannière attend que le badge soit posé : deux choses qui
              entrent en même temps, on ne regarde ni l'une ni l'autre. */}
          <HomeBanner banner={banner} visible={bannerReady} />
        </motion.div>

        {/* RIGHT: Launcher panel — pas de scroll : tout est dimensionné en
            clamp(vh) pour rétrécir avec la HAUTEUR de fenêtre (pas vw comme
            avant — ce panneau empile ses éléments verticalement, c'est la
            hauteur disponible qui le contraint, pas la largeur). */}
        <motion.div
          // Le panneau arrive de la droite, ses éléments se posent ensuite
          // l'un après l'autre (variantes `panelItem` ci-dessous).
          variants={panelVariants}
          initial="initial"
          animate="animate"
          className="relative flex w-[28%] min-w-[220px] flex-shrink-0 flex-col items-center justify-between overflow-hidden px-1 pt-[clamp(6px,2.5vh,20px)]"
        >

          <motion.button
            variants={panelItem}
            whileHover={{ scale: 1.12, rotate: 8 }}
            whileTap={{ scale: 0.92 }}
            onClick={() => navigate('/information')}
            className="absolute top-[8px] right-[8px] w-[clamp(24px,4.8vh,36px)] h-[clamp(24px,4.8vh,36px)] flex items-center justify-center rounded-lg text-[rgba(255,255,255,0.3)] bg-transparent transition-all duration-150 hover:text-[rgba(255,255,255,0.8)] hover:bg-[rgba(255,255,255,0.06)]"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" className="w-[55%] h-[55%]"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" /></svg>
          </motion.button>

          {/* Le titre respire : sa lueur enfle et retombe lentement. */}
          <motion.h1
            variants={panelItem}
            animate={{
              textShadow: [
                '0 0 40px rgba(75,63,207,0.60)',
                '0 0 56px rgba(75,63,207,0.85)',
                '0 0 40px rgba(75,63,207,0.60)',
              ],
            }}
            transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut' }}
            className="text-center font-black text-white leading-none text-[clamp(16px,6vh,64px)] tracking-[-0.01em]"
          >
            YuyuFrame
          </motion.h1>

          {/* Avatar */}
          <motion.div variants={panelItem} className="flex flex-col items-center gap-2">
            {username ? (
              <button onClick={() => navigate('/login')} className="flex flex-col items-center gap-2 group" title={t('home.manageAccounts')}>
                <div className="relative">
                  {uuid && (
                    <img
                      src={customFaceUri ?? `https://mc-heads.net/avatar/${uuid}/150`}
                      alt={username}
                      // Pas de image-rendering:pixelated ici — l'avatar est
                      // redimensionné en continu par clamp() entre 40 et 150px
                      // (voir le panneau de droite, dimensionné en vh) : le
                      // nearest-neighbor de "pixelated" produit des blocs de
                      // taille inégale à un ratio de downscale non entier,
                      // visible comme un rendu "mal scallé" à certaines tailles
                      // de fenêtre. Un lissage classique reste net à toutes les
                      // tailles ; demander la source à 150 (= le max du clamp)
                      // évite aussi un downscale inutilement agressif.
                      className="rounded-xl transition-all duration-200 group-hover:brightness-75 w-[clamp(45px,calc(-151px_+_35vh),150px)] h-[clamp(45px,calc(-151px_+_35vh),150px)] object-cover shadow-[0_4px_24px_rgba(0,0,0,0.6)]"
                      onError={(e) => {
                        e.currentTarget.style.display = 'none'
                        const fb = e.currentTarget.nextElementSibling as HTMLElement | null
                        if (fb) fb.style.display = 'flex'
                      }}
                    />
                  )}
                  <div
                    className={`items-center justify-center rounded-xl font-black text-white transition-all duration-200 group-hover:brightness-75 w-[clamp(45px,calc(-151px_+_35vh),150px)] h-[clamp(45px,calc(-151px_+_35vh),150px)] text-[clamp(14px,6.8vh,56px)] bg-[rgba(75,63,207,0.60)] [font-family:monospace] ${uuid ? 'hidden' : 'flex'}`}
                  >
                    {username[0].toUpperCase()}
                  </div>
                  <div className="absolute inset-0 flex items-center justify-center rounded-xl opacity-0 group-hover:opacity-100 transition-opacity duration-200">
                    <svg viewBox="0 0 24 24" fill="white" className="w-[30%] h-[30%] opacity-90">
                      <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
                    </svg>
                  </div>
                </div>
                <span className="text-[clamp(9px,1.4vh,11px)] text-[rgba(255,255,255,0.4)] font-medium">{username}</span>
              </button>
            ) : (
              <button
                onClick={() => navigate('/login')}
                className="flex flex-col items-center justify-center gap-2 rounded-xl transition-all duration-200 w-[clamp(45px,calc(-151px_+_35vh),150px)] h-[clamp(45px,calc(-151px_+_35vh),150px)] border-2 border-dashed border-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.25)] hover:border-[rgba(75,63,207,0.5)] hover:text-[rgba(120,110,230,0.7)]"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" className="w-[28%] h-[28%]">
                  <path d="M11 7L9.6 8.4l2.6 2.6H2v2h10.2l-2.6 2.6L11 17l5-5-5-5zm9 12h-8v2h8c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-8v2h8v14z" />
                </svg>
                <span className="text-[clamp(8px,1.3vh,10px)] tracking-[0.1em] font-semibold">{t('home.connect')}</span>
              </button>
            )}
          </motion.div>

          {/* Le trait se déploie depuis le centre à l'ouverture. */}
          <motion.div
            initial={{ scaleX: 0 }}
            animate={{ scaleX: 1 }}
            transition={{ duration: 0.5, ease: EASE_OUT, delay: 0.25 }}
            className="w-full h-px bg-[rgba(255,255,255,0.06)]"
          />

          {/* Instance + lancement — groupés avec un gap fixe pour que le bouton
              ne flotte pas dans un espace résiduel géré par le justify-between
              du panneau ; largeurs décroissantes (sélecteur > pastille > bouton)
              pour former une pyramide inversée. */}
          <motion.div variants={panelItem} className="w-full flex flex-col gap-[clamp(6px,2.1vh,16px)]">

          {/* Instance selector */}
          <div className="w-full flex flex-col gap-[clamp(3px,1.05vh,8px)]">
            <div className="flex items-center justify-between">
              <label className="text-[clamp(8px,1.3vh,10px)] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('home.instance')}
              </label>
              <button
                onClick={() => navigate('/instances')}
                className="flex items-center gap-1 transition-colors duration-150 text-[clamp(8px,1.3vh,10px)] text-[rgba(75,63,207,0.7)] font-semibold hover:text-[#7872e8]"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
                {t('home.manage')}
              </button>
            </div>

            {instances.length === 0 ? (
              <button
                onClick={() => navigate('/instances')}
                className="w-full flex items-center justify-center gap-2 rounded-xl transition-all duration-200 h-[clamp(30px,6vh,45px)] border-2 border-dashed border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.2)] text-[12px] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(120,110,230,0.6)]"
              >
                {t('home.createInstance')}
              </button>
            ) : (
              <button
                onClick={() => setShowInstanceSwitch(true)}
                className="relative w-full flex items-center justify-between rounded-xl px-3 text-sm font-medium text-white outline-none h-[clamp(30px,6vh,45px)] bg-[rgba(0,0,0,0.45)] border border-[rgba(255,255,255,0.1)] transition-all duration-150 hover:border-[rgba(75,63,207,0.4)]"
              >
                <span className="truncate">
                  {instance ? `${instance.name} — ${instance.mc_version} (${instance.loader})` : t('home.chooseInstance')}
                </span>
                <svg viewBox="0 0 10 6" fill="white" width={10} height={6} className="flex-shrink-0 opacity-[0.45]">
                  <path d="M0 0l5 6 5-6z" />
                </svg>
              </button>
            )}

            {/* Instance info pill — se replie/déplie et change de contenu en
                douceur quand on passe d'une instance à l'autre. */}
            <AnimatePresence mode="wait" initial={false}>
            {instance && (
              <motion.div
                key={instance.id}
                initial={{ opacity: 0, height: 0, y: -4 }}
                animate={{ opacity: 1, height: 'auto', y: 0 }}
                exit={{ opacity: 0, height: 0, y: -4 }}
                transition={{ duration: 0.24, ease: EASE_OUT }}
                className="w-[92%] mx-auto flex flex-col gap-[clamp(3px,0.8vh,6px)] px-3 py-[clamp(4px,1vh,6px)] rounded-xl bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.05)] overflow-hidden"
              >
                <div className="flex items-center gap-2">
                  <span className="text-[clamp(8px,1.3vh,10px)] font-bold" style={{ color: loaderColor(instance.loader) }}>{instance.loader.toUpperCase()}</span>
                  <span className="text-[clamp(8px,1.3vh,10px)] text-[rgba(255,255,255,0.25)]">·</span>
                  <span className="text-[clamp(8px,1.3vh,10px)] text-[rgba(255,255,255,0.3)]">{instance.mc_version}</span>
                  <span className="text-[clamp(8px,1.3vh,10px)] text-[rgba(255,255,255,0.25)]">·</span>
                  <span className="text-[clamp(8px,1.3vh,10px)] text-[rgba(255,255,255,0.3)]">{instance.ram_mb >= 1024 ? `${instance.ram_mb / 1024}Go` : `${instance.ram_mb}Mo`}</span>
                </div>

                <button
                  onClick={() => !gameRunning && P2P_ENABLED && setP2pEnabled(!p2pEnabled)}
                  disabled={gameRunning || !P2P_ENABLED}
                  title={!P2P_ENABLED ? t('home.p2pComingSoon') : undefined}
                  className={`flex items-center justify-between transition-all duration-150 h-[clamp(18px,3.5vh,26px)] rounded-lg px-2 disabled:cursor-not-allowed cursor-pointer ${P2P_ENABLED ? 'opacity-100' : 'opacity-60'} ${p2pToggleClasses}`}
                >
                  <span className="flex items-center gap-1.5 text-[clamp(8px,1.3vh,10px)] font-semibold text-[rgba(255,255,255,0.25)]">
                    <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>
                    {t('home.p2p')} {!P2P_ENABLED && <span className="text-[9px] opacity-60">{t('home.p2pSoon')}</span>}
                  </span>
                  <span className="relative transition-all duration-200 w-[26px] h-[14px] rounded-[7px] bg-[rgba(255,255,255,0.12)] flex-shrink-0">
                    <span className="absolute top-0.5 rounded-full bg-white transition-all duration-200 w-2.5 h-2.5 left-0.5 opacity-40" />
                  </span>
                </button>
              </motion.div>
            )}
            </AnimatePresence>
          </div>

          {/* Launch button — l'annulation devient une pastille "Annuler"
              intégrée sous le texte "EN JEU..." plutôt qu'un bouton rond
              séparé, pour ne pas casser la forme du bouton principal. */}
          <div className="flex w-[80%] mx-auto gap-2">
            {gameRunning ? (
              <div
                className={`relative overflow-hidden font-bold text-white transition-all duration-200 flex-1 flex flex-col items-center justify-center gap-1.5 rounded-2xl text-[13px] tracking-[0.04em] py-2.5 ${launchBtnBg} ${launchBtnShadow}`}
              >
                {progress && (
                  <span
                    className="absolute inset-y-0 left-0 z-0 bg-[rgba(255,255,255,0.22)] transition-all duration-300 ease-out"
                    style={{ width: `${percent}%` }}
                  />
                )}
                <span className="relative z-10 flex items-center justify-center gap-2">
                  <span className="h-4 w-4 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.2)] border-t-white" />
                  {t('home.launching')}
                </span>
                <button
                  onClick={handleCancelLaunch}
                  disabled={cancelling}
                  className={`relative z-10 rounded-full px-4 py-1 text-[10px] font-semibold transition-all duration-150 border ${cancelling ? 'text-[rgba(255,255,255,0.3)] border-[rgba(255,255,255,0.08)] bg-transparent cursor-not-allowed' : 'text-[rgba(252,165,165,0.9)] border-[rgba(248,113,113,0.35)] bg-[rgba(200,50,50,0.14)] cursor-pointer hover:bg-[rgba(200,50,50,0.26)]'}`}
                >
                  {cancelling ? t('home.cancelling') : t('home.cancel')}
                </button>
              </div>
            ) : (
              <motion.button
                onClick={username ? handleLaunch : () => navigate('/login')}
                disabled={!!username && !selectedInstanceId}
                // Le bouton le plus important de l'app : il respire quand il
                // est prêt, se soulève au survol et s'enfonce au clic.
                animate={
                  username && selectedInstanceId && !progress
                    ? { boxShadow: ['0 0 0 rgba(75,63,207,0)', '0 0 34px rgba(75,63,207,0.5)', '0 0 0 rgba(75,63,207,0)'] }
                    : undefined
                }
                transition={{ duration: 3.2, repeat: Infinity, ease: 'easeInOut' }}
                whileHover={{ scale: 1.03 }}
                whileTap={{ scale: 0.96 }}
                className={`relative overflow-hidden font-bold text-white transition-all duration-200 h-[clamp(34px,7vh,52px)] flex-1 rounded-2xl text-[13px] tracking-[0.04em] disabled:cursor-not-allowed cursor-pointer ${launchBtnBg} ${launchBtnShadow}`}
              >
                {progress && (
                  <span
                    className="absolute inset-y-0 left-0 z-0 bg-[rgba(255,255,255,0.22)] transition-all duration-300 ease-out"
                    style={{ width: `${percent}%` }}
                  />
                )}
                {progress ? (
                  <span className="relative z-10 flex items-center justify-center gap-2 px-3">
                    <span className="truncate">{progress.message}</span>
                    <span className="flex-shrink-0 opacity-80">{percent}%</span>
                  </span>
                ) : (
                  <span className="relative z-10">
                    {!username ? t('home.connect')
                      : !selectedInstanceId ? t('home.noInstance')
                      : t('home.launch', { name: instance?.name ?? '' })}
                  </span>
                )}
              </motion.button>
            )}
          </div>

          </motion.div>
        </motion.div>
      </div>

      {/* ── Footer ── plus de scrollbar : hauteur dictée par son propre
          contenu (flex-shrink-0, pas de min-h/pourcentage forcé), qui
          rétrécit lui-même via les clamp() vh ci-dessous. C'est la zone
          principale (banner + panneau de lancement, au-dessus) qui absorbe
          l'espace restant et scrolle si besoin — jamais le footer. */}
      <motion.div
        // Le pied de page monte après la zone principale.
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, ease: EASE_OUT, delay: 0.12 }}
        className="flex flex-shrink-0 flex-col px-6 py-[clamp(8px,2vh,16px)] bg-[#09090D] border-t border-t-[rgba(255,255,255,0.06)] gap-[clamp(6px,1.2vh,12px)]"
      >

        {/* Zone principale — s'étire pour remplir l'espace disponible */}
        <div className="flex flex-1 flex-col gap-[clamp(8px,1.6vh,16px)]">

        {/* Feature cards — explicatif — remplacées par les raccourcis serveurs
            si l'utilisateur a activé "Afficher mes serveurs sur l'accueil"
            (voir Settings.tsx) : même gabarit (3 cartes flex-1), juste le
            contenu qui change. */}
        {/* Les trois cartes arrivent l'une après l'autre. */}
        <motion.div
          variants={cardsVariants}
          initial="initial"
          animate="animate"
          className="flex items-stretch gap-2"
        >
          {showHomeServers ? (
            <>
              {serverSlots.map((server, i) =>
                server ? (
                  // Enveloppe animée : la carte garde son propre rendu, on
                  // ne fait qu'ajouter son entrée en cascade et le survol.
                  <motion.div
                    key={server.ip}
                    variants={cardItem}
                    whileHover={{ y: -3 }}
                    transition={{ type: 'spring', stiffness: 420, damping: 26 }}
                    className="flex flex-1"
                  >
                    <ServerCard
                      server={server}
                      className="flex-1"
                      onClick={() => handleServerClick(server)}
                    />
                  </motion.div>
                ) : (
                  <motion.div
                    key={`empty-${i}`}
                    variants={cardItem}
                    onClick={savedServers.length > 3 ? () => setShowServerManage(true) : undefined}
                    className={`flex flex-1 flex-col items-center justify-center gap-1 rounded-xl px-[clamp(8px,1.4vh,12px)] py-[clamp(6px,1.1vh,10px)] text-center border-2 border-dashed border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.2)] text-[clamp(8px,1.15vh,10px)] transition-all duration-200 ${savedServers.length > 3 ? 'cursor-pointer hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(120,110,230,0.6)]' : ''}`}
                  >
                    {savedServers.length === 0
                      ? t('home.noServerRegistered')
                      : savedServers.length > 3
                        ? t('home.noServerPinned')
                        : t('home.noOtherServer')}
                  </motion.div>
                )
              )}
              {savedServers.length > 3 && (
                <button
                  onClick={() => setShowServerManage(true)}
                  title={t('home.allServers')}
                  className="flex-shrink-0 flex items-center justify-center w-9 rounded-xl bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.35)] transition-all duration-150 hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)] hover:text-white"
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
                    <circle cx="12" cy="5" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="12" cy="19" r="1.8" />
                  </svg>
                </button>
              )}
            </>
          ) : (
            FEATURES.map((f, i) => (
              <motion.div
                key={i}
                variants={cardItem}
                whileHover={f.path ? { y: -3 } : undefined}
                transition={{ type: 'spring', stiffness: 420, damping: 26 }}
                onClick={f.path ? () => navigate(f.path!) : undefined}
                className={`flex flex-1 flex-col gap-1.5 rounded-xl px-[clamp(8px,1.4vh,12px)] py-[clamp(6px,1.1vh,10px)] transition-all duration-150 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.05)] ${f.path ? 'cursor-pointer opacity-100' : 'cursor-default opacity-100'} ${f.path ? 'hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)]' : ''}`}
              >
                <div className="flex items-center gap-1.5 text-[rgba(255,255,255,0.35)]">
                  <span className="flex-shrink-0 text-[clamp(12px,1.6vh,16px)]">{f.icon}</span>
                  <span className="text-[clamp(9px,1.3vh,11px)] font-bold text-[rgba(255,255,255,0.6)] whitespace-nowrap">
                    {f.title}
                  </span>
                </div>
                <p className="text-[clamp(8px,1.15vh,10px)] text-[rgba(255,255,255,0.28)] leading-[1.55] m-0">
                  {f.desc}
                </p>
              </motion.div>
            ))
          )}
        </motion.div>

        {/* Brand | Nav | Promo — 3 colonnes égales, alignées en haut.
            overflow-x-auto en filet de sécurité : si les 7 liens de nav ne
            tiennent plus même à leur taille clamp() minimale, la ligne
            devient scrollable au lieu de couper les derniers liens. */}
        <div className="my-auto grid gap-4 grid-cols-[auto_1fr_auto] items-center overflow-x-auto">

          {/* LEFT — Brand + compte */}
          <div className="flex flex-col gap-2">
            <span className="font-black text-white text-[clamp(13px,1.9vh,17px)] tracking-[-0.01em]">
              YuyuFrame
            </span>
            <span className="text-[clamp(9px,1.3vh,11px)] text-[rgba(255,255,255,0.22)] leading-normal">
              {t('home.brandTagline')}
            </span>
            <div className="flex items-center mt-1">
              {username ? (
                <div className="flex items-center overflow-hidden h-8 rounded-[10px] bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.09)]">
                  <button
                    onClick={() => navigate('/login')}
                    className="flex items-center gap-2 h-full pl-2.5 pr-3 transition-all duration-150 hover:bg-[rgba(75,63,207,0.14)]"
                    title={t('home.manageAccount')}
                  >
                    <span className="w-1.5 h-1.5 rounded-full bg-[#4ade80] flex-shrink-0 inline-block" />
                    {uuid && (
                      <img src={customFaceUri ?? `https://mc-heads.net/avatar/${uuid}/32`} alt={username}
                        className="w-4 h-4 [image-rendering:pixelated] rounded-[3px] flex-shrink-0"
                        onError={(e) => { e.currentTarget.style.display = 'none' }}
                      />
                    )}
                    <span className="text-[11px] font-semibold text-[rgba(255,255,255,0.82)] max-w-[80px] overflow-hidden text-ellipsis whitespace-nowrap">
                      {username}
                    </span>
                  </button>
                  <div className="w-px h-4 bg-[rgba(255,255,255,0.08)] flex-shrink-0" />
                  <button
                    onClick={handleLogout}
                    className="flex items-center justify-center h-full px-2.5 transition-all duration-150 text-[rgba(255,255,255,0.28)] hover:bg-[rgba(200,50,50,0.14)] hover:text-[rgb(248,113,113)]"
                    title={t('home.logout')}
                  >
                    <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
                      <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z" />
                    </svg>
                  </button>
                </div>
              ) : (
                <button
                  onClick={() => navigate('/login')}
                  className="flex items-center gap-1.5 rounded-xl px-4 font-semibold transition-all duration-200 h-8 text-[11px] bg-[#4B3FCF] text-white hover:bg-[#6155e8]"
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}><path d="M11 7L9.6 8.4l2.6 2.6H2v2h10.2l-2.6 2.6L11 17l5-5-5-5zm9 12h-8v2h8c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-8v2h8v14z" /></svg>
                  {t('home.login')}
                </button>
              )}
            </div>
          </div>

          {/* CENTER — Nav pyramid */}
          <div className="flex min-w-0 items-center justify-center gap-2 w-full [container-type:inline-size]">
            <NavLink label={t('home.nav.instances')} path="/instances" onClick={() => navigate('/instances')} currentPath={location.pathname} distance={3}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 16.5c0 .38-.21.71-.53.88l-7.9 4.44c-.16.12-.36.18-.57.18s-.41-.06-.57-.18l-7.9-4.44A1 1 0 013 16.5v-9c0-.38.21-.71.53-.88l7.9-4.44c.16-.12.36-.18.57-.18s.41.06.57.18l7.9 4.44c.32.17.53.5.53.88v9z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.settings')} path="/settings" onClick={() => navigate('/settings')} currentPath={location.pathname} distance={2}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" /></svg>
            </NavLink>
            {/* Sync et Stats ne sont plus dans la barre : elles vivent
                désormais dans la page Fonctionnalités, qui les regroupe avec
                celles à venir. */}
            <NavLink label={t('home.nav.features')} path="/features" onClick={() => navigate('/features')} currentPath={location.pathname} distance={1} accent>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.plans')} path="/plans" onClick={() => navigate('/plans')} currentPath={location.pathname} distance={0} plans>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.support')} path="/support" onClick={() => navigate('/support')} currentPath={location.pathname} distance={1} accent>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a9 9 0 00-9 9v5a3 3 0 003 3h1a1 1 0 001-1v-5a1 1 0 00-1-1H5v-1a7 7 0 1114 0v1h-2a1 1 0 00-1 1v5a1 1 0 001 1h1a3 3 0 003-3v-5a9 9 0 00-9-9z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.account')} path="/login" onClick={() => navigate('/login')} currentPath={location.pathname} distance={2}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.7 0 4.8-2.1 4.8-4.8S14.7 2.4 12 2.4 7.2 4.5 7.2 7.2 9.3 12 12 12zm0 2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.server')} path="/server" onClick={() => navigate('/server')} currentPath={location.pathname} distance={3}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
            </NavLink>
          </div>

          {/* RIGHT — YuyuFrame Pro, miroir du LEFT aligné à droite */}
          <div className="flex flex-col gap-2 items-end">
            <span className="font-black text-white text-right text-[clamp(13px,1.9vh,17px)] tracking-[-0.01em]">
              YuyuFrame <span className="text-[#a78bfa]">Pro</span>
            </span>
            <span className="text-right text-[clamp(9px,1.3vh,11px)] text-[rgba(255,255,255,0.22)] leading-[1.6]">
              {t('home.proTagline')}
            </span>
            {/* Pill pleine largeur : bouton | séparateur | -50%.
                Une lueur violette très lente attire l'œil sans clignoter. */}
            <motion.div
              animate={{ boxShadow: ['0 0 0 rgba(120,100,255,0)', '0 0 18px rgba(120,100,255,0.25)', '0 0 0 rgba(120,100,255,0)'] }}
              transition={{ duration: 4.5, repeat: Infinity, ease: 'easeInOut' }}
              className="flex items-center overflow-hidden mt-1 h-8 rounded-[10px] bg-[rgba(75,63,207,0.08)] border border-[rgba(120,100,255,0.2)]"
            >
              <motion.button
                whileHover={{ scale: 1.03 }}
                whileTap={{ scale: 0.97 }}
                onClick={() => navigate('/plans')}
                className="flex items-center gap-2 h-full pl-3 pr-3 transition-all duration-150 hover:bg-[rgba(75,63,207,0.2)]"
                title={t('home.proSeePlansTitle')}
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} className="text-[#a78bfa] flex-shrink-0"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" /></svg>
                <span className="text-[11px] font-semibold text-[rgba(255,255,255,0.82)] whitespace-nowrap">
                  {t('home.proSeePlans')}
                </span>
              </motion.button>
              <div className="w-px h-4 bg-[rgba(255,255,255,0.08)] flex-shrink-0" />
              <div className="flex items-center justify-center h-full px-3">
                <span className="text-[10px] font-bold text-[#a78bfa] whitespace-nowrap">-50%</span>
              </div>
            </motion.div>
          </div>
        </div>

        </div>{/* fin zone principale */}

        {/* Divider — collé en bas */}
        <div className="mt-auto h-px bg-[rgba(255,255,255,0.06)]" />

        {/* Copyright + legal */}
        <div className="flex items-center justify-between">
          <span className="text-[clamp(9px,1.2vh,11px)] text-[rgba(255,255,255,0.15)] font-medium">
            {t('home.copyright')}
          </span>
          <div className="flex items-center gap-4">
            {[
              { lbl: t('home.legal.licence'), tab: 'licence' },
              { lbl: t('home.legal.confidentialite'), tab: 'confidentialite' },
              { lbl: t('home.legal.conditions'), tab: 'conditions' },
            ].map(({ lbl, tab }) => (
              <button
                key={lbl}
                onClick={() => navigate(`/legal?tab=${tab}`)}
                className="transition-colors duration-150 text-[clamp(9px,1.2vh,11px)] text-[rgba(255,255,255,0.18)] font-medium hover:text-[rgba(255,255,255,0.5)]"
              >
                {lbl}
              </button>
            ))}
          </div>
        </div>

      </motion.div>

      {showInstanceSwitch && (
        <InstanceSwitchModal
          instances={instances}
          selectedInstanceId={selectedInstanceId}
          onClose={() => setShowInstanceSwitch(false)}
          onSelect={setSelectedInstanceId}
        />
      )}

      {showServerManage && (
        <ServerManageModal
          servers={savedServers}
          favorites={favoriteIps}
          onToggleFavorite={handleToggleFavorite}
          onClose={() => setShowServerManage(false)}
          onLaunch={(server) => handleServerClick(server)}
        />
      )}

      {pendingServer && (
        <ServerConfirmModal
          server={pendingServer}
          onClose={() => setPendingServer(null)}
          onConfirm={() => { launch(pendingServer.ip); setPendingServer(null) }}
        />
      )}
    </div>
  )
}


const NAV_SIZE_CLASSES = [
  'h-[clamp(52px,6vh,76px)] text-[clamp(13px,2.6cqw,21px)] font-[700] px-[clamp(7px,1.4cqw,12px)]',
  'h-[clamp(46px,5.5vh,68px)] text-[clamp(12px,2.3cqw,19px)] font-[650] px-[clamp(6px,1.2cqw,11px)]',
  'h-[clamp(40px,5vh,60px)] text-[clamp(11px,2cqw,17px)] font-[600] px-[clamp(5px,1.1cqw,9px)]',
  'h-[clamp(36px,4.5vh,52px)] text-[clamp(10px,1.7cqw,14px)] font-[550] px-[clamp(4px,0.9cqw,8px)]',
]

const NAV_ICON_CLASSES = [
  'w-[clamp(16px,2.6cqw,22px)] h-[clamp(16px,2.6cqw,22px)]',
  'w-[clamp(14px,2.3cqw,19px)] h-[clamp(14px,2.3cqw,19px)]',
  'w-[clamp(12px,2cqw,17px)] h-[clamp(12px,2cqw,17px)]',
  'w-[clamp(11px,1.7cqw,14px)] h-[clamp(11px,1.7cqw,14px)]',
]

function NavLink({ label, onClick, plans, accent, distance = 0, path, currentPath, children }: {
  label: string; onClick: () => void; plans?: boolean; accent?: boolean; distance?: number; path?: string; currentPath?: string; children: React.ReactNode
}) {
  // Tailles relatives à la fenêtre via clamp — s'adaptent à toutes les largeurs
  // Unités cqw : relatives à la largeur réellement disponible pour la nav (container query),
  // plutôt qu'à la largeur de toute la fenêtre — la nav s'adapte donc à la place qui lui est laissée.
  const d = Math.min(distance, 3)
  const isActive = path ? currentPath === path : false
  const baseColorClass = plans ? 'text-[#c4b5fd]' : isActive ? 'text-[#a5b4fc]' : 'text-[rgba(255,255,255,0.9)]'
  const bgBorderShadow = plans
    ? 'bg-[rgba(75,63,207,0.1)] border border-[rgba(120,100,255,0.22)] shadow-[0_0_18px_rgba(75,63,207,0.12)_inset]'
    : 'bg-transparent border border-transparent shadow-none'
  const hoverClasses = plans
    ? 'hover:text-[#e9d5ff] hover:bg-[rgba(75,63,207,0.22)] hover:border-[rgba(139,92,246,0.48)]'
    : accent
      ? 'hover:text-[#c4b5fd] hover:bg-[rgba(139,92,246,0.22)] hover:border-[rgba(139,92,246,0.60)]'
      : 'hover:text-[rgba(255,255,255,0.92)] hover:bg-[rgba(255,255,255,0.06)]'

  return (
    <motion.button
      onClick={onClick}
      whileHover={{ y: -2 }}
      whileTap={{ scale: 0.95 }}
      // Ressort raide et léger : le survol d'un lien de nav doit répondre
      // tout de suite, pas accompagner le curseur.
      transition={{ type: 'spring', stiffness: 900, damping: 32, mass: 0.4 }}
      className={`relative flex items-center gap-1 rounded-xl transition-all duration-150 whitespace-nowrap cursor-pointer ${NAV_SIZE_CLASSES[d]} ${baseColorClass} ${bgBorderShadow} opacity-100 ${hoverClasses}`}
    >
      <span className={`flex flex-shrink-0 ${NAV_ICON_CLASSES[d]} ${plans ? 'text-[#a78bfa]' : 'text-inherit'}`}>{children}</span>
      {label}
      {isActive && (
        // Le soulignement glisse d'un onglet à l'autre : c'est le même
        // élément, partagé par `layoutId`, qui se déplace.
        <motion.span
          layoutId="nav-active-underline"
          transition={{ type: 'spring', stiffness: 420, damping: 32 }}
          className={`absolute bottom-[5px] left-1/2 -translate-x-1/2 w-[14px] h-0.5 rounded-[1px] ${plans ? 'bg-[#a78bfa]' : 'bg-[#818cf8]'}`}
        />
      )}
    </motion.button>
  )
}
