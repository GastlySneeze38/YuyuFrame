import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import type { CatalogQuery, CatalogServer } from '@/api/client'
import { Pagination, pageCountFor } from '@/components/ui/Pagination'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { errorMessage } from '@/lib/apiError'
import { useT } from '@/i18n'
import { CatalogDetail } from './CatalogDetail'
import { LANGUAGES, PANEL, REGIONS, Select, categoryLabel, compact, languageName, regionLabel } from './bits'

/**
 * Le catalogue de serveurs de Modrinth : recherche, filtres, pages de 20.
 * Une carte ouvre la fiche du serveur par-dessus la grille, d'où l'on joue,
 * installe son modpack ou l'ajoute à une instance.
 */

type Kind = 'all' | 'vanilla' | 'modpack'

const EMPTY: CatalogQuery = {
  query: '',
  categories: [],
  region: null,
  language: null,
  gameVersion: null,
  kind: null,
  sort: 'relevance',
  page: 1,
  // 0 tant que la grille n'est pas mesurée : rien n'est demandé avant.
  pageSize: 0,
}

/** Gabarit d'une carte : la grille en déduit combien en tiennent à l'écran.
 *  Une carte vise `CARD_H` mais peut descendre jusqu'à `CARD_MIN_H` pour
 *  qu'au moins deux rangées tiennent ; une seule rangée seulement si même
 *  ça ne passe pas. Les cartes s'étirent ensuite pour remplir la hauteur. */
const CARD_MIN_W = 240
const CARD_H = 228
const CARD_MIN_H = 176
const GAP = 12

/** Rangées et hauteur de carte pour une zone de `height` pixels. */
function rowsFor(height: number): { rows: number; cardH: number } {
  let rows = Math.floor((height + GAP) / (CARD_H + GAP))
  if (rows < 2) rows = Math.floor((height + GAP) / (CARD_MIN_H + GAP))
  rows = Math.max(1, rows)
  return { rows, cardH: Math.floor((height - GAP * (rows - 1)) / rows) }
}

let categoriesCache: { name: string; header: string }[] | null = null

/** Les catégories montrées d'emblée : les genres les plus cherchés. */
const MAIN_CATEGORIES = ['pvp', 'smp', 'minigames', 'survival-mode', 'skyblock', 'factions', 'rpg', 'pokemon']

/** Les familles de Modrinth, dans l'ordre du panneau « Plus ». */
const GROUPS = ['minecraft_server_gameplay', 'minecraft_server_features', 'minecraft_server_community', 'minecraft_server_meta']

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={`whitespace-nowrap rounded-full border px-2.5 py-1 text-[11px] font-semibold transition-colors ${
        on ? 'border-accent bg-accent/30 text-white' : 'border-white/[0.1] text-txt-secondary hover:border-white/25 hover:text-white'
      }`}
    >
      {children}
    </button>
  )
}

