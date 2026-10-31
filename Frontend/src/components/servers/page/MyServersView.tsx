import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { InstanceServers, SavedServer } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { Pagination, pageCountFor } from '@/components/ui/Pagination'
import { InstanceIcon } from '@/components/instances/InstanceIcon'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { loaderColor } from '@/lib/loader'
import { useServerPings, type PingState } from '@/lib/serverPing'
import { useT } from '@/i18n'
import { Motd, PANEL, ServerFavicon, Signal, useJoinServer } from './bits'
import { ServerForm, type ServerFormValues } from './ServerForm'

/**
 * « Mes serveurs » : la liste multijoueur de chaque instance (`servers.dat`,
 * la même que dans le jeu). À gauche les instances, à droite les serveurs de
 * celle qu'on a choisie, en cartes et **par pages** : la grille ne défile
 * pas, elle montre ce qui tient et on change de page, comme le catalogue.
 *
 * Ordre : les serveurs épinglés sur l'accueil, puis ceux qui répondent, puis
 * ceux qui ne répondent pas (ceux dont le ping est en cours restent entre les
 * deux, pour ne pas sauter en bas puis remonter). Tous sont pingués ici, pas
 * seulement ceux de la page affichée : c'est ce qui permet de trier.
 *
 * Grille sans animation d'entrée ni de sortie : avec `AnimatePresence` et
 * `layout`, des cartes sorties restaient invisibles à leur place.
 */

/** Gabarit d'une carte, comme dans le catalogue : au moins deux rangées tant
 *  que les cartes ne descendent pas sous `TILE_MIN_H`. */
const TILE_MIN_W = 280
const TILE_H = 196
const TILE_MIN_H = 160
const GAP = 12

function rowsFor(height: number): { rows: number; tileH: number } {
  let rows = Math.floor((height + GAP) / (TILE_H + GAP))
  if (rows < 2) rows = Math.floor((height + GAP) / (TILE_MIN_H + GAP))
  rows = Math.max(1, rows)
  return { rows, tileH: Math.floor((height - GAP * (rows - 1)) / rows) }
}

/** Rang de tri : épinglé, en ligne, en attente, hors ligne. */
function rank(pinned: boolean, state: PingState): number {
  if (pinned) return 0
  return state.status === 'ok' ? 1 : state.status === 'loading' ? 2 : 3
}

