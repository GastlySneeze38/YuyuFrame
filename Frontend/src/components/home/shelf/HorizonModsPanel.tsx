import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useT } from '@/i18n'
import { showError } from '@/stores/useErrorToast'
import type { Instance } from '@/types'
import type { HorizonInstalled } from '@/types/horizon'
import { DeleteButton, ShelfPanel, Switch, useStackCapacity } from './shared'
import { Chip, PanelTitle, Pill } from './ModsPanel'

/**
 * Panneau des mods de l'accueil pour une instance Horizon : les mods de son
 * registre (`horizon-mods/`, `horizon-native-mods/`), mêmes gestes que celui
 * des mods Java (`ModsPanel`) — clic sur une icône pour allumer ou éteindre,
 * pastille des mises à jour, panneau ouvert avec recherche et filtres.
 *
 * Pas de dépôt de fichier : un mod Horizon s'installe depuis son registre,
 * qui le vérifie (empreinte, signature) ; le pied du panneau mène au registre.
 */

type Filter = 'all' | 'on' | 'off' | 'updates'

const ICON = 26
const OVERLAP = 7

export function HorizonModsPanel({ instance, running, open, onOpen, onClose }: {
  instance: Instance
  running: boolean
  open: boolean
  onOpen: () => void
  onClose: () => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const id = instance.id
  const loaderVersion = instance.loader_version ?? ''
  const [mods, setMods] = useState<HorizonInstalled[] | null>(null)
  const [filter, setFilter] = useState<Filter>('all')
  const [query, setQuery] = useState('')
  const [busy, setBusy] = useState<Set<string>>(new Set())
  const toggling = useRef(new Set<string>())

  const load = useCallback(async () => {
    const list = await api.horizon.installed(id, loaderVersion)
    return list.filter((i) => i.kind === 'mod' || i.kind === 'native-mod')
  }, [id, loaderVersion])

  // Relu à chaque changement d'instance et à la fin d'une partie.
  useEffect(() => {
    let cancelled = false
    setMods(null)
    load().then((l) => { if (!cancelled) setMods(l) }).catch(() => { if (!cancelled) setMods([]) })
    return () => { cancelled = true }
  }, [load, running])

  const mark = (file: string, on: boolean) => setBusy((s) => {
    const n = new Set(s)
    if (on) n.add(file); else n.delete(file)
    return n
  })

  const toggle = async (m: HorizonInstalled) => {
    if (running || toggling.current.has(m.file)) return
    toggling.current.add(m.file)
    setMods((l) => l?.map((x) => (x.file === m.file ? { ...x, enabled: !m.enabled } : x)) ?? null)
    try {
      await api.horizon.toggle(id, m.file, !m.enabled, loaderVersion)
    } catch (e) {
      setMods((l) => l?.map((x) => (x.file === m.file ? m : x)) ?? null)
      showError(e)
    } finally {
      toggling.current.delete(m.file)
    }
  }

  const remove = async (m: HorizonInstalled) => {
    try {
      await api.horizon.remove(id, m.file)
      setMods((l) => l?.filter((x) => x.file !== m.file) ?? null)
    } catch (e) { showError(e) }
  }

  const update = async (m: HorizonInstalled) => {
    if (!m.project) return
    mark(m.file, true)
    try {
      await api.horizon.install(id, m.project, loaderVersion, undefined, m.file)
      setMods(await load())
    } catch (e) { showError(e) }
    finally { mark(m.file, false) }
  }

  const headerRef = useRef<HTMLDivElement>(null)
  const list = mods ?? []
  const off = list.filter((m) => !m.enabled).length
  const updates = list.filter((m) => m.update && m.project)

  const q = query.trim().toLowerCase()
  const shown = list.filter((m) => {
    if (filter === 'on' && !m.enabled) return false
    if (filter === 'off' && m.enabled) return false
    if (filter === 'updates' && !(m.update && m.project)) return false
    return !q || m.name.toLowerCase().includes(q) || m.id.includes(q)
  })

  const [stackRef, capacity] = useStackCapacity(ICON, OVERLAP)
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
          <span className="truncate text-[12px] text-[rgba(255,255,255,0.35)]">{t('mods.horizon.nothingInstalled')}</span>
        ) : (
          <>
            {stackShown.map((m, i) => (
              <button
                key={m.file}
                type="button"
                title={running ? t('home.shelf.lockedWhilePlaying') : `${m.name} — ${t(m.enabled ? 'home.shelf.clickToDisable' : 'home.shelf.clickToEnable')}`}
                onClick={(e) => { e.stopPropagation(); void toggle(m) }}
                disabled={running}
                className="relative flex-shrink-0 rounded-[7px] transition-transform duration-150 ease-out hover:z-10 hover:-translate-y-0.5 active:scale-95 disabled:cursor-not-allowed"
                style={{ marginLeft: i === 0 ? 0 : -OVERLAP, zIndex: stackShown.length - i }}
              >
                <HorizonIcon item={m} size={ICON} ring />
              </button>
            ))}
            {hidden > 0 && <span className="ml-1.5 flex-shrink-0 text-[12px] font-semibold tabular-nums text-[rgba(255,255,255,0.45)]">+{hidden}</span>}
          </>
        )}
      </div>
      {off > 0 && !open && <Pill onClick={() => openOn('off')} tone="muted">{t('home.shelf.offCount', { n: off })}</Pill>}
      {updates.length > 0 && <Pill onClick={() => openOn('updates')}>{t('home.shelf.updatesCount', { n: updates.length })}</Pill>}
    </>
  )

  return (
    <ShelfPanel open={open} onToggle={open ? onClose : onOpen} header={header} dropping={false} headerRef={headerRef}>
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
        <Chip on={filter === 'updates'} onClick={() => setFilter('updates')}>{t('home.shelf.filterUpdates')} {updates.length > 0 && <b className="font-semibold">{updates.length}</b>}</Chip>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-1.5">
        {shown.map((m) => {
          const working = busy.has(m.file)
          return (
            <div key={m.file} className="group flex h-10 items-center gap-2.5 rounded-lg px-2 transition-colors duration-150 hover:bg-[rgba(255,255,255,0.04)]">
              <span className={`transition-[opacity,filter] duration-200 ${m.enabled ? '' : 'opacity-40 grayscale'}`}>
                <HorizonIcon item={m} size={26} />
              </span>
              <span className="flex min-w-0 flex-1 items-baseline gap-2" title={m.file}>
                <span className={`truncate text-[13px] font-medium ${m.enabled ? 'text-[rgba(255,255,255,0.9)]' : 'text-[rgba(255,255,255,0.35)] line-through'}`}>{m.name}</span>
                {m.version && <span className="flex-shrink-0 truncate text-[11.5px] text-[rgba(255,255,255,0.35)]">{m.version}</span>}
                {m.kind === 'native-mod' && (
                  <span className="flex-shrink-0 rounded-full bg-[rgba(250,204,21,0.14)] px-1.5 py-px text-[9px] font-bold uppercase text-[rgba(250,204,21,0.9)]">{t('mods.horizon.nativeBadge')}</span>
                )}
              </span>
              {m.update && m.project && (
                <button
                  type="button"
                  disabled={working || running}
                  onClick={() => void update(m)}
                  title={t('home.shelf.updateTo', { v: m.update })}
                  className="flex h-6 flex-shrink-0 items-center gap-1 rounded-full bg-[rgb(var(--accent)/0.16)] px-2 text-[11px] font-semibold text-[rgb(var(--accent-text))] transition-[background-color,transform] duration-150 hover:bg-[rgb(var(--accent)/0.3)] active:scale-95 disabled:opacity-50"
                >
                  {working ? <span className="h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent" /> : '↑'}
                  <span className="max-w-[90px] truncate">{m.update}</span>
                </button>
              )}
              <DeleteButton label={t('home.shelf.delete')} confirmLabel={t('home.shelf.confirmDelete')} disabled={running} onConfirm={() => void remove(m)} />
              <button
                type="button"
                role="switch"
                aria-checked={m.enabled}
                aria-label={m.name}
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
          <div className="py-6 text-center text-[12.5px] text-[rgba(255,255,255,0.35)]">
            {list.length === 0 ? t('home.shelf.noMods') : t('home.shelf.noMatch')}
          </div>
        )}
      </div>

      <div className="flex flex-shrink-0 items-center gap-2 px-2.5 py-2">
        {updates.length > 1 && (
          <button
            type="button"
            disabled={running || busy.size > 0}
            onClick={async () => { for (const m of updates) await update(m) }}
            className="rounded-lg bg-[rgb(var(--accent)/0.16)] px-2.5 py-1.5 text-[12px] font-semibold text-[rgb(var(--accent-text))] transition-[background-color,transform] duration-150 hover:bg-[rgb(var(--accent)/0.28)] active:scale-[0.97] disabled:opacity-50"
          >
            {t('home.shelf.updateAll', { n: updates.length })}
          </button>
        )}
        <button
          type="button"
          onClick={() => fullScreen('browse')}
          className="rounded-lg px-2.5 py-1.5 text-[12px] font-semibold text-[rgb(var(--accent-text))] transition-colors duration-150 hover:bg-[rgb(var(--accent)/0.12)]"
        >
          + {t('home.shelf.browse')}
        </button>
        <button type="button" onClick={() => fullScreen('installed')} className="ml-auto flex-shrink-0 rounded-lg px-2.5 py-1.5 text-[12px] font-semibold text-[rgba(255,255,255,0.55)] transition-colors duration-150 hover:bg-[rgba(255,255,255,0.06)] hover:text-white">
          {t('home.shelf.fullScreen')} →
        </button>
      </div>
    </ShelfPanel>
  )
}

/** L'icône d'un contenu Horizon : celle de son archive ou de son registre, sinon son initiale. */
export function HorizonIcon({ item, size, ring, round }: { item: HorizonInstalled; size: number; ring?: boolean; round?: boolean }) {
  const [failed, setFailed] = useState(false)
  const src = !failed ? item.icon : null
  return (
    <span
      className={`flex items-center justify-center overflow-hidden ${round ? 'rounded-full' : 'rounded-[7px]'} bg-[rgba(255,255,255,0.08)] ${ring ? 'ring-2 ring-bg-secondary' : ''} ${ring && !item.enabled ? 'opacity-35 grayscale' : ''}`}
      style={{ width: size, height: size }}
    >
      {src ? (
        <img src={src} alt="" draggable={false} onError={() => setFailed(true)} className="h-full w-full object-cover [image-rendering:pixelated]" />
      ) : (
        <span className="font-bold text-[rgba(255,255,255,0.55)]" style={{ fontSize: Math.round(size * 0.42) }}>{item.name.charAt(0).toUpperCase()}</span>
      )}
    </span>
  )
}
