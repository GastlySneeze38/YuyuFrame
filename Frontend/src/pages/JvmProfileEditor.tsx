import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import {
  CATEGORY_META, diffAgainstPreset, diffCount, familyFor, lintJvmArgs, parseArgEntry, parseJvmArgs, presetsFor,
} from '@/lib/jvmFlags'
import { categoryOf, flagDoc } from '@/lib/jvmCatalog'
import { ArgsEditor } from '@/components/jvm/ArgsEditor'
import { BaseModal } from '@/components/jvm/BaseModal'
import { FlagPicker } from '@/components/jvm/FlagPicker'
import { ImportModal } from '@/components/jvm/ImportModal'
import { GridModal, GridSummary } from '@/components/jvm/GridModal'
import { Warn } from '@/components/jvm/controls'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import type { JvmConfigPreview, JvmFlagCategory, JvmProfile } from '@/types'

const CATEGORIES: JvmFlagCategory[] = ['jvm', 'gc', 'jit']

/** Les trois catégories vivent chacune sur son propre onglet, plus un onglet
 * d'aperçu. Empilées, elles saturaient l'écran : on ne règle qu'un sujet à la
 * fois, et le reste ne fait qu'ajouter du bruit autour de celui qu'on édite. */
type EditorTab = JvmFlagCategory | 'preview'

const ARGS_KEY: Record<JvmFlagCategory, 'args_jvm' | 'args_gc' | 'args_jit'> = {
  jvm: 'args_jvm',
  gc: 'args_gc',
  jit: 'args_jit',
}

/**
 * Éditeur d'une configuration JVM (`/jvm/:profileId`).
 *
 * Principe de l'écran : **il ne montre que ce qui existe déjà**. Une catégorie
 * vide n'affiche que les deux façons de la remplir ; les réglages de la JVM se
 * résument à une ligne ; les jeux de drapeaux et le catalogue vivent en
 * modales. La version précédente affichait tout en permanence — colonne de
 * réglages, sept jeux, en-têtes, champs — et se présentait donc pleine avant
 * qu'aucune décision n'ait été prise, ce qui est exactement ce qui décourage au
 * moment où l'on découvre l'outil.
 *
 * Corollaire : une seule colonne centrée. L'ancienne barre latérale prenait un
 * quart de la largeur pour six réglages qu'on touche une fois, pendant que la
 * zone d'édition manquait de place et laissait un grand vide à droite.
 *
 * Les drapeaux restent séparés en trois catégories — moteur, ramasse-miettes,
 * compilateur — parce que ce sont trois sujets indépendants : on change de GC
 * sans toucher au JIT. Au lancement, les trois sont simplement concaténés.
 */
