import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import type { CatalogSkin } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError } from '@/stores/useErrorToast'
import { bakeSkin } from '@/lib/skinBake'
import { fadeVariants, fastTransition } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Catalogue de skins — l'écran d'avant.
 *
 * ── Ce qu'il résout ───────────────────────────────────────────────────────
 * Les trois sources de l'écran Skins supposent qu'on sait déjà quel skin on
 * veut : un pseudo, une adresse, un fichier. Il manquait l'endroit où l'on
 * regarde ce qui existe. Ely.by tient un catalogue public de plusieurs
 * milliers de skins, et une de ses entrées est déjà une référence applicable
 * telle quelle — d'où le peu de code qu'il a fallu pour l'ajouter.
 *
 * Cet écran ne modifie jamais un compte. Il désigne, et renvoie à l'écran
 * Skins avec le skin en main : c'est là-bas, et seulement là-bas, qu'on
 * applique — avec la confirmation qui va avec.
 *
 * ── Les étiquettes tiennent lieu de recherche ─────────────────────────────
 * Les skins du catalogue n'ont pas de titre, seulement des étiquettes. Il n'y
 * a donc rien à chercher par nom, et le champ en haut filtre par étiquette.
 * C'est une limite de la source, pas un choix.
 *
 * ── Pagination : un index global, pas des tranches ────────────────────────
 * Ely.by sert 40 skins par appel, taille imposée, et notre grille en affiche
 * ce qui tient — rarement un diviseur de 40. Découper chaque lot de 40 en
 * tranches donnait donc une dernière tranche à moitié vide, puis une attente
 * en plein milieu du parcours, à un moment qui semblait arbitraire.
 *
 * On raisonne donc sur une liste continue : la page N montre les éléments
 * [N×places, (N+1)×places). Les lots Ely.by qu'il faut pour la couvrir — un,
 * parfois deux — sont demandés et **gardés en mémoire**, et le suivant est
 * préchargé. Résultat : toutes les pages sont pleines, et franchir une
 * frontière de lot ne se voit pas.
 *
 * ── Le rendu 3D ───────────────────────────────────────────────────────────
 * Chaque case montre un rendu 3D cuit en image fixe (`lib/skinBake.ts`), et
 * la case survolée seulement passe en 3D vivante. Un `SkinViewer` par case
 * voudrait dire un contexte WebGL par case, et les navigateurs en plafonnent
 * une quinzaine.
 */

/** Taille d'un lot Ely.by. Imposée : leur `limit` est ignoré. */
const REMOTE_PAGE_SIZE = 40

/**
 * Trois rangées, quitte à rétrécir les cases.
 *
 * La hauteur des cases se déduit de la place disponible au lieu d'être fixée :
 * une hauteur fixe laissait soit un vide sous la grille, soit deux rangées là
 * où trois tenaient de justesse.
 */
const TARGET_ROWS = 3

/** En dessous, la case ne montre plus un personnage mais une vignette : on
 *  préfère alors retirer une rangée. */
const MIN_TILE_HEIGHT = 116

/** Largeur sur hauteur d'une case. Un personnage est plus haut que large, et
 *  la case suit, sinon le rendu flotte au milieu de vide. */
const TILE_ASPECT = 0.7

const GRID_GAP = 12
const MIN_COLUMNS = 2
const MAX_COLUMNS = 10

/**
 * Lots gardés de part et d'autre de la position.
 *
 * Parcourir longtemps retiendrait sinon tout ce qu'on a vu : les entrées du
 * catalogue sont légères, mais les aperçus (le PNG du skin) et surtout les
 * rendus cuits (une image de 180×288 par skin) ne le sont pas. À quelques
 * dizaines de kilo-octets pièce, cent pages feraient des dizaines de
 * méga-octets de data URI vivant dans l'état React.
 *
 * Un de chaque côté suffit : celui d'avant pour que reculer soit instantané,
 * celui d'après étant déjà préchargé. Au plus quatre lots en mémoire, soit
 * ~160 skins. Ce qui sort de cette fenêtre est déchargé — et le redemander
 * est de toute façon bon marché, le PNG restant dans le cache disque du Rust.
 */
