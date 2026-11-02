import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { open as openUrl } from '@tauri-apps/plugin-shell'
import { api } from '@/api/client'
import type { CatalogServer, CatalogServerDetail } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { RichDescription } from '@/components/ui/RichDescription'
import { FitList, type FitItem } from '@/components/ui/FitList'
import { ModalShell } from '@/components/ui/ModalShell'
import { JoinServerModal, type JoinRequest } from '@/components/servers/JoinServerModal'
import { useStore } from '@/stores/useStore'
import { showError, showNotice } from '@/stores/useErrorToast'
import { SNAP } from '@/lib/motion'
import { createInstanceFromPack } from '@/lib/modpackInstance'
import { useT } from '@/i18n'
import { Select, categoryLabel, compact, languageName, regionLabel, useJoinServer } from './bits'

/**
 * La fiche d'un serveur du catalogue, en grand par-dessus la grille.
 *
 * En haut, ce qui décide : l'image, le nom, l'adresse, les chiffres qui
 * comptent et le bouton pour jouer. Le reste est rangé dans trois onglets —
 * Description (le texte de la fiche, Markdown et HTML nettoyés), Galerie, et
 * Infos (versions, langues, catégories, liens) — au lieu de tout empiler en
 * petit dans une seule colonne.
 *
 * - Serveur **vanilla** : « Jouer » ouvre le choix d'une instance d'une
 *   version acceptée (n'importe quel loader), ou en crée une.
 * - Serveur **moddé** : son modpack exact s'installe dans une instance neuve
 *   (ou on reprend une instance qui l'a déjà), puis la partie se lance.
 * - Dans les deux cas, « Ajouter à une instance » range seulement le serveur.
 */

type Tab = 'description' | 'info'

