import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-shell'
import { getCurrentWindow, currentMonitor, PhysicalPosition, PhysicalSize } from '@tauri-apps/api/window'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Account } from '@/types'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { showError } from '@/stores/useErrorToast'
import { OfflineAccountModal } from '@/components/account/OfflineAccountModal'
import { SkinPickerModal } from '@/components/account/SkinPickerModal'

type Step = 'idle' | 'loading' | 'polling' | 'confirmed' | 'error'

const OVERLAY_WIDTH = 380
const OVERLAY_HEIGHT = 340
const OVERLAY_CONFIRM_HEIGHT = 460
const OVERLAY_MARGIN = 24

export default function Login() {
  const navigate = useNavigate()
  const { uuid, username, accounts, setAccounts, setUser, removeAccount, addAccount } = useStore()
  const [showOfflineModal, setShowOfflineModal] = useState(false)
  const [skins, setSkins] = useState<Record<string, string>>({})
  const [step, setStep] = useState<Step>('idle')
  const [userCode, setUserCode] = useState('')
  const [verifyUrl, setVerifyUrl] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [previewUuid, setPreviewUuid] = useState<string | null>(null)
  const [overlayActive, setOverlayActive] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const canvasContainerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)
  const savedWindowGeometry = useRef<{ size: PhysicalSize; position: PhysicalPosition } | null>(null)

  // Position/taille de l'overlay : milieu droit de l'écran, ancré au bord
  // droit avec une marge, centré verticalement pour la hauteur donnée.
  const computeOverlayGeometry = async (heightPx: number) => {
    const monitor = await currentMonitor()
    const scale = monitor?.scaleFactor ?? 1
    const size = new PhysicalSize(Math.round(OVERLAY_WIDTH * scale), Math.round(heightPx * scale))
    const margin = Math.round(OVERLAY_MARGIN * scale)
    const monitorSize = monitor?.size ?? new PhysicalSize(1920, 1080)
    const monitorPos = monitor?.position ?? new PhysicalPosition(0, 0)
    const position = new PhysicalPosition(
      monitorPos.x + monitorSize.width - size.width - margin,
      monitorPos.y + Math.round((monitorSize.height - size.height) / 2),
    )
    return { size, position }
  }

  // Rétrécit la fenêtre en overlay compact + always-on-top pendant l'auth
  // Microsoft — sans ça, la fenêtre disparaît derrière le navigateur et
  // l'utilisateur perd de vue le code à entrer / le statut de connexion.
  const enterOverlay = async () => {
    const win = getCurrentWindow()
    try {
      savedWindowGeometry.current = {
        size: await win.outerSize(),
        position: await win.outerPosition(),
      }
      const { size, position } = await computeOverlayGeometry(OVERLAY_HEIGHT)
      await win.setSize(size)
      await win.setPosition(position)
      await win.setAlwaysOnTop(true)
      setOverlayActive(true)
    } catch {
      // Best-effort : si le redimensionnement échoue, on reste en page pleine
    }
  }

  // Agrandit l'overlay en hauteur pour la page de confirmation (avatar +
  // nom de compte), sans toucher à savedWindowGeometry ni à l'always-on-top.
  const growOverlayForConfirmation = async () => {
    const win = getCurrentWindow()
    try {
      const { size, position } = await computeOverlayGeometry(OVERLAY_CONFIRM_HEIGHT)
      await win.setSize(size)
      await win.setPosition(position)
    } catch { /* best-effort */ }
  }

  const exitOverlay = async () => {
    const win = getCurrentWindow()
    const saved = savedWindowGeometry.current
    savedWindowGeometry.current = null
    setOverlayActive(false)
    try {
      await win.setAlwaysOnTop(false)
      if (saved) {
        await win.setSize(saved.size)
        await win.setPosition(saved.position)
      }
      await win.setFocus()
    } catch { /* best-effort */ }
  }

  useEffect(() => {
    api.mc.accounts()
      .then((accs) => {
        const mapped: Account[] = accs.map((a) => ({ username: a.mc_username, uuid: a.mc_uuid, is_offline: a.is_offline }))
        setAccounts(mapped)
        const active = accs.find((a) => a.is_active)
        if (active) setUser(active.mc_username, active.mc_uuid, active.is_offline)
      })
      .catch(() => {})
  }, [])

  // Charge le skin custom (voir OfflineAccountModal/skin.rs) des comptes hors
  // ligne déjà connus — un seul appel par compte tant qu'il n'a pas encore
  // été mis en cache (setSkin met aussi ce cache à jour directement).
  useEffect(() => {
    accounts
      .filter((a) => a.is_offline && !(a.uuid in skins))
      .forEach((a) => {
        api.mc.getSkin(a.uuid).then((dataUri) => {
          if (dataUri) setSkins((s) => ({ ...s, [a.uuid]: dataUri }))
        }).catch(() => {})
      })
  }, [accounts])

  useEffect(() => {
    if (!canvasRef.current || !canvasContainerRef.current) return
    const container = canvasContainerRef.current
    const { width: w, height: h } = container.getBoundingClientRect()
    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: w || 360,
      height: h || 520,
    })
    viewer.background = null
    viewer.autoRotate = true
    viewer.autoRotateSpeed = 0.7
    viewer.zoom = 0.85
    viewer.fov = 60
    viewer.animation = new WalkingAnimation()
    viewer.animation.speed = 0.4
    viewerRef.current = viewer

    const ro = new ResizeObserver(() => {
      const { width, height } = container.getBoundingClientRect()
      if (width > 0 && height > 0) viewer.setSize(width, height)
    })
    ro.observe(container)

    return () => {
      ro.disconnect()
      viewer.dispose()
      viewerRef.current = null
    }
  }, [])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer) return
    const displayUuid = previewUuid ?? uuid
    const displayAccount_ = accounts.find((a) => a.uuid === displayUuid)
    if (displayUuid && displayAccount_?.is_offline) {
      // Pas de vrai skin Mojang pour un compte hors ligne — mc-heads.net n'a
      // rien de pertinent pour cet UUID inventé, donc soit le skin custom
      // (voir skins cache ci-dessus), soit rien du tout.
      const custom = skins[displayUuid]
      if (custom) {
        ;(viewer.loadSkin(custom, { model: 'auto-detect' }) as Promise<void> | void)?.catch?.(() => {})
      } else {
        viewer.loadSkin(null)
      }
    } else if (displayUuid) {
      ;(viewer.loadSkin(`https://mc-heads.net/skin/${displayUuid}`, { model: 'auto-detect' }) as Promise<void> | void)?.catch?.(() => {})
    } else {
      viewer.loadSkin(null)
    }
  }, [uuid, previewUuid, skins])

  const stopPolling = () => {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null }
  }

  const startLogin = async () => {
    setStep('loading')
    setError('')
    try {
      const resp = await api.auth.startDevice()
      setUserCode(resp.user_code)
      setVerifyUrl(resp.verification_uri)
      open(resp.verification_uri)
      setStep('polling')
      enterOverlay()
      pollRef.current = setInterval(async () => {
        try {
          const poll = await api.auth.poll()
          if (poll.status === 'success' && poll.username) {
            stopPolling()
            setStep('confirmed')
            await growOverlayForConfirmation()
            const accs = await api.mc.accounts()
const mapped: Account[] = accs.map((a) => ({ username: a.mc_username, uuid: a.mc_uuid, is_offline: a.is_offline }))
            setAccounts(mapped)
            const active = accs.find((a) => a.is_active)
if (active) setUser(active.mc_username, active.mc_uuid, active.is_offline)
            await new Promise((r) => setTimeout(r, 1400))
            await exitOverlay()
            navigate('/home', { replace: true })
          } else if (poll.status === 'error') {
            stopPolling()
            setError(poll.error ?? 'Erreur inconnue')
            setStep('error')
          }
        } catch { /* keep polling */ }
      }, 5000)
    } catch (e) {
      // invoke() de Tauri rejette avec une simple chaîne (pas un Error JS)
      // quand une commande Rust renvoie Err(String) — sans ce cas, le vrai
      // message ("Non authentifié sur YuyuFrame", etc.) était masqué par un
      // message générique inutile pour diagnostiquer le problème.
      const message = e instanceof Error ? e.message : typeof e === 'string' ? e : 'Erreur de connexion au backend'
      setError(message)
      setStep('error')
    }
  }

  const handleSelect = async (acc: Account) => {
    try {
      await api.mc.switch(acc.uuid)
      setUser(acc.username, acc.uuid, acc.is_offline)
      navigate('/home')
    } catch (e) { showError(e) }
  }

  const handleRemove = async (acc: Account) => {
    try {
      await api.mc.delete(acc.uuid)
      removeAccount(acc.uuid)
    } catch (e) { showError(e) }
  }

  useEffect(() => {
    return () => {
      stopPolling()
      if (savedWindowGeometry.current) exitOverlay()
    }
  }, [])

  const displayAccount = accounts.find((a) => a.uuid === (previewUuid ?? uuid))

  if (overlayActive) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-[#09090D] px-5 py-4">
        <div className="flex items-center gap-2">
          <div className="h-3.5 w-3.5 rounded-sm bg-[#4B3FCF]" />
          <span className="font-black text-white text-[13px] tracking-[-0.01em]">YuyuFrame</span>
        </div>

        {step === 'polling' && (
          <div className="flex w-full flex-col gap-3">
            <p className="text-center text-[11px] text-[rgba(255,255,255,0.45)]">
              Entre ce code sur la page Microsoft :
            </p>
            <button
              onClick={() => { navigator.clipboard.writeText(userCode); setCopied(true); setTimeout(() => setCopied(false), 2000) }}
              className={`rounded-xl py-2.5 text-center transition-all duration-150 border ${copied ? 'bg-[rgba(74,222,128,0.08)] border-[rgba(74,222,128,0.3)]' : 'bg-[rgba(0,0,0,0.4)] border-[rgba(255,255,255,0.08)]'}`}
              title="Cliquer pour copier"
            >
              <span className="font-mono font-black text-white text-[22px] tracking-[0.2em]">
                {userCode}
              </span>
              <p className={`text-[10px] mt-1 ${copied ? 'text-[rgb(134,239,172)]' : 'text-[rgba(255,255,255,0.2)]'}`}>
                {copied ? 'Copié !' : 'Cliquer pour copier'}
              </p>
            </button>
            <div className="flex gap-2">
              <button
                onClick={() => open(verifyUrl)}
                className="flex-1 rounded-xl py-2 text-[12px] font-medium text-white transition-all duration-150 bg-[rgba(75,63,207,0.15)] border border-[rgba(75,63,207,0.3)] hover:bg-[rgba(75,63,207,0.3)]"
              >
                Ouvrir Microsoft →
              </button>
              <button
                onClick={() => { stopPolling(); exitOverlay(); setStep('idle') }}
                className="rounded-xl px-3 py-2 text-[12px] transition-all duration-150 text-[rgba(255,255,255,0.3)] border border-[rgba(255,255,255,0.07)] hover:text-[rgba(255,255,255,0.65)]"
              >
                Annuler
              </button>
            </div>
            <div className="flex items-center justify-center gap-2">
              <span className="h-3 w-3 animate-spin-slow rounded-full border border-[rgba(255,255,255,0.12)] border-t-[#4B3FCF]" />
              <span className="text-[10px] text-[rgba(255,255,255,0.35)]">
                En attente de confirmation...
              </span>
            </div>
          </div>
        )}

        {step === 'confirmed' && (
          <div className="flex w-full flex-col items-center gap-3 text-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-[rgba(74,222,128,0.12)] border border-[rgba(74,222,128,0.35)]">
              <svg viewBox="0 0 24 24" fill="none" stroke="#4ade80" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" className="h-8 w-8">
                <path d="M20 6L9 17l-5-5" />
              </svg>
            </div>
            {username && (
              <img
                src={`https://mc-heads.net/avatar/${uuid}/40`}
                alt={username}
                className="rounded-lg w-10 h-10 [image-rendering:pixelated]"
              />
            )}
            <div>
              <p className="font-bold text-white text-[14px]">Connecté avec succès</p>
              {username && (
                <p className="text-[12px] text-[rgba(255,255,255,0.4)] mt-1">{username}</p>
              )}
            </div>
            <p className="text-[10px] text-[rgba(255,255,255,0.25)]">Retour au launcher...</p>
          </div>
        )}

        {step === 'error' && (
          <div className="flex w-full flex-col gap-3">
            <div className="rounded-xl px-3 py-2.5 bg-[rgba(200,50,50,0.12)] border border-[rgba(200,50,50,0.2)]">
              <p className="text-[11px] text-[rgb(252,165,165)] break-all whitespace-pre-wrap">{error}</p>
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => { navigator.clipboard.writeText(error); setCopied(true); setTimeout(() => setCopied(false), 2000) }}
                className={`rounded-xl px-3 py-2 text-[12px] transition-all duration-150 border ${copied ? 'text-[rgb(134,239,172)] border-[rgba(74,222,128,0.3)]' : 'text-[rgba(255,255,255,0.4)] border-[rgba(255,255,255,0.08)]'}`}
              >
                {copied ? 'Copié ✓' : 'Copier'}
              </button>
              <button
                onClick={() => { exitOverlay(); setStep('idle') }}
                className="flex-1 rounded-xl py-2 text-[12px] transition-all duration-150 text-[rgba(255,255,255,0.4)] border border-[rgba(255,255,255,0.08)] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(255,255,255,0.7)]"
              >
                Réessayer
              </button>
            </div>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="font-black text-white text-[16px] tracking-[-0.01em] leading-[1.2]">
            Connexion
          </h1>
          <p className="text-[10px] text-[rgba(255,255,255,0.28)] mt-px">
            Gérez vos comptes Minecraft (max 2)
          </p>
        </div>
      </PageHeader>

      {/* Body */}
      <div className="flex flex-1 overflow-hidden">

        {/* Left — 3D skin viewer */}
        <div className="relative flex flex-shrink-0 flex-col w-[38%] bg-[radial-gradient(ellipse_at_50%_58%,rgba(75,63,207,0.22)_0%,transparent_72%)] border-r border-r-[rgba(255,255,255,0.05)]">
          {/* shadow under feet */}
          <div className="absolute bottom-[18%] left-1/2 -translate-x-1/2 w-[28%] h-[14px] bg-[rgba(75,63,207,0.55)] rounded-full blur-[20px]" />

          {/* Canvas fills all available height */}
          <div ref={canvasContainerRef} className="relative z-10 min-h-0 flex-1">
            <canvas
              ref={canvasRef}
              className="bg-transparent block"
            />
          </div>

          <div className="relative z-10 flex flex-shrink-0 flex-col items-center gap-0.5 py-3">
            {displayAccount ? (
              <>
                <p className="font-bold text-white text-[13px]">
                  {displayAccount.username}
                </p>
                <p className={`text-[10px] ${displayAccount.uuid === uuid ? 'text-[rgba(74,222,128,0.75)]' : 'text-[rgba(255,255,255,0.3)]'}`}>
                  {displayAccount.uuid === uuid ? '● Actif' : '○ Aperçu'}
                </p>
              </>
            ) : (
              <p className="text-[10px] text-[rgba(255,255,255,0.2)]">Aucun compte</p>
            )}
          </div>
        </div>

        {/* Right — account management */}
        <div className="flex flex-1 flex-col gap-6 overflow-y-auto px-8 py-7">

          {/* Branding */}
          <div className="flex items-start justify-between gap-3">
            <div className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              <div className="h-4 w-4 rounded-sm bg-[#4B3FCF]" />
              <span className="font-black text-white text-[22px] tracking-[-0.01em]">
                YuyuFrame
              </span>
            </div>
            <p className="text-[11px] text-[rgba(255,255,255,0.28)]">
              {accounts.length === 0
                ? 'Connecte-toi pour jouer'
                : accounts.length < 2
                ? 'Ajoute un deuxième compte ou continue'
                : 'Sélectionne le compte avec lequel jouer'}
            </p>
            </div>

            <button
              onClick={() => { setShowOfflineModal(true); api.analytics.track('offline_account_modal_opened') }}
              title="Compte hors ligne"
              className="flex h-8 flex-shrink-0 items-center justify-center rounded-full border border-[rgba(255,255,255,0.15)] px-3.5 text-[rgba(255,255,255,0.35)] transition-colors hover:border-[rgba(255,255,255,0.4)] hover:text-white"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" width={13} height={13}>
                <path d="M12 5v14M5 12h14" />
              </svg>
            </button>
          </div>

          {/* Account rows */}
          <div className="flex flex-col gap-2">
            {accounts.map((acc) => (
              <AccountRow
                key={acc.uuid}
                acc={acc}
                isActive={acc.uuid === uuid}
                skin={skins[acc.uuid]}
                onSelect={() => handleSelect(acc)}
                onRemove={() => handleRemove(acc)}
                onHover={() => setPreviewUuid(acc.uuid)}
                onLeave={() => setPreviewUuid(null)}
                onSkinChange={(dataUri) => setSkins((s) => ({ ...s, [acc.uuid]: dataUri }))}
              />
            ))}

            {accounts.length < 2 && step === 'idle' && (
              <AddRow onClick={startLogin} />
            )}
          </div>

          {/* Auth flow panel */}
          {(step === 'loading' || step === 'polling' || step === 'error') && (
            <div className="rounded-2xl p-5 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]">
              {step === 'loading' && (
                <div className="flex items-center justify-center gap-3 py-2">
                  <span className="h-4 w-4 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.15)] border-t-[#4B3FCF]" />
                  <span className="text-[13px] text-[rgba(255,255,255,0.5)]">Connexion en cours...</span>
                </div>
              )}

              {step === 'polling' && (
                <div className="flex flex-col gap-4">
                  <p className="text-center text-[12px] text-[rgba(255,255,255,0.45)]">
                    Entre ce code sur la page Microsoft :
                  </p>
                  <button
                    onClick={() => { navigator.clipboard.writeText(userCode); setCopied(true); setTimeout(() => setCopied(false), 2000) }}
                    className={`rounded-xl py-3 text-center transition-all duration-150 border ${copied ? 'bg-[rgba(74,222,128,0.08)] border-[rgba(74,222,128,0.3)]' : 'bg-[rgba(0,0,0,0.4)] border-[rgba(255,255,255,0.08)]'}`}
                    title="Cliquer pour copier"
                  >
                    <span className="font-mono font-black text-white text-[26px] tracking-[0.25em]">
                      {userCode}
                    </span>
                    <p className={`text-[10px] mt-1 ${copied ? 'text-[rgb(134,239,172)]' : 'text-[rgba(255,255,255,0.2)]'}`}>
                      {copied ? 'Copié !' : 'Cliquer pour copier'}
                    </p>
                  </button>
                  <div className="flex gap-2">
                    <button
                      onClick={() => open(verifyUrl)}
                      className="flex-1 rounded-xl py-2 text-sm font-medium text-white transition-all duration-150 bg-[rgba(75,63,207,0.15)] border border-[rgba(75,63,207,0.3)] hover:bg-[rgba(75,63,207,0.3)]"
                    >
                      Ouvrir Microsoft →
                    </button>
                    <button
                      onClick={() => { stopPolling(); setStep('idle') }}
                      className="rounded-xl px-4 py-2 text-sm transition-all duration-150 text-[rgba(255,255,255,0.3)] border border-[rgba(255,255,255,0.07)] hover:text-[rgba(255,255,255,0.65)]"
                    >
                      Annuler
                    </button>
                  </div>
                  <div className="flex items-center justify-center gap-2">
                    <span className="h-3 w-3 animate-spin-slow rounded-full border border-[rgba(255,255,255,0.12)] border-t-[#4B3FCF]" />
                    <span className="text-[11px] text-[rgba(255,255,255,0.35)]">
                      En attente de confirmation...
                    </span>
                  </div>
                </div>
              )}

              {step === 'error' && (
                <div className="flex flex-col gap-3">
                  <div className="rounded-xl px-4 py-3 bg-[rgba(200,50,50,0.12)] border border-[rgba(200,50,50,0.2)]">
                    <p className="text-[11px] text-[rgb(252,165,165)] break-all whitespace-pre-wrap">{error}</p>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={() => { navigator.clipboard.writeText(error); setCopied(true); setTimeout(() => setCopied(false), 2000) }}
                      className={`rounded-xl px-3 py-2 text-sm transition-all duration-150 border ${copied ? 'text-[rgb(134,239,172)] border-[rgba(74,222,128,0.3)]' : 'text-[rgba(255,255,255,0.4)] border-[rgba(255,255,255,0.08)]'}`}
                    >
                      {copied ? 'Copié ✓' : 'Copier'}
                    </button>
                    <button
                      onClick={() => setStep('idle')}
                      className="flex-1 rounded-xl py-2 text-sm transition-all duration-150 text-[rgba(255,255,255,0.4)] border border-[rgba(255,255,255,0.08)] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(255,255,255,0.7)]"
                    >
                      Réessayer
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {showOfflineModal && (
        <OfflineAccountModal
          onClose={() => setShowOfflineModal(false)}
          onAdded={(acc) => { addAccount(acc.username, acc.uuid, acc.is_offline); navigate('/home') }}
        />
      )}
    </div>
  )
}

function AccountRow({
  acc, isActive, skin, onSelect, onRemove, onHover, onLeave, onSkinChange,
}: {
  acc: Account
  isActive: boolean
  skin?: string
  onSelect: () => void
  onRemove: () => void
  onHover: () => void
  onLeave: () => void
  onSkinChange: (dataUri: string) => void
}) {
  const [hovered, setHovered] = useState(false)
  const [showSkinPicker, setShowSkinPicker] = useState(false)
  const offline = acc.is_offline

  return (
    <div
      className={`flex items-center gap-3 rounded-xl p-3 transition-all duration-150 border ${
        isActive
          ? 'bg-[rgba(75,63,207,0.08)] border-[rgba(75,63,207,0.35)] shadow-[0_0_24px_rgba(75,63,207,0.1)]'
          : hovered
            ? 'bg-[rgba(255,255,255,0.03)] border-[rgba(255,255,255,0.09)] shadow-none'
            : 'bg-[rgba(255,255,255,0.02)] border-[rgba(255,255,255,0.06)] shadow-none'
      }`}
      onMouseEnter={() => { setHovered(true); onHover() }}
      onMouseLeave={() => { setHovered(false); onLeave() }}
    >
      {/* Avatar — les comptes hors ligne n'ont pas de vrai profil Mojang,
          donc pas d'appel à mc-heads.net : soit la tête recadrée depuis le
          skin custom (face 8×8 à l'offset (8,8) d'une texture 64×64), soit
          la pastille avec l'initiale. */}
      <div className="relative flex-shrink-0">
        {offline ? (
          skin ? (
            <div
              className="rounded-lg w-11 h-11 [image-rendering:pixelated]"
              style={{
                backgroundImage: `url(${skin})`,
                backgroundSize: '352px 352px',
                backgroundPosition: '-44px -44px',
              }}
            />
          ) : (
            <div className="flex items-center justify-center rounded-lg font-black text-white w-11 h-11 bg-[rgba(75,63,207,0.45)] [font-family:monospace] text-[18px]">
              {acc.username[0].toUpperCase()}
            </div>
          )
        ) : (
          <>
            <img
              src={`https://mc-heads.net/avatar/${acc.uuid}/48`}
              alt={acc.username}
              className="rounded-lg w-11 h-11 [image-rendering:pixelated]"
              onError={(e) => {
                e.currentTarget.style.display = 'none'
                const fb = e.currentTarget.nextElementSibling as HTMLElement | null
                if (fb) fb.style.display = 'flex'
              }}
            />
            <div className="hidden items-center justify-center rounded-lg font-black text-white w-11 h-11 bg-[rgba(75,63,207,0.45)] [font-family:monospace] text-[18px]">
              {acc.username[0].toUpperCase()}
            </div>
          </>
        )}
        {isActive && (
          <div className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 bg-[#22c55e] border-[#09090D]" />
        )}
      </div>

      {/* Info */}
      <div className="flex min-w-0 flex-1 flex-col">
        <p className="truncate font-semibold text-white text-[13px]">{acc.username}</p>
        <p className={`text-[10px] mt-px ${isActive ? 'text-[rgba(74,222,128,0.7)]' : 'text-[rgba(255,255,255,0.3)]'}`}>
          {isActive ? '● Actif' : 'Compte sauvegardé'}
        </p>
      </div>

      {/* Actions */}
      <div className="flex flex-shrink-0 items-center gap-1.5">
        {isActive ? (
          <button
            onClick={onSelect}
            className="rounded-lg px-4 py-1.5 text-sm font-medium text-white transition-all duration-150 bg-[rgba(75,63,207,0.25)] border border-[rgba(75,63,207,0.45)] hover:bg-[rgba(75,63,207,0.42)]"
          >
            Jouer →
          </button>
        ) : (
          <button
            onClick={onSelect}
            className="rounded-lg px-3 py-1.5 text-sm transition-all duration-150 text-[rgba(255,255,255,0.45)] border border-[rgba(255,255,255,0.08)] hover:border-[rgba(75,63,207,0.45)] hover:text-[rgba(255,255,255,0.9)]"
          >
            Sélectionner
          </button>
        )}
        {offline && (
          <button
            onClick={() => setShowSkinPicker(true)}
            title="Changer de skin"
            className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.2)] border border-[rgba(255,255,255,0.05)] bg-transparent hover:text-[rgba(255,255,255,0.7)] hover:border-[rgba(255,255,255,0.15)]"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className="w-[13px] h-[13px]">
              <path d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M4 6h16v12H4V6z" />
            </svg>
          </button>
        )}
        <button
          onClick={onRemove}
          className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.2)] border border-[rgba(255,255,255,0.05)] bg-transparent hover:text-[rgb(252,165,165)] hover:border-[rgba(200,50,50,0.3)] hover:bg-[rgba(200,50,50,0.08)]"
          title="Déconnecter"
        >
          <svg viewBox="0 0 24 24" fill="currentColor" className="w-[13px] h-[13px]">
            <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
          </svg>
        </button>
      </div>

      {showSkinPicker && (
        <SkinPickerModal
          uuid={acc.uuid}
          onClose={() => setShowSkinPicker(false)}
          onApplied={onSkinChange}
        />
      )}
    </div>
  )
}

function AddRow({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="flex w-full items-center gap-3 rounded-xl p-3 transition-all duration-200 bg-[rgba(255,255,255,0.015)] border-[1.5px] border-dashed border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.3)] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(140,130,240,0.8)] hover:bg-[rgba(75,63,207,0.05)]"
    >
      <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-lg bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.06)]">
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M12 5v14M5 12h14" strokeLinecap="round" />
        </svg>
      </div>
      <div className="text-left">
        <p className="text-[13px] font-semibold">Ajouter un compte</p>
        <p className="text-[10px] mt-0.5 text-[rgba(255,255,255,0.2)]">Connexion via Microsoft</p>
      </div>
    </button>
  )
}
