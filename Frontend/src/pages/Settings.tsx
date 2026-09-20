import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { open as openDirPicker } from '@tauri-apps/plugin-dialog'
import { useStore } from '@/stores/useStore'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { formatRam } from '@/lib/format'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { Toggle } from '@/components/ui/Toggle'
import { SettingsNav, useSectionSpy } from '@/components/settings/SettingsNav'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { useT, LANGUAGES } from '@/i18n'

/** Valeurs courantes proposées en puces pour la RAM personnalisée (>8 Go) —
 * choix rapide sans taper, le champ Mo juste en dessous reste ouvert pour
 * une valeur exacte (ex: 9500 Mo). */
const CUSTOM_RAM_PRESETS_GO = [9, 10, 12, 16, 24, 32]

export default function Settings() {
  const t = useT()
  const navigate = useNavigate()
  const {
    brightness, setBrightness, defaultRam, setDefaultRam, customRamMb, setCustomRamMb,
    closeOnLaunch, setCloseOnLaunch,
    instanceSyncMode, setInstanceSyncMode, avoidBetaDependencies, setAvoidBetaDependencies,
    syncGameSettings, setSyncGameSettings, showConsole, setShowConsole,
    showHomeServers, setShowHomeServers, confirmServerLaunch, setConfirmServerLaunch,
    language, setLanguage,
  } = useStore()

  const [showRamInfo, setShowRamInfo] = useState(false)

  // Mémorisées : le repérage de section s'abonne au défilement à partir de
  // cette liste, une nouvelle à chaque rendu le réabonnerait sans arrêt.
  const CATEGORIES = useMemo(() => [
    { id: 'lancement', label: t('settings.categories.lancement'), icon: 'M5 3l14 9-14 9V3z' },
    { id: 'instances', label: t('settings.categories.instances'), icon: 'M4 5h16v6H4zM4 13h16v6H4zM8 8h.01M8 16h.01' },
    { id: 'stockage', label: t('settings.categories.stockage'), icon: 'M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7' },
    { id: 'langue', label: t('settings.categories.langue'), icon: 'M12 3a9 9 0 100 18 9 9 0 000-18zM3 12h18M12 3c2.5 2.5 3.5 5.6 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.6-3.5-9s1-6.5 3.5-9z' },
    { id: 'serveurs', label: t('settings.categories.serveurs'), icon: 'M4 5h16v5H4zM4 14h16v5H4zM8 7.5h.01M8 16.5h.01' },
    { id: 'confidentialite', label: t('settings.categories.confidentialite'), icon: 'M12 3l8 3.5v5c0 4.6-3.4 8.7-8 9.5-4.6-.8-8-4.9-8-9.5v-5L12 3z' },
    { id: 'apparence', label: t('settings.categories.apparence'), icon: 'M12 3a9 9 0 000 18c.9 0 1.6-.7 1.6-1.6 0-.4-.2-.8-.4-1.1-.3-.3-.4-.7-.4-1.1 0-.9.7-1.6 1.6-1.6H16a5 5 0 005-5c0-4.1-4-7.6-9-7.6zM7.5 12.5h.01M9.5 8.5h.01M14.5 8.5h.01' },
    { id: 'apropos', label: t('settings.categories.apropos'), icon: 'M12 3a9 9 0 100 18 9 9 0 000-18zM12 11v5M12 7.5h.01' },
  ], [t])

  const { scrollRef, sectionRefs, activeId, goTo } = useSectionSpy(CATEGORIES)

  const [analyticsDisabled, setAnalyticsDisabledState] = useState(false)
  useEffect(() => {
    api.analytics.isDisabled().then(setAnalyticsDisabledState).catch(() => {})
  }, [])
  const toggleAnalytics = () => {
    const next = !analyticsDisabled
    setAnalyticsDisabledState(next)
    api.analytics.setDisabled(next).catch(() => {})
  }

  const [dataRoot, setDataRoot] = useState<string | null>(null)
  const [pendingParent, setPendingParent] = useState<string | null>(null)
  const [movingData, setMovingData] = useState(false)
  useEffect(() => {
    api.system.getDataRoot().then(setDataRoot).catch(() => {})
  }, [])

  const pickDataRoot = async () => {
    const picked = await openDirPicker({ directory: true })
    if (!picked || Array.isArray(picked)) return
    setPendingParent(picked)
  }

  const confirmMoveDataRoot = async () => {
    if (!pendingParent) return
    setMovingData(true)
    try {
      const newRoot = await api.system.setDataRoot(pendingParent)
      setDataRoot(newRoot)
      setPendingParent(null)
    } catch (e) {
      showError(e)
    } finally {
      setMovingData(false)
    }
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-[#09090D]">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="font-black text-white text-[16px] tracking-[-0.01em] leading-[1.2]">
            {t('settings.title')}
          </h1>
          <p className="text-[10px] text-[rgba(255,255,255,0.28)] mt-px">
            {t('settings.subtitle')}
          </p>
        </div>
      </PageHeader>

      {/* Content */}
      <div className="flex flex-1 overflow-hidden">

        <SettingsNav
          categories={CATEGORIES}
          activeId={activeId}
          onPick={goTo}
          title={t('settings.sidebarTitle')}
        />

        <div ref={scrollRef} className="min-w-0 flex-1 overflow-y-auto p-8">
        <div className="mx-auto flex max-w-2xl flex-col gap-4">

          {/* Lancement */}
          <div id="lancement" ref={(el) => { sectionRefs.current.lancement = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.lancement.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M8 5v14l11-7z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* RAM par défaut */}
              <div className="flex flex-col gap-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-white">{t('settings.lancement.ramLabel')}</p>
                    <p className="text-[11px] text-white/35 mt-0.5">
                      {t('settings.lancement.ramDesc')}
                    </p>
                  </div>
                  <span className="text-sm font-bold text-[#7b72e9]">
                    {formatRam(defaultRam)}
                  </span>
                </div>
                <input
                  type="range"
                  // Bornes et pas alignés sur les paliers réellement
                  // sélectionnables dans RamPicker (2 à 8 Go, tous espacés
                  // d'1 Go) — avant, le slider allait de 1 à 16 Go par pas de
                  // 512 Mo : des valeurs comme "4.5 Go" ou "1 Go" ne
                  // correspondaient à AUCUN bouton proposé ailleurs dans le
                  // launcher (retour utilisateur). Au-delà de 8 Go, c'est le
                  // champ "RAM personnalisée" juste en dessous qui prend le
                  // relais, pas ce slider.
                  min={2048} max={8192} step={1024}
                  value={defaultRam}
                  onChange={(e) => setDefaultRam(Number(e.target.value))}
                  className="w-full accent-[#4B3FCF]"
                />
                <div className="flex justify-between text-[10px] text-white/25">
                  <span>2 Go</span><span>3 Go</span><span>4 Go</span><span>5 Go</span><span>6 Go</span><span>7 Go</span><span>8 Go</span>
                </div>
              </div>

              <div className="h-px bg-white/6" />

              {/* RAM personnalisée (>8 Go) — remplace le palier "8 Go" des
                  fenêtres de création/édition d'instance (voir RamPicker).
                  Présentée dans son propre encadré : c'est un réglage à part,
                  qui prend le pas sur le curseur juste au-dessus. */}
              <div className="rounded-xl border border-line bg-surface-2 p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex flex-col gap-1">
                    <div className="flex items-center gap-1.5">
                      <p className="text-sm font-medium text-white">{t('settings.lancement.customRamLabel')}</p>
                      <button
                        onClick={() => setShowRamInfo((v) => !v)}
                        title={t('settings.lancement.ramInfoTooltip')}
                        className={`flex h-4 w-4 items-center justify-center rounded-full border text-[10px] font-bold transition-colors ${
                          showRamInfo
                            ? 'border-accent/60 bg-accent/20 text-accent-hover'
                            : 'border-line-strong text-txt-muted hover:border-white/40 hover:text-txt-secondary'
                        }`}
                      >
                        i
                      </button>
                    </div>
                    <p className="text-[11px] leading-relaxed text-txt-muted">{t('settings.lancement.customRamDesc')}</p>
                  </div>

                  {/* La valeur retenue, en gros : c'est la seule chose à
                      vérifier d'un coup d'œil en revenant sur la page. */}
                  <AnimatePresence mode="popLayout">
                    {customRamMb !== null && customRamMb >= 1024 && (
                      <motion.span
                        key={customRamMb}
                        initial={{ opacity: 0, y: -6, scale: 0.9 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        exit={{ opacity: 0, y: 6, scale: 0.9 }}
                        transition={{ type: 'spring', stiffness: 520, damping: 30 }}
                        className="flex-none rounded-lg bg-accent/15 px-2.5 py-1 text-[13px] font-bold text-accent-hover"
                      >
                        {formatRam(customRamMb)}
                      </motion.span>
                    )}
                  </AnimatePresence>
                </div>

                <AnimatePresence initial={false}>
                  {showRamInfo && (
                    <motion.p
                      initial={{ height: 0, opacity: 0, marginTop: 0 }}
                      animate={{ height: 'auto', opacity: 1, marginTop: 12 }}
                      exit={{ height: 0, opacity: 0, marginTop: 0 }}
                      transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
                      className="overflow-hidden rounded-lg bg-black/25 p-3 text-[11px] leading-relaxed text-txt-secondary"
                    >
                      {t('settings.lancement.ramInfoText')}
                    </motion.p>
                  )}
                </AnimatePresence>

                {/* Puces de valeurs courantes : choix direct sans taper, le
                    champ juste en dessous reste là pour une valeur exacte. */}
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {CUSTOM_RAM_PRESETS_GO.map((go) => {
                    const mb = go * 1024
                    const active = customRamMb === mb
                    return (
                      <motion.button
                        key={go}
                        onClick={() => setCustomRamMb(active ? null : mb)}
                        whileHover={{ y: -2 }}
                        whileTap={{ scale: 0.95 }}
                        transition={{ type: 'spring', stiffness: 700, damping: 28, mass: 0.4 }}
                        className={`relative h-8 rounded-lg px-3 text-[12px] font-semibold transition-colors duration-150 ${
                          active ? 'text-white' : 'border border-line bg-black/25 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
                        }`}
                      >
                        {/* Le fond de la puce choisie glisse de l'une à l'autre. */}
                        {active && (
                          <motion.span
                            layoutId="custom-ram-active"
                            transition={{ type: 'spring', stiffness: 520, damping: 34 }}
                            className="absolute inset-0 rounded-lg border border-accent/60 bg-accent/30"
                          />
                        )}
                        <span className="relative">{go} Go</span>
                      </motion.button>
                    )
                  })}
                </div>

                <div className="mt-3 flex items-center gap-2">
                  <div className="flex h-10 flex-1 items-center gap-2 rounded-xl border border-line bg-black/30 pl-3 pr-2.5 transition-colors focus-within:border-accent/60">
                    <input
                      type="number"
                      inputMode="numeric"
                      min={1024}
                      step={512}
                      placeholder={t('settings.lancement.customRamPlaceholder')}
                      value={customRamMb ?? ''}
                      onChange={(e) => {
                        const raw = e.target.value
                        setCustomRamMb(raw === '' ? null : Math.max(1024, Number(raw)))
                      }}
                      // Masque les flèches natives du input[type=number] —
                      // très inégales d'un thème système à l'autre, et déjà
                      // accessibles au clavier (↑/↓) sans elles.
                      className="w-full bg-transparent text-sm text-white outline-none [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
                    />
                    <span className="flex-shrink-0 text-[11px] text-txt-muted">Mo</span>
                  </div>
                  <AnimatePresence>
                    {customRamMb !== null && (
                      <motion.button
                        initial={{ opacity: 0, width: 0 }}
                        animate={{ opacity: 1, width: 'auto' }}
                        exit={{ opacity: 0, width: 0 }}
                        onClick={() => setCustomRamMb(null)}
                        className="h-10 flex-none overflow-hidden whitespace-nowrap rounded-xl border border-line px-3 text-[12px] font-semibold text-txt-muted transition-colors hover:border-danger/40 hover:text-danger"
                      >
                        {t('settings.lancement.customRamClear')}
                      </motion.button>
                    )}
                  </AnimatePresence>
                </div>
              </div>

              <div className="h-px bg-white/6" />

              {/* JVM par défaut — le réglage détaillé vit désormais sur
                  l'écran des configurations JVM, qui sait les réutiliser d'une
                  instance à l'autre. Ici on montre ce qui s'applique et on y
                  renvoie, plutôt que de recopier un formulaire complet dans un
                  tiroir repliable. */}
              <motion.div
                whileHover="hover"
                className="flex items-center gap-4 rounded-xl border border-line bg-surface-2 p-4 transition-colors duration-200 hover:border-accent/30"
              >
                <motion.div
                  variants={{ hover: { scale: 1.08, rotate: -6 } }}
                  transition={{ type: 'spring', stiffness: 420, damping: 18 }}
                  className="flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-accent/15 text-accent-hover"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
                    <path d="M4 6h16M4 12h16M4 18h10" />
                  </svg>
                </motion.div>

                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <p className="text-sm font-medium text-white">{t('settings.lancement.jvmLabel')}</p>
                  <p className="text-[11px] leading-relaxed text-txt-muted">{t('settings.lancement.jvmProfilesDesc')}</p>
                </div>

                <Button size="sm" variant="secondary" onClick={() => navigate('/jvm')}>
                  {t('settings.lancement.jvmOpen')}
                </Button>
              </motion.div>

              <div className="h-px bg-white/6" />

              {/* Fermer au lancement */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.lancement.hideOnLaunchLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.lancement.hideOnLaunchDesc')}
                  </p>
                </div>
                <Toggle checked={closeOnLaunch} onChange={() => setCloseOnLaunch(!closeOnLaunch)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Console au lancement */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.lancement.consoleLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.lancement.consoleDesc')}
                  </p>
                </div>
                <Toggle checked={showConsole} onChange={() => setShowConsole(!showConsole)} />
              </div>
            </div>
          </SCard>
          </div>

          {/* Instances */}
          <div id="instances" ref={(el) => { sectionRefs.current.instances = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.instances.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 2L1 9l11 7 9-5.73V17h2V9L12 2zM3 13.18v4.91L12 23l9-4.91v-4.91l-9 5.73-9-5.73z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* Dépendances beta */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.instances.avoidBetaLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.instances.avoidBetaDesc')}
                  </p>
                </div>
                <Toggle checked={avoidBetaDependencies} onChange={() => setAvoidBetaDependencies(!avoidBetaDependencies)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Sync paramètres Minecraft */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.instances.syncGameSettingsLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.instances.syncGameSettingsDesc')}
                  </p>
                </div>
                <Toggle checked={syncGameSettings} onChange={() => setSyncGameSettings(!syncGameSettings)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Sync instances au démarrage */}
              <div className="flex flex-col gap-3">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.instances.startupSyncLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.instances.startupSyncDesc')}
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {([
                    { value: 'db_wins', label: t('settings.instances.dbWinsLabel'), desc: t('settings.instances.dbWinsDesc') },
                    { value: 'disk_wins', label: t('settings.instances.diskWinsLabel'), desc: t('settings.instances.diskWinsDesc') },
                  ] as const).map(({ value, label, desc }) => {
                    const active = instanceSyncMode === value
                    return (
                      <button
                        key={value}
                        onClick={() => setInstanceSyncMode(value)}
                        className={`flex flex-col gap-1 rounded-xl p-3 text-left transition-all duration-150 ${active ? 'bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.55)]' : 'bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]'}`}
                      >
                        <span className="font-semibold text-white text-[12px]">{label}</span>
                        <span className="text-[10px] text-white/35 leading-[1.4]">{desc}</span>
                      </button>
                    )
                  })}
                </div>
              </div>
            </div>
          </SCard>
          </div>

          {/* Stockage */}
          <div id="stockage" ref={(el) => { sectionRefs.current.stockage = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.stockage.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M20 6h-8l-2-2H4c-1.1 0-2 .89-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.11-.9-2-2-2z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-4">
              <div>
                <p className="text-sm font-medium text-white">{t('settings.stockage.folderLabel')}</p>
                <p className="text-[11px] text-white/35 mt-0.5">
                  {t('settings.stockage.folderDesc')}
                </p>
              </div>

              <div className="flex items-center gap-2 rounded-xl px-4 py-3 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]">
                <span className="flex-1 truncate font-mono text-[12px] text-white/60">
                  {dataRoot ?? t('common.loading')}
                </span>
                <button
                  onClick={() => dataRoot && api.system.openFolder(dataRoot).catch(showError)}
                  disabled={!dataRoot}
                  className="flex-shrink-0 rounded-lg px-3 py-1.5 text-[11px] font-semibold text-white/60 bg-white/5 hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {t('common.open')}
                </button>
                <button
                  onClick={pickDataRoot}
                  disabled={movingData}
                  className="flex-shrink-0 rounded-lg px-3 py-1.5 text-[11px] font-semibold text-white bg-[rgba(75,63,207,0.4)] hover:bg-[rgba(75,63,207,0.6)] disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {t('common.change')}
                </button>
              </div>

              {pendingParent && (
                <div className="flex flex-col gap-3 rounded-xl px-4 py-3 bg-[rgba(250,204,21,0.06)] border border-[rgba(250,204,21,0.25)]">
                  <p className="text-[12px] text-white/70">
                    {t('settings.stockage.moveConfirmPrefix')} <span className="font-mono text-white">{pendingParent}\YuyuFrame</span>?{' '}
                    {t('settings.stockage.moveConfirmSuffix')}
                  </p>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={confirmMoveDataRoot}
                      disabled={movingData}
                      className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-bold text-black bg-[#facc15] hover:bg-[#eab308] disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {movingData ? t('settings.stockage.moving') : t('settings.stockage.confirmMove')}
                    </button>
                    <button
                      onClick={() => setPendingParent(null)}
                      disabled={movingData}
                      className="rounded-lg px-3 py-1.5 text-[11px] font-semibold text-white/50 hover:text-white/80 disabled:cursor-not-allowed"
                    >
                      {t('common.cancel')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          </SCard>
          </div>

          {/* Langue */}
          <div id="langue" ref={(el) => { sectionRefs.current.langue = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.langue.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.9 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.89-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-3">
              <p className="text-sm font-medium text-white">{t('settings.langue.label')}</p>
              <p className="text-[11px] text-white/35 -mt-2">
                {t('settings.langue.desc')}
              </p>
              {/* Trois colonnes : la liste s'allonge à chaque langue ajoutée,
                  deux colonnes donnaient une colonne interminable. */}
              <div className="grid grid-cols-3 gap-2">
                {LANGUAGES.map(({ code, nativeLabel }) => {
                  const active = language === code
                  return (
                    <motion.button
                      key={code}
                      onClick={() => setLanguage(code)}
                      whileHover={{ y: -2 }}
                      whileTap={{ scale: 0.97 }}
                      transition={{ type: 'spring', stiffness: 700, damping: 28, mass: 0.4 }}
                      className="relative flex items-center justify-center gap-2 rounded-xl p-3 text-center"
                    >
                      {active ? (
                        <motion.span
                          layoutId="settings-language-active"
                          transition={{ type: 'spring', stiffness: 520, damping: 34 }}
                          className="absolute inset-0 rounded-xl border border-accent/55 bg-accent/20"
                        />
                      ) : (
                        <span className="absolute inset-0 rounded-xl border border-line bg-white/[0.03]" />
                      )}
                      <span className="relative text-[12px] font-semibold text-white">{nativeLabel}</span>
                    </motion.button>
                  )
                })}
              </div>
            </div>
          </SCard>
          </div>

          {/* Serveurs */}
          <div id="serveurs" ref={(el) => { sectionRefs.current.serveurs = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.serveurs.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M4 1h16a1 1 0 011 1v4a1 1 0 01-1 1H4a1 1 0 01-1-1V2a1 1 0 011-1zm3 2.5a1 1 0 100 2 1 1 0 000-2zM4 9h16a1 1 0 011 1v4a1 1 0 01-1 1H4a1 1 0 01-1-1v-4a1 1 0 011-1zm3 2.5a1 1 0 100 2 1 1 0 000-2zM4 17h16a1 1 0 011 1v4a1 1 0 01-1 1H4a1 1 0 01-1-1v-4a1 1 0 011-1zm3 2.5a1 1 0 100 2 1 1 0 000-2z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* Raccourcis serveurs sur l'accueil */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.serveurs.showHomeLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.serveurs.showHomeDesc')}
                  </p>
                </div>
                <Toggle checked={showHomeServers} onChange={() => {
                  setShowHomeServers(!showHomeServers)
                  api.analytics.track(showHomeServers ? 'home_servers_setting_disabled' : 'home_servers_setting_enabled')
                }} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Confirmation avant lancement direct sur un serveur */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.serveurs.confirmLaunchLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.serveurs.confirmLaunchDesc')}
                  </p>
                </div>
                <Toggle checked={confirmServerLaunch} onChange={() => setConfirmServerLaunch(!confirmServerLaunch)} />
              </div>
            </div>
          </SCard>
          </div>

          {/* Confidentialité */}
          <div id="confidentialite" ref={(el) => { sectionRefs.current.confidentialite = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.confidentialite.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* Opt-out PostHog */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">{t('settings.confidentialite.analyticsLabel')}</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    {t('settings.confidentialite.analyticsDesc')}
                  </p>
                </div>
                <Toggle checked={!analyticsDisabled} onChange={toggleAnalytics} />
              </div>
            </div>
          </SCard>
          </div>

          {/* Apparence */}
          <div id="apparence" ref={(el) => { sectionRefs.current.apparence = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.apparence.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* Mode d'affichage */}
              <div className="flex flex-col gap-3">
                <p className="text-sm font-medium text-white">{t('settings.apparence.displayModeLabel')}</p>
                <div className="grid grid-cols-2 gap-3">
                  {([
                    { id: 'oled', label: t('settings.apparence.oledLabel'), desc: t('settings.apparence.oledDesc'), value: 100, icon: '◑' },
                    { id: 'dark', label: t('settings.apparence.darkModeLabel'), desc: t('settings.apparence.darkModeDesc'), value: 200, icon: '☀' },
                  ] as const).map(({ id, label, desc, value, icon }) => {
                    const active = brightness === value
                    return (
                      <button
                        key={id}
                        onClick={() => setBrightness(value)}
                        className={`flex flex-col gap-1 rounded-xl p-4 text-left transition-all duration-150 ${active ? 'bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.55)]' : 'bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]'}`}
                      >
                        <span className="text-[18px] leading-none">{icon}</span>
                        <span className="font-semibold text-white text-[13px]">{label}</span>
                        <span className="text-[11px] text-white/35">{desc}</span>
                      </button>
                    )
                  })}
                </div>
              </div>

              <div className="h-px bg-white/6" />

              {/* Luminosité */}
              <div className="flex flex-col gap-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-white">{t('settings.apparence.brightnessLabel')}</p>
                    <p className="text-[11px] text-white/35 mt-0.5">
                      {t('settings.apparence.brightnessDesc')}
                    </p>
                  </div>
                  <span className="text-sm font-bold text-[#7b72e9]">
                    {brightness}%
                  </span>
                </div>
                <input
                  type="range"
                  min={40} max={200} step={5}
                  value={brightness}
                  onChange={(e) => setBrightness(Number(e.target.value))}
                  className="w-full accent-[#4B3FCF]"
                />
                <div className="flex justify-between text-[10px] text-white/25">
                  <span>{t('settings.apparence.dark')}</span>
                  <span>OLED</span>
                  <span>{t('settings.apparence.darkModeLabel')}</span>
                </div>
              </div>
            </div>
          </SCard>
          </div>

          {/* À propos */}
          <div id="apropos" ref={(el) => { sectionRefs.current.apropos = el }} className="scroll-mt-8">
          <SCard
            title={t('settings.apropos.title')}
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-3">
              <IRow label={t('settings.apropos.launcher')} value="YuyuFrame v2.0" />
              <IRow label={t('settings.apropos.stack')} value="Tauri · React · Rust" />
              <IRow label={t('settings.apropos.author')} value="Ghasty" />
            </div>
          </SCard>
          </div>

        </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Carte de réglages. Elle entre quand elle arrive à l'écran plutôt qu'au
 * chargement : sur une page aussi longue, tout animer d'un coup ferait
 * s'agiter des cartes que personne ne regarde. `once: false` pour que la
 * page reste vivante quand on la reparcourt.
 */
function SCard({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 14 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: false, margin: '0px 0px -10% 0px' }}
      transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
      whileHover="hover"
      className="rounded-2xl border border-line bg-surface-1 p-6 transition-colors duration-200 hover:border-accent/25"
    >
      <div className="mb-5 flex items-center gap-3">
        <motion.div
          variants={{ hover: { scale: 1.08, rotate: -5 } }}
          transition={{ type: 'spring', stiffness: 420, damping: 18 }}
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent/20 text-accent-hover"
        >
          {icon}
        </motion.div>
        <h2 className="text-[14px] font-bold tracking-[0.02em] text-white">{title}</h2>
      </div>
      {children}
    </motion.section>
  )
}


function IRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[13px] text-white/35">{label}</span>
      <span className="font-medium text-white text-[13px]">{value}</span>
    </div>
  )
}