export function CatalogView({ onAdded }: { onAdded: () => void }) {
  const t = useT()
  const [query, setQuery] = useState<CatalogQuery>(EMPTY)
  const [text, setText] = useState('')
  const [result, setResult] = useState<{ servers: CatalogServer[]; total: number } | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [categories, setCategories] = useState(categoriesCache ?? [])
  const [open, setOpen] = useState<CatalogServer | null>(null)
  const [moreOpen, setMoreOpen] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)
  // « Plus » se referme au clic ailleurs que dans la barre de filtres.
  useEffect(() => {
    if (!moreOpen) return
    const onDown = (e: MouseEvent) => {
      if (!filtersRef.current?.contains(e.target as Node)) setMoreOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [moreOpen])
  const generation = useRef(0)
  const gridRef = useRef<HTMLDivElement>(null)

  // Le texte part après une courte pause de frappe, et ramène à la page 1.
  useEffect(() => {
    const id = setTimeout(() => setQuery((q) => (q.query === text ? q : { ...q, query: text, page: 1 })), 350)
    return () => clearTimeout(id)
  }, [text])

  useEffect(() => {
    if (categoriesCache) return
    api.servers
      .categories()
      .then((c) => {
        categoriesCache = c
        setCategories(c)
      })
      .catch(() => {})
  }, [])

  // La grille ne défile pas : elle montre exactement ce qui tient (colonnes ×
  // rangées), et c'est ce nombre qu'on demande à Modrinth. Quand la fenêtre
  // change de taille, la page est recalculée pour garder sous les yeux le
  // premier serveur affiché.
  const [cols, setCols] = useState(1)
  const [cardH, setCardH] = useState(CARD_H)
  useEffect(() => {
    const box = gridRef.current
    if (!box) return
    const measure = () => {
      // Onglet caché (`display: none`) : rien à mesurer, on attendra d'être visible.
      if (box.clientWidth === 0 || box.clientHeight === 0) return
      const c = Math.max(1, Math.floor((box.clientWidth + GAP) / (CARD_MIN_W + GAP)))
      const { rows: r, cardH: h } = rowsFor(box.clientHeight)
      const size = Math.min(50, c * r)
      setCols(c)
      setCardH(h)
      setQuery((q) =>
        q.pageSize === size ? q : { ...q, pageSize: size, page: q.pageSize ? Math.floor(((q.page - 1) * q.pageSize) / size) + 1 : q.page },
      )
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(box)
    return () => observer.disconnect()
  }, [])

  const run = () => {
    if (query.pageSize === 0) return
    const mine = ++generation.current
    setLoading(true)
    setError(null)
    api.servers
      .search(query)
      .then((r) => {
        if (mine !== generation.current) return
        setResult({ servers: r.servers, total: r.total })
      })
      .catch((e) => mine === generation.current && setError(errorMessage(e)))
      .finally(() => mine === generation.current && setLoading(false))
  }
  useEffect(run, [query])

  const set = (patch: Partial<CatalogQuery>) => setQuery((q) => ({ ...q, ...patch, page: patch.page ?? 1 }))
  const toggleCategory = (name: string) =>
    set({ categories: query.categories.includes(name) ? query.categories.filter((c) => c !== name) : [...query.categories, name] })
  const filtered = query.categories.length > 0 || query.region || query.language || query.kind
  const kind: Kind = query.kind ?? 'all'

  // Une rangée courte des catégories les plus cherchées (plus celles déjà
  // cochées) ; toutes les autres sont dans « Plus », rangées par famille.
  const known = new Set(categories.map((c) => c.name))
  const chips = [...MAIN_CATEGORIES.filter((c) => known.size === 0 || known.has(c)), ...query.categories.filter((c) => !MAIN_CATEGORIES.includes(c))]
  const groups = GROUPS.map((header) => ({
    header,
    names: categories.filter((c) => c.header === header).map((c) => c.name).sort((a, b) => categoryLabel(a).localeCompare(categoryLabel(b))),
  })).filter((g) => g.names.length > 0)
  const extraCount = query.categories.filter((c) => !MAIN_CATEGORIES.includes(c)).length

  return (
    <div className="relative flex min-h-0 w-full min-w-0 flex-1 flex-col gap-3">
      {/* Recherche et filtres */}
      <div ref={filtersRef} className={`relative z-10 flex flex-col gap-2.5 rounded-2xl p-3 ${PANEL}`}>
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-[220px] flex-1">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} width={14} height={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-txt-muted">
              <circle cx="11" cy="11" r="7" />
              <path d="M20 20l-3.5-3.5" />
            </svg>
            <input
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={t('servers.page.catalog.search')}
              className="h-9 w-full rounded-xl border border-line bg-surface-2 pl-9 pr-3 text-[12.5px] text-white outline-none placeholder:text-txt-muted focus:border-accent/60"
            />
          </div>
          <div className="flex rounded-xl bg-white/[0.04] p-1">
            {(['all', 'vanilla', 'modpack'] as Kind[]).map((k) => (
              <button
                key={k}
                onClick={() => set({ kind: k === 'all' ? null : k })}
                className={`relative rounded-lg px-3 py-1 text-[11.5px] font-bold transition-colors ${kind === k ? 'text-white' : 'text-txt-muted hover:text-txt-secondary'}`}
              >
                {kind === k && <motion.span layoutId="catalog-kind" className="absolute inset-0 rounded-lg bg-accent/70" transition={{ type: 'spring', stiffness: 600, damping: 40 }} />}
                <span className="relative">{t(`servers.page.catalog.kind.${k}`)}</span>
              </button>
            ))}
          </div>
          <Select
            value={query.region ?? ''}
            onChange={(v) => set({ region: v || null })}
            options={[{ value: '', label: t('servers.page.catalog.anyRegion') }, ...REGIONS.map((r) => ({ value: r, label: regionLabel(r) }))]}
          />
          <Select
            value={query.language ?? ''}
            onChange={(v) => set({ language: v || null })}
            options={[{ value: '', label: t('servers.page.catalog.anyLanguage') }, ...LANGUAGES.map((l) => ({ value: l, label: languageName(l) }))]}
          />
          <Select
            value={query.sort}
            onChange={(v) => set({ sort: v })}
            options={(['relevance', 'follows', 'newest', 'updated'] as const).map((s) => ({ value: s, label: t(`servers.page.catalog.sort.${s}`) }))}
          />
        </div>
        <div className="flex items-center gap-2">
          <div className="flex min-w-0 flex-1 flex-wrap gap-1.5">
            {chips.map((name) => (
              <Chip key={name} on={query.categories.includes(name)} onClick={() => toggleCategory(name)}>
                {categoryLabel(name)}
              </Chip>
            ))}
            {groups.length > 0 && (
              <button
                onClick={() => setMoreOpen((o) => !o)}
                className={`flex items-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-1 text-[11px] font-semibold transition-colors ${
                  moreOpen ? 'border-white/40 bg-white/[0.08] text-white' : 'border-white/[0.1] text-txt-secondary hover:border-white/25 hover:text-white'
                }`}
              >
                {t('servers.page.filters.more')}
                {extraCount > 0 && <span className="rounded-full bg-accent px-1.5 text-[10px] text-white">{extraCount}</span>}
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} width={10} height={10} className={`transition-transform ${moreOpen ? 'rotate-180' : ''}`}>
                  <path d="M6 9l6 6 6-6" />
                </svg>
              </button>
            )}
          </div>
          {filtered && (
            <button onClick={() => setQuery({ ...EMPTY, query: query.query, sort: query.sort, pageSize: query.pageSize })} className="flex-shrink-0 text-[11px] font-semibold text-accent-text hover:text-white">
              {t('servers.page.catalog.clear')}
            </button>
          )}
        </div>

        {/* Toutes les catégories, par famille, sous la barre */}
        <AnimatePresence>
          {moreOpen && (
            <motion.div
              initial={{ opacity: 0, y: -6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.15 }}
              className="absolute left-0 right-0 top-full z-20 mt-2 grid max-h-[50vh] grid-cols-2 gap-4 overflow-y-auto rounded-2xl border border-white/[0.1] bg-[#16161c] p-4 shadow-2xl lg:grid-cols-4"
            >
              {groups.map((g) => (
                <div key={g.header} className="flex flex-col gap-1.5">
                  <p className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t(`servers.page.filters.groups.${g.header}`)}</p>
                  <div className="flex flex-wrap gap-1.5">
                    {g.names.map((name) => (
                      <Chip key={name} on={query.categories.includes(name)} onClick={() => toggleCategory(name)}>
                        {categoryLabel(name)}
                      </Chip>
                    ))}
                  </div>
                </div>
              ))}
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Résultats */}
      <div ref={gridRef} className="relative min-h-0 flex-1 overflow-hidden">
        {error ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
            <p className="text-[14px] font-bold text-white">{t('servers.page.catalog.error')}</p>
            <p className="max-w-md text-[11.5px] text-txt-muted">{error}</p>
            <button onClick={run} className="mt-1 rounded-xl bg-accent px-4 py-2 text-[12px] font-bold text-white hover:bg-accent-hover">
              {t('servers.page.catalog.retry')}
            </button>
          </div>
        ) : !result ? (
          <div className="flex h-full items-center justify-center">
            <ButtonSpinner size={30} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
          </div>
        ) : result.servers.length === 0 ? (
          <div className="flex h-full items-center justify-center text-[13px] text-txt-muted">{t('servers.page.catalog.empty')}</div>
        ) : (
          <div
            className={`grid content-start transition-opacity ${loading ? 'opacity-50' : ''}`}
            style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))`, gridAutoRows: cardH, gap: GAP }}
          >
            {result.servers.map((s, i) => (
              <CatalogCard key={s.id} server={s} index={i} bannerH={Math.min(150, Math.max(52, cardH - 112))} onOpen={() => setOpen(s)} />
            ))}
          </div>
        )}
      </div>

      {result && (
        <div className={`flex items-center justify-between gap-3 rounded-xl px-3 py-1.5 ${PANEL}`}>
          <span className="text-[11px] text-txt-secondary">
            {t('servers.page.catalog.results', { count: result.total })} · {t('servers.page.catalog.source')}
          </span>
          <Pagination
            page={query.page}
            pageCount={pageCountFor(result.total, query.pageSize || 20)}
            onChange={(page) => set({ page })}
            disabled={loading}
          />
        </div>
      )}

      <AnimatePresence>{open && <CatalogDetail key={open.id} server={open} onClose={() => setOpen(null)} onAdded={onAdded} />}</AnimatePresence>
    </div>
  )
}

/** `bannerH` : l'image d'en-tête absorbe la hauteur variable de la carte ;
 *  le texte en dessous garde toujours la sienne. */
function CatalogCard({ server, index, bannerH, onOpen }: { server: CatalogServer; index: number; bannerH: number; onOpen: () => void }) {
  const t = useT()
  const accent = server.color ?? '#4b3fcf'
  return (
    <motion.button
      onClick={onOpen}
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2, delay: Math.min(index, 12) * 0.02 }}
      // Hauteur fixe (CARD_H, posée par la grille) : c'est elle qui permet de
      // savoir combien de rangées tiennent. Pas de soulèvement au survol, la
      // grille ne défilant pas, il serait rogné en haut.
      className="group flex h-full flex-col overflow-hidden rounded-2xl border border-white/[0.07] bg-[#121217] text-left transition-colors hover:border-white/30 hover:bg-[#16161c]"
    >
      {/* En-tête : l'image de la galerie, sinon un dégradé de sa couleur */}
      <div className="relative w-full flex-shrink-0 overflow-hidden" style={{ height: bannerH, background: `linear-gradient(135deg, ${accent}55, ${accent}11)` }}>
        {server.banner_url && (
          <img src={server.banner_url} alt="" loading="lazy" draggable={false} className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.04]" />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-[#121217] via-[#121217]/20 to-transparent" />
        {server.ping && (
          <span className="absolute right-2 top-2 flex items-center gap-1.5 rounded-full bg-black/55 px-2 py-0.5 text-[10.5px] font-bold text-white backdrop-blur">
            <span className="h-1.5 w-1.5 rounded-full bg-[#4ade80] shadow-[0_0_6px_#4ade80]" />
            {compact(server.ping.players_online)}
          </span>
        )}
      </div>
      {/* `relative` : posé au-dessus de l'en-tête, qui l'est aussi, sinon
          l'image et son dégradé passent par-dessus le nom. */}
      <div className="relative -mt-7 flex flex-1 flex-col gap-2 px-3 pb-3">
        <div className="flex items-end gap-2.5">
          <div className="relative h-12 w-12 flex-shrink-0 overflow-hidden rounded-xl border-2 border-[#121217] bg-surface-3">
            {server.icon_url && <img src={server.icon_url} alt="" loading="lazy" draggable={false} className="h-full w-full object-cover" />}
          </div>
          <div className="flex min-w-0 flex-1 flex-col pb-0.5">
            <span className="truncate text-[13.5px] font-black text-white">{server.name}</span>
            <span className="truncate text-[10.5px] text-txt-muted">{server.address}</span>
          </div>
        </div>
        <p className="line-clamp-2 min-h-[30px] text-[11.5px] leading-snug text-txt-secondary">{server.summary}</p>
        {/* Une seule ligne d'étiquettes : la carte garde sa hauteur fixe. */}
        <div className="mt-auto flex flex-nowrap items-center gap-1 overflow-hidden [&>span]:flex-shrink-0 [&>span]:whitespace-nowrap">
          {server.content.kind === 'modpack' && (
            <span className="rounded-md bg-[#f59e0b]/20 px-1.5 py-0.5 text-[10px] font-bold text-[#fbbf24]">{t('servers.page.catalog.modded')}</span>
          )}
          {server.recommended_version && (
            <span className="rounded-md bg-white/[0.06] px-1.5 py-0.5 text-[10px] font-semibold text-txt-secondary">{server.recommended_version}</span>
          )}
          {server.region && (
            <span className="rounded-md bg-white/[0.06] px-1.5 py-0.5 text-[10px] font-semibold text-txt-secondary">{regionLabel(server.region)}</span>
          )}
          {server.categories.slice(0, 3).map((c) => (
            <span key={c} className="rounded-md bg-white/[0.04] px-1.5 py-0.5 text-[10px] text-txt-muted">
              {categoryLabel(c)}
            </span>
          ))}
        </div>
      </div>
    </motion.button>
  )
}
