import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { open as openFileDialog, save as saveFileDialog } from '@tauri-apps/plugin-dialog'
import { getCurrentWebview } from '@tauri-apps/api/webview'
import { api } from '@/api/client'
import type { HealthCheck, Instance, JavaReport, JavaStatus, Loader, LoaderVersion, OptionsSummary, SharedOptionsStatus } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { CloseButton } from '@/components/ui/CloseButton'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { loaderColor, LOADERS } from '@/lib/loader'
import { useLoadersFor } from '@/lib/loaderCompat'
import { formatRam } from '@/lib/format'
import { press } from '@/lib/motion'
import { InstanceIcon } from './InstanceIcon'
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
type Tab = 'general' | 'installation' | 'java' | 'game' | 'repair'

export function InstanceSettingsModal({
  instance,
  versions,
  onClose,
  onUpdate,
  onToggleFavorite,
  onDuplicate,
  onDuplicateAs,
  onDelete,
}: {
  instance: Instance
  versions: string[]
  onClose: () => void
  onUpdate: (instance: Instance) => void
  /** Appliqué tout de suite, hors brouillon — voir l'en-tête du fichier. */
  onToggleFavorite: (id: string) => void
  /** Copie immédiate, sans rien demander : même version, même RAM, même nom
   *  préfixé. C'est le geste courant, et il n'a besoin d'aucune réponse. */
  onDuplicate: (instance: Instance) => void
  /** Copie en changeant quelque chose — ouvre la fenêtre de duplication. */
  onDuplicateAs: (instance: Instance) => void
  onDelete: (id: string) => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('general')

  const [name, setName] = useState(instance.name)
  const [description, setDescription] = useState(instance.description)
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

  // La version du jeu et le loader n'en font pas partie : ils ont leur propre
  // enregistrement dans l'onglet Installation, parce qu'ils réinstallent des
  // fichiers au lieu de corriger un libellé.
  const dirty =
    name !== instance.name ||
    description !== instance.description ||
    ram !== instance.ram_mb

  const tabs = useMemo(
    () => [
      { id: 'general' as const, label: t('instancesPage.tabGeneral'), icon: <IconInfo /> },
      { id: 'installation' as const, label: t('instancesPage.tabInstallation'), icon: <IconBox /> },
      { id: 'java' as const, label: t('instancesPage.tabJava'), icon: <IconChip /> },
      { id: 'game' as const, label: t('instancesPage.tabGame'), icon: <IconSliders /> },
      { id: 'repair' as const, label: t('instancesPage.tabRepair'), icon: <IconWrench /> },
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
      // La version, le loader et la version de loader sont repassés tels
      // quels : ils ne se modifient que depuis l'onglet Installation, et
      // omettre `loader_version` le laisserait intact de toute façon (voir
      // `instance_update` côté Rust).
      const updated = await api.instances.update(instance.id, name.trim(), instance.mc_version, instance.loader, ram, description.trim(), {
        vendor: instance.jvm_vendor,
        customPath: instance.jvm_custom_path ?? undefined,
        gcPolicy: instance.gc_policy,
        extraArgs: instance.jvm_extra_args,
        argsMode: instance.jvm_args_mode,
      })
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
          <InstanceIcon instance={instance} size={36} />
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
                {/* Le nom et l'icône côte à côte : ce sont les deux faces de
                    la même chose — comment l'instance se reconnaît dans la
                    liste. */}
                <div className="flex items-start gap-4">
                  <div className="flex min-w-0 flex-1 flex-col gap-5">
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
                  </div>

                  <IconField instance={instance} onUpdate={onUpdate} />
                </div>

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

                <Separator />

                {/* Les mêmes gestes que le menu à trois points de la carte,
                    volontairement : on ouvre les paramètres pour s'occuper
                    d'une instance, et refermer pour retrouver un menu ailleurs
                    n'a pas de sens. Le menu reste le raccourci, ceci est
                    l'endroit où tout est écrit. */}
                <Row
                  title={t('instancesPage.duplicate')}
                  desc={t('instancesPage.duplicateHint')}
                  action={
                    <div className="flex shrink-0 gap-2">
                      <button
                        onClick={() => onDuplicate(instance)}
                        className="flex items-center gap-1.5 rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:border-accent/40 hover:text-txt-primary"
                      >
                        <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
                          <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
                        </svg>
                        {t('instancesPage.duplicate')}
                      </button>
                      <button
                        onClick={() => onDuplicateAs(instance)}
                        className="rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:border-accent/40 hover:text-txt-primary"
                      >
                        {t('instancesPage.duplicateAs')}
                      </button>
                    </div>
                  }
                />

                <Separator />

                <Row
                  title={t('instancesPage.deleteForever')}
                  desc={t('instancesPage.deleteHint')}
                  action={<DeleteButton onConfirm={() => onDelete(instance.id)} />}
                />
              </div>
            )}

            {tab === 'installation' && (
              <InstallationTab instance={instance} versions={versions} onUpdate={onUpdate} />
            )}

            {tab === 'java' && (
              <div className="flex flex-col gap-5">
                <JavaField instance={instance} />

                <Separator />

                <Field label={t('instancesPage.ramTitle')} hint={formatRam(ram)}>
                  <RamPicker value={ram} onChange={setRam} loader={instance.loader} modCount={modCount} onStatusChange={setRamStatus} />
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

            {tab === 'repair' && <RepairTab instance={instance} />}
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

      <Separator />

      <OptionsTransfer instanceId={instanceId} />
    </div>
  )
}

/**
 * Emporter et reprendre **toutes** les options de l'instance.
 *
 * Le modèle partagé au-dessus ne connaît qu'`options.txt` — ce qu'il faut pour
 * « mes réglages de base partout », et trop peu pour « donne-moi ta
 * configuration » : dans un modpack, l'essentiel des réglages vit dans
 * `config/`, un fichier par mod. D'où une archive, qui se range dans les
 * téléchargements et se redonne telle quelle.
 *
 * L'importation accepte le dépôt direct d'un fichier sur la zone. C'est le
 * geste naturel quand on reçoit une configuration, et ça évite d'aller la
 * rechercher dans un sélecteur alors qu'elle est déjà sous la souris.
 */
function OptionsTransfer({ instanceId }: { instanceId: string }) {
  const t = useT()
  const [summary, setSummary] = useState<OptionsSummary | null>(null)
  const [busy, setBusy] = useState<'export' | 'import' | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [hovering, setHovering] = useState(false)
  /** Rectangle de la zone de dépôt, pour savoir si le fichier est au-dessus
   *  d'elle : l'événement Tauri donne une position dans la fenêtre, pas un
   *  survol d'élément — la webview ne voit pas ce glisser-déposer. */
  const dropRef = useRef<HTMLDivElement>(null)

  const refresh = () => {
    api.instances.optionsSummary(instanceId).then(setSummary).catch(() => setSummary(null))
  }
  useEffect(refresh, [instanceId])

  const importFrom = async (path: string) => {
    setBusy('import')
    setResult(null)
    try {
      const count = await api.instances.importOptions(instanceId, path)
      setResult(t('instancesPage.optionsImported', { count }))
      refresh()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  // Le glisser-déposer passe par Tauri et non par le DOM : la webview a le
  // sien désactivé (c'est le défaut de Tauri 2), et c'est tant mieux — son
  // événement à lui ne donnerait pas le chemin du fichier, seulement son
  // contenu, qu'il faudrait relire pour rien.
  useEffect(() => {
    let unlisten: (() => void) | undefined
    let cancelled = false

    const over = (position: { x: number; y: number }) => {
      const box = dropRef.current?.getBoundingClientRect()
      if (!box) return false
      // La position vient en pixels physiques : on la ramène dans le repère
      // de la page, sinon la zone est fausse dès que l'affichage est agrandi.
      const x = position.x / window.devicePixelRatio
      const y = position.y / window.devicePixelRatio
      return x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
    }

    getCurrentWebview()
      .onDragDropEvent((event) => {
        if (event.payload.type === 'over') {
          setHovering(over(event.payload.position))
          return
        }
        if (event.payload.type === 'drop') {
          const inside = over(event.payload.position)
          setHovering(false)
          // Un seul fichier : l'archive OU le `options.txt`. En déposer
          // plusieurs ne veut rien dire ici, et en choisir un au hasard serait
          // pire que de ne rien faire.
          const [file] = event.payload.paths
          if (inside && file) void importFrom(file)
          return
        }
        setHovering(false)
      })
      .then((fn) => {
        if (cancelled) fn()
        else unlisten = fn
      })
      .catch(() => {})

    return () => {
      cancelled = true
      unlisten?.()
    }
  }, [instanceId])

  const exportAll = async () => {
    const path = await saveFileDialog({
      defaultPath: `options-${instanceId}.zip`,
      filters: [{ name: 'Archive', extensions: ['zip'] }],
    })
    if (!path) return
    setBusy('export')
    setResult(null)
    try {
      const count = await api.instances.exportOptions(instanceId, path)
      setResult(t('instancesPage.optionsExported', { count }))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const pick = async () => {
    const picked = await openFileDialog({
      filters: [{ name: t('instancesPage.optionsFile'), extensions: ['zip', 'txt'] }],
    })
    if (typeof picked === 'string') await importFrom(picked)
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5">
        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">
          {t('instancesPage.optionsTransferTitle')}
        </p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">
          {t('instancesPage.optionsTransferDesc')}
        </p>
        <p className="mt-1 text-[11.5px] text-txt-muted">
          {summary === null
            ? t('common.loading')
            : summary.files === 0
              ? t('instancesPage.optionsNone')
              : t('instancesPage.optionsCount', { files: summary.files, config: summary.config_files })}
        </p>
      </div>

      <Row
        title={t('instancesPage.optionsDownload')}
        desc={t('instancesPage.optionsDownloadDesc')}
        action={
          <ActionButton onClick={exportAll} busy={busy === 'export'} done={false} disabled={summary?.files === 0}>
            {t('instancesPage.optionsDownloadAction')}
          </ActionButton>
        }
      />

      {/* La zone de dépôt EST le bouton d'importation : deux surfaces pour le
          même geste demanderaient de choisir laquelle utiliser. */}
      <button
        ref={dropRef as unknown as React.RefObject<HTMLButtonElement>}
        onClick={pick}
        disabled={busy !== null}
        className={`flex flex-col items-center gap-1 rounded-xl border border-dashed px-4 py-5 text-center transition-colors ${
          hovering
            ? 'border-accent bg-accent/15'
            : 'border-line-strong bg-surface-2 hover:border-accent/50'
        } disabled:opacity-50`}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={18} height={18} className={hovering ? 'text-accent-hover' : 'text-txt-muted'}>
          <path d="M12 16V4M7 9l5-5 5 5M4 17v2a1 1 0 001 1h14a1 1 0 001-1v-2" />
        </svg>
        <span className="text-[12.5px] font-semibold text-txt-primary">
          {busy === 'import' ? t('common.loading') : t('instancesPage.optionsImport')}
        </span>
        <span className="text-[11px] leading-snug text-txt-muted">{t('instancesPage.optionsImportHint')}</span>
      </button>

      {result && <p className="text-[12px] text-[rgba(134,239,172,0.85)]">{result}</p>}
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

/**
 * L'emplacement du Java de cette instance.
 *
 * La version requise n'est pas un choix : elle vient de la version du jeu (et
 * du loader). L'écran l'annonce donc dans son titre — « Java 21 » — plutôt que
 * de la faire sélectionner, et les trois boutons ne portent que sur le
 * *chemin* : installer le runtime recommandé, en détecter un déjà présent sur
 * la machine, ou en désigner un à la main.
 *
 * Le launcher faisait déjà tout cela seul au lancement ; c'est précisément le
 * problème que cet écran règle : quand la résolution échoue, rien ne le disait
 * et le jeu ne démarrait pas.
 */
function JavaField({ instance }: { instance: Instance }) {
  const t = useT()
  const [status, setStatus] = useState<JavaStatus | null>(null)
  const [report, setReport] = useState<JavaReport | null>(null)
  const [customOpen, setCustomOpen] = useState(false)
  const [busy, setBusy] = useState<'install' | 'detect' | 'browse' | 'analyse' | null>(null)

  const load = () => {
    api.instances.javaStatus(instance.id).then(setStatus).catch(() => setStatus(null))
  }
  // Rechargé quand la version du jeu ou le loader change : c'est ce couple qui
  // décide de la version requise.
  useEffect(load, [instance.id, instance.mc_version, instance.loader, instance.jvm_custom_path])

  const apply = async (path: string | null) => {
    setReport(null)
    try {
      setStatus(await api.instances.setJavaPath(instance.id, path))
    } catch (e) {
      showError(e)
    }
  }

  /** Le rapport est jeté dès qu'on touche au chemin : il décrirait une
   *  installation qui n'est plus celle qu'on regarde. */
  const analyse = async () => {
    setBusy('analyse')
    try {
      setReport(await api.instances.inspectJava(instance.id))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const install = async () => {
    setBusy('install')
    setReport(null)
    try {
      setStatus(await api.instances.installJava(instance.id))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const detect = async () => {
    setBusy('detect')
    try {
      const found = await api.instances.javaDetect(instance.id)
      if (!found) { showError(t('instancesPage.javaNotFound')); return }
      await apply(found)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const browse = async () => {
    const picked = await openFileDialog({
      // Sur Windows l'exécutable s'appelle `java.exe` ; ailleurs il n'a pas
      // d'extension, et un filtre en imposerait une qui n'existe pas.
      filters: navigator.userAgent.includes('Windows')
        ? [{ name: 'Java', extensions: ['exe'] }]
        : undefined,
    })
    if (typeof picked !== 'string') return
    setBusy('browse')
    try {
      // On interroge la JVM avant de l'enregistrer : désigner un fichier qui
      // n'est pas un java, ou qui est de la mauvaise version, se verrait
      // sinon au prochain lancement seulement.
      const major = await api.instances.probeJava(picked)
      if (major === null) { showError(t('instancesPage.javaInvalid')); return }
      if (status && major !== status.required_major) {
        showError(t('instancesPage.javaWrongVersion', { found: major, required: status.required_major }))
        return
      }
      await apply(picked)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const label = status ? t('instancesPage.javaLocation', { major: status.required_major }) : t('instancesPage.java')

  return (
    <div className="flex flex-col gap-2">
      <p className="text-[13px] font-semibold text-txt-primary">{label}</p>

      <div className="flex items-center gap-2.5">
        <div className="flex h-10 min-w-0 flex-1 items-center rounded-xl border border-line bg-black/40 px-3">
          <span
            dir="rtl"
            className={`truncate text-left text-[12.5px] ${status?.path ? 'text-txt-secondary' : 'text-txt-muted'}`}
            title={status?.path ?? undefined}
          >
            {/* De droite à gauche : sur un chemin trop long, c'est le nom du
                fichier qui compte, pas le début de l'arborescence. */}
            {status?.path ?? t('instancesPage.javaNone')}
          </span>
        </div>
        <StatusDot ok={status?.ok ?? false} />
      </div>

      {status && status.path && !status.ok && (
        <p className="text-[11.5px] text-warning">
          {status.detected_major
            ? t('instancesPage.javaWrongVersion', { found: status.detected_major, required: status.required_major })
            : t('instancesPage.javaInvalid')}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <SmallAction onClick={install} busy={busy === 'install'} disabled={busy !== null}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" width={13} height={13}>
            <path d="M12 3v12M7 11l5 5 5-5M5 20h14" />
          </svg>
          {t('instancesPage.javaInstall')}
        </SmallAction>
        <SmallAction onClick={detect} busy={busy === 'detect'} disabled={busy !== null}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" width={13} height={13}>
            <circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" />
          </svg>
          {t('instancesPage.javaDetect')}
        </SmallAction>
        <SmallAction onClick={browse} busy={busy === 'browse'} disabled={busy !== null}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" width={13} height={13}>
            <path d="M3 7h6l2 2h10v10H3z" />
          </svg>
          {t('instancesPage.javaBrowse')}
        </SmallAction>
        {/* Le pendant de « Réparer » pour Java : le lancement se contente de
            trouver un exécutable, alors qu'une extraction interrompue laisse
            un java sans sa bibliothèque de machine virtuelle — présent, et
            incapable de démarrer. */}
        <SmallAction onClick={analyse} busy={busy === 'analyse'} disabled={busy !== null || !status?.path}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" width={13} height={13}>
            <path d="M3 13h4l2 5 4-12 2 7h6" />
          </svg>
          {t('instancesPage.javaAnalyse')}
        </SmallAction>
        <SmallAction onClick={() => setCustomOpen(true)} busy={false} disabled={busy !== null}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" width={13} height={13}>
            <path d="M12 3v12M7 11l5 5 5-5M5 20h14" /><circle cx="18" cy="6" r="2.5" />
          </svg>
          {t('instancesPage.javaCustomInstall')}
        </SmallAction>
        {/* Seulement quand il y a quelque chose à retirer : un bouton
            « automatique » alors qu'on y est déjà n'apprendrait rien. */}
        {status?.source === 'custom' && (
          <SmallAction onClick={() => apply(null)} busy={false} disabled={busy !== null}>
            {t('instancesPage.javaAuto')}
          </SmallAction>
        )}
      </div>

      {/* Le rapport reste affiché jusqu'au geste suivant : on l'a demandé, il
          n'a pas à disparaître tout seul. */}
      {report && (
        <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3">
          <ReportLine ok={report.exists} label={t('instancesPage.javaCheckExists')} />
          <ReportLine
            ok={report.complete !== false}
            label={t('instancesPage.javaCheckComplete')}
            note={report.complete === null ? t('instancesPage.javaCheckNotApplicable') : undefined}
          />
          <ReportLine
            ok={report.major === report.required_major}
            label={t('instancesPage.javaCheckResponds')}
            note={report.major ? `Java ${report.major}` : t('instancesPage.javaCheckNoAnswer')}
          />
        </div>
      )}

      <AnimatePresence>
        {customOpen && (
          <CustomJavaModal
            instanceId={instance.id}
            requiredMajor={status?.required_major ?? 21}
            onClose={() => setCustomOpen(false)}
            onInstalled={(next) => { setStatus(next); setReport(null); setCustomOpen(false) }}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

/** Une ligne du rapport : ce qui a été vérifié, et le verdict. */
function ReportLine({ ok, label, note }: { ok: boolean; label: string; note?: string }) {
  return (
    <div className="flex items-center gap-2.5 text-[12px]">
      <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${ok ? 'bg-[rgb(134,239,172)]' : 'bg-danger'}`} />
      <span className="flex-1 text-txt-secondary">{label}</span>
      {note && <span className="shrink-0 text-[11px] text-txt-muted">{note}</span>}
    </div>
  )
}

/**
 * Installer une version de Java choisie.
 *
 * Deux décisions, et pas une de plus : qui le publie, et quelle version
 * majeure. Le reste — système d'exploitation, architecture, build — se déduit
 * de la machine, et le demander ne ferait que multiplier les façons de se
 * tromper.
 *
 * La version requise par l'instance est proposée en premier et marquée :
 * installer autre chose est légitime (tester une JVM plus récente), mais ça
 * ne doit pas se faire par inadvertance, d'où l'avertissement quand on s'en
 * écarte.
 */
function CustomJavaModal({
  instanceId,
  requiredMajor,
  onClose,
  onInstalled,
}: {
  instanceId: string
  requiredMajor: number
  onClose: () => void
  onInstalled: (status: JavaStatus) => void
}) {
  const t = useT()
  const [vendor, setVendor] = useState<'hotspot' | 'openj9'>('hotspot')
  const [major, setMajor] = useState(requiredMajor)
  const [busy, setBusy] = useState(false)

  // Les versions à support long, plus celle qu'exige l'instance si elle n'y
  // est pas : une liste figée laisserait de côté la seule qui compte vraiment.
  const majors = [...new Set([requiredMajor, 8, 11, 17, 21, 25])].sort((a, b) => a - b)

  const install = async () => {
    setBusy(true)
    try {
      onInstalled(await api.instances.installCustomJava(instanceId, major, vendor))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <ModalShell title={t('instancesPage.javaCustomInstall')} onClose={onClose} maxWidth="max-w-md">
      <div className="flex flex-col gap-4">
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">
          {t('instancesPage.javaCustomDesc')}
        </p>

        <Field label={t('instancesPage.javaVendor')}>
          <div className="flex gap-1.5">
            {([
              { id: 'hotspot' as const, label: 'Eclipse Temurin' },
              { id: 'openj9' as const, label: 'OpenJ9' },
            ]).map((v) => (
              <button
                key={v.id}
                onClick={() => setVendor(v.id)}
                className={`h-10 flex-1 rounded-xl border text-[12.5px] font-semibold transition-colors ${
                  vendor === v.id
                    ? 'border-accent/70 bg-accent/30 text-txt-primary'
                    : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
                }`}
              >
                {v.label}
              </button>
            ))}
          </div>
        </Field>

        <Field label={t('instancesPage.javaMajor')}>
          <Select
            value={String(major)}
            onChange={(v) => setMajor(Number(v))}
            options={majors.map((m) => ({
              value: String(m),
              label: m === requiredMajor
                ? t('instancesPage.javaMajorRequired', { major: m })
                : `Java ${m}`,
            }))}
          />
        </Field>

        {major !== requiredMajor && (
          <div className="flex gap-2.5 rounded-xl border border-warning/35 bg-warning/10 p-3">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" width={15} height={15} className="mt-px shrink-0 text-warning">
              <path d="M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" />
            </svg>
            <p className="text-[11.5px] leading-relaxed text-txt-secondary">
              {t('instancesPage.javaMajorMismatch', { major, required: requiredMajor })}
            </p>
          </div>
        )}

        <Button variant="primary" onClick={install} loading={busy} fullWidth>
          {t('instancesPage.javaCustomAction')}
        </Button>
      </div>
    </ModalShell>
  )
}

/** La pastille d'état, à droite du chemin — verte ou rouge, rien d'autre. */
function StatusDot({ ok }: { ok: boolean }) {
  return ok ? (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" width={18} height={18} className="shrink-0 text-[rgb(134,239,172)]">
      <circle cx="12" cy="12" r="9" /><path d="M8.5 12.5l2.5 2.5 4.5-5" />
    </svg>
  ) : (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" width={18} height={18} className="shrink-0 text-danger">
      <circle cx="12" cy="12" r="9" /><path d="M9 9l6 6M15 9l-6 6" />
    </svg>
  )
}

function SmallAction({
  onClick,
  busy,
  disabled,
  children,
}: {
  onClick: () => void
  busy: boolean
  disabled?: boolean
  children: ReactNode
}) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="flex items-center gap-1.5 rounded-xl border border-line bg-surface-2 px-3 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:border-accent/40 hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40"
    >
      {busy ? t('common.loading') : children}
    </button>
  )
}

/**
 * L'état de l'installation, fichier par fichier.
 *
 * Ce que le lancement vérifie déjà, et ce qu'il laisse passer : au lancement,
 * un fichier est retéléchargé s'il manque ou n'a pas la bonne taille. Un
 * fichier de la bonne taille et du mauvais contenu passe, et le jeu démarre
 * sur une erreur Java incompréhensible. Cet écran vérifie donc les empreintes,
 * ce que le lancement ne fait pas — d'où un examen qui prend quelques secondes
 * et qui n'est **pas** lancé tout seul à l'ouverture de l'onglet : c'est un
 * geste, pas une page d'accueil.
 */
function RepairTab({ instance }: { instance: Instance }) {
  const t = useT()
  const [checks, setChecks] = useState<HealthCheck[] | null>(null)
  const [busy, setBusy] = useState<'scan' | 'repair' | null>(null)
  const [repaired, setRepaired] = useState<number | null>(null)

  const scan = async () => {
    setBusy('scan')
    setRepaired(null)
    try {
      setChecks(await api.instances.diagnose(instance.id))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const repair = async () => {
    setBusy('repair')
    try {
      const count = await api.instances.repair(instance.id)
      setRepaired(count)
      // Rediagnostiquer tout de suite : annoncer « réparé » sans le
      // revérifier serait une promesse, pas un constat.
      setChecks(await api.instances.diagnose(instance.id))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const problems = checks?.filter((c) => c.status === 'broken' || c.status === 'missing') ?? []

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5 rounded-xl border border-line bg-surface-2 p-3.5">
        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">
          {t('instancesPage.repairTitle')}
        </p>
        <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('instancesPage.repairDesc')}</p>
        <p className="mt-1 text-[11.5px] text-txt-muted">{t('instancesPage.repairSafe')}</p>
      </div>

      {checks === null ? (
        <button
          onClick={scan}
          disabled={busy !== null}
          className="w-fit rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-60"
        >
          {busy === 'scan' ? t('instancesPage.scanning') : t('instancesPage.scan')}
        </button>
      ) : (
        <>
          <div className="flex flex-col gap-2">
            {checks.map((check) => (
              <CheckRow key={check.id} check={check} />
            ))}
          </div>

          {repaired !== null && (
            <p className="text-[12px] text-[rgba(134,239,172,0.85)]">
              {repaired === 0 ? t('instancesPage.repairNothing') : t('instancesPage.repairDone', { count: repaired })}
            </p>
          )}

          <div className="flex gap-2">
            <button
              onClick={repair}
              disabled={busy !== null || problems.length === 0}
              className="rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40"
            >
              {busy === 'repair' ? t('instancesPage.repairing') : t('instancesPage.repair')}
            </button>
            <button
              onClick={scan}
              disabled={busy !== null}
              className="rounded-xl border border-line bg-surface-2 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:opacity-50"
            >
              {busy === 'scan' ? t('instancesPage.scanning') : t('instancesPage.scanAgain')}
            </button>
          </div>
        </>
      )}
    </div>
  )
}

/** Une ligne de diagnostic : ce qui a été examiné, et ce qu'on y a trouvé. */
function CheckRow({ check }: { check: HealthCheck }) {
  const t = useT()
  const tone = {
    ok: { dot: 'bg-[rgb(134,239,172)]', text: 'text-[rgba(134,239,172,0.9)]' },
    broken: { dot: 'bg-danger', text: 'text-danger' },
    missing: { dot: 'bg-warning', text: 'text-warning' },
    unknown: { dot: 'bg-txt-muted', text: 'text-txt-muted' },
  }[check.status]

  const summary =
    check.status === 'ok'
      ? check.total > 1
        ? t('instancesPage.checkOkCount', { count: check.total })
        : t('instancesPage.checkOk')
      : check.status === 'broken'
        ? t('instancesPage.checkBroken', { count: check.broken })
        : check.status === 'missing'
          ? t('instancesPage.checkMissing')
          : t('instancesPage.checkUnknown')

  return (
    <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-2 px-3.5 py-2.5">
      <span className={`h-2 w-2 shrink-0 rounded-full ${tone.dot}`} />
      <div className="min-w-0 flex-1">
        <p className="text-[12.5px] font-semibold text-txt-primary">{t(`instancesPage.check_${check.id}`)}</p>
        {check.detail && <p className="truncate text-[11px] text-txt-muted">{check.detail}</p>}
      </div>
      <span className={`shrink-0 text-[11.5px] font-semibold ${tone.text}`}>{summary}</span>
    </div>
  )
}

/**
 * L'installation : ce qui est installé, et comment en changer.
 *
 * Elle a son propre enregistrement, séparé du brouillon des autres onglets,
 * parce que ce n'est pas la même nature de geste. Renommer une instance se
 * défait ; changer sa version de jeu, son loader ou sa version de loader
 * réinstalle des fichiers et peut rendre injouable ce qui marchait — d'où la
 * lecture d'abord, un bouton « Modifier » pour entrer dans le formulaire, et
 * un avertissement qui s'affiche à ce moment-là plutôt que d'être un décor
 * permanent qu'on ne lit plus.
 *
 * La mise à jour des mods est **un choix**, pas une conséquence : changer de
 * version relançait la recherche d'une version compatible de chaque mod, sans
 * rien demander. C'est ce qu'on veut la plupart du temps, pas toujours.
 */
function InstallationTab({
  instance,
  versions,
  onUpdate,
}: {
  instance: Instance
  versions: string[]
  onUpdate: (instance: Instance) => void
}) {
  const t = useT()
  const [editing, setEditing] = useState(false)
  const [mcVersion, setMcVersion] = useState(instance.mc_version)
  const [loader, setLoader] = useState<Loader>(instance.loader)
  const [loaderVersion, setLoaderVersion] = useState(instance.loader_version)
  const [updateMods, setUpdateMods] = useState(true)
  /** Effacer les mods en passant à vanilla. Décoché par défaut : la case
   *  propose une suppression, et une suppression ne se propose pas cochée. */
  const [wipeMods, setWipeMods] = useState(false)
  const [saving, setSaving] = useState(false)
  const [savingLabel, setSavingLabel] = useState('')

  /** Versions du loader proposées — `null` tant qu'on ne les a pas. */
  const [loaderVersions, setLoaderVersions] = useState<LoaderVersion[] | null>(null)
  /** Loaders qui existent pour la version du jeu choisie — `null` en attente.
   *  Tant qu'on ne sait pas, on les propose tous : retirer un bouton puis le
   *  remettre une seconde plus tard serait pire que de ne rien filtrer. */
  const available = useLoadersFor(mcVersion)

  // Rechargées à chaque changement de loader ou de version de jeu : une liste
  // de builds n'a de sens que pour un couple précis. Le compteur de génération
  // écarte la réponse d'un couple qu'on vient de quitter — sans lui, une
  // réponse lente écraserait la liste du couple courant.
  // Chargées dès l'ouverture de l'onglet et pas seulement en édition : la vue
  // de lecture annonce elle aussi la version recommandée, puisque c'est elle
  // que le launcher installe quand rien n'est épinglé.
  const generation = useRef(0)
  useEffect(() => {
    const mine = ++generation.current
    setLoaderVersions(null)
    if (loader === 'vanilla') { setLoaderVersions([]); return }
    api.versions.loader(loader, mcVersion)
      .then((list) => { if (generation.current === mine) setLoaderVersions(list) })
      .catch(() => { if (generation.current === mine) setLoaderVersions([]) })
  }, [loader, mcVersion])

  // Changer de version du jeu peut faire disparaître le loader choisi — passer
  // de 1.21 à 1.19 retire NeoForge. On retombe alors sur vanilla, le seul qui
  // existe partout, plutôt que de laisser un bouton sélectionné qui n'est plus
  // proposé.
  useEffect(() => {
    if (!editing || !available) return
    if (!available.includes(loader)) setLoader('vanilla')
  }, [editing, available, loader])

  // Une version épinglée pour un autre couple n'existerait pas ici : on la
  // relâche plutôt que de proposer un choix qui ferait échouer le lancement.
  useEffect(() => {
    if (!editing) return
    if (loader !== instance.loader || mcVersion !== instance.mc_version) setLoaderVersion('')
  }, [editing, loader, mcVersion, instance.loader, instance.mc_version])

  const cancel = () => {
    setMcVersion(instance.mc_version)
    setLoader(instance.loader)
    setLoaderVersion(instance.loader_version)
    setUpdateMods(true)
    setWipeMods(false)
    setEditing(false)
  }

  const versionChanged = mcVersion !== instance.mc_version || loader !== instance.loader
  /** On quitte un loader pour vanilla : les mods installés ne se chargeront
   *  plus, quelle que soit leur version. */
  const goingVanilla = loader === 'vanilla' && instance.loader !== 'vanilla'

  /**
   * Ce que le launcher installe quand rien n'est épinglé.
   *
   * C'est la version recommandée par le loader lui-même, pas « la plus
   * récente » : l'une veut dire quelque chose, l'autre non. Elle est annoncée
   * avec son numéro partout où on parle de recommandation, sans quoi
   * « recommandée » reste une promesse sans contenu.
   */
  const recommended = loaderVersions?.find((v) => v.recommended) ?? null
  const defaultLabel = recommended
    ? t('instancesPage.loaderDefaultWith', { version: recommended.version })
    : t('instancesPage.loaderDefault')

  /** Un loader peut n'exister pour aucune version du jeu choisi — NeoForge
   *  avant 1.20.2, Quilt et Fabric sur les très vieilles versions. Rien à
   *  recommander alors, et rien à installer non plus. */
  const unavailable = loader !== 'vanilla' && loaderVersions?.length === 0

  const save = async () => {
    setSaving(true)
    setSavingLabel(t('instancesPage.saving'))
    try {
      const updated = await api.instances.update(
        instance.id,
        instance.name,
        mcVersion,
        loader,
        instance.ram_mb,
        instance.description,
        {
          vendor: instance.jvm_vendor,
          customPath: instance.jvm_custom_path ?? undefined,
          gcPolicy: instance.gc_policy,
          extraArgs: instance.jvm_extra_args,
          argsMode: instance.jvm_args_mode,
        },
        loaderVersion,
      )
      if (goingVanilla) {
        if (wipeMods) {
          setSavingLabel(t('instancesPage.wipingMods'))
          // Un par un : c'est ce que la commande sait faire, et une
          // suppression qui échoue sur un fichier ne doit pas emporter les
          // autres — l'instance resterait à moitié nettoyée sans qu'on sache
          // où elle s'est arrêtée.
          const mods = await api.mods.list(instance.id).catch(() => [])
          for (const mod of mods) {
            await api.mods.delete(instance.id, mod.name).catch(() => {})
          }
        }
      } else if (versionChanged && updateMods) {
        setSavingLabel(t('instancesPage.updatingMods'))
        await updateModsForNewVersion(instance.id, mcVersion, loader)
      }
      onUpdate(updated)
      setEditing(false)
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex flex-col gap-5">
      {!editing ? (
        <>
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
            {instance.loader !== 'vanilla' && (
              <Line label={t('instancesPage.loaderVersion', { loader: label(instance.loader) })}>
                <span className="font-semibold text-txt-primary">
                  {/* Sans recommandation connue, un tiret : annoncer
                      « Recommandée » sans pouvoir dire laquelle serait une
                      promesse vide, et c'est le cas quand le loader n'existe
                      pas pour cette version du jeu. */}
                  {instance.loader_version || (recommended || !unavailable ? defaultLabel : '—')}
                </span>
              </Line>
            )}
          </div>

          {unavailable && (
            <div className="flex gap-2.5 rounded-xl border border-danger/35 bg-danger/10 p-3">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" width={15} height={15} className="mt-px shrink-0 text-danger">
                <circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16h.01" />
              </svg>
              <p className="text-[11.5px] leading-relaxed text-txt-secondary">
                {t('instancesPage.loaderUnavailable', { loader: label(instance.loader), mc: instance.mc_version })}
              </p>
            </div>
          )}

          <div className="flex flex-col gap-2.5">
            <button
              onClick={() => setEditing(true)}
              className="flex w-fit items-center gap-2 rounded-xl border border-warning/40 bg-warning/15 px-3.5 py-2 text-[12.5px] font-semibold text-warning transition-colors hover:bg-warning/25"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
                <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
              </svg>
              {t('instancesPage.editInstallation')}
            </button>
            <p className="text-[11.5px] leading-relaxed text-txt-muted">
              {t('instancesPage.editInstallationHint')}
            </p>
          </div>
        </>
      ) : (
        <div className="flex flex-col gap-4 rounded-xl border border-line bg-surface-2 p-4">
          <p className="text-[13px] font-semibold text-txt-primary">{t('instancesPage.editInstallation')}</p>

          {/* L'avertissement est ici, à l'ouverture du formulaire, et pas à
              côté du bouton : c'est le moment où il se lit, parce que c'est le
              moment où on s'apprête à agir. */}
          <div className="flex gap-2.5 rounded-xl border border-warning/35 bg-warning/10 p-3">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" width={15} height={15} className="mt-px shrink-0 text-warning">
              <path d="M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" />
            </svg>
            <p className="text-[11.5px] leading-relaxed text-txt-secondary">
              {t('instancesPage.installationWarning')}
            </p>
          </div>

          <Field label={t('instancesPage.platform')}>
            {/* Seuls les loaders qui existent pour cette version du jeu : un
                bouton qu'on ne peut pas choisir n'a pas à être là. Le message
                d'indisponibilité plus bas reste, en filet — il attrape le cas
                où la vérification n'a pas pu se faire. */}
            <div className="flex flex-wrap gap-1.5">
              {LOADERS.filter((l) => !available || available.includes(l)).map((l) => (
                <button
                  key={l}
                  onClick={() => setLoader(l)}
                  className={`h-9 rounded-xl border px-3.5 text-[12.5px] font-semibold transition-colors ${
                    loader === l
                      ? 'border-accent/70 bg-accent/30 text-txt-primary'
                      : 'border-line bg-surface-1 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
                  }`}
                >
                  {label(l)}
                </button>
              ))}
            </div>
          </Field>

          <Field label={t('instancesPage.gameVersion')}>
            <Select value={mcVersion} onChange={setMcVersion} options={versions} />
          </Field>

          {loader !== 'vanilla' && (
            <Field label={t('instancesPage.loaderVersion', { loader: label(loader) })}>
              {loaderVersions === null ? (
                <p className="text-[11.5px] text-txt-muted">{t('common.loading')}</p>
              ) : unavailable ? (
                // Pas de menu du tout : il n'y a rien à y mettre, et un menu
                // vide laisserait croire à un chargement qui n'aboutit pas.
                <div className="flex gap-2.5 rounded-xl border border-danger/35 bg-danger/10 p-3">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" width={15} height={15} className="mt-px shrink-0 text-danger">
                    <circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16h.01" />
                  </svg>
                  <p className="text-[11.5px] leading-relaxed text-txt-secondary">
                    {t('instancesPage.loaderUnavailable', { loader: label(loader), mc: mcVersion })}
                  </p>
                </div>
              ) : (
                <>
                  <Select
                    value={loaderVersion}
                    onChange={setLoaderVersion}
                    // La recommandation et l'état de publication sont écrits
                    // DANS le libellé : on ne peut pas styliser une `<option>`
                    // de façon fiable, et c'est au moment de choisir qu'il
                    // faut l'information, pas après.
                    options={loaderVersions.map((v) => ({
                      value: v.version,
                      label: v.recommended
                        ? `${v.version} — ${t('instancesPage.loaderRecommended')}`
                        : v.stable
                          ? v.version
                          : `${v.version} — ${t('instancesPage.loaderUnstable')}`,
                    }))}
                    // La première entrée n'est pas une version choisie : c'est
                    // le comportement par défaut du launcher, qui doit rester
                    // atteignable pour revenir en arrière — et qui annonce
                    // quelle version il installera.
                    placeholder={{ value: '', label: defaultLabel }}
                  />
                  <p className="text-[11px] leading-relaxed text-txt-muted">
                    {t('instancesPage.loaderVersionHint')}
                  </p>
                </>
              )}
            </Field>
          )}

          {/* Deux questions différentes selon la destination. Vers un loader :
              faut-il chercher une version compatible de chaque mod ? Vers
              vanilla : il n'y a plus rien à chercher, les mods ne se
              chargeront plus — la seule chose à décider est si on les efface.
              Et celle-là part décochée : un dossier de mods vidé ne se
              récupère pas, alors qu'un dossier laissé en place ne coûte que
              de la place et redevient utile en revenant sur un loader. */}
          {versionChanged && (
            <button
              onClick={() => (goingVanilla ? setWipeMods(!wipeMods) : setUpdateMods(!updateMods))}
              className="flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5 text-left transition-colors hover:border-line-strong"
            >
              <Check on={goingVanilla ? wipeMods : updateMods} danger={goingVanilla} />
              <span className="min-w-0">
                <span className="block text-[12px] font-semibold text-txt-secondary">
                  {goingVanilla ? t('instancesPage.wipeModsLabel') : t('instancesPage.updateModsLabel')}
                </span>
                <span className="block text-[11px] leading-snug text-txt-muted">
                  {goingVanilla ? t('instancesPage.wipeModsHint') : t('instancesPage.updateModsHint')}
                </span>
              </span>
            </button>
          )}

          <div className="flex gap-2">
            {/* Enregistrer un couple sans aucune version de loader ne ferait
                que repousser l'échec au lancement, où il serait incompréhensible. */}
            <button
              onClick={save}
              disabled={saving || unavailable}
              className="rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40"
            >
              {saving ? savingLabel : t('common.save')}
            </button>
            <button
              onClick={cancel}
              disabled={saving}
              className="rounded-xl border border-line bg-surface-1 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:opacity-50"
            >
              {t('common.cancel')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/** Un menu déroulant aux couleurs du launcher — celui du système ne les prend pas. */
function Select({
  value,
  onChange,
  options,
  placeholder,
}: {
  value: string
  onChange: (value: string) => void
  /** Une chaîne quand la valeur suffit, une paire quand le libellé en dit
   *  plus que la valeur (voir les versions de loader). */
  options: (string | { value: string; label: string })[]
  placeholder?: { value: string; label: string }
}) {
  return (
    <div className="relative">
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="h-10 w-full appearance-none rounded-xl border border-line bg-black/40 px-3 pr-8 text-[13px] font-medium text-txt-primary outline-none"
      >
        {placeholder && (
          <option value={placeholder.value} className="bg-[#111118]">{placeholder.label}</option>
        )}
        {options.map((o) => {
          const { value: v, label: l } = typeof o === 'string' ? { value: o, label: o } : o
          return <option key={v} value={v} className="bg-[#111118]">{l}</option>
        })}
      </select>
      <svg viewBox="0 0 10 6" fill="currentColor" width={10} height={6} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-txt-muted">
        <path d="M0 0l5 6 5-6z" />
      </svg>
    </div>
  )
}

/** `danger` quand cocher détruit quelque chose : la couleur dit ce que le
 *  libellé annonce, avant qu'on ait fini de le lire. */
function Check({ on, danger }: { on: boolean; danger?: boolean }) {
  return (
    <span
      className={`flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-md border transition-colors ${
        on
          ? danger
            ? 'border-danger bg-danger text-white'
            : 'border-accent bg-accent text-white'
          : 'border-line-strong bg-transparent text-transparent'
      }`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" width={11} height={11}>
        <path d="M5 13l4 4L19 7" />
      </svg>
    </span>
  )
}

/**
 * L'icône de l'instance, et de quoi la changer.
 *
 * Le chemin choisi ne sert qu'à l'appel : c'est le Rust qui lit le fichier et
 * range les octets (voir `commands/instance/icon.rs`), donc déplacer ou
 * supprimer l'image d'origine ensuite ne casse rien.
 */
function IconField({ instance, onUpdate }: { instance: Instance; onUpdate: (i: Instance) => void }) {
  const t = useT()
  const [busy, setBusy] = useState(false)

  const pick = async () => {
    if (busy) return
    const picked = await openFileDialog({
      filters: [{ name: 'Image', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp'] }],
    })
    if (typeof picked !== 'string') return
    setBusy(true)
    try {
      onUpdate(await api.instances.setIcon(instance.id, picked))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const clear = async () => {
    setBusy(true)
    try {
      onUpdate(await api.instances.setIcon(instance.id, null))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex shrink-0 flex-col items-center gap-2">
      <span className="self-start text-[10px] font-semibold uppercase tracking-[0.1em] text-txt-muted">
        {t('instancesPage.icon')}
      </span>
      <button
        onClick={pick}
        disabled={busy}
        title={t('instancesPage.changeIcon')}
        className="group relative overflow-hidden rounded-2xl border border-line transition-colors hover:border-accent/50 disabled:opacity-60"
      >
        <InstanceIcon instance={instance} size={84} className="rounded-2xl" />
        <span className="absolute inset-0 flex items-center justify-center bg-black/55 opacity-0 transition-opacity group-hover:opacity-100">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={18} height={18} className="text-white">
            <path d="M3 7h3l2-2h8l2 2h3v12H3z" /><circle cx="12" cy="13" r="3.5" />
          </svg>
        </span>
      </button>
      {instance.icon ? (
        <button
          onClick={clear}
          disabled={busy}
          className="text-[11px] font-medium text-txt-muted underline decoration-txt-muted underline-offset-2 transition-colors hover:text-txt-primary disabled:opacity-50"
        >
          {t('instancesPage.removeIcon')}
        </button>
      ) : (
        <span className="text-[11px] text-txt-muted">{t('instancesPage.iconHint')}</span>
      )}
    </div>
  )
}

/**
 * Suppression définitive : la confirmation tient dans le bouton lui-même.
 *
 * Une modale par-dessus la modale des paramètres serait disproportionnée, et
 * le texte au-dessus dit déjà ce qui se perd.
 */
function DeleteButton({ onConfirm }: { onConfirm: () => void }) {
  const t = useT()
  const [armed, setArmed] = useState(false)

  if (!armed) {
    return (
      <button
        onClick={() => setArmed(true)}
        className="flex shrink-0 items-center gap-1.5 rounded-xl border border-danger/40 bg-danger/10 px-3.5 py-2 text-[12px] font-semibold text-danger transition-colors hover:bg-danger/20"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
          <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
        </svg>
        {t('instancesPage.deleteForever')}
      </button>
    )
  }
  return (
    <div className="flex shrink-0 gap-2">
      <button
        onClick={() => setArmed(false)}
        className="rounded-xl border border-line bg-surface-2 px-3.5 py-2 text-[12px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary"
      >
        {t('common.cancel')}
      </button>
      <button
        onClick={onConfirm}
        className="rounded-xl bg-danger px-3.5 py-2 text-[12px] font-bold text-white"
      >
        {t('common.delete')}
      </button>
    </div>
  )
}

function Separator() {
  return <div className="h-px bg-line" />
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
const IconWrench = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
    <path d="M14.7 6.3a4 4 0 01-5 5L4 17v3h3l5.7-5.7a4 4 0 015-5l-2.3-2.3 2.1-2.1a4 4 0 00-2.8 1.4z" />
  </svg>
)
const IconSliders = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" width={14} height={14}>
    <path d="M4 6h16M4 12h16M4 18h16" /><circle cx="9" cy="6" r="2" /><circle cx="15" cy="12" r="2" /><circle cx="8" cy="18" r="2" />
  </svg>
)
