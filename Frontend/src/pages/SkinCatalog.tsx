import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import type { CatalogSkin, SkinFormat, SkinKindFilter } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError } from '@/stores/useErrorToast'
import { BAKE_HEIGHT, BAKE_VIEW, BAKE_WIDTH, bakeSkin } from '@/lib/skinBake'
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

/**
 * Pages d'aperçus gardées de part et d'autre de la position.
 *
 * Parcourir longtemps retiendrait sinon tout ce qu'on a vu : un aperçu (le
 * PNG du skin) et surtout un rendu cuit (une image de 180×288) pèsent des
 * dizaines de kilo-octets chacun, en data URI dans l'état React. Deux pages
 * de chaque côté suffisent pour que reculer et avancer restent instantanés.
 *
 * Les entrées du catalogue elles-mêmes ne sont pas déchargées : quelques
 * centaines d'octets pièce, négligeable à côté.
 */
const KEEP_PAGES = 2

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
 * Modèle de bras, tel que l'interface le présente.
 *
 * Ely.by n'a pas de valeur « classique » : ses quatre formats sont exclusifs,
 * et c'est `new` (le 64×64 moderne) qui ne contient que des bras classiques.
 * La traduction se fait ici, une seule fois. Seule conséquence visible :
 * « Classique » ne montre pas les vieux skins 64×32, une poignée de
 * téléversements de 2013.
 */
const MODELS = [
  { id: 'any', format: null, label: 'skinCatalog.modelAny' },
  { id: 'classic', format: 'new', label: 'skinCatalog.modelClassic' },
  { id: 'slim', format: 'slim', label: 'skinCatalog.modelSlim' },
] as const satisfies readonly { id: string; format: SkinFormat | null; label: string }[]

type ModelId = (typeof MODELS)[number]['id']

/**
 * Catégories d'Ely.by — leurs propres entrées, pas des étiquettes choisies par
 * nous : elles rendent forcément des résultats, et couvrent le catalogue.
 *
 * La valeur part telle quelle (la casse compte côté Ely.by) ; seul le libellé
 * est traduit.
 */
const KINDS = [
  'Comics',
  'Adventure',
  'Heroes',
  'Evildoers',
  'Weekend',
  'Characters',
  'Historical',
  'Fantasy',
  'Scientific',
  'Other',
] as const satisfies readonly SkinKindFilter[]

