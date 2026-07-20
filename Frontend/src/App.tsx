import { lazy, Suspense, useEffect } from 'react'
import { Navigate, Route, Routes, useNavigate, useLocation } from 'react-router-dom'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { TitleBar } from '@/components/TitleBar'
import { UpdateChecker } from '@/components/UpdateChecker'
import { useStore } from '@/stores/useStore'
import { api } from '@/api/client'
import { BETA_TEST } from '@/config/beta'

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

function RouteFallback() {
  return <div className="flex h-full w-full" style={{ background: '#09090D' }} />
}

const label = getCurrentWindow().label
const isConsoleWindow = label.startsWith('mc-console-')

function AuthGuard({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const { yuyuToken } = useStore()

  useEffect(() => {
    if (BETA_TEST) return
    if (!yuyuToken && pathname !== '/yuyu') {
      navigate('/yuyu', { replace: true })
    }
  }, [yuyuToken, pathname])

  return <>{children}</>
}

export default function App() {
  const { brightness, instanceSyncMode, setInstances } = useStore()

  useEffect(() => {
    if (isConsoleWindow) return
    api.instances.startupSync(instanceSyncMode)
      .then(() => api.instances.list())
      .then(setInstances)
      .catch(() => {})

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

  if (isConsoleWindow) {
    return (
      <Suspense fallback={<RouteFallback />}>
        <Console />
      </Suspense>
    )
  }

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-bg-primary">
      <TitleBar />
      <UpdateChecker />
      <div className="flex-1 overflow-hidden" style={{ filter: `brightness(${brightness / 100})` }}>
        <Suspense fallback={<RouteFallback />}>
          <Routes>
            {/* YuyuFrame account gate — skipped in beta */}
            <Route path="/yuyu" element={BETA_TEST ? <Navigate to="/home" replace /> : <YuyuLogin />} />

            {/* Protected routes */}
            <Route
              path="/*"
              element={
                <AuthGuard>
                  <Routes>
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
                  </Routes>
                </AuthGuard>
              }
            />
          </Routes>
        </Suspense>
      </div>
    </div>
  )
}
