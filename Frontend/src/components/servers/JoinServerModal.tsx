import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { FriendAvatar } from '@/components/friends/FriendBits'
import { loaderColor, showsRam } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { errorMessage } from '@/lib/apiError'
import { skinOf } from '@/lib/friends'
import { useStore, useStorePick } from '@/stores/useStore'
import { useFriends } from '@/stores/useFriends'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import type { LookupResult } from '@/types/friends'
import type { Instance } from '@/types'
import { withoutDefaultPort } from '@/lib/serverAddress'
import { compareVersions, probeServer, probeVersion, type ServerProbeInfo, type VersionVerdict } from '@/lib/serverProbe'

export interface JoinRequest {
  ip: string
  mcVersion: string
  loader: string
  /** L'ami qui invite (invitation reçue), pour le titre. */
  inviter?: string
  /** Ticket du joueur qu'on rejoint par son statut Discord : de quoi l'ajouter
   *  en ami une fois la partie lancée (`/friends/join-ticket/lookup`). */
  host?: string
  /** Toutes les versions que le serveur accepte (catalogue). `mcVersion`
   *  reste celle d'une instance créée. */
  versions?: string[]
  /** Serveur vanilla : n'importe quel loader fait l'affaire (un Fabric avec
   *  Sodium rejoint très bien), et une instance créée est vanilla. */
  anyLoader?: boolean
  /** Nom sous lequel ranger le serveur dans la liste de l'instance avant de
   *  la lancer (catalogue) : il reste ensuite dans « Mes serveurs ». */
  remember?: string
}

/**
 * « Rejoindre un ami » : une invitation acceptée, un message privé Discord,
 * ou le bouton « Rejoindre » du statut Discord de quelqu'un. Lance le jeu
 * directement sur le serveur ; sans instance qui convienne, en crée une
 * (version de l'ami, mémoire par défaut) et la lance.
 *
 * **Quelles instances (2026-11-10)** : on ne s'arrête plus à la version et au
 * loader de l'ami. Le serveur est interrogé (`lib/serverProbe.ts` →
 * `servers/probe.rs`) : exige-t-il Forge ou NeoForge (sinon n'importe quel
 * loader entre — on joue en Fabric sur un serveur vanilla), puis, une
 * version à la fois, accepte-t-il chacune des versions de mes instances.
 * Chaque instance porte son verdict au fil des réponses : celle de l'ami
 * d'abord, puis acceptées, en cours, « à essayer » (le serveur ne dit rien),
 * et refusées, grisées. Serveur injoignable : comme avant, même version et
 * même loader. Les versions du catalogue (`versions`, fiche Modrinth) sont
 * acceptées d'office et les autres demandées au serveur : la fiche est
 * souvent en retard sur le serveur (Hypixel sans 26.3, donc sans Horizon).
 *
 * L'adresse est rejointe **sans le port par défaut** (`withoutDefaultPort`) :
 * écrit, il empêcherait le jeu de suivre l'enregistrement SRV du serveur.
 *
 * Venu du statut Discord d'un joueur qui n'est pas encore un ami, la fenêtre
 * reste ouverte une fois le jeu lancé pour proposer de l'ajouter d'un clic.
 */
