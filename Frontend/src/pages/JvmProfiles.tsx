import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { formatRam } from '@/lib/format'
import { autoVendorFor, parseJvmArgs } from '@/lib/jvmFlags'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import type { Instance, JvmProfile } from '@/types'

const VENDOR_LABELS: Record<string, string> = {
  auto: 'Auto',
  temurin: 'Temurin',
  openj9: 'OpenJ9',
  graal: 'GraalVM',
  custom: 'Personnalisé',
}

function flagCount(p: JvmProfile): number {
  return parseJvmArgs(p.args_jvm).length + parseJvmArgs(p.args_gc).length + parseJvmArgs(p.args_jit).length
}

/** Résumé d'une ligne : ce qui distingue vraiment deux configs entre elles. */
function summarize(p: JvmProfile): string {
  const vendor = p.jvm_vendor === 'auto'
    ? `Auto (${VENDOR_LABELS[autoVendorFor(p.ram_mb ?? 4096)]})`
    : VENDOR_LABELS[p.jvm_vendor] ?? p.jvm_vendor
  const gc = p.gc_policy === 'auto' ? 'GC auto' : `GC ${p.gc_policy}`
  const ram = p.ram_mb ? formatRam(p.ram_mb) : 'RAM de l’instance'
  const n = flagCount(p)
  return [vendor, gc, ram, n > 0 ? `${n} drapeau${n > 1 ? 'x' : ''}` : 'aucun drapeau'].join(' · ')
}

function Chip({ children, onClick, active }: { children: React.ReactNode; onClick?: () => void; active?: boolean }) {
  return (
    <button
      onClick={onClick}
      disabled={!onClick}
      className={`h-[24px] rounded-md border px-2 text-[10px] font-semibold transition-colors ${
        active
          ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.3)] text-white'
          : 'border-[rgba(255,255,255,0.09)] bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.45)]'
      } ${onClick ? 'hover:border-white/30' : 'cursor-default'}`}
    >
      {children}
    </button>
  )
}

/**
 * Écran "Configurations JVM" (`/jvm`) — la bibliothèque.
 *
 * Une configuration est une entité à part entière, pas un réglage caché dans
 * une instance : c'est ce qui permet d'en avoir plusieurs, de les comparer, et
 * de basculer une instance de l'une à l'autre sans rien ressaisir. Une instance
 * pointe vers au plus une config ; relier une instance ici la délie donc
 * automatiquement de sa config précédente.
 */
