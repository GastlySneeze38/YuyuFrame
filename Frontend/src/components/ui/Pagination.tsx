import { useLayoutEffect, useRef, useState } from 'react'
import { useT } from '@/i18n'

/** Les numéros à afficher autour de la page courante : la première, la
 *  dernière, la courante et ses deux voisines, avec `null` là où des pages
 *  sont sautées. Au-delà de sept pages on ne peut plus toutes les montrer, et
 *  une rangée qui change de largeur à chaque clic ferait bouger les flèches
 *  sous le pointeur. */
export function pageWindow(page: number, pageCount: number): (number | null)[] {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, i) => i + 1)
  const pages = new Set([1, pageCount, page - 1, page, page + 1])
  // Près d'un bord, on garde le même nombre de cases qu'au milieu.
  if (page <= 3) [2, 3, 4].forEach((p) => pages.add(p))
  if (page >= pageCount - 2) [pageCount - 3, pageCount - 2, pageCount - 1].forEach((p) => pages.add(p))
  const sorted = [...pages].filter((p) => p >= 1 && p <= pageCount).sort((a, b) => a - b)
  const out: (number | null)[] = []
  for (const p of sorted) {
    const prev = out[out.length - 1]
    if (typeof prev === 'number' && p - prev > 1) out.push(null)
    out.push(p)
  }
  return out
}

/** La forme réduite, quand la rangée complète ne tient pas (téléphone,
 *  colonne étroite) : la première, la courante, la dernière — trois numéros
 *  au plus, `‹ 1 … 5 … 12 ›`. Près d'un bord, la voisine remplace le saut
 *  pour garder trois numéros. */
export function compactPageWindow(page: number, pageCount: number): (number | null)[] {
  if (pageCount <= 4) return Array.from({ length: pageCount }, (_, i) => i + 1)
  const pages = new Set([1, page, pageCount])
  if (page === 1) pages.add(2)
  if (page === pageCount) pages.add(pageCount - 1)
  const sorted = [...pages].sort((a, b) => a - b)
  const out: (number | null)[] = []
  for (const p of sorted) {
    const prev = out[out.length - 1]
    if (typeof prev === 'number' && p - prev > 1) out.push(null)
    out.push(p)
  }
  return out
}

/** Nombre de pages pour `total` résultats, jamais moins d'une. */
export function pageCountFor(total: number, pageSize: number): number {
  return Math.max(1, Math.ceil(total / pageSize))
}

/**
 * Pagination des recherches (mods, modpacks, packs de ressources, shaders).
 *
 * Rien n'est rendu quand il n'y a qu'une page : une rangée de boutons inertes
 * sous trois résultats ne dirait rien.
 */
export function Pagination({ page, pageCount, onChange, disabled }: {
  page: number
  pageCount: number
  onChange: (page: number) => void
  /** Une recherche est en route : les boutons attendent sa réponse. */
  disabled?: boolean
}) {
  const t = useT()
  // La rangée ne passe jamais à la ligne (la flèche « suivante » se
  // retrouvait seule en dessous) : on mesure la place, et si la forme
  // complète ne tient pas, on prend la forme réduite.
  const navRef = useRef<HTMLElement>(null)
  const [width, setWidth] = useState<number | null>(null)
  useLayoutEffect(() => {
    const el = navRef.current
    if (!el) return
    setWidth(el.clientWidth)
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    return () => ro.disconnect()
  }, [pageCount > 1])
  if (pageCount <= 1) return null

  const full = pageWindow(page, pageCount)
  // Largeur de la forme complète : chaque case (32 px, 44 au doigt, un peu
  // plus pour les numéros à trois chiffres), chaque « … » (~16 px), les
  // écarts de 6 px.
  const mobileCells = typeof window !== 'undefined' && window.matchMedia('(max-width: 640px)').matches
  const cellPx = (mobileCells ? 44 : 32) + (pageCount >= 100 ? 8 : 0)
  const needed = (n: (number | null)[]) => n.reduce<number>((w, p) => w + (p === null ? 16 : cellPx), 2 * cellPx) + (n.length + 1) * 6
  const shown = width !== null && needed(full) > width ? compactPageWindow(page, pageCount) : full

  // Sur téléphone, des cases de 44 px : 32 px se ratent au doigt.
  const cell = 'flex h-8 min-w-8 shrink-0 items-center justify-center rounded-lg border px-2 text-[12px] font-semibold transition-colors duration-150 max-sm:h-11 max-sm:min-w-11 max-sm:rounded-xl max-sm:text-[15px]'
  const idle = 'border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] text-[rgba(255,255,255,0.55)] hover:border-[rgb(var(--accent)/0.5)] hover:text-white disabled:cursor-not-allowed disabled:opacity-30 disabled:hover:border-[rgba(255,255,255,0.08)]'
  const current = 'border-[rgb(var(--accent)/0.7)] bg-[rgb(var(--accent)/0.35)] text-white'

  const arrow = (target: number, label: string, path: string) => (
    <button
      type="button"
      onClick={() => onChange(target)}
      disabled={disabled || target < 1 || target > pageCount}
      title={label}
      aria-label={label}
      className={`${cell} ${idle}`}
    >
      <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d={path} /></svg>
    </button>
  )

  return (
    <nav ref={navRef} className="flex w-full flex-nowrap items-center justify-center gap-1.5 pt-1">
      {/* Les libellés existent déjà pour le catalogue de skins : mêmes mots,
          même geste. */}
      {arrow(page - 1, t('skinCatalog.prev'), 'M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z')}
      {shown.map((p, i) => p === null ? (
        <span key={`gap-${i}`} className="w-4 shrink-0 text-center text-[12px] text-[rgba(255,255,255,0.25)]">…</span>
      ) : (
        <button
          key={p}
          type="button"
          onClick={() => onChange(p)}
          disabled={disabled || p === page}
          aria-current={p === page ? 'page' : undefined}
          className={`${cell} ${p === page ? current : idle}`}
        >
          {p}
        </button>
      ))}
      {arrow(page + 1, t('skinCatalog.next'), 'M8.59 16.59L10 18l6-6-6-6-1.41 1.41L13.17 12z')}
    </nav>
  )
}
