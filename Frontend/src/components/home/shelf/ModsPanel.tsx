import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useT } from '@/i18n'
import { background } from '@/lib/netQueue'
import { useStorePick } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import {
  _iconCache, _modrinthCache, checkForUpdates, displayName, fetchVersionsByHash,
  type ModrinthInfo, type ModUpdate,
} from '@/components/mods/modUtils'
import type { Instance, Mod } from '@/types'
import { DeleteButton, ShelfPanel, Switch, prettyName, queueIcon, useFileDrop, useStackCapacity } from './shared'

/**
 * Panneau des mods de l'accueil.
 *
 * Fermé, une ligne : les icônes empilées des mods, « +N », et la pastille des
 * mises à jour. Chaque élément fait plus d'une chose :
 * - clic sur une icône : active ou désactive le mod, sans rien ouvrir ;
 * - clic sur la pastille : ouvre le panneau filtré sur les mises à jour ;
 * - clic ailleurs : ouvre le panneau ;
 * - un `.jar` glissé dessus : installé.
 *
 * Ouvert : recherche, filtres, une ligne par mod (version, mise à jour,
 * corbeille en deux temps, interrupteur) et « tout mettre à jour ».
 *
 * Les versions et mises à jour viennent de Modrinth, en arrière-plan, et
 * partagent le cache de l'écran des mods (`_modrinthCache`) : passer de l'un
 * à l'autre ne redemande rien. CurseForge reste à l'écran complet.
 */

type Filter = 'all' | 'on' | 'off' | 'updates'

const ICON = 26
const OVERLAP = 7

