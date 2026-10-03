import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-shell'
import { getCurrentWindow, currentMonitor, PhysicalPosition, PhysicalSize } from '@tauri-apps/api/window'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import { skinPreview } from '@/lib/skinCache'
import { loadLatestSkin } from '@/lib/skinLoad'
import { useStore } from '@/stores/useStore'
import type { Account } from '@/types'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { showError } from '@/stores/useErrorToast'
import { OfflineAccountModal } from '@/components/account/OfflineAccountModal'
import { AddAccountModal } from '@/components/account/AddAccountModal'
import { YuyuAccountPanel } from '@/components/account/YuyuAccountPanel'
import { SectionTitle } from '@/components/ui/Field'
import { SkinFace } from '@/components/ui/SkinFace'
import { ModalShell } from '@/components/ui/ModalShell'
import { fadeVariants, fastTransition, listItemVariants, listVariants, pressable } from '@/lib/motion'
import { AnimatePresence, motion } from 'framer-motion'
import { useT } from '@/i18n'

type Step = 'idle' | 'loading' | 'polling' | 'confirmed' | 'error'

const OVERLAY_WIDTH = 380
const OVERLAY_HEIGHT = 340
const OVERLAY_CONFIRM_HEIGHT = 460
const OVERLAY_MARGIN = 24

