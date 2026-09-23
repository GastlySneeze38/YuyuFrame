import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { listen } from '@tauri-apps/api/event'
import { press, pressIf, listVariants, listItemVariants } from '@/lib/motion'
import { api } from '@/api/client'
import { formatBytes } from '@/lib/format'
import { showError } from '@/stores/useErrorToast'
import { EmptyState } from '@/components/ui/EmptyState'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import type { ModrinthHit } from './modUtils'
import type { ModInstallProgress, PackInfo, PackKind } from '@/types'

/**
 * Packs de ressources et shaders : recherche, installation, gestion.
 *
 * Un seul composant pour les trois écrans du menu « Packs » plutôt qu'un par
 * écran. Ils partagent tout ce qui compte — la même liste installée à
 * rafraîchir après chaque pose, la même recherche Modrinth au type près, la
 * même suppression. Les séparer imposerait de faire remonter cet état d'un
 * cran pour qu'ils restent d'accord entre eux.
 *
 * Volontairement plus simple que l'écran des mods : pas de CurseForge (le
 * proxy ne couvre pas ces types), pas de détection de mises à jour (un pack
 * n'a pas de dépendances et ne casse pas un lancement), pas d'activation (le
 * jeu choisit lui-même le pack actif, via options.txt pour les ressources et
 * via la configuration du mod de shaders pour les shaders).
 */

export type PacksMode = 'installed' | 'resourcepack' | 'shader'

/** Catégories Modrinth propres à chaque type — le vocabulaire des mods
 *  (technology, magic…) n'a aucun sens ici. Affichées telles quelles,
 *  comme dans la recherche de mods. */
const CATEGORIES: Record<PackKind, string[]> = {
  resourcepack: ['8x-', '16x', '32x', '48x', '64x', '128x', '256x', 'realistic', 'simplistic', 'themed', 'vanilla-like', 'combat', 'decoration'],
  shader: ['iris', 'optifine', 'vanilla', 'fantasy', 'realistic', 'semi-realistic', 'cartoon', 'potato', 'low', 'medium', 'high', 'screenshot'],
}

const KIND_ICON: Record<PackKind, string> = {
  resourcepack: 'M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z',
  shader: 'M12 3l2.09 6.26L20.5 9.5l-5 3.8 1.9 6.2L12 15.8 6.6 19.5l1.9-6.2-5-3.8 6.41-.24L12 3z',
}

/** Trouve le fichier à télécharger pour ce pack.
 *
 *  Deux essais : d'abord restreint à la version du jeu, puis sans filtre. Un
 *  shader n'est pas toujours étiqueté pour chaque version de Minecraft alors
 *  qu'il y fonctionne — s'arrêter au premier essai rendrait la moitié du
 *  catalogue non installable. */
async function resolvePackFile(slug: string, gameVersion: string): Promise<{ url: string; filename: string } | null> {
  for (const params of [`?game_versions=${encodeURIComponent(JSON.stringify([gameVersion]))}`, '']) {
    try {
      const res = await fetch(`https://api.modrinth.com/v2/project/${slug}/version${params}`, {
        headers: { 'User-Agent': 'YuyuFrame/1.0' },
      })
      if (!res.ok) continue
      const versions: Array<{ files: Array<{ url: string; filename: string; primary: boolean }> }> = await res.json()
      for (const version of versions) {
        const file = version.files.find((f) => f.primary && f.filename.toLowerCase().endsWith('.zip'))
          ?? version.files.find((f) => f.filename.toLowerCase().endsWith('.zip'))
        if (file) return { url: file.url, filename: file.filename }
      }
    } catch {
      // Réseau indisponible : on laisse l'appelant le signaler une fois, à la
      // fin, plutôt que de le dire deux fois pour deux essais.
    }
  }
  return null
}

