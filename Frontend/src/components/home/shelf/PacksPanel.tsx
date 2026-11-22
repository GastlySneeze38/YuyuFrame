import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useT } from '@/i18n'
import { formatBytes } from '@/lib/format'
import { showError } from '@/stores/useErrorToast'
import type { Instance, PackInfo, PackKind } from '@/types'
import type { HorizonInstalled } from '@/types/horizon'
import { HorizonIcon } from './HorizonModsPanel'
import { DeleteButton, ShelfPanel, prettyName, queueIcon, tintFor, useFileDrop, useStackCapacity } from './shared'
import { Chip, PanelTitle } from './ModsPanel'

/**
 * Panneau des packs de l'accueil : packs de ressources et shaders.
 *
 * Fermé, une ligne : les vignettes des packs de ressources en carrés, un
 * séparateur, les shaders en ronds — on les distingue sans un mot. Clic sur
 * une vignette : ouvre le panneau sur sa famille. Un `.zip` glissé dessus est
 * ajouté (à la famille ouverte, packs de ressources si le panneau est fermé).
 *
 * Ouvert : les deux familles en onglets, les pochettes en grand, une ligne
 * par pack avec sa taille et sa corbeille. Pas d'interrupteur : c'est le jeu
 * qui active un pack (voir `instances/packs.rs`).
 */

const THUMB = 26
const OVERLAP = 8

/** Vignettes gardées pour la session. */
const iconCache = new Map<string, string | null>()

