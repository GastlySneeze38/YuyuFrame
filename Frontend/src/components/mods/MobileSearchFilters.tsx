import type { ReactNode } from 'react'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { Toggle } from '@/components/ui/Toggle'
import { useT } from '@/i18n'
import type { ModrinthSearchFilters } from './modUtils'

const SORT_OPTIONS = ['relevance', 'downloads', 'follows', 'newest', 'updated'] as const

/** Nombre de filtres posés, pour la pastille du bouton « Filtres ». */
export function activeFilterCount(filters: ModrinthSearchFilters | null, categories: string[]): number {
  let n = categories.length
  if (filters) {
    if (filters.environment) n++
    if (filters.license) n++
    if (filters.openSourceOnly) n++
    if (filters.sort && filters.sort !== 'relevance') n++
  }
  return n
}

/**
 * Le bouton « Filtres » des recherches, sur téléphone : 44 px, avec le nombre
 * de filtres posés. Il ouvre `MobileSearchFilters`.
 */
export function MobileFiltersButton({ count, onClick }: { count: number; onClick: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      aria-haspopup="dialog"
      className={`press flex h-11 shrink-0 items-center gap-1.5 rounded-xl border px-3.5 text-[15px] font-semibold ${
        count > 0 ? 'border-[rgba(120,100,255,0.55)] bg-[rgba(75,63,207,0.25)] text-white' : 'border-white/[0.1] bg-white/[0.04] text-white/75'
      }`}
    >
      <svg viewBox="0 0 24 24" fill="currentColor" className="h-[18px] w-[18px]"><path d="M4 6h16v2H4zm3 5h10v2H7zm4 5h2v2h-2z" /></svg>
      {t('mods.filtersToggle')}
      {count > 0 && <span className="rounded-full bg-[#4B3FCF] px-1.5 text-[12px] font-bold tabular-nums">{count}</span>}
    </button>
  )
}

/**
 * Les filtres d'une recherche (mods, modpacks, packs), sur téléphone : une
 * feuille qui monte du bas, qu'on rejette d'un glissé du doigt. Sur PC, les
 * mêmes réglages sont un panneau qui se déplie sous la barre de recherche ;
 * dans 358 px de large ce panneau poussait les résultats hors de l'écran.
 *
 * Les filtres s'appliquent au fur et à mesure, comme sur PC — « Voir les
 * résultats » ne fait que refermer. Le tri passe d'une liste déroulante à des
 * pastilles : cinq choix se lisent d'un coup d'œil, et se touchent mieux.
 */
export function MobileSearchFilters({ open, onClose, categories, selected, onCategories, filters, onFilters, showEnvironment = true }: {
  open: boolean
  onClose: () => void
  /** Catégories proposées (vide = pas de section Catégories). */
  categories: string[]
  selected: string[]
  onCategories: (next: string[]) => void
  /** Filtres Modrinth complets (mods, modpacks). Absents pour les packs. */
  filters?: ModrinthSearchFilters
  onFilters?: (next: ModrinthSearchFilters) => void
  showEnvironment?: boolean
}) {
  const t = useT()
  const count = activeFilterCount(filters ?? null, selected)

  const reset = () => {
    onCategories([])
    if (onFilters) onFilters({})
  }

  return (
    <Sheet
      open={open}
      onClose={onClose}
      label={t('mods.filtersToggle')}
      header={<SheetTitle>{t('mods.filtersToggle')}</SheetTitle>}
      bodyClassName="flex flex-col gap-6 px-5 pb-5"
      footer={
        <div className="flex gap-2">
          <button
            onClick={reset}
            disabled={count === 0}
            className="press h-12 shrink-0 rounded-2xl bg-white/[0.06] px-5 text-[15px] font-semibold text-white/80 disabled:opacity-40"
          >
            {t('mods.filtersReset')}
          </button>
          <button
            onClick={onClose}
            className="press h-12 min-w-0 flex-1 rounded-2xl bg-[#4B3FCF] text-[15px] font-bold text-white"
          >
            {t('mods.mobile.showResults')}
          </button>
        </div>
      }
    >
      {categories.length > 0 && (
        <Section title={t('mods.filtersCategories')}>
          <div className="flex flex-wrap gap-2">
            {categories.map((c) => {
              const active = selected.includes(c)
              return (
                <Chip key={c} active={active} onClick={() => onCategories(active ? selected.filter((x) => x !== c) : [...selected, c])}>
                  {c}
                </Chip>
              )
            })}
          </div>
        </Section>
      )}

      {filters && onFilters && (
        <>
          {showEnvironment && (
            <Section title={t('mods.filtersEnvironment')}>
              {/* Trois choix exclusifs : un commutateur, pas des pastilles. */}
              <div className="flex rounded-2xl bg-white/[0.04] p-1">
                {([undefined, 'client', 'server'] as const).map((env) => (
                  <button
                    key={env ?? 'any'}
                    onClick={() => onFilters({ ...filters, environment: env })}
                    aria-pressed={filters.environment === env}
                    className={`h-10 min-w-0 flex-1 rounded-xl text-[14px] font-semibold transition-colors duration-150 ${
                      filters.environment === env ? 'bg-[rgba(75,63,207,0.35)] text-white' : 'text-white/55'
                    }`}
                  >
                    {env === 'client' ? t('mods.filtersEnvironmentClient') : env === 'server' ? t('mods.filtersEnvironmentServer') : t('mods.filtersEnvironmentAny')}
                  </button>
                ))}
              </div>
            </Section>
          )}

          <Section title={t('mods.filtersSort')}>
            <div className="flex flex-wrap gap-2">
              {SORT_OPTIONS.map((s) => (
                <Chip key={s} active={(filters.sort ?? 'relevance') === s} onClick={() => onFilters({ ...filters, sort: s })}>
                  {t(`mods.sort${s.charAt(0).toUpperCase()}${s.slice(1)}`)}
                </Chip>
              ))}
            </div>
          </Section>

          <Section title={t('mods.filtersLicense')}>
            <input
              type="text"
              value={filters.license ?? ''}
              onChange={(e) => onFilters({ ...filters, license: e.target.value || undefined })}
              placeholder={t('mods.filtersLicensePlaceholder')}
              autoCapitalize="none"
              autoCorrect="off"
              enterKeyHint="done"
              className="h-12 w-full rounded-xl border border-white/[0.1] bg-black/30 px-4 text-[16px] text-white outline-none placeholder:text-white/35 focus:border-[rgba(120,100,255,0.6)]"
            />
            <label className="mt-3 flex min-h-[48px] cursor-pointer items-center justify-between gap-3">
              <span className="text-[15px] text-white/85">{t('mods.filtersOpenSourceOnly')}</span>
              <Toggle
                checked={!!filters.openSourceOnly}
                onChange={() => onFilters({ ...filters, openSourceOnly: filters.openSourceOnly ? undefined : true })}
              />
            </label>
          </Section>
        </>
      )}
    </Sheet>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{title}</h3>
      {children}
    </section>
  )
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`press h-10 rounded-full border px-4 text-[14px] font-semibold transition-colors duration-150 ${
        active ? 'border-[rgba(120,100,255,0.6)] bg-[rgba(75,63,207,0.35)] text-white' : 'border-white/[0.08] bg-white/[0.03] text-white/65'
      }`}
    >
      {children}
    </button>
  )
}
