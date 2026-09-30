import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import type { McAccountInfo, SkinRef, SkinVariant } from '@/api/client'
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
 * ── Un skin est une URL, pas un fichier ───────────────────────────────────
 * Voir `commands/account/skin.rs` pour le raisonnement complet. En bref : un
 * skin ne vaut que s'il est vu par les autres joueurs, donc il doit être
 * hébergé quelque part de public. Nous n'avons pas de stockage à offrir pour
 * ça, alors on ne manipule que des skins déjà hébergés — celui d'un compte
 * premium (Mojang l'héberge) ou une URL fournie par l'utilisateur.
 *
 * L'ancien choix « fichier local » a donc disparu : un PNG sur le disque de
 * quelqu'un n'est visible de personne, et il ne pourra pas l'être.
 *
 * ── Ce que « appliquer » veut dire, selon le compte ───────────────────────
 * Microsoft : le skin part chez Mojang et devient réel partout. C'est une
 * modification du compte, annoncée comme telle sous le bouton.
 * Hors ligne : la référence reste chez nous et ne change que l'affichage du
 * launcher, jusqu'à ce que l'agent sache la poser en jeu.
 */

type Tab = 'player' | 'url'

/** Skin en attente de validation — choisi, vérifié, pas encore appliqué. */
interface Candidate {
  url: string
  variant: SkinVariant
  dataUri: string
  origin: string
  /** Pseudo du joueur d'où il vient, pour l'annoncer à l'écran. */
  fromPlayer?: string
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

  // Skin du compte choisi. Pour un compte Microsoft jamais passé par ici, la
  // référence locale est vide alors que Mojang, lui, sert bien un skin : on le
  // demande à Mojang pour que l'écran montre la vérité plutôt qu'un vide.
  useEffect(() => {
    if (!account) return
    let cancelled = false
    setCurrent(null)
    setCurrentUri(null)
    setCandidate(null)
    setJustApplied(false)

    api.skin.current(account.mc_uuid)
      .then(async (ref) => {
        if (cancelled) return
        if (ref) {
          setCurrent(ref)
          const uri = await api.skin.preview(account.mc_uuid).catch(() => null)
          if (!cancelled) setCurrentUri(uri)
          return
        }
        if (account.is_offline) return
        const mojang = await api.skin.ofAccount(account.mc_uuid).catch(() => null)
        if (cancelled || !mojang) return
        setCurrent({ url: mojang.url, variant: mojang.variant, origin: 'mojang' })
        setCurrentUri(mojang.data_uri)
      })
      .catch(() => {})

    return () => { cancelled = true }
  }, [account?.mc_uuid, account?.is_offline])

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
        url: found.url,
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
      setCandidate({
        url: checked.url,
        // Rien dans les pixels ne distingue de façon fiable un skin fin d'un
        // skin classique : on part du modèle le plus courant et on laisse
        // choisir juste en dessous.
        variant: 'classic',
        dataUri: checked.data_uri,
        origin: 'url',
      })
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
      const saved = await api.skin.apply(account.mc_uuid, candidate.url, candidate.variant, candidate.origin)
      setCurrent(saved)
      setCurrentUri(candidate.dataUri)
      setCandidate(null)
      setJustApplied(true)
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
                    ? candidate.fromPlayer
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
                    {(['player', 'url'] as const).map((m) => (
                      <button
                        key={m}
                        onClick={() => setTab(m)}
                        className={`flex-1 rounded-lg py-2 text-[12.5px] font-semibold transition-colors ${
                          tab === m ? 'bg-accent text-white' : 'text-txt-secondary hover:text-txt-primary'
                        }`}
                      >
                        {m === 'player' ? t('skins.sourcePlayer') : t('skins.sourceUrl')}
                      </button>
                    ))}
                  </div>

                  {tab === 'player' ? (
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
                  ) : (
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

                      <p className="text-[12px] leading-relaxed text-txt-secondary">
                        {account?.is_offline ? t('skins.offlineNotice') : t('skins.mojangNotice')}
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
          </div>
        )}
      </div>
    </div>
  )
}

/** Libellé lisible de l'origine enregistrée (`player:Notch`, `url`, `mojang`). */
function originLabel(origin: string, t: (k: string, v?: Record<string, string | number>) => string): string {
  if (origin.startsWith('player:')) return t('skins.originPlayer', { name: origin.slice('player:'.length) })
  if (origin === 'mojang') return t('skins.originMojang')
  return t('skins.originUrl')
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
