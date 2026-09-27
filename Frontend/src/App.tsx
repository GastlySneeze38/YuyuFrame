import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { TitleBar } from '@/components/TitleBar'
import { UpdateChecker } from '@/components/UpdateChecker'
import { ErrorToast } from '@/components/ui/ErrorToast'
import { FleetNotices } from '@/components/FleetNotices'
import { PasswordChangeModal } from '@/components/account/PasswordChangeModal'
import { PageTransition } from '@/components/PageTransition'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { PlanGate } from '@/components/PlanGate'
import { ModalQueueHost } from '@/components/ModalQueueHost'
import { JoinServerModal, type JoinRequest } from '@/components/servers/JoinServerModal'
import { useStore, type Lang } from '@/stores/useStore'
import { ipLanguage, systemLanguage } from '@/i18n/detect'
import { useModalQueue } from '@/stores/useModalQueue'
import { useFleet } from '@/stores/useFleet'
import { useSupportWatch, POLL_MS as SUPPORT_POLL_MS } from '@/stores/useSupportWatch'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { AUTH_SYSTEM_VERSION } from '@/config/authVersion'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { parseJoinUrl } from '@/lib/joinLink'

// Chargées à la demande — évite de tout regrouper dans un seul chunk JS au
// premier chargement (pages secondaires comme Legal/Information/Stats
// n'ont pas besoin d'être prêtes avant que l'utilisateur les visite).
//
// Chaque page garde son import dans `PAGE_IMPORTS` : une fois la première
// page à l'écran, on les charge toutes en tâche de fond (`preloadPages`).
// Sans ça, chaque première visite suspendait le rendu le temps d'aller
// chercher son morceau de JS, et l'écran d'attente clignotait 50 ms — assez
// pour se voir, pas assez pour vouloir le montrer.
const PAGE_IMPORTS = [
  () => import('@/pages/Login'),
  () => import('@/pages/Home'),
  () => import('@/pages/Instances'),
  () => import('@/pages/Mods'),
  () => import('@/pages/Settings'),
  () => import('@/pages/Information'),
  () => import('@/pages/Legal'),
  () => import('@/pages/YuyuLogin'),
  () => import('@/pages/Sync'),
  () => import('@/pages/SyncInstance'),
  () => import('@/pages/Features'),
  () => import('@/pages/Support'),
  () => import('@/pages/Plans'),
  () => import('@/pages/Stats'),
  () => import('@/pages/Backup'),
  () => import('@/pages/Server'),
  () => import('@/pages/JvmProfiles'),
  () => import('@/pages/JvmProfileEditor'),
]

/** Parties (sur trente jours) à partir desquelles on ose demander un avis. */
const REVIEW_MIN_SESSIONS = 10
/** Délai après un plantage pendant lequel on ne demande rien à personne. */
const REVIEW_CRASH_GRACE_MS = 20_000

/** Charge les pages restantes quand le navigateur n'a rien de mieux à faire. */
function preloadPages() {
  const run = () => PAGE_IMPORTS.forEach((load) => void load().catch(() => {}))
  if ('requestIdleCallback' in window) window.requestIdleCallback(run, { timeout: 3000 })
  else setTimeout(run, 1500)
}

const Login = lazy(() => import('@/pages/Login'))
const Home = lazy(() => import('@/pages/Home'))
const Instances = lazy(() => import('@/pages/Instances'))
const Mods = lazy(() => import('@/pages/Mods'))
const Settings = lazy(() => import('@/pages/Settings'))
const Information = lazy(() => import('@/pages/Information'))
const Legal = lazy(() => import('@/pages/Legal'))
const YuyuLogin = lazy(() => import('@/pages/YuyuLogin'))
const Console = lazy(() => import('@/pages/Console'))
const Sync = lazy(() => import('@/pages/Sync'))
const Features = lazy(() => import('@/pages/Features'))
const Support = lazy(() => import('@/pages/Support'))
const Plans = lazy(() => import('@/pages/Plans'))
const Stats = lazy(() => import('@/pages/Stats'))
const Backup = lazy(() => import('@/pages/Backup'))
const SyncInstance = lazy(() => import('@/pages/SyncInstance'))
const Server = lazy(() => import('@/pages/Server'))
const JvmProfiles = lazy(() => import('@/pages/JvmProfiles'))
const JvmProfileEditor = lazy(() => import('@/pages/JvmProfileEditor'))
// Atelier des modales. Le `import()` est DANS la branche de développement,
// pas seulement la route : écrit dehors, Vite voyait un module importable et
// en sortait un morceau de JS livré à tout le monde — mort, mais livré.
// Ici, `import.meta.env.DEV` vaut `false` à la compilation, la branche
// disparaît, et le fichier avec elle.
const DevModals = import.meta.env.DEV ? lazy(() => import('@/pages/DevModals')) : null