const KEEP_RADIUS = 1

/** Délai avant de passer une case en 3D vivante. Un balayage rapide de la
 *  souris traverse une rangée entière : sans ce répit, on créerait et
 *  détruirait un contexte WebGL par case au passage. */
const HOVER_DELAY_MS = 130

interface Tiling {
  columns: number
  rows: number
  tileHeight: number
}

export default function SkinCatalog() {
  const t = useT()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const account = params.get('account')

  const [tags, setTags] = useState<string[]>([])
  const [tagInput, setTagInput] = useState('')
  const [slimOnly, setSlimOnly] = useState(false)

  /** Lots Ely.by déjà reçus, par numéro. */
  const [pages, setPages] = useState<Map<number, CatalogSkin[]>>(() => new Map())
  const [failed, setFailed] = useState<Set<number>>(() => new Set())
  const [lastRemotePage, setLastRemotePage] = useState(1)

  /** Index, dans la liste continue, du premier élément affiché. Toujours un
   *  multiple du nombre de places — c'est lui la position, pas un numéro de
   *  page, pour que redimensionner la fenêtre ne téléporte pas ailleurs. */
  const [anchor, setAnchor] = useState(0)

  const [previews, setPreviews] = useState<Record<string, string | null>>({})
  const [baked, setBaked] = useState<Record<string, string>>({})

  const gridRef = useRef<HTMLDivElement>(null)
  const [tiling, setTiling] = useState<Tiling>({ columns: 4, rows: TARGET_ROWS, tileHeight: 160 })
  const slots = Math.max(1, tiling.columns * tiling.rows)

  const total = lastRemotePage * REMOTE_PAGE_SIZE
  const pageCount = Math.max(1, Math.floor(total / slots))
  const pageNumber = Math.floor(anchor / slots) + 1

  // ── La grille se mesure ───────────────────────────────────────────────────
  useEffect(() => {
    const box = gridRef.current
    if (!box) return
    const measure = () => {
      const { width, height } = box.getBoundingClientRect()
      if (width > 0 && height > 0) setTiling(tilingFor(width, height))
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(box)
    return () => ro.disconnect()
  }, [])

  // Changer de taille change le nombre de places : la position se recale sur
  // une page pleine, au plus près de ce qu'on regardait.
  useEffect(() => {
    setAnchor((current) => {
      const maxAnchor = Math.max(0, (Math.floor(total / slots) - 1) * slots)
      return Math.min(Math.floor(current / slots) * slots, maxAnchor)
    })
  }, [slots, total])

  // ── Les lots nécessaires ──────────────────────────────────────────────────

  const firstRemote = Math.floor(anchor / REMOTE_PAGE_SIZE) + 1
  const lastRemote = Math.floor((anchor + slots - 1) / REMOTE_PAGE_SIZE) + 1

  const pagesRef = useRef(pages)
  pagesRef.current = pages
  const inflight = useRef<Set<number>>(new Set())

  const ensureRemote = useCallback(
    (page: number) => {
      if (page < 1 || pagesRef.current.has(page) || inflight.current.has(page)) return
      inflight.current.add(page)
      api.skin
        .catalog(page, tags, null, slimOnly)
        .then((received) => {
          setPages((known) => new Map(known).set(page, received.items))
          setLastRemotePage(received.last_page)
        })
        .catch((e) => {
          setFailed((known) => new Set(known).add(page))
          showError(e)
        })
        .finally(() => inflight.current.delete(page))
    },
    [tags, slimOnly],
  )

  useEffect(() => {
    for (let page = firstRemote; page <= lastRemote; page += 1) ensureRemote(page)
    // Le lot d'après, pendant qu'on regarde celui-ci : c'est ce qui rend
    // invisible le franchissement d'une frontière de lot.
    ensureRemote(lastRemote + 1)
  }, [firstRemote, lastRemote, ensureRemote])

  // ── Déchargement de ce qui s'éloigne ──────────────────────────────────────
  //
  // Les lots hors fenêtre partent, et avec eux leurs aperçus et leurs rendus
  // cuits — c'est la même règle pour les trois caches, pour qu'aucun ne puisse
  // grossir pendant que les autres se vident.
  useEffect(() => {
    const low = firstRemote - KEEP_RADIUS
    const high = lastRemote + KEEP_RADIUS
    setPages((known) => keepPages(known, low, high))
    setFailed((known) => {
      const kept = new Set(Array.from(known).filter((page) => page >= low && page <= high))
      return kept.size === known.size ? known : kept
    })
  }, [firstRemote, lastRemote])

  // Les aperçus et les rendus suivent les lots : une adresse qui n'appartient
  // plus à aucun lot gardé n'a plus de case où s'afficher.
  useEffect(() => {
    const alive = new Set<string>()
    pages.forEach((items) => items.forEach((skin) => alive.add(skin.url)))
    setPreviews((known) => keepUrls(known, alive))
    setBaked((known) => keepUrls(known, alive))
  }, [pages])

  /** `null` tant qu'un lot nécessaire manque — c'est l'état de chargement. */
  const visible = useMemo(() => {
    const gathered: CatalogSkin[] = []
    for (let page = firstRemote; page <= lastRemote; page += 1) {
      const items = pages.get(page)
      if (!items) return failed.has(page) ? [] : null
      gathered.push(...items)
    }
    const base = (firstRemote - 1) * REMOTE_PAGE_SIZE
    return gathered.slice(anchor - base, anchor - base + slots)
  }, [pages, failed, firstRemote, lastRemote, anchor, slots])

  // ── Les aperçus des seules cases affichées ────────────────────────────────
  //
  // Demander les 40 d'un lot ferait payer tout le lot pour en montrer une
  // douzaine, et c'est précisément ce qu'on a corrigé ailleurs pour les
  // connexions lentes. Le cache disque du Rust rend les retours gratuits.
  useEffect(() => {
    if (!visible) return
    const missing = visible.map((s) => s.url).filter((url) => !(url in previews))
    if (missing.length === 0) return
    let cancelled = false
    api.skin
      .catalogPreviews(missing)
      .then((uris) => {
        if (cancelled) return
        setPreviews((known) => {
          const next = { ...known }
          missing.forEach((url, i) => { next[url] = uris[i] ?? null })
          return next
        })
      })
      .catch(() => {
        if (cancelled) return
        setPreviews((known) => {
          const next = { ...known }
          missing.forEach((url) => { next[url] = null })
          return next
        })
      })
    return () => { cancelled = true }
  }, [visible, previews])

  // ── Cuisson du rendu 3D ───────────────────────────────────────────────────
  useEffect(() => {
    if (!visible) return
    let cancelled = false
    visible.forEach((skin) => {
      const uri = previews[skin.url]
      if (!uri || baked[skin.url]) return
      bakeSkin(skin.url, uri, skin.variant === 'slim')
        .then((image) => { if (!cancelled) setBaked((known) => ({ ...known, [skin.url]: image })) })
        .catch(() => {})
    })
    return () => { cancelled = true }
  }, [visible, previews, baked])

  // ── Navigation ────────────────────────────────────────────────────────────

  const canPrev = anchor > 0
  const canNext = anchor + slots < total

  const goNext = useCallback(() => {
    setAnchor((current) => (current + slots < total ? current + slots : current))
  }, [slots, total])

  const goPrev = useCallback(() => {
    setAnchor((current) => Math.max(0, current - slots))
  }, [slots])

  // Les flèches du clavier font la même chose que celles de l'écran.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement | null)?.tagName === 'INPUT') return
      if (e.key === 'ArrowRight') goNext()
      if (e.key === 'ArrowLeft') goPrev()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [goNext, goPrev])

  // ── Filtres ───────────────────────────────────────────────────────────────

  /** Changer de filtre change toute la liste : les lots en mémoire ne valent
   *  plus rien, et on repart du début. */
  const resetTo = (nextTags: string[], nextSlimOnly: boolean) => {
    setTags(nextTags)
    setSlimOnly(nextSlimOnly)
    setPages(new Map())
    setFailed(new Set())
    setLastRemotePage(1)
    setAnchor(0)
  }

  const addTag = () => {
    const tag = tagInput.trim()
    setTagInput('')
    if (!tag || tags.includes(tag)) return
    resetTo([...tags, tag], slimOnly)
  }

  /** Essayer un skin, c'est repartir à l'écran Skins avec lui en main : il y
   *  arrive comme candidat, et c'est là-bas qu'on confirme. */
  const trySkin = (skin: CatalogSkin) => {
    const query = new URLSearchParams()
    if (account) query.set('account', account)
    query.set('try', skin.url)
    query.set('variant', skin.variant)
    navigate(`/skins?${query.toString()}`)
  }

  const backTo = account ? `/skins?account=${encodeURIComponent(account)}` : '/skins'

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader backTo={backTo}>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('skinCatalog.title')}
          </h1>
          <p className="mt-0.5 text-[11.5px] text-txt-secondary">{t('skinCatalog.subtitle')}</p>
        </div>
      </PageHeader>

      <div className="min-h-0 flex-1 overflow-hidden px-7 py-5">
        <div className="mx-auto flex h-full min-h-[420px] w-full max-w-[1180px] flex-col gap-4">
          <Filters
            tags={tags}
            tagInput={tagInput}
            slimOnly={slimOnly}
            onTagInput={setTagInput}
            onAddTag={addTag}
            onRemoveTag={(tag) => resetTo(tags.filter((x) => x !== tag), slimOnly)}
            onToggleSlim={() => resetTo(tags, !slimOnly)}
          />

          <div ref={gridRef} className="relative min-h-0 flex-1">
            <AnimatePresence mode="wait">
              {!visible ? (
                <Layer key="loading">
                  <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
                </Layer>
              ) : visible.length === 0 ? (
                <Layer key="empty">
                  <div className="flex flex-col items-center gap-2 text-center">
                    <p className="text-[14px] font-semibold">{t('skinCatalog.empty')}</p>
                    <p className="max-w-sm text-[12.5px] leading-relaxed text-txt-secondary">
                      {t('skinCatalog.emptyHint')}
                    </p>
                  </div>
                </Layer>
              ) : (
                <motion.div
                  key={anchor}
                  variants={fadeVariants}
                  initial="hidden"
                  animate="visible"
                  exit="hidden"
                  transition={fastTransition}
                  className="absolute inset-0 grid content-start"
                  style={{
                    gridTemplateColumns: `repeat(${tiling.columns}, minmax(0, 1fr))`,
                    gap: `${GRID_GAP}px`,
                  }}
                >
                  {visible.map((skin) => (
                    <Tile
                      key={skin.id}
                      skin={skin}
                      height={tiling.tileHeight}
                      image={baked[skin.url] ?? null}
                      source={previews[skin.url] ?? null}
                      pending={!(skin.url in previews)}
                      onTry={() => trySkin(skin)}
                    />
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          <div className="flex items-center justify-center gap-4">
            <Arrow direction="prev" disabled={!canPrev} onClick={goPrev} label={t('skinCatalog.prev')} />
            <p className="min-w-[132px] text-center text-[12.5px] tabular-nums text-txt-secondary">
              {t('skinCatalog.page', { page: pageNumber, last: pageCount })}
            </p>
            <Arrow direction="next" disabled={!canNext} onClick={goNext} label={t('skinCatalog.next')} />
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Colonnes, rangées et hauteur de case qui remplissent la place disponible.
 *
 * On vise trois rangées et on en déduit la hauteur des cases. Si la fenêtre
 * est trop basse pour que trois rangées restent lisibles, on en retire une
 * plutôt que de rapetisser indéfiniment.
 */
function tilingFor(width: number, height: number): Tiling {
  let rows = TARGET_ROWS
  let tileHeight = (height - GRID_GAP * (rows - 1)) / rows
  while (rows > 1 && tileHeight < MIN_TILE_HEIGHT) {
    rows -= 1
    tileHeight = (height - GRID_GAP * (rows - 1)) / rows
  }
  const tileWidth = Math.max(MIN_TILE_HEIGHT * TILE_ASPECT, tileHeight * TILE_ASPECT)
  const columns = clamp(
    Math.floor((width + GRID_GAP) / (tileWidth + GRID_GAP)),
    MIN_COLUMNS,
    MAX_COLUMNS,
  )
  return { columns, rows, tileHeight: Math.max(MIN_TILE_HEIGHT, tileHeight) }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * Les deux filtres de déchargement rendent l'objet d'origine quand ils n'ont
 * rien à retirer. Sans ça, l'effet qui taille les aperçus d'après les lots
 * produirait un nouvel objet à chaque rendu, qui relancerait l'effet.
 */
function keepPages(
  pages: Map<number, CatalogSkin[]>,
  low: number,
  high: number,
): Map<number, CatalogSkin[]> {
  const kept = new Map(Array.from(pages).filter(([page]) => page >= low && page <= high))
  return kept.size === pages.size ? pages : kept
}

function keepUrls<T>(entries: Record<string, T>, alive: Set<string>): Record<string, T> {
  const keys = Object.keys(entries)
  const kept = keys.filter((url) => alive.has(url))
  if (kept.length === keys.length) return entries
  return Object.fromEntries(kept.map((url) => [url, entries[url]]))
}

/** Couche centrée qui occupe la zone de grille — chargement, vide. */
function Layer({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      variants={fadeVariants}
      initial="hidden"
      animate="visible"
      exit="hidden"
      transition={fastTransition}
      className="absolute inset-0 flex items-center justify-center"
    >
      {children}
    </motion.div>
  )
}

// ── Filtres ──────────────────────────────────────────────────────────────────

function Filters({
  tags,
  tagInput,
  slimOnly,
  onTagInput,
  onAddTag,
  onRemoveTag,
  onToggleSlim,
}: {
  tags: string[]
  tagInput: string
  slimOnly: boolean
  onTagInput: (value: string) => void
  onAddTag: () => void
  onRemoveTag: (tag: string) => void
  onToggleSlim: () => void
}) {
  const t = useT()
  return (
    <div className="flex flex-wrap items-center gap-2">
      <input
        value={tagInput}
        onChange={(e) => onTagInput(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') onAddTag() }}
        placeholder={t('skinCatalog.tagPlaceholder')}
        maxLength={32}
        className="h-10 w-[240px] rounded-xl border border-line bg-black/40 px-3.5 text-[13px] text-txt-primary placeholder:text-txt-muted outline-none transition-colors focus:border-accent/50"
      />
      <Button size="sm" variant="secondary" onClick={onAddTag} disabled={!tagInput.trim()}>
        {t('skinCatalog.addTag')}
      </Button>

      {tags.map((tag) => (
        <button
          key={tag}
          onClick={() => onRemoveTag(tag)}
          title={t('skinCatalog.removeTag')}
          className="flex h-8 items-center gap-1.5 rounded-lg border border-accent/40 bg-accent/15 px-3 text-[12.5px] text-txt-primary transition-colors hover:border-accent/70"
        >
          {tag}
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className="h-3 w-3">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      ))}

      <button
        onClick={onToggleSlim}
        className={`ml-auto flex h-8 items-center rounded-lg border px-3 text-[12.5px] transition-colors ${
          slimOnly
            ? 'border-accent/50 bg-accent/15 text-txt-primary'
            : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong'
        }`}
      >
        {t('skinCatalog.slimOnly')}
      </button>
    </div>
  )
}

// ── Une case ─────────────────────────────────────────────────────────────────

function Tile({
  skin,
  height,
  image,
  source,
  pending,
  onTry,
}: {
  skin: CatalogSkin
  height: number
  /** Rendu 3D cuit en image fixe. `null` tant qu'il n'est pas prêt. */
  image: string | null
  /** PNG du skin, pour la 3D vivante au survol. */
  source: string | null
  pending: boolean
  onTry: () => void
}) {
  const t = useT()
  const [hovered, setHovered] = useState(false)
  const [live, setLive] = useState(false)

  // La 3D vivante attend que le survol se confirme — voir HOVER_DELAY_MS.
  useEffect(() => {
    if (!hovered || !source) { setLive(false); return }
    const timer = window.setTimeout(() => setLive(true), HOVER_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [hovered, source])

  return (
    <div
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{ height }}
      className={`group relative flex flex-col overflow-hidden rounded-xl border transition-colors ${
        hovered ? 'border-accent/50 bg-surface-2' : 'border-line bg-surface-1'
      }`}
    >
      <div className="relative min-h-0 flex-1">
        {live && source ? (
          <LiveViewer source={source} slim={skin.variant === 'slim'} />
        ) : image ? (
          <img src={image} alt="" className="h-full w-full object-contain" draggable={false} />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            {pending ? (
              <ButtonSpinner size={18} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            ) : (
              <span className="text-[11px] text-txt-muted">{t('skinCatalog.noPreview')}</span>
            )}
          </div>
        )}
      </div>

      <div className="flex h-6 shrink-0 items-center justify-center gap-1 border-t border-line/60 px-2 text-[10.5px] tabular-nums text-txt-muted">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-3 w-3">
          <path d="M17 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
          <circle cx="9.5" cy="7" r="4" />
        </svg>
        {compact(skin.wearers)}
      </div>

      {/* Le bouton recouvre le bas de la case au survol : c'est l'action de
          cette case, elle n'a pas à occuper de la place en permanence. */}
      <AnimatePresence>
        {hovered && (
          <motion.div
            variants={fadeVariants}
            initial="hidden"
            animate="visible"
            exit="hidden"
            transition={fastTransition}
            className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/85 to-transparent p-1.5 pt-5"
          >
            <Button size="sm" onClick={onTry} fullWidth>{t('skinCatalog.try')}</Button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

/** Compteur abrégé : 29348 tient mal dans une case rétrécie. */
function compact(value: number): string {
  if (value >= 1000000) return `${(value / 1000000).toFixed(1)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(value >= 10000 ? 0 : 1)}k`
  return String(value)
}

/**
 * La 3D vivante de la case survolée.
 *
 * Elle n'existe que pendant le survol : monter ce composant crée un contexte
 * WebGL, le démonter le rend. Comme une seule case est survolée à la fois, il
 * n'y en a jamais qu'un — c'est tout l'intérêt des rendus cuits à côté.
 */
function LiveViewer({ source, slim }: { source: string; slim: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!canvasRef.current || !boxRef.current) return
    const { width, height } = boxRef.current.getBoundingClientRect()

    // Même raison qu'ailleurs : `skinview3d` crée son contexte sans `alpha`,
    // et l'effacement peindrait du noir. On impose nos attributs en créant le
    // contexte avant lui.
    canvasRef.current.getContext('webgl2', { alpha: true, premultipliedAlpha: true })
      ?? canvasRef.current.getContext('webgl', { alpha: true, premultipliedAlpha: true })

    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: width || 100,
      height: height || MIN_TILE_HEIGHT,
    })
    viewer.background = null
    viewer.autoRotate = true
    viewer.autoRotateSpeed = 1.4
    viewer.zoom = 0.88
    viewer.fov = 42
    viewer.animation = new WalkingAnimation()
    viewer.animation.speed = 0.5
    ;(viewer.loadSkin(source, { model: slim ? 'slim' : 'default' }) as Promise<void> | void)?.catch?.(() => {})

    return () => viewer.dispose()
  }, [source, slim])

  return (
    <div ref={boxRef} className="h-full w-full">
      <canvas ref={canvasRef} className="h-full w-full" />
    </div>
  )
}

// ── Flèches ──────────────────────────────────────────────────────────────────

function Arrow({
  direction,
  disabled,
  onClick,
  label,
}: {
  direction: 'prev' | 'next'
  disabled: boolean
  onClick: () => void
  label: string
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      aria-label={label}
      className="flex h-10 w-10 items-center justify-center rounded-xl border border-line bg-surface-2 text-txt-primary transition-colors hover:border-accent/40 hover:bg-surface-3 disabled:cursor-not-allowed disabled:opacity-35 disabled:hover:border-line disabled:hover:bg-surface-2"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
        {direction === 'prev' ? <path d="M15 18 9 12l6-6" /> : <path d="m9 18 6-6-6-6" />}
      </svg>
    </button>
  )
}
