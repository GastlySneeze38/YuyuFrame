import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { formatRam } from '@/lib/format'
import { JVM_PRESETS, lintJvmArgs, parseJvmArgs } from '@/lib/jvmFlags'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { RamPicker } from '@/components/ui/RamPicker'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import type { JvmArgsMode, JvmConfigPreview, JvmFormValues, JvmVendor } from '@/types'

const VENDORS: { id: JvmVendor; label: string }[] = [
  { id: 'auto', label: 'Auto' },
  { id: 'temurin', label: 'Temurin' },
  { id: 'openj9', label: 'OpenJ9' },
  { id: 'graal', label: 'GraalVM' },
  { id: 'custom', label: 'Personnalisé' },
]

const HOTSPOT_GC_OPTIONS = [
  { id: 'auto', label: 'Auto' },
  { id: 'g1', label: 'G1GC' },
  { id: 'zgc', label: 'ZGC' },
]

// `metronome` n'est PAS proposé : la JVM OpenJ9 de Windows le refuse au
// démarrage (JVMJ9VM007E), il n'existe que sur les builds temps réel.
const OPENJ9_GC_OPTIONS = [
  { id: 'auto', label: 'Auto (gencon)' },
  { id: 'gencon', label: 'gencon' },
  { id: 'optthruput', label: 'optthruput' },
  { id: 'optavgpause', label: 'optavgpause' },
  { id: 'balanced', label: 'balanced' },
]

/** Même règle que `resolve_auto_vendor` côté Rust — dupliquée ici uniquement
 * pour dire ce que "Auto" choisirait, la décision réelle reste au backend. */
function autoVendorFor(ramMb: number): 'openj9' | 'temurin' {
  return ramMb <= 2048 ? 'openj9' : 'temurin'
}

// ── Petits blocs de présentation ─────────────────────────────────────────────

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.4)]">{label}</label>
      {children}
    </div>
  )
}

