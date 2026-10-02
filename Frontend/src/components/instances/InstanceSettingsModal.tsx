import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { Instance, Loader, SharedOptionsStatus } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { ModalShell } from '@/components/ui/ModalShell'
import { CloseButton } from '@/components/ui/CloseButton'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { loaderColor, LOADERS } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { press } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Les réglages d'une instance, en un seul endroit.
 *
 * Avant, ils étaient éparpillés : le nom et la version dans une modale
 * « Modifier l'instance », les paramètres Minecraft dans une entrée de menu
 * sans écran, la configuration JVM sur une autre page. Chacun se trouvait par
 * un chemin différent, et on ne pouvait pas savoir ce qui était réglable sans
 * ouvrir le menu à trois points.
 *
 * Désormais une modale à onglets, sur le modèle de ce qui se fait ailleurs :
 * une colonne de sujets à gauche, le sujet ouvert à droite. Le menu de la
 * carte ne garde que les gestes qui n'ont pas de réglage — dupliquer, ouvrir
 * le dossier, supprimer — et un bouton qui mène ici.
 *
 * ── Ce qui s'enregistre, et quand ─────────────────────────────────────────
 * Les champs des trois premiers onglets forment un brouillon : ils ne partent
 * au Rust qu'au clic sur « Enregistrer », et le pied de modale n'apparaît que
 * s'il y a quelque chose à enregistrer. La raison est la version du jeu —
 * la changer relance une mise à jour des mods, ce qu'on ne déclenche pas
 * parce qu'un menu déroulant a bougé sous la souris.
 *
 * Deux exceptions, volontaires : le favori (une bascule, son effet est
 * immédiat et se défait d'un clic) et les paramètres Minecraft (deux copies
 * de fichier, pas des champs). Elles ne passent pas par le brouillon et
 * n'allument donc pas le pied.
 */
type Tab = 'general' | 'installation' | 'java' | 'game'

