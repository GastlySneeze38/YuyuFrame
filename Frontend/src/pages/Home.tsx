import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import type { Variants } from 'framer-motion'
import { EASE_OUT, SNAP, press } from '@/lib/motion'
import { P2P_ENABLED } from '@/config/features'
import { api } from '@/api/client'
import { skinPreview } from '@/lib/skinCache'
import { useStore } from '@/stores/useStore'
import { useSupportWatch } from '@/stores/useSupportWatch'
import { useCrashWatch } from '@/stores/useCrashWatch'
import { loaderColor } from '@/lib/loader'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { showError, showNotice } from '@/stores/useErrorToast'
import { InstanceSwitchModal } from '@/components/instances/InstanceSwitchModal'
import { WelcomeSequence } from '@/components/home/WelcomeSequence'
import { homeGreeting } from '@/lib/greeting'
import { HomeBanner, Confetti, useHomeBanner } from '@/components/home/HomeBanner'
import { ProCard } from '@/components/home/ProCard'
import { LaunchSweep } from '@/components/home/LaunchSweep'
import { AgentModal, WarningIcon } from '@/components/home/AgentModal'
import { Skyline } from '@/components/home/Skyline'
import { ServerCard } from '@/components/servers/ServerCard'
import { ServerManageModal } from '@/components/servers/ServerManageModal'
import { ServerConfirmModal } from '@/components/servers/ServerConfirmModal'
import { useT } from '@/i18n'
import type { SavedServer } from '@/api/client'

// ── Animations d'entrée de l'accueil ────────────────────────────────────────
// Le bloc de lancement monte sous la bannière, puis ses éléments se posent
// l'un après l'autre. Les cartes du pied de page font de même.
//
// Il glissait depuis le bord droit quand il était une colonne latérale ;
// empilé sous la bannière, un mouvement horizontal irait contre la
// disposition et ferait déborder la page le temps de l'animation.