export function PacksTab({
  instanceId, mcVersion, mode, onInstalledCountChange,
}: {
  instanceId: string
  mcVersion: string
  mode: PacksMode
  /** Remonte le nombre de packs installés pour le compteur du menu. */
  onInstalledCountChange?: (count: number) => void
}) {
  const t = useT()
  const [installed, setInstalled] = useState<PackInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<ModrinthHit[]>([])
  const [searching, setSearching] = useState(false)
  const [categories, setCategories] = useState<string[]>([])
  const [installing, setInstalling] = useState<string | null>(null)
  const [progress, setProgress] = useState<{ percent: number } | null>(null)

  const browseKind: PackKind | null = mode === 'installed' ? null : mode

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      // Les deux dossiers sont lus ensemble : l'écran « installés » montre les
      // deux familles côte à côte, et après une pose on ne sait pas laquelle a
      // changé sans la relire.
      const [packs, shaders] = await Promise.all([
        api.packs.list(instanceId, 'resourcepack'),
        api.packs.list(instanceId, 'shader'),
      ])
      const all = [...packs, ...shaders]
      setInstalled(all)
      onInstalledCountChange?.(all.length)
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }, [instanceId, onInstalledCountChange])

  useEffect(() => { void refresh() }, [refresh])

  // Recherche relancée à la frappe, après une pause : sans ce délai, taper
  // « faithful » lancerait huit requêtes dont sept jetées.
  useEffect(() => {
    if (!browseKind) return
    let cancelled = false
    setSearching(true)
    const timer = setTimeout(async () => {
      try {
        const res = await api.mods.searchAdvanced({
          query,
          gameVersion: mcVersion || undefined,
          // Le loader ne s'applique pas : un pack ne dépend pas de Fabric ni
          // de Forge. Passer celui de l'instance ne rendrait rien, puisque le
          // backend en ferait une facette `categories:fabric`.
          loader: 'vanilla',
          projectType: browseKind,
          categories,
          limit: 40,
        })
        // Même cast que `fetchModrinthSearch` : le backend rend le JSON
        // Modrinth brut, les champs utilisés ici sont bien présents.
        if (!cancelled) setResults(res.hits as unknown as ModrinthHit[])
      } catch (e) {
        if (!cancelled) { setResults([]); showError(e) }
      } finally {
        if (!cancelled) setSearching(false)
      }
    }, 320)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [browseKind, query, mcVersion, categories])

  // Le type change (shaders ↔ ressources) : les résultats de l'autre type
  // n'ont plus rien à faire là, et ses catégories non plus.
  useEffect(() => { setResults([]); setCategories([]) }, [browseKind])

  // Progression du téléchargement : un pack de ressources en haute
  // résolution pèse couramment plus qu'un modpack entier, le bouton ne peut
  // pas se contenter d'un « … ».
  useEffect(() => {
    let stop: (() => void) | null = null
    let cancelled = false
    void listen<ModInstallProgress>('pack_install_progress', (e) => {
      const { downloaded, total } = e.payload
      setProgress(total > 0 ? { percent: Math.round((downloaded / total) * 100) } : null)
    }).then((f) => { cancelled ? f() : (stop = f) })
    return () => { cancelled = true; stop?.() }
  }, [])

  const installedNames = useMemo(() => new Set(installed.map((p) => p.name.toLowerCase())), [installed])

  const handleInstall = async (hit: ModrinthHit) => {
    if (!browseKind || installing) return
    setInstalling(hit.project_id)
    setProgress(null)
    try {
      const file = await resolvePackFile(hit.slug, mcVersion)
      if (!file) { showError(t('packs.noFile', { name: hit.title })); return }
      await api.packs.install(instanceId, browseKind, file.url, file.filename)
      await refresh()
    } catch (e) {
      showError(e)
    } finally {
      setInstalling(null)
      setProgress(null)
    }
  }

  const handleDelete = async (pack: PackInfo) => {
    try {
      await api.packs.delete(instanceId, pack.kind, pack.name)
      await refresh()
    } catch (e) {
      showError(e)
    }
  }

  // ── Écran « installés » ───────────────────────────────────────────────────
  if (mode === 'installed') {
    if (loading) {
      return <EmptyState compact icon={<ButtonSpinner size={18} />} title={t('packs.loading')} />
    }
    if (installed.length === 0) {
      return (
        <EmptyState
          icon={<svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.18)" width={26} height={26}><path d={KIND_ICON.resourcepack} /></svg>}
          title={t('packs.emptyInstalled')}
          subtitle={t('packs.emptyInstalledHint')}
        />
      )
    }
    return (
      <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-1.5">
        {installed.map((pack) => (
          <motion.div
            key={`${pack.kind}-${pack.name}`}
            variants={listItemVariants}
            className="flex items-center gap-3 rounded-xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.02)] px-3 py-2.5"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15} className="flex-shrink-0 text-[rgba(150,140,230,0.7)]">
              <path d={KIND_ICON[pack.kind]} />
            </svg>
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[12.5px] font-semibold text-[rgba(255,255,255,0.85)]">{pack.name}</span>
              <span className="text-[10.5px] text-[rgba(255,255,255,0.28)]">
                {t(pack.kind === 'shader' ? 'packs.kindShader' : 'packs.kindResourcepack')} · {formatBytes(pack.size)}
              </span>
            </div>
            <motion.button {...press}
              onClick={() => handleDelete(pack)}
              title={t('packs.delete')}
              className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-lg text-[rgba(255,255,255,0.28)] transition-colors duration-150 hover:bg-[rgba(200,50,50,0.14)] hover:text-[rgb(248,113,113)]"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
                <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
              </svg>
            </motion.button>
          </motion.div>
        ))}
      </motion.div>
    )
  }

  // ── Écrans de recherche ───────────────────────────────────────────────────
  const kind = browseKind as PackKind

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2">
            <SearchIcon size={14} color="rgba(255,255,255,0.3)" />
          </span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t(kind === 'shader' ? 'packs.searchShaders' : 'packs.searchResourcepacks')}
            className="h-9 w-full rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] pl-9 pr-3 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)]"
          />
        </div>
        {searching && <ButtonSpinner size={16} />}
      </div>

      <div className="flex flex-wrap gap-1.5">
        {CATEGORIES[kind].map((c) => {
          const active = categories.includes(c)
          return (
            <button
              key={c}
              onClick={() => setCategories(active ? categories.filter((x) => x !== c) : [...categories, c])}
              className={`h-7 rounded-lg border px-2.5 text-[11px] font-semibold transition-colors duration-150 ${
                active
                  ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] text-[rgba(255,255,255,0.95)]'
                  : 'border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.4)] hover:text-[rgba(255,255,255,0.7)]'
              }`}
            >
              {c}
            </button>
          )
        })}
      </div>

      {!searching && results.length === 0 ? (
        <EmptyState
          icon={<svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.18)" width={26} height={26}><path d={KIND_ICON[kind]} /></svg>}
          title={t('packs.noResult')}
          subtitle={t('packs.noResultHint')}
        />
      ) : (
        <motion.div variants={listVariants} initial="initial" animate="animate" className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-2">
          <AnimatePresence initial={false}>
            {results.map((hit) => {
              const busy = installing === hit.project_id
              return (
                <motion.div
                  key={hit.project_id}
                  variants={listItemVariants}
                  className="flex gap-2.5 rounded-xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.02)] p-2.5"
                >
                  {hit.icon_url ? (
                    <img src={hit.icon_url} alt="" className="h-10 w-10 flex-shrink-0 rounded-lg object-cover" />
                  ) : (
                    <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-[rgba(75,63,207,0.2)]">
                      <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.35)" width={16} height={16}><path d={KIND_ICON[kind]} /></svg>
                    </div>
                  )}
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <span className="truncate text-[12.5px] font-bold text-[rgba(255,255,255,0.88)]">{hit.title}</span>
                    <span className="line-clamp-2 text-[10.5px] leading-[1.45] text-[rgba(255,255,255,0.32)]">{hit.description}</span>
                  </div>
                  <motion.button {...pressIf(!busy)}
                    onClick={() => handleInstall(hit)}
                    disabled={!!installing}
                    className={`flex h-7 flex-shrink-0 items-center gap-1 self-start rounded-lg px-2.5 text-[11px] font-semibold transition-colors duration-150 ${
                      busy
                        ? 'bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.4)]'
                        : 'bg-[rgba(75,63,207,0.3)] text-[rgba(255,255,255,0.85)] hover:bg-[rgba(75,63,207,0.5)] disabled:opacity-40'
                    }`}
                  >
                    {busy
                      ? (progress ? `${progress.percent}%` : t('packs.installing'))
                      : t('packs.install')}
                  </motion.button>
                </motion.div>
              )
            })}
          </AnimatePresence>
        </motion.div>
      )}

      {installedNames.size > 0 && (
        <span className="text-[10.5px] text-[rgba(255,255,255,0.22)]">
          {t('packs.installedCount', { count: installedNames.size })}
        </span>
      )}
    </div>
  )
}
