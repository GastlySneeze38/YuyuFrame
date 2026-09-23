import { useCallback, useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { press } from '@/lib/motion'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { EmptyState } from '@/components/ui/EmptyState'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import type { Instance, McOption } from '@/types'

/**
 * Réglages de l'instance : ceux de Minecraft et ceux de YuyuFrame.
 *
 * ── Deux vues sur le même fichier ─────────────────────────────────────────
 * `options.txt` contient des dizaines de clés, dont la plupart sont des
 * raccourcis clavier et des réglages propres aux mods installés. En montrer
 * la liste brute serait honnête mais inutilisable ; n'en montrer qu'une
 * sélection serait confortable mais mensonger — on ne saurait plus où est
 * passé le reste.
 *
 * D'où les deux vues. La vue lisible présente les réglages qui comptent avec
 * le bon contrôle (un curseur pour la distance d'affichage, un interrupteur
 * pour la synchronisation verticale). La vue avancée montre le fichier entier,
 * cherchable, valeurs brutes. C'est la même donnée, jamais deux états
 * différents : les deux écrivent par la même fonction.
 *
 * Rien n'est écrit tant qu'on ne valide pas : le fichier est celui d'un jeu
 * qui peut être en train de tourner, et une écriture par frappe écraserait ce
 * que Minecraft y met en quittant.
 */

type Control =
  | { type: 'slider'; min: number; max: number; step?: number; suffix?: string }
  | { type: 'bool' }
  | { type: 'choice'; choices: Array<{ value: string; labelKey: string }> }
  | { type: 'text' }

interface KnownOption {
  key: string
  labelKey: string
  control: Control
  /** Valeur écrite par Minecraft à la première génération du fichier. */
  fallback: string
}

/** Les réglages que l'on sait présenter. Tout le reste vit dans la vue
 *  avancée — cette liste ne cherche pas à couvrir le fichier, seulement ce
 *  qu'on ouvre réellement les options pour changer. */
const KNOWN: Array<{ groupKey: string; options: KnownOption[] }> = [
  {
    groupKey: 'options.groupVideo',
    options: [
      { key: 'renderDistance', labelKey: 'options.renderDistance', fallback: '12', control: { type: 'slider', min: 2, max: 32, suffix: ' ch.' } },
      { key: 'simulationDistance', labelKey: 'options.simulationDistance', fallback: '12', control: { type: 'slider', min: 5, max: 32, suffix: ' ch.' } },
      { key: 'fov', labelKey: 'options.fov', fallback: '70', control: { type: 'slider', min: 30, max: 110 } },
      { key: 'maxFps', labelKey: 'options.maxFps', fallback: '120', control: { type: 'slider', min: 10, max: 260 } },
      { key: 'graphicsMode', labelKey: 'options.graphicsMode', fallback: '1', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.graphicsFast' },
        { value: '1', labelKey: 'options.graphicsFancy' },
        { value: '2', labelKey: 'options.graphicsFabulous' },
      ] } },
      { key: 'particles', labelKey: 'options.particles', fallback: '0', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.particlesAll' },
        { value: '1', labelKey: 'options.particlesDecreased' },
        { value: '2', labelKey: 'options.particlesMinimal' },
      ] } },
      { key: 'enableVsync', labelKey: 'options.vsync', fallback: 'true', control: { type: 'bool' } },
      { key: 'fullscreen', labelKey: 'options.fullscreen', fallback: 'false', control: { type: 'bool' } },
      { key: 'gamma', labelKey: 'options.gamma', fallback: '0.5', control: { type: 'slider', min: 0, max: 1, step: 0.05 } },
    ],
  },
  {
    groupKey: 'options.groupSound',
    options: [
      { key: 'soundCategory_master', labelKey: 'options.soundMaster', fallback: '1.0', control: { type: 'slider', min: 0, max: 1, step: 0.05 } },
      { key: 'soundCategory_music', labelKey: 'options.soundMusic', fallback: '1.0', control: { type: 'slider', min: 0, max: 1, step: 0.05 } },
      { key: 'soundCategory_ambient', labelKey: 'options.soundAmbient', fallback: '1.0', control: { type: 'slider', min: 0, max: 1, step: 0.05 } },
    ],
  },
  {
    groupKey: 'options.groupGameplay',
    options: [
      { key: 'lang', labelKey: 'options.lang', fallback: 'fr_fr', control: { type: 'text' } },
      { key: 'autoJump', labelKey: 'options.autoJump', fallback: 'false', control: { type: 'bool' } },
      { key: 'toggleCrouch', labelKey: 'options.toggleCrouch', fallback: 'false', control: { type: 'bool' } },
      { key: 'guiScale', labelKey: 'options.guiScale', fallback: '0', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.guiScaleAuto' },
        { value: '1', labelKey: 'options.guiScaleSmall' },
        { value: '2', labelKey: 'options.guiScaleNormal' },
        { value: '3', labelKey: 'options.guiScaleLarge' },
      ] } },
    ],
  },
]

