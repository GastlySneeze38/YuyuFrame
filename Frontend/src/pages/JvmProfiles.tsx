import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { formatRam } from '@/lib/format'
import { autoVendorFor, effectiveGc, familyFor, parseJvmArgs, type JvmFamily } from '@/lib/jvmFlags'
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

const FAMILY_LABEL: Record<JvmFamily, string> = {
  hotspot: 'HotSpot',
  openj9: 'OpenJ9',
  graal: 'GraalVM',
}

type LinkFilter = 'all' | 'linked' | 'unlinked'

/** Tout ce qu'on veut savoir d'une config sans l'ouvrir. Calculé une fois par
 * config et réutilisé par la carte, les filtres et le tri. */
function describe(p: JvmProfile) {
  const ram = p.ram_mb ?? 4096
  const family = familyFor(p.jvm_vendor, ram)
  const counts = {
    jvm: parseJvmArgs(p.args_jvm).length,
    gc: parseJvmArgs(p.args_gc).length,
    jit: parseJvmArgs(p.args_jit).length,
  }
  return {
    family,
    counts,
    total: counts.jvm + counts.gc + counts.jit,
    gc: effectiveGc(p.gc_policy, family, p.args_jvm, p.args_gc, p.args_jit),
    vendor: p.jvm_vendor === 'auto'
      ? `Auto → ${VENDOR_LABELS[autoVendorFor(ram)]}`
      : VENDOR_LABELS[p.jvm_vendor] ?? p.jvm_vendor,
    ram: p.ram_mb ? formatRam(p.ram_mb) : "Celle de l'instance",
  }
}

// ── Briques ──────────────────────────────────────────────────────────────────

function Spec({ label, value, accent, note }: { label: string; value: string; accent?: boolean; note?: string }) {
  return (
    <div
      className={`flex flex-col rounded-lg border px-2.5 py-1.5 ${
        accent
          ? 'border-[rgba(75,63,207,0.45)] bg-[rgba(75,63,207,0.16)]'
          : 'border-[rgba(255,255,255,0.07)] bg-[rgba(0,0,0,0.28)]'
      }`}
    >
      <span className="text-[9px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.32)]">{label}</span>
      <span className={`text-[11px] font-bold ${accent ? 'text-[rgba(190,183,255,0.95)]' : 'text-[rgba(255,255,255,0.72)]'}`}>
        {value}
      </span>
      {note && <span className="text-[9px] text-[rgba(255,255,255,0.28)]">{note}</span>}
    </div>
  )
}

function CountPill({ label, n }: { label: string; n: number }) {
  return (
    <span
      className={`rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${
        n > 0 ? 'bg-[rgba(255,255,255,0.07)] text-[rgba(255,255,255,0.6)]' : 'bg-[rgba(255,255,255,0.03)] text-[rgba(255,255,255,0.22)]'
      }`}
    >
      {label} {n}
    </span>
  )
}

