import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { press } from '@/lib/motion'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { WarningIcon } from '@/components/home/AgentModal'
import { EmptyState } from '@/components/ui/EmptyState'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { OptionSearchBar } from './OptionSearchBar'
import { LanguagePickerModal } from './LanguagePickerModal'
import { LANGUAGE_BY_CODE } from './gameLanguages'
import { Flag } from '@/components/ui/Flag'
import type { SearchCandidate } from './optionSearch'
import { tIn, useT } from '@/i18n'
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
  /** Ouvre le sélecteur de langue plutôt que d'exiger le code exact. */
  | { type: 'language' }
  /** Se règle en appuyant sur la touche voulue — voir `KeybindButton`. */
  | { type: 'keybind' }

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
      { key: 'lang', labelKey: 'options.lang', fallback: 'fr_fr', control: { type: 'language' } },
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

/**
 * Les réglages du client intégré que l'on présente ici.
 *
 * ── Pourquoi ceux-là et pas les modules ───────────────────────────────────
 * Les modules ne sont pas les mêmes d'une version à l'autre : les animations
 * 1.7, les bascules sprint/sneak et le FOV fixe n'existent qu'en 1.8.9, la
 * vision claire et la citrouille qu'à partir de la 1.21.11 (voir
 * `ModuleRegistry`, drapeaux `IS_1_8_9`/`IS_26_1`). Les lister ici
 * obligerait le launcher à refaire ce tri version par version, et à le
 * refaire à chaque module ajouté — pour finir par proposer des interrupteurs
 * qui ne commandent rien sur la version choisie.
 *
 * Ce qui suit vient au contraire de `GlobalUiSettings` (module `ui-settings`),
 * les préférences de l'agent lui-même : elles existent partout où l'agent se
 * charge, quelle que soit la version, et ce sont des contrôles ordinaires —
 * un curseur, un interrupteur, une liste. Les modules et leurs réglages
 * propres se règlent en jeu, et restent visibles dans la vue avancée.
 */
const AGENT_KNOWN: Array<{ groupKey: string; options: KnownOption[] }> = [
  {
    groupKey: 'options.groupAgentLook',
    options: [
      { key: 'ui-settings.setting.themeMode', labelKey: 'options.agentTheme', fallback: '0', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.agentThemeDark' },
        { value: '1', labelKey: 'options.agentThemeLight' },
      ] } },
      // Mêmes trois paliers que la taille d'interface de Minecraft : autant
      // réutiliser les libellés déjà traduits.
      { key: 'ui-settings.setting.uiSize', labelKey: 'options.agentUiSize', fallback: '1', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.guiScaleSmall' },
        { value: '1', labelKey: 'options.guiScaleNormal' },
        { value: '2', labelKey: 'options.guiScaleLarge' },
      ] } },
      { key: 'ui-settings.setting.cardLayout', labelKey: 'options.agentCards', fallback: '2', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.agentCardsDetailed' },
        { value: '1', labelKey: 'options.agentCardsCompact' },
        { value: '2', labelKey: 'options.agentCardsGrid' },
      ] } },
      { key: 'ui-settings.setting.separateFavorites', labelKey: 'options.agentSeparateFavorites', fallback: 'true', control: { type: 'bool' } },
    ],
  },
  {
    groupKey: 'options.groupAgentHud',
    options: [
      // 47 % = l'opacité de départ du panneau HUD (alpha 120/255).
      { key: 'ui-settings.setting.hudOpacity', labelKey: 'options.agentHudOpacity', fallback: '47', control: { type: 'slider', min: 10, max: 100, suffix: ' %' } },
      { key: 'ui-settings.setting.cornerRadius', labelKey: 'options.agentHudRadius', fallback: '2', control: { type: 'slider', min: 0, max: 16, suffix: ' px' } },
      { key: 'ui-settings.setting.hudGlassBackground', labelKey: 'options.agentHudGlass', fallback: 'false', control: { type: 'bool' } },
      { key: 'ui-settings.setting.showHudInInventory', labelKey: 'options.agentHudInInventory', fallback: 'true', control: { type: 'bool' } },
      { key: 'ui-settings.setting.showHudInContainers', labelKey: 'options.agentHudInContainers', fallback: 'true', control: { type: 'bool' } },
      { key: 'ui-settings.setting.showHudInChat', labelKey: 'options.agentHudInChat', fallback: 'true', control: { type: 'bool' } },
    ],
  },
  {
    groupKey: 'options.groupAgentGeneral',
    options: [
      // Les langues sont leurs propres noms : traduire « Deutsch » n'aiderait
      // personne à le reconnaître. L'ordre est celui de `Lang.LANGUAGE_IDS`,
      // l'agent persistant l'indice et non le code.
      { key: 'ui-settings.setting.language', labelKey: 'options.agentLanguage', fallback: '0', control: { type: 'choice', choices: [
        { value: '0', labelKey: 'options.agentLangFr' },
        { value: '1', labelKey: 'options.agentLangEn' },
        { value: '2', labelKey: 'options.agentLangEs' },
        { value: '3', labelKey: 'options.agentLangDe' },
        { value: '4', labelKey: 'options.agentLangPt' },
        { value: '5', labelKey: 'options.agentLangRu' },
      ] } },
      { key: 'ui-settings.setting.menuKey', labelKey: 'options.agentMenuKey', fallback: 'RSHIFT', control: { type: 'keybind' } },
    ],
  },
]

