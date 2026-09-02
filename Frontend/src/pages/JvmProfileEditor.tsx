import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { formatRam } from '@/lib/format'
import {
  ARGS_MODES, CATEGORY_META, applyPreset, autoVendorFor, familyFor, lintJvmArgs, parseJvmArgs, presetsFor,
} from '@/lib/jvmFlags'
import { Card, Field, Segmented, Warn } from '@/components/jvm/controls'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { RamPicker } from '@/components/ui/RamPicker'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import type { JvmArgsMode, JvmConfigPreview, JvmFlagCategory, JvmProfile, JvmVendor } from '@/types'

const VENDORS: { id: JvmVendor; label: string }[] = [
  { id: 'auto', label: 'Auto' },
  { id: 'temurin', label: 'Temurin' },
  { id: 'openj9', label: 'OpenJ9' },
  { id: 'graal', label: 'GraalVM' },
  { id: 'custom', label: 'Perso.' },
]

const HOTSPOT_GC = [
  { id: 'auto', label: 'Auto' },
  { id: 'g1', label: 'G1GC' },
  { id: 'zgc', label: 'ZGC' },
]

// `metronome` volontairement absent : la JVM OpenJ9 de Windows le refuse au
// démarrage (JVMJ9VM007E), il n'existe que sur les builds temps réel.
const OPENJ9_GC = [
  { id: 'auto', label: 'Auto (gencon)' },
  { id: 'gencon', label: 'gencon' },
  { id: 'optthruput', label: 'optthruput' },
  { id: 'optavgpause', label: 'optavgpause' },
  { id: 'balanced', label: 'balanced' },
]

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

const FAMILY_LABEL = { hotspot: 'HotSpot', openj9: 'OpenJ9', graal: 'GraalVM (HotSpot + Graal)' } as const