const KNOWN_KEYS = new Set(KNOWN.flatMap((g) => g.options.map((o) => o.key)))

export function OptionsTab({ instance }: { instance: Instance }) {
  const t = useT()
  const navigate = useNavigate()
  // Copie locale du favori : la page ne possède pas l'instance (elle la
  // reçoit), et remonter ce seul champ jusqu'à son propriétaire pour une
  // étoile ne vaut pas le fil à tirer. La source de vérité reste la base,
  // relue au prochain chargement de la page.
  const [favorite, setFavorite] = useState(instance.favorite)
  useEffect(() => { setFavorite(instance.favorite) }, [instance.favorite])
  const [advanced, setAdvanced] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [fileExists, setFileExists] = useState(true)
  /** Ce qui est sur le disque. */
  const [saved, setSaved] = useState<Record<string, string>>({})
  /** Les valeurs modifiées et pas encore écrites. */
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [filter, setFilter] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const entries = await api.mcOptions.read(instance.id)
      setFileExists(entries.length > 0)
      setSaved(Object.fromEntries(entries.map((e) => [e.key, e.value])))
      setDraft({})
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }, [instance.id])

  useEffect(() => { void load() }, [load])

  const value = (key: string, fallback = '') => draft[key] ?? saved[key] ?? fallback
  const set = (key: string, next: string) => setDraft((d) => ({ ...d, [key]: next }))

  const dirty = useMemo(
    () => Object.entries(draft).filter(([k, v]) => (saved[k] ?? '') !== v),
    [draft, saved],
  )

  const save = async () => {
    if (dirty.length === 0 || saving) return
    setSaving(true)
    try {
      const changes: McOption[] = dirty.map(([key, value]) => ({ key, value }))
      const entries = await api.mcOptions.write(instance.id, changes)
      setSaved(Object.fromEntries(entries.map((e) => [e.key, e.value])))
      setDraft({})
      setFileExists(true)
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  // ── Réglages YuyuFrame ────────────────────────────────────────────────────
  // Ils ne vivent pas dans options.txt mais en base, et se sauvegardent
  // immédiatement : ce sont des actions franches (rendre favori, ouvrir le
  // dossier), pas un formulaire à valider.
  const toggleFavorite = async () => {
    try {
      setFavorite((await api.instances.toggleFavorite(instance.id)).favorite)
    } catch (e) {
      showError(e)
    }
  }

  if (loading) {
    return <EmptyState compact icon={<ButtonSpinner size={18} />} title={t('options.loading')} />
  }

  const rows = advanced
    ? Object.keys(saved)
      .concat(Object.keys(draft).filter((k) => !(k in saved)))
      .filter((k) => k.toLowerCase().includes(filter.toLowerCase()))
      .sort()
    : []

  return (
    <div className="flex flex-col gap-4">

      {/* Barre : bascule de vue + enregistrement */}
      <div className="flex items-center gap-2">
        <div className="flex gap-1 rounded-lg bg-[rgba(255,255,255,0.03)] p-0.5">
          {([false, true] as const).map((mode) => (
            <button
              key={String(mode)}
              onClick={() => setAdvanced(mode)}
              className={`rounded-md px-3 py-1 text-[11px] font-semibold transition-colors duration-150 ${
                advanced === mode ? 'bg-[rgba(75,63,207,0.35)] text-white' : 'text-[rgba(255,255,255,0.35)] hover:text-[rgba(255,255,255,0.6)]'
              }`}
            >
              {t(mode ? 'options.viewAdvanced' : 'options.viewReadable')}
            </button>
          ))}
        </div>

        {advanced && (
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={t('options.filterPlaceholder')}
            className="h-8 flex-1 rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-[12px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)]"
          />
        )}

        <div className="ml-auto flex items-center gap-2">
          {dirty.length > 0 && (
            <span className="text-[11px] text-[rgba(179,163,255,0.9)]">
              {t('options.pending', { count: dirty.length })}
            </span>
          )}
          <motion.button {...press}
            onClick={save}
            disabled={dirty.length === 0 || saving}
            className={`h-8 rounded-lg px-4 text-[12px] font-semibold transition-colors duration-150 ${
              dirty.length === 0 || saving
                ? 'cursor-not-allowed bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.3)]'
                : 'bg-[#4B3FCF] text-white hover:bg-[#6155e8]'
            }`}
          >
            {saving ? t('options.saving') : t('options.save')}
          </motion.button>
        </div>
      </div>

      {!fileExists && (
        <div className="rounded-xl border border-[rgba(250,204,21,0.25)] bg-[rgba(250,204,21,0.08)] px-3.5 py-2.5 text-[11.5px] leading-relaxed text-[rgba(250,204,21,0.85)]">
          {t('options.noFileYet')}
        </div>
      )}

      {/* ── Vue avancée : le fichier entier ─────────────────────────────── */}
      {advanced ? (
        rows.length === 0 ? (
          <EmptyState compact
            icon={<svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.18)" width={22} height={22}><path d="M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h12v2H3v-2z" /></svg>}
            title={t('options.noMatch')}
          />
        ) : (
          <div className="flex flex-col gap-1">
            {rows.map((key) => (
              <div key={key} className="flex items-center gap-3 rounded-lg px-2.5 py-1.5 odd:bg-[rgba(255,255,255,0.02)]">
                <span className="w-[42%] flex-shrink-0 truncate font-mono text-[11.5px] text-[rgba(255,255,255,0.55)]" title={key}>
                  {key}
                </span>
                <input
                  value={value(key)}
                  onChange={(e) => set(key, e.target.value)}
                  className={`h-7 flex-1 rounded-md border bg-[rgba(0,0,0,0.4)] px-2 font-mono text-[11.5px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)] ${
                    key in draft && draft[key] !== saved[key]
                      ? 'border-[rgba(139,92,246,0.55)]'
                      : 'border-[rgba(255,255,255,0.08)]'
                  }`}
                />
              </div>
            ))}
          </div>
        )
      ) : (
        /* ── Vue lisible ────────────────────────────────────────────────── */
        <div className="flex flex-col gap-5">
          {KNOWN.map((group) => (
            <section key={group.groupKey} className="flex flex-col gap-2">
              <h3 className="text-[10.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
                {t(group.groupKey)}
              </h3>
              <div className="flex flex-col gap-1">
                {group.options.map((opt) => (
                  <OptionRow
                    key={opt.key}
                    option={opt}
                    value={value(opt.key, opt.fallback)}
                    changed={opt.key in draft && draft[opt.key] !== saved[opt.key]}
                    onChange={(v) => set(opt.key, v)}
                  />
                ))}
              </div>
            </section>
          ))}

          {/* Les clés inconnues ne sont pas cachées : on dit combien il y en a
              et où les trouver, plutôt que de laisser croire que le fichier
              se résume à ce qui précède. */}
          <button
            onClick={() => setAdvanced(true)}
            className="self-start text-[11px] font-semibold text-[rgba(75,63,207,0.85)] transition-colors duration-150 hover:text-[#8b7ff0]"
          >
            {t('options.othersCount', { count: Object.keys(saved).filter((k) => !KNOWN_KEYS.has(k)).length })}
          </button>

          {/* ── Réglages YuyuFrame ──────────────────────────────────────── */}
          <section className="flex flex-col gap-2">
            <h3 className="text-[10.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
              {t('options.groupYuyu')}
            </h3>
            <div className="flex flex-wrap gap-2">
              <motion.button {...press} onClick={toggleFavorite}
                className={`flex h-8 items-center gap-1.5 rounded-lg border px-3 text-[11.5px] font-semibold transition-colors duration-150 ${
                  favorite
                    ? 'border-[rgba(250,204,21,0.35)] bg-[rgba(250,204,21,0.12)] text-[rgba(250,204,21,0.9)]'
                    : 'border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.03)] text-[rgba(255,255,255,0.5)]'
                }`}
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
                  <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" />
                </svg>
                {t(favorite ? 'options.favoriteOn' : 'options.favoriteOff')}
              </motion.button>

              <motion.button {...press} onClick={() => navigate('/jvm')}
                className="flex h-8 items-center gap-1.5 rounded-lg border border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.03)] px-3 text-[11.5px] font-semibold text-[rgba(255,255,255,0.5)] transition-colors duration-150 hover:text-[rgba(255,255,255,0.8)]"
              >
                {t('options.jvmConfig', { ram: instance.ram_mb >= 1024 ? `${instance.ram_mb / 1024} Go` : `${instance.ram_mb} Mo` })}
              </motion.button>

              <motion.button {...press} onClick={() => api.instances.openFolder(instance.id).catch(showError)}
                className="flex h-8 items-center gap-1.5 rounded-lg border border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.03)] px-3 text-[11.5px] font-semibold text-[rgba(255,255,255,0.5)] transition-colors duration-150 hover:text-[rgba(255,255,255,0.8)]"
              >
                {t('options.openFolder')}
              </motion.button>
            </div>
          </section>
        </div>
      )}
    </div>
  )
}

function OptionRow({ option, value, changed, onChange }: {
  option: KnownOption
  value: string
  changed: boolean
  onChange: (v: string) => void
}) {
  const t = useT()
  const { control } = option

  return (
    <div className={`flex items-center gap-3 rounded-lg px-2.5 py-2 transition-colors duration-150 ${changed ? 'bg-[rgba(75,63,207,0.1)]' : 'odd:bg-[rgba(255,255,255,0.02)]'}`}>
      <span className="w-[40%] flex-shrink-0 text-[12px] text-[rgba(255,255,255,0.65)]">
        {t(option.labelKey)}
      </span>

      {control.type === 'slider' && (
        <>
          <input
            type="range"
            min={control.min}
            max={control.max}
            step={control.step ?? 1}
            value={Number(value) || control.min}
            onChange={(e) => onChange(e.target.value)}
            className="h-1 flex-1 cursor-pointer appearance-none rounded-full bg-[rgba(255,255,255,0.1)] accent-[#4B3FCF]"
          />
          <span className="w-[68px] flex-shrink-0 text-right font-mono text-[11.5px] text-[rgba(255,255,255,0.55)]">
            {value}{control.suffix ?? ''}
          </span>
        </>
      )}

      {control.type === 'bool' && (
        <button
          onClick={() => onChange(value === 'true' ? 'false' : 'true')}
          className={`relative ml-auto h-[18px] w-[34px] flex-shrink-0 rounded-full transition-colors duration-200 ${
            value === 'true' ? 'bg-[rgba(75,63,207,0.9)]' : 'bg-[rgba(255,255,255,0.12)]'
          }`}
        >
          <span className={`absolute top-[3px] h-3 w-3 rounded-full bg-white transition-all duration-200 ${value === 'true' ? 'left-[19px]' : 'left-[3px]'}`} />
        </button>
      )}

      {control.type === 'choice' && (
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          // `color-scheme: dark` : la liste déroulante est dessinée par le
          // système, hors de la page — sans ça elle s'ouvre en blanc sur blanc.
          style={{ colorScheme: 'dark' }}
          className="ml-auto h-7 rounded-md border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-2 text-[11.5px] text-white outline-none"
        >
          {control.choices.map((c) => (
            <option key={c.value} value={c.value}>{t(c.labelKey)}</option>
          ))}
        </select>
      )}

      {control.type === 'text' && (
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="ml-auto h-7 w-[140px] rounded-md border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-2 font-mono text-[11.5px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)]"
        />
      )}
    </div>
  )
}
