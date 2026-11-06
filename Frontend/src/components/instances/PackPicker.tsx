import { useEffect, useRef, useState } from 'react'
import { searchModrinthModpacks, type ModpackHit } from '@/lib/modrinthModpacks'
import { fetchPackVersions, type PackVersion } from '@/lib/modpackInstance'
import { SEARCH_PAGE_SIZE } from '@/components/mods/modUtils'
import { Pagination, pageCountFor } from '@/components/ui/Pagination'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { formatDownloadCount } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { useT } from '@/i18n'
import { RemoteImage } from '@/components/ui/RemoteImage'

/** Le modpack choisi et la version du pack à installer. */
export interface PackChoice {
  hit: ModpackHit
  version: PackVersion | null
}

/**
 * Deuxième étape de la création d'une instance depuis un modpack : chercher
 * un modpack sur Modrinth, puis choisir la version du pack. Sans recherche,
 * les plus téléchargés d'abord — c'est ce qu'on veut voir en ouvrant l'écran.
 */
export function PackPicker({ choice, onChoice }: {
  choice: PackChoice | null
  onChoice: (choice: PackChoice | null) => void
}) {
  const t = useT()
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(1)
  const [hits, setHits] = useState<ModpackHit[]>([])
  const [total, setTotal] = useState(0)
  const [searching, setSearching] = useState(true)
  const [versions, setVersions] = useState<PackVersion[] | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  // Une réponse arrivée après une recherche plus récente est ignorée.
  const generation = useRef(0)

  useEffect(() => {
    const gen = ++generation.current
    setSearching(true)
    const timer = setTimeout(() => {
      searchModrinthModpacks(query.trim(), undefined, undefined, { sort: query.trim() ? 'relevance' : 'downloads' }, page)
        .then((res) => {
          if (gen !== generation.current) return
          setHits(res.hits)
          setTotal(res.total)
        })
        .catch(() => {
          if (gen === generation.current) { setHits([]); setTotal(0) }
        })
        .finally(() => {
          if (gen === generation.current) setSearching(false)
        })
    }, query ? 300 : 0)
    return () => clearTimeout(timer)
  }, [query, page])

  // Les versions du pack choisi : la plus récente publiée comme « release » par défaut.
  const chosenId = choice?.hit.project_id
  useEffect(() => {
    if (!chosenId) { setVersions(null); return }
    let alive = true
    setVersions(null)
    fetchPackVersions(chosenId)
      .then((list) => {
        if (!alive) return
        setVersions(list)
        const preferred = list.find((v) => v.version_type === 'release') ?? list[0] ?? null
        onChoice(choice ? { hit: choice.hit, version: preferred } : null)
      })
      .catch(() => { if (alive) setVersions([]) })
    return () => { alive = false }
    // `choice` change avec la version : seul le pack compte ici.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chosenId])

  const changePage = (p: number) => {
    setPage(p)
    listRef.current?.scrollTo({ top: 0 })
  }

  const versionLabel = (v: PackVersion) =>
    [v.version_number, v.game_versions[0], v.loaders.join('/')].filter(Boolean).join(' · ')

  return (
    <div className="flex flex-col gap-3">
      <div className="relative">
        <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(255,255,255,0.3)]">
          <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
        </svg>
        <input
          autoFocus
          type="text"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setPage(1) }}
          placeholder={t('instancesPage.packSearchPlaceholder')}
          className="h-10 w-full rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.05)] pl-9 pr-9 text-sm text-white outline-none focus:border-[rgb(var(--accent)/0.6)]"
        />
        {searching && (
          <ButtonSpinner size={16} color="rgb(var(--accent)/0.8)" trackColor="rgba(255,255,255,0.1)" className="absolute right-3 top-1/2 -translate-y-1/2" />
        )}
      </div>

      <div ref={listRef} className="flex max-h-[340px] flex-col gap-2 overflow-y-auto pr-1">
        {!searching && hits.length === 0 && (
          <p className="py-8 text-center text-[12px] text-[rgba(255,255,255,0.35)]">{t('instancesPage.packNoResults')}</p>
        )}
        {hits.map((hit) => {
          const selected = choice?.hit.project_id === hit.project_id
          return (
            <div
              key={hit.project_id}
              onClick={() => { if (!selected) onChoice({ hit, version: null }) }}
              className={`flex flex-col gap-2.5 rounded-2xl border px-3.5 py-3 transition-colors ${
                selected
                  ? 'border-[rgb(var(--accent)/0.75)] bg-[rgb(var(--accent)/0.14)]'
                  : 'cursor-pointer border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)] hover:border-[rgb(var(--accent)/0.35)]'
              }`}
            >
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center overflow-hidden rounded-xl bg-[rgba(255,255,255,0.06)]">
                  {hit.icon_url
                    ? <RemoteImage src={hit.icon_url} className="h-full w-full object-cover" />
                    : <PlugIcon size={18} color="rgba(255,255,255,0.2)" />}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px] font-semibold text-white">{hit.title}</p>
                  <p className="mt-0.5 truncate text-[11px] text-[rgba(255,255,255,0.4)]">{hit.description}</p>
                  <p className="mt-[3px] text-[10px] text-[rgba(255,255,255,0.25)]">
                    {t('mods.byAuthor', { author: hit.author })} · {formatDownloadCount(hit.downloads)} {t('mods.downloads')}
                  </p>
                </div>
              </div>

              {selected && (
                <div className="flex flex-col gap-1.5 pl-[52px]" onClick={(e) => e.stopPropagation()}>
                  <label className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.4)]">
                    {t('instancesPage.packVersion')}
                  </label>
                  {versions === null ? (
                    <p className="flex items-center gap-2 text-[11px] text-[rgba(255,255,255,0.4)]">
                      <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.1)" /> {t('instancesPage.packVersionsLoading')}
                    </p>
                  ) : versions.length === 0 ? (
                    <p className="text-[11px] text-[rgba(240,180,90,0.8)]">{t('instancesPage.packNoVersion')}</p>
                  ) : (
                    <div className="flex items-center gap-2">
                      <select
                        value={choice?.version?.id ?? ''}
                        onChange={(e) => onChoice({ hit, version: versions.find((v) => v.id === e.target.value) ?? null })}
                        className="h-9 min-w-0 flex-1 rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.35)] px-2 text-[12px] font-medium text-white outline-none"
                      >
                        {versions.map((v) => (
                          <option key={v.id} value={v.id} className="bg-[rgb(var(--bg-card))]">{versionLabel(v)}</option>
                        ))}
                      </select>
                      {choice?.version?.loaders[0] && (
                        <span className="flex-shrink-0 text-[11px] font-semibold" style={{ color: loaderColor(choice.version.loaders[0]) }}>
                          {choice.version.loaders[0]}
                        </span>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>

      <Pagination page={page} pageCount={pageCountFor(total, SEARCH_PAGE_SIZE)} onChange={changePage} disabled={searching} />
    </div>
  )
}
