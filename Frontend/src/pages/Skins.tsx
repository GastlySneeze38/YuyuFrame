import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { open as openFileDialog } from '@tauri-apps/plugin-dialog'
import { AnimatePresence, motion } from 'framer-motion'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import type { McAccountInfo, SkinHistoryEntry, SkinKind, SkinRef, SkinVariant } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError } from '@/stores/useErrorToast'
import { fadeVariants, fastTransition } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Skins — l'écran unique, pour les comptes Microsoft comme pour les comptes
 * hors ligne.
 *
 * ── Trois sources, et ce qu'elles deviennent ──────────────────────────────
 * Un skin doit être hébergé quelque part pour que les autres joueurs le
 * voient ; nous n'hébergeons rien (voir `commands/account/skin.rs`). D'où :
 *
 *   joueur   le skin de n'importe quel compte premium — Mojang l'héberge déjà
 *   URL      n'importe quel PNG public — son hébergeur s'en charge
 *   fichier  un PNG du disque. Sur un compte Microsoft il est ENVOYÉ à Mojang,
 *            qui l'héberge : il rejoint donc les deux autres. Sur un compte
 *            hors ligne, il n'y a personne à qui l'envoyer, et il ne vit que
 *            dans la base du launcher — ce que l'écran dit avant d'appliquer.
 *
 * ── L'historique est le nôtre ─────────────────────────────────────────────
 * Mojang ne sert que le skin *courant* d'un profil, jamais les précédents.
 * Impossible donc de reconstituer un passé : on enregistre ce qui est appliqué
 * (`db/skin_history.rs`), pour les deux sortes de compte, et on amorce la liste
 * d'un compte Microsoft avec le skin qu'il porte quand on le découvre.
 */

type Tab = 'player' | 'url' | 'file'

/** Skin choisi et vérifié, pas encore appliqué. */
interface Candidate {
  kind: SkinKind
  source: string
  variant: SkinVariant
  dataUri: string
  origin: string
  /** Pseudo du joueur d'où il vient, pour l'annoncer à l'écran. */
  fromPlayer?: string
  /** Vrai quand il sort de l'historique : le libellé d'aperçu le dit. */
  fromHistory?: boolean
}