function Segmented<T extends string>({ options, value, onChange }: {
  options: { id: T; label: string }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {options.map((o) => (
        <button
          key={o.id}
          onClick={() => onChange(o.id)}
          className={`h-[28px] rounded-lg border px-2.5 text-[11px] font-semibold transition-all duration-150 ${
            value === o.id
              ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] text-white'
              : 'border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.35)] text-[rgba(255,255,255,0.45)] hover:border-white/25'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

function Warn({ children }: { children: React.ReactNode }) {
  return <p className="text-[11px] leading-relaxed text-[rgba(240,180,90,0.75)]">⚠ {children}</p>
}

function Card({ title, sub, children }: { title: string; sub?: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-2xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] p-4">
      <div>
        <h2 className="text-[12px] font-black uppercase tracking-[0.12em] text-[rgba(255,255,255,0.75)]">{title}</h2>
        {sub && <p className="mt-1 text-[11px] leading-relaxed text-[rgba(255,255,255,0.35)]">{sub}</p>}
      </div>
      {children}
    </section>
  )
}

/**
 * Écran plein "Configuration JVM" (`/jvm/:instanceId`) — remplace l'ancienne
 * section repliable "JVM avancé" de la modal d'édition d'instance.
 *
 * Le centre de l'écran est le champ d'arguments JVM, pas le sélecteur de
 * vendeur : c'est le seul endroit où un réglage de perf se teste réellement,
 * et il n'existait tout simplement pas avant (le launcher générait ses flags
 * en dur, sans aucun moyen de les compléter ni de les remplacer). Les
 * réglages guidés — RAM, vendeur, GC — restent dans une colonne latérale
 * parce qu'ils ne font que produire une base par-dessus laquelle les
 * arguments manuels s'appliquent (voir `merge_jvm_args` côté Rust).
 *
 * L'aperçu est calculé par `preview_jvm_config`, c'est-à-dire par les MÊMES
 * fonctions qu'un vrai lancement — il montre les drapeaux réels, y compris
 * ceux que le backend a retirés à cause d'un conflit, jamais une simulation
 * refaite côté frontend qui divergerait au premier changement.
 */
export default function JvmConfig() {
  const navigate = useNavigate()
  const { instanceId } = useParams<{ instanceId: string }>()
  const { instances, updateInstance } = useStore()
  const instance = instances.find((i) => i.id === instanceId) ?? null

  const [ram, setRam] = useState(instance?.ram_mb ?? 4096)
  const [vendor, setVendor] = useState<JvmVendor>(instance?.jvm_vendor ?? 'auto')
  const [customPath, setCustomPath] = useState(instance?.jvm_custom_path ?? '')
  const [gcPolicy, setGcPolicy] = useState(instance?.gc_policy ?? 'auto')
  const [extraArgs, setExtraArgs] = useState(instance?.jvm_extra_args ?? '')
  const [argsMode, setArgsMode] = useState<JvmArgsMode>(instance?.jvm_args_mode ?? 'append')

  const [saving, setSaving] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [preview, setPreview] = useState<JvmConfigPreview | null>(null)
  const [copied, setCopied] = useState(false)

  // L'instance peut arriver après le premier rendu (liste encore en cours de
  // chargement au démarrage, ou arrivée directe sur l'URL) — on réhydrate le
  // formulaire dès qu'elle est là, une seule fois, tant que l'utilisateur n'a
  // rien pu modifier.
  useEffect(() => {
    if (!instance) return
    setRam(instance.ram_mb)
    setVendor(instance.jvm_vendor)
    setCustomPath(instance.jvm_custom_path ?? '')
    setGcPolicy(instance.gc_policy)
    setExtraArgs(instance.jvm_extra_args)
    setArgsMode(instance.jvm_args_mode)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instance?.id])

  const jvm: JvmFormValues = useMemo(
    () => ({ vendor, customPath, gcPolicy, extraArgs, argsMode }),
    [vendor, customPath, gcPolicy, extraArgs, argsMode],
  )

  const lint = useMemo(() => lintJvmArgs(extraArgs), [extraArgs])
  const userArgs = useMemo(() => new Set(parseJvmArgs(extraArgs)), [extraArgs])
  const autoVendor = autoVendorFor(ram)
  const gcOptions = vendor === 'openj9' ? OPENJ9_GC_OPTIONS : HOTSPOT_GC_OPTIONS

  const dirty =
    !!instance &&
    (ram !== instance.ram_mb ||
      vendor !== instance.jvm_vendor ||
      customPath !== (instance.jvm_custom_path ?? '') ||
      gcPolicy !== instance.gc_policy ||
      extraArgs !== instance.jvm_extra_args ||
      argsMode !== instance.jvm_args_mode)

  const applyPreset = (id: string) => {
    const preset = JVM_PRESETS.find((p) => p.id === id)
    if (!preset) return
    setArgsMode(preset.mode)
    setExtraArgs((current) => {
      if (preset.full) return preset.body
      const base = current.trimEnd()
      return base ? `${base}\n\n${preset.body}` : preset.body
    })
  }

  const handlePreview = async () => {
    if (!instance) return
    setPreviewing(true)
    try {
      setPreview(await api.instances.previewJvmConfig(instance.id, instance.mc_version, ram, jvm))
    } catch (e) {
      showError(e)
    } finally {
      setPreviewing(false)
    }
  }

  const handleSave = async () => {
    if (!instance) return
    setSaving(true)
    try {
      const updated = await api.instances.update(
        instance.id, instance.name, instance.mc_version, instance.loader, ram, instance.description, jvm,
      )
      updateInstance(updated)
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  const handleCopy = () => {
    if (!preview) return
    navigator.clipboard.writeText([preview.java_path, ...preview.jvm_args].join('\n')).then(
      () => {
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      },
      () => {},
    )
  }

  if (!instance) {
    return (
      <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
        <PageHeader backTo="/instances">
          <PageHeaderSeparator />
          <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configuration JVM</h1>
        </PageHeader>
        <div className="flex flex-1 flex-col items-center justify-center gap-1">
          <p className="text-[14px] font-semibold text-[rgba(255,255,255,0.5)]">Instance introuvable</p>
          <p className="text-[12px] text-[rgba(255,255,255,0.25)]">Elle a peut-être été supprimée depuis.</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
      <PageHeader backTo="/instances">
        <PageHeaderSeparator />
        <h1 className="text-[16px] font-black tracking-[-0.01em] text-white">Configuration JVM</h1>
        <span className="rounded-md bg-[rgba(255,255,255,0.05)] px-2 py-0.5 text-[11px] font-semibold text-[rgba(255,255,255,0.45)]">
          {instance.name} · {instance.mc_version} · {instance.loader}
        </span>
        <div className="flex-1" />
        <button
          onClick={handleSave}
          disabled={!dirty || saving}
          className="flex h-[30px] items-center gap-2 rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] px-3.5 text-[11px] font-bold text-white transition-all duration-150 hover:bg-[rgba(75,63,207,0.5)] disabled:cursor-default disabled:opacity-35 disabled:hover:bg-[rgba(75,63,207,0.35)]"
        >
          {saving && <ButtonSpinner />}
          {dirty ? 'Enregistrer' : 'Enregistré'}
        </button>
      </PageHeader>

      <div className="flex-1 overflow-auto">
        <div className="mx-auto flex w-full max-w-[1180px] flex-col gap-5 px-6 py-6 lg:flex-row-reverse lg:items-start">

          {/* ── Réglages guidés — colonne latérale : ils ne produisent que la
              base par-dessus laquelle les arguments manuels s'appliquent. ── */}
          <aside className="flex w-full flex-col gap-4 lg:w-[300px] lg:flex-shrink-0">
            <Card title="Base générée" sub="Ce que le launcher pose avant vos arguments.">
              <Field label={`Mémoire allouée — ${formatRam(ram)}`}>
                <RamPicker value={ram} onChange={setRam} loader={instance.loader} />
              </Field>

              <Field label="Vendeur JVM">
                <Segmented options={VENDORS} value={vendor} onChange={setVendor} />
                {vendor === 'auto' ? (
                  <p className="text-[10px] text-[rgba(255,255,255,0.35)]">
                    Choisira {autoVendor === 'openj9' ? 'OpenJ9 (gencon)' : 'Temurin'} pour {formatRam(ram)}.
                  </p>
                ) : vendor !== autoVendor ? (
                  <Warn>Auto choisirait {autoVendor === 'openj9' ? 'OpenJ9' : 'Temurin'} pour cette RAM.</Warn>
                ) : null}
                {vendor === 'openj9' && (
                  <Warn>
                    Le JIT d'OpenJ9 (Testarossa) plafonne bien plus bas que C2 sur Minecraft — aucune policy GC ne
                    rattrape l'écart.
                  </Warn>
                )}
              </Field>

              {vendor !== 'auto' && (
                <Field label={`Chemin java.exe ${vendor === 'custom' ? '(requis)' : '(optionnel)'}`}>
                  <input
                    type="text"
                    placeholder="C:\...\bin\java.exe"
                    value={customPath}
                    onChange={(e) => setCustomPath(e.target.value)}
                    className="h-[34px] w-full rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.4)] px-3 font-mono text-[11px] text-white outline-none focus:border-[rgba(75,63,207,0.6)]"
                  />
                  <p className="text-[10px] text-[rgba(255,255,255,0.3)]">
                    Épingle une install précise. Prioritaire sur tout le reste de la résolution.
                  </p>
                </Field>
              )}

              <Field label="Ramasse-miettes">
                <Segmented options={gcOptions} value={gcPolicy} onChange={setGcPolicy} />
                {gcPolicy === 'zgc' && ram < 6144 && (
                  <Warn>ZGC exige 6 Go et Java 21+ — remplacé automatiquement par G1GC en dessous.</Warn>
                )}
                {lint.gcSelectors.length > 0 && (
                  <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.35)]">
                    Ignoré : vos arguments posent déjà <span className="font-mono">{lint.gcSelectors[0]}</span>, qui
                    remplace tout le bloc GC généré.
                  </p>
                )}
              </Field>
            </Card>
          </aside>

          {/* ── Le cœur de l'écran ─────────────────────────────────────────── */}
          <main className="flex min-w-0 flex-1 flex-col gap-5">
            <Card
              title="Arguments JVM"
              sub="Appliqués après la base générée. Un drapeau tapé ici écrase son homologue généré ; poser un sélecteur de GC retire tout le bloc GC du launcher."
            >
              <div className="flex flex-wrap items-center gap-1.5">
                <Segmented
                  options={[
                    { id: 'append' as JvmArgsMode, label: 'Compléter la base' },
                    { id: 'replace' as JvmArgsMode, label: 'Remplacer la base' },
                  ]}
                  value={argsMode}
                  onChange={setArgsMode}
                />
                <span className="ml-1 text-[10px] leading-relaxed text-[rgba(255,255,255,0.3)]">
                  {argsMode === 'append'
                    ? 'Le tuning du launcher est conservé.'
                    : 'Seuls -Xmx/-Xms et les library path survivent.'}
                </span>
              </div>

              <textarea
                value={extraArgs}
                onChange={(e) => setExtraArgs(e.target.value)}
                spellCheck={false}
                placeholder={'-XX:+UseShenandoahGC\n-XX:ShenandoahGCMode=generational\n\n# les lignes commençant par # sont ignorées'}
                className="min-h-[320px] w-full resize-y rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] p-3 font-mono text-[12px] leading-relaxed text-[rgba(255,255,255,0.85)] outline-none focus:border-[rgba(75,63,207,0.6)]"
              />

              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-[rgba(255,255,255,0.35)]">
                <span>{lint.count} drapeau{lint.count > 1 ? 'x' : ''}</span>
                <span>·</span>
                <span>Un ou plusieurs par ligne, <span className="font-mono">#</span> pour commenter</span>
              </div>

              {lint.duplicates.length > 0 && (
                <Warn>
                  Défini deux fois : <span className="font-mono">{lint.duplicates.join(', ')}</span> — le dernier gagne.
                </Warn>
              )}
              {lint.gcSelectors.length > 1 && (
                <Warn>
                  Deux sélecteurs de GC (<span className="font-mono">{lint.gcSelectors.join(', ')}</span>) — la JVM
                  refusera de démarrer. Désactivez celui de trop avec sa forme négative
                  (<span className="font-mono">-XX:-UseG1GC</span>).
                </Warn>
              )}
            </Card>

            <Card
              title="Jeux de drapeaux"
              sub="Vérifiés comme acceptés par un Java 25. Un jeu complet remplace le champ, un complément s'y ajoute."
            >
              <div className="flex flex-wrap gap-1.5">
                {JVM_PRESETS.map((p) => (
                  <button
                    key={p.id}
                    onClick={() => applyPreset(p.id)}
                    title={p.hint}
                    className={`h-[28px] rounded-lg border px-2.5 text-[11px] font-semibold transition-all duration-150 ${
                      p.full
                        ? 'border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.7)] hover:border-white/30'
                        : 'border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.12)] text-[rgba(150,140,240,0.9)] hover:border-[rgba(75,63,207,0.7)]'
                    }`}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </Card>

            <Card
              title="Ligne de commande réelle"
              sub="Résolue par le backend avec les mêmes fonctions qu'un lancement. Peut télécharger la JVM si elle manque."
            >
              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={handlePreview}
                  disabled={previewing}
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
                {dirty && (
                  <span className="text-[10px] text-[rgba(255,255,255,0.3)]">
                    Calculé sur les valeurs affichées, enregistrées ou non.
                  </span>
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
                    En <span className="text-[rgba(150,140,240,0.9)]">violet</span>, ce qui vient de vos arguments.
                    Le classpath et les drapeaux du loader s'ajoutent au lancement.
                  </p>
                </div>
              )}
            </Card>

            <button
              onClick={() => navigate('/instances')}
              className="self-start text-[11px] font-semibold text-[rgba(255,255,255,0.35)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
            >
              ← Retour aux instances
            </button>
          </main>
        </div>
      </div>
    </div>
  )
}
