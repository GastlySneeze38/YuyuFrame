import { useRef, useState, type ReactNode } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useStorePick } from '@/stores/useStore'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { formatRam } from '@/lib/format'
import { Toggle } from '@/components/ui/Toggle'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { playHalloweenLaunchSound } from '@/components/home/Halloween'
import { EASE_OUT } from '@/lib/motion'
import { useT, LANGUAGES } from '@/i18n'
import type { SharedOptionsStatus } from '@/types'

const CUSTOM_RAM_PRESETS_GO = [9, 10, 12, 16, 24, 32]

export interface SettingsCategory { id: string; label: string; icon: string }

/**
 * Les réglages sur téléphone, à la manière des Réglages d'iOS et d'Android :
 * une liste de sections, puis chaque section dans son propre écran, poussé
 * depuis la droite. La page du PC est une seule longue colonne avec un
 * menu latéral ; dans 375 px le menu ne tient pas et la colonne devient un
 * défilement sans repère.
 *
 * La section ouverte est dans l'adresse (`?section=<id>`) : le bouton retour
 * d'Android la referme. Tout l'état et les gestes viennent de
 * `pages/Settings.tsx` (ou du magasin) : cet écran ne fait qu'afficher, les
 * deux versions ne peuvent donc pas diverger sur ce qu'elles écrivent.
 *
 * Mêmes briques que l'écran Options d'une instance : listes groupées à
 * séparateurs, lignes de 56 px, interrupteur sur toute la ligne, curseurs
 * `touch-range`, texte principal 15 px, explication 13 px.
 */
