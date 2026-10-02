/**
 * Partager les paramètres du launcher par lien (`yuyuframe://settings/…`,
 * `Backend/src/share_link.rs`). Page Paramètres, en-tête : « Partager » et
 * « Paramètres optimisés » (le lien du créateur, `CREATOR_SETTINGS_LINK`).
 *
 * **Tout sauf la langue et le stockage** : la langue est celle de chacun, et
 * le dossier de stockage est un chemin propre à une machine — l'appliquer
 * déplacerait toutes les données du launcher vers un dossier qui n'existe
 * peut-être pas. Pour la même raison, jamais le chemin d'un Java
 * personnalisé : `defaultJvmVendor` ne voyage pas s'il vaut `custom`.
 *
 * Les réglages vivent dans le magasin persisté, donc c'est ici qu'ils sont
 * écrits et **validés** : chaque valeur reçue passe par `parse`, qui n'accepte
 * que ce que l'écran Paramètres pourrait lui-même poser. Un réglage nouveau
 * s'ajoute à `SHARED_SETTINGS`, avec son libellé (celui de la page) et sa
 * validation.
 *
 * Format : une ligne `clé=valeur` par réglage.
 */

import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { formatRam } from '@/lib/format'
import type { t as tFn } from '@/i18n'

/** Lien des paramètres optimisés choisis par le créateur de YuyuFrame. Vide
 *  tant qu'il n'est pas publié : le bouton l'annonce alors comme « bientôt ». */
export const CREATOR_SETTINGS_LINK =
  'yuyuframe://settings/謀瑀㐈葐㔒䱔擠꼃搘橩麅鋒霨唎剈濘浕庺礠䳀䍗㟗壶늏鏻㯕癪䫡獱藢湊戒류澪뻲矽㴇詣뺩䆇䳭봺릎籓唿奒㠀䑚螁䯳䓦뇊疀䉤璷鼐靌塄冠䜀䜺뮝㜆䐊窖熓崓羉葀穜䡛䥜䷵堞蚛베縸佚昭䩷䒓醊諦凚緥溟餔낄偸陙䷴薕缐硤'

type T = typeof tFn
type Value = string | number | boolean | null

interface SharedSetting {
  key: string
  /** Clé de traduction du libellé, celle de la page Paramètres. */
  label: string
  read: () => Value | Promise<Value>
  /** Valeur reçue → valeur sûre, ou `undefined` si elle est refusée. */
  parse: (raw: string) => Value | undefined
  apply: (value: Value) => void | Promise<void>
  format: (value: Value, t: T) => string
}

const bool = (raw: string) => (raw === 'true' ? true : raw === 'false' ? false : undefined)
const intIn = (min: number, max: number) => (raw: string) => {
  const n = Number(raw)
  return Number.isInteger(n) && n >= min && n <= max ? n : undefined
}
const onOff = (value: Value, t: T) => (value ? t('settingsShare.on') : t('settingsShare.off'))
const store = () => useStore.getState()

/** Un réglage booléen du magasin. */
function toggle(key: string, label: string, get: () => boolean, set: (v: boolean) => void): SharedSetting {
  return { key, label, read: get, parse: bool, apply: (v) => set(v as boolean), format: onOff }
}

const VENDORS = ['auto', 'temurin', 'openj9', 'graal'] as const
const VENDOR_NAMES: Record<string, string> = { temurin: 'Temurin', openj9: 'OpenJ9', graal: 'GraalVM' }