export default function Skins() {
  const t = useT()
  const [params, setParams] = useSearchParams()
  const [accounts, setAccounts] = useState<McAccountInfo[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('player')

  const [current, setCurrent] = useState<SkinRef | null>(null)
  const [currentUri, setCurrentUri] = useState<string | null>(null)
  const [candidate, setCandidate] = useState<Candidate | null>(null)
  const [history, setHistory] = useState<SkinHistoryEntry[]>([])

  const [playerName, setPlayerName] = useState('')
  const [url, setUrl] = useState('')
  const [searching, setSearching] = useState(false)
  const [applying, setApplying] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [justApplied, setJustApplied] = useState(false)

  useEffect(() => {
    api.mc.accounts()
      .then((accs) => {
        setAccounts(accs)
        // Compte demandé par l'URL (lien depuis l'écran Comptes), à défaut le
        // compte actif, à défaut le premier.
        const wanted = params.get('account')
        const found = accs.find((a) => a.mc_uuid === wanted) ?? accs.find((a) => a.is_active) ?? accs[0]
        setSelected(found?.mc_uuid ?? null)
      })
      .catch((e) => { setAccounts([]); showError(e) })
  }, [])

  const account = useMemo(
    () => accounts?.find((a) => a.mc_uuid === selected) ?? null,
    [accounts, selected],
  )

  const loadHistory = useCallback((uuid: string) => {
    api.skin.history(uuid).then(setHistory).catch(() => setHistory([]))
  }, [])

  // Skin du compte choisi. Pour un compte Microsoft jamais passé par ici, la
  // référence locale est vide alors que Mojang, lui, sert bien un skin : on le
  // demande à Mojang pour que l'écran montre la vérité plutôt qu'un vide — et
  // cet appel amorce du même coup son historique (voir skin_of_account).
  useEffect(() => {
    if (!account) return
    let cancelled = false
    setCurrent(null)
    setCurrentUri(null)
    setCandidate(null)
    setHistory([])
    setJustApplied(false)

    const uuid = account.mc_uuid
    api.skin.current(uuid)
      .then(async (ref) => {
        if (cancelled) return
        if (ref) {
          setCurrent(ref)
          const uri = await api.skin.preview(uuid).catch(() => null)
          if (!cancelled) setCurrentUri(uri)
        } else if (!account.is_offline) {
          const mojang = await api.skin.ofAccount(uuid).catch(() => null)
          if (!cancelled && mojang) {
            setCurrent({ kind: 'url', source: mojang.url, variant: mojang.variant, origin: 'mojang' })
            setCurrentUri(mojang.data_uri)
          }
        }
        // Après l'amorçage, jamais avant : sinon la liste arriverait sans le
        // skin que Mojang vient de nous apprendre.
        if (!cancelled) loadHistory(uuid)
      })
      .catch(() => {})

    return () => { cancelled = true }
  }, [account?.mc_uuid, account?.is_offline, loadHistory])

  const shown = candidate ?? (current && currentUri ? { ...current, dataUri: currentUri } : null)

  const pickAccount = (uuid: string) => {
    setSelected(uuid)
    // L'URL suit la sélection : revenir sur l'écran ou le recharger garde le
    // compte sous les yeux.
    setParams(uuid ? { account: uuid } : {}, { replace: true })
  }

  const searchPlayer = async () => {
    if (!playerName.trim() || searching) return
    setSearching(true)
    try {
      const found = await api.skin.resolvePlayer(playerName.trim())
      setCandidate({
        kind: 'url',
        source: found.url,
        variant: found.variant,
        dataUri: found.data_uri,
        origin: `player:${found.username}`,
        fromPlayer: found.username,
      })
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setSearching(false)
    }
  }

  const checkUrl = async () => {
    if (!url.trim() || searching) return
    setSearching(true)
    try {
      const checked = await api.skin.checkUrl(url.trim())
      // Rien dans les pixels ne distingue de façon fiable un skin fin d'un skin
      // classique : on part du modèle le plus courant et on laisse choisir.
      setCandidate({ ...checked, dataUri: checked.data_uri, origin: 'url' })
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setSearching(false)
    }
  }

  const pickFile = async () => {
    if (searching) return
    const picked = await openFileDialog({ filters: [{ name: 'Skin Minecraft', extensions: ['png'] }] })
    if (typeof picked !== 'string') return
    setSearching(true)
    try {
      const imported = await api.skin.importFile(picked)
      setCandidate({ ...imported, dataUri: imported.data_uri, origin: 'file' })
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setSearching(false)
    }
  }

  const apply = async () => {
    if (!account || !candidate || applying) return
    setApplying(true)
    try {
      const saved = await api.skin.apply(
        account.mc_uuid,
        candidate.kind,
        candidate.source,
        candidate.variant,
        candidate.origin,
      )
      setCurrent(saved)
      setCurrentUri(candidate.dataUri)
      setCandidate(null)
      setJustApplied(true)
      loadHistory(account.mc_uuid)
    } catch (e) {
      showError(e)
    } finally {
      setApplying(false)
    }
  }

  const remove = async () => {
    if (!account || removing) return
    setRemoving(true)
    try {
      await api.skin.remove(account.mc_uuid)
      setCurrent(null)
      setCurrentUri(null)
      setCandidate(null)
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setRemoving(false)
    }
  }

  const forget = async (id: number) => {
    if (!account) return
    try {
      await api.skin.historyForget(account.mc_uuid, id)
      loadHistory(account.mc_uuid)
    } catch (e) {
      showError(e)
    }
  }

  return (
    <div className="relative h-full overflow-y-auto bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('skins.title')}
          </h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('skins.subtitle')}</p>
        </div>
      </PageHeader>

      <div className="mx-auto w-full max-w-5xl px-7 py-8">
        {accounts === null ? (
          <div className="flex h-64 items-center justify-center">
            <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
          </div>
        ) : accounts.length === 0 ? (
          <div className="flex h-64 flex-col items-center justify-center gap-2 text-center">
            <p className="text-[13px] font-semibold">{t('skins.noAccount')}</p>
            <p className="max-w-sm text-[12px] leading-relaxed text-txt-secondary">{t('skins.noAccountHint')}</p>
          </div>
        ) : (
          <div className="flex flex-col gap-6">
            <AccountPicker accounts={accounts} selected={selected} onPick={pickAccount} />

            <div className="grid gap-6 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
              <SkinPreview
                dataUri={shown?.dataUri ?? null}
                variant={shown?.variant ?? 'classic'}
                label={
                  candidate
                    ? candidate.fromHistory
                      ? t('skins.previewFromHistory')
                      : candidate.fromPlayer
                        ? t('skins.previewFromPlayer', { name: candidate.fromPlayer })
                        : t('skins.previewPending')
                    : current
                      ? t('skins.previewCurrent')
                      : t('skins.previewNone')
                }
                pending={!!candidate}
              />

              <div className="flex flex-col gap-5">
                <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
                  <div className="flex gap-1 rounded-xl bg-surface-2 p-1">
                    {(['player', 'url', 'file'] as const).map((m) => (
                      <button
                        key={m}
                        onClick={() => setTab(m)}
                        className={`flex-1 rounded-lg py-2 text-[12.5px] font-semibold transition-colors ${
                          tab === m ? 'bg-accent text-white' : 'text-txt-secondary hover:text-txt-primary'
                        }`}
                      >
                        {t(`skins.source${m[0].toUpperCase()}${m.slice(1)}`)}
                      </button>
                    ))}
                  </div>

                  {tab === 'player' && (
                    <>
                      <p className="text-[12px] leading-relaxed text-txt-secondary">{t('skins.playerHint')}</p>
                      <div className="flex gap-2">
                        <input
                          value={playerName}
                          onChange={(e) => setPlayerName(e.target.value)}
                          onKeyDown={(e) => { if (e.key === 'Enter') searchPlayer() }}
                          placeholder={t('skins.playerPlaceholder')}
                          maxLength={16}
                          className="h-11 min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3.5 text-[13px] text-txt-primary outline-none transition-colors focus:border-accent/50"
                        />
                        <Button onClick={searchPlayer} loading={searching} disabled={!playerName.trim()}>
                          {t('skins.search')}
                        </Button>
                      </div>
                    </>
                  )}

                  {tab === 'url' && (
                    <>
                      <p className="text-[12px] leading-relaxed text-txt-secondary">{t('skins.urlHint')}</p>
                      <div className="flex gap-2">
                        <input
                          value={url}
                          onChange={(e) => setUrl(e.target.value)}
                          onKeyDown={(e) => { if (e.key === 'Enter') checkUrl() }}
                          placeholder="https://.../skin.png"
                          className="h-11 min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3.5 text-[13px] text-txt-primary outline-none transition-colors focus:border-accent/50"
                        />
                        <Button onClick={checkUrl} loading={searching} disabled={!url.trim()}>
                          {t('skins.check')}
                        </Button>
                      </div>
                    </>
                  )}

                  {tab === 'file' && (
                    <>
                      <p className="text-[12px] leading-relaxed text-txt-secondary">
                        {account?.is_offline ? t('skins.fileHintOffline') : t('skins.fileHintMojang')}
                      </p>
                      <Button onClick={pickFile} loading={searching}>{t('skins.chooseFile')}</Button>
                    </>
                  )}
                </div>

                <AnimatePresence mode="wait">
                  {candidate && (
                    <motion.div
                      key="candidate"
                      variants={fadeVariants}
                      initial="initial"
                      animate="animate"
                      exit="exit"
                      transition={fastTransition}
                      className="flex flex-col gap-4 rounded-2xl border border-accent/30 bg-accent/5 p-5"
                    >
                      <VariantPicker
                        value={candidate.variant}
                        onChange={(variant) => setCandidate({ ...candidate, variant })}
                      />

                      {/* Un fichier sur un compte hors ligne est le seul cas qui
                          ne survit pas à l'effacement de la base : il faut le
                          dire avant, pas après. */}
                      {candidate.kind === 'local' && account?.is_offline && (
                        <p className="rounded-xl border border-warning/35 bg-warning/10 p-3 text-[12px] leading-relaxed text-txt-secondary">
                          {t('skins.localWarning')}
                        </p>
                      )}

                      <p className="text-[12px] leading-relaxed text-txt-secondary">
                        {account?.is_offline
                          ? t('skins.offlineNotice')
                          : candidate.kind === 'local'
                            ? t('skins.fileToMojangNotice')
                            : t('skins.mojangNotice')}
                      </p>

                      <div className="flex flex-wrap gap-2">
                        <Button variant="primary" onClick={apply} loading={applying}>
                          {t('skins.apply')}
                        </Button>
                        <Button variant="ghost" onClick={() => setCandidate(null)} disabled={applying}>
                          {t('skins.cancel')}
                        </Button>
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>

                {!candidate && current && (
                  <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
                    <div className="flex flex-col gap-1">
                      <p className="text-[12.5px] font-semibold">{t('skins.currentTitle')}</p>
                      <p className="text-[11.5px] text-txt-muted">{originLabel(current.origin, t)}</p>
                    </div>
                    {justApplied && (
                      <p className="text-[12px] font-medium text-success">
                        {account?.is_offline ? t('skins.appliedOffline') : t('skins.appliedMojang')}
                      </p>
                    )}
                    <div>
                      <Button variant="danger" size="sm" onClick={remove} loading={removing}>
                        {t('skins.remove')}
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            </div>

            <History
              entries={history}
              currentSource={current?.source ?? null}
              currentVariant={current?.variant ?? null}
              onRestore={(e) => {
                setCandidate({
                  kind: e.kind,
                  source: e.source,
                  variant: e.variant,
                  dataUri: e.data_uri!,
                  origin: e.origin,
                  fromHistory: true,
                })
                setJustApplied(false)
              }}
              onForget={forget}
            />
          </div>
        )}
      </div>
    </div>
  )
}

/** Libellé lisible de l'origine (`player:Notch`, `url`, `file`, `mojang`). */
function originLabel(origin: string, t: (k: string, v?: Record<string, string | number>) => string): string {
  if (origin.startsWith('player:')) return t('skins.originPlayer', { name: origin.slice('player:'.length) })
  if (origin === 'mojang') return t('skins.originMojang')
  if (origin === 'file') return t('skins.originFile')
  return t('skins.originUrl')
}

/**
 * Les skins déjà portés.
 *
 * Une entrée sans aperçu reste affichée : son hébergeur peut être momentanément
 * injoignable, et la faire disparaître donnerait à croire qu'on l'a perdue. Elle
 * n'est simplement pas restaurable tant qu'on ne peut pas la montrer.
 */
function History({
  entries,
  currentSource,
  currentVariant,
  onRestore,
  onForget,
}: {
  entries: SkinHistoryEntry[]
  currentSource: string | null
  currentVariant: SkinVariant | null
  onRestore: (entry: SkinHistoryEntry) => void
  onForget: (id: number) => void
}) {
  const t = useT()
  if (entries.length === 0) return null

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-0.5">
        <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-txt-muted">{t('skins.history')}</p>
        <p className="text-[11.5px] text-txt-secondary">{t('skins.historyHint')}</p>
      </div>

      <div className="grid grid-cols-[repeat(auto-fill,minmax(116px,1fr))] gap-2.5">
        {entries.map((e) => {
          const worn = e.source === currentSource && e.variant === currentVariant
          return (
            <div
              key={e.id}
              className={`group relative flex flex-col items-center gap-2 rounded-xl border p-3 transition-colors ${
                worn ? 'border-accent/45 bg-accent/10' : 'border-line bg-surface-1 hover:border-line-strong'
              }`}
            >
              {e.data_uri ? (
                // Tête recadrée depuis le gabarit : la face fait 8×8 à l'offset
                // (8,8) d'une texture large de 64.
                <div
                  className="h-14 w-14 rounded-lg [image-rendering:pixelated]"
                  style={{
                    backgroundImage: `url(${e.data_uri})`,
                    backgroundSize: '448px 448px',
                    backgroundPosition: '-56px -56px',
                  }}
                />
              ) : (
                <div className="flex h-14 w-14 items-center justify-center whitespace-pre-line rounded-lg bg-surface-3 text-center text-[9px] leading-tight text-txt-muted">
                  {t('skins.noPreview')}
                </div>
              )}

              <span className="text-[10.5px] text-txt-muted">{t(`skins.${e.variant}`)}</span>

              {worn ? (
                <span className="text-[10.5px] font-semibold text-accent-hover">{t('skins.worn')}</span>
              ) : (
                <button
                  onClick={() => onRestore(e)}
                  disabled={!e.data_uri}
                  className="text-[10.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {t('skins.restore')}
                </button>
              )}

              <button
                onClick={() => onForget(e.id)}
                title={t('skins.forget')}
                className="absolute right-1.5 top-1.5 flex h-5 w-5 items-center justify-center rounded-md text-txt-muted opacity-0 transition-all hover:bg-danger/20 hover:text-danger group-hover:opacity-100"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" width={11} height={11}>
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function AccountPicker({
  accounts,
  selected,
  onPick,
}: {
  accounts: McAccountInfo[]
  selected: string | null
  onPick: (uuid: string) => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2.5">
      <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-txt-muted">{t('skins.account')}</p>
      <div className="flex flex-wrap gap-2">
        {accounts.map((a) => {
          const active = a.mc_uuid === selected
          return (
            <button
              key={a.mc_uuid}
              onClick={() => onPick(a.mc_uuid)}
              className={`flex items-center gap-2.5 rounded-xl border px-3 py-2 text-left transition-colors ${
                active ? 'border-accent/45 bg-accent/12' : 'border-line bg-surface-1 hover:border-line-strong'
              }`}
            >
              {/* Les comptes hors ligne n'ont pas de profil Mojang : leur UUID
                  est inventé, le service d'avatars n'a rien à en dire. */}
              {a.is_offline ? (
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent/40 font-black text-white [font-family:monospace] text-[14px]">
                  {a.mc_username[0].toUpperCase()}
                </span>
              ) : (
                <img
                  src={`https://mc-heads.net/avatar/${a.mc_uuid}/32`}
                  alt=""
                  className="h-8 w-8 rounded-lg [image-rendering:pixelated]"
                />
              )}
              <span className="flex flex-col">
                <span className="text-[12.5px] font-semibold">{a.mc_username}</span>
                <span className="text-[10px] text-txt-muted">
                  {a.is_offline ? t('skins.offlineBadge') : t('skins.mojangBadge')}
                </span>
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

function VariantPicker({
  value,
  onChange,
}: {
  value: SkinVariant
  onChange: (variant: SkinVariant) => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2">
      <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-txt-muted">{t('skins.variant')}</p>
      <div className="flex gap-2">
        {(['classic', 'slim'] as const).map((v) => (
          <button
            key={v}
            onClick={() => onChange(v)}
            className={`flex-1 rounded-xl border px-3 py-2.5 text-left transition-colors ${
              value === v ? 'border-accent/50 bg-accent/15' : 'border-line bg-surface-1 hover:border-line-strong'
            }`}
          >
            <span className="block text-[12.5px] font-semibold">{t(`skins.${v}`)}</span>
            <span className="block text-[10.5px] text-txt-muted">{t(`skins.${v}Hint`)}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * Aperçu 3D.
 *
 * Le modèle est imposé, jamais deviné : c'est précisément le réglage qu'on est
 * en train de choisir juste à côté, et laisser `auto-detect` décider ferait
 * mentir l'aperçu sur ce qui sera appliqué. `skinview3d` nomme le modèle
 * classique « default » ; la traduction se fait ici, une seule fois.
 */
function SkinPreview({
  dataUri,
  variant,
  label,
  pending,
}: {
  dataUri: string | null
  variant: SkinVariant
  label: string
  pending: boolean
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)

  const load = useCallback((uri: string | null, model: SkinVariant) => {
    const viewer = viewerRef.current
    if (!viewer) return
    if (!uri) {
      viewer.loadSkin(null)
      return
    }
    ;(viewer.loadSkin(uri, { model: model === 'slim' ? 'slim' : 'default' }) as Promise<void> | void)?.catch?.(() => {})
  }, [])

  useEffect(() => {
    if (!canvasRef.current || !boxRef.current) return
    const box = boxRef.current
    const { width, height } = box.getBoundingClientRect()

    // Fond transparent : `skinview3d` crée son contexte WebGL sans `alpha`,
    // et three.js le laisse alors à `false` — son effacement peindrait donc du
    // noir. Créer le contexte avant lui impose nos attributs, la spécification
    // WebGL exigeant que les `getContext` suivants du même type rendent le
    // contexte déjà créé. Même raisonnement que SkinBust.
    canvasRef.current.getContext('webgl2', { alpha: true, premultipliedAlpha: true })
      ?? canvasRef.current.getContext('webgl', { alpha: true, premultipliedAlpha: true })

    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: width || 300,
      height: height || 380,
    })
    viewer.background = null
    viewer.autoRotate = true
    viewer.autoRotateSpeed = 0.6
    viewer.zoom = 0.82
    viewer.fov = 55
    viewer.animation = new WalkingAnimation()
    viewer.animation.speed = 0.4
    viewerRef.current = viewer

    const ro = new ResizeObserver(() => {
      const r = box.getBoundingClientRect()
      if (r.width > 0 && r.height > 0) viewer.setSize(r.width, r.height)
    })
    ro.observe(box)

    return () => {
      ro.disconnect()
      viewer.dispose()
      viewerRef.current = null
    }
  }, [])

  useEffect(() => { load(dataUri, variant) }, [dataUri, variant, load])

  return (
    <div
      className={`flex flex-col gap-3 rounded-2xl border p-5 transition-colors ${
        pending ? 'border-accent/30 bg-accent/5' : 'border-line bg-surface-1'
      }`}
    >
      <div ref={boxRef} className="relative h-[340px] w-full">
        <canvas ref={canvasRef} className="h-full w-full" />
      </div>
      <p className="text-center text-[11.5px] text-txt-secondary">{label}</p>
    </div>
  )
}