export function MyServersView({
  data,
  loading,
  instanceId,
  onPick,
  onReload,
  onAdd,
  onCatalog,
}: {
  data: InstanceServers[] | null
  loading: boolean
  instanceId: string | null
  onPick: (id: string) => void
  onReload: () => void
  onAdd: () => void
  onCatalog: () => void
}) {
  const t = useT()
  const instances = useStore((s) => s.instances)
  const toggleFavorite = useStore((s) => s.toggleFavoriteServer)
  const favorites = useStore((s) => s.favoriteServers)
  const [editing, setEditing] = useState<SavedServer | null>(null)
  const [saving, setSaving] = useState(false)
  const [page, setPage] = useState(1)
  const [layout, setLayout] = useState({ cols: 1, rows: 1, tileH: TILE_H })
  const gridRef = useRef<HTMLDivElement>(null)

  const current = data?.find((d) => d.instance_id === instanceId) ?? data?.[0] ?? null
  const servers = current?.servers ?? []
  const { stateOf, refresh } = useServerPings(servers.map((s) => s.ip))
  const pinnedIps = (current && favorites[current.instance_id]) ?? []
  // « Sur l'accueil » suit la règle de l'accueil lui-même (Home.tsx) : avec
  // 3 serveurs ou moins, il les montre tous ; au-delà, seulement les épinglés.
  const onHome = (ip: string) =>
    servers.length <= 3 || pinnedIps.some((p) => p.toLowerCase() === ip.toLowerCase())

  const sorted = useMemo(
    () =>
      servers
        .map((s, i) => ({ s, i, r: rank(onHome(s.ip), stateOf(s.ip)) }))
        // À rang égal, l'ordre de la liste du jeu.
        .sort((a, b) => a.r - b.r || a.i - b.i)
        // `i` (position dans servers.dat) sert de clé : la même adresse peut
        // être deux fois dans la liste du jeu, et deux cartes de même clé font
        // perdre le fil à React — une carte restait affichée d'une page et
        // d'une instance à l'autre.
        .map((x) => ({ ...x.s, slot: x.i })),
    [servers, pinnedIps, stateOf],
  )

  const perPage = Math.max(1, layout.cols * layout.rows)
  const pageCount = pageCountFor(sorted.length, perPage)
  const shown = sorted.slice((page - 1) * perPage, page * perPage)

  // Changer d'instance referme la modification et revient à la première page.
  useEffect(() => {
    setEditing(null)
    setPage(1)
  }, [current?.instance_id])
  // Une page qui n'existe plus (serveur retiré, fenêtre agrandie) : la dernière.
  useEffect(() => {
    if (page > pageCount) setPage(pageCount)
  }, [page, pageCount])

  // La grille mesure sa place, comme le catalogue.
  useEffect(() => {
    const box = gridRef.current
    if (!box) return
    const measure = () => {
      if (box.clientWidth === 0 || box.clientHeight === 0) return
      const cols = Math.max(1, Math.floor((box.clientWidth + GAP) / (TILE_MIN_W + GAP)))
      const { rows, tileH } = rowsFor(box.clientHeight)
      setLayout((l) => (l.cols === cols && l.rows === rows && l.tileH === tileH ? l : { cols, rows, tileH }))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(box)
    return () => observer.disconnect()
  }, [editing, current?.instance_id, servers.length === 0])

  if (loading && !data) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <ButtonSpinner size={30} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
      </div>
    )
  }
  if (!data || !current) {
    return <div className="flex flex-1 items-center justify-center text-[15px] font-bold text-white">{t('servers.page.mine.noInstance')}</div>
  }

  const saveEdit = async (v: ServerFormValues) => {
    if (!editing) return
    setSaving(true)
    try {
      await api.servers.edit(current.instance_id, editing.ip, v.name, v.address, v.resourcePacks)
      const next = v.address.trim().toLowerCase()
      // L'épingle de l'accueil suit le serveur quand son adresse change.
      if (pinnedIps.includes(editing.ip) && next !== editing.ip.toLowerCase()) {
        toggleFavorite(current.instance_id, editing.ip)
        toggleFavorite(current.instance_id, next)
      }
      setEditing(null)
      onReload()
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  const onlineCount = servers.filter((s) => stateOf(s.ip).status === 'ok').length

  return (
    <div className="flex min-h-0 w-full min-w-0 flex-1 gap-4">
      {/* Les instances */}
      <aside className={`flex w-[clamp(240px,27%,320px)] flex-shrink-0 flex-col overflow-hidden rounded-2xl ${PANEL}`}>
        <p className="px-4 pb-2 pt-4 text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('servers.page.mine.instances')}</p>
        <div className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-2 pb-2">
          {data.map((d) => {
            const active = d.instance_id === current.instance_id
            const inst = instances.find((i) => i.id === d.instance_id)
            return (
              <button
                key={d.instance_id}
                onClick={() => onPick(d.instance_id)}
                className={`flex items-center gap-3 rounded-xl border px-2.5 py-2 text-left transition-colors ${
                  active ? 'border-accent/60 bg-accent/20' : 'border-transparent hover:bg-white/[0.05]'
                }`}
              >
                {inst && <InstanceIcon instance={inst} size={38} />}
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-[13px] font-bold text-white">{d.instance_name}</span>
                  <span className="flex items-center gap-1 text-[11px] text-txt-muted">
                    <span style={{ color: loaderColor(d.loader) }}>{d.loader}</span>
                    <span>·</span>
                    <span>{d.mc_version}</span>
                  </span>
                </span>
                <span className="rounded-md bg-white/[0.07] px-1.5 py-0.5 text-[11px] font-bold tabular-nums text-txt-secondary">{d.servers.length}</span>
              </button>
            )
          })}
        </div>
      </aside>

      {/* Les serveurs de l'instance choisie */}
      <section className="flex min-w-0 flex-1 flex-col gap-3">
        <div className={`flex flex-wrap items-center gap-3 rounded-2xl px-4 py-3 ${PANEL}`}>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-[16px] font-black text-white">{current.instance_name}</h2>
            <p className="text-[11px] text-txt-muted">{t('servers.page.mine.sameAsGame')}</p>
          </div>
          <Button size="sm" variant="secondary" onClick={onCatalog}>
            {t('servers.page.mine.browse')}
          </Button>
          <Button size="sm" variant="primary" onClick={onAdd}>
            {t('servers.page.mine.add')}
          </Button>
        </div>

        {editing ? (
          <div className={`flex min-h-0 flex-1 justify-center overflow-y-auto rounded-2xl px-5 py-6 ${PANEL}`}>
            <div className="w-full max-w-md">
              <ServerForm
                title={t('servers.page.form.editTitle')}
                initial={{ name: editing.name, address: editing.ip, resourcePacks: editing.resource_packs ?? 'prompt' }}
                submitLabel={t('servers.page.form.done')}
                busy={saving}
                onSubmit={(v) => void saveEdit(v)}
                onCancel={() => setEditing(null)}
              />
            </div>
          </div>
        ) : current.error ? (
          <Empty title={t('servers.page.mine.unreadable')} hint={current.error} />
        ) : servers.length === 0 ? (
          <Empty title={t('servers.page.mine.empty')} hint={t('servers.page.mine.emptyHint')} />
        ) : (
          <>
            {/* La grille ne défile pas : ce qui tient, puis les pages. */}
            <div ref={gridRef} className="min-h-0 flex-1 overflow-hidden">
              <div
                className="grid content-start"
                style={{ gridTemplateColumns: `repeat(${layout.cols}, minmax(0, 1fr))`, gridAutoRows: layout.tileH, gap: GAP }}
              >
                {shown.map((s) => (
                  <ServerTile
                    key={`${current.instance_id}:${s.slot}:${s.ip}`}
                    instanceId={current.instance_id}
                    server={s}
                    state={stateOf(s.ip)}
                    onHome={onHome(s.ip)}
                    onRefresh={() => refresh(s.ip)}
                    onChanged={onReload}
                    onEdit={() => setEditing(s)}
                  />
                ))}
              </div>
            </div>

            <div className={`flex items-center justify-between gap-3 rounded-xl px-3 py-1.5 ${PANEL}`}>
              <span className="flex items-center gap-2 text-[11px] text-txt-secondary">
                {t('servers.page.list.count', { count: servers.length })}
                <span className="text-white/20">·</span>
                <span className="h-1.5 w-1.5 rounded-full bg-[#4ade80]" />
                {t('servers.page.list.onlineCount', { count: onlineCount })}
                <button onClick={() => refresh()} className="ml-1 font-semibold text-accent-text transition-colors hover:text-white">
                  {t('servers.page.list.refresh')}
                </button>
              </span>
              <Pagination page={page} pageCount={pageCount} onChange={setPage} />
            </div>
          </>
        )}
      </section>
    </div>
  )
}

function Empty({ title, hint }: { title: string; hint: string }) {
  return (
    <div className={`flex flex-1 flex-col items-center justify-center gap-1.5 rounded-2xl px-6 text-center ${PANEL}`}>
      <p className="text-[14px] font-bold text-white">{title}</p>
      <p className="max-w-md text-[12px] leading-relaxed text-txt-muted">{hint}</p>
    </div>
  )
}

function ServerTile({
  instanceId,
  server,
  state,
  onHome,
  onRefresh,
  onChanged,
  onEdit,
}: {
  instanceId: string
  server: SavedServer
  state: PingState
  /** Affiché sur l'accueil (épinglé, ou instance de 3 serveurs au plus). */
  onHome: boolean
  onRefresh: () => void
  onChanged: () => void
  onEdit: () => void
}) {
  const t = useT()
  const join = useJoinServer()
  const pinned = useStore((s) => s.favoriteServers[instanceId]?.includes(server.ip) ?? false)
  const toggleFavorite = useStore((s) => s.toggleFavoriteServer)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [busy, setBusy] = useState(false)

  const info = state.status === 'ok' ? state.info : null
  const offline = state.status === 'offline'

  const doJoin = async () => {
    setBusy(true)
    try {
      await join(instanceId, server.ip)
    } catch (e) {
      showError(e)
      setBusy(false)
    }
  }

  const remove = async () => {
    setBusy(true)
    try {
      await api.servers.remove(instanceId, server.ip)
      if (pinned) toggleFavorite(instanceId, server.ip)
      onChanged()
    } catch (e) {
      showError(e)
      setBusy(false)
    }
  }

  const pin = () => {
    if (!toggleFavorite(instanceId, server.ip)) showNotice(t('home.maxPinnedServers'))
  }

  return (
    <div
      className={`group flex h-full min-h-0 flex-col gap-2.5 overflow-hidden rounded-2xl border p-3.5 transition-[border-color,background-color,opacity] ${
        onHome ? 'border-accent/50 bg-[#17152a]' : 'border-white/[0.07] bg-[#121217] hover:border-white/[0.18]'
      } ${offline ? 'opacity-70 hover:opacity-100' : ''}`}
    >
      <div className="flex flex-shrink-0 items-start gap-3">
        {/* Hors ligne : l'icône passe en gris, comme éteinte. */}
        <div className={offline ? 'opacity-50 grayscale' : ''}>
          <ServerFavicon ping={info} size={48} />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex items-center gap-2">
            <span className={`truncate text-[14px] font-bold ${offline ? 'text-white/70' : 'text-white'}`}>{server.name || t('servers.defaultName')}</span>
            {onHome && <span className="flex-shrink-0 rounded-md bg-accent/30 px-1.5 py-px text-[9.5px] font-bold text-white">{t('servers.page.mine.pinned')}</span>}
          </div>
          <span className="truncate text-[11.5px] text-txt-muted">{server.ip}</span>
        </div>
        <div className="flex flex-col items-end gap-1.5">
          {offline ? (
            <span className="flex items-center gap-1.5 rounded-full bg-white/[0.06] px-2 py-0.5 text-[10.5px] font-semibold text-txt-muted">
              <span className="h-1.5 w-1.5 rounded-full bg-white/30" />
              {t('servers.page.list.offlineBadge')}
            </span>
          ) : (
            <Signal state={state} />
          )}
          <button onClick={onRefresh} title={t('servers.refreshPing')} className="rounded-md p-0.5 text-white/30 transition hover:text-white">
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className={state.status === 'loading' ? 'animate-spin' : ''}>
              <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-8 3.58-8 8s3.58 8 8 8a7.994 7.994 0 007.75-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
            </svg>
          </button>
        </div>
      </div>

      {/* Le MOTD en couleurs ; hors ligne, une phrase sobre à la place. Le
          bloc absorbe la hauteur variable de la carte. */}
      <div className="flex min-h-0 flex-1 flex-col justify-center gap-1 overflow-hidden rounded-xl bg-black/40 px-3 py-2">
        {info ? (
          <>
            <Motd info={info} />
            <span className="truncate text-[11px] text-txt-muted">
              {t('servers.page.players', { online: info.players_online, max: info.players_max })} · {info.version_name}
            </span>
          </>
        ) : offline ? (
          <span className="text-[12px] text-txt-muted">{t('servers.page.list.offlineHint')}</span>
        ) : (
          <span className="text-[12px] text-txt-muted">{t('servers.page.list.pinging')}</span>
        )}
      </div>

      <div className="flex flex-shrink-0 items-center gap-1.5">
        <Button size="sm" variant={offline ? 'secondary' : 'primary'} loading={busy && !confirmRemove} onClick={() => void doJoin()} className="flex-1">
          {t('servers.page.mine.join')}
        </Button>
        <IconAction title={pinned ? t('servers.removeFromHome') : t('servers.page.list.pin')} active={pinned} onClick={pin}>
          <path d="M16 3l5 5-3 1-4 4 1 5-2 2-4-4-5 5-1-1 5-5-4-4 2-2 5 1 4-4z" />
        </IconAction>
        <IconAction title={t('servers.page.mine.edit')} onClick={onEdit}>
          <path d="M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4" />
        </IconAction>
        <button
          onClick={() => (confirmRemove ? void remove() : setConfirmRemove(true))}
          onMouseLeave={() => setConfirmRemove(false)}
          title={t('servers.page.mine.remove')}
          className={`flex h-8 items-center justify-center rounded-lg border text-[11.5px] font-semibold transition-colors ${
            confirmRemove ? 'border-danger/60 bg-danger/15 px-3 text-danger' : 'w-8 border-white/[0.1] text-txt-muted hover:border-danger/40 hover:text-danger'
          }`}
        >
          {confirmRemove ? (
            t('servers.page.mine.removeConfirm')
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
              <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
            </svg>
          )}
        </button>
      </div>
    </div>
  )
}

function IconAction({ title, active, onClick, children }: { title: string; active?: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`flex h-8 w-8 items-center justify-center rounded-lg border transition-colors ${
        active ? 'border-accent/60 bg-accent/25 text-white' : 'border-white/[0.1] text-txt-muted hover:border-white/25 hover:text-white'
      }`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
        {children}
      </svg>
    </button>
  )
}