export function ModsPanel({ instance, running, open, onOpen, onClose }: {
  instance: Instance
  running: boolean
  open: boolean
  onOpen: () => void
  onClose: () => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const id = instance.id
  const { pinnedMods, avoidBetaDependencies } = useStorePick('pinnedMods', 'avoidBetaDependencies')

  const [mods, setMods] = useState<Mod[] | null>(null)
  const [versions, setVersions] = useState<Record<string, ModrinthInfo>>({})
  const [updates, setUpdates] = useState<ModUpdate[]>([])
  const [updating, setUpdating] = useState<Set<string>>(new Set())
  const [filter, setFilter] = useState<Filter>('all')
  const [query, setQuery] = useState('')
  const toggling = useRef(new Set<string>())
  // Instance affichée : une réponse Modrinth arrivée après un changement
  // d'instance est rangée dans le cache, jamais affichée.
  const shownId = useRef(id)
  shownId.current = id

  // ── Chargement ─────────────────────────────────────────────────────────────

  const lookup = useCallback((list: Mod[]) => {
    const cached = _modrinthCache[id]
    if (cached) { setVersions(cached.versionMap); setUpdates(cached.updates); return }
    background(async () => {
      const vd = await fetchVersionsByHash(list.map((m) => m.sha1))
      if (shownId.current === id) setVersions(vd)
      const upd = await checkForUpdates(id, list, vd, instance.mc_version, instance.loader, avoidBetaDependencies)
      if (shownId.current === id) setUpdates(upd)
      _modrinthCache[id] = { versionMap: vd, updates: upd }
    })
  }, [id, instance.mc_version, instance.loader, avoidBetaDependencies])

  // Relu à chaque changement d'instance et à la fin d'une partie.
  useEffect(() => {
    let cancelled = false
    setMods(null); setVersions({}); setUpdates([])
    api.mods.list(id)
      .then((list) => { if (cancelled) return; setMods(list); lookup(list) })
      .catch(() => { if (!cancelled) setMods([]) })
    return () => { cancelled = true }
  }, [id, running, lookup])

  // Le contenu change : le cache partagé n'est plus vrai.
  const invalidate = () => { delete _modrinthCache[id] }

  // ── Gestes ─────────────────────────────────────────────────────────────────

  const toggle = useCallback(async (mod: Mod) => {
    if (running || toggling.current.has(mod.name)) return
    toggling.current.add(mod.name)
    // Optimiste, et par fonction : deux bascules rapides ne s'écrasent pas.
    setMods((l) => l?.map((m) => (m.name === mod.name ? { ...m, enabled: !mod.enabled } : m)) ?? null)
    try {
      const updated = await api.mods.toggle(id, mod.name)
      setMods((l) => l?.map((m) => (m.name === mod.name ? updated : m)) ?? null)
    } catch (e) {
      setMods((l) => l?.map((m) => (m.name === mod.name ? mod : m)) ?? null)
      showError(e)
    } finally {
      toggling.current.delete(mod.name)
    }
  }, [id, running])

  const remove = async (mod: Mod) => {
    try {
      await api.mods.delete(id, mod.name)
      setMods((l) => l?.filter((m) => m.name !== mod.name) ?? null)
      setUpdates((u) => u.filter((x) => x.mod.name !== mod.name))
      invalidate()
    } catch (e) { showError(e) }
  }

  // Même enchaînement que l'écran des mods (`handleUpdateMod`).
  const update = async (u: ModUpdate) => {
    setUpdating((s) => new Set(s).add(u.mod.sha1))
    try {
      const fresh = await api.mods.install(id, u.fileUrl, u.filename)
      if (fresh.name !== u.mod.name) await api.mods.delete(id, u.mod.name).catch(() => {})
      setMods((l) => l ? sortMods([...l.filter((m) => m.name !== u.mod.name), fresh]) : l)
      setUpdates((list) => list.filter((x) => x.mod.sha1 !== u.mod.sha1))
      // Un mod de modpack garde sa référence dans modpack.json.
      api.modpacks.renameFile(id, u.mod.name.replace(/\.disabled$/, ''), fresh.name).catch(() => {})
      fetchVersionsByHash([fresh.sha1]).then((vd) => setVersions((v) => ({ ...v, ...vd })))
      invalidate()
    } catch (e) { showError(e) }
    finally { setUpdating((s) => { const n = new Set(s); n.delete(u.mod.sha1); return n }) }
  }

  const importJars = async (paths: string[]) => {
    try {
      const result = await api.mods.importPaths(id, paths)
      if (result.imported.length === 0) return
      setMods((l) => sortMods([...(l ?? []).filter((m) => !result.imported.some((x) => x.name === m.name)), ...result.imported]))
      fetchVersionsByHash(result.imported.map((m) => m.sha1)).then((vd) => setVersions((v) => ({ ...v, ...vd })))
      invalidate()
    } catch (e) { showError(e) }
  }

  // Zone de dépôt : la ligne du bas fermé, tout le panneau ouvert.
  const headerRef = useRef<HTMLDivElement>(null)
  const dropping = useFileDrop(
    // Le panneau ouvert est la couche juste avant la ligne d'en-tête.
    () => (open ? (headerRef.current?.previousElementSibling as HTMLElement | null) ?? null : headerRef.current),
    (p) => /\.jar$/i.test(p),
    (paths) => { if (!running) void importJars(paths) },
  )

  // ── Données affichées ──────────────────────────────────────────────────────

  // Les mods épinglés ne sont jamais proposés (même règle que l'écran des mods),
  // ni ceux dont la mise à jour casserait un autre mod.
  const offered = useMemo(() => updates.filter((u) => {
    if (u.blockedBy.length > 0) return false
    const pid = versions[u.mod.sha1]?.projectId
    return !(pid && pinnedMods[`${id}:${pid}`])
  }), [updates, versions, pinnedMods, id])
  const updateOf = useMemo(() => new Map(offered.map((u) => [u.mod.name, u])), [offered])

  const list = mods ?? []
  const off = list.filter((m) => !m.enabled).length
  const nameOf = (m: Mod) => versions[m.sha1]?.modrinthName || prettyName(m.name)

  const q = query.trim().toLowerCase()
  const shown = list.filter((m) => {
    if (filter === 'on' && !m.enabled) return false
    if (filter === 'off' && m.enabled) return false
    if (filter === 'updates' && !updateOf.has(m.name)) return false
    return !q || nameOf(m).toLowerCase().includes(q) || m.name.toLowerCase().includes(q)
  })

  const [stackRef, capacity] = useStackCapacity(ICON, OVERLAP)
  // Actifs d'abord dans la pile : c'est ce qui tourne en jeu.
  const stack = [...list.filter((m) => m.enabled), ...list.filter((m) => !m.enabled)]
  const stackShown = stack.length > capacity ? stack.slice(0, Math.max(0, capacity - 1)) : stack
  const hidden = stack.length - stackShown.length

  const openOn = (f: Filter) => { setFilter(f); if (!open) onOpen() }
  const fullScreen = (content: string) => { onClose(); navigate(`/instances?content=${content}`) }

  const header = (
    <>
      <PanelTitle icon="M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5" label={t('home.shelf.mods')} count={mods?.length} />
      <div ref={stackRef} className="flex min-w-0 flex-1 items-center">
        {mods === null ? (
          <span className="h-[26px] w-24 animate-pulse rounded-md bg-[rgba(255,255,255,0.05)]" />
        ) : list.length === 0 ? (
          <span className="truncate text-[12px] text-[rgba(255,255,255,0.35)]">{t('home.shelf.dropJar')}</span>
        ) : (
          <>
            {stackShown.map((m, i) => (
              <button
                key={displayName(m.name)}
                type="button"
                title={running ? t('home.shelf.lockedWhilePlaying') : `${nameOf(m)} — ${t(m.enabled ? 'home.shelf.clickToDisable' : 'home.shelf.clickToEnable')}`}
                onClick={(e) => { e.stopPropagation(); void toggle(m) }}
                disabled={running}
                className="relative flex-shrink-0 rounded-[7px] transition-transform duration-150 ease-out hover:z-10 hover:-translate-y-0.5 active:scale-95 disabled:cursor-not-allowed"
                style={{ marginLeft: i === 0 ? 0 : -OVERLAP, zIndex: stackShown.length - i }}
              >
                <ModIcon instanceId={id} mod={m} name={nameOf(m)} size={ICON} ring />
              </button>
            ))}
            {hidden > 0 && <span className="ml-1.5 flex-shrink-0 text-[12px] font-semibold tabular-nums text-[rgba(255,255,255,0.45)]">+{hidden}</span>}
          </>
        )}
      </div>
      {off > 0 && !open && (
        <Pill onClick={() => openOn('off')} tone="muted">{t('home.shelf.offCount', { n: off })}</Pill>
      )}
      {offered.length > 0 && (
        <Pill onClick={() => openOn('updates')}>{t('home.shelf.updatesCount', { n: offered.length })}</Pill>
      )}
    </>
  )

  return (
    <ShelfPanel open={open} onToggle={open ? onClose : onOpen} header={header} dropping={dropping} headerRef={headerRef}>
      <div className="flex flex-shrink-0 flex-wrap items-center gap-1.5 p-2.5 pb-2">
        <label className="flex h-8 min-w-[160px] flex-1 items-center gap-2 rounded-lg bg-[rgba(0,0,0,0.3)] px-2.5 text-[12.5px] text-[rgba(255,255,255,0.4)] focus-within:ring-1 focus-within:ring-[rgb(var(--accent)/0.5)]">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" width={13} height={13}><path d="M11 4a7 7 0 100 14 7 7 0 000-14zM20 20l-4-4" /></svg>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('home.shelf.searchMods', { n: list.length })}
            className="min-w-0 flex-1 bg-transparent text-white outline-none placeholder:text-[rgba(255,255,255,0.35)]"
          />
        </label>
        <Chip on={filter === 'all'} onClick={() => setFilter('all')}>{t('home.shelf.filterAll')}</Chip>
        <Chip on={filter === 'on'} onClick={() => setFilter('on')}>{t('home.shelf.filterOn')}</Chip>
        <Chip on={filter === 'off'} onClick={() => setFilter('off')}>{t('home.shelf.filterOff')} {off > 0 && <b className="font-semibold">{off}</b>}</Chip>
        <Chip on={filter === 'updates'} onClick={() => setFilter('updates')}>{t('home.shelf.filterUpdates')} {offered.length > 0 && <b className="font-semibold">{offered.length}</b>}</Chip>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-1.5">
        {shown.map((m) => {
          const u = updateOf.get(m.name)
          const busy = updating.has(m.sha1)
          return (
            // `content-visibility` : une ligne hors de vue n'est ni mise en
            // page ni peinte, même avec deux cents mods.
            <div key={displayName(m.name)} className="group flex h-10 items-center [contain-intrinsic-size:auto_40px] [content-visibility:auto] gap-2.5 rounded-lg px-2 transition-colors duration-150 hover:bg-[rgba(255,255,255,0.04)]">
              <span className={`transition-[opacity,filter] duration-200 ${m.enabled ? '' : 'opacity-40 grayscale'}`}>
                <ModIcon instanceId={id} mod={m} name={nameOf(m)} size={26} />
              </span>
              <span className="flex min-w-0 flex-1 items-baseline gap-2" title={displayName(m.name)}>
                <span className={`truncate text-[13px] font-medium ${m.enabled ? 'text-[rgba(255,255,255,0.9)]' : 'text-[rgba(255,255,255,0.35)] line-through'}`}>{nameOf(m)}</span>
                {versions[m.sha1]?.version && <span className="flex-shrink-0 truncate text-[11.5px] text-[rgba(255,255,255,0.35)]">{versions[m.sha1].version}</span>}
              </span>
              {u && (
                <button
                  type="button"
                  disabled={busy || running}
                  onClick={() => void update(u)}
                  title={t('home.shelf.updateTo', { v: u.newVersion })}
                  className="flex h-6 flex-shrink-0 items-center gap-1 rounded-full bg-[rgb(var(--accent)/0.16)] px-2 text-[11px] font-semibold text-[rgb(var(--accent-text))] transition-[background-color,transform] duration-150 hover:bg-[rgb(var(--accent)/0.3)] active:scale-95 disabled:opacity-50"
                >
                  {busy ? <span className="h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent" /> : '↑'}
                  <span className="max-w-[90px] truncate">{u.newVersion}</span>
                </button>
              )}
              <DeleteButton
                label={t('home.shelf.delete')}
                confirmLabel={t('home.shelf.confirmDelete')}
                disabled={running}
                onConfirm={() => void remove(m)}
              />
              <button
                type="button"
                role="switch"
                aria-checked={m.enabled}
                aria-label={nameOf(m)}
                disabled={running}
                title={running ? t('home.shelf.lockedWhilePlaying') : undefined}
                onClick={() => void toggle(m)}
                className="flex h-7 items-center px-1 transition-transform duration-150 active:scale-95 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Switch on={m.enabled} />
              </button>
            </div>
          )
        })}
        {mods !== null && shown.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-6 text-[12.5px] text-[rgba(255,255,255,0.35)]">
            {list.length === 0 ? t('home.shelf.noMods') : t('home.shelf.noMatch')}
            {q && (
              <button type="button" onClick={() => fullScreen('browse')} className="rounded-lg px-2.5 py-1 font-semibold text-[rgb(var(--accent-text))] hover:bg-[rgb(var(--accent)/0.12)]">
                {t('home.shelf.searchModrinth', { q: query.trim() })} →
              </button>
            )}
          </div>
        )}
      </div>

      <div className="flex flex-shrink-0 items-center gap-2 px-2.5 py-2">
        {offered.length > 1 && (
          <button
            type="button"
            disabled={running || updating.size > 0}
            onClick={async () => { for (const u of offered) await update(u) }}
            className="rounded-lg bg-[rgb(var(--accent)/0.16)] px-2.5 py-1.5 text-[12px] font-semibold text-[rgb(var(--accent-text))] transition-[background-color,transform] duration-150 hover:bg-[rgb(var(--accent)/0.28)] active:scale-[0.97] disabled:opacity-50"
          >
            {t('home.shelf.updateAll', { n: offered.length })}
          </button>
        )}
        <span className="truncate text-[11.5px] text-[rgba(255,255,255,0.3)]">{t('home.shelf.dropJar')}</span>
        <button type="button" onClick={() => fullScreen('installed')} className="ml-auto flex-shrink-0 rounded-lg px-2.5 py-1.5 text-[12px] font-semibold text-[rgba(255,255,255,0.55)] transition-colors duration-150 hover:bg-[rgba(255,255,255,0.06)] hover:text-white">
          {t('home.shelf.fullScreen')} →
        </button>
      </div>
    </ShelfPanel>
  )
}

