import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useStorePick } from '@/stores/useStore'
import type { Instance } from '@/types'
import { ModsContent } from '@/pages/Mods'
import { ImportSourceModal } from '@/components/import/ImportSourceModal'
import { CreateInstanceModal } from '@/components/instances/CreateInstanceModal'
import { InstanceSettingsModal } from '@/components/instances/InstanceSettingsModal'
import { DuplicateInstanceModal } from '@/components/instances/DuplicateInstanceModal'
import { ShareImportModal } from '@/components/instances/ShareImportModal'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import { InstanceRow } from './instances/InstanceRow'
import { InstanceActionSheet } from './instances/InstanceActionSheet'
import { loaderName } from './home/InstanceSheet'
import { MenuButton } from './ui/NavDrawer'

/** Courbe des tiroirs d'iOS : l'écran poussé arrive comme sur un téléphone. */
const PUSH_EASE = [0.32, 0.72, 0, 1] as const

/**
 * L'écran Instances sur téléphone : celui du PC, en réduit.
 *
 * Sur PC, la liste est à gauche et le contenu de l'instance choisie (mods,
 * packs, shaders, options) à droite. Sur un téléphone il n'y a la place que
 * pour l'un des deux : la liste occupe l'écran, et toucher une instance la
 * choisit puis pousse son contenu par-dessus, depuis la droite — le motif
 * liste → détail de toutes les applis. Le contenu ouvert est noté dans
 * l'adresse (`?open=<id>`) : le bouton retour d'Android le referme.
 *
 * Ordre et hiérarchie du PC : en-tête (titre, configurations JVM), Favoris,
 * Autres (repliables), puis les deux actions principales — Nouvelle instance
 * en avant, Importer à côté — posées en bas, sous le pouce.
 */
