import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { EASE_OUT } from '@/lib/motion'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { useT } from '@/i18n'
import { relatedOptions, searchOptions, type SearchCandidate, type SearchResult } from './optionSearch'

/**
 * Champ de recherche des réglages, avec complétion et suggestions.
 *
 * Les résultats sûrs et les approximations sont séparés : mélangés, on ne
 * saurait plus si l'interface a trouvé ce qu'on demandait ou si elle propose
 * autre chose. Les approximations viennent donc sous leur propre titre.
 *
 * Le clavier suffit : flèches pour parcourir, Entrée pour choisir, Échap pour
 * abandonner. C'est un champ de recherche — y obliger la souris serait le
 * rendre plus lent que de faire défiler la liste.
 */

export function OptionSearchBar({ candidates, onPick }: {
  candidates: SearchCandidate[]
  /** Appelé avec la clé choisie — à charge de l'écran de l'amener à l'œil. */
  onPick: (key: string) => void
}) {
  const t = useT()
  const [query, setQuery] = useState('')
  const [cursor, setCursor] = useState(0)
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  const results = useMemo(() => searchOptions(query, candidates), [query, candidates])
  const sure = results.filter((r) => r.kind !== 'near')
  const maybe = results.filter((r) => r.kind === 'near')
  // Un seul index pour les deux listes : elles ne sont séparées qu'à
  // l'affichage, le clavier les parcourt d'affilée.
  const ordered = [...sure, ...maybe]

  useEffect(() => { setCursor(0) }, [query])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  const choose = (result: SearchResult | SearchCandidate) => {
    onPick(result.key)
    setOpen(false)
    // La requête est gardée : on revient souvent chercher le réglage voisin
    // juste après, et la retaper serait une corvée.
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { setOpen(false); return }
    if (ordered.length === 0) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setCursor((c) => (c + 1) % ordered.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setCursor((c) => (c - 1 + ordered.length) % ordered.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const picked = ordered[cursor]
      if (picked) choose(picked)
    }
  }

  // Réglages de la même famille que le résultat visé — sur `soundCategory_`,
  // avoir trouvé le volume général c'est très souvent vouloir la musique
  // juste après.
  const related = ordered[cursor] ? relatedOptions(ordered[cursor].key, candidates) : []

  const renderRow = (result: SearchResult, index: number) => (
    <button
      key={result.key}
      onMouseEnter={() => setCursor(index)}
      onClick={() => choose(result)}
      className={`flex w-full items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left transition-colors duration-100 ${
        cursor === index ? 'bg-[rgba(75,63,207,0.3)]' : 'hover:bg-[rgba(255,255,255,0.05)]'
      }`}
    >
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-[12px] font-medium text-[rgba(255,255,255,0.85)]">
          {result.label ?? result.key}
        </span>
        {result.label && (
          <span className="truncate font-mono text-[10px] text-[rgba(255,255,255,0.28)]">{result.key}</span>
        )}
      </div>
      {result.group && (
        <span className="flex-shrink-0 rounded-md bg-[rgba(255,255,255,0.05)] px-1.5 py-0.5 text-[9.5px] font-semibold uppercase tracking-[0.06em] text-[rgba(255,255,255,0.32)]">
          {result.group}
        </span>
      )}
    </button>
  )

  return (
    <div ref={rootRef} className="relative flex-1">
      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2">
        <SearchIcon size={14} color="rgba(255,255,255,0.3)" />
      </span>
      <input
        value={query}
        onChange={(e) => { setQuery(e.target.value); setOpen(true) }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        placeholder={t('options.searchPlaceholder')}
        className="h-8 w-full rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] pl-9 pr-3 text-[12px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)]"
      />

      <AnimatePresence>
        {open && query.trim().length > 0 && (
          <motion.div
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.14, ease: EASE_OUT }}
            className="absolute left-0 right-0 top-[calc(100%+4px)] z-20 flex max-h-[320px] flex-col gap-0.5 overflow-y-auto rounded-xl border border-[rgba(255,255,255,0.09)] bg-[rgba(18,17,27,0.98)] p-1 shadow-[0_12px_38px_rgba(0,0,0,0.55)]"
          >
            {ordered.length === 0 ? (
              <p className="px-2.5 py-2 text-[11.5px] text-[rgba(255,255,255,0.3)]">
                {t('options.searchNothing')}
              </p>
            ) : (
              <>
                {sure.map((r, i) => renderRow(r, i))}

                {maybe.length > 0 && (
                  <>
                    <p className="px-2.5 pb-0.5 pt-2 text-[9.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.25)]">
                      {t('options.searchNear')}
                    </p>
                    {maybe.map((r, i) => renderRow(r, sure.length + i))}
                  </>
                )}

                {related.length > 0 && (
                  <>
                    <p className="px-2.5 pb-0.5 pt-2 text-[9.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.25)]">
                      {t('options.searchRelated')}
                    </p>
                    {related.map((c) => (
                      <button
                        key={c.key}
                        onClick={() => choose(c)}
                        className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left transition-colors duration-100 hover:bg-[rgba(255,255,255,0.05)]"
                      >
                        <span className="truncate text-[11.5px] text-[rgba(255,255,255,0.55)]">
                          {c.label ?? c.key}
                        </span>
                      </button>
                    ))}
                  </>
                )}
              </>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