export function MobileSettings(p: {
  categories: SettingsCategory[]
  sharedOptions: SharedOptionsStatus | null
  analyticsDisabled: boolean
  toggleAnalytics: () => void
  presenceVisible: boolean
  togglePresence: () => void
  dataRoot: string | null
  pendingParent: string | null
  movingData: boolean
  pickDataRoot: () => void
  confirmMoveDataRoot: () => void
  cancelMove: () => void
  /** L'interrupteur « arrière-plan » : le couper demande confirmation. */
  onBackgroundToggle: () => void
  onShare: () => void
  onOptimized: () => void
  /** Appui sur le titre de la section Langue (easter egg, 5 d'affilée). */
  onLanguageTitlePress: () => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const reduce = useReducedMotion()
  const [params, setParams] = useSearchParams()
  const sectionId = params.get('section')
  const section = p.categories.find((c) => c.id === sectionId) ?? null
  // Une section ouverte depuis la liste a ajouté une entrée d'historique : on
  // revient en arrière. Ouverte par une adresse directe, on remplace.
  const pushed = useRef(false)
  const open = (id: string) => { pushed.current = true; setParams({ section: id }) }
  const close = () => {
    if (pushed.current) { pushed.current = false; navigate(-1) }
    else setParams({}, { replace: true })
  }

  const s = useStorePick('brightness', 'customRamMb', 'defaultRam', 'language')
  const preview: Record<string, string | undefined> = {
    lancement: formatRam(s.customRamMb ?? s.defaultRam),
    langue: LANGUAGES.find((l) => l.code === s.language)?.nativeLabel,
    apparence: s.brightness === 100 ? t('settings.apparence.oledLabel') : s.brightness === 200 ? t('settings.apparence.darkModeLabel') : `${s.brightness}%`,
  }

  return (
    <div className="relative h-full overflow-hidden bg-[#09090D]">
      {/* ── Liste des sections ─────────────────────────────────────────── */}
      <div className="flex h-full flex-col" aria-hidden={!!section}>
        <header className="flex shrink-0 items-center gap-1 pb-3 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
          <MenuButton />
          <h1 className="min-w-0 flex-1 truncate text-[28px] font-bold tracking-tight text-white">{t('settings.title')}</h1>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(24px,env(safe-area-inset-bottom))]">
          <div className="flex flex-col gap-6">
            {/* Partager / paramètres optimisés : en tête, comme les boutons
                flottants du PC qui restent à portée sur toute la page. */}
            <div className="grid grid-cols-2 gap-2">
              <QuickAction label={t('settingsShare.shareButton')} onClick={p.onShare}
                icon="M18 8a3 3 0 100-6 3 3 0 000 6zM6 15a3 3 0 100-6 3 3 0 000 6zM18 22a3 3 0 100-6 3 3 0 000 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4" />
              <QuickAction label={t('settingsShare.optimizedButton')} onClick={p.onOptimized} accent icon="M13 2L4 14h7l-1 8 9-12h-7l1-8z" />
            </div>

            <Group>
              {p.categories.map((c) => (
                <button
                  key={c.id}
                  onClick={() => open(c.id)}
                  className="flex min-h-[56px] w-full items-center gap-3.5 px-4 text-left active:bg-white/[0.04]"
                >
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/20 text-accent-hover">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
                      <path d={c.icon} />
                    </svg>
                  </span>
                  <span className="min-w-0 flex-1 truncate text-[16px] text-white/90">{c.label}</span>
                  {preview[c.id] && <span className="max-w-[40%] shrink-0 truncate text-[15px] text-white/55">{preview[c.id]}</span>}
                  <Chevron />
                </button>
              ))}
            </Group>
          </div>
        </div>
      </div>

      {/* ── Section poussée ────────────────────────────────────────────── */}
      {/* Elle glisse depuis la droite et repart par la droite, plus vite :
          revenir est une réponse. La liste reste montée dessous, pour
          retrouver sa position au retour. */}
      <AnimatePresence>
        {section && (
          <motion.div
            key={section.id}
            initial={reduce ? { opacity: 0 } : { transform: 'translateX(100%)' }}
            animate={reduce ? { opacity: 1 } : { transform: 'translateX(0%)', transitionEnd: { transform: 'none' } }}
            exit={reduce ? { opacity: 0, transition: { duration: 0.15 } } : { transform: 'translateX(100%)', transition: { duration: 0.22, ease: EASE_OUT } }}
            transition={{ duration: 0.32, ease: [0.32, 0.72, 0, 1] }}
            className="absolute inset-0 z-10 flex flex-col bg-[#09090D] shadow-[-12px_0_32px_rgba(0,0,0,0.45)]"
          >
            <header className="grid shrink-0 grid-cols-[48px_1fr_48px] items-center border-b border-white/[0.06] px-1 pb-2 pt-[max(8px,env(safe-area-inset-top))]">
              <button onClick={close} aria-label={t('settings.title')} className="press flex h-11 w-11 items-center justify-center rounded-full text-[#a29bfe]">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
                  <path d="M15 6l-6 6 6 6" />
                </svg>
              </button>
              <h2
                // L'easter egg de la langue passe par le titre, comme sur PC.
                onPointerDown={section.id === 'langue' ? p.onLanguageTitlePress : undefined}
                className="truncate text-center text-[17px] font-bold text-white"
              >
                {section.label}
              </h2>
              <span />
            </header>
            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-5">
              <SectionBody id={section.id} p={p} />
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function SectionBody({ id, p }: { id: string; p: Parameters<typeof MobileSettings>[0] }) {
  const t = useT()
  const navigate = useNavigate()
  const s = useStorePick(
    'allowBackground', 'avoidBetaDependencies', 'brightness', 'closeOnLaunch', 'confirmServerLaunch', 'customRamMb',
    'defaultRam', 'instanceSyncMode', 'language', 'setAvoidBetaDependencies', 'setBrightness', 'setCloseOnLaunch',
    'setConfirmServerLaunch', 'setCustomRamMb', 'setDefaultRam', 'setInstanceSyncMode', 'setLanguage', 'setShowConsole',
    'setShowHomeServers', 'setSyncGameSettings', 'sgaUnlocked', 'showConsole', 'showHomeServers', 'soundVolume', 'setSoundVolume', 'syncGameSettings',
  )
  const [showRamInfo, setShowRamInfo] = useState(false)

  switch (id) {
    case 'lancement':
      return (
        <div className="flex flex-col gap-6">
          <Group footer={t('settings.lancement.ramDesc')}>
            <SliderRow
              label={t('settings.lancement.ramLabel')}
              value={formatRam(s.defaultRam)}
              min={2048} max={8192} step={1024}
              current={s.defaultRam}
              onChange={s.setDefaultRam}
              ticks={['2 Go', '4 Go', '6 Go', '8 Go']}
            />
          </Group>

          {/* RAM au-delà de 8 Go : prend le pas sur le curseur. */}
          <Group
            title={t('settings.lancement.customRamLabel')}
            footer={t('settings.lancement.customRamDesc')}
          >
            <div className="flex flex-col gap-3 p-4">
              <div className="flex flex-wrap gap-2">
                {CUSTOM_RAM_PRESETS_GO.map((go) => {
                  const mb = go * 1024
                  const active = s.customRamMb === mb
                  return (
                    <button
                      key={go}
                      onClick={() => s.setCustomRamMb(active ? null : mb)}
                      aria-pressed={active}
                      className={`press h-10 rounded-full border px-4 text-[15px] font-semibold transition-colors duration-150 ${
                        active ? 'border-[rgba(120,100,255,0.6)] bg-[rgba(75,63,207,0.35)] text-white' : 'border-white/[0.08] bg-white/[0.03] text-white/70'
                      }`}
                    >
                      {go} Go
                    </button>
                  )
                })}
              </div>
              <div className="flex items-center gap-2">
                <div className="flex h-12 min-w-0 flex-1 items-center gap-2 rounded-xl border border-white/[0.1] bg-black/30 px-4 focus-within:border-[rgba(120,100,255,0.6)]">
                  <input
                    type="number"
                    inputMode="numeric"
                    enterKeyHint="done"
                    min={1024}
                    step={512}
                    placeholder={t('settings.lancement.customRamPlaceholder')}
                    value={s.customRamMb ?? ''}
                    onChange={(e) => {
                      const raw = e.target.value
                      s.setCustomRamMb(raw === '' ? null : Math.max(1024, Number(raw)))
                    }}
                    className="w-full min-w-0 bg-transparent text-[16px] text-white outline-none placeholder:text-white/35 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
                  />
                  <span className="shrink-0 text-[15px] text-white/55">Mo</span>
                </div>
                {s.customRamMb !== null && (
                  <button
                    onClick={() => s.setCustomRamMb(null)}
                    className="press h-12 shrink-0 rounded-xl bg-white/[0.06] px-4 text-[15px] font-semibold text-white/75"
                  >
                    {t('settings.lancement.customRamClear')}
                  </button>
                )}
              </div>
              <button
                onClick={() => setShowRamInfo((v) => !v)}
                aria-expanded={showRamInfo}
                className="self-start text-[15px] font-semibold text-[#a99bff]"
              >
                {t('settings.lancement.ramInfoTooltip')}
              </button>
              <AnimatePresence initial={false}>
                {showRamInfo && (
                  <motion.p
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: 'auto', opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.22, ease: EASE_OUT }}
                    className="overflow-hidden text-[14px] leading-relaxed text-white/65"
                  >
                    {t('settings.lancement.ramInfoText')}
                  </motion.p>
                )}
              </AnimatePresence>
            </div>
          </Group>

          <Group footer={t('settings.lancement.jvmProfilesDesc')}>
            <NavRow label={t('settings.lancement.jvmLabel')} onClick={() => navigate('/jvm')} />
          </Group>

          <Group>
            <ToggleRow label={t('settings.lancement.hideOnLaunchLabel')} desc={t('settings.lancement.hideOnLaunchDesc')}
              checked={s.closeOnLaunch} onChange={() => s.setCloseOnLaunch(!s.closeOnLaunch)} />
            <ToggleRow label={t('settings.lancement.backgroundLabel')}
              desc={s.allowBackground ? t('settings.lancement.backgroundDesc') : t('settings.lancement.backgroundOffDesc')}
              checked={s.allowBackground} onChange={p.onBackgroundToggle} />
            <ToggleRow label={t('settings.lancement.consoleLabel')} desc={t('settings.lancement.consoleDesc')}
              checked={s.showConsole} onChange={() => s.setShowConsole(!s.showConsole)} />
          </Group>
        </div>
      )

    case 'instances':
      return (
        <div className="flex flex-col gap-6">
          <Group>
            <ToggleRow label={t('settings.instances.avoidBetaLabel')} desc={t('settings.instances.avoidBetaDesc')}
              checked={s.avoidBetaDependencies} onChange={() => s.setAvoidBetaDependencies(!s.avoidBetaDependencies)} />
            <ToggleRow label={t('settings.instances.syncGameSettingsLabel')} desc={t('settings.instances.syncGameSettingsDesc')}
              checked={s.syncGameSettings} onChange={() => s.setSyncGameSettings(!s.syncGameSettings)}
              note={s.syncGameSettings && p.sharedOptions ? (
                p.sharedOptions.exists ? (
                  <span className="text-[rgba(134,239,172,0.85)]">
                    {t('settings.instances.syncTemplateReady', {
                      count: p.sharedOptions.option_count,
                      date: p.sharedOptions.saved_at ? new Date(p.sharedOptions.saved_at * 1000).toLocaleDateString() : '—',
                    })}
                  </span>
                ) : (
                  <span className="text-[rgba(250,204,21,0.9)]">{t('settings.instances.syncTemplateMissing')}</span>
                )
              ) : undefined}
            />
          </Group>
          <Group title={t('settings.instances.startupSyncLabel')} footer={t('settings.instances.startupSyncDesc')}>
            {([
              { value: 'db_wins', label: t('settings.instances.dbWinsLabel'), desc: t('settings.instances.dbWinsDesc') },
              { value: 'disk_wins', label: t('settings.instances.diskWinsLabel'), desc: t('settings.instances.diskWinsDesc') },
            ] as const).map((o) => (
              <RadioRow key={o.value} label={o.label} desc={o.desc} active={s.instanceSyncMode === o.value} onClick={() => s.setInstanceSyncMode(o.value)} />
            ))}
          </Group>
        </div>
      )

    case 'stockage':
      return (
        <div className="flex flex-col gap-6">
          <Group title={t('settings.stockage.folderLabel')} footer={t('settings.stockage.folderDesc')}>
            <div className="flex flex-col gap-3 p-4">
              <span className="selectable break-all font-mono text-[14px] leading-relaxed text-white/75">{p.dataRoot ?? t('common.loading')}</span>
              <div className="flex gap-2">
                <button
                  onClick={() => p.dataRoot && api.system.openFolder(p.dataRoot).catch(showError)}
                  disabled={!p.dataRoot}
                  className="press h-11 min-w-0 flex-1 rounded-xl bg-white/[0.06] text-[15px] font-semibold text-white/80 disabled:opacity-40"
                >
                  {t('common.open')}
                </button>
                <button
                  onClick={p.pickDataRoot}
                  disabled={p.movingData}
                  className="press h-11 min-w-0 flex-1 rounded-xl bg-[#4B3FCF] text-[15px] font-bold text-white disabled:opacity-40"
                >
                  {t('common.change')}
                </button>
              </div>
            </div>
          </Group>
          {p.pendingParent && (
            <div className="flex flex-col gap-3 rounded-2xl border border-[rgba(250,204,21,0.25)] bg-[rgba(250,204,21,0.06)] p-4">
              <p className="text-[15px] leading-relaxed text-white/80">
                {t('settings.stockage.moveConfirmPrefix')} <span className="break-all font-mono text-white">{p.pendingParent}\YuyuFrame</span>?{' '}
                {t('settings.stockage.moveConfirmSuffix')}
              </p>
              <div className="flex gap-2">
                <button onClick={p.cancelMove} disabled={p.movingData} className="press h-11 flex-1 rounded-xl bg-white/[0.06] text-[15px] font-semibold text-white/75">
                  {t('common.cancel')}
                </button>
                <button onClick={p.confirmMoveDataRoot} disabled={p.movingData} className="press h-11 flex-1 rounded-xl bg-[#facc15] text-[15px] font-bold text-black disabled:opacity-50">
                  {p.movingData ? t('settings.stockage.moving') : t('settings.stockage.confirmMove')}
                </button>
              </div>
            </div>
          )}
        </div>
      )

    case 'langue':
      return (
        <Group footer={t('settings.langue.desc')}>
          {LANGUAGES.filter((l) => !l.secret || s.sgaUnlocked).map(({ code, nativeLabel }) => (
            <RadioRow key={code} label={nativeLabel} active={s.language === code} onClick={() => s.setLanguage(code)} />
          ))}
        </Group>
      )

    case 'serveurs':
      return (
        <Group>
          <ToggleRow label={t('settings.serveurs.showHomeLabel')} desc={t('settings.serveurs.showHomeDesc')}
            checked={s.showHomeServers}
            onChange={() => {
              s.setShowHomeServers(!s.showHomeServers)
              api.analytics.track(s.showHomeServers ? 'home_servers_setting_disabled' : 'home_servers_setting_enabled')
            }} />
          <ToggleRow label={t('settings.serveurs.confirmLaunchLabel')} desc={t('settings.serveurs.confirmLaunchDesc')}
            checked={s.confirmServerLaunch} onChange={() => s.setConfirmServerLaunch(!s.confirmServerLaunch)} />
        </Group>
      )

    case 'confidentialite':
      return (
        <Group>
          <ToggleRow label={t('settings.confidentialite.analyticsLabel')} desc={t('settings.confidentialite.analyticsDesc')}
            checked={!p.analyticsDisabled} onChange={p.toggleAnalytics} />
          <ToggleRow label={t('settings.confidentialite.presenceLabel')} desc={t('settings.confidentialite.presenceDesc')}
            checked={p.presenceVisible} onChange={p.togglePresence} />
        </Group>
      )

    case 'apparence':
      return (
        <div className="flex flex-col gap-6">
          <Group title={t('settings.apparence.displayModeLabel')}>
            {([
              { id: 'oled', label: t('settings.apparence.oledLabel'), desc: t('settings.apparence.oledDesc'), value: 100 },
              { id: 'dark', label: t('settings.apparence.darkModeLabel'), desc: t('settings.apparence.darkModeDesc'), value: 200 },
            ] as const).map((o) => (
              <RadioRow key={o.id} label={o.label} desc={o.desc} active={s.brightness === o.value} onClick={() => s.setBrightness(o.value)} />
            ))}
          </Group>
          <Group footer={t('settings.apparence.brightnessDesc')}>
            <SliderRow
              label={t('settings.apparence.brightnessLabel')}
              value={`${s.brightness}%`}
              min={40} max={200} step={5}
              current={s.brightness}
              onChange={s.setBrightness}
              ticks={[t('settings.apparence.dark'), 'OLED', t('settings.apparence.darkModeLabel')]}
            />
          </Group>
          <Group footer={t('settings.apparence.soundDesc')}>
            <SliderRow
              label={t('settings.apparence.soundLabel')}
              value={s.soundVolume === 0 ? t('settings.apparence.soundOff') : `${s.soundVolume}%`}
              min={0} max={100} step={5}
              current={s.soundVolume}
              onChange={s.setSoundVolume}
            />
            <button
              onClick={playHalloweenLaunchSound}
              disabled={s.soundVolume === 0}
              className="flex min-h-[52px] w-full items-center px-4 text-left text-[16px] font-semibold text-[#a99bff] active:bg-white/[0.04] disabled:opacity-40"
            >
              {t('settings.apparence.soundTest')}
            </button>
          </Group>
        </div>
      )

    case 'apropos':
      return (
        <div className="flex flex-col gap-6">
          <Group>
            <InfoRow label={t('settings.apropos.launcher')} value="YuyuFrame v2.0" />
            <InfoRow label={t('settings.apropos.stack')} value="Tauri · React · Rust" />
            <InfoRow label={t('settings.apropos.author')} value="Ghasty" />
          </Group>
          <Group title={t('legal.title')}>
            {([
              ['licence', t('home.legal.licence')],
              ['confidentialite', t('home.legal.confidentialite')],
              ['conditions', t('home.legal.conditions')],
            ] as const).map(([tab, label]) => (
              <NavRow key={tab} label={label} onClick={() => navigate(`/legal?tab=${tab}`)} />
            ))}
          </Group>
        </div>
      )

    default:
      return null
  }
}

/* ── Briques ───────────────────────────────────────────────────────────── */

/** Liste groupée : titre au-dessus, carte à séparateurs, explication dessous. */
function Group({ title, footer, children }: { title?: string; footer?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      {title && <h3 className="px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{title}</h3>}
      <div className="flex flex-col divide-y divide-white/[0.06] overflow-hidden rounded-2xl border border-white/[0.06] bg-white/[0.04]">
        {children}
      </div>
      {footer && <p className="px-1 text-[13px] leading-relaxed text-white/55">{footer}</p>}
    </section>
  )
}

/** Interrupteur : toute la ligne se touche. */
function ToggleRow({ label, desc, note, checked, onChange }: {
  label: string
  desc?: string
  note?: ReactNode
  checked: boolean
  onChange: () => void
}) {
  return (
    <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4 py-3">
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[15px] text-white/90">{label}</span>
        {desc && <span className="text-[13px] leading-snug text-white/55">{desc}</span>}
        {note && <span className="mt-1 text-[13px] leading-snug">{note}</span>}
      </span>
      <Toggle checked={checked} onChange={onChange} />
    </label>
  )
}

/** Choix exclusif : la ligne entière, une coche sur celle retenue. */
function RadioRow({ label, desc, active, onClick }: { label: string; desc?: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className="flex min-h-[56px] w-full items-center gap-3 px-4 py-3 text-left active:bg-white/[0.04]"
    >
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={`text-[15px] ${active ? 'font-semibold text-white' : 'text-white/90'}`}>{label}</span>
        {desc && <span className="text-[13px] leading-snug text-white/55">{desc}</span>}
      </span>
      {active && (
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-[#a99bff]"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" /></svg>
      )}
    </button>
  )
}

/** Ligne qui mène ailleurs. */
function NavRow({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button onClick={onClick} className="flex min-h-[52px] w-full items-center gap-3 px-4 text-left active:bg-white/[0.04]">
      <span className="min-w-0 flex-1 truncate text-[15px] text-white/90">{label}</span>
      <Chevron />
    </button>
  )
}

/** Curseur : nom et valeur sur une ligne, piste au doigt dessous. */
function SliderRow({ label, value, min, max, step, current, onChange, ticks }: {
  label: string
  value: string
  min: number
  max: number
  step: number
  current: number
  onChange: (v: number) => void
  ticks?: string[]
}) {
  const fill = ((current - min) / (max - min)) * 100
  return (
    <div className="flex flex-col px-4 pt-3">
      <div className="flex items-baseline justify-between gap-3">
        <span className="min-w-0 truncate text-[15px] text-white/90">{label}</span>
        <span className="shrink-0 text-[15px] font-semibold tabular-nums text-[#a99bff]">{value}</span>
      </div>
      <input
        type="range"
        min={min} max={max} step={step}
        value={current}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
        style={{ '--fill': `${Math.max(0, Math.min(100, fill))}%` } as React.CSSProperties}
        className="touch-range w-full"
      />
      {ticks && (
        <div className="-mt-1 flex justify-between pb-3 text-[13px] text-white/45">
          {ticks.map((tk) => <span key={tk}>{tk}</span>)}
        </div>
      )}
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-h-[52px] items-center justify-between gap-3 px-4">
      <span className="text-[15px] text-white/90">{label}</span>
      <span className="truncate text-[15px] text-white/55">{value}</span>
    </div>
  )
}

function QuickAction({ label, icon, onClick, accent }: { label: string; icon: string; onClick: () => void; accent?: boolean }) {
  return (
    <button
      onClick={onClick}
      className={`press flex min-h-[64px] flex-col items-start justify-center gap-1.5 rounded-2xl border px-4 py-3 text-left ${
        accent ? 'border-accent/50 bg-accent/20 text-white' : 'border-white/[0.08] bg-white/[0.04] text-white/85'
      }`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className={`h-5 w-5 ${accent ? 'text-white' : 'text-accent-hover'}`}>
        <path d={icon} />
      </svg>
      <span className="text-[15px] font-semibold leading-tight">{label}</span>
    </button>
  )
}

function Chevron() {
  return <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-white/35"><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
}
