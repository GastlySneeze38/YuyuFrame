import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { FriendAvatar } from '@/components/friends/FriendBits'
import { loaderColor } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { errorMessage } from '@/lib/apiError'
import { skinOf } from '@/lib/friends'
import { useStore } from '@/stores/useStore'
import { useFriends } from '@/stores/useFriends'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import type { LookupResult } from '@/types/friends'

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
 * ou le bouton « Rejoindre » du statut Discord de quelqu'un. Propose les
 * instances de la même version et du même loader et lance le jeu directement
 * sur le serveur ; sans instance compatible, en crée une (même version, même
 * loader, mémoire par défaut) et la lance.
 *
 * Venu du statut Discord d'un joueur qui n'est pas encore un ami, la fenêtre
 * reste ouverte une fois le jeu lancé pour proposer de l'ajouter d'un clic.
 */
export function JoinServerModal({ request, onClose }: { request: JoinRequest; onClose: () => void }) {
  const t = useT()
  const navigate = useNavigate()
  const { instances, avoidBetaDependencies, showConsole, setSelectedInstanceId, setInstanceRunning, isAgentEnabled, addInstance, defaultRam } = useStore()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const refreshFriends = useFriends((s) => s.refresh)
  const [launchingId, setLaunchingId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [host, setHost] = useState<LookupResult | null>(null)
  const [hostDone, setHostDone] = useState<string | null>(null)
  const [step, setStep] = useState<'pick' | 'host'>('pick')
  const [busy, setBusy] = useState(false)

  const versions = request.versions?.length ? request.versions : [request.mcVersion]
  const compatible = instances.filter(
    (i) => versions.includes(i.mc_version) && (request.anyLoader || i.loader === request.loader),
  )

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
    if (request.remember) await api.servers.add(instanceId, request.remember, request.ip).catch(() => false)
    // Le choix « avec / sans le client intégré » suit l'instance, quel que
    // soit l'endroit d'où on la lance (voir AgentModal).
    await api.launch.start(instanceId, avoidBetaDependencies, showConsole, request.ip, isAgentEnabled(instanceId))
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
      const loader = request.anyLoader ? 'vanilla' : request.loader
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

  const loaderLabel = request.anyLoader ? 'vanilla' : request.loader
  return (
    <ModalShell title={request.inviter ? t('friends.join.titleFrom', { name: request.inviter }) : t('friends.join.title')} onClose={onClose} maxWidth="max-w-lg">
      <p className="-mt-2 text-[12px] text-[rgba(255,255,255,0.4)]">
        {t('friends.join.target', { version: request.mcVersion, loader: loaderLabel, server: request.ip })}
      </p>

      {compatible.length === 0 ? (
        <div className="mt-4 flex flex-col items-center gap-3 rounded-2xl border border-line bg-surface-1 px-5 py-6 text-center">
          <p className="text-[14px] font-bold text-white">{t('friends.join.none')}</p>
          <p className="max-w-sm text-[12px] leading-relaxed text-txt-muted">{t('friends.join.noneHint', { version: request.mcVersion, loader: loaderLabel })}</p>
          <button
            disabled={creating || launchingId !== null}
            onClick={() => void handleCreate()}
            className="mt-1 rounded-xl bg-[#4B3FCF] px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-[#6155e8] disabled:opacity-50"
          >
            {creating ? t('friends.join.creating') : launchingId ? t('friends.join.launching') : t('friends.join.create')}
          </button>
        </div>
      ) : (
        <>
          <p className="mb-2 mt-3 text-[12px] text-txt-muted">{t('friends.join.pick')}</p>
          <div className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto pr-1">
            {compatible.map((inst) => (
              <button
                key={inst.id}
                onClick={() => void handleJoin(inst.id)}
                disabled={launchingId !== null}
                className="flex items-center justify-between gap-3 rounded-2xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-4 py-3 text-left transition-all duration-150 hover:border-[rgba(75,63,207,0.4)] hover:bg-[rgba(75,63,207,0.12)] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <div className="flex min-w-0 flex-col gap-1">
                  <p className="truncate text-[13px] font-bold text-white">{inst.name}</p>
                  <div className="flex items-center gap-1.5">
                    <span className="text-[10px] font-semibold" style={{ color: loaderColor(inst.loader) }}>{inst.loader}</span>
                    <span className="text-[10px] text-[rgba(255,255,255,0.2)]">·</span>
                    <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{inst.mc_version}</span>
                    <span className="text-[10px] text-[rgba(255,255,255,0.2)]">·</span>
                    <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{formatRam(inst.ram_mb)}</span>
                  </div>
                </div>
                <span className="flex-shrink-0 rounded-xl bg-[#4B3FCF] px-3 py-1.5 text-[11px] font-semibold text-white">
                  {launchingId === inst.id ? t('friends.join.launching') : t('friends.join.join')}
                </span>
              </button>
            ))}
          </div>
        </>
      )}
    </ModalShell>
  )
}