export function PacksPanel({ instance, running, open, onOpen, onClose }: {
  instance: Instance
  running: boolean
  open: boolean
  onOpen: () => void
  onClose: () => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const id = instance.id
  const [packs, setPacks] = useState<PackInfo[] | null>(null)
  const [shaders, setShaders] = useState<PackInfo[] | null>(null)
  const [tab, setTab] = useState<PackKind>('resourcepack')
  // Horizon : ses shaders `.hshader` (un seul utilisé à la fois), en plus des
  // packs `.zip`. Les packs de ressources de son registre sont des `.zip`.
  const horizon = instance.loader === 'horizon'
  const loaderVersion = instance.loader_version ?? ''
  const [hshaders, setHshaders] = useState<HorizonInstalled[]>([])

  useEffect(() => {
    let cancelled = false
    setPacks(null); setShaders(null); setHshaders([])
    api.packs.list(id, 'resourcepack').then((l) => { if (!cancelled) setPacks(l) }).catch(() => { if (!cancelled) setPacks([]) })
    api.packs.list(id, 'shader').then((l) => { if (!cancelled) setShaders(l) }).catch(() => { if (!cancelled) setShaders([]) })
    if (horizon) {
      api.horizon.installed(id, loaderVersion)
        .then((l) => { if (!cancelled) setHshaders(l.filter((i) => i.kind === 'shader')) })
        .catch(() => {})
    }
    return () => { cancelled = true }
  }, [id, running, horizon, loaderVersion])

  const pickShader = async (s: HorizonInstalled) => {
    try {
      await api.horizon.pickShader(id, s.enabled ? null : s.file)
      setHshaders((l) => l.map((x) => ({ ...x, enabled: x.file === s.file ? !s.enabled : false })))
    } catch (e) { showError(e) }
  }
  const removeHshader = async (s: HorizonInstalled) => {
    try {
      await api.horizon.remove(id, s.file)
      setHshaders((l) => l.filter((x) => x.file !== s.file))
    } catch (e) { showError(e) }
  }

  const setList = (kind: PackKind, fn: (l: PackInfo[]) => PackInfo[]) =>
    (kind === 'shader' ? setShaders : setPacks)((l) => fn(l ?? []))

  const remove = async (p: PackInfo) => {
    try {
      await api.packs.delete(id, p.kind, p.name)
      setList(p.kind, (l) => l.filter((x) => x.name !== p.name))
    } catch (e) { showError(e) }
  }

  const importZips = async (paths: string[]) => {
    const kind: PackKind = open ? tab : 'resourcepack'
    try {
      const added = await api.packs.importPaths(id, kind, paths)
      setList(kind, (l) => [...l.filter((x) => !added.some((a) => a.name === x.name)), ...added]
        .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase())))
      if (open) setTab(kind)
    } catch (e) { showError(e) }
  }

  const headerRef = useRef<HTMLDivElement>(null)
  const dropping = useFileDrop(
    // Le panneau ouvert est la couche juste avant la ligne d'en-tête.
    () => (open ? (headerRef.current?.previousElementSibling as HTMLElement | null) ?? null : headerRef.current),
    (p) => /\.zip$/i.test(p),
    (paths) => void importZips(paths),
  )

  const res = packs ?? []
  const sh = shaders ?? []
  const loading = packs === null || shaders === null
  const shCount = sh.length + hshaders.length
  const total = res.length + shCount

  // Les deux groupes se partagent la place de la pile, shaders d'abord servis
  // d'une place s'il y en a (sinon ils disparaîtraient derrière les packs).
  const [stackRef, capacity] = useStackCapacity(THUMB, OVERLAP)
  const shSlots = shCount > 0 ? Math.min(shCount, Math.max(1, Math.floor(capacity / 3))) : 0
  const resSlots = Math.max(0, capacity - shSlots - (shCount > 0 ? 1 : 0))
  const resShown = res.slice(0, resSlots)
  // Le shader Horizon utilisé passe en premier.
  const hShown = [...hshaders].sort((a, b) => Number(b.enabled) - Number(a.enabled)).slice(0, shSlots)
  const shShown = sh.slice(0, Math.max(0, shSlots - hShown.length))
  const hidden = total - resShown.length - shShown.length - hShown.length

  const openOn = (kind: PackKind) => { setTab(kind); if (!open) onOpen() }
  const fullScreen = () => { onClose(); navigate('/instances?content=packs-installed') }
  const current = tab === 'shader' ? sh : res

  const header = (
    <>
      <PanelTitle icon="M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2zM4 15l4-4 4 4 3-3 5 5" label={t('home.shelf.packs')} count={loading ? undefined : total} />
      <div ref={stackRef} className="flex min-w-0 flex-1 items-center">
        {loading ? (
          <span className="h-[26px] w-20 animate-pulse rounded-md bg-[rgba(255,255,255,0.05)]" />
        ) : total === 0 ? (
          <span className="truncate text-[12px] text-[rgba(255,255,255,0.35)]">{t('home.shelf.dropZip')}</span>
        ) : (
          <>
            {resShown.map((p, i) => (
              <Thumb key={p.name} instanceId={id} pack={p} first={i === 0} index={resShown.length - i} onClick={() => openOn('resourcepack')} />
            ))}
            {shShown.length + hShown.length > 0 && resShown.length > 0 && <span className="mx-2 h-5 w-px flex-shrink-0 bg-[rgba(255,255,255,0.1)]" />}
            {hShown.map((s, i) => (
              <button
                key={s.file}
                type="button"
                title={s.name}
                onClick={(e) => { e.stopPropagation(); openOn('shader') }}
                className="relative flex-shrink-0 transition-transform duration-150 ease-out hover:z-10 hover:-translate-y-0.5 active:scale-95"
                style={{ marginLeft: i === 0 ? 0 : -OVERLAP, zIndex: hShown.length + shShown.length - i }}
              >
                <HorizonIcon item={{ ...s, enabled: true }} size={THUMB} round ring />
              </button>
            ))}
            {shShown.map((p, i) => (
              <Thumb key={p.name} instanceId={id} pack={p} first={i === 0 && hShown.length === 0} index={shShown.length - i} round onClick={() => openOn('shader')} />
            ))}
            {hidden > 0 && <span className="ml-1.5 flex-shrink-0 text-[12px] font-semibold tabular-nums text-[rgba(255,255,255,0.45)]">+{hidden}</span>}
          </>
        )}
      </div>
    </>
  )

  return (
    <ShelfPanel open={open} onToggle={open ? onClose : onOpen} header={header} dropping={dropping} headerRef={headerRef}>
      <div className="flex flex-shrink-0 items-center gap-1.5 p-2.5 pb-2">
        <Chip on={tab === 'resourcepack'} onClick={() => setTab('resourcepack')}>{t('home.shelf.resourcepacks')} <b className="font-semibold">{res.length}</b></Chip>
        <Chip on={tab === 'shader'} onClick={() => setTab('shader')}>{t('home.shelf.shaders')} <b className="font-semibold">{shCount}</b></Chip>
        <button
          type="button"
          onClick={() => { onClose(); navigate(`/instances?content=${tab === 'shader' ? 'packs-shader' : 'packs-resourcepack'}`) }}
          className="ml-auto rounded-lg px-2.5 py-1.5 text-[12px] font-semibold text-[rgb(var(--accent-text))] transition-colors duration-150 hover:bg-[rgb(var(--accent)/0.12)]"
        >
          + {t('home.shelf.browse')}
        </button>
      </div>

      {/* Les pochettes en éventail : l'aperçu, avant la liste. */}
      {current.length > 0 && (
        <div className="flex flex-shrink-0 items-end px-3 pb-2 pt-1">
          {current.slice(0, 7).map((p, i) => (
            <span key={p.name} className="flex-shrink-0 transition-transform duration-150 ease-out hover:z-10 hover:-translate-y-1" style={{ marginLeft: i === 0 ? 0 : -14, zIndex: 10 - i }}>
              <Cover instanceId={id} pack={p} size={64} round={tab === 'shader'} />
            </span>
          ))}
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto px-1.5">
        {/* Shaders Horizon : un seul utilisé à la fois, choisi ici. */}
        {tab === 'shader' && hshaders.map((s) => (
          <div key={s.file} className="group flex h-10 items-center gap-2.5 rounded-lg px-2 transition-colors duration-150 hover:bg-[rgba(255,255,255,0.04)]" title={s.file}>
            <HorizonIcon item={{ ...s, enabled: true }} size={26} round />
            <span className="flex min-w-0 flex-1 items-baseline gap-2">
              <span className="truncate text-[13px] font-medium text-[rgba(255,255,255,0.9)]">{s.name}</span>
              {s.version && <span className="flex-shrink-0 text-[11.5px] text-[rgba(255,255,255,0.35)]">{s.version}</span>}
            </span>
            <DeleteButton label={t('home.shelf.delete')} confirmLabel={t('home.shelf.confirmDelete')} disabled={running} onConfirm={() => void removeHshader(s)} />
            <button
              type="button"
              disabled={running}
              onClick={() => void pickShader(s)}
              className={`h-7 flex-shrink-0 rounded-md px-2.5 text-[11px] font-bold transition-colors duration-150 disabled:opacity-50 ${
                s.enabled ? 'bg-[rgb(var(--accent)/0.3)] text-white' : 'bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.1)]'
              }`}
            >
              {s.enabled ? t('mods.horizon.inUse') : t('mods.horizon.use')}
            </button>
          </div>
        ))}
        {current.map((p) => (
          <div key={p.name} className="group flex h-10 items-center [contain-intrinsic-size:auto_40px] [content-visibility:auto] gap-2.5 rounded-lg px-2 transition-colors duration-150 hover:bg-[rgba(255,255,255,0.04)]" title={p.name}>
            <Cover instanceId={id} pack={p} size={26} round={tab === 'shader'} />
            <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-[rgba(255,255,255,0.9)]">{prettyName(p.name)}</span>
            <span className="flex-shrink-0 text-[11.5px] tabular-nums text-[rgba(255,255,255,0.35)]">{formatBytes(p.size)}</span>
            <DeleteButton label={t('home.shelf.delete')} confirmLabel={t('home.shelf.confirmDelete')} disabled={running} onConfirm={() => void remove(p)} />
          </div>
        ))}
        {!loading && current.length === 0 && !(tab === 'shader' && hshaders.length > 0) && (
          <div className="py-6 text-center text-[12.5px] text-[rgba(255,255,255,0.35)]">
            {tab === 'shader' ? t('home.shelf.noShaders') : t('home.shelf.noPacks')}
          </div>
        )}
      </div>

      <div className="flex flex-shrink-0 items-center gap-2 px-2.5 py-2">
        <span className="truncate text-[11.5px] text-[rgba(255,255,255,0.3)]">{t('home.shelf.dropZip')}</span>
        <button type="button" onClick={fullScreen} className="ml-auto flex-shrink-0 rounded-lg px-2.5 py-1.5 text-[12px] font-semibold text-[rgba(255,255,255,0.55)] transition-colors duration-150 hover:bg-[rgba(255,255,255,0.06)] hover:text-white">
          {t('home.shelf.fullScreen')} →
        </button>
      </div>
    </ShelfPanel>
  )
}

function Thumb({ instanceId, pack, first, index, round, onClick }: {
  instanceId: string; pack: PackInfo; first: boolean; index: number; round?: boolean; onClick: () => void
}) {
  return (
    <button
      type="button"
      title={prettyName(pack.name)}
      onClick={(e) => { e.stopPropagation(); onClick() }}
      className="relative flex-shrink-0 transition-transform duration-150 ease-out hover:z-10 hover:-translate-y-0.5 active:scale-95"
      style={{ marginLeft: first ? 0 : -OVERLAP, zIndex: index }}
    >
      <Cover instanceId={instanceId} pack={pack} size={THUMB} round={round} ring />
    </button>
  )
}

/** Vignette d'un pack (`pack.png`), ou son initiale sur une teinte tirée du nom. */
function Cover({ instanceId, pack, size, round, ring }: { instanceId: string; pack: PackInfo; size: number; round?: boolean; ring?: boolean }) {
  const key = `${instanceId}/${pack.kind}/${pack.name}`
  const [icon, setIcon] = useState<string | null | undefined>(() => iconCache.get(key))
  useEffect(() => {
    if (iconCache.has(key)) { setIcon(iconCache.get(key)); return }
    let cancelled = false
    queueIcon(() => api.packs.icon(instanceId, pack.kind, pack.name))
      .then((uri) => { iconCache.set(key, uri); if (!cancelled) setIcon(uri) })
      .catch(() => { iconCache.set(key, null); if (!cancelled) setIcon(null) })
    return () => { cancelled = true }
  }, [key, instanceId, pack.kind, pack.name])

  const name = prettyName(pack.name)
  return (
    <span
      className={`flex items-center justify-center overflow-hidden ${round ? 'rounded-full' : 'rounded-[7px]'} ${ring ? 'ring-2 ring-bg-secondary' : 'border border-[rgba(255,255,255,0.08)]'}`}
      style={{ width: size, height: size, background: icon ? 'rgba(255,255,255,0.06)' : tintFor(name) }}
    >
      {icon ? (
        <img src={icon} alt="" draggable={false} className="h-full w-full object-cover [image-rendering:pixelated]" />
      ) : (
        <span className="font-black text-white/75" style={{ fontSize: Math.round(size * 0.42) }}>{name.charAt(0).toUpperCase()}</span>
      )}
    </span>
  )
}