export function CatalogDetail({ server, onClose, onAdded }: { server: CatalogServer; onClose: () => void; onAdded: () => void }) {
  const t = useT()
  const instances = useStore((s) => s.instances)
  const selectedInstanceId = useStore((s) => s.selectedInstanceId)
  const addInstance = useStore((s) => s.addInstance)
  const defaultRam = useStore((s) => s.defaultRam)
  const join = useJoinServer()

  const [detail, setDetail] = useState<CatalogServerDetail | null>(null)
  const [detailFailed, setDetailFailed] = useState(false)
  // Ouverte sur Infos : c'est là qu'est l'en-tête, donc le bouton pour jouer.
  const [tab, setTab] = useState<Tab>('info')
  const [addOpen, setAddOpen] = useState(false)
  const [target, setTarget] = useState(selectedInstanceId ?? instances[0]?.id ?? '')
  const [adding, setAdding] = useState(false)
  const [joinRequest, setJoinRequest] = useState<JoinRequest | null>(null)
  const [packInstance, setPackInstance] = useState<string | null>(null)
  const [installing, setInstalling] = useState<string | null>(null)
  const [zoomed, setZoomed] = useState<string | null>(null)
  const [moreList, setMoreList] = useState<MoreListState | null>(null)

  const modpack = server.content.kind === 'modpack' ? server.content : null

  useEffect(() => {
    let alive = true
    api.servers
      .get(server.id)
      .then((d) => alive && setDetail(d))
      .catch(() => alive && setDetailFailed(true))
    return () => {
      alive = false
    }
  }, [server.id])

  // Une instance qui a déjà ce modpack : on la propose plutôt que d'en
  // installer une deuxième.
  useEffect(() => {
    if (!modpack) return
    let alive = true
    void (async () => {
      for (const inst of instances) {
        const meta = await api.modpacks.getMeta(inst.id).catch(() => null)
        if (meta?.project_id === modpack.project_id) {
          if (alive) setPackInstance(inst.id)
          return
        }
      }
    })()
    return () => {
      alive = false
    }
  }, [modpack?.project_id])

  // Échap ferme l'image agrandie, puis la fiche.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // La fenêtre de liste se ferme d'elle-même sur Échap : la fiche reste.
      if (e.key !== 'Escape' || joinRequest || moreList) return
      if (zoomed) setZoomed(null)
      else if (!installing) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, joinRequest, zoomed, installing, moreList])

  const version = server.recommended_version ?? server.game_versions[0] ?? null
  const gallery = detail?.gallery ?? []
  const packHolder = packInstance ? instances.find((i) => i.id === packInstance) : null
  const accent = server.color ?? '#4b3fcf'

  const play = () => {
    if (!version) return
    setJoinRequest({
      ip: server.address,
      mcVersion: version,
      loader: 'vanilla',
      versions: server.game_versions,
      anyLoader: true,
      remember: server.name,
    })
  }

  const addTo = async () => {
    if (!target) return
    setAdding(true)
    try {
      const added = await api.servers.add(target, server.name, server.address)
      const name = instances.find((i) => i.id === target)?.name ?? ''
      showNotice(added ? t('servers.page.detail.added', { name }) : t('servers.page.detail.already', { name }))
      setAddOpen(false)
      onAdded()
    } catch (e) {
      showError(e)
    } finally {
      setAdding(false)
    }
  }

  const playExistingPack = async (instanceId: string) => {
    try {
      await api.servers.add(instanceId, server.name, server.address).catch(() => false)
      await join(instanceId, server.address)
    } catch (e) {
      showError(e)
    }
  }

  const installPack = async () => {
    if (!modpack) return
    setInstalling(t('servers.page.detail.preparing'))
    try {
      // Même chemin que la fenêtre « Nouvelle instance » (lib/modpackInstance.ts).
      const instance = await createInstanceFromPack({
        versionId: modpack.version_id,
        name: server.name,
        // Un modpack de serveur demande vite plus que la mémoire par défaut.
        ramMb: Math.max(defaultRam, 4096),
        author: server.author ?? '',
        onCreated: addInstance,
        onProgress: (step) => {
          if (step.kind === 'installing') {
            setInstalling(t('servers.page.detail.installingProgress', { current: step.current, total: step.total }))
          }
        },
      })
      await api.servers.add(instance.id, server.name, server.address).catch(() => false)
      onAdded()
      setInstalling(t('servers.page.detail.launching'))
      await join(instance.id, server.address)
    } catch (e) {
      showError(e)
      setInstalling(null)
    }
  }

  const tabs: { key: Tab; label: string }[] = [
    { key: 'description', label: t('servers.page.detail.tabs.description') },
    { key: 'info', label: t('servers.page.detail.tabs.info') },
  ]

  return (
    <>
      <motion.div
        className="absolute inset-0 z-30 flex justify-center rounded-2xl bg-black/60 p-2 backdrop-blur-[3px]"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onMouseDown={(e) => e.target === e.currentTarget && !installing && onClose()}
      >
        <motion.article
          className="flex h-full w-full max-w-5xl flex-col overflow-hidden rounded-2xl border border-white/[0.08] bg-[#111116] shadow-2xl"
          initial={{ y: 16, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 16, opacity: 0 }}
          transition={{ type: 'spring', stiffness: 420, damping: 38 }}
        >
          {/* ── En-tête : image, identité, action — seulement sur Infos ; la
              description a toute la hauteur pour elle ── */}
          {tab === 'info' && (
          <header className="relative flex-shrink-0">
            <div className="relative h-[140px] overflow-hidden" style={{ background: `linear-gradient(135deg, ${accent}66, ${accent}10)` }}>
              {server.banner_url && <img src={server.banner_url} alt="" className="h-full w-full object-cover" draggable={false} />}
              <div className="absolute inset-0 bg-gradient-to-t from-[#111116] via-[#111116]/40 to-black/10" />
              <button
                onClick={onClose}
                className="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-xl bg-black/55 text-white/80 backdrop-blur transition hover:text-white"
                title={t('servers.page.detail.close')}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} width={15} height={15}>
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>

            <div className="relative -mt-16 flex items-end gap-5 px-7">
              <div className="h-[104px] w-[104px] flex-shrink-0 overflow-hidden rounded-[22px] border-4 border-[#111116] bg-surface-3 shadow-xl">
                {server.icon_url && <img src={server.icon_url} alt="" className="h-full w-full object-cover" draggable={false} />}
              </div>
              <div className="flex min-w-0 flex-1 flex-col gap-1.5 pb-1">
                <div className="flex items-center gap-2.5">
                  <h2 className="truncate text-[26px] font-black leading-none text-white">{server.name}</h2>
                  {modpack && <span className="flex-shrink-0 rounded-lg bg-[#f59e0b]/20 px-2 py-0.5 text-[11px] font-bold text-[#fbbf24]">{t('servers.page.catalog.modded')}</span>}
                </div>
                <button
                  onClick={() => {
                    void navigator.clipboard.writeText(server.address)
                    showNotice(t('servers.page.detail.copied'))
                  }}
                  className="flex items-center gap-1.5 self-start rounded-lg bg-white/[0.06] px-2.5 py-1 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:bg-white/[0.1] hover:text-white"
                  title={t('servers.page.detail.copy')}
                >
                  {server.address}
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} width={12} height={12}>
                    <rect x="9" y="9" width="11" height="11" rx="2" />
                    <path d="M5 15V5a2 2 0 012-2h10" />
                  </svg>
                </button>
              </div>

              {/* L'action principale, toujours à portée */}
              <div className="flex flex-shrink-0 flex-col items-end gap-2 pb-1">
                {installing ? (
                  <div className="flex h-11 items-center gap-2.5 rounded-xl bg-accent/20 px-4 text-[13px] font-semibold text-white">
                    <ButtonSpinner size={15} />
                    {installing}
                  </div>
                ) : (
                  <div className="flex gap-2">
                    <Button variant="secondary" onClick={() => setAddOpen((o) => !o)}>
                      {t('servers.page.detail.addToInstance')}
                    </Button>
                    {modpack ? (
                      packHolder ? (
                        <Button variant="primary" onClick={() => void playExistingPack(packHolder.id)}>
                          {t('servers.page.detail.playWith', { name: packHolder.name })}
                        </Button>
                      ) : (
                        <Button variant="primary" onClick={() => void installPack()}>
                          {t('servers.page.detail.install')}
                        </Button>
                      )
                    ) : (
                      <Button variant="primary" disabled={!version} onClick={play}>
                        {t('servers.page.detail.play')}
                      </Button>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* Ajouter à une instance, déplié sous l'en-tête */}
            <AnimatePresence>
              {addOpen && !installing && (
                <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
                  <div className="mx-7 mt-4 flex items-center gap-3 rounded-xl border border-white/[0.08] bg-white/[0.03] p-3">
                    <span className="flex-shrink-0 text-[12.5px] text-txt-secondary">{t('servers.page.detail.addTo')}</span>
                    {instances.length === 0 ? (
                      <span className="text-[12.5px] text-txt-muted">{t('servers.page.mine.noInstance')}</span>
                    ) : (
                      <>
                        <Select
                          value={target}
                          onChange={setTarget}
                          className="min-w-0 flex-1"
                          options={instances.map((i) => ({ value: i.id, label: `${i.name} · ${i.loader} ${i.mc_version}` }))}
                        />
                        <Button size="sm" variant="primary" loading={adding} onClick={() => void addTo()}>
                          {t('servers.page.detail.addButton')}
                        </Button>
                      </>
                    )}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>

          </header>
          )}

          {/* ── Deux onglets : la description (et sa galerie), puis les
              informations du serveur. Sans l'en-tête, la barre porte la
              fermeture. ── */}
          <nav className={`flex flex-shrink-0 items-center gap-1 border-b border-white/[0.07] px-6 ${tab === 'info' ? 'mt-4' : 'pt-3'}`}>
            {tabs.map((tb) => (
              <button
                key={tb.key}
                onClick={() => setTab(tb.key)}
                className={`relative px-4 pb-3 pt-1 text-[13.5px] font-bold transition-colors ${tab === tb.key ? 'text-white' : 'text-txt-muted hover:text-txt-secondary'}`}
              >
                {tb.label}
                {tab === tb.key && <motion.span layoutId="detail-tab" transition={SNAP} className="absolute inset-x-2 -bottom-px h-[2px] rounded-full bg-accent" />}
              </button>
            ))}
            {tab === 'description' && (
              <button
                onClick={onClose}
                className="mb-2 ml-auto flex h-8 w-8 items-center justify-center rounded-lg text-white/60 transition hover:bg-white/[0.08] hover:text-white"
                title={t('servers.page.detail.close')}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} width={14} height={14}>
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            )}
          </nav>

          {/* La description défile ; les infos tiennent dans l'espace qui
              reste, sans barre de défilement. */}
          <div className={`min-h-0 flex-1 px-7 ${tab === 'description' ? 'overflow-y-auto py-6' : 'overflow-hidden py-5'}`}>
            {tab === 'description' ? (
              <div className="mx-auto flex max-w-3xl flex-col gap-8">
                {detail ? (
                  detail.description.trim() ? (
                    <RichDescription source={detail.description} />
                  ) : (
                    <p className="text-[13px] text-txt-secondary">{server.summary}</p>
                  )
                ) : detailFailed ? (
                  <p className="text-[13px] text-txt-secondary">{server.summary}</p>
                ) : (
                  <Loading />
                )}

                {gallery.length > 0 && (
                  <section className="flex flex-col gap-3 border-t border-white/[0.07] pt-6">
                    <h3 className="text-[12px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('servers.page.detail.tabs.gallery')}</h3>
                    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                      {gallery.map((g) => (
                        <button key={g} onClick={() => setZoomed(g)} className="overflow-hidden rounded-xl border border-white/[0.07] transition hover:border-white/25">
                          <img src={g} alt="" loading="lazy" draggable={false} className="aspect-video w-full object-cover transition-transform duration-300 hover:scale-[1.03]" />
                        </button>
                      ))}
                    </div>
                  </section>
                )}
              </div>
            ) : (
              // Toute la hauteur sous l'en-tête est occupée, sans défilement :
              // une rangée de gros chiffres, puis trois panneaux étirés jusqu'en
              // bas. Ce qui ne tient pas est résumé (« +N », avec la liste au
              // survol).
              <div className="flex h-full flex-col gap-4 overflow-hidden">
                <div className="grid flex-shrink-0 grid-cols-4 gap-3">
                  <Stat label={t('servers.page.detail.online')} value={server.ping ? compact(server.ping.players_online) : '—'} live={!!server.ping} />
                  <Stat label={t('servers.page.detail.plays')} value={server.plays_2w != null ? compact(server.plays_2w) : '—'} />
                  <Stat
                    label={modpack ? t('servers.page.detail.modpack') : t('servers.page.detail.version')}
                    value={modpack ? modpack.name ?? server.name : version ?? '—'}
                  />
                  <Stat label={t('servers.page.detail.region')} value={server.region ? regionLabel(server.region) : '—'} />
                </div>

                <div className="grid min-h-0 flex-1 grid-cols-3 gap-3">
                  {/* Chaque liste n'affiche que ce qui tient dans son panneau
                      (`FitList`) : le reste devient « +N ». */}
                  <Panel>
                    <InfoBlock title={t('servers.page.detail.versionList')} fill>
                      {server.game_versions.length > 0 ? (
                        <MoreList
                          title={t('servers.page.detail.versionList')}
                          onMore={setMoreList}
                          items={[...server.game_versions].sort(compareVersions).map((v) => ({
                            key: v,
                            label: v,
                            node: <Pill strong={v === server.recommended_version}>{v}</Pill>,
                          }))}
                        />
                      ) : (
                        <span className="text-[13px] text-txt-muted">{modpack ? t('servers.page.detail.versionsFromPack') : '—'}</span>
                      )}
                    </InfoBlock>
                  </Panel>

                  {/* « Publié par » d'abord : une ligne fixe en haut, la liste
                      des langues prend ce qui reste, sans trou au milieu. */}
                  <Panel>
                    {server.author && (
                      <InfoBlock title={t('servers.page.detail.owner')}>
                        <span className="truncate text-[14px] font-semibold text-white">{server.author}</span>
                      </InfoBlock>
                    )}
                    <InfoBlock title={t('servers.page.detail.languages')} fill>
                      {server.languages.length > 0 ? (
                        <MoreList
                          title={t('servers.page.detail.languages')}
                          onMore={setMoreList}
                          items={server.languages.map((l) => ({ key: l, label: languageName(l), node: <Pill>{languageName(l)}</Pill> }))}
                        />
                      ) : (
                        <span className="text-[13px] text-txt-muted">—</span>
                      )}
                    </InfoBlock>
                  </Panel>

                  <Panel>
                    {server.categories.length > 0 && (
                      <InfoBlock title={t('servers.page.detail.categories')} fill>
                        <MoreList
                          title={t('servers.page.detail.categories')}
                          onMore={setMoreList}
                          items={server.categories.map((c) => ({ key: c, label: categoryLabel(c), node: <Pill>{categoryLabel(c)}</Pill> }))}
                        />
                      </InfoBlock>
                    )}
                    <InfoBlock title={t('servers.page.detail.links')} fill>
                      <MoreList
                        title={t('servers.page.detail.links')}
                        onMore={setMoreList}
                        items={[
                          ...(detail?.links ?? []).map((l) => ({ key: l.url, url: l.url, label: l.platform || l.url })),
                          { key: 'modrinth', url: `https://modrinth.com/server/${server.slug || server.id}`, label: 'Modrinth' },
                        ].map((l) => ({
                          key: l.key,
                          label: l.label,
                          node: <LinkButton onClick={() => void openUrl(l.url)}>{l.label}</LinkButton>,
                        }))}
                      />
                    </InfoBlock>
                  </Panel>
                </div>
              </div>
            )}
          </div>
        </motion.article>

        {/* Image agrandie */}
        <AnimatePresence>
          {zoomed && (
            <motion.div
              className="absolute inset-0 z-10 flex items-center justify-center rounded-2xl bg-black/85 p-8"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setZoomed(null)}
            >
              <img src={zoomed} alt="" className="max-h-full max-w-full rounded-xl object-contain" draggable={false} />
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>

      {/* La liste complète derrière un « +N » */}
      {moreList && (
        <ModalShell title={moreList.title} onClose={() => setMoreList(null)} maxWidth="max-w-xl" closeOnBackdrop>
          <div className="flex max-h-[60vh] flex-wrap content-start gap-2 overflow-y-auto pr-1">
            {moreList.items.map((i) => (
              <span key={i.key} className="flex">
                {i.node}
              </span>
            ))}
          </div>
        </ModalShell>
      )}

      {joinRequest && (
        <JoinServerModal
          request={joinRequest}
          onClose={() => {
            setJoinRequest(null)
            onAdded()
          }}
        />
      )}
    </>
  )
}

function Loading() {
  return (
    <div className="flex justify-center py-10">
      <ButtonSpinner size={24} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
    </div>
  )
}

function Stat({ label, value, live }: { label: string; value: string; live?: boolean }) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-2xl border border-white/[0.07] bg-white/[0.03] px-4 py-3.5" title={value}>
      <span className="text-[11.5px] font-semibold text-txt-muted">{label}</span>
      <span className="flex min-w-0 items-center gap-2 text-[21px] font-black leading-tight text-white">
        {live && <span className="h-2.5 w-2.5 flex-shrink-0 rounded-full bg-[#4ade80] shadow-[0_0_8px_#4ade80]" />}
        <span className="truncate">{value}</span>
      </span>
    </div>
  )
}

/** Un panneau de la grille des infos, étiré sur toute la hauteur restante. */
function Panel({ children }: { children: React.ReactNode }) {
  return <div className="flex min-h-0 min-w-0 flex-col gap-5 overflow-hidden rounded-2xl border border-white/[0.07] bg-white/[0.02] p-4">{children}</div>
}

function LinkButton({ onClick, children }: { onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className="flex items-center gap-1.5 rounded-lg border border-white/[0.1] px-3 py-1.5 text-[13px] font-semibold capitalize text-txt-secondary transition-colors hover:border-white/25 hover:text-white"
    >
      {children}
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} width={11} height={11} className="opacity-60">
        <path d="M7 17L17 7M9 7h8v8" />
      </svg>
    </button>
  )
}