function FilterChips<T extends string>({ options, value, onChange }: {
  options: { id: T; label: string; count?: number }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="flex flex-wrap gap-1">
      {options.map((o) => (
        <button
          key={o.id}
          onClick={() => onChange(o.id)}
          className={`h-[26px] rounded-lg border px-2.5 text-[10px] font-semibold transition-colors ${
            value === o.id
              ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.3)] text-white'
              : 'border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.42)] hover:border-white/25'
          }`}
        >
          {o.label}
          {o.count !== undefined && <span className="ml-1 opacity-55">{o.count}</span>}
        </button>
      ))}
    </div>
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
 *
 * La carte affiche le collecteur RÉELLEMENT appliqué (voir `effectiveGc`), pas
 * le réglage de la grille : un sélecteur posé dans les drapeaux l'emporte
 * toujours sur celui-ci, et annoncer la grille reviendrait souvent à nommer un
 * collecteur qui ne tourne pas.
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

  const [search, setSearch] = useState('')
  const [linkFilter, setLinkFilter] = useState<LinkFilter>('all')
  const [familyFilter, setFamilyFilter] = useState<JvmFamily | 'all'>('all')
  const [gcFilter, setGcFilter] = useState<string>('all')

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

  const unlinkedInstances = useMemo(() => instances.filter((i) => !i.jvm_profile_id), [instances])

  const described = useMemo(
    () => (profiles ?? []).map((p) => ({ profile: p, info: describe(p), linked: byProfile.get(p.id) ?? [] })),
    [profiles, byProfile],
  )

  // Les valeurs de GC proposées viennent des configs réellement présentes —
  // une liste figée afficherait des filtres qui ne renvoient jamais rien.
  const gcValues = useMemo(
    () => [...new Set(described.map((d) => d.info.gc.label))].sort(),
    [described],
  )

  const visible = described.filter(({ profile, info, linked }) => {
    if (linkFilter === 'linked' && linked.length === 0) return false
    if (linkFilter === 'unlinked' && linked.length > 0) return false
    if (familyFilter !== 'all' && info.family !== familyFilter) return false
    if (gcFilter !== 'all' && info.gc.label !== gcFilter) return false
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      const hay = [profile.name, profile.args_jvm, profile.args_gc, profile.args_jit, info.gc.label, info.vendor]
        .join('\n').toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })

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

  const linkedCount = instances.length - unlinkedInstances.length

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-[#09090D]">
      <PageHeader>
        <PageHeaderSeparator />
        <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configurations JVM</h1>
        <span className="rounded-md bg-[rgba(255,255,255,0.05)] px-2 py-0.5 text-[11px] font-semibold text-[rgba(255,255,255,0.4)]">
          {linkedCount}/{instances.length} instance{instances.length > 1 ? 's' : ''} reliée{linkedCount > 1 ? 's' : ''}
        </span>
      </PageHeader>

      {/* ── Filtres ─────────────────────────────────────────────────────────
          Sortis du flux de la liste : ils doivent rester atteignables quand on
          fait défiler une bibliothèque de configs de benchmark. */}
      <div className="flex flex-shrink-0 flex-wrap items-center gap-2 border-b border-[rgba(255,255,255,0.06)] px-6 py-2.5">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Rechercher — nom ou drapeau (ex : Shenandoah, MaxGCPause)"
          className="h-[28px] w-[330px] rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.4)] px-2.5 text-[11px] text-white outline-none placeholder:text-[rgba(255,255,255,0.25)] focus:border-[rgba(75,63,207,0.6)]"
        />
        <div className="h-[20px] w-px bg-[rgba(255,255,255,0.07)]" />
        <FilterChips
          value={linkFilter}
          onChange={setLinkFilter}
          options={[
            { id: 'all', label: 'Toutes', count: described.length },
            { id: 'linked', label: 'Reliées', count: described.filter((d) => d.linked.length > 0).length },
            { id: 'unlinked', label: 'Non reliées', count: described.filter((d) => d.linked.length === 0).length },
          ]}
        />
        <div className="h-[20px] w-px bg-[rgba(255,255,255,0.07)]" />
        <FilterChips
          value={familyFilter}
          onChange={setFamilyFilter}
          options={[
            { id: 'all' as const, label: 'Toute JVM' },
            ...(['hotspot', 'openj9', 'graal'] as JvmFamily[])
              .filter((f) => described.some((d) => d.info.family === f))
              .map((f) => ({ id: f, label: FAMILY_LABEL[f] })),
          ]}
        />
        {gcValues.length > 1 && (
          <>
            <div className="h-[20px] w-px bg-[rgba(255,255,255,0.07)]" />
            <FilterChips
              value={gcFilter}
              onChange={setGcFilter}
              options={[{ id: 'all', label: 'Tout GC' }, ...gcValues.map((g) => ({ id: g, label: g }))]}
            />
          </>
        )}
      </div>

      <div className="flex-1 overflow-auto">
        {/* pb-24 : réserve la place du bouton flottant, sinon il masque la
            dernière carte une fois la liste assez longue pour défiler. */}
        <div className="mx-auto flex w-full max-w-4xl flex-col gap-3 px-6 pb-24 pt-5">

          {profiles === null && (
            <div className="flex justify-center py-12"><ButtonSpinner size={20} /></div>
          )}

          {profiles?.length === 0 && (
            <div className="flex flex-col items-center gap-2 rounded-2xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.02)] px-6 py-12 text-center">
              <p className="text-[14px] font-semibold text-[rgba(255,255,255,0.5)]">Aucune configuration</p>
              <p className="max-w-md text-[12px] leading-relaxed text-[rgba(255,255,255,0.25)]">
                Sans configuration, chaque instance utilise les drapeaux générés par le launcher selon sa RAM. Créez-en
                une pour tester autre chose — et une deuxième pour avoir un point de comparaison.
              </p>
            </div>
          )}

          {profiles !== null && profiles.length > 0 && visible.length === 0 && (
            <p className="py-10 text-center text-[12px] text-[rgba(255,255,255,0.25)]">
              Aucune configuration ne correspond à ces filtres.
            </p>
          )}

          {visible.map(({ profile: p, info, linked }) => {
            const isLinking = linkingId === p.id
            return (
              <div
                key={p.id}
                className={`flex flex-col gap-3 rounded-2xl border bg-[rgba(255,255,255,0.03)] p-4 transition-colors ${
                  linked.length > 0
                    ? 'border-[rgba(75,63,207,0.35)] hover:border-[rgba(75,63,207,0.6)]'
                    : 'border-[rgba(255,255,255,0.07)] hover:border-[rgba(255,255,255,0.14)]'
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <button onClick={() => navigate(`/jvm/${p.id}`)} className="min-w-0 flex-1 text-left">
                    <p className="truncate text-[14px] font-bold text-white">{p.name}</p>
                    <div className="mt-0.5 flex flex-wrap items-center gap-1">
                      <CountPill label="Moteur" n={info.counts.jvm} />
                      <CountPill label="GC" n={info.counts.gc} />
                      <CountPill label="JIT" n={info.counts.jit} />
                      {p.args_mode === 'replace' && (
                        <span className="rounded-md bg-[rgba(240,180,90,0.14)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(240,180,90,0.85)]">
                          Remplace la base
                        </span>
                      )}
                    </div>
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

                {/* Ce que la config applique vraiment. */}
                <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-4">
                  <Spec label="JVM" value={info.vendor} />
                  <Spec
                    label="Ramasse-miettes"
                    value={info.gc.label}
                    accent={info.gc.fromFlags}
                    note={info.gc.fromFlags ? 'depuis les drapeaux' : undefined}
                  />
                  <Spec label="Mémoire" value={info.ram} />
                  <Spec label="Drapeaux" value={`${info.total} au total`} />
                </div>

                {/* ── Le lien ────────────────────────────────────────────────
                    Bloc à part, avec son propre fond : c'est l'information la
                    plus consultée de cet écran (« quelle instance tourne avec
                    quoi »), elle ne peut pas se réduire à une ligne de texte
                    perdue en bas de carte. */}
                <div
                  className={`flex flex-col gap-2 rounded-xl border p-2.5 ${
                    linked.length > 0
                      ? 'border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.09)]'
                      : 'border-dashed border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.2)]'
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className={`text-[10px] font-semibold uppercase tracking-[0.1em] ${
                      linked.length > 0 ? 'text-[rgba(180,172,255,0.8)]' : 'text-[rgba(255,255,255,0.3)]'
                    }`}>
                      {linked.length > 0
                        ? `Appliquée à ${linked.length} instance${linked.length > 1 ? 's' : ''}`
                        : "Appliquée à aucune instance"}
                    </span>
                    <button
                      onClick={() => setLinkingId(isLinking ? null : p.id)}
                      className="h-[24px] flex-shrink-0 rounded-md border border-[rgba(255,255,255,0.15)] bg-[rgba(255,255,255,0.05)] px-2 text-[10px] font-semibold text-[rgba(150,140,240,0.95)] transition-colors hover:border-[rgba(75,63,207,0.7)]"
                    >
                      {isLinking ? 'Terminé' : linked.length > 0 ? 'Modifier les liens' : 'Relier une instance'}
                    </button>
                  </div>

                  {linked.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {linked.map((i) => (
                        <span
                          key={i.id}
                          className="flex items-center gap-1 rounded-md border border-[rgba(75,63,207,0.55)] bg-[rgba(75,63,207,0.25)] px-1.5 py-0.5 text-[10px] font-semibold text-white"
                        >
                          {i.name}
                          <span className="font-normal opacity-50">{i.mc_version}</span>
                        </span>
                      ))}
                    </div>
                  )}

                  {isLinking && (
                    <div className="flex flex-col gap-1.5 border-t border-[rgba(255,255,255,0.08)] pt-2">
                      <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.35)]">
                        Cliquer une instance déjà reliée ailleurs la fait basculer sur cette config — une instance n'en
                        utilise qu'une.
                      </p>
                      <div className="flex flex-wrap gap-1">
                        {instances.length === 0 && (
                          <span className="text-[10px] text-[rgba(255,255,255,0.25)]">Aucune instance.</span>
                        )}
                        {instances.map((i) => {
                          const here = i.jvm_profile_id === p.id
                          const elsewhere = !!i.jvm_profile_id && !here
                          return (
                            <button
                              key={i.id}
                              onClick={() => toggleLink(i, p.id)}
                              className={`h-[24px] rounded-md border px-2 text-[10px] font-semibold transition-colors ${
                                here
                                  ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] text-white'
                                  : 'border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.45)] hover:border-white/30'
                              }`}
                            >
                              {here ? '✓ ' : ''}{i.name}
                              {elsewhere && <span className="opacity-45"> · déjà reliée</span>}
                            </button>
                          )
                        })}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )
          })}

          {/* Vue d'ensemble du lien côté instances : sans elle, une instance
              oubliée sur les drapeaux par défaut n'apparaît nulle part. */}
          {profiles !== null && profiles.length > 0 && unlinkedInstances.length > 0 && (
            <div className="flex flex-col gap-2 rounded-2xl border border-dashed border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.015)] p-4">
              <p className="text-[11px] font-semibold text-[rgba(255,255,255,0.5)]">
                {unlinkedInstances.length} instance{unlinkedInstances.length > 1 ? 's' : ''} sans configuration
              </p>
              <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.28)]">
                Elles utilisent les drapeaux générés par le launcher selon leur RAM. Reliez-les depuis une carte
                ci-dessus.
              </p>
              <div className="flex flex-wrap gap-1">
                {unlinkedInstances.map((i) => (
                  <span
                    key={i.id}
                    className="rounded-md border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.3)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(255,255,255,0.4)]"
                  >
                    {i.name}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Bouton flottant : la création est l'action principale de cet écran,
          mais elle ne doit pas suivre le défilement de la liste ni se perdre
          dans l'en-tête. La saisie du nom se fait au même endroit, en dépliant
          le bouton, plutôt que dans un formulaire en haut de page que le
          regard aurait à aller chercher. */}
      <div className="absolute bottom-6 right-6 z-10">
        {creating ? (
          <div className="flex items-center gap-2 rounded-2xl border border-[rgba(75,63,207,0.5)] bg-[rgba(20,18,32,0.96)] p-2 shadow-[0_8px_32px_rgba(0,0,0,0.55)] backdrop-blur-sm">
            <input
              autoFocus
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreate()
                if (e.key === 'Escape') { setCreating(false); setNewName('') }
              }}
              placeholder="Nom (ex : Shenandoah + JIT débridé)"
              className="h-[34px] w-[300px] rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-[12px] text-white outline-none focus:border-[rgba(75,63,207,0.6)]"
            />
            <button
              onClick={() => handleCreate()}
              disabled={busy}
              className="flex h-[34px] items-center gap-2 rounded-xl border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.45)] px-3.5 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.6)] disabled:opacity-50"
            >
              {busy && <ButtonSpinner />}
              Créer
            </button>
          </div>
        ) : (
          <button
            onClick={() => setCreating(true)}
            className="flex h-[42px] items-center gap-2 rounded-2xl border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.45)] px-5 text-[12px] font-bold text-white shadow-[0_8px_28px_rgba(40,30,140,0.45)] transition-all duration-150 hover:bg-[rgba(75,63,207,0.62)] hover:shadow-[0_10px_34px_rgba(40,30,140,0.6)]"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14} className="flex-shrink-0">
              <path d="M11 5h2v14h-2z" />
              <path d="M5 11h14v2H5z" />
            </svg>
            Ajouter une configuration
          </button>
        )}
      </div>
    </div>
  )
}
