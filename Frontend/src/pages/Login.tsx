import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-shell'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Account } from '@/types'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { showError } from '@/stores/useErrorToast'

type Step = 'idle' | 'loading' | 'polling' | 'error'

export default function Login() {
  const navigate = useNavigate()
  const { uuid, accounts, setAccounts, setUser, removeAccount } = useStore()
  const [step, setStep] = useState<Step>('idle')
  const [userCode, setUserCode] = useState('')
  const [verifyUrl, setVerifyUrl] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [previewUuid, setPreviewUuid] = useState<string | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const canvasContainerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)

  useEffect(() => {
    api.mc.accounts()
      .then((accs) => {
        const mapped: Account[] = accs.map((a) => ({ username: a.mc_username, uuid: a.mc_uuid }))
        setAccounts(mapped)
        const active = accs.find((a) => a.is_active)
        if (active) setUser(active.mc_username, active.mc_uuid)
      })
      .catch(() => {})
  }, [])

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
    if (displayUuid) {
      ;(viewer.loadSkin(`https://mc-heads.net/skin/${displayUuid}`, { model: 'auto-detect' }) as Promise<void> | void)?.catch?.(() => {})
    } else {
      viewer.loadSkin(null)
    }
  }, [uuid, previewUuid])

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
      pollRef.current = setInterval(async () => {
        try {
          const poll = await api.auth.poll()
          if (poll.status === 'success' && poll.username) {
            stopPolling()
            const accs = await api.mc.accounts()
            const mapped: Account[] = accs.map((a) => ({ username: a.mc_username, uuid: a.mc_uuid }))
            setAccounts(mapped)
            const active = accs.find((a) => a.is_active)
            if (active) setUser(active.mc_username, active.mc_uuid)
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
      setUser(acc.username, acc.uuid)
      navigate('/home')
    } catch (e) { showError(e) }
  }

  const handleRemove = async (acc: Account) => {
    try {
      await api.mc.delete(acc.uuid)
      removeAccount(acc.uuid)
    } catch (e) { showError(e) }
  }

  useEffect(() => () => stopPolling(), [])

  const displayAccount = accounts.find((a) => a.uuid === (previewUuid ?? uuid))

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

          {/* Account rows */}
          <div className="flex flex-col gap-2">
            {accounts.map((acc) => (
              <AccountRow
                key={acc.uuid}
                acc={acc}
                isActive={acc.uuid === uuid}
                onSelect={() => handleSelect(acc)}
                onRemove={() => handleRemove(acc)}
                onHover={() => setPreviewUuid(acc.uuid)}
                onLeave={() => setPreviewUuid(null)}
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
    </div>
  )
}

function AccountRow({
  acc, isActive, onSelect, onRemove, onHover, onLeave,
}: {
  acc: Account
  isActive: boolean
  onSelect: () => void
  onRemove: () => void
  onHover: () => void
  onLeave: () => void
}) {
  const [hovered, setHovered] = useState(false)

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
      {/* Avatar */}
      <div className="relative flex-shrink-0">
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