/** `fill` : le bloc prend la hauteur qui reste dans son panneau, pour que la
 *  liste qu'il contient sache combien d'éléments elle peut montrer. */
function InfoBlock({ title, children, fill }: { title: string; children: React.ReactNode; fill?: boolean }) {
  return (
    <section className={`flex min-w-0 flex-col gap-2.5 ${fill ? 'min-h-0 flex-1' : 'flex-shrink-0'}`}>
      <h3 className="text-[11.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{title}</h3>
      {children}
    </section>
  )
}

function Pill({ children, strong, title }: { children: React.ReactNode; strong?: boolean; title?: string }) {
  return (
    <span title={title} className={`rounded-lg px-2.5 py-1 text-[13px] font-semibold ${strong ? 'bg-accent/30 text-white' : 'bg-white/[0.06] text-txt-secondary'}`}>
      {children}
    </span>
  )
}

type MoreListState = { title: string; items: FitItem[] }

/** Une liste qui n'affiche que ce qui tient dans son bloc ; le « +N » est un
 *  bouton qui ouvre la liste complète dans une fenêtre. */
function MoreList({ title, items, onMore }: { title: string; items: FitItem[]; onMore: (list: MoreListState) => void }) {
  return (
    <FitList
      className="flex-1"
      items={items}
      renderMore={(n, names) => (
        <button
          onClick={() => onMore({ title, items })}
          title={names}
          className="rounded-lg border border-accent/40 bg-accent/15 px-2.5 py-1 text-[13px] font-bold text-white transition-colors hover:bg-accent/30"
        >
          +{n}
        </button>
      )}
    />
  )
}

/** Versions de la plus récente à la plus ancienne (1.21.11 avant 1.21.9). */
function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pb[i] ?? 0) - (pa[i] ?? 0)
    if (d) return d
  }
  return 0
}