/** Clé → valeur de départ, sous forme de texte : le fichier ne contient que
 *  des chaînes, et une clé absente vaut ce défaut. */
const AGENT_DEFAULTS: Record<string, string> = Object.fromEntries(
  AGENT_KNOWN.flatMap((g) => g.options.map((o) => [o.key, o.fallback])),
)

/** L'agent écrit ses curseurs en flottant (`47.058823`). On l'arrondit pour
 *  l'affichage seulement — la valeur du fichier n'est touchée que si on
 *  déplace effectivement le curseur. */
function agentSliderText(raw: string): string {
  const n = Number(raw)
  return Number.isFinite(n) ? String(Math.round(n * 10) / 10) : raw
}

export function OptionsTab({ instance }: { instance: Instance }) {
  const t = useT()
  const [advanced, setAdvanced] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [fileExists, setFileExists] = useState(true)
  /** Ce qui est sur le disque. */
  const [saved, setSaved] = useState<Record<string, string>>({})
  /** Les valeurs modifiées et pas encore écrites. */
  const [draft, setDraft] = useState<Record<string, string>>({})
  /** Réglage visé par la recherche : mis en évidence puis amené à l'œil, et
   *  relâché au bout de quelques secondes — c'est un repère, pas un état. */
  const [highlight, setHighlight] = useState<string | null>(null)
  const rowRefs = useRef<Record<string, HTMLDivElement | null>>({})

  /* ── Client intégré ──────────────────────────────────────────────────────
   * Deuxième fichier, même écran. Les modules de l'agent vivent dans leur
   * propre `.properties` (voir `agent_options.rs`) : deux états séparés, un
   * seul bouton « Enregistrer » — pour qui règle son instance, c'est une
   * seule page de réglages, pas deux fichiers.
   *
   * L'agent n'est pas tissable partout. L'état vient du Rust, seul détenteur
   * de la liste des versions supportées ; ici il ne sert qu'à dire pourquoi
   * la section est en lecture seule, c'est encore le Rust qui tranche au
   * lancement. */
  const [agentSaved, setAgentSaved] = useState<Record<string, string>>({})
  const [agentDraft, setAgentDraft] = useState<Record<string, string>>({})
  const [agentBlocked, setAgentBlocked] = useState(false)
  useEffect(() => {
    let alive = true
    api.launch
      .agentStatus(instance.mc_version, instance.loader)
      .then((s) => { if (alive) setAgentBlocked(!s.available) })
      .catch(() => { if (alive) setAgentBlocked(false) })
    return () => { alive = false }
  }, [instance.mc_version, instance.loader])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      // Les deux fichiers ensemble : un agent qui n'a jamais tourné rend une
      // liste vide, ce n'est pas une erreur — les modules repartent alors de
      // leurs valeurs par défaut.
      const [entries, agentEntries] = await Promise.all([
        api.mcOptions.read(instance.id),
        api.agentOptions.read(instance.id),
      ])
      setFileExists(entries.length > 0)
      setSaved(Object.fromEntries(entries.map((e) => [e.key, e.value])))
      setDraft({})
      setAgentSaved(Object.fromEntries(agentEntries.map((e) => [e.key, e.value])))
      setAgentDraft({})
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }, [instance.id])

  useEffect(() => { void load() }, [load])

  const value = (key: string, fallback = '') => draft[key] ?? saved[key] ?? fallback
  const set = (key: string, next: string) => setDraft((d) => ({ ...d, [key]: next }))

  const agentValue = (key: string, fallback = '') => agentDraft[key] ?? agentSaved[key] ?? fallback
  const setAgent = (key: string, next: string) => setAgentDraft((d) => ({ ...d, [key]: next }))

  const dirty = useMemo(
    () => Object.entries(draft).filter(([k, v]) => (saved[k] ?? '') !== v),
    [draft, saved],
  )
  /** Même règle pour l'agent, mais avec le défaut du module en référence :
   *  une clé absente du fichier vaut ce défaut, la remettre à cette valeur
   *  n'est donc pas une modification. */
  const agentDirty = useMemo(
    () => Object.entries(agentDraft).filter(
      ([k, v]) => (agentSaved[k] ?? AGENT_DEFAULTS[k] ?? '') !== v,
    ),
    [agentDraft, agentSaved],
  )

  /** Tout ce que la recherche peut trouver : les réglages que l'on sait
   *  nommer, plus les clés présentes dans le fichier que l'on ne connaît pas.
   *  Les secondes n'ont ni libellé ni groupe — c'est exactement ce que la
   *  recherche affiche, une clé brute, sans prétendre la traduire. */
  const candidates = useMemo<SearchCandidate[]>(() => {
    const known = KNOWN.flatMap((group) =>
      group.options.map((o) => {
        const label = t(o.labelKey)
        const groupLabel = t(group.groupKey)
        const english = tIn('en', o.labelKey)
        const englishGroup = tIn('en', group.groupKey)
        return {
          key: o.key,
          label,
          group: groupLabel,
          // L'anglais reste cherchable même quand l'interface est dans une
          // autre langue : on désigne couramment une option par son nom
          // anglais alors que le jeu tourne en français. Ajouté seulement
          // s'il diffère, sinon on comparerait deux fois la même chaîne.
          aliases: english === label ? undefined : [english],
          groupAliases: englishGroup === groupLabel ? undefined : [englishGroup],
        }
      }),
    )
    const unknown = Object.keys(saved)
      .filter((k) => !KNOWN_KEYS.has(k))
      .map((key) => ({ key }))
    return [...known, ...unknown]
  }, [saved, t])

  /** Amène le réglage choisi à l'œil : bascule vers la vue qui sait le
   *  montrer, puis fait défiler jusqu'à lui. Une clé que la vue lisible ne
   *  connaît pas n'existe que dans la vue avancée. */
  const goToOption = (key: string) => {
    setAdvanced(!KNOWN_KEYS.has(key))
    setHighlight(key)
  }

  useEffect(() => {
    if (!highlight) return
    // Un cran d'attente : la bascule de vue doit être peinte avant qu'on
    // puisse viser une ligne qui vient seulement d'exister.
    const scroll = setTimeout(() => {
      rowRefs.current[highlight]?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }, 60)
    const release = setTimeout(() => setHighlight(null), 2400)
    return () => { clearTimeout(scroll); clearTimeout(release) }
  }, [highlight, advanced])

  /** Un seul geste pour les deux fichiers. Chacun n'est réécrit que s'il a
   *  changé — enregistrer des réglages Minecraft n'a pas à créer un fichier
   *  d'agent sur une instance qui n'en a jamais eu. */
  const save = async () => {
    if ((dirty.length === 0 && agentDirty.length === 0) || saving) return
    setSaving(true)
    try {
      if (dirty.length > 0) {
        const changes: McOption[] = dirty.map(([key, value]) => ({ key, value }))
        const entries = await api.mcOptions.write(instance.id, changes)
        setSaved(Object.fromEntries(entries.map((e) => [e.key, e.value])))
        setDraft({})
        setFileExists(true)
      }
      if (agentDirty.length > 0) {
        const changes: McOption[] = agentDirty.map(([key, value]) => ({ key, value }))
        const entries = await api.agentOptions.write(instance.id, changes)
        setAgentSaved(Object.fromEntries(entries.map((e) => [e.key, e.value])))
        setAgentDraft({})
      }
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <EmptyState compact icon={<ButtonSpinner size={18} />} title={t('options.loading')} />
  }

  const rows = advanced
    ? Object.keys(saved)
      .concat(Object.keys(draft).filter((k) => !(k in saved)))
      .sort()
    : []

  /** Les clés du fichier de l'agent, dans la vue avancée. Celles qu'on affiche
   *  déjà plus haut avec leur interrupteur en sont retirées : les voir deux
   *  fois, une fois nommées et une fois brutes, ne dirait rien de plus. */
  const agentRows = advanced
    ? Object.keys(agentSaved)
      .concat(Object.keys(agentDraft).filter((k) => !(k in agentSaved)))
      .filter((k) => !(k in AGENT_DEFAULTS))
      .sort()
    : []

  const pending = dirty.length + agentDirty.length

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

        {/* La recherche est offerte dans les deux vues, et c'est elle qui
            décide où emmener : un réglage connu se montre mieux avec son
            contrôle, une clé brute n'existe que dans la vue avancée. */}
        <OptionSearchBar candidates={candidates} onPick={goToOption} />

        <div className="flex items-center gap-2">
          {pending > 0 && (
            <span className="text-[11px] text-[rgba(179,163,255,0.9)]">
              {t('options.pending', { count: pending })}
            </span>
          )}
          <motion.button {...press}
            onClick={save}
            disabled={pending === 0 || saving}
            className={`h-8 rounded-lg px-4 text-[12px] font-semibold transition-colors duration-150 ${
              pending === 0 || saving
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
        rows.length === 0 && agentRows.length === 0 ? (
          <EmptyState compact
            icon={<svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.18)" width={22} height={22}><path d="M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h12v2H3v-2z" /></svg>}
            title={t('options.emptyAdvanced')}
          />
        ) : (
          <div className="flex flex-col gap-1">
            {rows.map((key) => (
              <div
                key={key}
                ref={(el) => { rowRefs.current[key] = el }}
                className={`flex items-center gap-3 rounded-lg px-2.5 py-1.5 transition-colors duration-300 ${
                  highlight === key
                    ? 'bg-[rgba(75,63,207,0.28)] ring-1 ring-[rgba(139,92,246,0.6)]'
                    : 'odd:bg-[rgba(255,255,255,0.02)]'
                }`}
              >
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

            {/* Le fichier de l'agent à la suite, séparé et annoncé : ce sont
                deux fichiers différents, et une clé `zoom.setting.niveau`
                perdue au milieu des raccourcis de Minecraft ne se
                comprendrait pas. */}
            {agentRows.length > 0 && (
              <>
                <h3 className="mt-4 text-[10.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
                  {t('options.groupAgent')}
                </h3>
                {agentRows.map((key) => (
                  <div
                    key={key}
                    className="flex items-center gap-3 rounded-lg px-2.5 py-1.5 odd:bg-[rgba(255,255,255,0.02)]"
                  >
                    <span className="w-[42%] flex-shrink-0 truncate font-mono text-[11.5px] text-[rgba(255,255,255,0.55)]" title={key}>
                      {key}
                    </span>
                    <input
                      value={agentValue(key)}
                      onChange={(e) => setAgent(key, e.target.value)}
                      disabled={agentBlocked}
                      className={`h-7 flex-1 rounded-md border bg-[rgba(0,0,0,0.4)] px-2 font-mono text-[11.5px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)] ${
                        key in agentDraft && agentDraft[key] !== agentSaved[key]
                          ? 'border-[rgba(139,92,246,0.55)]'
                          : 'border-[rgba(255,255,255,0.08)]'
                      }`}
                    />
                  </div>
                ))}
              </>
            )}
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
                    highlighted={highlight === opt.key}
                    rowRef={(el) => { rowRefs.current[opt.key] = el }}
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

          {/* ── Réglages du client intégré ──────────────────────────────── */}
          <div className="flex flex-col gap-2 border-t border-[rgba(255,255,255,0.06)] pt-4">
            <h3 className="text-[10.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
              {t('options.groupAgent')}
            </h3>
            <p className="text-[11px] leading-relaxed text-[rgba(255,255,255,0.4)]">
              {t('options.agentIntro')}
            </p>

            {/* Dit avant de laisser toucher : sur une version non supportée,
                rien n'est injecté, et ces interrupteurs ne changeraient rien
                au jeu qui va se lancer. */}
            {agentBlocked && (
              <div className="flex items-start gap-2 rounded-xl border border-[rgba(250,204,21,0.25)] bg-[rgba(250,204,21,0.08)] px-3.5 py-2.5 text-[11.5px] leading-relaxed text-[rgba(250,204,21,0.85)]">
                <WarningIcon className="mt-[2px] h-3.5 w-3.5 flex-shrink-0" />
                {t('options.agentUnavailable', { version: instance.mc_version })}
              </div>
            )}
          </div>

          {/* Les mêmes contrôles que pour Minecraft, sur l'autre fichier :
              un réglage reste un réglage, et l'œil n'a pas à réapprendre
              une ligne parce qu'elle vient d'ailleurs. */}
          {AGENT_KNOWN.map((group) => (
            <section
              key={group.groupKey}
              className={`flex flex-col gap-2 ${agentBlocked ? 'pointer-events-none opacity-45' : ''}`}
            >
              <h4 className="text-[10.5px] font-bold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
                {t(group.groupKey)}
              </h4>
              <div className="flex flex-col gap-1">
                {group.options.map((opt) => (
                  <OptionRow
                    key={opt.key}
                    option={opt}
                    value={opt.control.type === 'slider'
                      ? agentSliderText(agentValue(opt.key, opt.fallback))
                      : agentValue(opt.key, opt.fallback)}
                    changed={opt.key in agentDraft && agentDraft[opt.key] !== (agentSaved[opt.key] ?? opt.fallback)}
                    highlighted={false}
                    rowRef={() => {}}
                    onChange={(v) => setAgent(opt.key, v)}
                  />
                ))}
              </div>
            </section>
          ))}

          {/* Même honnêteté que pour options.txt : ce qui se règle en jeu
              (couleurs, seuils, placement des HUD) existe et se voit, on dit
              simplement où. */}
          {agentRows.length === 0 && Object.keys(agentSaved).length === 0 ? (
            <p className="text-[11px] leading-relaxed text-[rgba(255,255,255,0.35)]">
              {t('options.agentNoFileYet')}
            </p>
          ) : (
            <button
              onClick={() => setAdvanced(true)}
              className="self-start text-[11px] font-semibold text-[rgba(75,63,207,0.85)] transition-colors duration-150 hover:text-[#8b7ff0]"
            >
              {t('options.agentOthersCount', {
                count: Object.keys(agentSaved).filter((k) => !(k in AGENT_DEFAULTS)).length,
              })}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function OptionRow({ option, value, changed, highlighted, rowRef, onChange }: {
  option: KnownOption
  value: string
  changed: boolean
  highlighted: boolean
  rowRef: (el: HTMLDivElement | null) => void
  onChange: (v: string) => void
}) {
  const t = useT()
  const { control } = option
  const [pickingLanguage, setPickingLanguage] = useState(false)
  const language = control.type === 'language' ? LANGUAGE_BY_CODE.get(value) : undefined

  return (
    <div
      ref={rowRef}
      className={`flex items-center gap-3 rounded-lg px-2.5 py-2 transition-colors duration-300 ${
        highlighted
          ? 'bg-[rgba(75,63,207,0.28)] ring-1 ring-[rgba(139,92,246,0.6)]'
          : changed
            ? 'bg-[rgba(75,63,207,0.1)]'
            : 'odd:bg-[rgba(255,255,255,0.02)]'
      }`}
    >
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

      {control.type === 'keybind' && (
        <KeybindButton value={value} onChange={onChange} />
      )}

      {control.type === 'text' && (
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="ml-auto h-7 w-[140px] rounded-md border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-2 font-mono text-[11.5px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)]"
        />
      )}

      {control.type === 'language' && (
        <>
          <button
            onClick={() => setPickingLanguage(true)}
            className="ml-auto flex h-7 items-center gap-2 rounded-md border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] pl-2 pr-2.5 transition-colors duration-150 hover:border-[rgba(75,63,207,0.5)]"
          >
            {language ? (
              <>
                <Flag spec={language.flag} size={16} />
                <span className="text-[11.5px] text-white">{language.native}</span>
              </>
            ) : (
              // Code hors de la liste proposée : montré tel quel plutôt que
              // remplacé par un nom inventé.
              <span className="font-mono text-[11.5px] text-white">{value || '—'}</span>
            )}
            <svg viewBox="0 0 10 6" fill="currentColor" width={9} height={6} className="opacity-40">
              <path d="M0 0l5 6 5-6z" />
            </svg>
          </button>

          {pickingLanguage && (
            <LanguagePickerModal
              current={value}
              onPick={onChange}
              onClose={() => setPickingLanguage(false)}
            />
          )}
        </>
      )}
    </div>
  )
}

/**
 * Nom de touche attendu par l'agent, à partir du code physique du navigateur.
 *
 * Les deux désignent la **position** de la touche et non le caractère qu'elle
 * produit : `KeyA` est la touche à gauche de la rangée du milieu, `Q` sur un
 * clavier AZERTY, exactement comme `GLFW_KEY_A`. Une correspondance directe
 * est donc juste, sans se préoccuper de la disposition du clavier.
 *
 * La table reprend `UiInputPollerModern.buildCapturableKeys` — seuls ces noms
 * sont résolus par l'agent ; un nom absent y vaut « aucune touche ».
 */
const KEY_NAMES: Record<string, string> = {
  Space: 'SPACE', Enter: 'ENTER', Tab: 'TAB', Escape: 'ESCAPE',
  ShiftLeft: 'LSHIFT', ShiftRight: 'RSHIFT', ControlLeft: 'LCTRL', ControlRight: 'RCTRL',
  AltLeft: 'LALT', AltRight: 'RALT', MetaLeft: 'LSUPER', MetaRight: 'RSUPER', ContextMenu: 'MENU',
  ArrowLeft: 'LEFT', ArrowRight: 'RIGHT', ArrowUp: 'UP', ArrowDown: 'DOWN',
  Backspace: 'BACKSPACE', Delete: 'DELETE', CapsLock: 'CAPSLOCK', Backquote: 'GRAVE',
  Insert: 'INSERT', Home: 'HOME', End: 'END', PageUp: 'PAGEUP', PageDown: 'PAGEDOWN',
  Minus: 'MINUS', Equal: 'EQUAL', BracketLeft: 'LBRACKET', BracketRight: 'RBRACKET',
  Backslash: 'BACKSLASH', Semicolon: 'SEMICOLON', Quote: 'APOSTROPHE',
  Comma: 'COMMA', Period: 'PERIOD', Slash: 'SLASH',
  NumpadDecimal: 'NUMDECIMAL', NumpadDivide: 'NUMDIVIDE', NumpadMultiply: 'NUMMULTIPLY',
  NumpadSubtract: 'NUMSUBTRACT', NumpadAdd: 'NUMADD', NumpadEnter: 'NUMENTER', NumpadEqual: 'NUMEQUAL',
  ScrollLock: 'SCROLLLOCK', NumLock: 'NUMLOCK', PrintScreen: 'PRINTSCREEN', Pause: 'PAUSE',
  // La touche en plus des claviers ISO, à gauche de W sur AZERTY.
  IntlBackslash: 'WORLD2',
}

function agentKeyName(code: string): string | null {
  if (KEY_NAMES[code]) return KEY_NAMES[code]
  if (/^Key[A-Z]$/.test(code)) return code.slice(3)
  if (/^Digit[0-9]$/.test(code)) return code.slice(5)
  if (/^Numpad[0-9]$/.test(code)) return `NUM${code.slice(6)}`
  if (/^F([1-9]|1[0-9]|2[0-5])$/.test(code)) return code
  return null
}

const MODIFIER_NAMES = new Set(['LSHIFT', 'RSHIFT', 'LCTRL', 'RCTRL', 'LALT', 'RALT', 'LSUPER', 'RSUPER'])

/**
 * Touche d'ouverture du menu en jeu : on appuie dessus, on ne l'écrit pas.
 *
 * Taper « RSHIFT » à la main supposait de connaître le vocabulaire de GLFW,
 * et une faute de frappe ne se voyait qu'en jeu, en constatant que le menu ne
 * s'ouvrait plus. La capture supprime les deux problèmes.
 *
 * Les modificateurs maintenus sont conservés : `Ctrl` + `K` donne `LCTRL+K`,
 * la combinaison que l'agent sait relire. Un modificateur seul reste une
 * touche valable — c'est le cas du défaut, Maj droite.
 */
function KeybindButton({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const t = useT()
  const [capturing, setCapturing] = useState(false)

  useEffect(() => {
    if (!capturing) return
    const onKey = (e: KeyboardEvent) => {
      // Sans ça, Tab quitterait le bouton et Espace le re-déclencherait :
      // pendant la capture, aucune touche n'appartient plus à la page.
      e.preventDefault()
      e.stopPropagation()
      if (e.code === 'Escape') { setCapturing(false); return }
      const name = agentKeyName(e.code)
      // Touche hors de la table de l'agent : on ne l'enregistre pas, elle ne
      // serait jamais résolue en jeu. La capture reste ouverte.
      if (!name) return
      const mods = MODIFIER_NAMES.has(name)
        ? []
        : [
          e.ctrlKey && 'LCTRL',
          e.shiftKey && 'LSHIFT',
          e.altKey && 'LALT',
          e.metaKey && 'LSUPER',
        ].filter(Boolean) as string[]
      onChange([...mods, name].join('+'))
      setCapturing(false)
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [capturing, onChange])

  return (
    <div className="ml-auto flex items-center gap-1.5">
      <button
        onClick={() => setCapturing((c) => !c)}
        onBlur={() => setCapturing(false)}
        className={`h-7 min-w-[120px] rounded-md border px-2.5 font-mono text-[11.5px] outline-none transition-colors duration-150 ${
          capturing
            ? 'border-[rgba(139,92,246,0.7)] bg-[rgba(75,63,207,0.25)] text-white'
            : 'border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] text-white hover:border-[rgba(75,63,207,0.5)]'
        }`}
      >
        {capturing
          ? <span className="font-sans text-[11px] text-[rgba(199,190,255,0.9)]">{t('options.keybindPress')}</span>
          : value && value !== 'NONE'
            ? value
            : <span className="font-sans text-[11px] text-[rgba(255,255,255,0.35)]">{t('options.keybindNone')}</span>}
      </button>
      <button
        onClick={() => { setCapturing(false); onChange('NONE') }}
        title={t('options.keybindClear')}
        aria-label={t('options.keybindClear')}
        className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] text-[rgba(255,255,255,0.4)] transition-colors duration-150 hover:text-[rgba(255,255,255,0.8)]"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}>
          <path d="M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.88 18.3 9.17 12 2.88 5.71 4.3 4.29l6.29 6.3 6.3-6.3z" />
        </svg>
      </button>
    </div>
  )
}
