import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { InstanceServers, SavedServer } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { InstanceIcon } from '@/components/instances/InstanceIcon'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { loaderColor } from '@/lib/loader'
import { useServerPing } from '@/lib/serverPing'
import { useT } from '@/i18n'
import { Motd, PANEL, ServerFavicon, Signal, useJoinServer } from './bits'
import { ServerForm, type ServerFormValues } from './ServerForm'

/**
 * « Mes serveurs » : la liste multijoueur de chaque instance (`servers.dat`,
 * la même que dans le jeu). À gauche les instances, à droite les serveurs de
 * celle qu'on a choisie, en cartes : rejoindre, épingler sur l'accueil,
 * modifier (avec le formulaire du jeu), retirer.
 *
 * La grille est volontairement sans animation d'entrée ni de sortie : avec
 * `AnimatePresence` et `layout`, des cartes sorties restaient à leur place,
 * invisibles, et laissaient des trous dans la grille.
 */
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

  const current = data?.find((d) => d.instance_id === instanceId) ?? data?.[0] ?? null

  // Changer d'instance referme la modification en cours.
  useEffect(() => setEditing(null), [current?.instance_id])

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
      if (favorites[current.instance_id]?.includes(editing.ip) && next !== editing.ip.toLowerCase()) {
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
        ) : current.servers.length === 0 ? (
          <Empty title={t('servers.page.mine.empty')} hint={t('servers.page.mine.emptyHint')} />
        ) : (
          <div className="min-h-0 flex-1 overflow-y-auto pr-1">
            <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] items-start gap-3 pb-2">
              {current.servers.map((s) => (
                <ServerTile key={`${current.instance_id}:${s.ip}`} instanceId={current.instance_id} server={s} onChanged={onReload} onEdit={() => setEditing(s)} />
              ))}
            </div>
          </div>
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
  onChanged,
  onEdit,
}: {
  instanceId: string
  server: SavedServer
  onChanged: () => void
  onEdit: () => void
}) {
  const t = useT()
  const join = useJoinServer()
  const { state, refresh, refreshing } = useServerPing(server.ip)
  const pinned = useStore((s) => s.favoriteServers[instanceId]?.includes(server.ip) ?? false)
  const toggleFavorite = useStore((s) => s.toggleFavoriteServer)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [busy, setBusy] = useState(false)

  const info = state.status === 'ok' ? state.info : null

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
      className={`group flex flex-col gap-3 rounded-2xl border p-3.5 transition-colors ${
        pinned ? 'border-accent/50 bg-[#17152a]' : 'border-white/[0.07] bg-[#121217] hover:border-white/[0.18]'
      }`}
    >
      <div className="flex items-start gap-3">
        <ServerFavicon ping={info} size={48} />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex items-center gap-2">
            <span className="truncate text-[14px] font-bold text-white">{server.name || t('servers.defaultName')}</span>
            {pinned && <span className="flex-shrink-0 rounded-md bg-accent/30 px-1.5 py-px text-[9.5px] font-bold text-white">{t('servers.page.mine.pinned')}</span>}
          </div>
          <span className="truncate text-[11.5px] text-txt-muted">{server.ip}</span>
        </div>
        <div className="flex flex-col items-end gap-1.5">
          <Signal state={state} />
          <button onClick={refresh} title={t('servers.refreshPing')} className="rounded-md p-0.5 text-white/30 transition hover:text-white">
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className={refreshing ? 'animate-spin' : ''}>
              <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-8 3.58-8 8s3.58 8 8 8a7.994 7.994 0 007.75-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
            </svg>
          </button>
        </div>
      </div>

      {/* Le MOTD, en couleurs, et l'état du serveur */}
      <div className="flex min-h-[54px] flex-col justify-center gap-1 rounded-xl bg-black/40 px-3 py-2">
        {info ? (
          <>
            <Motd info={info} />
            <span className="truncate text-[11px] text-txt-muted">
              {t('servers.page.players', { online: info.players_online, max: info.players_max })} · {info.version_name}
            </span>
          </>
        ) : (
          <span className={`text-[12px] ${state.status === 'offline' ? 'text-[#ff5555]' : 'text-txt-muted'}`}>
            {state.status === 'offline' ? t('servers.page.list.offline') : t('servers.page.list.pinging')}
          </span>
        )}
      </div>

      <div className="flex items-center gap-1.5">
        <Button size="sm" variant="primary" loading={busy && !confirmRemove} onClick={() => void doJoin()} className="flex-1">
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