export default function MobileInstances() {
  const t = useT()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const {
    versions, setVersions,
    instances, setInstances, addInstance, updateInstance, removeInstance,
    selectedInstanceId, setSelectedInstanceId,
    defaultRam, defaultJvmVendor, defaultJvmCustomPath, defaultGcPolicy,
  } = useStorePick(
    'versions', 'setVersions',
    'instances', 'setInstances', 'addInstance', 'updateInstance', 'removeInstance',
    'selectedInstanceId', 'setSelectedInstanceId',
    'defaultRam', 'defaultJvmVendor', 'defaultJvmCustomPath', 'defaultGcPolicy',
  )

  const [loading, setLoading] = useState(instances.length === 0)
  const [showCreate, setShowCreate] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [showShareImport, setShowShareImport] = useState(false)
  // Désignées par identifiant, jamais copiées : leur nom et leur étoile
  // changent pendant qu'elles sont ouvertes (même raison que sur PC).
  const [settingsTargetId, setSettingsTargetId] = useState<string | null>(null)
  const [settingsTab, setSettingsTab] = useState<string | undefined>(undefined)
  const [actionsForId, setActionsForId] = useState<string | null>(null)
  const [duplicateSource, setDuplicateSource] = useState<Instance | null>(null)
  const [othersExpanded, setOthersExpanded] = useState(true)
  const loaded = useRef(false)

  const openId = searchParams.get('open')
  const opened = instances.find((i) => i.id === openId) ?? null
  const settingsTarget = instances.find((i) => i.id === settingsTargetId) ?? null
  const actionsFor = instances.find((i) => i.id === actionsForId) ?? null
  const favorites = instances.filter((i) => i.favorite)
  const others = instances.filter((i) => !i.favorite)

  // Même chargement que sur PC : la liste d'abord (SQLite, immédiat), les
  // versions de Mojang derrière sans rien bloquer.
  useEffect(() => {
    if (loaded.current) return
    loaded.current = true
    api.instances
      .list()
      .then((insts) => {
        setInstances(insts)
        if (insts.some((i) => i.favorite)) setOthersExpanded(false)
      })
      .catch(showError)
      .finally(() => setLoading(false))
    if (versions.length === 0) api.versions.list().then(setVersions).catch(() => {})
  }, [])

  // `?settings=<id>&tab=java` ouvre les paramètres sur un onglet, comme sur
  // PC (lien de la fenêtre de plantage, par exemple). Seuls ces deux
  // paramètres sont retirés : `open` reste, c'est l'écran poussé.
  useEffect(() => {
    const target = searchParams.get('settings')
    if (!target) return
    setSettingsTargetId(target)
    setSettingsTab(searchParams.get('tab') ?? undefined)
    const next = new URLSearchParams(searchParams)
    next.delete('settings')
    next.delete('tab')
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  const releaseVersions = versions.filter((v) => v.version_type === 'release').map((v) => v.id)

  const openInstance = useCallback((inst: Instance) => {
    setSelectedInstanceId(inst.id)
    setSearchParams({ open: inst.id })
  }, [setSelectedInstanceId, setSearchParams])

  const closeInstance = () => {
    // Revenir en arrière si l'écran a été poussé d'ici, pour que le bouton
    // retour d'Android et celui de l'écran fassent la même chose.
    if (window.history.state?.idx > 0) navigate(-1)
    else setSearchParams({}, { replace: true })
  }

  const handleDelete = useCallback(async (id: string) => {
    try {
      await api.instances.delete(id)
      removeInstance(id)
      setSettingsTargetId((current) => (current === id ? null : current))
    } catch (e) { showError(e) }
  }, [removeInstance])

  const handleToggleFavorite = useCallback(async (id: string) => {
    try {
      updateInstance(await api.instances.toggleFavorite(id))
    } catch (e) { showError(e) }
  }, [updateInstance])

  const handleDuplicateNow = useCallback(async (inst: Instance) => {
    try {
      const copy = await api.instances.duplicate(inst.id, t('instancesPage.copyOf', { name: inst.name }), inst.mc_version, inst.ram_mb)
      addInstance(copy)
      setSelectedInstanceId(copy.id)
    } catch (e) { showError(e) }
  }, [addInstance, setSelectedInstanceId, t])

  const openActions = useCallback((inst: Instance) => setActionsForId(inst.id), [])

  const row = (inst: Instance) => (
    <InstanceRow
      key={inst.id}
      instance={inst}
      selected={inst.id === selectedInstanceId}
      onOpen={openInstance}
      onToggleFavorite={handleToggleFavorite}
      onMore={openActions}
    />
  )

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-white">
      {/* ── En-tête : titre en grand, configurations JVM à droite ── */}
      <header className="flex shrink-0 items-center gap-1 pb-3 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
        <MenuButton />
        <h1 className="min-w-0 flex-1 truncate text-[28px] font-black leading-none tracking-[-0.015em] text-white">{t('instancesPage.title')}</h1>
        <button
          onClick={() => navigate('/jvm')}
          className="press flex h-11 shrink-0 items-center rounded-full border border-white/[0.1] bg-white/[0.05] px-4 text-[14px] font-semibold text-white/80"
        >
          {t('settings.lancement.jvmLabel')}
        </button>
      </header>

      {/* ── La liste ── */}
      <main className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-4">
        {loading ? (
          <div className="flex flex-col gap-2 pt-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="h-[72px] animate-pulse rounded-2xl bg-white/[0.04]" />
            ))}
          </div>
        ) : instances.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
            <div className="text-[40px]">🧱</div>
            <p className="text-[17px] font-bold text-white/85">{t('instancesPage.noInstance')}</p>
            <p className="text-[14px] text-white/55">{t('instancesPage.mobile.emptyHint')}</p>
          </div>
        ) : (
          <>
            {favorites.length > 0 && (
              <section className="pt-2">
                <h2 className="flex h-9 items-center gap-1.5 px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-[#facc15]">
                  <span aria-hidden>★</span>
                  {t('instancesPage.favorites')}
                </h2>
                <div className="flex flex-col gap-2">{favorites.map(row)}</div>
              </section>
            )}

            {others.length > 0 && (
              <section className={favorites.length > 0 ? 'pt-6' : 'pt-2'}>
                {/* Repliable quand il y a des favoris, comme sur PC. Sans
                    animation de hauteur : on l'ouvre souvent, et animer la
                    hauteur d'une liste saccade sur un téléphone. */}
                {favorites.length > 0 ? (
                  <button
                    onClick={() => setOthersExpanded((v) => !v)}
                    aria-expanded={othersExpanded}
                    className="flex h-11 w-full items-center gap-1.5 px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55"
                  >
                    <svg viewBox="0 0 24 24" fill="currentColor" className={`h-3 w-3 transition-transform duration-150 ease-out ${othersExpanded ? 'rotate-90' : ''}`}>
                      <path d="M8 5v14l11-7z" />
                    </svg>
                    {t('instancesPage.others', { count: others.length })}
                  </button>
                ) : (
                  <h2 className="flex h-9 items-center px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">
                    {t('instancesPage.othersHeader')}
                  </h2>
                )}
                {(othersExpanded || favorites.length === 0) && (
                  <div className="flex flex-col gap-2">{others.map(row)}</div>
                )}
              </section>
            )}
          </>
        )}
      </main>

      {/* ── Les deux actions principales, sous le pouce ── */}
      <div className="flex shrink-0 gap-2 border-t border-white/[0.06] bg-bg-primary px-4 pb-[max(12px,env(safe-area-inset-bottom))] pt-3">
        <button
          onClick={() => setShowCreate(true)}
          className="press flex h-[52px] min-w-0 flex-1 items-center justify-center gap-2 rounded-2xl bg-[rgb(var(--accent))] text-[16px] font-bold text-white shadow-[0_6px_24px_rgb(var(--accent)/0.35)]"
        >
          <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
          <span className="truncate">{t('instancesPage.newInstance')}</span>
        </button>
        <button
          onClick={() => setShowImport(true)}
          className="press flex h-[52px] shrink-0 items-center justify-center gap-2 rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 text-[15px] font-semibold text-white/85"
        >
          <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
          {t('instancesPage.mobile.import')}
        </button>
      </div>

      {/* ── Le contenu d'une instance, poussé depuis la droite ── */}
      <AnimatePresence>
        {opened && (
          <motion.div
            key={opened.id}
            initial={{ transform: 'translateX(100%)' }}
            animate={{ transform: 'translateX(0%)', transition: { duration: 0.32, ease: PUSH_EASE }, transitionEnd: { transform: 'none' } }}
            exit={{ transform: 'translateX(100%)', transition: { duration: 0.22, ease: PUSH_EASE } }}
            className="absolute inset-0 z-20 flex flex-col bg-bg-primary shadow-[-12px_0_32px_rgba(0,0,0,0.5)]"
          >
            {/* Barre de navigation à la manière d'iOS et d'Android : retour à
                gauche, le titre centré (nom, puis version et loader), les
                paramètres à droite — trois zones nettes au lieu d'une ligne où
                retour, icône et nom se disputaient la place. */}
            <header className="grid shrink-0 grid-cols-[48px_1fr_48px] items-center border-b border-white/[0.06] px-1 pb-2 pt-[max(8px,env(safe-area-inset-top))]">
              <button
                onClick={closeInstance}
                aria-label={t('instancesPage.title')}
                className="press flex h-11 w-11 items-center justify-center rounded-full text-[rgb(var(--accent-text))]"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
                  <path d="M15 6l-6 6 6 6" />
                </svg>
              </button>
              <div className="flex min-w-0 flex-col items-center px-2 text-center">
                <span className="max-w-full truncate text-[16px] font-bold leading-tight text-white">{opened.name}</span>
                <span className="max-w-full truncate text-[12px] leading-tight text-white/55">{opened.mc_version} · {loaderName(opened.loader)}</span>
              </div>
              <button
                onClick={() => setSettingsTargetId(opened.id)}
                aria-label={t('instancesPage.settings')}
                className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-white/70"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" className="h-[22px] w-[22px]"><path d="M19.14 12.94a7.07 7.07 0 000-1.88l2.03-1.58a.5.5 0 00.12-.64l-1.92-3.32a.5.5 0 00-.6-.22l-2.39.96a7.03 7.03 0 00-1.63-.94l-.36-2.54a.5.5 0 00-.5-.42h-3.84a.5.5 0 00-.5.42l-.36 2.54c-.59.24-1.13.56-1.63.94l-2.39-.96a.5.5 0 00-.6.22L2.65 8.84a.5.5 0 00.12.64l2.03 1.58a7.07 7.07 0 000 1.88l-2.03 1.58a.5.5 0 00-.12.64l1.92 3.32c.13.22.39.3.6.22l2.39-.96c.5.38 1.04.7 1.63.94l.36 2.54c.04.24.25.42.5.42h3.84c.25 0 .46-.18.5-.42l.36-2.54c.59-.24 1.13-.56 1.63-.94l2.39.96c.21.08.47 0 .6-.22l1.92-3.32a.5.5 0 00-.12-.64l-2.03-1.58zM12 15.6A3.6 3.6 0 1112 8.4a3.6 3.6 0 010 7.2z" /></svg>
              </button>
            </header>
            {/* Le contenu de l'instance, le même composant que sur PC. Son
                écran à lui (mods, packs, shaders, options) sera adapté au
                téléphone à son tour. */}
            <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
              <ModsContent key={`${opened.id}-${opened.mc_version}`} instance={opened} />
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* ── Feuilles et fenêtres ── */}
      <InstanceActionSheet
        instance={actionsFor}
        onClose={() => setActionsForId(null)}
        onSettings={(inst) => setSettingsTargetId(inst.id)}
        onDuplicate={setDuplicateSource}
        onDelete={handleDelete}
      />

      {showCreate && (
        <CreateInstanceModal
          versions={releaseVersions}
          defaultRam={defaultRam}
          defaultJvmVendor={defaultJvmVendor}
          defaultJvmCustomPath={defaultJvmCustomPath}
          defaultGcPolicy={defaultGcPolicy}
          onClose={() => setShowCreate(false)}
          onCreate={(inst) => {
            addInstance(inst)
            setSelectedInstanceId(inst.id)
          }}
        />
      )}

      {settingsTarget && (
        <InstanceSettingsModal
          instance={settingsTarget}
          versions={releaseVersions}
          initialTab={settingsTab}
          onClose={() => { setSettingsTargetId(null); setSettingsTab(undefined) }}
          onUpdate={updateInstance}
          onToggleFavorite={handleToggleFavorite}
          onDuplicate={handleDuplicateNow}
          onDuplicateAs={setDuplicateSource}
          onDelete={handleDelete}
        />
      )}

      {duplicateSource && (
        <DuplicateInstanceModal
          source={duplicateSource}
          versions={releaseVersions}
          onClose={() => setDuplicateSource(null)}
          onDuplicate={(inst) => {
            addInstance(inst)
            setSelectedInstanceId(inst.id)
            setDuplicateSource(null)
          }}
        />
      )}

      {showImport && (
        <ImportSourceModal
          onClose={() => setShowImport(false)}
          onImported={(instanceId) => {
            api.instances.list().then(setInstances).catch(showError)
            setSelectedInstanceId(instanceId)
          }}
          onPickShared={() => { setShowImport(false); setShowShareImport(true) }}
        />
      )}

      {showShareImport && (
        <ShareImportModal
          onClose={() => setShowShareImport(false)}
          onImported={(inst) => {
            addInstance(inst)
            setSelectedInstanceId(inst.id)
            setShowShareImport(false)
          }}
        />
      )}
    </div>
  )
}