export function InstanceSettingsModal({
  instance,
  versions,
  onClose,
  onUpdate,
  onToggleFavorite,
}: {
  instance: Instance
  versions: string[]
  onClose: () => void
  onUpdate: (instance: Instance) => void
  /** Appliqué tout de suite, hors brouillon — voir l'en-tête du fichier. */
  onToggleFavorite: (id: string) => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('general')

  const [name, setName] = useState(instance.name)
  const [description, setDescription] = useState(instance.description)
  const [mcVersion, setMcVersion] = useState(instance.mc_version)
  const [loader, setLoader] = useState<Loader>(instance.loader)
  const [ram, setRam] = useState(instance.ram_mb)

  const [saving, setSaving] = useState(false)
  const [savingLabel, setSavingLabel] = useState(t('instancesPage.saving'))
  /** Demande de confirmation quand on ferme sur un brouillon non enregistré. */
  const [askClose, setAskClose] = useState(false)

  // Mods déjà installés : la recommandation RAM en dépend, donc elle doit les
  // connaître dès l'ouverture.
  const [modCount, setModCount] = useState(0)
  useEffect(() => {
    api.mods.list(instance.id).then((mods) => setModCount(mods.length)).catch(() => {})
  }, [instance.id])

  // Purement informatif : cette RAM existait déjà sur l'instance, donc on
  // avertit sans bloquer — contrairement à la création.
  const [ramStatus, setRamStatus] = useState<RamStatus>({ isKnownTier: true, isRecommended: true })

  // Nom de la configuration JVM reliée. Relu ici plutôt que porté par
  // l'instance : le lien est une relation, et une configuration renommée doit
  // se refléter partout sans retoucher les instances.
  const [jvmProfileName, setJvmProfileName] = useState<string | null>(null)
  useEffect(() => {
    if (!instance.jvm_profile_id) { setJvmProfileName(null); return }
    api.jvmProfiles.list()
      .then((list) => setJvmProfileName(list.find((p) => p.id === instance.jvm_profile_id)?.name ?? null))
      .catch(() => {})
  }, [instance.jvm_profile_id])

  const dirty =
    name !== instance.name ||
    description !== instance.description ||
    mcVersion !== instance.mc_version ||
    loader !== instance.loader ||
    ram !== instance.ram_mb

  const tabs = useMemo(
    () => [
      { id: 'general' as const, label: t('instancesPage.tabGeneral'), icon: <IconInfo /> },
      { id: 'installation' as const, label: t('instancesPage.tabInstallation'), icon: <IconBox /> },
      { id: 'java' as const, label: t('instancesPage.tabJava'), icon: <IconChip /> },
      { id: 'game' as const, label: t('instancesPage.tabGame'), icon: <IconSliders /> },
    ],
    [t],
  )

  const save = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    setSaving(true)
    setSavingLabel(t('instancesPage.saving'))
    try {
      // Le bloc JVM est renvoyé tel quel : il a son propre écran, et ne pas
      // le repasser le réinitialiserait au moindre renommage.
      const updated = await api.instances.update(instance.id, name.trim(), mcVersion, loader, ram, description.trim(), {
        vendor: instance.jvm_vendor,
        customPath: instance.jvm_custom_path ?? undefined,
        gcPolicy: instance.gc_policy,
        extraArgs: instance.jvm_extra_args,
        argsMode: instance.jvm_args_mode,
      })
      if (mcVersion !== instance.mc_version) {
        setSavingLabel(t('instancesPage.updatingMods'))
        await updateModsForNewVersion(instance.id, mcVersion, loader)
      }
      onUpdate(updated)
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  /** Fermer sur un brouillon demande confirmation : le pied se change en
   *  question plutôt que d'ouvrir une seconde modale par-dessus la première. */
  const requestClose = () => {
    if (dirty && !saving) setAskClose(true)
    else onClose()
  }

  return (
    <ModalShell
      onClose={requestClose}
      maxWidth="max-w-3xl"
      // Le rembourrage de la coquille est repris ici : la colonne d'onglets
      // doit toucher les bords, et sa ligne de séparation descendre d'un bout
      // à l'autre. Hauteur fixée pour que changer d'onglet ne fasse pas sauter
      // la fenêtre — c'est la liste de gauche qui est le repère, elle ne doit
      // pas bouger.
      cardStyle={{ padding: 0, overflow: 'hidden', height: 'min(620px, 86vh)' }}
    >
      <div className="flex h-full min-h-0 flex-col">
        <header className="flex shrink-0 items-center gap-3 border-b border-line px-5 py-4">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-[16px]">
            🧱
          </div>
          <div className="flex min-w-0 flex-1 items-baseline gap-2">
            <p className="truncate text-[15px] font-bold text-txt-primary">{instance.name}</p>
            <span className="shrink-0 text-txt-muted">›</span>
            <p className="shrink-0 text-[15px] font-bold text-txt-primary">{t('instancesPage.settings')}</p>
          </div>
          <CloseButton onClick={requestClose} />
        </header>

        <div className="flex min-h-0 flex-1">
          <nav className="flex w-[196px] shrink-0 flex-col gap-1 overflow-y-auto border-r border-line p-3">
            {tabs.map((item) => (
              <button
                key={item.id}
                onClick={() => setTab(item.id)}
                className={`flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-left text-[12.5px] font-semibold transition-colors ${
                  tab === item.id
                    ? 'bg-accent/20 text-txt-primary'
                    : 'text-txt-secondary hover:bg-surface-2 hover:text-txt-primary'
                }`}
              >
                <span className={tab === item.id ? 'text-accent-hover' : 'text-txt-muted'}>{item.icon}</span>
                <span className="truncate">{item.label}</span>
              </button>
            ))}
          </nav>

          <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
            {tab === 'general' && (
              <div className="flex flex-col gap-5">
                <Field label={t('instancesPage.name')}>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder={t('instancesPage.namePlaceholder')}
                    className="h-10 w-full rounded-xl border border-line bg-black/40 px-3 text-[13px] text-txt-primary outline-none transition-colors placeholder:text-txt-muted focus:border-accent/60"
                  />
                </Field>

                <Field label={t('instancesPage.description')} hint={t('instancesPage.optional')}>
                  <textarea
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder={t('instancesPage.descriptionPlaceholder')}
                    rows={3}
                    maxLength={140}
                    className="w-full resize-none rounded-xl border border-line bg-black/40 px-3 py-2 text-[13px] text-txt-primary outline-none transition-colors placeholder:text-txt-muted focus:border-accent/60"
                  />
                </Field>

                <Row
                  title={t('instancesPage.favorite')}
                  desc={t('instancesPage.favoriteHint')}
                  action={
                    <motion.button
                      {...press}
                      onClick={() => onToggleFavorite(instance.id)}
                      className={`flex h-8 w-8 items-center justify-center rounded-lg border transition-colors ${
                        instance.favorite
                          ? 'border-[rgba(250,204,21,0.45)] bg-[rgba(250,204,21,0.12)] text-[#facc15]'
                          : 'border-line bg-surface-2 text-txt-muted hover:border-line-strong hover:text-txt-secondary'
                      }`}
                      title={instance.favorite ? t('instancesPage.removeFromFavorites') : t('instancesPage.addToFavorites')}
                    >
                      <svg viewBox="0 0 24 24" fill={instance.favorite ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={instance.favorite ? 0 : 1.8} width={14} height={14}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
                      </svg>
                    </motion.button>
                  }
                />
              </div>
            )}

            {tab === 'installation' && (
              <div className="flex flex-col gap-5">
                {/* Ce qui est installé, avant ce qu'on peut changer : on vient
                    souvent ici pour lire, pas pour modifier. */}
                <div className="flex flex-col gap-2 rounded-xl border border-line bg-surface-2 p-3.5">
                  <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">
                    {t('instancesPage.installationInfo')}
                  </p>
                  <Line label={t('instancesPage.platform')}>
                    <span className="font-semibold" style={{ color: loaderColor(instance.loader) }}>
                      {label(instance.loader)}
                    </span>
                  </Line>
                  <Line label={t('instancesPage.gameVersion')}>
                    <span className="font-semibold text-txt-primary">{instance.mc_version}</span>
                  </Line>
                  <Line label={t('instancesPage.modsInstalled')}>
                    <span className="font-semibold tabular-nums text-txt-primary">{modCount}</span>
                  </Line>
                </div>

                <Field label={t('instancesPage.versionMc')}>
                  <div className="relative">
                    <select
                      value={mcVersion}
                      onChange={(e) => setMcVersion(e.target.value)}
                      className="h-10 w-full appearance-none rounded-xl border border-line bg-black/40 px-3 pr-8 text-[13px] font-medium text-txt-primary outline-none"
                    >
                      {versions.map((v) => (
                        <option key={v} value={v} className="bg-[#111118]">{v}</option>
                      ))}
                    </select>
                    <svg viewBox="0 0 10 6" fill="currentColor" width={10} height={6} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-txt-muted">
                      <path d="M0 0l5 6 5-6z" />
                    </svg>
                  </div>
                </Field>

                <Field label="Loader">
                  <div className="flex flex-wrap gap-1.5">
                    {LOADERS.map((l) => (
                      <button
                        key={l}
                        onClick={() => setLoader(l)}
                        className={`h-10 rounded-xl border px-3.5 text-[12.5px] font-semibold transition-colors ${
                          loader === l
                            ? 'border-accent/70 bg-accent/30 text-txt-primary'
                            : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
                        }`}
                      >
                        {label(l)}
                      </button>
                    ))}
                  </div>
                </Field>

                {mcVersion !== instance.mc_version && (
                  <Notice>
                    {t('instancesPage.modsWillUpdatePrefix')}{' '}
                    <span className="font-semibold text-accent-hover">{mcVersion}</span>.
                  </Notice>
                )}
              </div>
            )}

            {tab === 'java' && (
              <div className="flex flex-col gap-5">
                <Field label={t('instancesPage.ramTitle')} hint={formatRam(ram)}>
                  <RamPicker value={ram} onChange={setRam} loader={loader} modCount={modCount} onStatusChange={setRamStatus} />
                </Field>

                {ramStatus.isKnownTier && !ramStatus.isRecommended && (
                  <p className="text-[11.5px] text-[rgba(240,180,90,0.75)]">⚠ {t('instancesPage.ramNotOptimal')}</p>
                )}

                {/* Les arguments JVM ont leur propre écran : champ multi-lignes,
                    jeux de drapeaux, aperçu de la vraie ligne de commande. Un
                    onglet de modale ne porterait pas tout ça. */}
                <motion.button
                  {...press}
                  onClick={() => { onClose(); navigate(instance.jvm_profile_id ? `/jvm/${instance.jvm_profile_id}` : '/jvm') }}
                  className="flex items-center justify-between gap-3 rounded-xl border border-line bg-surface-2 px-3.5 py-3 text-left transition-colors hover:border-accent/40"
                >
                  <div className="min-w-0">
                    <p className="text-[12.5px] font-semibold text-txt-primary">{t('instancesPage.jvmConfigTitle')}</p>
                    <p className="truncate text-[11.5px] text-txt-muted">
                      {jvmProfileName ?? t('instancesPage.jvmConfigNone')}
                    </p>
                  </div>
                  <span className="shrink-0 text-[11.5px] font-semibold text-accent-hover">
                    {t('instancesPage.jvmConfigOpen')}
                  </span>
                </motion.button>
              </div>
            )}

            {tab === 'game' && <GameSettings instanceId={instance.id} />}
          </div>
        </div>

        {/* Pied de modale : il n'existe que s'il y a quelque chose à
            enregistrer, pour que l'absence de bouton veuille dire « tout est
            à jour » plutôt que « rien ne s'enregistre ici ». */}
        {(dirty || saving) && (
          <motion.footer
            initial={{ y: 12, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            className="flex shrink-0 items-center justify-between gap-3 border-t border-line bg-surface-1 px-5 py-3.5"
          >
            {askClose ? (
              <>
                <p className="text-[12px] text-txt-secondary">{t('instancesPage.discardChanges')}</p>
                <div className="flex shrink-0 gap-2">
                  <button
                    onClick={onClose}
                    className="rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary"
                  >
                    {t('instancesPage.discard')}
                  </button>
                  <button
                    onClick={() => setAskClose(false)}
                    className="rounded-xl bg-accent px-3.5 py-2 text-[12.5px] font-bold text-white"
                  >
                    {t('common.cancel')}
                  </button>
                </div>
              </>
            ) : (
              <>
                <p className="text-[12px] text-txt-muted">{t('instancesPage.unsavedChanges')}</p>
                <div className="flex shrink-0 gap-2">
                  <button
                    onClick={() => {
                      setName(instance.name)
                      setDescription(instance.description)
                      setMcVersion(instance.mc_version)
                      setLoader(instance.loader)
                      setRam(instance.ram_mb)
                    }}
                    disabled={saving}
                    className="rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:opacity-50"
                  >
                    {t('instancesPage.discard')}
                  </button>
                  <button
                    onClick={save}
                    disabled={saving}
                    className="rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-60"
                  >
                    {saving ? savingLabel : t('common.save')}
                  </button>
                </div>
              </>
            )}
          </motion.footer>
        )}
      </div>
    </ModalShell>
  )
}

/**
 * Les paramètres Minecraft de l'instance — le `options.txt` du jeu.
 *
 * Deux gestes symétriques autour d'un même fichier modèle, partagé par toutes
 * les instances : en faire le modèle, ou le recevoir. Ils vivaient dans une
 * entrée de menu (« Exporter mes paramètres ») qui n'annonçait ni l'un ni
 * l'autre, et dont le pendant — appliquer — n'existait nulle part dans
 * l'interface alors que la commande était là depuis le début.
 */
function GameSettings({ instanceId }: { instanceId: string }) {
  const t = useT()
  const [status, setStatus] = useState<SharedOptionsStatus | null>(null)
  const [busy, setBusy] = useState<'export' | 'apply' | null>(null)
  const [done, setDone] = useState<'export' | 'apply' | null>(null)

  const refresh = () => {
    api.instances.sharedOptionsStatus().then(setStatus).catch(() => setStatus(null))
  }
  useEffect(refresh, [])

  const run = async (which: 'export' | 'apply') => {
    setBusy(which)
    setDone(null)
    try {
      if (which === 'export') {
        await api.instances.exportSettings(instanceId)
      } else {
        const applied = await api.instances.applySettings(instanceId)
        if (!applied) { showError(t('instancesPage.templateNone')); return }
      }
      setDone(which)
      refresh()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5">
        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">
          {t('instancesPage.gameSettingsTitle')}
        </p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('instancesPage.gameSettingsDesc')}</p>
        <p className="mt-1 text-[11.5px] text-txt-muted">
          {status?.exists
            ? t('settings.instances.syncTemplateReady', {
                count: status.option_count,
                date: status.saved_at ? new Date(status.saved_at * 1000).toLocaleDateString() : '—',
              })
            : t('instancesPage.templateNone')}
        </p>
      </div>

      <Row
        title={t('instancesPage.exportSettings')}
        desc={t('instancesPage.exportSettingsDesc')}
        action={
          <ActionButton onClick={() => run('export')} busy={busy === 'export'} done={done === 'export'}>
            {t('common.save')}
          </ActionButton>
        }
      />

      <Row
        title={t('instancesPage.applySettings')}
        desc={t('instancesPage.applySettingsDesc')}
        action={
          <ActionButton
            onClick={() => run('apply')}
            busy={busy === 'apply'}
            done={done === 'apply'}
            disabled={!status?.exists}
          >
            {t('instancesPage.applyAction')}
          </ActionButton>
        }
      />
    </div>
  )
}

function ActionButton({
  onClick,
  busy,
  done,
  disabled,
  children,
}: {
  onClick: () => void
  busy: boolean
  done: boolean
  disabled?: boolean
  children: ReactNode
}) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      disabled={busy || disabled}
      className={`shrink-0 rounded-xl border px-3.5 py-2 text-[12px] font-semibold transition-colors ${
        done
          ? 'border-[rgba(134,239,172,0.4)] bg-[rgba(134,239,172,0.12)] text-[rgba(134,239,172,0.9)]'
          : 'border-line bg-surface-2 text-txt-secondary hover:border-accent/40 hover:text-txt-primary'
      } disabled:cursor-not-allowed disabled:opacity-40`}
    >
      {busy ? t('common.loading') : done ? t('instancesPage.done') : children}
    </button>
  )
}

/** Un champ et son intitulé — la forme de tout ce qui se saisit ici. */
function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="flex items-baseline gap-1.5 text-[10px] font-semibold uppercase tracking-[0.1em] text-txt-muted">
        {label}
        {/* Pas de suffixe d'opacité sur `txt-muted` : le jeton porte déjà son
            alpha, et `/70` donnerait une couleur invalide (voir
            `tailwind.config.js`). */}
        {hint && <span className="text-[10px] normal-case tracking-normal text-txt-muted">{hint}</span>}
      </label>
      {children}
    </div>
  )
}

/** Un réglage et son bouton, alignés — la forme de tout ce qui s'actionne. */
function Row({ title, desc, action }: { title: string; desc: string; action: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="text-[13px] font-semibold text-txt-primary">{title}</p>
        <p className="mt-0.5 text-[11.5px] leading-relaxed text-txt-secondary">{desc}</p>
      </div>
      {action}
    </div>
  )
}

/** Une ligne d'information, intitulé à gauche et valeur à droite. */
function Line({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 text-[12.5px]">
      <span className="text-txt-muted">{label}</span>
      {children}
    </div>
  )
}

function Notice({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-xl border border-accent/25 bg-accent/10 px-3 py-2.5">
      <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="shrink-0 text-accent-hover">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
      </svg>
      <p className="text-[11.5px] text-txt-secondary">{children}</p>
    </div>
  )
}

const label = (value: string) => value.charAt(0).toUpperCase() + value.slice(1)

const IconInfo = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" width={14} height={14}>
    <circle cx="12" cy="12" r="9" /><path d="M12 16v-4M12 8h.01" />
  </svg>
)
const IconBox = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
    <path d="M21 16V8l-9-5-9 5v8l9 5 9-5z" /><path d="M3.3 7.3L12 12l8.7-4.7M12 12v9" />
  </svg>
)
const IconChip = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
    <rect x="7" y="7" width="10" height="10" rx="1.5" />
    <path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2" />
  </svg>
)
const IconSliders = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" width={14} height={14}>
    <path d="M4 6h16M4 12h16M4 18h16" /><circle cx="9" cy="6" r="2" /><circle cx="15" cy="12" r="2" /><circle cx="8" cy="18" r="2" />
  </svg>
)