/** Clé de traduction du libellé d'une catégorie : `Comics` → `kindComics`. */
const kindKey = (kind: SkinKindFilter) => `skinCatalog.kind${kind}`

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
  const [model, setModel] = useState<ModelId>('any')
  const [kind, setKind] = useState<SkinKindFilter | null>(null)

  const [showSensitive, setShowSensitive] = useState(false)

  /**
   * Tout ce qui a été chargé, à plat et dans l'ordre.
   *
   * Les lots d'Ely.by ne font plus forcément 40 : le filtre de YuyuFrame en
   * écarte une ou deux par lot. Indexer par « lot × 40 » décalerait donc tout
   * un peu plus à chaque lot. On accumule à plat, et les pages se découpent
   * là-dedans — ce qui garde aussi les pages pleines, puisqu'on charge le lot
   * suivant dès qu'il manque de quoi remplir.
   *
   * Ces entrées ne sont jamais déchargées, à la différence des aperçus et des
   * rendus : un `CatalogSkin` pèse quelques centaines d'octets, et on n'en
   * accumule que ce qu'on a réellement parcouru.
   */
  const [items, setItems] = useState<CatalogSkin[]>([])
  const [loadedLots, setLoadedLots] = useState(0)
  const [lastLot, setLastLot] = useState(1)
  const [loading, setLoading] = useState(true)

  /** Index, dans la liste à plat, du premier élément affiché. Toujours un
   *  multiple du nombre de places — c'est lui la position, pas un numéro de
   *  page, pour que redimensionner la fenêtre ne téléporte pas ailleurs. */
  const [anchor, setAnchor] = useState(0)

  const [previews, setPreviews] = useState<Record<string, string | null>>({})
  const [baked, setBaked] = useState<Record<string, string>>({})

  /**
   * La case survolée, tenue ici et non dans chaque case.
   *
   * Chaque case gardait son propre état, et il suffisait qu'un `mouseleave`
   * se perde — ce qui arrive en balayant vite la grille — pour qu'elle reste
   * bloquée en survol. Plusieurs aperçus 3D vivaient alors en même temps,
   * c'est-à-dire plusieurs contextes WebGL, exactement ce qu'on voulait
   * éviter. Avec une seule valeur ici, il ne peut y en avoir qu'un.
   */
  const [hovered, setHovered] = useState<number | null>(null)

  const gridRef = useRef<HTMLDivElement>(null)
  const [tiling, setTiling] = useState<Tiling>({ columns: 4, rows: TARGET_ROWS, tileHeight: 160 })
  const slots = Math.max(1, tiling.columns * tiling.rows)

  const pageNumber = Math.floor(anchor / slots) + 1
  /** Reste-t-il du catalogue à charger ? */
  const moreLots = loadedLots < lastLot

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
  // le début d'une page, au plus près de ce qu'on regardait.
  useEffect(() => {
    setAnchor((current) => Math.floor(current / slots) * slots)
  }, [slots])

  // ── Chargement, lot par lot et dans l'ordre ───────────────────────────────

  const loadedLotsRef = useRef(loadedLots)
  loadedLotsRef.current = loadedLots
  const lastLotRef = useRef(lastLot)
  lastLotRef.current = lastLot
  const busy = useRef(false)

  /** Change à chaque changement de filtre : une réponse partie avant est
   *  ignorée à son retour, au lieu d'être recollée à la mauvaise liste. */
  const generation = useRef(0)

  const loadNextLot = useCallback(() => {
    if (busy.current) return
    const lot = loadedLotsRef.current + 1
    if (lot > lastLotRef.current) return

    busy.current = true
    setLoading(true)
    const mine = generation.current
    const format = MODELS.find((m) => m.id === model)?.format ?? null

    api.skin
      .catalog(lot, tags, null, format, kind, showSensitive)
      .then((received) => {
        if (mine !== generation.current) return
        setItems((known) => [...known, ...received.items])
        setLoadedLots(lot)
        setLastLot(received.last_page)
      })
      .catch((e) => {
        if (mine !== generation.current) return
        // On s'arrête là plutôt que de retenter en boucle sur le même lot.
        setLastLot(lot - 1)
        showError(e)
      })
      .finally(() => {
        busy.current = false
        if (mine === generation.current) setLoading(false)
      })
  }, [tags, model, kind, showSensitive])

  // On garde une page d'avance : franchir une frontière de lot ne se voit pas,
  // et la page affichée est toujours pleine tant qu'il reste du catalogue.
  useEffect(() => {
    if (items.length < anchor + slots * 2 && loadedLots < lastLot) loadNextLot()
  }, [items.length, anchor, slots, loadedLots, lastLot, loading, loadNextLot])

  const visible = useMemo(() => items.slice(anchor, anchor + slots), [items, anchor, slots])

  // ── Déchargement de ce qui s'éloigne ──────────────────────────────────────
  //
  // Seuls les aperçus et les rendus cuits sont déchargés : ce sont eux qui
  // pèsent (un data URI chacun, des dizaines de kilo-octets pour un rendu).
  // Les entrées du catalogue restent, elles sont négligeables à côté.
  useEffect(() => {
    const from = Math.max(0, anchor - slots * KEEP_PAGES)
    const to = anchor + slots * (KEEP_PAGES + 1)
    const alive = new Set(items.slice(from, to).map((skin) => skin.url))
    setPreviews((known) => keepUrls(known, alive))
    setBaked((known) => keepUrls(known, alive))
  }, [items, anchor, slots])

  // ── Les aperçus des seules cases affichées ────────────────────────────────
  //
  // Demander les 40 d'un lot ferait payer tout le lot pour en montrer une
  // douzaine, et c'est précisément ce qu'on a corrigé ailleurs pour les
  // connexions lentes. Le cache disque du Rust rend les retours gratuits.
  useEffect(() => {
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
  // Encore du monde après la page affichée, ou encore des lots à charger.
  const canNext = anchor + slots < items.length || moreLots

  const goNext = useCallback(() => {
    setAnchor((current) => (current + slots < items.length ? current + slots : current))
  }, [slots, items.length])

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
  const resetFilters = (next: {
    tags?: string[]
    model?: ModelId
    kind?: SkinKindFilter | null
    showSensitive?: boolean
  }) => {
    if (next.tags !== undefined) setTags(next.tags)
    if (next.model !== undefined) setModel(next.model)
    if (next.kind !== undefined) setKind(next.kind)
    if (next.showSensitive !== undefined) setShowSensitive(next.showSensitive)
    // Les réponses déjà parties appartiennent à l'ancienne liste.
    generation.current += 1
    setItems([])
    setLoadedLots(0)
    setLastLot(1)
    setAnchor(0)
    setPreviews({})
    setBaked({})
    setLoading(true)
  }

  const addTag = () => {
    const tag = tagInput.trim()
    setTagInput('')
    if (!tag || tags.includes(tag)) return
    resetFilters({ tags: [...tags, tag] })
  }

  /**
   * Essayer un skin renvoie d'où l'on vient.
   *
   * Depuis l'écran Skins il y arrive **en candidat**, et c'est là-bas qu'on
   * confirme. Depuis l'éditeur (`?to=editor`) il devient le dessin de départ :
   * on est venu chercher une base, pas un skin à porter.
   */
  const fromEditor = params.get('to') === 'editor'

  const trySkin = (skin: CatalogSkin) => {
    const query = new URLSearchParams()
    if (account) query.set('account', account)
    if (fromEditor) {
      query.set('load', skin.url)
      query.set('variant', skin.variant)
      navigate(`/skins/editor?${query.toString()}`)
      return
    }
    query.set('try', skin.url)
    query.set('variant', skin.variant)
    navigate(`/skins?${query.toString()}`)
  }

  const home = fromEditor ? '/skins/editor' : '/skins'
  const backTo = account ? `${home}?account=${encodeURIComponent(account)}` : home

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
            model={model}
            kind={kind}
            onTagInput={setTagInput}
            onAddTag={addTag}
            onRemoveTag={(tag) => resetFilters({ tags: tags.filter((x) => x !== tag) })}
            onPickModel={(next) => resetFilters({ model: next })}
            onPickKind={(next) => resetFilters({ kind: kind === next ? null : next })}
            showSensitive={showSensitive}
            onToggleSensitive={() => resetFilters({ showSensitive: !showSensitive })}
          />

          {/* Filet de sécurité : quitter la grille éteint le survol, même si
              la case concernée n'a pas reçu son propre `mouseleave`. */}
          <div
            ref={gridRef}
            onMouseLeave={() => setHovered(null)}
            className="relative min-h-0 flex-1"
          >
            <AnimatePresence mode="wait">
              {visible.length === 0 && loading ? (
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
                      hovered={hovered === skin.id}
                      onHover={setHovered}
                      onTry={() => trySkin(skin)}
                    />
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          <div className="flex items-center justify-center gap-4">
            <Arrow direction="prev" disabled={!canPrev} onClick={goPrev} label={t('skinCatalog.prev')} />
            {/* Pas de total : le filtre de YuyuFrame écarte des entrées, donc
                le nombre de pages réel n'est connu qu'une fois le catalogue
                parcouru. Mieux vaut ne rien annoncer qu'annoncer à côté. */}
            <p className="min-w-[110px] text-center text-[12.5px] tabular-nums text-txt-secondary">
              {t('skinCatalog.page', { page: pageNumber })}
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
 * Rend l'objet d'origine quand il n'y a rien à retirer. Sans ça, l'effet de
 * déchargement produirait un nouvel objet à chaque rendu, qui le relancerait.
 */
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

/**
 * Deux rangées : la recherche et le modèle en haut, les catégories en bas.
 *
 * Les catégories sont celles d'Ely.by, pas des étiquettes choisies par nous —
 * elles rendent forcément des résultats, et donnent une porte d'entrée à qui
 * n'a pas de mot en tête. Le champ libre reste pour qui en a un.
 */
function Filters({
  tags,
  tagInput,
  model,
  kind,
  showSensitive,
  onTagInput,
  onAddTag,
  onRemoveTag,
  onPickModel,
  onPickKind,
  onToggleSensitive,
}: {
  tags: string[]
  tagInput: string
  model: ModelId
  kind: SkinKindFilter | null
  showSensitive: boolean
  onTagInput: (value: string) => void
  onAddTag: () => void
  onRemoveTag: (tag: string) => void
  onPickModel: (model: ModelId) => void
  onPickKind: (kind: SkinKindFilter) => void
  onToggleSensitive: () => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <input
          value={tagInput}
          onChange={(e) => onTagInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') onAddTag() }}
          placeholder={t('skinCatalog.tagPlaceholder')}
          maxLength={32}
          className="h-9 w-[220px] rounded-xl border border-line bg-black/40 px-3.5 text-[13px] text-txt-primary placeholder:text-txt-muted outline-none transition-colors focus:border-accent/50"
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

        {/* Les trois modèles collés en un seul bloc : c'est un choix unique,
            et trois boutons séparés le feraient passer pour trois bascules. */}
        <div className="ml-auto flex h-8 shrink-0 overflow-hidden rounded-lg border border-line">
          {MODELS.map((entry) => (
            <button
              key={entry.id}
              onClick={() => onPickModel(entry.id)}
              className={`h-full border-l border-line px-3 text-[12.5px] transition-colors first:border-l-0 ${
                model === entry.id
                  ? 'bg-accent/20 text-txt-primary'
                  : 'bg-surface-2 text-txt-secondary hover:bg-surface-3'
              }`}
            >
              {t(entry.label)}
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-1.5">
        <Chip active={kind === null} onClick={() => kind && onPickKind(kind)}>
          {t('skinCatalog.allKinds')}
        </Chip>
        {KINDS.map((entry) => (
          <Chip key={entry} active={kind === entry} onClick={() => onPickKind(entry)}>
            {t(kindKey(entry))}
          </Chip>
        ))}

        {/* Lève les deux filtres, celui d'Ely.by et le nôtre. Teinté en
            avertissement quand il est actif : c'est le seul réglage de cet
            écran qui fait apparaître quelque chose plutôt que de trier. */}
        <button
          onClick={onToggleSensitive}
          title={t('skinCatalog.sensitiveHint')}
          className={`ml-auto flex h-7 items-center gap-1.5 rounded-full border px-3 text-[12px] transition-colors ${
            showSensitive
              ? 'border-warning/50 bg-warning/15 text-txt-primary'
              : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
          }`}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
            {showSensitive ? (
              <>
                <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
                <circle cx="12" cy="12" r="3" />
              </>
            ) : (
              <>
                <path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.5 0 10 7 10 7a18 18 0 0 1-2.16 3.19M6.6 6.6A18 18 0 0 0 2 11s3.5 7 10 7a9 9 0 0 0 5.4-1.6" />
                <path d="m2 2 20 20" />
              </>
            )}
          </svg>
          {t('skinCatalog.sensitive')}
        </button>
      </div>
    </div>
  )
}

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      className={`h-7 rounded-full border px-3 text-[12px] transition-colors ${
        active
          ? 'border-accent/50 bg-accent/20 text-txt-primary'
          : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
      }`}
    >
      {children}
    </button>
  )
}

// ── Une case ─────────────────────────────────────────────────────────────────

function Tile({
  skin,
  height,
  image,
  source,
  pending,
  hovered,
  onHover,
  onTry,
}: {
  skin: CatalogSkin
  height: number
  /** Rendu 3D cuit en image fixe. `null` tant qu'il n'est pas prêt. */
  image: string | null
  /** PNG du skin, pour la 3D vivante au survol. */
  source: string | null
  pending: boolean
  hovered: boolean
  onHover: (id: number | null) => void
  onTry: () => void
}) {
  const t = useT()
  const [live, setLive] = useState(false)
  /** La 3D a sa texture et peut remplacer l'image. */
  const [liveReady, setLiveReady] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  /**
   * Rectangle qu'occupe réellement l'image cuite dans la case.
   *
   * L'image est en `object-contain` : son format (180×288) n'étant pas celui
   * de la case, elle est centrée avec des marges. Donner au canevas toute la
   * case le faisait donc rendre un personnage plus grand et décalé — d'où le
   * saut au survol. On calcule ici le même rectangle pour les deux.
   */
  const [fit, setFit] = useState<{ width: number; height: number } | null>(null)

  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const measure = () => {
      const rect = box.getBoundingClientRect()
      if (rect.width <= 0 || rect.height <= 0) return
      const scale = Math.min(rect.width / BAKE_WIDTH, rect.height / BAKE_HEIGHT)
      setFit({ width: BAKE_WIDTH * scale, height: BAKE_HEIGHT * scale })
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(box)
    return () => ro.disconnect()
  }, [])

  // La 3D vivante attend que le survol se confirme — voir HOVER_DELAY_MS.
  useEffect(() => {
    if (!hovered || !source) {
      setLive(false)
      setLiveReady(false)
      return
    }
    const timer = window.setTimeout(() => setLive(true), HOVER_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [hovered, source])

  return (
    <div
      onMouseEnter={() => onHover(skin.id)}
      onMouseLeave={() => onHover(null)}
      style={{ height }}
      className={`group relative flex flex-col overflow-hidden rounded-xl border transition-colors ${
        hovered ? 'border-accent/50 bg-surface-2' : 'border-line bg-surface-1'
      }`}
    >
      <div ref={boxRef} className="relative min-h-0 flex-1">
        {image ? (
          <img
            src={image}
            alt=""
            draggable={false}
            className={`h-full w-full object-contain transition-opacity duration-150 ${
              liveReady ? 'opacity-0' : 'opacity-100'
            }`}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            {pending ? (
              <ButtonSpinner size={18} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            ) : (
              <span className="text-[11px] text-txt-muted">{t('skinCatalog.noPreview')}</span>
            )}
          </div>
        )}

        {/* Fondu croisé entre les deux : l'image ne disparaît qu'une fois la
            3D prête, et la 3D n'apparaît pas avant. Sans le premier, le
            canevas resterait vide le temps du décodage ; sans le second,
            l'image fixe se verrait par transparence derrière la 3D, qui a un
            fond transparent et tourne. Même position et même pose, donc le
            passage de l'une à l'autre ne se voit pas. */}
        {live && source && fit && (
          <div
            className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2"
            style={{ width: fit.width, height: fit.height }}
          >
            <LiveViewer
              source={source}
              slim={skin.variant === 'slim'}
              onReady={() => setLiveReady(true)}
            />
          </div>
        )}
      </div>

      {/* `border-line-soft` et jamais `border-line/60` : ces jetons portent
          déjà leur alpha, un suffixe d'opacité donne une couleur invalide et
          le navigateur retombe sur un trait blanc. */}
      <div className="flex h-6 shrink-0 items-center justify-center gap-1 border-t border-line-soft px-2 text-[10.5px] tabular-nums text-txt-muted">
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
function LiveViewer({
  source,
  slim,
  onReady,
}: {
  source: string
  slim: boolean
  /** Prévient la case que la 3D peut prendre la place de l'image. */
  onReady: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    if (!canvasRef.current || !boxRef.current) return
    const { width, height } = boxRef.current.getBoundingClientRect()
    let disposed = false

    // Même raison qu'ailleurs : `skinview3d` crée son contexte sans `alpha`,
    // et l'effacement peindrait du noir. On impose nos attributs en créant le
    // contexte avant lui.
    canvasRef.current.getContext('webgl2', { alpha: true, premultipliedAlpha: true })
      ?? canvasRef.current.getContext('webgl', { alpha: true, premultipliedAlpha: true })

    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: width || BAKE_WIDTH,
      height: height || BAKE_HEIGHT,
    })
    viewer.background = null
    // Exactement la pose du rendu cuit, pour que la 3D prenne la place de
    // l'image sans que le personnage bouge. La rotation et l'animation
    // partent de là.
    viewer.zoom = BAKE_VIEW.zoom
    viewer.fov = BAKE_VIEW.fov
    viewer.playerWrapper.rotation.y = BAKE_VIEW.rotationY
    viewer.autoRotate = true
    viewer.autoRotateSpeed = 1.4
    viewer.animation = new WalkingAnimation()
    viewer.animation.speed = 0.5
    ;(viewer.loadSkin(source, { model: slim ? 'slim' : 'default' }) as Promise<void> | void)
      ?.then?.(() => {
        if (disposed) return
        setReady(true)
        onReady()
      })
      ?.catch?.(() => {})

    return () => {
      disposed = true
      viewer.dispose()
    }
    // `onReady` est volontairement hors des dépendances : une nouvelle
    // référence à chaque rendu de la case relancerait le viewer, donc
    // recréerait un contexte WebGL, à chaque image de l'animation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, slim])

  return (
    <div
      ref={boxRef}
      className={`h-full w-full transition-opacity duration-150 ${ready ? 'opacity-100' : 'opacity-0'}`}
    >
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