export default function Login() {
  const t = useT()
  const navigate = useNavigate()
  const { uuid, username, accounts, setAccounts, setUser, clearUser, removeAccount, addAccount } = useStore()
  const [showOfflineModal, setShowOfflineModal] = useState(false)
  // Choix Microsoft / hors ligne : un seul bouton d'ajout dans la liste.
  const [showAddModal, setShowAddModal] = useState(false)
  // Liste complète des comptes, quand ils ne tiennent plus tous à l'écran.
  const [showAllAccounts, setShowAllAccounts] = useState(false)
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

  // Aperçu du skin enregistré, pour TOUS les comptes — un seul appel par
  // compte tant qu'il n'est pas en cache.
  //
  // Il ne se limitait avant qu'aux comptes hors ligne, en partant du principe
  // qu'un skin posé sur un compte Microsoft était chez Mojang et donc déjà
  // servi par le service d'avatars. C'est vrai à terme, mais ce service met du
  // temps à rafraîchir son cache : entre-temps, l'écran des skins montrait le
  // nouveau skin et celui-ci l'ancien — ou Steve. Notre référence locale est la
  // plus fraîche, elle passe donc devant, et le service reste le repli.
  useEffect(() => {
    accounts
      .filter((a) => !(a.uuid in skins))
      .forEach((a) => {
        skinPreview(a.uuid).then((dataUri) => {
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
    if (!displayUuid) {
      void loadLatestSkin(viewer, null)
      return
    }
    // Le skin enregistré d'abord, quel que soit le type de compte : c'est la
    // source la plus fraîche (voir le cache ci-dessus). À défaut, le service
    // d'avatars — qui rend l'apparence par défaut pour un UUID inventé, donc
    // un personnage plutôt qu'un vide pour un compte hors ligne sans skin.
    const source = skins[displayUuid] ?? `https://mc-heads.net/skin/${displayUuid}`
    // Le dernier demandé l'emporte : le repli vient du réseau et finissait
    // parfois après le skin enregistré (`lib/skinLoad.ts`).
    loadLatestSkin(viewer, source, { model: 'auto-detect' }).catch(() => {})
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
            setError(poll.error ?? t('login.unknownError'))
            setStep('error')
          }
        } catch { /* keep polling */ }
      }, 5000)
    } catch (e) {
      // invoke() de Tauri rejette avec une simple chaîne (pas un Error JS)
      // quand une commande Rust renvoie Err(String) — sans ce cas, le vrai
      // message ("Non authentifié sur YuyuFrame", etc.) était masqué par un
      // message générique inutile pour diagnostiquer le problème.
      const message = e instanceof Error ? e.message : typeof e === 'string' ? e : t('login.backendConnectionError')
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

  // Le backend désigne le compte qui reprend la main si l'actif est supprimé —
  // on s'aligne sur lui plutôt que de deviner localement.
  const handleRemove = async (acc: Account) => {
    try {
      const active = await api.mc.delete(acc.uuid)
      removeAccount(acc.uuid)
      if (active) setUser(active.mc_username, active.mc_uuid, active.is_offline)
      else clearUser()
      // Le serveur garde la liste des comptes liés (recherche du support).
      api.yuyu.syncMinecraftAccounts().catch(() => {})
    } catch (e) { showError(e) }
  }

  useEffect(() => {
    return () => {
      stopPolling()
      if (savedWindowGeometry.current) exitOverlay()
    }
  }, [])

  const displayAccount = accounts.find((a) => a.uuid === (previewUuid ?? uuid))

  // La grille des comptes ne dépasse jamais 3 lignes, pour que la page tienne
  // dans la fenêtre : une colonne tant que tout rentre, deux ensuite. Quand
  // même deux colonnes ne suffisent plus, la dernière case devient « + N » et
  // le bouton d'ajout déménage dans la modale.
  const MAX_ROWS = 3
  const twoColumns = accounts.length + 1 > MAX_ROWS
  const capacity = MAX_ROWS * (twoColumns ? 2 : 1)
  const addFitsInGrid = accounts.length + 1 <= capacity
  const visibleAccounts = addFitsInGrid ? accounts : accounts.slice(0, capacity - 1)
  const hiddenCount = accounts.length - visibleAccounts.length

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
              {t('login.enterCodeOnMicrosoft')}
            </p>
            <button
              onClick={() => { navigator.clipboard.writeText(userCode); setCopied(true); setTimeout(() => setCopied(false), 2000) }}
              className={`rounded-xl py-2.5 text-center transition-all duration-150 border ${copied ? 'bg-[rgba(74,222,128,0.08)] border-[rgba(74,222,128,0.3)]' : 'bg-[rgba(0,0,0,0.4)] border-[rgba(255,255,255,0.08)]'}`}
              title={t('login.clickToCopy')}
            >
              <span className="font-mono font-black text-white text-[22px] tracking-[0.2em]">
                {userCode}
              </span>
              <p className={`text-[10px] mt-1 ${copied ? 'text-[rgb(134,239,172)]' : 'text-[rgba(255,255,255,0.2)]'}`}>
                {copied ? t('login.copiedExclaim') : t('login.clickToCopy')}
              </p>
            </button>
            <div className="flex gap-2">
              <button
                onClick={() => open(verifyUrl)}
                className="flex-1 rounded-xl py-2 text-[12px] font-medium text-white transition-all duration-150 bg-[rgba(75,63,207,0.15)] border border-[rgba(75,63,207,0.3)] hover:bg-[rgba(75,63,207,0.3)]"
              >
                {t('login.openMicrosoft')}
              </button>
              <button
                onClick={() => { stopPolling(); exitOverlay(); setStep('idle') }}
                className="rounded-xl px-3 py-2 text-[12px] transition-all duration-150 text-[rgba(255,255,255,0.3)] border border-[rgba(255,255,255,0.07)] hover:text-[rgba(255,255,255,0.65)]"
              >
                {t('common.cancel')}
              </button>
            </div>
            <div className="flex items-center justify-center gap-2">
              <span className="h-3 w-3 animate-spin-slow rounded-full border border-[rgba(255,255,255,0.12)] border-t-[#4B3FCF]" />
              <span className="text-[10px] text-[rgba(255,255,255,0.35)]">
                {t('login.waitingForConfirmation')}
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
              <p className="font-bold text-white text-[14px]">{t('login.connectedSuccessfully')}</p>
              {username && (
                <p className="text-[12px] text-[rgba(255,255,255,0.4)] mt-1">{username}</p>
              )}
            </div>
            <p className="text-[10px] text-[rgba(255,255,255,0.25)]">{t('login.backToLauncher')}</p>
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
                {copied ? t('login.copiedCheck') : t('console.copy')}
              </button>
              <button
                onClick={() => { exitOverlay(); setStep('idle') }}
                className="flex-1 rounded-xl py-2 text-[12px] transition-all duration-150 text-[rgba(255,255,255,0.4)] border border-[rgba(255,255,255,0.08)] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(255,255,255,0.7)]"
              >
                {t('login.retry')}
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
          <h1 className="font-black text-txt-primary text-[16px] tracking-[-0.01em] leading-[1.2]">
            {t('account.title')}
          </h1>
          <p className="mt-px text-[10px] text-txt-muted">
            {t('account.subtitle')}
          </p>
        </div>
      </PageHeader>

      {/* Corps : deux colonnes qui tiennent dans la hauteur de la fenêtre.
          À gauche le compte YuyuFrame, à droite le skin puis les comptes
          Minecraft. Aucune colonne ne défile : `min-h-0` laisse les blocs se
          comprimer au lieu de déborder. */}
      <motion.div
        variants={listVariants}
        initial="initial"
        animate="animate"
        className="grid min-h-0 flex-1 grid-cols-[minmax(0,420px)_minmax(0,1fr)] gap-5 overflow-hidden px-6 py-5"
      >
        {/* Colonne gauche — compte YuyuFrame */}
        <motion.div variants={listItemVariants} className="flex min-h-0 flex-col gap-3">
          <div className="flex items-center gap-2">
            <div className="h-4 w-4 rounded-sm bg-accent" />
            <span className="text-[20px] font-black tracking-[-0.01em] text-txt-primary">YuyuFrame</span>
          </div>
          {/* Tout tient sans défilement une fois replié ; déplier les
              appareils peut demander un peu de place, d'où le `min-h-0`. */}
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto pr-0.5">
            <YuyuAccountPanel />
          </div>
        </motion.div>

        {/* Colonne droite — le skin en grand, puis les comptes Minecraft */}
        <motion.div variants={listItemVariants} className="flex min-h-0 flex-col gap-3">
          {/* Le skin prend toute la place qui reste au-dessus des comptes. */}
          <div className="relative flex min-h-[220px] flex-1 flex-col overflow-hidden rounded-2xl border border-line bg-[radial-gradient(ellipse_at_50%_58%,rgba(75,63,207,0.22)_0%,transparent_72%)]">
            <div className="absolute bottom-[16%] left-1/2 h-[12px] w-[28%] -translate-x-1/2 rounded-full bg-accent/50 blur-[20px]" />
            <div ref={canvasContainerRef} className="relative z-10 min-h-0 flex-1">
              <canvas ref={canvasRef} className="block bg-transparent" />
            </div>
            <div className="relative z-10 flex flex-shrink-0 flex-col items-center gap-0.5 pb-2.5">
              {displayAccount ? (
                <>
                  <p className="text-[12px] font-bold text-txt-primary">{displayAccount.username}</p>
                  <p className={`text-[10px] ${displayAccount.uuid === uuid ? 'text-success' : 'text-txt-muted'}`}>
                    {displayAccount.uuid === uuid ? t('login.active') : t('login.preview')}
                  </p>
                </>
              ) : (
                <p className="text-[10px] text-txt-muted">{t('login.noAccount')}</p>
              )}
            </div>
          </div>

          {/* Comptes Minecraft — jamais de défilement : une colonne tant
              qu'il y a peu de comptes, deux au-delà, et une tuile « + N »
              qui ouvre la liste complète quand ça ne tient plus. Une seule
              porte d'entrée pour en ajouter un, Microsoft ou hors ligne. */}
          <div className="flex flex-shrink-0 flex-col gap-2">
            <SectionTitle
              action={
                accounts.length > visibleAccounts.length ? (
                  <button
                    onClick={() => setShowAllAccounts(true)}
                    className="text-[11px] text-txt-muted transition-colors duration-150 hover:text-txt-primary"
                  >
                    {t('login.allAccounts', { count: accounts.length })}
                  </button>
                ) : undefined
              }
            >
              {t('login.minecraftAccounts')}
            </SectionTitle>
            <motion.div
              layout
              className={`grid gap-2 ${twoColumns ? 'grid-cols-2' : 'grid-cols-1'}`}
            >
              <AnimatePresence initial={false}>
                {visibleAccounts.map((acc) => (
                  <AccountRow
                    key={acc.uuid}
                    acc={acc}
                    compact={twoColumns}
                    isActive={acc.uuid === uuid}
                    skin={skins[acc.uuid]}
                    onSelect={() => handleSelect(acc)}
                    onRemove={() => handleRemove(acc)}
                    onHover={() => setPreviewUuid(acc.uuid)}
                    onLeave={() => setPreviewUuid(null)}
                    onEditSkin={() => navigate(`/skins?account=${acc.uuid}`)}
                  />
                ))}
              </AnimatePresence>

              {hiddenCount > 0 && (
                <MoreTile count={hiddenCount} onClick={() => setShowAllAccounts(true)} />
              )}
              {/* Le bouton d'ajout ne reste dans la grille que s'il y tient :
                  sinon il attend dans la modale « tous les comptes ». */}
              {step === 'idle' && addFitsInGrid && (
                <AddRow compact={twoColumns} onClick={() => setShowAddModal(true)} />
              )}
            </motion.div>
          </div>

          {/* Connexion Microsoft en cours (le gros de ce parcours se passe
              dans la fenêtre compacte `overlayActive`, voir plus haut). */}
          {(step === 'loading' || step === 'polling' || step === 'error') && (
            <motion.div
              variants={fadeVariants}
              initial="initial"
              animate="animate"
              className="flex-shrink-0 rounded-2xl border border-line bg-surface-1 p-4"
            >
              {step === 'loading' && (
                <div className="flex items-center justify-center gap-3 py-2">
                  <span className="h-4 w-4 animate-spin-slow rounded-full border-2 border-line-strong border-t-accent" />
                  <span className="text-[13px] text-txt-secondary">{t('login.connecting')}</span>
                </div>
              )}

              {step === 'polling' && (
                <div className="flex flex-col gap-4">
                  <p className="text-center text-[12px] text-[rgba(255,255,255,0.45)]">
                    {t('login.enterCodeOnMicrosoft')}
                  </p>
                  <button
                    onClick={() => { navigator.clipboard.writeText(userCode); setCopied(true); setTimeout(() => setCopied(false), 2000) }}
                    className={`rounded-xl py-3 text-center transition-all duration-150 border ${copied ? 'bg-[rgba(74,222,128,0.08)] border-[rgba(74,222,128,0.3)]' : 'bg-[rgba(0,0,0,0.4)] border-[rgba(255,255,255,0.08)]'}`}
                    title={t('login.clickToCopy')}
                  >
                    <span className="font-mono font-black text-white text-[26px] tracking-[0.25em]">
                      {userCode}
                    </span>
                    <p className={`text-[10px] mt-1 ${copied ? 'text-[rgb(134,239,172)]' : 'text-[rgba(255,255,255,0.2)]'}`}>
                      {copied ? t('login.copiedExclaim') : t('login.clickToCopy')}
                    </p>
                  </button>
                  <div className="flex gap-2">
                    <button
                      onClick={() => open(verifyUrl)}
                      className="flex-1 rounded-xl py-2 text-sm font-medium text-white transition-all duration-150 bg-[rgba(75,63,207,0.15)] border border-[rgba(75,63,207,0.3)] hover:bg-[rgba(75,63,207,0.3)]"
                    >
                      {t('login.openMicrosoft')}
                    </button>
                    <button
                      onClick={() => { stopPolling(); setStep('idle') }}
                      className="rounded-xl px-4 py-2 text-sm transition-all duration-150 text-[rgba(255,255,255,0.3)] border border-[rgba(255,255,255,0.07)] hover:text-[rgba(255,255,255,0.65)]"
                    >
                      {t('common.cancel')}
                    </button>
                  </div>
                  <div className="flex items-center justify-center gap-2">
                    <span className="h-3 w-3 animate-spin-slow rounded-full border border-[rgba(255,255,255,0.12)] border-t-[#4B3FCF]" />
                    <span className="text-[11px] text-[rgba(255,255,255,0.35)]">
                      {t('login.waitingForConfirmation')}
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
                      {copied ? t('login.copiedCheck') : t('console.copy')}
                    </button>
                    <button
                      onClick={() => setStep('idle')}
                      className="flex-1 rounded-xl py-2 text-sm transition-all duration-150 text-[rgba(255,255,255,0.4)] border border-[rgba(255,255,255,0.08)] hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(255,255,255,0.7)]"
                    >
                      {t('login.retry')}
                    </button>
                  </div>
                </div>
              )}
            </motion.div>
          )}
        </motion.div>
      </motion.div>

      <AnimatePresence>
        {showAllAccounts && (
          <ModalShell
            title={t('login.allAccountsTitle')}
            onClose={() => setShowAllAccounts(false)}
            maxWidth="max-w-xl"
          >
            <motion.div
              variants={listVariants}
              initial="initial"
              animate="animate"
              className="flex max-h-[60vh] flex-col gap-2 overflow-y-auto pr-0.5"
            >
              {/* Quand la grille est pleine, c'est ici qu'on ajoute un compte. */}
              {!addFitsInGrid && (
                <AddRow onClick={() => { setShowAllAccounts(false); setShowAddModal(true) }} />
              )}
              <AnimatePresence initial={false}>
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
                    onEditSkin={() => navigate(`/skins?account=${acc.uuid}`)}
                  />
                ))}
              </AnimatePresence>
            </motion.div>
          </ModalShell>
        )}
        {showAddModal && (
          <AddAccountModal
            onClose={() => setShowAddModal(false)}
            onMicrosoft={startLogin}
            onOffline={() => { setShowOfflineModal(true); api.analytics.track('offline_account_modal_opened') }}
          />
        )}
        {showOfflineModal && (
          <OfflineAccountModal
            onClose={() => setShowOfflineModal(false)}
            onAdded={(acc) => {
              addAccount(acc.username, acc.uuid, acc.is_offline)
              api.yuyu.syncMinecraftAccounts().catch(() => {})
              navigate('/home')
            }}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

/**
 * Une ligne de compte Minecraft. En mode `compact` (deux colonnes), les
 * libellés des boutons laissent la place aux icônes : la carte est deux fois
 * moins large, mais on peut toujours tout faire.
 */
function AccountRow({
  acc, isActive, skin, compact, onSelect, onRemove, onHover, onLeave, onEditSkin,
}: {
  acc: Account
  isActive: boolean
  skin?: string
  compact?: boolean
  onSelect: () => void
  onRemove: () => void
  onHover: () => void
  onLeave: () => void
  onEditSkin: () => void
}) {
  const t = useT()
  const [hovered, setHovered] = useState(false)
  const offline = acc.is_offline

  return (
    <motion.div
      layout
      variants={listItemVariants}
      // La ligne s'en va sur le côté : on voit clairement quel compte part.
      exit={{ opacity: 0, x: -12, transition: fastTransition }}
      whileHover={{ y: -1 }}
      transition={fastTransition}
      className={`flex items-center gap-3 rounded-xl border p-3 transition-colors duration-150 ease-out ${
        isActive
          ? 'border-accent/35 bg-accent/10 shadow-[0_0_24px_rgba(75,63,207,0.1)]'
          : hovered
            ? 'border-line-strong bg-surface-2'
            : 'border-line bg-surface-1'
      }`}
      onMouseEnter={() => { setHovered(true); onHover() }}
      onMouseLeave={() => { setHovered(false); onLeave() }}
    >
      {/* Avatar — la tête tirée du skin choisi (voir SkinFace, qui superpose
          bien les deux couches), sinon l'avatar du compte. Les comptes hors
          ligne passent par la même voie : leur UUID est inventé, le service
          rend donc l'apparence par défaut, ce qui est exactement ce qu'un
          compte sans skin doit montrer. L'initiale ne reste que sans réseau. */}
      <div className="relative flex-shrink-0">
        {skin ? (
          <SkinFace dataUri={skin} size={44} className="rounded-lg" />
        ) : (
          <>
            <img
              src={`https://mc-heads.net/avatar/${acc.uuid}/44`}
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
        <p className="truncate text-[13px] font-semibold text-txt-primary">{acc.username}</p>
        <p className={`mt-px text-[10px] ${isActive ? 'text-success' : 'text-txt-muted'}`}>
          {isActive ? t('login.active') : t('login.savedAccount')}
        </p>
      </div>

      {/* Actions */}
      <div className="flex flex-shrink-0 items-center gap-1.5">
        {isActive ? (
          <button
            onClick={onSelect}
            title={t('login.play')}
            className="rounded-lg border border-accent/45 bg-accent/25 px-3 py-1.5 text-[13px] font-medium text-txt-primary transition-colors duration-150 ease-out hover:bg-accent/40"
          >
            {compact ? '▶' : t('login.play')}
          </button>
        ) : (
          <button
            onClick={onSelect}
            title={t('login.select')}
            className="rounded-lg border border-line px-3 py-1.5 text-[13px] text-txt-secondary transition-colors duration-150 ease-out hover:border-accent/45 hover:text-txt-primary"
          >
            {compact ? '▶' : t('login.select')}
          </button>
        )}
        {/* Le skin se gère désormais pour les deux sortes de compte, sur
            l'écran dédié (Fonctionnalités → Skins) : c'est là que vivent les
            deux sources, l'aperçu 3D et le choix du modèle. Ici on n'y mène
            plus qu'en un clic, sur le bon compte. */}
        <button
          onClick={onEditSkin}
          title={t('login.changeSkin')}
          className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.2)] border border-[rgba(255,255,255,0.05)] bg-transparent hover:text-[rgba(255,255,255,0.7)] hover:border-[rgba(255,255,255,0.15)]"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className="w-[13px] h-[13px]">
            <path d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M4 6h16v12H4V6z" />
          </svg>
        </button>
        <button
          onClick={onRemove}
          className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.2)] border border-[rgba(255,255,255,0.05)] bg-transparent hover:text-[rgb(252,165,165)] hover:border-[rgba(200,50,50,0.3)] hover:bg-[rgba(200,50,50,0.08)]"
          title={t('login.disconnect')}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" className="w-[13px] h-[13px]">
            <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
          </svg>
        </button>
      </div>
    </motion.div>
  )
}