export default function JvmProfileEditor() {
  const navigate = useNavigate()
  const { profileId } = useParams<{ profileId: string }>()
  const instances = useStore((s) => s.instances)

  const [loaded, setLoaded] = useState<JvmProfile | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [draft, setDraft] = useState<JvmProfile | null>(null)
  const [saving, setSaving] = useState(false)
  const [tab, setTab] = useState<EditorTab>('gc')

  const [pickerOpen, setPickerOpen] = useState(false)
  const [gridOpen, setGridOpen] = useState(false)
  const [baseOpen, setBaseOpen] = useState(false)
  const [importOpen, setImportOpen] = useState(false)

  const [previewInstanceId, setPreviewInstanceId] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [preview, setPreview] = useState<JvmConfigPreview | null>(null)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    api.jvmProfiles.list()
      .then((list) => {
        const found = list.find((p) => p.id === profileId) ?? null
        setLoaded(found)
        setDraft(found)
        setNotFound(!found)
      })
      .catch((e) => { setNotFound(true); showError(e) })
  }, [profileId])

  // L'aperçu a besoin d'une instance réelle (version MC, dossier de jeu) — on
  // propose d'abord une instance déjà reliée à cette config, sinon la première
  // venue : c'est un aperçu de drapeaux, pas un lancement.
  const linkedInstances = useMemo(
    () => instances.filter((i) => i.jvm_profile_id === profileId),
    [instances, profileId],
  )
  useEffect(() => {
    if (previewInstanceId) return
    setPreviewInstanceId(linkedInstances[0]?.id ?? instances[0]?.id ?? null)
  }, [linkedInstances, instances, previewInstanceId])

  const set = <K extends keyof JvmProfile>(key: K, value: JvmProfile[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d))

  // `base_presets` est stocké en JSON opaque côté base : le catalogue des jeux
  // vit ici, pas dans le backend. Un contenu illisible (config écrite par une
  // version antérieure, JSON tronqué) ne doit jamais casser l'écran — on
  // retombe sur "aucune base", qui est un état parfaitement valide.
  const bases: Partial<Record<JvmFlagCategory, string>> = useMemo(() => {
    if (!draft?.base_presets) return {}
    try {
      const parsed = JSON.parse(draft.base_presets)
      return typeof parsed === 'object' && parsed !== null ? parsed : {}
    } catch {
      return {}
    }
  }, [draft?.base_presets])

  const setBase = (category: JvmFlagCategory, id: string | null) => {
    const next = { ...bases }
    if (id) next[category] = id
    else delete next[category]
    set('base_presets', Object.keys(next).length ? JSON.stringify(next) : '')
  }

  /**
   * Ajoute un drapeau venu du catalogue — dans SA catégorie, pas dans celle
   * qu'on regardait. Savoir si `-XX:+UseNUMA` relève du moteur ou du GC est un
   * travail de comptable qui n'a aucune incidence sur le lancement (les trois
   * catégories sont concaténées) mais qui décide si on retrouvera son drapeau
   * plus tard. Le catalogue le sait, l'utilisateur n'a pas à le savoir.
   */
  const addFlag = (token: string) => {
    const entry = parseArgEntry(token)
    const category = categoryOf(entry.name)
    const key = ARGS_KEY[category]
    const current = draft?.[key] ?? ''
    set(key, current.trim() ? `${current.trimEnd()}\n${token}` : token)
    setPickerOpen(false)
    if (category !== tab) setTab(category)
  }

  /**
   * Import d'un jeu collé. Il touche les trois catégories d'un coup (une ligne
   * de commande Aikar contient du GC et du moteur), d'où son traitement ici
   * plutôt que dans la modale de la catégorie affichée.
   *
   * En mode "Remplacer", les bases sont oubliées : les drapeaux ne viennent
   * plus d'un jeu du catalogue, laisser « basée sur X » afficherait des écarts
   * calculés contre une origine qui n'est plus la bonne.
   */
  const importFlags = (byCategory: Record<JvmFlagCategory, string[]>, mode: 'append' | 'replace') => {
    setDraft((d) => {
      if (!d) return d
      const next = { ...d }
      for (const category of CATEGORIES) {
        const key = ARGS_KEY[category]
        const added = byCategory[category].join('\n')
        if (mode === 'replace') {
          next[key] = added
        } else if (added) {
          next[key] = next[key].trim() ? `${next[key].trimEnd()}\n${added}` : added
        }
      }
      if (mode === 'replace') next.base_presets = ''
      return next
    })
    setBaseOpen(false)
  }

  const previewInstance = instances.find((i) => i.id === previewInstanceId) ?? null
  // La RAM de la config prime, sinon celle de l'instance d'aperçu — c'est
  // exactement la règle appliquée au lancement (voir launch.rs).
  const effectiveRam = draft?.ram_mb ?? previewInstance?.ram_mb ?? 4096
  const family = draft ? familyFor(draft.jvm_vendor, effectiveRam) : 'hotspot'
  const lint = useMemo(
    () => (draft ? lintJvmArgs(draft.args_jvm, draft.args_gc, draft.args_jit) : lintJvmArgs('')),
    [draft],
  )
  const userArgs = useMemo(
    () => new Set(draft ? [draft.args_jvm, draft.args_gc, draft.args_jit].flatMap(parseJvmArgs) : []),
    [draft],
  )
  const dirty = !!draft && !!loaded && JSON.stringify(draft) !== JSON.stringify(loaded)

  const handleSave = async () => {
    if (!draft) return
    setSaving(true)
    try {
      const saved = await api.jvmProfiles.save(draft)
      setLoaded(saved)
      setDraft(saved)
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  const handlePreview = async () => {
    if (!draft || !previewInstance) return
    setPreviewing(true)
    try {
      setPreview(await api.instances.previewJvmConfig(
        previewInstance.id, previewInstance.mc_version, effectiveRam,
        {
          vendor: draft.jvm_vendor,
          customPath: draft.jvm_custom_path ?? undefined,
          gcPolicy: draft.gc_policy,
          extraArgs: [draft.args_jvm, draft.args_gc, draft.args_jit].map((s) => s.trim()).filter(Boolean).join('\n'),
          argsMode: draft.args_mode,
        },
      ))
    } catch (e) {
      showError(e)
    } finally {
      setPreviewing(false)
    }
  }

  const handleCopy = () => {
    if (!preview) return
    navigator.clipboard.writeText([preview.java_path, ...preview.jvm_args].join('\n')).then(
      () => { setCopied(true); setTimeout(() => setCopied(false), 1500) },
      () => {},
    )
  }

  if (notFound) {
    return (
      <Shell>
        <div className="flex flex-1 flex-col items-center justify-center gap-1">
          <p className="text-[14px] font-semibold text-[rgba(255,255,255,0.5)]">Configuration introuvable</p>
          <p className="text-[12px] text-[rgba(255,255,255,0.25)]">Elle a peut-être été supprimée depuis.</p>
        </div>
      </Shell>
    )
  }

  if (!draft) {
    return (
      <Shell>
        <div className="flex flex-1 items-center justify-center"><ButtonSpinner size={20} /></div>
      </Shell>
    )
  }

  // Noms déjà posés, toutes catégories confondues — le catalogue s'en sert pour
  // dire "déjà dans la config" plutôt que de laisser ajouter un doublon.
  const presentFlags = new Set(
    [draft.args_jvm, draft.args_gc, draft.args_jit].flatMap(parseJvmArgs).map((a) => parseArgEntry(a).name),
  )
  const missingUnlock = draft.args_mode !== 'replace' || presentFlags.has('-XX:UnlockExperimentalVMOptions')
    ? []
    : [...presentFlags].filter((n) => flagDoc(n)?.experimental)

  const editableTab: JvmFlagCategory = tab === 'preview' ? 'gc' : tab

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-[#09090D]">
      <PageHeader backTo="/jvm">
        <PageHeaderSeparator />
        <input
          value={draft.name}
          onChange={(e) => set('name', e.target.value)}
          className="min-w-0 flex-1 rounded-lg border border-transparent bg-transparent px-2 py-1 text-[16px] font-black tracking-[-0.01em] text-white outline-none transition-colors hover:border-[rgba(255,255,255,0.1)] focus:border-[rgba(75,63,207,0.6)] focus:bg-[rgba(0,0,0,0.35)]"
        />
        <button
          onClick={handleSave}
          disabled={!dirty || saving}
          className="flex h-[30px] flex-shrink-0 items-center gap-2 rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] px-3.5 text-[11px] font-bold text-white transition-all duration-150 hover:bg-[rgba(75,63,207,0.5)] disabled:cursor-default disabled:opacity-35 disabled:hover:bg-[rgba(75,63,207,0.35)]"
        >
          {saving && <ButtonSpinner />}
          {dirty ? 'Enregistrer' : 'Enregistré'}
        </button>
      </PageHeader>

      <nav className="flex flex-shrink-0 items-end gap-1 border-b border-[rgba(255,255,255,0.06)] px-5">
        {CATEGORIES.map((cat) => {
          const n = parseJvmArgs(draft[ARGS_KEY[cat]]).length
          const active = tab === cat
          return (
            <button
              key={cat}
              onClick={() => setTab(cat)}
              className={`flex items-center gap-1.5 border-b-2 px-3 py-2.5 text-[12px] font-bold transition-colors ${
                active
                  ? 'border-[rgba(120,105,255,0.9)] text-white'
                  : 'border-transparent text-[rgba(255,255,255,0.38)] hover:text-[rgba(255,255,255,0.7)]'
              }`}
            >
              {CATEGORY_META[cat].label}
              {/* Le compteur n'apparaît que s'il y a quelque chose à compter :
                  trois "0" alignés au premier chargement, c'est trois éléments
                  qui n'apprennent rien. */}
              {n > 0 && (
                <span
                  className={`rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${
                    active
                      ? 'bg-[rgba(75,63,207,0.4)] text-[rgba(210,205,255,0.95)]'
                      : 'bg-[rgba(255,255,255,0.07)] text-[rgba(255,255,255,0.5)]'
                  }`}
                >
                  {n}
                </span>
              )}
            </button>
          )
        })}
        <div className="flex-1" />
        <button
          onClick={() => setTab('preview')}
          className={`border-b-2 px-3 py-2.5 text-[12px] font-bold transition-colors ${
            tab === 'preview'
              ? 'border-[rgba(120,105,255,0.9)] text-white'
              : 'border-transparent text-[rgba(255,255,255,0.38)] hover:text-[rgba(255,255,255,0.7)]'
          }`}
        >
          Ligne de commande
        </button>
      </nav>

      <div className="flex-1 overflow-auto">
        <div className="mx-auto flex w-full max-w-[820px] flex-col gap-4 px-6 py-5">

          <GridSummary draft={draft} instanceRam={previewInstance?.ram_mb ?? 4096} onOpen={() => setGridOpen(true)} />

          {lint.duplicates.length > 0 && (
            <Warn>
              Défini deux fois (toutes catégories confondues) :{' '}
              <span className="font-mono">{lint.duplicates.join(', ')}</span> — le dernier gagne.
            </Warn>
          )}
          {lint.gcSelectors.length > 1 && (
            <Warn>
              Deux collecteurs sélectionnés (<span className="font-mono">{lint.gcSelectors.join(', ')}</span>) — la JVM
              refusera de démarrer.
            </Warn>
          )}
          {/* Piège classique, et le message de la JVM ne dit pas quoi faire : un
              drapeau expérimental sans son déverrouillage empêche le démarrage.
              En mode "Compléter", le déverrouillage vient déjà de la base
              générée — l'avertissement ne vaut qu'en "Remplacer". */}
          {missingUnlock.length > 0 && (
            <Warn>
              <span className="font-mono">{missingUnlock.join(', ')}</span>{' '}
              {missingUnlock.length > 1 ? 'sont expérimentaux' : 'est expérimental'} : sans{' '}
              <span className="font-mono">-XX:+UnlockExperimentalVMOptions</span>, la JVM refusera de démarrer.{' '}
              <button
                onClick={() => addFlag('-XX:+UnlockExperimentalVMOptions')}
                className="font-semibold text-[rgba(150,140,240,0.95)] underline underline-offset-2 hover:text-[rgba(190,183,255,1)]"
              >
                Ajouter le drapeau
              </button>
            </Warn>
          )}

          {tab !== 'preview' && (
            <CategoryPane
              category={tab}
              value={draft[ARGS_KEY[tab]]}
              basePreset={presetsFor(tab, family).find((p) => p.id === bases[tab]) ?? null}
              onChange={(v) => set(ARGS_KEY[tab], v)}
              onBrowseBase={() => setBaseOpen(true)}
              onAddFlag={() => setPickerOpen(true)}
            />
          )}

          {tab === 'preview' && (
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-[14px] font-black tracking-[-0.01em] text-white">Ligne de commande</h2>
                <div className="flex-1" />
                {instances.length > 1 && (
                  <select
                    value={previewInstanceId ?? ''}
                    onChange={(e) => setPreviewInstanceId(e.target.value || null)}
                    className="h-[28px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(0,0,0,0.4)] px-2 text-[11px] text-[rgba(255,255,255,0.6)] outline-none"
                  >
                    {instances.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                  </select>
                )}
                {preview && (
                  <button
                    onClick={handleCopy}
                    className="h-[28px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.05)] px-3 text-[11px] font-semibold text-[rgba(255,255,255,0.55)] transition-colors hover:border-white/25"
                  >
                    {copied ? 'Copié' : 'Copier'}
                  </button>
                )}
                <button
                  onClick={handlePreview}
                  disabled={previewing || !previewInstance}
                  className="flex h-[28px] items-center gap-2 rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-3 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)] disabled:opacity-40"
                >
                  {previewing && <ButtonSpinner />}
                  {previewing ? 'Résolution…' : preview ? 'Recalculer' : 'Calculer'}
                </button>
              </div>

              {!preview ? (
                <Empty
                  title="Résolue par le backend, exactement comme un lancement"
                  sub="Peut télécharger la JVM si elle manque."
                />
              ) : (
                <div className="flex flex-col gap-2 rounded-2xl border border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.35)] p-3">
                  <p className="font-mono text-[11px] leading-relaxed text-[rgba(255,255,255,0.55)]">
                    Java {preview.java_major} — <span className="break-all">{preview.java_path}</span>
                  </p>
                  <div className="flex flex-col">
                    {preview.jvm_args.map((arg, i) => (
                      <span
                        key={`${arg}-${i}`}
                        className={`break-all font-mono text-[11px] leading-relaxed ${
                          userArgs.has(arg) ? 'text-[rgba(150,140,240,0.95)]' : 'text-[rgba(255,255,255,0.4)]'
                        }`}
                      >
                        {arg}
                      </span>
                    ))}
                  </div>
                  <p className="text-[10px] text-[rgba(255,255,255,0.3)]">
                    En <span className="text-[rgba(150,140,240,0.9)]">violet</span>, ce qui vient de cette config.
                  </p>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {gridOpen && (
        <GridModal
          draft={draft}
          onChange={set}
          instanceRam={previewInstance?.ram_mb ?? 4096}
          linked={linkedInstances}
          onManageLinks={() => navigate('/jvm')}
          onClose={() => setGridOpen(false)}
        />
      )}

      {baseOpen && (
        <BaseModal
          category={editableTab}
          family={family}
          value={draft[ARGS_KEY[editableTab]]}
          baseId={bases[editableTab] ?? null}
          onChange={(v) => set(ARGS_KEY[editableTab], v)}
          onBaseChange={(id) => setBase(editableTab, id)}
          onImport={() => setImportOpen(true)}
          onClose={() => setBaseOpen(false)}
        />
      )}

      {importOpen && (
        <ImportModal onImport={importFlags} onClose={() => setImportOpen(false)} />
      )}

      {pickerOpen && (
        <FlagPicker
          family={family}
          category={editableTab}
          present={presentFlags}
          onAdd={addFlag}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </div>
  )
}

/** Une catégorie de drapeaux. Vide, elle ne montre que les deux façons de la
 * remplir — pas d'en-tête, pas de liste de jeux, pas de compteur à zéro. */
function CategoryPane({ category, value, basePreset, onChange, onBrowseBase, onAddFlag }: {
  category: JvmFlagCategory
  value: string
  basePreset: { id: string; label: string; body: string } | null
  onChange: (v: string) => void
  onBrowseBase: () => void
  onAddFlag: () => void
}) {
  const count = parseJvmArgs(value).length
  const écarts = basePreset ? diffCount(diffAgainstPreset(value, basePreset.body)) : 0

  if (count === 0) {
    return (
      <div className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-[rgba(255,255,255,0.1)] px-6 py-20 text-center">
        <div>
          <p className="text-[14px] font-bold text-[rgba(255,255,255,0.55)]">{CATEGORY_META[category].empty}</p>
          <p className="mx-auto mt-1.5 max-w-[400px] text-[11px] leading-relaxed text-[rgba(255,255,255,0.28)]">
            {CATEGORY_META[category].sub}
          </p>
        </div>
        <div className="flex flex-wrap justify-center gap-2">
          <button
            onClick={onBrowseBase}
            className="h-[32px] rounded-xl border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-4 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)]"
          >
            Partir d’un jeu de drapeaux
          </button>
          <button
            onClick={onAddFlag}
            className="h-[32px] rounded-xl border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.05)] px-4 text-[11px] font-semibold text-[rgba(255,255,255,0.6)] transition-colors hover:border-white/25"
          >
            Ajouter un drapeau
          </button>
        </div>
      </div>
    )
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-[14px] font-black tracking-[-0.01em] text-white">{CATEGORY_META[category].label}</h2>
        <div className="flex-1" />
        <button
          onClick={onBrowseBase}
          className={`h-[28px] rounded-lg border px-2.5 text-[11px] font-semibold transition-colors ${
            basePreset
              ? 'border-[rgba(75,63,207,0.5)] bg-[rgba(75,63,207,0.18)] text-[rgba(190,183,255,0.95)] hover:border-[rgba(75,63,207,0.8)]'
              : 'border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.55)] hover:border-white/25'
          }`}
        >
          {basePreset
            ? `${basePreset.label}${écarts > 0 ? ` · ${écarts} écart${écarts > 1 ? 's' : ''}` : ''}`
            : 'Partir d’un jeu'}
        </button>
        <button
          onClick={onAddFlag}
          className="flex h-[28px] items-center gap-1.5 rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-3 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)]"
        >
          <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10} className="flex-shrink-0">
            <path d="M11 5h2v14h-2z" /><path d="M5 11h14v2H5z" />
          </svg>
          Ajouter
        </button>
      </div>
      <ArgsEditor value={value} onChange={onChange} />
    </>
  )
}

function Empty({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="rounded-2xl border border-dashed border-[rgba(255,255,255,0.1)] px-6 py-20 text-center">
      <p className="text-[12px] text-[rgba(255,255,255,0.35)]">{title}</p>
      <p className="mt-1 text-[10px] text-[rgba(255,255,255,0.22)]">{sub}</p>
    </div>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
      <PageHeader backTo="/jvm">
        <PageHeaderSeparator />
        <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configuration JVM</h1>
      </PageHeader>
      {children}
    </div>
  )
}