export function JoinServerModal({ request, onClose }: { request: JoinRequest; onClose: () => void }) {
  const t = useT()
  const navigate = useNavigate()
  const { instances, avoidBetaDependencies, showConsole, setSelectedInstanceId, setInstanceRunning, isAgentEnabled, addInstance, defaultRam } =
    useStorePick('instances', 'avoidBetaDependencies', 'showConsole', 'setSelectedInstanceId', 'setInstanceRunning', 'isAgentEnabled', 'addInstance', 'defaultRam', 'agentOptOut')
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const refreshFriends = useFriends((s) => s.refresh)
  const [launchingId, setLaunchingId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [host, setHost] = useState<LookupResult | null>(null)
  const [hostDone, setHostDone] = useState<string | null>(null)
  const [step, setStep] = useState<'pick' | 'host'>('pick')
  const [busy, setBusy] = useState(false)

  const ip = withoutDefaultPort(request.ip)
  // Les versions que le catalogue (Modrinth) annonce pour ce serveur :
  // acceptées d'office. Sa fiche est souvent en retard (Hypixel n'y a pas
  // 26.3), donc les autres versions de mes instances sont quand même
  // demandées au serveur, comme pour un ami.
  const listed = request.versions ?? []
  const fromCatalog = listed.length > 0
  const [info, setInfo] = useState<ServerProbeInfo | null>(null)
  const [verdicts, setVerdicts] = useState<Record<string, VersionVerdict>>({})

  // Le loader exigé : aucun pour un serveur du catalogue marqué vanilla ;
  // sinon celui du serveur s'il est moddé, aucun s'il ne l'est pas, celui de
  // l'ami tant qu'on ne peut pas savoir (serveur injoignable).
  const required = request.anyLoader
    ? null
    : info === null || !info.reachable
      ? request.loader
      : info.modded === null
        ? null
        : info.modded === 'modded'
          ? request.loader
          : info.modded
  const offline = info !== null && !info.reachable
  const candidates = instances.filter((i) => {
    if (required && i.loader !== required) return false
    // Injoignable : seulement les versions sûres (celle de l'ami, celles du catalogue).
    if (offline) return i.mc_version === request.mcVersion || listed.includes(i.mc_version)
    return true
  })

  useEffect(() => {
    let alive = true
    void probeServer(ip).then((found) => {
      if (!alive) return
      setInfo(found)
      if (!found.reachable || found.echoes_all) return
      // Les versions de mes instances, la plus récente d'abord : une question
      // à la fois, chaque réponse affichée dès qu'elle arrive.
      const own = [...new Set(instances.map((i) => i.mc_version))]
        .filter((v) => v !== request.mcVersion && !listed.includes(v))
        .sort((a, b) => compareVersions(b, a))
      for (const v of own) void probeVersion(ip, v).then((r) => alive && setVerdicts((p) => ({ ...p, [v]: r })))
    })
    return () => {
      alive = false
    }
    // Les instances sont lues à l'ouverture : une instance créée ici est de la version de l'ami.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ip, request.mcVersion])

  function statusOf(inst: Instance): RowStatus {
    if (listed.includes(inst.mc_version)) return 'ok'
    // « Sa version » n'a de sens que pour un ami ; le catalogue n'a que la sienne, déjà acceptée.
    if (inst.mc_version === request.mcVersion) return fromCatalog ? 'ok' : 'same'
    if (info === null) return 'checking'
    if (info.echoes_all) return 'unsure'
    if (!(inst.mc_version in verdicts)) return 'checking'
    const v = verdicts[inst.mc_version]
    return v === true ? 'ok' : v === false ? 'refused' : 'unsure'
  }

  const rows = candidates
    .map((inst) => ({ inst, status: statusOf(inst) }))
    .sort((a, b) => ORDER[a.status] - ORDER[b.status] || compareVersions(b.inst.mc_version, a.inst.mc_version))
  const joinable = rows.filter((r) => r.status !== 'refused')
  const checking = rows.some((r) => r.status === 'checking')

  // Le joueur derrière le lien, s'il n'est pas déjà un ami : lu tout de suite,
  // montré une fois la partie lancée.
  useEffect(() => {
    if (!request.host || !signedIn) return
    api.friends
      .ticketLookup(request.host)
      .then((found) => {
        if (found.relation === 'none' || found.relation === 'incoming') setHost(found)
      })
      .catch(() => {})
  }, [request.host, signedIn])

  const launch = async (instanceId: string) => {
    // Rangé avant le lancement : le jeu relit sa liste en démarrant.
    if (request.remember) await api.servers.add(instanceId, request.remember, ip).catch(() => false)
    // Le choix « avec / sans le client intégré » suit l'instance, quel que
    // soit l'endroit d'où on la lance (voir AgentModal).
    await api.launch.start(instanceId, avoidBetaDependencies, showConsole, ip, isAgentEnabled(instanceId))
    setSelectedInstanceId(instanceId)
    setInstanceRunning(instanceId, true)
    navigate('/home')
    if (host) setStep('host')
    else onClose()
  }

  const handleJoin = async (instanceId: string) => {
    setLaunchingId(instanceId)
    try {
      await launch(instanceId)
    } catch (e) {
      showError(e)
      setLaunchingId(null)
    }
  }

  const handleCreate = async () => {
    setCreating(true)
    try {
      const loader = required ?? 'vanilla'
      const loaderName = loader === 'vanilla' ? 'Vanilla' : loader.charAt(0).toUpperCase() + loader.slice(1)
      const name = request.remember ?? t('friends.join.newName', { loader: loaderName, version: request.mcVersion })
      const instance = await api.instances.create(name, request.mcVersion, loader, defaultRam)
      addInstance(instance)
      setCreating(false)
      setLaunchingId(instance.id)
      await launch(instance.id)
    } catch (e) {
      showError(e)
      setCreating(false)
      setLaunchingId(null)
    }
  }

  const addHost = async () => {
    if (!host) return
    setBusy(true)
    try {
      const sent = await api.friends.request({ id: host.id })
      setHostDone(sent.status === 'friends' ? t('friends.nowFriends', { name: sent.username }) : t('friends.sent', { name: sent.username }))
      void refreshFriends()
    } catch (e) {
      setHostDone(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (step === 'host' && host) {
    return (
      <ModalShell title={t('friends.join.hostTitle', { name: host.username })} onClose={onClose} maxWidth="max-w-md">
        <div className="flex items-center gap-3 rounded-2xl border border-line bg-surface-1 p-3">
          <FriendAvatar skin={skinOf(host)} state={null} size={44} />
          <div className="flex min-w-0 flex-1 flex-col">
            <span className="truncate text-[14px] font-bold text-white">{host.username}</span>
            <span className="text-[12px] text-txt-muted">{hostDone ?? t('friends.join.hostHint')}</span>
          </div>
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <button onClick={onClose} className="rounded-xl px-4 py-2 text-[12.5px] font-semibold text-txt-secondary hover:text-white">
            {hostDone ? t('common.close') : t('friends.join.later')}
          </button>
          {!hostDone && (
            <button disabled={busy} onClick={() => void addHost()} className="rounded-xl bg-[#3ba55d] px-4 py-2 text-[12.5px] font-bold text-white disabled:opacity-50">
              {host.relation === 'incoming' ? t('friends.accept') : t('friends.join.hostAdd')}
            </button>
          )}
        </div>
      </ModalShell>
    )
  }

  const loaderLabel = required ?? 'vanilla'
  const nothing = joinable.length === 0 && !checking
  return (
    <ModalShell title={request.inviter ? t('friends.join.titleFrom', { name: request.inviter }) : t('friends.join.title')} onClose={onClose} maxWidth="max-w-lg">
      <p className="-mt-2 text-[12px] text-[rgba(255,255,255,0.4)] max-sm:mt-0 max-sm:text-[13px] max-sm:text-white/55">
        {t('friends.join.target', { version: request.mcVersion, loader: request.anyLoader ? 'vanilla' : request.loader, server: ip })}
      </p>
      {!request.anyLoader && info?.modded && (
        <p className="mt-1 text-[12px] text-warning max-sm:text-[13px]">
          {t('friends.join.modded', { loader: loaderName(info.modded === 'modded' ? request.loader : info.modded) })}
        </p>
      )}

      {nothing ? (
        <div className="mt-4 flex flex-col items-center gap-3 rounded-2xl border border-line bg-surface-1 px-5 py-6 text-center">
          <p className="text-[14px] font-bold text-white">{t('friends.join.none')}</p>
          <p className="max-w-sm text-[12px] leading-relaxed text-txt-muted max-sm:text-[15px]">{t('friends.join.noneHint', { version: request.mcVersion, loader: loaderLabel })}</p>
          <button
            disabled={creating || launchingId !== null}
            onClick={() => void handleCreate()}
            className="mt-1 rounded-xl bg-[rgb(var(--accent))] px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-[rgb(var(--accent-hover))] disabled:opacity-50 max-sm:h-12 max-sm:w-full max-sm:text-[15px]"
          >
            {creating ? t('friends.join.creating') : launchingId ? t('friends.join.launching') : t('friends.join.create')}
          </button>
        </div>
      ) : (
        <p className="mb-2 mt-3 text-[12px] text-txt-muted max-sm:text-[13px]">{checking ? t('friends.join.checkingHint') : t('friends.join.pick')}</p>
      )}

      {rows.length > 0 && (
        // Sur téléphone : lignes de 64 px, texte 15/13 ; la feuille défile d'elle-même.
        <div
          className={`flex max-h-[50vh] flex-col gap-2 overflow-y-auto pr-1 max-sm:max-h-none max-sm:overflow-visible max-sm:pr-0 max-sm:[&_p]:text-[15px] max-sm:[&>button]:min-h-[64px] ${nothing ? 'mt-3' : ''}`}
        >
          {rows.map(({ inst, status }) => {
            const refused = status === 'refused'
            return (
              <button
                key={inst.id}
                onClick={() => void handleJoin(inst.id)}
                disabled={launchingId !== null || refused}
                title={refused ? t('friends.join.refusedTitle', { version: inst.mc_version }) : status === 'unsure' ? t('friends.join.unsureTitle') : undefined}
                className="flex items-center justify-between gap-3 rounded-2xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-4 py-3 text-left transition-colors duration-150 hover:border-[rgb(var(--accent)/0.4)] hover:bg-[rgb(var(--accent)/0.12)] disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-[rgba(255,255,255,0.07)] disabled:hover:bg-[rgba(255,255,255,0.03)]"
              >
                <div className="flex min-w-0 flex-col gap-1">
                  <p className="truncate text-[13px] font-bold text-white">{inst.name}</p>
                  <div className="flex items-center gap-1.5 text-[10px] max-sm:text-[12.5px]">
                    <span className="font-semibold" style={{ color: loaderColor(inst.loader) }}>{inst.loader}</span>
                    <span className="text-[rgba(255,255,255,0.2)]">·</span>
                    <span className="text-[rgba(255,255,255,0.3)]">{inst.mc_version}</span>
                    {status === 'same' && (
                      <span className="rounded-full bg-[#3ba55d]/15 px-1.5 py-px font-semibold text-[#5fd38d]">{t('friends.join.sameVersion')}</span>
                    )}
                    {showsRam(inst) && (
                      <>
                        <span className="text-[rgba(255,255,255,0.2)]">·</span>
                        <span className="text-[rgba(255,255,255,0.3)]">{formatRam(inst.ram_mb)}</span>
                      </>
                    )}
                  </div>
                </div>
                <RowChip status={status} launching={launchingId === inst.id} />
              </button>
            )
          })}
        </div>
      )}
    </ModalShell>
  )
}

type RowStatus = 'same' | 'ok' | 'checking' | 'unsure' | 'refused'
const ORDER: Record<RowStatus, number> = { same: 0, ok: 1, checking: 2, unsure: 3, refused: 4 }

function loaderName(loader: string): string {
  return loader === 'neoforge' ? 'NeoForge' : loader.charAt(0).toUpperCase() + loader.slice(1)
}

/** Ce qu'on peut faire de la ligne : rejoindre, attendre la réponse du
 *  serveur (on peut quand même rejoindre), essayer, ou rien. */
function RowChip({ status, launching }: { status: RowStatus; launching: boolean }) {
  const t = useT()
  const base = 'flex flex-shrink-0 items-center gap-1.5 rounded-xl px-3 py-1.5 text-[11px] font-semibold max-sm:text-[13px]'
  if (launching) return <span className={`${base} bg-[rgb(var(--accent))] text-white`}>{t('friends.join.launching')}</span>
  if (status === 'refused') return <span className={`${base} bg-white/[0.05] text-txt-muted`}>{t('friends.join.refused')}</span>
  if (status === 'checking')
    return (
      <span className={`${base} bg-white/[0.06] text-txt-secondary`}>
        <span className="h-3 w-3 animate-spin rounded-full border-2 border-white/20 border-t-white/70" />
        {t('friends.join.checking')}
      </span>
    )
  if (status === 'unsure')
    return <span className={`${base} border border-[rgb(var(--accent)/0.5)] text-[rgb(var(--accent-text))]`}>{t('friends.join.unsure')}</span>
  return <span className={`${base} bg-[rgb(var(--accent))] text-white`}>{t('friends.join.join')}</span>
}