export default function JvmProfiles() {
  const navigate = useNavigate()
  const { instances, setInstances } = useStore()
  const [profiles, setProfiles] = useState<JvmProfile[] | null>(null)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [linkingId, setLinkingId] = useState<string | null>(null)

  useEffect(() => {
    api.jvmProfiles.list().then(setProfiles).catch((e) => { setProfiles([]); showError(e) })
  }, [])

  // Un seul parcours des instances pour toutes les cartes, plutôt qu'un filtre
  // par carte : la liste est relue à chaque rendu de la page.
  const byProfile = useMemo(() => {
    const map = new Map<string, Instance[]>()
    for (const i of instances) {
      if (!i.jvm_profile_id) continue
      const list = map.get(i.jvm_profile_id) ?? []
      list.push(i)
      map.set(i.jvm_profile_id, list)
    }
    return map
  }, [instances])

  const refreshInstances = () => api.instances.list().then(setInstances).catch(showError)

  const handleCreate = async (fromId?: string) => {
    const name = newName.trim() || (fromId ? 'Copie' : 'Nouvelle configuration')
    setBusy(true)
    try {
      const created = await api.jvmProfiles.create(name, fromId)
      setProfiles((p) => [...(p ?? []), created])
      setCreating(false)
      setNewName('')
      navigate(`/jvm/${created.id}`)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const handleDuplicate = async (p: JvmProfile) => {
    setBusy(true)
    try {
      const created = await api.jvmProfiles.create(`${p.name} (copie)`, p.id)
      setProfiles((list) => [...(list ?? []), created])
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (id: string) => {
    setBusy(true)
    try {
      await api.jvmProfiles.delete(id)
      setProfiles((list) => (list ?? []).filter((p) => p.id !== id))
      setConfirmDelete(null)
      await refreshInstances()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const toggleLink = async (instance: Instance, profileId: string) => {
    const next = instance.jvm_profile_id === profileId ? null : profileId
    try {
      await api.instances.setJvmProfile(instance.id, next)
      await refreshInstances()
    } catch (e) {
      showError(e)
    }
  }

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
      <PageHeader>
        <PageHeaderSeparator />
        <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configurations JVM</h1>
        <div className="flex-1" />
        <button
          onClick={() => setCreating((v) => !v)}
          className="h-[30px] rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] px-3.5 text-[11px] font-bold text-white transition-all duration-150 hover:bg-[rgba(75,63,207,0.5)]"
        >
          + Nouvelle
        </button>
      </PageHeader>

      <div className="flex-1 overflow-auto">
        <div className="mx-auto flex w-full max-w-4xl flex-col gap-3 px-6 py-6">

          <p className="text-[11px] leading-relaxed text-[rgba(255,255,255,0.35)]">
            Une configuration regroupe la JVM, le ramasse-miettes et les drapeaux à passer au jeu. Reliez-la à autant
            d'instances que vous voulez — une instance n'en utilise qu'une seule à la fois.
          </p>

          {creating && (
            <div className="flex items-center gap-2 rounded-2xl border border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.08)] p-3">
              <input
                autoFocus
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') handleCreate(); if (e.key === 'Escape') setCreating(false) }}
                placeholder="Nom de la configuration (ex : Shenandoah + JIT débridé)"
                className="h-[32px] flex-1 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.4)] px-3 text-[12px] text-white outline-none focus:border-[rgba(75,63,207,0.6)]"
              />
              <button
                onClick={() => handleCreate()}
                disabled={busy}
                className="flex h-[32px] items-center gap-2 rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] px-3 text-[11px] font-bold text-white disabled:opacity-50"
              >
                {busy && <ButtonSpinner />}
                Créer
              </button>
            </div>
          )}

          {profiles === null && (
            <div className="flex justify-center py-12"><ButtonSpinner size={20} /></div>
          )}

          {profiles?.length === 0 && !creating && (
            <div className="flex flex-col items-center gap-2 rounded-2xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.02)] px-6 py-12 text-center">
              <p className="text-[14px] font-semibold text-[rgba(255,255,255,0.5)]">Aucune configuration</p>
              <p className="max-w-md text-[12px] leading-relaxed text-[rgba(255,255,255,0.25)]">
                Sans configuration, chaque instance utilise les drapeaux générés par le launcher selon sa RAM. Créez-en
                une pour tester autre chose — et une deuxième pour avoir un point de comparaison.
              </p>
            </div>
          )}

          {profiles?.map((p) => {
            const linked = byProfile.get(p.id) ?? []
            const isLinking = linkingId === p.id
            return (
              <div
                key={p.id}
                className="flex flex-col gap-3 rounded-2xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] p-4 transition-colors hover:border-[rgba(255,255,255,0.14)]"
              >
                <div className="flex items-start justify-between gap-3">
                  <button onClick={() => navigate(`/jvm/${p.id}`)} className="min-w-0 flex-1 text-left">
                    <p className="truncate text-[14px] font-bold text-white">{p.name}</p>
                    <p className="mt-0.5 truncate text-[11px] text-[rgba(255,255,255,0.4)]">{summarize(p)}</p>
                  </button>
                  <div className="flex flex-shrink-0 items-center gap-1.5">
                    <button
                      onClick={() => navigate(`/jvm/${p.id}`)}
                      className="h-[28px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-2.5 text-[11px] font-semibold text-[rgba(255,255,255,0.7)] transition-colors hover:border-white/25"
                    >
                      Modifier
                    </button>
                    <button
                      onClick={() => handleDuplicate(p)}
                      disabled={busy}
                      className="h-[28px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-2.5 text-[11px] font-semibold text-[rgba(255,255,255,0.5)] transition-colors hover:border-white/25 disabled:opacity-50"
                    >
                      Dupliquer
                    </button>
                    {confirmDelete === p.id ? (
                      <button
                        onClick={() => handleDelete(p.id)}
                        disabled={busy}
                        className="h-[28px] rounded-lg border border-[rgba(220,90,90,0.6)] bg-[rgba(220,90,90,0.2)] px-2.5 text-[11px] font-bold text-[rgba(255,150,150,0.95)] disabled:opacity-50"
                      >
                        Confirmer
                      </button>
                    ) : (
                      <button
                        onClick={() => setConfirmDelete(p.id)}
                        className="h-[28px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-2.5 text-[11px] font-semibold text-[rgba(255,255,255,0.4)] transition-colors hover:border-[rgba(220,90,90,0.5)] hover:text-[rgba(255,150,150,0.9)]"
                      >
                        Supprimer
                      </button>
                    )}
                  </div>
                </div>

                <div className="flex flex-wrap items-center gap-1.5">
                  {linked.length === 0 ? (
                    <span className="text-[10px] text-[rgba(255,255,255,0.25)]">Reliée à aucune instance</span>
                  ) : (
                    linked.map((i) => <Chip key={i.id} active>{i.name}</Chip>)
                  )}
                  <button
                    onClick={() => setLinkingId(isLinking ? null : p.id)}
                    className="h-[24px] rounded-md border border-dashed border-[rgba(255,255,255,0.18)] px-2 text-[10px] font-semibold text-[rgba(150,140,240,0.9)] transition-colors hover:border-[rgba(75,63,207,0.7)]"
                  >
                    {isLinking ? 'Terminé' : 'Relier…'}
                  </button>
                </div>

                {isLinking && (
                  <div className="flex flex-col gap-2 rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] p-3">
                    <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.35)]">
                      Cocher une instance déjà reliée ailleurs la fait basculer sur cette config — une instance n'en
                      utilise qu'une.
                    </p>
                    <div className="flex flex-wrap gap-1.5">
                      {instances.length === 0 && (
                        <span className="text-[10px] text-[rgba(255,255,255,0.25)]">Aucune instance.</span>
                      )}
                      {instances.map((i) => {
                        const here = i.jvm_profile_id === p.id
                        const elsewhere = !!i.jvm_profile_id && !here
                        return (
                          <Chip key={i.id} active={here} onClick={() => toggleLink(i, p.id)}>
                            {here ? '✓ ' : ''}{i.name}
                            {elsewhere && <span className="opacity-50"> (autre config)</span>}
                          </Chip>
                        )
                      })}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
