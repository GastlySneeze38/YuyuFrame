/**
 * Aperçu navigateur : ouvrir l'interface dans Chrome, sans Tauri.
 *
 * Sert à regarder les écrans à n'importe quelle taille avec le mode appareil
 * de l'inspecteur de Chrome, que WebView2 n'a pas. `npm run dev` (ou
 * `cargo tauri dev`, qui le lance), puis http://localhost:5173 dans Chrome.
 *
 * Chargé par `main.tsx` seulement en développement ET hors de Tauri, par un
 * `import()` placé dans une branche `import.meta.env.DEV` : en release la
 * branche disparaît à la compilation, et ce fichier avec elle.
 *
 * Ce ne sont pas les données du launcher : il n'y a pas de Rust derrière.
 * Chaque commande reçoit une réponse inventée — réaliste pour celles du
 * démarrage et de l'accueil, vide (liste vide ou `null`) pour les autres. Une
 * commande qui n'a pas de réponse écrite est signalée une fois dans la
 * console : c'est la liste de ce qu'il reste à remplir quand un écran en a
 * besoin. Les amis, le compétitif et les missions ont déjà leurs données de
 * démonstration (`useDemo`, atelier Ctrl+Maj+M).
 */
import { mockIPC, mockWindows } from '@tauri-apps/api/mocks'
import type { AuthStatus, Instance, SystemMemoryInfo } from '@/types'
import type { AgentStatus, FleetConfig, McAccountInfo, SavedServer, YuyuStatusResp } from '@/api/client'

const PLAYER_UUID = '0f6c1c3a-6a5e-4d2b-9b1a-3c7e2f8d4a10'

function instance(id: string, name: string, mc_version: string, loader: Instance['loader'], favorite = false): Instance {
  return {
    id,
    name,
    mc_version,
    loader,
    ram_mb: 4096,
    favorite,
    description: '',
    jvm_vendor: 'auto',
    jvm_custom_path: null,
    gc_policy: 'auto',
    jvm_extra_args: '',
    jvm_args_mode: 'append',
    jvm_profile_id: null,
    icon: '',
    loader_version: '',
    window_custom: false,
    window_fullscreen: false,
    window_width: 854,
    window_height: 480,
  }
}

const INSTANCES: Instance[] = [
  instance('preview-horizon', 'Survie Horizon', '26.3', 'horizon', true),
  instance('preview-fabric', 'Fabric 1.21.11', '1.21.11', 'fabric'),
  instance('preview-pvp', 'PvP 1.8.9', '1.8.9', 'vanilla'),
]

const ACCOUNTS: McAccountInfo[] = [
  { mc_username: 'Steve', mc_uuid: PLAYER_UUID, is_active: true, is_offline: false },
]

const SERVERS: SavedServer[] = [
  { name: 'Hypixel', ip: 'mc.hypixel.net' },
  { name: 'Serveur des copains', ip: 'play.exemple.fr' },
]

const FLEET: FleetConfig = {
  min_launcher_version: null,
  min_version_message: null,
  update_required: false,
  launcher_blocked: null,
  blocked_agent_versions: [],
  flags: [],
  announcements: [],
}

/** Réponses écrites à la main, par nom de commande. */
const ANSWERS: Record<string, unknown> = {
  auth_status: { authenticated: true, username: 'Steve', uuid: PLAYER_UUID } satisfies AuthStatus,
  mc_list_accounts: ACCOUNTS,
  instance_list: INSTANCES,
  instance_id_migrations: [],
  running_instances: [],
  list_saved_servers: SERVERS,
  launcher_agent_status: { available: true, block: null, min_java: 21 } satisfies AgentStatus,
  system_memory_info: { total_mb: 16384, available_mb: 9216, suggested_mb: 4096 } satisfies SystemMemoryInfo,
  fleet_config: FLEET,
  fleet_refresh: FLEET,
  yuyu_ping: true,
  yuyu_status: { has_account: true } satisfies YuyuStatusResp,
  presence_get_visible: true,
  analytics_get_disabled: false,
  patch_notes_latest: null,
  take_pending_deep_link: null,
  take_pending_events: [],
  detect_country: null,
  // Plugins de Tauri : pas de mise à jour, version fictive.
  'plugin:updater|check': null,
  'plugin:app|version': '0.0.0-apercu',
}

/** Les commandes qui rendent une liste : une liste vide plutôt que `null`,
 *  sur laquelle un `.map` planterait. */
const LIST_LIKE = /(^list_|_list$|_list_|_history$|^servers_all$|_categories$|^loader_(availability|game_versions|versions)$|^list_versions$)/

const reported = new Set<string>()

function answer(cmd: string): unknown {
  if (cmd in ANSWERS) return ANSWERS[cmd]
  // Fenêtre, événements du système… : rien à rendre.
  if (cmd.startsWith('plugin:')) return null
  if (!reported.has(cmd)) {
    reported.add(cmd)
    console.info(`[aperçu navigateur] « ${cmd} » n'a pas de réponse écrite, valeur vide rendue.`)
  }
  return LIST_LIKE.test(cmd) ? [] : null
}

export function installBrowserPreview(): void {
  mockWindows('main')
  mockIPC((cmd) => answer(cmd), { shouldMockEvents: true })
  console.info('[aperçu navigateur] Pas de Tauri : les commandes sont simulées (src/dev/browserPreview.ts).')
}