const panelVariants: Variants = {
  initial: { opacity: 0, y: 16 },
  animate: {
    opacity: 1,
    y: 0,
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

/**
 * Les trois cartes montrées à la place des serveurs épinglés, quand il n'y en
 * a aucun.
 *
 * La première annonçait « Sync P2P — en connexion directe, aucun cloud, aucun
 * serveur tiers », et menait à l'écran de synchronisation. Deux problèmes :
 * elle décrivait une technique dont on ne parle pas, et elle contredisait la
 * page des fonctionnalités, où la sync est depuis peu annoncée « bientôt » et
 * fermée. Elle dit maintenant la même chose que l'autre écran, et n'emmène
 * nulle part tant que ce n'est pas ouvert (`soon`).
 */
function useFeatures(t: ReturnType<typeof useT>) {
  return [
    {
      title: t('home.features.syncTitle'),
      desc: t('home.features.syncDesc'),
      icon: <svg viewBox="0 0 24 24" fill="currentColor" width="1em" height="1em"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>,
      path: null,
      soon: true,
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

/**
 * Ombre décalée du bouton de lancement, au survol.
 *
 * Deux couches pleines posées en biais, façon impression mal calée — mais
 * dans le violet de l'application, pas dans le rose et le mauve de l'exemple
 * qui jureraient avec le reste des écrans. Le décalage reste court (3 puis
 * 6 px) : le bouton est large, une ombre portée plus franche le ferait
 * flotter au-dessus du pied de page.
 *
 * La lueur diffuse du repos est conservée en troisième couche, sinon le
 * bouton perdrait son halo à l'instant où on le survole.
 */
const LAUNCH_HOVER_SHADOW = [
  '3px 3px 0 rgba(139,92,246,0.55)',
  '6px 6px 0 rgba(75,63,207,0.45)',
  '0 6px 32px rgba(75,63,207,0.55)',
].join(', ')

/** Pourcentage à partir duquel on efface le launcher, faute d'agent. */
const HIDE_AT_PERCENT = 90

const STARS = Array.from({ length: 55 }, (_, i) => ({
  x: (i * 37 + ((i * 7 + 13) % 100) * 1.7) % 100,
  y: (i * 23 + ((i * 7 + 13) % 100) * 2.3) % 62,
  r: i % 4 === 0 ? 2 : 1,
  o: 0.15 + (i % 5) * 0.08,
}))

export default function Home() {
  const navigate = useNavigate()
  const t = useT()
  const FEATURES = useFeatures(t)
  const {
    username, uuid,
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
    isAgentEnabled, setAgentEnabled,
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
  /** Survol du bouton de lancement — pilote la comète (voir LaunchSweep). */
  const [launchHover, setLaunchHover] = useState(false)
  /** Pastille de l'entrée « Support » : les réponses de l'équipe non lues,
   *  plus les rapports de plantage dont le statut a changé sans qu'on soit
   *  allé le lire. Les deux mènent au même écran, donc au même point. */
  const supportUnread = useSupportWatch((s) => s.unread)
  const crashUpdates = useCrashWatch((s) => s.updates)
  const [savedServers, setSavedServers] = useState<SavedServer[]>([])
  const [showServerManage, setShowServerManage] = useState(false)
  const [pendingServer, setPendingServer] = useState<SavedServer | null>(null)
  /** Texture complète du skin d'un compte hors ligne, pour le rendu 3D. */
  const [customSkinUri, setCustomSkinUri] = useState<string | null>(null)
  const [showAgentModal, setShowAgentModal] = useState(false)
  /** `true` quand le client intégré ne peut pas se charger sur l'instance
   *  choisie — pastille d'attention sur son bouton. */
  const [agentBlocked, setAgentBlocked] = useState(false)

  const instance = selectedInstance()

  // Phrase d'accueil : tirée au sort une fois par lancement du launcher, pas
  // à chaque passage sur l'accueil (voir `lib/greeting.ts`). `playIntro` dit
  // s'il reste la séquence d'arrivée à jouer.
  const language = useStore((st) => st.language)
  //
  // Calculée PENDANT le rendu, pas dans un effet. Un effet ne s'exécute
  // qu'après la première peinture : le temps de celle-ci, `playIntro` valait
  // faux, donc la séquence se croyait déjà jouée et affichait le badge
  // terminé — cadre, pseudo, et un avatar encore en cours de chargement.
  // L'effet passait ensuite `playIntro` à vrai et tout repartait de zéro. On
  // voyait donc l'arrivée avant le départ, à chaque ouverture du launcher.
  //
  // `homeGreeting` retient déjà son tirage par langue et par pseudo : deux
  // appels de suite rendent la même phrase, elle est donc sûre à appeler
  // pendant le rendu.
  const welcome = useMemo(
    () => (username ? homeGreeting(language, username) : { phrase: '', playIntro: false }),
    [language, username],
  )

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

  // Skin enregistré du compte, pour la bannière — pour TOUS les comptes.
  //
  // Il était réservé aux comptes hors ligne, en supposant que le skin d'un
  // compte Microsoft serait de toute façon servi par mc-heads. C'est vrai, mais
  // avec le retard de leur cache : juste après un changement, la bannière
  // montrait encore l'ancien. Notre référence locale passe donc devant, et le
  // service reste le repli (voir SkinBust, qui rend l'apparence par défaut pour
  // un UUID inventé).
  //
  // La texture part telle quelle au rendu 3D. Elle était auparavant recadrée
  // ici sur la zone « visage » du gabarit pour en tirer une vignette carrée ;
  // ce découpage n'a plus d'objet depuis que l'accueil montre le skin entier,
  // et un rendu 3D veut de toute façon la texture complète.
  useEffect(() => {
    if (!uuid) { setCustomSkinUri(null); return }
    let cancelled = false
    skinPreview(uuid)
      .then((dataUri) => { if (!cancelled) setCustomSkinUri(dataUri ?? null) })
      .catch(() => { if (!cancelled) setCustomSkinUri(null) })
    return () => { cancelled = true }
  }, [uuid])

  // Le client intégré ne sait pas se charger sur toutes les versions (voir
  // `agent_compat.rs`, côté Rust, qui reste seul juge). L'accueil le demande
  // pour l'instance choisie afin de poser — ou non — la pastille d'attention
  // sur son bouton : sans elle, on ne découvrirait l'absence du client qu'une
  // fois en jeu, en cherchant un menu qui n'existe pas.
  useEffect(() => {
    if (!instance) { setAgentBlocked(false); return }
    let cancelled = false
    api.launch.agentStatus(instance.mc_version, instance.loader)
      .then((s) => { if (!cancelled) setAgentBlocked(!s.available) })
      .catch(() => { if (!cancelled) setAgentBlocked(false) })
    return () => { cancelled = true }
  }, [instance?.mc_version, instance?.loader])

  // Phase 2 ("lancement", 60-100%) — les téléchargements (backend) s'arrêtent
  // pile à 60%, le reste (démarrage JVM + chargement interne Minecraft
  // jusqu'au menu principal, voir game_ready) n'a pas de progression réelle
  // connue. On mesure la durée réelle à chaque lancement (voir game_ready
  // ci-dessous) et on la réutilise pour animer la barre les fois suivantes,
  // au lieu de la laisser figée à 60% pendant tout ce temps.
  // Un départ et un minuteur par instance en cours de lancement.
  const phase2StartRef = useRef<Record<string, number>>({})
  const phase2TimerRef = useRef<Record<string, ReturnType<typeof setInterval>>>({})

  // ── Effacement du launcher au lancement ──────────────────────────────────
  //
  // Le réglage « masquer au lancement » s'appliquait au clic sur « Jouer ».
  // La fenêtre disparaissait donc alors que le téléchargement n'avait souvent
  // même pas commencé : on se retrouvait devant un bureau vide pendant une
  // minute, sans rien pour dire que quelque chose se passait.
  //
  // Elle attend maintenant que le jeu soit vraiment en train d'arriver. Le
  // bon signal dépend de l'agent :
  //
  // - **avec agent** — il annonce lui-même le menu principal (`game_ready`).
  //   C'est le signal exact : la fenêtre de Minecraft est là.
  // - **sans agent** — aucun signal ne viendra jamais du jeu. On se rabat sur
  //   la barre : à 90 %, la JVM tourne depuis un moment et la fenêtre du jeu
  //   est sur le point de paraître.
  //
  // `armed` retient qu'un masquage a été demandé pour ce lancement-là : le
  // réglage peut changer, la personne peut changer d'instance, et un
  // lancement qui échoue ne doit rien masquer du tout.
  const hideArmedRef = useRef<Record<string, boolean>>({})
  const agentActiveRef = useRef<Record<string, boolean>>({})

  /** Masque le launcher, une seule fois par lancement. */
  const hideForLaunch = (instanceId: string) => {
    if (!hideArmedRef.current[instanceId]) return
    delete hideArmedRef.current[instanceId]
    // Confié au Rust : il ferme la fenêtre quand l'arrière-plan est autorisé
    // — ce qui rend vraiment la mémoire de la webview — et se contente de la
    // réduire sinon, puisque fermer couperait la surveillance de la partie.
    api.window.hideForLaunch(true).catch(() => {})
  }

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
    if (!remembered) {
      // Pas encore de mesure : la barre reste figée à 60 %, donc les 90 %
      // n'arriveront jamais. Sans agent pour dire « c'est prêt », le seul
      // repère qui reste est celui-ci — les téléchargements sont finis, la
      // JVM démarre. C'est plus tôt que voulu, mais très loin du clic.
      if (!agentActiveRef.current[instanceId]) hideForLaunch(instanceId)
      return
    }
    const previous = phase2TimerRef.current[instanceId]
    if (previous) clearInterval(previous)
    phase2TimerRef.current[instanceId] = setInterval(() => {
      const frac = Math.min(0.975, (Date.now() - start) / remembered)
      const percent = 60 + Math.round(frac * 40)
      setProgress(instanceId, { current: percent, total: 100, message: t('home.startingMinecraft') })
      // Sans agent, c'est ici — et nulle part ailleurs — que le masquage se
      // décide : la barre est la seule chose qui avance.
      if (percent >= HIDE_AT_PERCENT && !agentActiveRef.current[instanceId]) hideForLaunch(instanceId)
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
    // Un lancement qui s'arrête — erreur, annulation, fin de partie — ne doit
    // rien masquer : la fenêtre est justement ce qu'on veut revoir.
    delete hideArmedRef.current[instanceId]
    delete agentActiveRef.current[instanceId]
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
  // Émis par le backend une fois l'agent préparé, juste avant le démarrage de
  // la JVM : c'est ce qui dit à quel signal se fier pour masquer le launcher.
  useTauriEvent<{ instance_id: string; active: boolean }>('launch_agent', ({ instance_id, active }) => {
    agentActiveRef.current[instance_id] = active
  })

  useTauriEvent<{ instance_id: string }>('game_ready', ({ instance_id }) => {
    // Avec l'agent, c'est LE signal : le menu principal est à l'écran.
    hideForLaunch(instance_id)
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

  // `game_crashed` est écouté dans App.tsx, pas ici : il ouvre désormais une
  // modale qui propose d'envoyer le rapport, et cette modale doit s'afficher
  // même si on a quitté l'accueil entre-temps.

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

  // La déconnexion a quitté l'accueil avec la pastille de compte du pied de
  // page : elle vit désormais uniquement sur l'écran des comptes, où l'on
  // arrive par « Compte » dans la barre.

  const launch = async (connectServer?: string) => {
    // Figé ici : l'utilisateur peut changer d'instance pendant l'appel.
    const instanceId = selectedInstanceId
    if (!instanceId || gameRunning || !username) return
    setBannerPulse(true)
    setTimeout(() => setBannerPulse(false), 900)
    try {
      // Choix « avec / sans le client intégré », pris dans sa fenêtre et
      // retenu par instance. Le Rust l'ignore là où l'agent ne peut pas se
      // charger de toute façon.
      const useAgent = isAgentEnabled(instanceId)
      if (p2pEnabled && P2P_ENABLED) await api.launch.startP2p(instanceId, avoidBetaDependencies, showConsole, connectServer, useAgent)
      else await api.launch.start(instanceId, avoidBetaDependencies, showConsole, connectServer, useAgent)
      setInstanceRunning(instanceId, true)
      if (instance) setLastSession({ instanceName: instance.name, at: new Date().toISOString() })
      // On arme, on ne masque pas : l'effacement attend que le jeu soit
      // vraiment en train d'arriver (voir `hideForLaunch` plus haut). Le
      // réglage est lu maintenant et pas plus tard, pour que le changer en
      // cours de lancement n'ait pas d'effet rétroactif.
      if (closeOnLaunch) hideArmedRef.current[instanceId] = true
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

      {/* ── Zone principale ──
          Une seule colonne, plus deux panneaux côte à côte. La bannière
          prenait la largeur, le lancement prenait le reste, et sur un écran
          étroit le second devenait une bande verticale où plus rien ne
          tenait. Empilés et centrés, les deux blocs gardent la même forme
          quelle que soit la largeur ; c'est la hauteur, elle, qui défile. */}
      <div className="flex min-h-0 flex-[1_1_0] flex-col gap-[clamp(8px,1.8vh,18px)] overflow-y-auto p-[clamp(8px,1.7vh,16px)]">

        {/* Bannière — pleine largeur, hauteur bornée */}
        <motion.div
          // La bannière s'installe : elle arrive légèrement réduite puis se
          // pose, comme un écran qu'on allume.
          initial={{ opacity: 0, scale: 0.985 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.45, ease: EASE_OUT }}
          className="relative h-[clamp(182px,38vh,340px)] flex-shrink-0 overflow-hidden rounded-[20px] border border-[rgba(200,200,220,0.08)] shadow-[0_8px_40px_rgba(0,0,0,0.7)]"
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
              va chercher, c'est l'état normal de l'écran.

              Animé en Motion plutôt qu'en @keyframes Tailwind : les réglages
              d'accessibilité du système sont respectés d'un seul endroit
              (`MotionConfig reducedMotion="user"` dans main.tsx), ce qu'une
              animation CSS ignorait.

              Les amplitudes visent un entre-deux : l'aurore montait jusqu'à
              l'opacité pleine toutes les 2 s et les étoiles passaient par un
              filtre de luminosité ×5, ce qui fatiguait les yeux pendant qu'on
              lit le reste de la page. Le pic reste sous l'ancien et le
              cycle est deux fois plus long : le mouvement se voit
              franchement, sans le clignotement qui piquait. */}
          <motion.div
            animate={{ opacity: [0.35, 0.95, 0.35] }}
            transition={{ duration: 4.5, repeat: Infinity, ease: 'easeInOut' }}
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_38%_60%,rgba(90,70,255,0.56)_0%,rgba(75,63,207,0.28)_40%,transparent_70%)]"
          />

          <motion.div
            animate={{ opacity: [0.25, 1, 0.25], scale: [1, 1.015, 1] }}
            transition={{ duration: 3.4, repeat: Infinity, ease: 'easeInOut' }}
            className="absolute inset-0"
          >
            {STARS.map((s, i) => (
              <div key={i} className="absolute rounded-full" style={{ left: `${s.x}%`, top: `${s.y}%`, width: s.r, height: s.r, background: `rgba(255,255,255,${s.o})` }} />
            ))}
          </motion.div>

          {/* Flash violet au lancement */}
          {bannerPulse && (
            <div className="absolute inset-0 pointer-events-none animate-banner-flash rounded-[20px] z-10 bg-[radial-gradient(ellipse_at_50%_50%,rgba(160,130,255,0.95)_0%,rgba(90,70,255,0.6)_35%,transparent_72%)]" />
          )}

          <Skyline />

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

          {/* Marque + information, en haut à gauche de la bannière.
              Le titre vivait dans le panneau de droite, où il mangeait la
              hauteur dont le bouton de lancement avait besoin. Posé sur la
              bannière, il ne coûte rien : cette place était vide. */}
          <div className="absolute left-[clamp(12px,2vw,22px)] top-[clamp(10px,1.8vh,18px)] z-10 flex items-center gap-2">
            {/* Le titre respire : sa lueur enfle et retombe lentement. */}
            <motion.h1
              animate={{
                textShadow: [
                  '0 0 30px rgba(75,63,207,0.55)',
                  '0 0 44px rgba(75,63,207,0.8)',
                  '0 0 30px rgba(75,63,207,0.55)',
                ],
              }}
              transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut' }}
              className="font-black leading-none tracking-[-0.015em] text-white text-[clamp(26px,6vh,58px)]"
            >
              YuyuFrame
            </motion.h1>
            <motion.button
              whileHover={{ scale: 1.12, rotate: 8 }}
              whileTap={{ scale: 0.92 }}
              transition={SNAP}
              onClick={() => navigate('/information')}
              title={t('information.title')}
              // Grandit avec le titre : posé à côté d'un mot devenu nettement
              // plus gros, un bouton resté à sa taille aurait l'air perdu.
              className="flex h-[clamp(22px,3.4vh,32px)] w-[clamp(22px,3.4vh,32px)] items-center justify-center rounded-lg bg-transparent text-[rgba(255,255,255,0.3)] transition-colors duration-150 hover:bg-[rgba(255,255,255,0.08)] hover:text-[rgba(255,255,255,0.8)]"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-[70%] w-[70%]"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" /></svg>
            </motion.button>
          </div>

          <WelcomeSequence
            username={username}
            // L'UUID sert à demander le rendu de la tête ; le skin local
            // prend le relais pour un compte hors ligne, dont l'UUID est
            // inventé et ne correspond à rien côté service.
            uuid={username ? uuid : null}
            localSkin={customSkinUri}
            greeting={username && welcome.phrase ? welcome.phrase : t('home.welcomeNew')}
            playIntro={welcome.playIntro}
            onAvatarClick={() => navigate('/login')}
          />

          {/* La bannière attend que le badge soit posé : deux choses qui
              entrent en même temps, on ne regarde ni l'une ni l'autre. */}
          <HomeBanner banner={banner} visible={bannerReady} />
        </motion.div>

        {/* Bloc de lancement — sous la bannière, centré, largeur bornée.
            Il ne s'étire plus sur toute la page : passé une certaine largeur,
            un sélecteur et un bouton qui s'allongent indéfiniment se lisent
            moins bien, pas mieux. */}
        <motion.div
          // Le bloc monte après la bannière, ses éléments se posant l'un
          // après l'autre (variantes `panelItem` ci-dessous).
          variants={panelVariants}
          initial="initial"
          animate="animate"
          className="mx-auto flex w-full max-w-[560px] flex-shrink-0 flex-col"
        >

          {/* Le titre, le bouton d'information et l'avatar ont rejoint la
              bannière : ils y occupent une place qui était vide, au lieu de
              disputer sa hauteur au bouton de lancement. Ne reste ici que ce
              pour quoi on vient — choisir une instance et jouer. */}
          <motion.div variants={panelItem} className="w-full flex flex-shrink-0 flex-col gap-[clamp(6px,1.3vh,10px)]">

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
              // Le sélecteur et l'engrenage sur la même ligne : choisir une
              // instance et aller la configurer sont deux gestes voisins, et
              // la pastille d'informations qui suivait ne disait rien que le
              // sélecteur ne dise déjà.
              <div className="flex w-full items-center gap-2">
                {/* Le sélecteur répond en deux temps : il se soulève d'un
                    pixel, et son chevron descend — le geste qu'on s'apprête à
                    faire, annoncé avant le clic.
                    Les états sont nommés (`rest` / `hover`) plutôt qu'écrits
                    en objet : seuls les noms de variantes descendent aux
                    enfants, et c'est ce qui permet au chevron de réagir au
                    survol du bouton sans écouteur à lui. */}
                <motion.button
                  onClick={() => setShowInstanceSwitch(true)}
                  initial="rest"
                  animate="rest"
                  whileHover="hover"
                  whileTap={{ scale: 0.99 }}
                  variants={{ rest: { y: 0 }, hover: { y: -1 } }}
                  transition={SNAP}
                  className="relative flex h-[clamp(34px,6.4vh,48px)] min-w-0 flex-1 items-center justify-between rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3.5 text-sm font-medium text-white outline-none transition-colors duration-150 hover:border-[rgba(75,63,207,0.4)]"
                >
                  <span className="truncate">
                    {instance ? `${instance.name} — ${instance.mc_version} (${instance.loader})` : t('home.chooseInstance')}
                  </span>
                  <motion.svg
                    variants={{ rest: { y: 0, opacity: 0.45 }, hover: { y: 2, opacity: 0.95 } }}
                    transition={SNAP}
                    viewBox="0 0 10 6" fill="white" width={10} height={6} className="ml-2 flex-shrink-0"
                  >
                    <path d="M0 0l5 6 5-6z" />
                  </motion.svg>
                </motion.button>

                {/* Cet engrenage menait à la page des mods — un raccourci qui
                    ne manquait à personne, la barre y mène déjà. Il ouvre
                    maintenant la fenêtre du client intégré : c'était la seule
                    fonctionnalité du launcher dont on ne parlait nulle part,
                    elle s'injectait au lancement sans jamais se présenter. */}
                <motion.button {...press}
                  onClick={() => setShowAgentModal(true)}
                  disabled={!instance}
                  title={t('agent.title')}
                  className="relative flex h-[clamp(34px,6.4vh,48px)] w-[clamp(34px,6.4vh,48px)] flex-shrink-0 items-center justify-center rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] text-[rgba(255,255,255,0.45)] transition-colors duration-150 hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(255,255,255,0.85)] disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" className="h-[45%] w-[45%]">
                    <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" />
                  </svg>
                  {/* Pastille d'attention : le client ne se chargera pas sur
                      cette version. Posée sur le bouton qui l'explique, pour
                      qu'il n'y ait qu'un geste à faire pour savoir pourquoi. */}
                  {agentBlocked && (
                    <span className="absolute -right-1 -top-1 flex h-[14px] w-[14px] items-center justify-center rounded-full bg-bg-primary">
                      <WarningIcon className="h-full w-full text-warning" />
                    </span>
                  )}
                </motion.button>
              </div>
            )}

          </div>

          {/* Bouton de lancement — l'annulation est une pastille « Annuler »
              intégrée sous le texte « EN JEU… » plutôt qu'un bouton rond
              séparé, pour ne pas casser la forme du bouton principal.
              Pleine largeur de la colonne : c'est le geste de l'écran, il n'a
              pas à se faire plus petit que ce qui le précède. */}
          {/* Plus de marge propre ici : le `gap` du groupe ci-dessus espaçait
              déjà le sélecteur du bouton, et les deux s'additionnaient — d'où
              un vide deux fois trop grand entre les deux. */}
          <div className="flex w-full gap-2">
            {gameRunning ? (
              <div
                // Même rayon que le bouton de lancement : c'est le même
                // emplacement, il ne doit pas changer de forme quand la
                // partie démarre.
                className={`relative overflow-hidden font-bold text-white transition-all duration-200 flex-1 flex flex-col items-center justify-center gap-[clamp(3px,0.8vh,6px)] rounded-xl text-[clamp(11px,1.7vh,13px)] tracking-[0.04em] py-[clamp(5px,1.4vh,10px)] ${launchBtnBg} ${launchBtnShadow}`}
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
              <span className="relative flex flex-1">

              {/* La respiration du repos, sortie du bouton.
                  Elle était jouée sur son `box-shadow` : une suite d'images
                  clés de 3,2 s en boucle. Sortir du survol renvoyait donc
                  l'ombre à cette boucle — et une suite d'images clés reprend
                  toujours du début, pour sa durée entière. L'ombre colorée
                  mettait trois secondes à s'effacer.
                  Ici c'est une lueur posée DERRIÈRE le bouton, qui pulse dans
                  son coin. Le `box-shadow` du bouton ne sert plus qu'au
                  survol, et rien ne lui dispute sa valeur. */}
              {canLaunch && !progress && (
                <motion.span
                  aria-hidden
                  animate={{ opacity: [0.25, 0.7, 0.25] }}
                  transition={{ duration: 3.2, repeat: Infinity, ease: 'easeInOut' }}
                  className="pointer-events-none absolute inset-x-3 inset-y-1.5 rounded-xl bg-[#4B3FCF] blur-[16px]"
                />
              )}

              <motion.button
                onClick={username ? handleLaunch : () => navigate('/login')}
                disabled={!!username && !selectedInstanceId}
                // Le bouton le plus important de l'app : il se soulève au
                // survol et s'enfonce au clic.
                transition={{ scale: SNAP, skewX: SNAP, boxShadow: { duration: 0.18, ease: 'easeOut' } }}
                // Le penché de l'exemple, mais piloté par le ressort plutôt
                // que par une transition CSS : les deux se disputeraient la
                // même `transform` que l'échelle ci-dessus, et le survol
                // répondrait mou.
                whileHover={
                  canLaunch
                    ? { scale: 1.03, skewX: -8, boxShadow: LAUNCH_HOVER_SHADOW }
                    : { scale: 1.03, skewX: -8 }
                }
                whileTap={{ scale: 0.96, skewX: -8 }}
                onHoverStart={() => setLaunchHover(true)}
                onHoverEnd={() => setLaunchHover(false)}
                // `transition-colors`, surtout pas `transition-all` : celui-ci
                // faisait aussi transiter `transform` et `box-shadow` — les
                // deux propriétés que framer écrit en ligne à chaque image.
                // Le navigateur interpolait donc par-dessus l'interpolation,
                // avec 200 ms de retard sur chaque valeur : d'où le survol
                // qui traînait à l'aller comme au retour.
                className={`relative overflow-hidden font-bold text-white transition-colors duration-200 h-[clamp(38px,6.6vh,52px)] flex-1 flex-shrink-0 rounded-xl text-[clamp(12px,1.8vh,16px)] tracking-[0.05em] disabled:cursor-not-allowed cursor-pointer ${launchBtnBg} ${launchBtnShadow}`}
              >
                {/* Seulement quand le bouton lance vraiment : une comète sur
                    un bouton désactivé ou déjà en cours promettrait une
                    action qui n'arrivera pas. */}
                {canLaunch && !progress && <LaunchSweep active={launchHover} />}

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
              </span>
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

        {/* Zone principale — sa hauteur est celle de son contenu.
            `flex-1` la faisait s'étirer sur la hauteur du pied de page, qui
            lui-même était dicté par les colonnes latérales et leurs pastilles
            à hauteur fixe : le résultat ne suivait plus rien. Chaque élément
            se met maintenant à l'échelle pour son compte, et le pied de page
            fait la somme. */}
        <div className="flex flex-col gap-[clamp(8px,1.6vh,16px)]">

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
          {/* L'offre ouvre la rangée, au format des cartes voisines. */}
          <motion.div variants={cardItem} className="flex flex-1">
            <ProCard onOpen={() => navigate('/plans')} />
          </motion.div>

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
                  {/* Même mot que la page des fonctionnalités : deux écrans
                      qui parlent de la même chose doivent la dater pareil. */}
                  {'soon' in f && f.soon && (
                    <span className="flex-shrink-0 rounded-full bg-[rgba(255,255,255,0.06)] px-1.5 py-px text-[clamp(7px,0.95vh,8.5px)] font-bold uppercase tracking-wider text-[rgba(255,255,255,0.3)]">
                      {t('features.soonBadge')}
                    </span>
                  )}
                </div>
                <p className="text-[clamp(8px,1.15vh,10px)] text-[rgba(255,255,255,0.28)] leading-[1.55] m-0">
                  {f.desc}
                </p>
              </motion.div>
            ))
          )}
        </motion.div>

        {/* Barre de navigation — seule occupante de sa ligne.
            overflow-x-auto en filet de sécurité : si les 7 liens ne tiennent
            plus même à leur taille clamp() minimale, la ligne devient
            défilable au lieu de couper les derniers. */}
        <div className="flex overflow-x-auto">

          {/* La marque, l'accroche et la pastille de compte ont quitté cette
              ligne : le nom est déjà sur la bannière, et « Compte » dans la
              barre mène à l'écran qui gère les comptes, déconnexion comprise.
              Le pied de page n'a plus qu'un rôle : naviguer. */}
          {/* `w-full` obligatoire : `container-type: inline-size` rend la
              largeur indépendante du contenu, donc sans largeur définie la
              barre s'effondrerait. Elle la tenait de sa colonne de grille
              avant, elle la tient de la ligne maintenant. */}
          {/* L'écart suit la largeur disponible, au lieu des 8 px fixes qui
              tassaient sept liens devenus plus grands. Il reste borné : au
              delà, la barre se disloque en éléments isolés. */}
          <div className="flex w-full min-w-0 items-center justify-center gap-[clamp(4px,0.65vw,16px)] py-[clamp(0px,0.8vh,10px)] [container-type:inline-size]">
            <NavLink label={t('home.nav.instances')} onClick={() => navigate('/instances')} distance={3}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 16.5c0 .38-.21.71-.53.88l-7.9 4.44c-.16.12-.36.18-.57.18s-.41-.06-.57-.18l-7.9-4.44A1 1 0 013 16.5v-9c0-.38.21-.71.53-.88l7.9-4.44c.16-.12.36-.18.57-.18s.41.06.57.18l7.9 4.44c.32.17.53.5.53.88v9z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.settings')} onClick={() => navigate('/settings')} distance={2}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" /></svg>
            </NavLink>
            {/* Sync et Stats ne sont plus dans la barre : elles vivent
                désormais dans la page Fonctionnalités, qui les regroupe avec
                celles à venir. */}
            <NavLink label={t('home.nav.features')} onClick={() => navigate('/features')} distance={1} accent>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.plans')} onClick={() => navigate('/plans')} distance={0} plans>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.support')} onClick={() => navigate('/support')} distance={1} accent badge={supportUnread + crashUpdates}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a9 9 0 00-9 9v5a3 3 0 003 3h1a1 1 0 001-1v-5a1 1 0 00-1-1H5v-1a7 7 0 1114 0v1h-2a1 1 0 00-1 1v5a1 1 0 001 1h1a3 3 0 003-3v-5a9 9 0 00-9-9z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.account')} onClick={() => navigate('/login')} distance={2}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.7 0 4.8-2.1 4.8-4.8S14.7 2.4 12 2.4 7.2 4.5 7.2 7.2 9.3 12 12 12zm0 2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z" /></svg>
            </NavLink>
            <NavLink label={t('home.nav.server')} onClick={() => navigate('/server')} distance={3}>
              <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
            </NavLink>
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

      {showAgentModal && instance && (
        <AgentModal
          mcVersion={instance.mc_version}
          loader={instance.loader}
          enabled={isAgentEnabled(instance.id)}
          onChange={(value) => setAgentEnabled(instance.id, value)}
          onClose={() => setShowAgentModal(false)}
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


/**
 * Les quatre étages de la pyramide, taille de police seulement.
 *
 * Un lien n'a plus de hauteur imposée : sa police décide de tout le reste —
 * marges intérieures, écart texte/icône et icône sont exprimés en `em` dans
 * le composant. Avant, la hauteur venait de `vh` et la police de `cqw` : deux
 * mesures sans rapport l'une avec l'autre, d'où des boutons hauts au texte
 * minuscule dès que la fenêtre était large et basse, et une barre qui ne
 * remplissait jamais sa ligne.
 *
 * L'unité de référence est la largeur de la fenêtre — c'est elle qui décide
 * si sept liens tiennent côte à côte. Le plancher est haut (11,5 px sur les
 * liens extérieurs) : en dessous, la barre devient illisible bien avant de
 * manquer de place.
 *
 * `max(vw, vh)` et non `vw` seul : la bannière et le bouton de lancement ont
 * été réduits, donc le pied de page dispose désormais de hauteur en trop. La
 * barre s'en sert — sur une fenêtre haute mais étroite, c'est la hauteur qui
 * commande, et les liens grossissent au lieu de laisser ce vide. Le plafond
 * reste là pour qu'ils ne deviennent pas des pavés sur un grand écran.
 */
const NAV_SIZE_CLASSES = [
  'text-[clamp(13px,max(1.32vw,2.35vh),24px)] font-[700]',
  'text-[clamp(12.5px,max(1.20vw,2.15vh),21.5px)] font-[650]',
  'text-[clamp(12px,max(1.08vw,1.94vh),19.5px)] font-[600]',
  'text-[clamp(11.5px,max(0.97vw,1.74vh),17.5px)] font-[550]',
]

/** Temps passé sur un même lien avant que le repère ne s'étire. */
function NavLink({ label, onClick, plans, accent, distance = 0, badge = 0, children }: {
  label: string; onClick: () => void; plans?: boolean; accent?: boolean; distance?: number
  /** Nombre d'éléments en attente sur cet écran. 0 = rien à signaler. */
  badge?: number
  children: React.ReactNode
}) {
  // Tailles relatives à la fenêtre via clamp — s'adaptent à toutes les largeurs
  // Unités cqw : relatives à la largeur réellement disponible pour la nav (container query),
  // plutôt qu'à la largeur de toute la fenêtre — la nav s'adapte donc à la place qui lui est laissée.
  const d = Math.min(distance, 3)
  const baseColorClass = plans ? 'text-[#c4b5fd]' : 'text-[rgba(255,255,255,0.9)]'
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
      // La pyramide se monte depuis son sommet : le lien central d'abord, les
      // autres en s'écartant. C'est la forme de la barre qui donne l'ordre,
      // pas celui du code.
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0, transition: { duration: 0.36, ease: EASE_OUT, delay: 0.08 + d * 0.05 } }}
      whileHover={{ y: -3 }}
      whileTap={{ scale: 0.95 }}
      // Ressort raide et léger : le survol d'un lien de nav doit répondre
      // tout de suite, pas accompagner le curseur.
      transition={SNAP}
      // `transition-colors`, surtout pas `transition-all` : celui-ci animait
      // aussi `transform`, la propriété que le ressort ci-dessus pilote déjà.
      // Les deux se disputaient la même valeur à chaque image et le survol
      // répondait mou. Le CSS ne garde que ce que framer ne touche pas.
      // Marges, écart et icône en `em` : tout suit la police de l'étage, donc
      // la hauteur du bouton est celle de son contenu et jamais un nombre posé
      // à côté.
      className={`group relative flex items-center gap-[0.45em] rounded-xl px-[0.85em] py-[0.62em] transition-colors duration-150 whitespace-nowrap cursor-pointer ${NAV_SIZE_CLASSES[d]} ${baseColorClass} ${bgBorderShadow} ${hoverClasses}`}
    >
      <span className={`relative flex h-[1.35em] w-[1.35em] flex-shrink-0 transition-transform duration-150 ease-out group-hover:scale-110 ${plans ? 'text-[#a78bfa]' : 'text-inherit'}`}>
        {children}
        {/* Posée sur l'icône et non à côté du libellé : elle reste au même
            endroit quelle que soit la longueur du mot, dans une barre dont
            les sept entrées n'ont pas la même largeur. Dimensions en `em`,
            comme le reste du bouton — elle suit la taille de la barre. */}
        {badge > 0 && (
          <span className="absolute -right-[0.35em] -top-[0.3em] flex h-[0.85em] min-w-[0.85em] items-center justify-center rounded-full bg-[#ef4444] px-[0.18em] text-[0.62em] font-bold leading-none text-white shadow-[0_0_0_0.12em_rgba(9,9,13,0.9)]">
            {badge > 9 ? '9+' : badge}
          </span>
        )}
      </span>
      {label}
    </motion.button>
  )
}
