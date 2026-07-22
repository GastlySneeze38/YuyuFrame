import { useEffect, useRef, useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { BETA_TEST } from '@/config/beta'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { loaderColor } from '@/lib/loader'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { showError, showNotice } from '@/stores/useErrorToast'
import { InstanceSwitchModal } from '@/components/instances/InstanceSwitchModal'
import { ServerCard } from '@/components/servers/ServerCard'
import { ServerManageModal } from '@/components/servers/ServerManageModal'
import { ServerConfirmModal } from '@/components/servers/ServerConfirmModal'
import type { SavedServer } from '@/api/client'

interface DownloadProgress {
  current: number
  total: number
  message: string
}

const FEATURES = [
  {
    title: 'Sync P2P',
    desc: 'Synchronise configurations, mods et instances entre toutes tes machines en connexion directe. Aucun cloud, aucun serveur tiers — tes données restent chez toi.',
    icon: <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>,
    path: '/sync',
  },
  {
    title: 'Statistiques avancées',
    desc: 'Suivi du temps de session, historique détaillé par instance et graphiques hebdomadaires. Visualise tes habitudes de jeu et compare tes performances dans le temps.',
    icon: <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z" /></svg>,
    path: '/stats',
  },
  {
    title: 'Accès Pro',
    desc: 'Fonctionnalités réservées aux abonnés : limites augmentées, accès anticipé aux nouvelles fonctions et support prioritaire en cas de problème.',
    icon: <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 4l5 2.18V11c0 3.5-2.33 6.79-5 7.93-2.67-1.14-5-4.43-5-7.93V7.18L12 5z" /></svg>,
    path: null,
  },
]

const STARS = Array.from({ length: 55 }, (_, i) => ({
  x: (i * 37 + ((i * 7 + 13) % 100) * 1.7) % 100,
  y: (i * 23 + ((i * 7 + 13) % 100) * 2.3) % 62,
  r: i % 4 === 0 ? 2 : 1,
  o: 0.15 + (i % 5) * 0.08,
}))

export default function Home() {
  const navigate = useNavigate()
  const location = useLocation()
  const {
    username, uuid,
    clearUser,
    instances, setInstances,
    selectedInstanceId, setSelectedInstanceId, selectedInstance,
    isInstanceRunning, setInstanceRunning,
    setLastSession,
    closeOnLaunch,
    p2pEnabled, setP2pEnabled,
    avoidBetaDependencies,
    showConsole,
    launchPhaseDurations, recordLaunchPhaseDuration,
    showHomeServers,
    confirmServerLaunch,
    favoriteServers, toggleFavoriteServer,
  } = useStore()

  const gameRunning = !!selectedInstanceId && isInstanceRunning(selectedInstanceId)

  const [progress, setProgress] = useState<DownloadProgress | null>(null)
  const [launchMsg, setLaunchMsg] = useState('')
  const [cancelling, setCancelling] = useState(false)
  const [showInstanceSwitch, setShowInstanceSwitch] = useState(false)
  const [bannerPulse, setBannerPulse] = useState(false)
  const [bannerAnimating, setBannerAnimating] = useState(false)
  const [savedServers, setSavedServers] = useState<SavedServer[]>([])
  const [showServerManage, setShowServerManage] = useState(false)
  const [pendingServer, setPendingServer] = useState<SavedServer | null>(null)

  const instance = selectedInstance()

  // Phase 2 ("lancement", 60-100%) — les téléchargements (backend) s'arrêtent
  // pile à 60%, le reste (démarrage JVM + chargement interne Minecraft
  // jusqu'au menu principal, voir game_ready) n'a pas de progression réelle
  // connue. On mesure la durée réelle à chaque lancement (voir game_ready
  // ci-dessous) et on la réutilise pour animer la barre les fois suivantes,
  // au lieu de la laisser figée à 60% pendant tout ce temps.
  const phase2StartRef = useRef<number | null>(null)
  const phase2TimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const clearPhase2 = () => {
    if (phase2TimerRef.current) { clearInterval(phase2TimerRef.current); phase2TimerRef.current = null }
    phase2StartRef.current = null
  }

  const startPhase2 = (instanceId: string) => {
    phase2StartRef.current = Date.now()
    const remembered = launchPhaseDurations[instanceId]
    if (!remembered) return // pas encore de mesure — reste figé à 60%, rien à animer
    if (phase2TimerRef.current) clearInterval(phase2TimerRef.current)
    phase2TimerRef.current = setInterval(() => {
      if (!phase2StartRef.current) return
      const elapsed = Date.now() - phase2StartRef.current
      const frac = Math.min(0.975, elapsed / remembered)
      setProgress({ current: 60 + Math.round(frac * 40), total: 100, message: 'Démarrage de Minecraft...' })
    }, 250)
  }

  useEffect(() => clearPhase2, [])

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

  useTauriEvent<DownloadProgress>('download_progress', (payload) => {
    setProgress(payload)
    // Palier exact émis par le backend une fois tous les téléchargements
    // finis (voir DOWNLOAD_PHASE_PERCENT côté Rust) — démarre la phase 2.
    if (payload.current === 60 && payload.total === 100 && !phase2StartRef.current && selectedInstanceId) {
      startPhase2(selectedInstanceId)
    }
  }, [selectedInstanceId])

  useTauriEvent<{ running: boolean; instance_id: string }>('game_state', (payload) => {
    const { running, instance_id } = payload
    setInstanceRunning(instance_id, running)
    if (!running) {
      clearPhase2()
      setProgress(null)
      setCancelling(false)
      getCurrentWindow().show()
    }
  })

  // Émis par le backend quand le hook TitleScreen.init() du LauncherAgent se
  // déclenche (voir TitleScreenMixin*.java) — Minecraft a fini son propre
  // chargement interne (splash + ressources) et affiche enfin le menu
  // principal. Avant ça, la barre restait figée à 60% pendant tout ce temps
  // mort (aucun signal entre la fin de nos téléchargements et le jeu
  // réellement visible). On mesure la durée réelle de cette phase pour
  // affiner l'animation des prochains lancements de cette instance.
  useTauriEvent<{ instance_id: string }>('game_ready', (payload) => {
    if (payload.instance_id !== selectedInstanceId) return
    if (phase2StartRef.current) {
      recordLaunchPhaseDuration(payload.instance_id, Date.now() - phase2StartRef.current)
    }
    clearPhase2()
    setProgress({ current: 100, total: 100, message: 'Minecraft prêt !' })
    setTimeout(() => setProgress(null), 900)
  }, [selectedInstanceId])

  useTauriEvent<string>('launch_error', (payload) => {
    setLaunchMsg(payload)
    if (selectedInstanceId) setInstanceRunning(selectedInstanceId, false)
    clearPhase2()
    setProgress(null)
    setCancelling(false)
  }, [selectedInstanceId])

  useTauriEvent<string>('launch_cancelled', () => {
    showNotice('Lancement annulé')
    clearPhase2()
    setProgress(null)
    setCancelling(false)
  })

  const handleCancelLaunch = async () => {
    if (!selectedInstanceId || cancelling) return
    setCancelling(true)
    try {
      await api.launch.cancel(selectedInstanceId)
    } catch (e) {
      showError(e)
      setCancelling(false)
    }
  }

  const handleLogout = async () => {
    try {
      await api.auth.logout()
      clearUser()
      navigate('/login', { replace: true })
    } catch (e) { showError(e) }
  }

  const handleBannerPlay = () => {
    setBannerAnimating((v) => !v)
  }

  const launch = async (connectServer?: string) => {
    if (!selectedInstanceId || gameRunning || !username) return
    setBannerPulse(true)
    setTimeout(() => setBannerPulse(false), 900)
    setLaunchMsg('')
    try {
      if (p2pEnabled) await api.launch.startP2p(selectedInstanceId, avoidBetaDependencies, showConsole, connectServer)
      else await api.launch.start(selectedInstanceId, avoidBetaDependencies, showConsole, connectServer)
      setInstanceRunning(selectedInstanceId, true)
      if (instance) setLastSession({ instanceName: instance.name, at: new Date().toISOString() })
      if (closeOnLaunch) getCurrentWindow().hide()
    } catch (e) {
      // invoke() de Tauri rejette avec une simple chaîne (pas un Error JS)
      // quand une commande Rust renvoie Err(String) — sans ce cas, le vrai
      // message d'erreur était masqué par un texte générique inutile pour
      // diagnostiquer le problème (cf. même bug corrigé sur Login.tsx).
      const message = e instanceof Error ? e.message : typeof e === 'string' ? e : 'Erreur de lancement'
      setLaunchMsg(message)
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
      showNotice('Maximum 3 serveurs épinglés — désépingle-en un d’abord')
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

  const p2pToggleClasses = BETA_TEST
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
        <div className="relative flex-1 overflow-hidden rounded-[20px] border border-[rgba(200,200,220,0.08)] shadow-[0_8px_40px_rgba(0,0,0,0.7)]">
          <div className="absolute inset-0 bg-[linear-gradient(180deg,#020208_0%,#06041a_18%,#0e0932_40%,#1c1250_58%,#130d35_76%,#070512_100%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_38%_55%,rgba(75,63,207,0.09)_0%,transparent_55%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_68%_58%,rgba(80,210,80,0.06)_0%,transparent_38%)]" />

          {/* Aurora — pulse infinie quand animating */}
          {bannerAnimating && (
            <div className="absolute inset-0 pointer-events-none animate-banner-glow bg-[radial-gradient(ellipse_at_38%_60%,rgba(90,70,255,0.7)_0%,rgba(75,63,207,0.35)_40%,transparent_70%)]" />
          )}

          {/* Stars — scintillent en continu quand animating */}
          <div className={`absolute inset-0 ${bannerAnimating ? 'animate-star-pulse' : ''}`}>
            {STARS.map((s, i) => (
              <div key={i} className="absolute rounded-full" style={{ left: `${s.x}%`, top: `${s.y}%`, width: s.r, height: s.r, background: `rgba(255,255,255,${s.o})` }} />
            ))}
          </div>

          {/* Flash violet au lancement */}
          {bannerPulse && (
            <div className="absolute inset-0 pointer-events-none animate-banner-flash rounded-[20px] z-10 bg-[radial-gradient(ellipse_at_50%_50%,rgba(160,130,255,0.95)_0%,rgba(90,70,255,0.6)_35%,transparent_72%)]" />
          )}

          <div className={`absolute bottom-0 left-0 right-0 h-[38%] ${bannerAnimating ? 'animate-terrain-float' : ''}`}>
            <svg viewBox="0 0 800 220" preserveAspectRatio="none" className="absolute inset-0 h-full w-full">
              <path d="M0 220 L0 110 L16 110 L16 90 L32 90 L32 110 L48 110 L48 130 L64 130 L64 100 L80 100 L80 78 L96 78 L96 100 L112 100 L112 120 L128 120 L128 95 L144 95 L144 78 L160 78 L160 95 L176 95 L176 115 L192 115 L192 135 L208 135 L208 115 L224 115 L224 98 L240 98 L240 78 L256 78 L256 95 L272 95 L272 115 L288 115 L288 100 L304 100 L304 82 L320 82 L320 100 L336 100 L336 120 L352 120 L352 100 L368 100 L368 82 L384 82 L384 100 L400 100 L400 118 L416 118 L416 135 L432 135 L432 115 L448 115 L448 95 L464 95 L464 78 L480 78 L480 92 L496 92 L496 110 L512 110 L512 128 L528 128 L528 108 L544 108 L544 88 L560 88 L560 108 L576 108 L576 125 L592 125 L592 140 L608 140 L608 120 L624 120 L624 100 L640 100 L640 80 L656 80 L656 98 L672 98 L672 115 L688 115 L688 100 L704 100 L704 82 L720 82 L720 100 L736 100 L736 118 L752 118 L752 105 L768 105 L768 120 L784 120 L784 140 L800 140 L800 220 Z" fill="rgba(4,3,12,0.88)" />
            </svg>
          </div>

          <div className="absolute bottom-0 left-0 right-0 h-24 bg-[linear-gradient(to_top,rgba(9,9,13,0.95),transparent)]" />

          {/* Play capsule — top left (animation only) */}
          <button
            onClick={handleBannerPlay}
            className={`absolute left-4 top-4 flex items-center gap-2 h-[30px] pl-[10px] pr-[14px] rounded-[20px] backdrop-blur-[10px] transition-[background,border-color] duration-300 hover:bg-[rgba(75,63,207,0.32)] hover:border-[rgba(255,255,255,0.45)] ${bannerAnimating ? 'bg-[rgba(75,63,207,0.45)] border border-[rgba(120,100,255,0.6)]' : 'bg-[rgba(18,15,38,0.78)] border border-[rgba(255,255,255,0.22)]'}`}
          >
            {bannerAnimating ? (
              <svg viewBox="0 0 10 10" fill="white" width={9} height={9}><rect x="1" y="1" width="3" height="8" /><rect x="6" y="1" width="3" height="8" /></svg>
            ) : (
              <svg viewBox="0 0 10 10" fill="white" width={9} height={9}><polygon points="1,1 9,5 1,9" /></svg>
            )}
            <span className="text-xs font-medium text-white">
              {bannerAnimating ? 'Stop' : 'Play'}
            </span>
          </button>

          {/* Instance badge — bottom right */}
          {instance && (
            <div className="absolute bottom-4 right-4 flex items-center gap-1.5">
              <span className="text-[10px] font-semibold" style={{ color: loaderColor(instance.loader) }}>{instance.loader}</span>
              <span className="text-[11px] text-[rgba(255,255,255,0.18)] font-medium">{instance.mc_version}</span>
            </div>
          )}
        </div>

        {/* RIGHT: Launcher panel */}
        <div className="relative flex w-[28%] flex-shrink-0 flex-col items-center justify-between overflow-hidden px-1 pt-5">

          <button
            onClick={() => navigate('/information')}
            className="absolute top-[8px] right-[8px] w-[36px] h-[36px] flex items-center justify-center rounded-lg text-[rgba(255,255,255,0.3)] bg-transparent transition-all duration-150 hover:text-[rgba(255,255,255,0.8)] hover:bg-[rgba(255,255,255,0.06)]"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={20} height={20}><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" /></svg>
          </button>

          <h1 className="text-center font-black text-white leading-none text-[clamp(28px,3.5vw,64px)] [text-shadow:0_0_40px_rgba(75,63,207,0.60)] tracking-[-0.01em]">
            YuyuFrame
          </h1>

          {/* Avatar */}
          <div className="flex flex-col items-center gap-2">
            {username ? (
              <button onClick={() => navigate('/login')} className="flex flex-col items-center gap-2 group" title="Gérer les comptes">
                <div className="relative">
                  {uuid && (
                    <img
                      src={`https://mc-heads.net/avatar/${uuid}/200`}
                      alt={username}
                      className="rounded-xl transition-all duration-200 group-hover:brightness-75 w-[clamp(80px,9vw,150px)] h-[clamp(80px,9vw,150px)] [image-rendering:pixelated] shadow-[0_4px_24px_rgba(0,0,0,0.6)]"
                      onError={(e) => {
                        e.currentTarget.style.display = 'none'
                        const fb = e.currentTarget.nextElementSibling as HTMLElement | null
                        if (fb) fb.style.display = 'flex'
                      }}
                    />
                  )}
                  <div
                    className={`items-center justify-center rounded-xl font-black text-white transition-all duration-200 group-hover:brightness-75 w-[clamp(80px,9vw,150px)] h-[clamp(80px,9vw,150px)] text-[clamp(28px,4vw,56px)] bg-[rgba(75,63,207,0.60)] [font-family:monospace] ${uuid ? 'hidden' : 'flex'}`}
                  >
                    {username[0].toUpperCase()}
                  </div>
                  <div className="absolute inset-0 flex items-center justify-center rounded-xl opacity-0 group-hover:opacity-100 transition-opacity duration-200">
                    <svg viewBox="0 0 24 24" fill="white" className="w-6 h-6 opacity-90">
                      <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
                    </svg>
                  </div>
                </div>
                <span className="text-[11px] text-[rgba(255,255,255,0.4)] font-medium">{username}</span>
              </button>
            ) : (
              <button
                onClick={() => navigate('/login')}
                className="flex flex-col items-center justify-center gap-2 rounded-xl transition-all duration-200 w-[clamp(80px,9vw,150px)] h-[clamp(80px,9vw,150px)] border-2 border-dashed border-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.25)] hover:border-[rgba(75,63,207,0.5)] hover:text-[rgba(120,110,230,0.7)]"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" className="h-8 w-8">
                  <path d="M11 7L9.6 8.4l2.6 2.6H2v2h10.2l-2.6 2.6L11 17l5-5-5-5zm9 12h-8v2h8c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-8v2h8v14z" />
                </svg>
                <span className="text-[10px] tracking-[0.1em] font-semibold">SE CONNECTER</span>
              </button>
            )}
          </div>

          <div className="w-full h-px bg-[rgba(255,255,255,0.06)]" />

          {/* Instance + lancement — groupés avec un gap fixe pour que le bouton
              ne flotte pas dans un espace résiduel géré par le justify-between
              du panneau ; largeurs décroissantes (sélecteur > pastille > bouton)
              pour former une pyramide inversée. */}
          <div className="w-full flex flex-col gap-4">

          {/* Instance selector */}
          <div className="w-full flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                Instance
              </label>
              <button
                onClick={() => navigate('/instances')}
                className="flex items-center gap-1 transition-colors duration-150 text-[10px] text-[rgba(75,63,207,0.7)] font-semibold hover:text-[#7872e8]"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
                Gérer
              </button>
            </div>

            {instances.length === 0 ? (
              <button
                onClick={() => navigate('/instances')}
                className="w-full flex items-center justify-center gap-2 rounded-xl transition-all duration-200 h-[45px] border-2 border-dashed border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.2)] text-[12px] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(120,110,230,0.6)]"
              >
                Créer une instance
              </button>
            ) : (
              <button
                onClick={() => setShowInstanceSwitch(true)}
                className="relative w-full flex items-center justify-between rounded-xl px-3 text-sm font-medium text-white outline-none h-[45px] bg-[rgba(0,0,0,0.45)] border border-[rgba(255,255,255,0.1)] transition-all duration-150 hover:border-[rgba(75,63,207,0.4)]"
              >
                <span className="truncate">
                  {instance ? `${instance.name} — ${instance.mc_version} (${instance.loader})` : 'Choisir une instance'}
                </span>
                <svg viewBox="0 0 10 6" fill="white" width={10} height={6} className="flex-shrink-0 opacity-[0.45]">
                  <path d="M0 0l5 6 5-6z" />
                </svg>
              </button>
            )}

            {/* Instance info pill */}
            {instance && (
              <div className="w-[92%] mx-auto flex flex-col gap-1.5 px-3 py-1.5 rounded-xl bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.05)]">
                <div className="flex items-center gap-2">
                  <span className="text-[10px] font-bold" style={{ color: loaderColor(instance.loader) }}>{instance.loader.toUpperCase()}</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.25)]">·</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{instance.mc_version}</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.25)]">·</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{instance.ram_mb >= 1024 ? `${instance.ram_mb / 1024}Go` : `${instance.ram_mb}Mo`}</span>
                </div>

                <button
                  onClick={() => !gameRunning && !BETA_TEST && setP2pEnabled(!p2pEnabled)}
                  disabled={gameRunning || BETA_TEST}
                  title={BETA_TEST ? 'P2P non disponible en beta' : undefined}
                  className={`flex items-center justify-between transition-all duration-150 h-[26px] rounded-lg px-2 disabled:cursor-not-allowed cursor-pointer ${BETA_TEST ? 'opacity-60' : 'opacity-100'} ${p2pToggleClasses}`}
                >
                  <span className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(255,255,255,0.25)]">
                    <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>
                    P2P {BETA_TEST && <span className="text-[9px] opacity-60">(bêta)</span>}
                  </span>
                  <span className="relative transition-all duration-200 w-[26px] h-[14px] rounded-[7px] bg-[rgba(255,255,255,0.12)]">
                    <span className="absolute top-0.5 rounded-full bg-white transition-all duration-200 w-2.5 h-2.5 left-0.5 opacity-40" />
                  </span>
                </button>
              </div>
            )}
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
                  EN JEU...
                </span>
                <button
                  onClick={handleCancelLaunch}
                  disabled={cancelling}
                  className={`relative z-10 rounded-full px-4 py-1 text-[10px] font-semibold transition-all duration-150 border ${cancelling ? 'text-[rgba(255,255,255,0.3)] border-[rgba(255,255,255,0.08)] bg-transparent cursor-not-allowed' : 'text-[rgba(252,165,165,0.9)] border-[rgba(248,113,113,0.35)] bg-[rgba(200,50,50,0.14)] cursor-pointer hover:bg-[rgba(200,50,50,0.26)]'}`}
                >
                  {cancelling ? 'Annulation...' : 'Annuler'}
                </button>
              </div>
            ) : (
              <button
                onClick={username ? handleLaunch : () => navigate('/login')}
                disabled={!!username && !selectedInstanceId}
                className={`relative overflow-hidden font-bold text-white transition-all duration-200 active:scale-95 h-[52px] flex-1 rounded-2xl text-[13px] tracking-[0.04em] disabled:cursor-not-allowed cursor-pointer ${launchBtnBg} ${launchBtnShadow}`}
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
                    {!username ? 'SE CONNECTER'
                      : !selectedInstanceId ? 'AUCUNE INSTANCE'
                      : `LANCER ${instance?.name ?? ''}`}
                  </span>
                )}
              </button>
            )}
          </div>

          {launchMsg && (
            <p className="w-full rounded-lg px-3 py-2 text-center text-xs text-red-300 bg-[rgba(200,50,50,0.12)]">
              {launchMsg}
            </p>
          )}

          </div>
        </div>
      </div>

      {/* ── Footer ── */}
      <div className="flex flex-shrink-0 flex-col px-6 py-4 flex-[0_0_30%] min-h-[250px] bg-[#09090D] border-t border-t-[rgba(255,255,255,0.06)] gap-3">

        {/* Zone principale — s'étire pour remplir l'espace disponible */}
        <div className="flex flex-1 flex-col gap-4">

        {/* Feature cards — explicatif — remplacées par les raccourcis serveurs
            si l'utilisateur a activé "Afficher mes serveurs sur l'accueil"
            (voir Settings.tsx) : même gabarit (3 cartes flex-1), juste le
            contenu qui change. */}
        <div className="flex items-stretch gap-2">
          {showHomeServers ? (
            <>
              {serverSlots.map((server, i) =>
                server ? (
                  <ServerCard
                    key={server.ip}
                    server={server}
                    className="flex-1"
                    onClick={() => handleServerClick(server)}
                  />
                ) : (
                  <div
                    key={`empty-${i}`}
                    onClick={savedServers.length > 3 ? () => setShowServerManage(true) : undefined}
                    className={`flex flex-1 flex-col items-center justify-center gap-1 rounded-xl px-3 py-2.5 text-center border-2 border-dashed border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.2)] text-[9px] transition-all duration-200 ${savedServers.length > 3 ? 'cursor-pointer hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(120,110,230,0.6)]' : ''}`}
                  >
                    {savedServers.length === 0
                      ? 'Aucun serveur enregistré'
                      : savedServers.length > 3
                        ? 'Aucun serveur épinglé'
                        : 'Aucun autre serveur'}
                  </div>
                )
              )}
              {savedServers.length > 3 && (
                <button
                  onClick={() => setShowServerManage(true)}
                  title="Tous les serveurs"
                  className="flex-shrink-0 flex items-center justify-center w-9 rounded-xl bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.35)] transition-all duration-150 hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)] hover:text-white"
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
                    <circle cx="12" cy="5" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="12" cy="19" r="1.8" />
                  </svg>
                </button>
              )}
            </>
          ) : (
            FEATURES.map((f, i) => {
              const betaLocked = BETA_TEST && (f.path === '/sync')
              return (
              <div
                key={i}
                onClick={f.path && !betaLocked ? () => navigate(f.path!) : undefined}
                className={`flex flex-1 flex-col gap-1.5 rounded-xl px-3 py-2.5 transition-all duration-150 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.05)] ${betaLocked ? 'cursor-not-allowed opacity-60' : f.path ? 'cursor-pointer opacity-100' : 'cursor-default opacity-100'} ${f.path && !betaLocked ? 'hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)]' : ''}`}
              >
                <div className="flex items-center gap-1.5 text-[rgba(255,255,255,0.35)]">
                  {f.icon}
                  <span className="text-[10px] font-bold text-[rgba(255,255,255,0.6)] whitespace-nowrap">
                    {f.title}
                  </span>
                </div>
                <p className="text-[9px] text-[rgba(255,255,255,0.28)] leading-[1.55] m-0">
                  {f.desc}
                </p>
              </div>
            )})
          )}
        </div>

        {/* Brand | Nav | Promo — 3 colonnes égales, alignées en haut */}
        <div className="my-auto grid gap-8 grid-cols-[auto_1fr_auto] items-center">

          {/* LEFT — Brand + compte */}
          <div className="flex flex-col gap-2">
            <span className="font-black text-white text-[15px] tracking-[-0.01em]">
              YuyuFrame
            </span>
            <span className="text-[10px] text-[rgba(255,255,255,0.22)] leading-normal">
              Le launcher Minecraft open-source.
            </span>
            <div className="flex items-center mt-1">
              {username ? (
                <div className="flex items-center overflow-hidden h-8 rounded-[10px] bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.09)]">
                  <button
                    onClick={() => navigate('/login')}
                    className="flex items-center gap-2 h-full pl-2.5 pr-3 transition-all duration-150 hover:bg-[rgba(75,63,207,0.14)]"
                    title="Gérer le compte"
                  >
                    <span className="w-1.5 h-1.5 rounded-full bg-[#4ade80] flex-shrink-0 inline-block" />
                    {uuid && (
                      <img src={`https://mc-heads.net/avatar/${uuid}/32`} alt={username}
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
                    title="Déconnexion"
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
                  Se connecter
                </button>
              )}
            </div>
          </div>

          {/* CENTER — Nav pyramid */}
          <div className="flex items-center justify-center gap-2 w-full [container-type:inline-size]">
            <NavLink label="Instances" path="/instances" onClick={() => navigate('/instances')} currentPath={location.pathname} distance={3}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 16.5c0 .38-.21.71-.53.88l-7.9 4.44c-.16.12-.36.18-.57.18s-.41-.06-.57-.18l-7.9-4.44A1 1 0 013 16.5v-9c0-.38.21-.71.53-.88l7.9-4.44c.16-.12.36-.18.57-.18s.41.06.57.18l7.9 4.44c.32.17.53.5.53.88v9z" /></svg>
            </NavLink>
            <NavLink label="Réglages" path="/settings" onClick={() => navigate('/settings')} currentPath={location.pathname} distance={2}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" /></svg>
            </NavLink>
            <NavLink label="Sync" path="/sync" onClick={() => navigate('/sync')} currentPath={location.pathname} distance={1} accent disabled={BETA_TEST}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>
            </NavLink>
            <NavLink label="Plans" path="/plans" onClick={() => navigate('/plans')} currentPath={location.pathname} distance={0} plans disabled={BETA_TEST}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" /></svg>
            </NavLink>
            <NavLink label="Stats" path="/stats" onClick={() => navigate('/stats')} currentPath={location.pathname} distance={1} accent>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z" /></svg>
            </NavLink>
            <NavLink label="Compte" path="/login" onClick={() => navigate('/login')} currentPath={location.pathname} distance={2}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.7 0 4.8-2.1 4.8-4.8S14.7 2.4 12 2.4 7.2 4.5 7.2 7.2 9.3 12 12 12zm0 2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z" /></svg>
            </NavLink>
            <NavLink label="Serveur" path="/server" onClick={() => navigate('/server')} currentPath={location.pathname} distance={3} disabled={BETA_TEST}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
            </NavLink>
          </div>

          {/* RIGHT — YuyuFrame Pro, miroir du LEFT aligné à droite */}
          <div className="flex flex-col gap-2 items-end">
            <span className="font-black text-white text-right text-[15px] tracking-[-0.01em]">
              YuyuFrame <span className="text-[#a78bfa]">Pro</span>
            </span>
            <span className="text-right text-[10px] text-[rgba(255,255,255,0.22)] leading-[1.6]">
              Sync illimité · Stats avancées
            </span>
            {/* Pill pleine largeur : bouton | séparateur | -50% */}
            <div className="flex items-center overflow-hidden mt-1 h-8 rounded-[10px] bg-[rgba(75,63,207,0.08)] border border-[rgba(120,100,255,0.2)]">
              <button
                onClick={() => navigate('/plans')}
                className="flex items-center gap-2 h-full pl-3 pr-3 transition-all duration-150 hover:bg-[rgba(75,63,207,0.2)]"
                title="Voir les plans Pro"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} className="text-[#a78bfa] flex-shrink-0"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" /></svg>
                <span className="text-[11px] font-semibold text-[rgba(255,255,255,0.82)] whitespace-nowrap">
                  Voir les plans
                </span>
              </button>
              <div className="w-px h-4 bg-[rgba(255,255,255,0.08)] flex-shrink-0" />
              <div className="flex items-center justify-center h-full px-3">
                <span className="text-[10px] font-bold text-[#a78bfa] whitespace-nowrap">-50%</span>
              </div>
            </div>
          </div>
        </div>

        </div>{/* fin zone principale */}

        {/* Divider — collé en bas */}
        <div className="mt-auto h-px bg-[rgba(255,255,255,0.06)]" />

        {/* Copyright + legal */}
        <div className="flex items-center justify-between">
          <span className="text-[10px] text-[rgba(255,255,255,0.15)] font-medium">
            © 2025 YuyuFrame — Tous droits réservés
          </span>
          <div className="flex items-center gap-4">
            {[
              { lbl: 'Licence', tab: 'licence' },
              { lbl: 'Confidentialité', tab: 'confidentialite' },
              { lbl: 'Conditions', tab: 'conditions' },
            ].map(({ lbl, tab }) => (
              <button
                key={lbl}
                onClick={() => navigate(`/legal?tab=${tab}`)}
                className="transition-colors duration-150 text-[10px] text-[rgba(255,255,255,0.18)] font-medium hover:text-[rgba(255,255,255,0.5)]"
              >
                {lbl}
              </button>
            ))}
          </div>
        </div>

      </div>

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

function NavLink({ label, onClick, plans, accent, distance = 0, path, currentPath, disabled, children }: {
  label: string; onClick: () => void; plans?: boolean; accent?: boolean; distance?: number; path?: string; currentPath?: string; disabled?: boolean; children: React.ReactNode
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
  const hoverClasses = disabled
    ? ''
    : plans
      ? 'hover:text-[#e9d5ff] hover:bg-[rgba(75,63,207,0.22)] hover:border-[rgba(139,92,246,0.48)]'
      : accent
        ? 'hover:text-[#c4b5fd] hover:bg-[rgba(139,92,246,0.22)] hover:border-[rgba(139,92,246,0.60)]'
        : 'hover:text-[rgba(255,255,255,0.92)] hover:bg-[rgba(255,255,255,0.06)]'

  return (
    <button
      onClick={disabled ? undefined : onClick}
      disabled={disabled}
      title={disabled ? 'Non disponible en bêta' : undefined}
      className={`relative flex items-center gap-1 rounded-xl transition-all duration-150 whitespace-nowrap disabled:cursor-not-allowed cursor-pointer ${NAV_SIZE_CLASSES[d]} ${baseColorClass} ${bgBorderShadow} ${disabled ? 'opacity-60' : 'opacity-100'} ${hoverClasses}`}
    >
      <span className={`flex flex-shrink-0 ${NAV_ICON_CLASSES[d]} ${plans ? 'text-[#a78bfa]' : 'text-inherit'}`}>{children}</span>
      {label}
      {isActive && (
        <span className={`absolute bottom-[5px] left-1/2 -translate-x-1/2 w-[14px] h-0.5 rounded-[1px] ${plans ? 'bg-[#a78bfa]' : 'bg-[#818cf8]'}`} />
      )}
    </button>
  )
}