/** Unique bouton d'ajout : il ouvre le choix Microsoft / hors ligne. */
function AddRow({ onClick, compact }: { onClick: () => void; compact?: boolean }) {
  const t = useT()
  return (
    <motion.button
      layout
      variants={listItemVariants}
      {...pressable}
      onClick={onClick}
      className="flex w-full items-center gap-3 rounded-xl border-[1.5px] border-dashed border-line bg-surface-1 p-3 text-txt-muted transition-colors duration-150 ease-out hover:border-accent/40 hover:bg-accent/5 hover:text-txt-secondary"
    >
      <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-lg border border-line bg-surface-2">
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M12 5v14M5 12h14" strokeLinecap="round" />
        </svg>
      </div>
      <div className="min-w-0 text-left">
        <p className="truncate text-[13px] font-semibold">{t('login.addAccount')}</p>
        {!compact && <p className="mt-0.5 text-[10px] text-txt-muted">{t('login.connectViaMicrosoft')}</p>}
      </div>
    </motion.button>
  )
}

/** Tuile « + N » : les comptes qui ne tiennent pas à l'écran, sur un clic. */
function MoreTile({ count, onClick }: { count: number; onClick: () => void }) {
  const t = useT()
  return (
    <motion.button
      layout
      variants={listItemVariants}
      {...pressable}
      onClick={onClick}
      className="flex w-full items-center gap-3 rounded-xl border border-line bg-surface-1 p-3 text-txt-secondary transition-colors duration-150 ease-out hover:border-accent/40 hover:bg-surface-2 hover:text-txt-primary"
    >
      <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-lg bg-surface-3 text-[15px] font-black text-txt-primary">
        +{count}
      </div>
      <div className="min-w-0 text-left">
        <p className="truncate text-[13px] font-semibold">{t('login.showMore')}</p>
        <p className="mt-0.5 truncate text-[10px] text-txt-muted">{t('login.otherAccounts', { count })}</p>
      </div>
    </motion.button>
  )
}
