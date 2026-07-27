import { useEffect, useRef, useState } from 'react'
import { open as openDirPicker } from '@tauri-apps/plugin-dialog'
import { useStore } from '@/stores/useStore'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { Toggle } from '@/components/ui/Toggle'
import { useT, LANGUAGES } from '@/i18n'

export default function Settings() {
  const t = useT()
  const {
    brightness, setBrightness, defaultRam, setDefaultRam, closeOnLaunch, setCloseOnLaunch,
    instanceSyncMode, setInstanceSyncMode, avoidBetaDependencies, setAvoidBetaDependencies,
    syncGameSettings, setSyncGameSettings, showConsole, setShowConsole,
    showHomeServers, setShowHomeServers, confirmServerLaunch, setConfirmServerLaunch,
    language, setLanguage,
  } = useStore()

  const CATEGORIES = [
    { id: 'lancement', label: t('settings.categories.lancement') },
    { id: 'instances', label: t('settings.categories.instances') },
    { id: 'stockage', label: t('settings.categories.stockage') },
    { id: 'langue', label: t('settings.categories.langue') },
    { id: 'serveurs', label: t('settings.categories.serveurs') },
    { id: 'confidentialite', label: t('settings.categories.confidentialite') },
    { id: 'apparence', label: t('settings.categories.apparence') },
    { id: 'apropos', label: t('settings.categories.apropos') },
  ] as const

  const scrollRef = useRef<HTMLDivElement | null>(null)
  const sectionRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const [activeId, setActiveId] = useState<string>(CATEGORIES[0].id)

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

  const scrollToCategory = (id: string) => {
    setActiveId(id)
    sectionRefs.current[id]?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  useEffect(() => {
    const root = scrollRef.current
    if (!root) return
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((e) => e.isIntersecting)
        if (visible.length === 0) return
        const top = visible.reduce((a, b) => (a.boundingClientRect.top < b.boundingClientRect.top ? a : b))
        setActiveId(top.target.id)
      },
      { root, rootMargin: '0px 0px -70% 0px', threshold: 0 }
    )
    Object.values(sectionRefs.current).forEach((el) => el && observer.observe(el))
    return () => observer.disconnect()
  }, [])

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">

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

        {/* Sidebar de navigation — pleine hauteur, fixe (ne scrolle pas avec le contenu) */}
        <div className="flex w-[220px] shrink-0 flex-col border-r border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.02)]">
          <div className="px-5 py-4 text-[10px] uppercase tracking-[0.8px] text-[rgba(255,255,255,0.35)]">
            {t('settings.sidebarTitle')}
          </div>
          {CATEGORIES.map(({ id, label }) => {
            const active = activeId === id
            return (
              <button
                key={id}
                onClick={() => scrollToCategory(id)}
                className={
                  active
                    ? 'flex w-full items-center text-left px-5 py-3 text-[14px] font-semibold cursor-pointer bg-[rgba(75,63,207,0.16)] border-l-2 border-l-[#7b72e9] text-white'
                    : 'flex w-full items-center text-left px-5 py-3 text-[14px] font-semibold cursor-pointer bg-transparent border-l-2 border-l-transparent text-[rgba(255,255,255,0.5)] transition-colors duration-150 hover:bg-white/[0.03] hover:text-white/70'
                }
              >
                {label}
              </button>
            )
          })}
        </div>

        <div ref={scrollRef} className="min-w-0 flex-1 overflow-y-auto p-8">
        <div className="mx-auto flex max-w-2xl flex-col gap-4">

          {/* Lancement */}
          <div id="lancement" ref={(el) => { sectionRefs.current.lancement = el }}>
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
                    {defaultRam >= 1024 ? `${(defaultRam / 1024).toFixed(defaultRam % 1024 === 0 ? 0 : 1)} Go` : `${defaultRam} Mo`}
                  </span>
                </div>
                <input
                  type="range"
                  min={1024} max={16384} step={512}
                  value={defaultRam}
                  onChange={(e) => setDefaultRam(Number(e.target.value))}
                  className="w-full accent-[#4B3FCF]"
                />
                <div className="flex justify-between text-[10px] text-white/25">
                  <span>1 Go</span><span>4 Go</span><span>8 Go</span><span>12 Go</span><span>16 Go</span>
                </div>
              </div>

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
          <div id="instances" ref={(el) => { sectionRefs.current.instances = el }}>
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
          <div id="stockage" ref={(el) => { sectionRefs.current.stockage = el }}>
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
          <div id="langue" ref={(el) => { sectionRefs.current.langue = el }}>
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
              <div className="grid grid-cols-2 gap-2">
                {LANGUAGES.map(({ code, nativeLabel }) => {
                  const active = language === code
                  return (
                    <button
                      key={code}
                      onClick={() => setLanguage(code)}
                      className={`flex items-center justify-center gap-2 rounded-xl p-3 text-left transition-all duration-150 ${active ? 'bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.55)]' : 'bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]'}`}
                    >
                      <span className="font-semibold text-white text-[12px]">{nativeLabel}</span>
                    </button>
                  )
                })}
              </div>
            </div>
          </SCard>
          </div>

          {/* Serveurs */}
          <div id="serveurs" ref={(el) => { sectionRefs.current.serveurs = el }}>
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
          <div id="confidentialite" ref={(el) => { sectionRefs.current.confidentialite = el }}>
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
          <div id="apparence" ref={(el) => { sectionRefs.current.apparence = el }}>
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
          <div id="apropos" ref={(el) => { sectionRefs.current.apropos = el }}>
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

function SCard({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div
      className="rounded-2xl p-6 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]"
    >
      <div className="mb-5 flex items-center gap-3">
        <div
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-[rgba(75,63,207,0.2)] text-[#7b72e9]"
        >
          {icon}
        </div>
        <h2 className="font-bold text-white text-[14px] tracking-[0.02em]">
          {title}
        </h2>
      </div>
      {children}
    </div>
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