function sortMods(list: Mod[]): Mod[] {
  return [...list].sort((a, b) => displayName(a.name).toLowerCase().localeCompare(displayName(b.name).toLowerCase()))
}

/** Icône d'un mod, tirée de son jar (cache partagé avec l'écran des mods). */
function ModIcon({ instanceId, mod, name, size, ring }: { instanceId: string; mod: Mod; name: string; size: number; ring?: boolean }) {
  const cacheKey = `${instanceId}/${displayName(mod.name)}`
  const [icon, setIcon] = useState<string | null | undefined>(() => _iconCache[cacheKey])
  useEffect(() => {
    if (cacheKey in _iconCache) { setIcon(_iconCache[cacheKey]); return }
    let cancelled = false
    queueIcon(() => api.mods.icon(instanceId, mod.name))
      .then((uri) => { _iconCache[cacheKey] = uri; if (!cancelled) setIcon(uri) })
      .catch(() => { _iconCache[cacheKey] = null; if (!cancelled) setIcon(null) })
    return () => { cancelled = true }
  }, [cacheKey, instanceId, mod.name])

  return (
    <span
      className={`flex items-center justify-center overflow-hidden rounded-[7px] bg-[rgba(255,255,255,0.08)] transition-[opacity,filter] duration-200 ${ring ? 'ring-2 ring-bg-secondary' : ''} ${ring && !mod.enabled ? 'opacity-35 grayscale' : ''}`}
      style={{ width: size, height: size }}
    >
      {icon ? (
        <img src={icon} alt="" draggable={false} className="h-full w-full object-cover [image-rendering:pixelated]" />
      ) : (
        <span className="text-[11px] font-bold text-[rgba(255,255,255,0.55)]">{name.charAt(0).toUpperCase()}</span>
      )}
    </span>
  )
}

