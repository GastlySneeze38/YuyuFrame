import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Instance } from '@/types'
import { ModsContent } from '@/pages/Mods'
import { ImportSourceModal } from '@/components/import/ImportSourceModal'
import { InstanceCard } from '@/components/instances/InstanceCard'
import { CreateInstanceModal } from '@/components/instances/CreateInstanceModal'
import { InstanceSettingsModal } from '@/components/instances/InstanceSettingsModal'
import { DuplicateInstanceModal } from '@/components/instances/DuplicateInstanceModal'
import { PageHeader } from '@/components/ui/PageHeader'
import { InstanceCardSkeleton } from '@/components/ui/Skeleton'
import { SNAP, listItemVariants, listVariants, press } from '@/lib/motion'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

export default function Instances() {
  const t = useT()
  const navigate = useNavigate()
  const {
    versions, setVersions,
    instances, setInstances, addInstance, updateInstance, removeInstance,
    selectedInstanceId, setSelectedInstanceId,
    defaultRam, defaultJvmVendor, defaultJvmCustomPath, defaultGcPolicy,
  } = useStore()

  const [loading, setLoading] = useState(true)
  const [showCreate, setShowCreate] = useState(false)
  const [showImport, setShowImport] = useState(false)
  // L'instance ouverte dans les paramètres est désignée par son identifiant,
  // pas copiée : la modale montre l'étoile des favoris et le nom, qui changent
  // pendant qu'elle est ouverte. Une copie serait restée celle de l'ouverture.
  const [settingsTargetId, setSettingsTargetId] = useState<string | null>(null)
  const [duplicateSource, setDuplicateSource] = useState<Instance | null>(null)
  const [othersExpanded, setOthersExpanded] = useState(true)
  const loaded = useRef(false)

  const selectedInstance = instances.find((i) => i.id === selectedInstanceId) ?? null
  const settingsTarget = instances.find((i) => i.id === settingsTargetId) ?? null
  const favorites = instances.filter((i) => i.favorite)
  const others = instances.filter((i) => !i.favorite)

  // Deux chargements indépendants, volontairement PAS dans un `Promise.all`.
  //
  // La liste des instances vient de SQLite : elle est là en quelques
  // millisecondes. La liste des versions vient de Mojang, par le réseau.
  // Les attendre ensemble faisait payer à toute la page le temps du
  // téléchargement, alors que les versions ne servent qu'aux fenêtres de
  // création et d'édition. Désormais la page s'affiche dès la base lue, et
  // les versions arrivent derrière sans rien bloquer (le backend les garde
  // en cache et les précharge au démarrage, donc elles sont souvent déjà
  // prêtes).
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

    if (versions.length === 0) {
      // Échec silencieux : une liste de versions manquante se signale dans
      // la fenêtre de création, pas par une alerte sur un écran qui marche.
      api.versions.list().then(setVersions).catch(() => {})
    }
  }, [])

  const releaseVersions = versions
    .filter((v) => v.version_type === 'release')
    .map((v) => v.id)

  const handleDelete = useCallback(async (id: string) => {
    try {
      await api.instances.delete(id)
      removeInstance(id)
    } catch (e) { showError(e) }
  }, [removeInstance])

  const handleToggleFavorite = useCallback(async (id: string) => {
    try {
      const updated = await api.instances.toggleFavorite(id)
      updateInstance(updated)
    } catch (e) { showError(e) }
  }, [updateInstance])

  const handleSettings = useCallback((inst: Instance) => {
    setSettingsTargetId(inst.id)
  }, [])

  const handleOpenFolder = useCallback((inst: Instance) => {
    api.instances.openFolder(inst.id).catch(showError)
  }, [])

  function renderCard(inst: Instance) {
    return (
      <InstanceCard
        key={inst.id}
        instance={inst}
        selected={inst.id === selectedInstanceId}
        onSelect={setSelectedInstanceId}
        onToggleFavorite={handleToggleFavorite}
        onDelete={handleDelete}
        onSettings={handleSettings}
        onDuplicate={setDuplicateSource}
        onOpenFolder={handleOpenFolder}
      />
    )
  }

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">

      <PageHeader px={5}>
        <motion.h1
          initial={{ opacity: 0, x: -8 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
          className="font-black text-white text-[16px] tracking-[-0.01em]"
        >
          {t('instancesPage.title')}
        </motion.h1>
        <div className="flex-1" />
        {/* Seul point d'entrée vers la bibliothèque de configs en dehors de la
            modal d'édition — les configs sont transverses aux instances, elles
            n'appartiennent à aucune en particulier. */}
        <motion.button
          onClick={() => navigate('/jvm')}
          whileHover={{ y: -2 }}
          whileTap={{ scale: 0.97 }}
          transition={SNAP}
          className="h-[28px] flex-shrink-0 rounded-lg border border-line-strong bg-white/[0.06] px-2.5 text-[11px] font-semibold text-txt-secondary transition-colors hover:border-accent/40 hover:text-txt-primary"
        >
          {t('settings.lancement.jvmLabel')}
        </motion.button>
      </PageHeader>

      {/* Body: sidebar + mods panel */}
      <div className="flex flex-1 overflow-hidden">

        {/* Left sidebar — instance list */}
        <div
          className="flex flex-col overflow-hidden w-[22%] min-w-[240px] max-w-[320px] flex-shrink-0 border-r border-[rgba(255,255,255,0.06)]"
        >
          {/* Scrollable list */}
          <div className="flex flex-1 flex-col overflow-y-auto p-3">
            {loading ? (
              // Quatre silhouettes aux dimensions des vraies cartes : la liste
              // se remplit sur place au lieu de pousser le reste vers le bas.
              <div className="flex flex-col gap-2">
                {[0, 1, 2, 3].map((i) => (
                  <InstanceCardSkeleton key={i} />
                ))}
              </div>
            ) : instances.length === 0 ? (
              <motion.div
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                className="flex h-full flex-col items-center justify-center gap-2"
              >
                <div className="text-[32px]">🧱</div>
                <p className="text-[13px] text-[rgba(255,255,255,0.3)] font-semibold text-center">{t('instancesPage.noInstance')}</p>
              </motion.div>
            ) : (
              <>
                {favorites.length > 0 && (
                  <div className="mb-1">
                    <p className="flex items-center gap-1.5 px-1 pb-1.5 text-xs font-semibold text-[#facc15] tracking-[0.08em] uppercase">
                      <motion.span
                        animate={{ scale: [1, 1.18, 1], opacity: [0.75, 1, 0.75] }}
                        transition={{ duration: 3.2, repeat: Infinity, ease: 'easeInOut' }}
                      >
                        ★
                      </motion.span>
                      {t('instancesPage.favorites')}
                    </p>
                    <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-2">
                      <AnimatePresence mode="popLayout">
                        {favorites.map(renderCard)}
                      </AnimatePresence>
                    </motion.div>
                  </div>
                )}

                {others.length > 0 && (
                  <div>
                    <motion.button {...press}
                      onClick={() => setOthersExpanded((v) => !v)}
                      className="flex w-full items-center gap-1.5 px-1 pb-1.5"
                    >
                      <motion.svg
                        viewBox="0 0 24 24" fill="currentColor" width={10} height={10}
                        animate={{ rotate: othersExpanded ? 90 : 0 }}
                        transition={SNAP}
                        className="text-[rgba(255,255,255,0.3)]"
                      >
                        <path d="M8 5v14l11-7z" />
                      </motion.svg>
                      <p className="text-xs font-semibold text-[rgba(255,255,255,0.3)] tracking-[0.08em] uppercase">
                        {t('instancesPage.others', { count: others.length })}
                      </p>
                    </motion.button>
                    <AnimatePresence initial={false}>
                      {othersExpanded && (
                        <motion.div
                          initial={{ height: 0, opacity: 0 }}
                          animate={{ height: 'auto', opacity: 1 }}
                          exit={{ height: 0, opacity: 0 }}
                          transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
                          className="overflow-hidden"
                        >
                          <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-2 pb-1">
                            <AnimatePresence mode="popLayout">
                              {others.map(renderCard)}
                            </AnimatePresence>
                          </motion.div>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </div>
                )}
              </>
            )}
          </div>

          {/* Fixed bottom button */}
          <div className="flex-shrink-0 flex flex-col gap-2 p-3 border-t border-[rgba(255,255,255,0.06)]">
            {/* Le + tourne et grossit au survol, le bouton se soulève : ce sont
                les deux actions principales de l'écran, elles peuvent le dire. */}
            <motion.button
              onClick={() => setShowCreate(true)}
              whileHover="hover"
              animate="rest"
              whileTap={{ scale: 0.97 }}
              variants={{ rest: { y: 0, boxShadow: '0 4px 20px rgba(75,63,207,0.3)' }, hover: { y: -2, boxShadow: '0 8px 28px rgba(75,63,207,0.45)' } }}
              transition={SNAP}
              className="flex h-[44px] w-full items-center justify-center gap-2 rounded-xl bg-[#4B3FCF] text-[13px] font-bold text-white hover:bg-[#6155e8]"
            >
              <motion.svg
                viewBox="0 0 24 24" fill="currentColor" width={15} height={15}
                variants={{ rest: { rotate: 0, scale: 1 }, hover: { rotate: 90, scale: 1.15 } }}
                transition={{ type: 'spring', stiffness: 500, damping: 20 }}
              >
                <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
              </motion.svg>
              {t('instancesPage.newInstance')}
            </motion.button>
            <motion.button
              onClick={() => setShowImport(true)}
              whileHover="hover"
              animate="rest"
              whileTap={{ scale: 0.97 }}
              variants={{ rest: { y: 0 }, hover: { y: -2 } }}
              transition={SNAP}
              className="flex h-[38px] w-full items-center justify-center gap-2 rounded-xl bg-white/[0.05] text-[12px] font-semibold text-txt-secondary transition-colors hover:bg-white/10 hover:text-txt-primary"
            >
              {/* La flèche plonge vers le bac : le geste dit ce que fait le bouton. */}
              <motion.svg
                viewBox="0 0 24 24" fill="currentColor" width={13} height={13}
                variants={{ rest: { y: 0 }, hover: { y: 2 } }}
                transition={{ type: 'spring', stiffness: 500, damping: 16 }}
              >
                <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />
              </motion.svg>
              {t('instancesPage.importInstance')}
            </motion.button>
          </div>
        </div>

        {/* Right panel — mods */}
        <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
          {selectedInstance ? (
            <ModsContent key={`${selectedInstance.id}-${selectedInstance.mc_version}`} instance={selectedInstance} />
          ) : (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
              className="flex flex-1 flex-col items-center justify-center gap-3"
            >
              {/* La flèche va et vient vers la liste : elle désigne où cliquer
                  plutôt que de rester plantée là. */}
              <motion.div
                animate={{ x: [0, -8, 0] }}
                transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }}
                className="text-[32px] opacity-40"
              >
                ←
              </motion.div>
              <p className="text-[14px] text-[rgba(255,255,255,0.3)] font-semibold">
                {t('instancesPage.selectInstance')}
              </p>
              <p className="text-[12px] text-[rgba(255,255,255,0.15)]">
                {t('instancesPage.modsWillAppearHere')}
              </p>
            </motion.div>
          )}
        </div>
      </div>

      {/* Modals */}
      {showCreate && (
        <CreateInstanceModal
          versions={releaseVersions}
          defaultRam={defaultRam}
          defaultJvmVendor={defaultJvmVendor}
          defaultJvmCustomPath={defaultJvmCustomPath}
          defaultGcPolicy={defaultGcPolicy}
          onClose={() => setShowCreate(false)}
          // Les réglages Minecraft ne sont plus appliqués ici : c'est
          // `instance_create` qui s'en charge, pour que tout chemin de
          // création en bénéficie — y compris la restauration d'une instance
          // depuis la sauvegarde cloud, qui l'oubliait.
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
          onClose={() => setSettingsTargetId(null)}
          // La modale reste ouverte après un enregistrement : on y vient pour
          // régler plusieurs choses, et la refermer à chaque fois obligerait à
          // rouvrir le menu entre deux onglets.
          onUpdate={updateInstance}
          onToggleFavorite={handleToggleFavorite}
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
        />
      )}
    </div>
  )
}