/**
 * Éditeur d'une configuration JVM (`/jvm/:profileId`).
 *
 * La colonne de gauche est la **grille** : RAM, vendeur, ramasse-miettes,
 * mode de fusion. Elle ne produit qu'une base — mais elle décide aussi de la
 * *famille* de JVM obtenue, et donc de ce qui a un sens dans les trois
 * catégories de drapeaux à droite : la syntaxe GC d'OpenJ9 (`-Xgcpolicy:*`)
 * n'a rien à voir avec celle d'HotSpot, et le JIT Graal n'existe que sur une
 * GraalVM. Les jeux de drapeaux proposés sont donc filtrés par la grille
 * plutôt que tous affichés en vrac (voir `presetsFor`).
 *
 * Les drapeaux sont séparés en trois catégories — moteur, ramasse-miettes,
 * compilateur — parce que ce sont trois sujets indépendants : on change de GC
 * sans toucher au JIT. Dans une liste unique, impossible de dire quelle moitié
 * d'un test a bougé. Au lancement, les trois sont simplement concaténés.
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
      <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
        <PageHeader backTo="/jvm">
          <PageHeaderSeparator />
          <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configuration JVM</h1>
        </PageHeader>
        <div className="flex flex-1 flex-col items-center justify-center gap-1">
          <p className="text-[14px] font-semibold text-[rgba(255,255,255,0.5)]">Configuration introuvable</p>
          <p className="text-[12px] text-[rgba(255,255,255,0.25)]">Elle a peut-être été supprimée depuis.</p>
        </div>
      </div>
    )
  }

  if (!draft) {
    return (
      <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
        <PageHeader backTo="/jvm">
          <PageHeaderSeparator />
          <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configuration JVM</h1>
        </PageHeader>
        <div className="flex flex-1 items-center justify-center"><ButtonSpinner size={20} /></div>
      </div>
    )
  }

  const gcOptions = family === 'openj9' ? OPENJ9_GC : HOTSPOT_GC
  const autoVendor = autoVendorFor(effectiveRam)

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
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

      {/* ── Navigation entre catégories ──────────────────────────────────────
          Pleine largeur, sous l'en-tête : c'est la navigation de l'écran, pas
          un réglage de la config — la colonne de gauche reste la grille. */}
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
              <span
                className={`rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${
                  n > 0
                    ? active
                      ? 'bg-[rgba(75,63,207,0.4)] text-[rgba(210,205,255,0.95)]'
                      : 'bg-[rgba(255,255,255,0.07)] text-[rgba(255,255,255,0.5)]'
                    : 'bg-[rgba(255,255,255,0.03)] text-[rgba(255,255,255,0.22)]'
                }`}
              >
                {n}
              </span>
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

      <div className="flex flex-1 overflow-hidden">

        {/* ── La grille ───────────────────────────────────────────────────── */}
        <aside className="flex w-[290px] flex-shrink-0 flex-col gap-4 overflow-auto border-r border-[rgba(255,255,255,0.06)] px-4 py-5">
          <div>
            <h2 className="text-[12px] font-black uppercase tracking-[0.12em] text-[rgba(255,255,255,0.75)]">Grille</h2>
            <p className="mt-1 text-[11px] leading-relaxed text-[rgba(255,255,255,0.35)]">
              La base posée par le launcher. Elle décide aussi des drapeaux qui ont un sens à droite.
            </p>
          </div>

          <Field
            label="Mémoire"
            hint={draft.ram_mb ? 'Cette config impose son tas à toutes les instances reliées.' : "La RAM choisie sur chaque instance est conservée."}
          >
            <Segmented
              size="sm"
              options={[{ id: 'inherit', label: "Celle de l'instance" }, { id: 'fixed', label: 'Imposée' }]}
              value={draft.ram_mb ? 'fixed' : 'inherit'}
              onChange={(v) => set('ram_mb', v === 'fixed' ? (previewInstance?.ram_mb ?? 4096) : null)}
            />
            {draft.ram_mb !== null && (
              <RamPicker value={draft.ram_mb} onChange={(v) => set('ram_mb', v)} />
            )}
          </Field>

          <Field label="Vendeur JVM">
            <Segmented size="sm" options={VENDORS} value={draft.jvm_vendor} onChange={(v) => set('jvm_vendor', v)} />
            <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.3)]">
              Famille obtenue : <span className="text-[rgba(255,255,255,0.55)]">{FAMILY_LABEL[family]}</span>
              {draft.jvm_vendor === 'auto' && ` — Auto choisit ${autoVendor === 'openj9' ? 'OpenJ9' : 'Temurin'} pour ${formatRam(effectiveRam)}.`}
            </p>
            {family === 'openj9' && (
              <Warn>
                Le JIT d'OpenJ9 (Testarossa) plafonne bien plus bas que C2 sur Minecraft — aucune policy GC ne rattrape
                l'écart.
              </Warn>
            )}
          </Field>

          {draft.jvm_vendor !== 'auto' && (
            <Field
              label={`Chemin java.exe ${draft.jvm_vendor === 'custom' ? '(requis)' : '(optionnel)'}`}
              hint="Épingle une install précise. Prioritaire sur toute la résolution automatique."
            >
              <input
                type="text"
                placeholder="C:\...\bin\java.exe"
                value={draft.jvm_custom_path ?? ''}
                onChange={(e) => set('jvm_custom_path', e.target.value || null)}
                className="h-[32px] w-full rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.4)] px-2.5 font-mono text-[10px] text-white outline-none focus:border-[rgba(75,63,207,0.6)]"
              />
            </Field>
          )}

          <Field label="Ramasse-miettes">
            <Segmented size="sm" options={gcOptions} value={draft.gc_policy} onChange={(v) => set('gc_policy', v)} />
            {draft.gc_policy === 'zgc' && effectiveRam < 6144 && (
              <Warn>ZGC exige 6 Go et Java 21+ — remplacé automatiquement par G1GC en dessous.</Warn>
            )}
            {lint.gcSelectors.length > 0 && (
              <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.35)]">
                Ignoré : la catégorie Ramasse-miettes pose déjà{' '}
                <span className="font-mono text-[rgba(150,140,240,0.9)]">{lint.gcSelectors[0]}</span>, qui remplace tout
                le bloc GC généré.
              </p>
            )}
          </Field>

          <Field label="Fusion avec la base">
            <Segmented
              size="sm"
              options={ARGS_MODES.map((m) => ({ id: m.id, label: m.label }))}
              value={draft.args_mode}
              onChange={(v) => set('args_mode', v as JvmArgsMode)}
            />
            <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.3)]">
              {ARGS_MODES.find((m) => m.id === draft.args_mode)?.sub}
            </p>
          </Field>

          {linkedInstances.length > 0 && (
            <Field label={`Reliée à ${linkedInstances.length} instance${linkedInstances.length > 1 ? 's' : ''}`}>
              <div className="flex flex-wrap gap-1">
                {linkedInstances.map((i) => (
                  <span key={i.id} className="rounded-md border border-[rgba(75,63,207,0.5)] bg-[rgba(75,63,207,0.2)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(200,195,255,0.9)]">
                    {i.name}
                  </span>
                ))}
              </div>
              <button
                onClick={() => navigate('/jvm')}
                className="self-start text-[10px] font-semibold text-[rgba(150,140,240,0.8)] hover:text-[rgba(180,172,255,1)]"
              >
                Gérer les liens →
              </button>
            </Field>
          )}
        </aside>

        {/* ── Les trois catégories ────────────────────────────────────────── */}
        <main className="flex-1 overflow-auto">
          <div className="flex w-full max-w-[880px] flex-col gap-4 px-6 py-5">

            {lint.duplicates.length > 0 && (
              <Warn>
                Défini deux fois (toutes catégories confondues) :{' '}
                <span className="font-mono">{lint.duplicates.join(', ')}</span> — le dernier gagne.
              </Warn>
            )}
            {lint.gcSelectors.length > 1 && (
              <Warn>
                Deux sélecteurs de GC (<span className="font-mono">{lint.gcSelectors.join(', ')}</span>) — la JVM
                refusera de démarrer. Désactivez celui de trop avec sa forme négative
                (<span className="font-mono">-XX:-UseG1GC</span>).
              </Warn>
            )}

            {tab !== 'preview' && (() => {
              const key = ARGS_KEY[tab]
              const value = draft[key]
              const presets = presetsFor(tab, family)
              const full = presets.filter((p) => p.full)
              const addons = presets.filter((p) => !p.full)
              return (
                <>
                  <div>
                    <h2 className="text-[15px] font-black tracking-[-0.01em] text-white">{CATEGORY_META[tab].label}</h2>
                    <p className="mt-1 max-w-[620px] text-[11px] leading-relaxed text-[rgba(255,255,255,0.4)]">
                      {CATEGORY_META[tab].sub}
                    </p>
                  </div>

                  {presets.length > 0 ? (
                    <Card
                      title="Jeux de drapeaux"
                      sub={`Filtrés pour ${FAMILY_LABEL[family]}. Un jeu complet remplace le champ, un complément s'y ajoute.`}
                    >
                      {full.length > 0 && (
                        <div className="flex flex-wrap gap-1.5">
                          {full.map((p) => (
                            <button
                              key={p.id}
                              onClick={() => set(key, applyPreset(value, p))}
                              title={p.hint}
                              className="h-[28px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-2.5 text-[11px] font-semibold text-[rgba(255,255,255,0.7)] transition-all duration-150 hover:border-white/30"
                            >
                              {p.label}
                            </button>
                          ))}
                        </div>
                      )}
                      {addons.length > 0 && (
                        <div className="flex flex-wrap gap-1.5">
                          {addons.map((p) => (
                            <button
                              key={p.id}
                              onClick={() => set(key, applyPreset(value, p))}
                              title={p.hint}
                              className="h-[28px] rounded-lg border border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.12)] px-2.5 text-[11px] font-semibold text-[rgba(150,140,240,0.9)] transition-all duration-150 hover:border-[rgba(75,63,207,0.7)]"
                            >
                              {p.label}
                            </button>
                          ))}
                        </div>
                      )}
                    </Card>
                  ) : (
                    <p className="text-[11px] text-[rgba(255,255,255,0.28)]">
                      Aucun jeu vérifié pour {FAMILY_LABEL[family]} dans cette catégorie — à saisir à la main.
                    </p>
                  )}

                  <textarea
                    value={value}
                    onChange={(e) => set(key, e.target.value)}
                    spellCheck={false}
                    placeholder={'# un ou plusieurs drapeaux par ligne\n# les lignes commençant par # sont ignorées'}
                    className="min-h-[320px] w-full flex-1 resize-y rounded-2xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] p-3.5 font-mono text-[12px] leading-relaxed text-[rgba(255,255,255,0.85)] outline-none focus:border-[rgba(75,63,207,0.6)]"
                  />
                </>
              )
            })()}

            {tab === 'preview' && (
            <Card
              title="Ligne de commande réelle"
              sub="Résolue par le backend avec les mêmes fonctions qu'un lancement. Peut télécharger la JVM si elle manque."
            >
              <div className="flex flex-wrap items-center gap-2">
                <select
                  value={previewInstanceId ?? ''}
                  onChange={(e) => setPreviewInstanceId(e.target.value || null)}
                  className="h-[30px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(0,0,0,0.4)] px-2 text-[11px] text-[rgba(255,255,255,0.7)] outline-none"
                >
                  {instances.length === 0 && <option value="">Aucune instance</option>}
                  {instances.map((i) => (
                    <option key={i.id} value={i.id}>{i.name} — {i.mc_version}</option>
                  ))}
                </select>
                <button
                  onClick={handlePreview}
                  disabled={previewing || !previewInstance}
                  className="flex h-[30px] items-center gap-2 rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-3 text-[11px] font-semibold text-[rgba(255,255,255,0.75)] transition-colors hover:border-white/25 disabled:opacity-50"
                >
                  {previewing && <ButtonSpinner />}
                  {previewing ? 'Résolution…' : 'Calculer'}
                </button>
                {preview && (
                  <button
                    onClick={handleCopy}
                    className="h-[30px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-3 text-[11px] font-semibold text-[rgba(255,255,255,0.6)] transition-colors hover:border-white/25"
                  >
                    {copied ? 'Copié' : 'Copier'}
                  </button>
                )}
              </div>

              {preview && (
                <div className="flex flex-col gap-2 rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.35)] p-3">
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
                    En <span className="text-[rgba(150,140,240,0.9)]">violet</span>, ce qui vient de cette config. Le
                    classpath et les drapeaux du loader s'ajoutent au lancement.
                  </p>
                </div>
              )}
            </Card>
            )}
          </div>
        </main>
      </div>
    </div>
  )
}