export function PanelTitle({ icon, label, count }: { icon: string; label: string; count?: number }) {
  return (
    <span className="flex flex-shrink-0 items-center gap-2">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-[18px] w-[18px] text-[rgba(255,255,255,0.45)]">
        <path d={icon} />
      </svg>
      <span className="text-[13px] font-semibold text-white">{label}</span>
      {count !== undefined && <span className="text-[12px] tabular-nums text-[rgba(255,255,255,0.35)]">{count}</span>}
    </span>
  )
}

export function Pill({ children, onClick, tone = 'accent' }: { children: React.ReactNode; onClick: () => void; tone?: 'accent' | 'muted' }) {
  return (
    <button
      type="button"
      onClick={(e) => { e.stopPropagation(); onClick() }}
      className={`flex-shrink-0 whitespace-nowrap rounded-full px-2.5 py-1 text-[11.5px] font-semibold transition-[background-color,transform] duration-150 active:scale-95 ${
        tone === 'accent'
          ? 'bg-[rgb(var(--accent)/0.16)] text-[rgb(var(--accent-text))] hover:bg-[rgb(var(--accent)/0.28)]'
          : 'bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.55)] hover:bg-[rgba(255,255,255,0.1)]'
      }`}
    >
      {children}
    </button>
  )
}

export function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex h-8 items-center gap-1 whitespace-nowrap rounded-lg px-2.5 text-[12px] font-medium transition-colors duration-150 ${
        on ? 'bg-[rgb(var(--accent)/0.22)] text-white' : 'bg-[rgba(255,255,255,0.04)] text-[rgba(255,255,255,0.5)] hover:text-white'
      }`}
    >
      {children}
    </button>
  )
}