export const SHARED_SETTINGS: SharedSetting[] = [
  {
    key: 'defaultRam',
    label: 'settings.lancement.ramLabel',
    read: () => store().defaultRam,
    parse: intIn(512, 65_536),
    apply: (v) => store().setDefaultRam(v as number),
    format: (v) => formatRam(v as number),
  },
  {
    key: 'customRamMb',
    label: 'settings.lancement.customRamLabel',
    read: () => store().customRamMb,
    parse: (raw) => (raw === 'none' ? null : intIn(8_193, 65_536)(raw)),
    apply: (v) => store().setCustomRamMb(v as number | null),
    format: (v, t) => (v === null ? t('settingsShare.none') : formatRam(v as number)),
  },
  {
    key: 'defaultJvmVendor',
    label: 'settingsShare.jvmVendor',
    // Un Java personnalisé est un chemin de cette machine : il part en « auto ».
    read: () => (store().defaultJvmVendor === 'custom' ? 'auto' : store().defaultJvmVendor),
    parse: (raw) => (VENDORS as readonly string[]).includes(raw) ? raw : undefined,
    apply: (v) => store().setDefaultJvmVendor(v as (typeof VENDORS)[number]),
    format: (v, t) => VENDOR_NAMES[v as string] ?? t('share.jvmAuto'),
  },
  {
    key: 'defaultGcPolicy',
    label: 'settingsShare.gcPolicy',
    read: () => store().defaultGcPolicy,
    parse: (raw) => (/^[a-z0-9]{1,32}$/i.test(raw) ? raw : undefined),
    apply: (v) => store().setDefaultGcPolicy(v as string),
    format: (v, t) => (v === 'auto' ? t('share.jvmAuto') : String(v)),
  },
  toggle('closeOnLaunch', 'settings.lancement.hideOnLaunchLabel', () => store().closeOnLaunch, (v) => store().setCloseOnLaunch(v)),
  toggle('allowBackground', 'settings.lancement.backgroundLabel', () => store().allowBackground, (v) => store().setAllowBackground(v)),
  toggle('showConsole', 'settings.lancement.consoleLabel', () => store().showConsole, (v) => store().setShowConsole(v)),
  toggle('avoidBetaDependencies', 'settings.instances.avoidBetaLabel', () => store().avoidBetaDependencies, (v) => store().setAvoidBetaDependencies(v)),
  toggle('syncGameSettings', 'settings.instances.syncGameSettingsLabel', () => store().syncGameSettings, (v) => store().setSyncGameSettings(v)),
  {
    key: 'instanceSyncMode',
    label: 'settings.instances.startupSyncLabel',
    read: () => store().instanceSyncMode,
    parse: (raw) => (raw === 'db_wins' || raw === 'disk_wins' ? raw : undefined),
    apply: (v) => store().setInstanceSyncMode(v as 'db_wins' | 'disk_wins'),
    format: (v, t) => (v === 'db_wins' ? t('settings.instances.dbWinsLabel') : t('settings.instances.diskWinsLabel')),
  },
  toggle('showHomeServers', 'settings.serveurs.showHomeLabel', () => store().showHomeServers, (v) => store().setShowHomeServers(v)),
  toggle('confirmServerLaunch', 'settings.serveurs.confirmLaunchLabel', () => store().confirmServerLaunch, (v) => store().setConfirmServerLaunch(v)),
  {
    key: 'analytics',
    label: 'settings.confidentialite.analyticsLabel',
    // Enregistré côté Rust, pas dans le magasin.
    read: async () => !(await api.analytics.isDisabled()),
    parse: bool,
    apply: (v) => api.analytics.setDisabled(!(v as boolean)),
    format: onOff,
  },
  {
    key: 'brightness',
    label: 'settings.apparence.brightnessLabel',
    read: () => store().brightness,
    parse: intIn(40, 200),
    apply: (v) => store().setBrightness(v as number),
    format: (v) => `${v}%`,
  },
]

const BY_KEY = new Map(SHARED_SETTINGS.map((s) => [s.key, s]))

/** Les réglages actuels, en texte de lien. */
export async function currentSettingsText(): Promise<string> {
  const lines = await Promise.all(
    SHARED_SETTINGS.map(async (s) => {
      const value = await s.read()
      return `${s.key}=${value === null ? 'none' : String(value)}`
    }),
  )
  return lines.join('\n')
}

/** Un changement proposé par un lien : ce qui est, ce qui serait. */
export interface SettingChange {
  setting: SharedSetting
  current: Value
  next: Value
}

/**
 * Texte reçu → changements à proposer. Clés inconnues (version plus récente)
 * et valeurs refusées par `parse` sont ignorées, sans bruit : on applique ce
 * qu'on comprend. Les réglages déjà à la bonne valeur ne sont pas listés.
 */
export async function settingsChanges(text: string): Promise<SettingChange[]> {
  const changes: SettingChange[] = []
  for (const line of text.split('\n')) {
    const sep = line.indexOf('=')
    if (sep <= 0) continue
    const setting = BY_KEY.get(line.slice(0, sep))
    if (!setting) continue
    const next = setting.parse(line.slice(sep + 1))
    if (next === undefined) continue
    const current = await setting.read()
    if (current !== next) changes.push({ setting, current, next })
  }
  return changes
}

export async function applySettingChanges(changes: SettingChange[]) {
  for (const { setting, next } of changes) await setting.apply(next)
}
