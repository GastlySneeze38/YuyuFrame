import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { TitleBar } from '@/components/TitleBar'
import { UpdateChecker } from '@/components/UpdateChecker'
import { ErrorToast } from '@/components/ui/ErrorToast'
import { FleetNotices } from '@/components/FleetNotices'
import { PasswordChangeModal } from '@/components/account/PasswordChangeModal'
import { OfflinePurchaseReminderModal } from '@/components/account/OfflinePurchaseReminderModal'
import { ReconnectModal } from '@/components/account/ReconnectModal'
import { PatchNotesModal } from '@/components/PatchNotesModal'
import { JoinServerModal, type JoinRequest } from '@/components/servers/JoinServerModal'
import { useStore } from '@/stores/useStore'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { AUTH_SYSTEM_VERSION } from '@/config/authVersion'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { parseJoinUrl } from '@/lib/joinLink'

// Chargées à la demande — évite de tout regrouper dans un seul chunk JS au
// premier chargement (pages secondaires comme Legal/Information/Stats
// n'ont pas besoin d'être prêtes avant que l'utilisateur les visite).
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
const Plans = lazy(() => import('@/pages/Plans'))
const Stats = lazy(() => import('@/pages/Stats'))
const Server = lazy(() => import('@/pages/Server'))
const JvmProfiles = lazy(() => import('@/pages/JvmProfiles'))
const JvmProfileEditor = lazy(() => import('@/pages/JvmProfileEditor'))

function RouteFallback() {
  return <div className="flex h-full w-full bg-[#09090D]" />
}

const label = getCurrentWindow().label
const isConsoleWindow = label.startsWith('mc-console-')

export default function App() {
  const { brightness, instanceSyncMode, setInstances, uuid, pendingPatchNotes, setPendingPatchNotes, authSystemVersion, setAuthSystemVersion, setUser, setInstanceRunning, applyInstanceIdMigrations, setApiOnline } = useStore()
  // Mot de passe provisoire donné par le support : la modale s'impose tant
  // qu'il n'est pas changé (le serveur refuse tout le reste).
  const passwordResetRequired = useStore((s) => s.yuyuPasswordResetRequired)
  const [showPatchNotes, setShowPatchNotes] = useState(false)
  const [showOfflineReminder, setShowOfflineReminder] = useState(false)
  const [showReconnect, setShowReconnect] = useState(false)
  const [joinRequest, setJoinRequest] = useState<JoinRequest | null>(null)
  // Calculé une seule fois au montage (avant tout re-render) — comparé puis
  // consommé dans les callbacks de démarrage ci-dessous, jamais relu après.
  const needsReconnectRef = useRef(authSystemVersion < AUTH_SYSTEM_VERSION)
  // Décidé une fois la liste des comptes chargée (voir plus bas).
  const offlineReminderRef = useRef(false)

  // Monté pour toute la durée de vie de la fenêtre principale — contrairement
  // à l'ancien listener posé uniquement dans Home.tsx, qui se désabonnait dès
  // qu'on naviguait ailleurs (Instances, Réglages...). Le jeu pouvait alors se
  // fermer pendant qu'on était sur une autre page : personne ne recevait
  // `game_state`, le store gardait `running: true` indéfiniment et le bouton
  // "EN JEU..." restait bloqué jusqu'à un F5 complet qui réinitialise le store.
  useTauriEvent<{ running: boolean; instance_id: string }>('game_state', ({ running, instance_id }) => {
    setInstanceRunning(instance_id, running)
    if (!running) getCurrentWindow().show()
  })

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

  useTauriEvent<string>('deep_link_join', (url) => {
    setJoinRequest(parseJoinUrl(url))
  })

  // Priorité aux notes de patch : si une mise à jour vient de se terminer
  // (voir UpdateChecker → relaunch()), on les affiche d'abord — le rappel
  // compte hors ligne, lui, n'apparaît qu'une fois les notes fermées
  // (handleClosePatchNotes), jamais en même temps.
  //
  // `isOffline` du store est un instantané persisté (voir partialize dans
  // useStore.ts) qui n'est resynchronisé qu'en repassant par Login/YuyuLogin
  // — au démarrage normal, l'app route direct vers /home sans jamais
  // revalider ce flag. Un `isOffline: true` persisté un jour (test du
  // compte hors ligne, switch antérieur...) redéclenchait donc le rappel à
  // chaque lancement même une fois de retour sur un compte Microsoft en
  // ligne. On revalide contre le compte actif réel avant de décider.
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
        if (pendingPatchNotes) {
          setShowPatchNotes(true)
        } else if (needsReconnectRef.current && active) {
          setShowReconnect(true)
        } else if (offlineReminderRef.current) {
          setShowOfflineReminder(true)
        }
      })
      .catch((e) => {
        // Sans la liste des comptes, impossible de savoir si le jeu est
        // acheté : pas de rappel d'achat plutôt qu'un rappel à tort.
        console.error('[App] liste des comptes Minecraft :', e)
        if (pendingPatchNotes) setShowPatchNotes(true)
        else if (needsReconnectRef.current && uuid) setShowReconnect(true)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleClosePatchNotes = () => {
    setShowPatchNotes(false)
    setPendingPatchNotes(null)
    if (needsReconnectRef.current && uuid) setShowReconnect(true)
    else if (offlineReminderRef.current) setShowOfflineReminder(true)
  }

  const handleCloseReconnect = () => {
    setShowReconnect(false)
    needsReconnectRef.current = false
    if (offlineReminderRef.current) setShowOfflineReminder(true)
  }

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

  if (isConsoleWindow) {
    return (
      <Suspense fallback={<RouteFallback />}>
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
      {showPatchNotes && pendingPatchNotes && (
        <PatchNotesModal
          version={pendingPatchNotes.version}
          notes={pendingPatchNotes.notes}
          onClose={handleClosePatchNotes}
        />
      )}
      {showReconnect && (
        <ReconnectModal onClose={handleCloseReconnect} />
      )}
      {showOfflineReminder && (
        <OfflinePurchaseReminderModal onClose={() => setShowOfflineReminder(false)} />
      )}
      {joinRequest && (
        <JoinServerModal request={joinRequest} onClose={() => setJoinRequest(null)} />
      )}
      <FleetNotices />
      {passwordResetRequired && <PasswordChangeModal forced onClose={() => {}} />}
      <div className="flex-1 overflow-hidden" style={{ filter: `brightness(${brightness / 100})` }}>
        <Suspense fallback={<RouteFallback />}>
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
            <Route path="/sync" element={<Sync />} />
            <Route path="/plans" element={<Plans />} />
            <Route path="/stats" element={<Stats />} />
            <Route path="/server" element={<Server />} />
            <Route path="/jvm" element={<JvmProfiles />} />
            <Route path="/jvm/:profileId" element={<JvmProfileEditor />} />
          </Routes>
        </Suspense>
      </div>
    </div>
  )
}