// Rien à montrer : une page qui n'est pas encore là laisse sa place vide le
// temps d'un souffle. Un aplat sombre, lui, se voyait passer.
const RouteFallback = null

const label = getCurrentWindow().label
const isConsoleWindow = label.startsWith('mc-console-')

export default function App() {
  const { brightness, instanceSyncMode, setInstances, uuid, authSystemVersion, setAuthSystemVersion, setUser, setInstanceRunning, applyInstanceIdMigrations, setApiOnline, allowBackground, syncGameSettings } = useStore()
  // Mot de passe provisoire donné par le support : la modale s'impose tant
  // qu'il n'est pas changé (le serveur refuse tout le reste).
  const applyDetectedLanguage = useStore((s) => s.applyDetectedLanguage)
  const passwordResetRequired = useStore((s) => s.yuyuPasswordResetRequired)
  // Les trois modales de démarrage ne sont plus des drapeaux locaux : elles
  // sont déposées dans la file, qui décide de l'ordre et n'en montre qu'une
  // à la fois (voir stores/useModalQueue.ts).
  const pushModal = useModalQueue((s) => s.push)
  const navigate = useNavigate()
  const yuyuSignedIn = useStore((s) => s.yuyuSignedIn)
  const refreshSupportWatch = useSupportWatch((s) => s.refresh)
  const clearSupportWatch = useSupportWatch((s) => s.clear)
  const [joinRequest, setJoinRequest] = useState<JoinRequest | null>(null)
  // Calculé une seule fois au montage (avant tout re-render) — comparé puis
  // consommé dans les callbacks de démarrage ci-dessous, jamais relu après.
  const needsReconnectRef = useRef(authSystemVersion < AUTH_SYSTEM_VERSION)
  // Décidé une fois la liste des comptes chargée (voir plus bas).
  const offlineReminderRef = useRef(false)
  /** Horodatage du dernier plantage signalé — voir `considerReviewPrompt`. */
  const lastCrashAt = useRef(0)

  /**
   * Demande d'avis, après une partie terminée normalement.
   *
   * Le délai n'est pas cosmétique : `game_crashed` et `game_state(false)`
   * partent de la même fin de partie, dans un ordre que rien ne garantit. En
   * décidant tout de suite, on demanderait son avis à quelqu'un dont le jeu
   * vient de planter, une demi-seconde avant que le plantage ne soit connu.
   */
  const considerReviewPrompt = () => {
    if (isConsoleWindow) return
    window.setTimeout(() => {
      if (Date.now() - lastCrashAt.current < REVIEW_CRASH_GRACE_MS) return
      api.stats.get()
        .then((stats) => {
          // Le seuil porte sur les trente derniers jours : quelqu'un qui joue
          // régulièrement en ce moment a un avis, celui qui a lancé dix
          // parties il y a deux ans n'en a plus.
          if (stats.totals.sessions < REVIEW_MIN_SESSIONS) return
          pushModal({
            kind: 'review',
            // Clé fixe : la demande est posée une fois pour toutes, refus
            // compris. C'est une question, pas un rappel.
            key: 'review-prompt',
            // …sauf sans compte connecté : la fenêtre ne peut alors rien
            // proposer d'autre que d'aller se connecter (l'avis est signé du
            // compte YuyuFrame). La retenir comme « posée » brûlerait
            // l'unique demande sur une réponse qu'on n'a pas laissé donner.
            remember: useStore.getState().yuyuSignedIn,
            data: { sessions: stats.totals.sessions },
          })
        })
        .catch(() => {})
    }, 2500)
  }

  // Monté pour toute la durée de vie de la fenêtre principale — contrairement
  // à l'ancien listener posé uniquement dans Home.tsx, qui se désabonnait dès
  // qu'on naviguait ailleurs (Instances, Réglages...). Le jeu pouvait alors se
  // fermer pendant qu'on était sur une autre page : personne ne recevait
  // `game_state`, le store gardait `running: true` indéfiniment et le bouton
  // "EN JEU..." restait bloqué jusqu'à un F5 complet qui réinitialise le store.
  // Extraits des écouteurs pour être rejoués tels quels : une partie finie
  // pendant que la fenêtre était fermée arrive par la boîte aux lettres
  // plutôt que par un événement (voir `replayMissedEvents`). Deux chemins,
  // un seul traitement — sinon l'un des deux finit par oublier une étape.
  const onGameState = ({ running, instance_id }: { running: boolean; instance_id: string }) => {
    setInstanceRunning(instance_id, running)
    // Le Rust remonte déjà la fenêtre à la fin d'une partie (voir
    // `restore_after_game`) : cet appel n'est qu'un filet pour les fenêtres
    // masquées autrement, et il ne coûte rien quand elle est déjà visible.
    if (!running) getCurrentWindow().show().catch(() => {})
    if (!running) considerReviewPrompt()
  }

  const onGameCrashed = ({ instance_id, report_id, title, kind }: {
    instance_id: string; report_id: string; title: string; kind: string
  }) => {
    lastCrashAt.current = Date.now()
    pushModal({
      kind: 'crash',
      // Un rapport donné ne se propose qu'une fois, même après un
      // redémarrage : la clé est celle du rapport.
      key: `crash-${report_id}`,
      data: { reportId: report_id, instanceId: instance_id, title, cause: kind },
    })
  }

  // Monté pour toute la durée de vie de la fenêtre principale — contrairement
  // à l'ancien listener posé uniquement dans Home.tsx, qui se désabonnait dès
  // qu'on naviguait ailleurs (Instances, Réglages...). Le jeu pouvait alors se
  // fermer pendant qu'on était sur une autre page : personne ne recevait
  // `game_state`, le store gardait `running: true` indéfiniment et le bouton
  // "EN JEU..." restait bloqué jusqu'à un F5 complet qui réinitialise le store.
  useTauriEvent<{ running: boolean; instance_id: string }>('game_state', onGameState)

  // Le jeu s'est fermé tout seul. Le rapport est déjà écrit sur le disque
  // (voir minecraft::crash) : la modale ne fait que demander quoi en faire.
  //
  // Écouté ici et non dans Home : la fenêtre peut être sur n'importe quel
  // écran au moment du plantage — ou masquée, si « fermer au lancement » est
  // actif. La file garde alors la demande jusqu'à ce qu'on revienne.
  useTauriEvent<{ instance_id: string; report_id: string; title: string; kind: string }>(
    'game_crashed',
    onGameCrashed,
  )

  /**
   * Rattrapage au montage : parties en cours et événements manqués.
   *
   * Deux trous se refermaient ici, tous deux dus au même fait — « masquer au
   * lancement » ne cache pas la fenêtre, il la FERME, et la webview part avec
   * elle :
   *
   * - la liste des instances en cours ne vivait que dans le magasin de la
   *   page ; la fenêtre recréée repartait vide et proposait de relancer une
   *   instance déjà en train de tourner. Le Rust, lui, n'a rien oublié ;
   * - la fin de partie et le plantage étaient annoncés pendant que personne
   *   n'écoutait, donc ni la modale de plantage ni la demande d'avis
   *   n'arrivaient jamais. Le Rust les a mis de côté.
   *
   * L'ordre compte : les parties en cours d'abord, puis les événements, qui
   * peuvent justement dire qu'une partie s'est terminée.
   */
  useEffect(() => {
    if (isConsoleWindow) return
    api.launch.running()
      .then((ids) => ids.forEach((id) => setInstanceRunning(id, true)))
      .catch(() => {})
      .then(() => api.pending.take())
      .then((events) => {
        for (const event of events ?? []) {
          if (event.name === 'game_state') onGameState(event.payload as Parameters<typeof onGameState>[0])
          else if (event.name === 'game_crashed') onGameCrashed(event.payload as Parameters<typeof onGameCrashed>[0])
        }
      })
      .catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
   * Langue du premier démarrage — voir `i18n/detect.ts`.
   *
   * Deux temps volontairement : la langue du système s'applique tout de
   * suite, puis le pays d'où l'on se connecte la corrige s'il dit autre
   * chose. Sans ce premier temps, l'interface s'afficherait en français le
   * temps d'un aller-retour réseau.
   *
   * Tourne à chaque démarrage tant que l'utilisateur n'a pas choisi sa langue
   * dans les réglages : un premier lancement hors ligne ne doit pas le figer
   * dans une langue qu'il n'a pas demandée.
   */
  useEffect(() => {
    if (isConsoleWindow || useStore.getState().languagePicked) return
    let alive = true
    const apply = (lang: Lang | null) => {
      // Une langue choisie entre-temps dans les réglages a toujours le
      // dernier mot : la détection arrive après, elle ne doit pas l'écraser.
      if (lang && alive && !useStore.getState().languagePicked) applyDetectedLanguage(lang)
    }
    apply(systemLanguage())
    ipLanguage().then(apply)
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Lien yuyuframe://join?... — bouton "Rejoindre" de la Rich Presence
  // Discord d'un ami (voir discord.rs::build_join_url). Deux chemins
  // d'arrivée, voir commands::deep_link côté Rust :
  // - app pas encore ouverte : l'URL était dans les arguments de lancement,
  //   récupérée ici au montage via take_pending_deep_link (mailbox one-shot,
  //   pas un event, pour ne pas dépendre d'un timing de montage React) ;
  // - app déjà ouverte : `tauri_plugin_single_instance` réémet directement
  //   l'event `deep_link_join` (pas de risque de le perdre, ce listener est
  //   monté depuis longtemps).
  useEffect(() => {
    if (isConsoleWindow) return
    api.deepLink.takePending().then((url) => {
      if (url) setJoinRequest(parseJoinUrl(url))
    }).catch(() => {})
  }, [])

  // Le réglage « continuer en arrière-plan » vit dans le magasin persisté,
  // mais c'est la boucle d'événements de Tauri qui décide de laisser ou non
  // le processus s'éteindre — elle est synchrone et ne peut rien lire. On le
  // lui pousse donc dès le démarrage, puis à chaque changement (Settings).
  useEffect(() => {
    if (isConsoleWindow) return
    api.window.setBackgroundAllowed(allowBackground).catch(() => {})
  }, [allowBackground])

  // Même raison pour « synchroniser les paramètres Minecraft » : c'est le
  // backend qui applique le modèle à chaque instance créée, quel que soit
  // l'écran d'où vient la création. Il lui faut donc sa propre copie du
  // réglage — avant, chaque appelant devait y penser, et celui de la
  // restauration cloud ne le faisait pas.
  useEffect(() => {
    if (isConsoleWindow) return
    api.instances.setSyncGameSettings(syncGameSettings).catch(() => {})
  }, [syncGameSettings])

  useTauriEvent<string>('deep_link_join', (url) => {
    setJoinRequest(parseJoinUrl(url))
  })

  // Les trois demandes de démarrage sont déposées sans se soucier les unes
  // des autres : l'ordre appartient à la file, plus à ce bloc. C'est le seul
  // changement de fond ici — avant, chaque branche devait connaître les
  // suivantes, et la chaîne se recopiait dans les `onClose`.
  //
  // `isOffline` du store est un instantané persisté (voir partialize dans
  // useStore.ts) qui n'est resynchronisé qu'en repassant par Login/YuyuLogin
  // — au démarrage normal, l'app route direct vers /home sans jamais
  // revalider ce flag. Un `isOffline: true` persisté un jour (test du
  // compte hors ligne, switch antérieur...) redéclenchait donc le rappel à
  // chaque lancement même une fois de retour sur un compte Microsoft en
  // ligne. On revalide contre le compte actif réel avant de décider.
  const queueStartupModals = (hasAccount: boolean) => {
    if (needsReconnectRef.current && hasAccount) {
      pushModal({ kind: 'reconnect', key: `reconnect-${AUTH_SYSTEM_VERSION}`, data: null })
    }
    if (offlineReminderRef.current) {
      // `remember: false` : ce n'est pas une nouvelle à annoncer une fois mais
      // un état à rappeler tant qu'il dure. Retenue pour toujours, la modale
      // ne serait vue qu'au tout premier lancement en compte hors ligne.
      pushModal({ kind: 'offlineReminder', key: 'offline-reminder', data: null, remember: false })
    }
  }

  useEffect(() => {
    if (isConsoleWindow) return
    // Marqué comme vu tout de suite (best-effort, comme pendingPatchNotes) —
    // un crash entre ce marquage et l'affichage effectif de la modale plus
    // bas ne redéclenchera pas ReconnectModal au prochain lancement, mais
    // évite surtout de la répéter à chaque démarrage une fois vue une fois.
    if (needsReconnectRef.current) setAuthSystemVersion(AUTH_SYSTEM_VERSION)
    api.mc.accounts()
      .then((accs) => {
        const active = accs.find((a) => a.is_active)
        if (active) setUser(active.mc_username, active.mc_uuid, active.is_offline)
        // Un seul compte Microsoft enregistré prouve que le jeu est acheté :
        // pas de rappel d'achat, même si le compte actif est hors ligne.
        offlineReminderRef.current = !!active?.is_offline && !accs.some((a) => !a.is_offline)
        queueStartupModals(!!active)
      })
      .catch((e) => {
        // Sans la liste des comptes, impossible de savoir si le jeu est
        // acheté : pas de rappel d'achat plutôt qu'un rappel à tort.
        console.error('[App] liste des comptes Minecraft :', e)
        queueStartupModals(!!uuid)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (isConsoleWindow) return
    // Migration one-shot des ids d'instance legacy → nouveau format lisible
    // (voir migrate_legacy_instance_ids côté backend, lib.rs) — remappe les
    // clés persistées ici (favoris, mods épinglés...) qui référencent encore
    // l'ancien id, AVANT de charger la liste d'instances : sans ça,
    // `selectedInstanceId` ne correspondrait plus à rien dans la liste
    // fraîchement rechargée et l'accueil se retrouverait sans instance
    // sélectionnée après la mise à jour. Best-effort : une erreur ici ne
    // bloque jamais le chargement des instances qui suit.
    api.instances.getIdMigrations()
      .then(applyInstanceIdMigrations)
      .catch(() => {})
      .finally(() => {
        api.instances.startupSync(instanceSyncMode)
          .then(() => api.instances.list())
          .then(setInstances)
          .catch(showError)
      })

    // Rafraîchit le token Minecraft au démarrage et périodiquement — sinon
    // le seul refresh qui se produisait était celui déclenché par
    // launch_game/mc_switch, donc un token expiré pouvait rester invalide
    // pendant toute une session passée sans lancer de jeu.
    api.auth.status().catch(() => {})
    const interval = setInterval(() => {
      api.auth.status().catch(() => {})
    }, 10 * 60 * 1000)
    return () => clearInterval(interval)
  }, [])

  // Connectivité LauncherAPI — purement indicative (petit badge dans la
  // TitleBar, voir apiOnline dans useStore), jamais de blocage ni de toast :
  // `yuyu_ping` ne rejette jamais (voir commands::account::yuyu::yuyu_ping),
  // donc pas de .catch() nécessaire ici.
  useEffect(() => {
    if (isConsoleWindow) return
    const ping = () => api.yuyu.ping().then(setApiOnline)
    ping()
    const interval = setInterval(ping, 45 * 1000)
    return () => clearInterval(interval)
  }, [])

  /**
   * Notes de version — annonces de flotte à l'emplacement `modal`.
   *
   * Le serveur a déjà fait le tri (OS, plan, version, fenêtre de validité) et
   * le backend relit la configuration toutes les quinze minutes ; on se
   * contente de déposer ce qui arrive. La file refuse d'elle-même ce qui a
   * déjà été lu, donc redéposer à chaque relecture ne coûte rien.
   *
   * La clé porte l'identifiant de l'annonce : une note corrigée dans le
   * back-office garde le sien et ne réapparaît pas à ceux qui l'ont déjà vue,
   * tandis qu'une nouvelle note est une nouvelle clé.
   */
  const announcements = useFleet((s) => s.config.announcements)
  useEffect(() => {
    if (isConsoleWindow) return
    for (const a of announcements) {
      if (a.placement !== 'modal' || !a.title) continue
      pushModal({
        kind: 'patchNotes',
        key: `announcement-${a.id}`,
        data: { title: a.title, kicker: a.kicker, body: a.message },
      })
    }
  }, [announcements, pushModal])

  // Pastille du support : sans cette veille, une réponse de l'équipe n'était
  // visible qu'en allant soi-même sur l'écran Support. Relue au démarrage
  // puis périodiquement, et remise à zéro dès qu'il n'y a plus de compte —
  // une pastille qui survivrait à la déconnexion ne parlerait de personne.
  useEffect(() => {
    if (isConsoleWindow) return
    if (!yuyuSignedIn) {
      clearSupportWatch()
      return
    }
    refreshSupportWatch()
    const timer = setInterval(refreshSupportWatch, SUPPORT_POLL_MS)
    return () => clearInterval(timer)
  }, [yuyuSignedIn])

  // Ctrl+Maj+M → atelier des modales. Une fenêtre Tauri n'a pas de barre
  // d'adresse : sans raccourci, une route de développement est inatteignable.
  // Compilé hors de la version publiée en même temps que la route.
  useEffect(() => {
    if (!import.meta.env.DEV || isConsoleWindow) return
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.shiftKey && (e.key === 'M' || e.key === 'm')) navigate('/dev/modals')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [navigate])

  // Les autres écrans se chargent en tâche de fond une fois le premier
  // affiché : la navigation n'a plus rien à attendre.
  useEffect(preloadPages, [])

  // Sert à remettre la barrière d'erreur à zéro d'une page à l'autre.
  const location = useLocation()

  if (isConsoleWindow) {
    return (
      <Suspense fallback={RouteFallback}>
        <ErrorToast />
        <Console />
      </Suspense>
    )
  }

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-bg-primary">
      <ErrorToast />
      <TitleBar />
      <UpdateChecker />
      {/* Une seule modale de communication à la fois, dans l'ordre décidé par
          la file. Les invitations à rejoindre et le mot de passe imposé
          restent en dehors : la première répond à un geste immédiat (un clic
          sur le lien d'un ami), le second bloque tout le reste. */}
      <ModalQueueHost />
      {joinRequest && (
        <JoinServerModal request={joinRequest} onClose={() => setJoinRequest(null)} />
      )}
      <FleetNotices />
      {passwordResetRequired && <PasswordChangeModal forced onClose={() => {}} />}
      <div className="flex-1 overflow-hidden" style={{ filter: `brightness(${brightness / 100})` }}>
        {/* Suspense À L'INTÉRIEUR de la transition, jamais au-dessus : une
            page chargée à la demande suspend le rendu, et un Suspense placé
            au-dessus remplacerait tout l'arbre — AnimatePresence compris —
            par son écran d'attente, en plein milieu d'une sortie de page. Au
            retour, la présence reprenait avec une sortie qu'elle attendait
            toujours, et plus rien ne s'affichait jusqu'au rechargement.
            C'est le bug de la page blanche quand on enchaîne vite les
            écrans. */}
        <PageTransition>
          <ErrorBoundary resetKey={location.pathname}>
          <Suspense fallback={RouteFallback}>
          <Routes>
            <Route path="/yuyu" element={<YuyuLogin />} />
            <Route path="/" element={<Navigate to="/home" replace />} />
            <Route path="/home" element={<Home />} />
            <Route path="/login" element={<Login />} />
            <Route path="/instances" element={<Instances />} />
            <Route path="/mods" element={<Mods />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="/information" element={<Information />} />
            <Route path="/legal" element={<Legal />} />
            {/* Sync et Stats restent accessibles par leur URL : elles ne
                sont plus dans la barre de l'accueil, mais dans /features. */}
            {/* La sync est payante : c'est le Rust qui tranche (PlanGate),
                pas le plan affiché par le store. */}
            <Route path="/sync" element={<PlanGate feature="sync"><Sync /></PlanGate>} />
            <Route path="/features" element={<Features />} />
            <Route path="/support" element={<Support />} />
            <Route path="/plans" element={<Plans />} />
            <Route path="/stats" element={<Stats />} />
            {/* Les sauvegardes sont payantes, au même titre que la sync : le
                Rust le déclarait déjà (`plan_guard`, clé « backup ») mais
                cette route ne le lui demandait pas — l'écran s'ouvrait donc
                pour tout le monde. */}
            <Route path="/backup" element={<PlanGate feature="backup"><Backup /></PlanGate>} />
            <Route path="/sync/:syncId" element={<SyncInstance />} />
            <Route path="/server" element={<Server />} />
            <Route path="/jvm" element={<JvmProfiles />} />
            <Route path="/jvm/:profileId" element={<JvmProfileEditor />} />
            {/* Atelier des modales, uniquement en développement (Ctrl+Maj+M). */}
            {DevModals && <Route path="/dev/modals" element={<DevModals />} />}
          </Routes>
          </Suspense>
          </ErrorBoundary>
        </PageTransition>
      </div>
    </div>
  )
}
