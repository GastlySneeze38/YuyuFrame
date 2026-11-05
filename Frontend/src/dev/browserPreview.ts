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
import { emit } from '@tauri-apps/api/event'
import { useStore } from '@/stores/useStore'
import type { AuthStatus, Instance, Mod, SystemMemoryInfo } from '@/types'
import type { AgentStatus, FleetConfig, McAccountInfo, SavedServer, ServerPingInfo, YuyuStatusResp } from '@/api/client'

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
  // Quelques mods, pour voir la liste de l'écran des mods remplie.
  mods_list: [
    { name: 'sodium-fabric-0.6.5+mc1.21.1.jar', size: 1_245_000, enabled: true, sha1: 'preview-sodium' },
    { name: 'lithium-fabric-0.14.3.jar', size: 712_000, enabled: true, sha1: 'preview-lithium' },
    { name: 'iris-fabric-1.8.1+mc1.21.1.jar', size: 2_310_000, enabled: false, sha1: 'preview-iris' },
  ] satisfies Mod[],
  mods_check_conflicts: [],
  curseforge_local_fingerprints: [],
  // Quelques réglages, pour voir l'écran Options rempli (vue lisible et avancée).
  mc_options_read: [
    { key: 'renderDistance', value: '12' },
    { key: 'simulationDistance', value: '10' },
    { key: 'fov', value: '70' },
    { key: 'maxFps', value: '120' },
    { key: 'graphicsMode', value: '1' },
    { key: 'particles', value: '0' },
    { key: 'enableVsync', value: 'true' },
    { key: 'fullscreen', value: 'false' },
    { key: 'gamma', value: '0.5' },
    { key: 'soundCategory_master', value: '1.0' },
    { key: 'soundCategory_music', value: '0.4' },
    { key: 'soundCategory_ambient', value: '1.0' },
    { key: 'lang', value: 'fr_fr' },
    { key: 'autoJump', value: 'false' },
    { key: 'toggleCrouch', value: 'false' },
    { key: 'guiScale', value: '0' },
    { key: 'key_key.forward', value: 'key.keyboard.z' },
    { key: 'key_key.left', value: 'key.keyboard.q' },
    { key: 'mouseSensitivity', value: '0.5' },
  ],
  agent_options_read: [
    { key: 'ui-settings.setting.themeMode', value: '0' },
    { key: 'zoom.setting.level', value: '4' },
  ],
  // Résultats de recherche des packs (et des mods) : trois projets factices.
  mods_search_advanced: {
    hits: [
      { project_id: 'p-faithful', slug: 'faithful-32x', title: 'Faithful 32x', description: 'La texture vanilla, deux fois plus fine.', icon_url: null, downloads: 1_200_000, follows: 8000, categories: ['32x'], author: 'Faithful' },
      { project_id: 'p-bsl', slug: 'bsl-shaders', title: 'BSL Shaders', description: 'Lumière douce, eau réaliste, ombres nettes.', icon_url: null, downloads: 900_000, follows: 6000, categories: ['iris'], author: 'capttatsu' },
      { project_id: 'p-fresh', slug: 'fresh-animations', title: 'Fresh Animations', description: 'Des créatures qui bougent enfin comme des créatures.', icon_url: null, downloads: 700_000, follows: 5000, categories: ['themed'], author: 'FreshLX' },
    ],
    total_hits: 3,
  },
  launcher_agent_status: { available: true, block: null, min_java: 21 } satisfies AgentStatus,
  system_memory_info: { total_mb: 16384, available_mb: 9216, suggested_mb: 4096 } satisfies SystemMemoryInfo,
  fleet_config: FLEET,
  fleet_refresh: FLEET,
  ping_server: {
    motd: 'Un serveur Minecraft',
    players_online: 128,
    players_max: 500,
    version_name: '26.3',
    favicon: null,
    latency_ms: 42,
    motd_spans: [],
  } satisfies ServerPingInfo,
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

/** Partie simulée : il n'y a pas de jeu derrière, donc personne pour annoncer
 *  sa fin. Sans ça, l'accueil resterait sur « En jeu… » pour toujours, et
 *  même après un rechargement (les instances lancées sont retenues). */
const FAKE_GAME_MS = 4000

function answer(cmd: string, payload: unknown): unknown {
  if (cmd === 'launch_game') {
    const instance_id = (payload as { instanceId?: string } | undefined)?.instanceId ?? ''
    window.setTimeout(() => void emit('game_state', { running: false, instance_id }), FAKE_GAME_MS)
    return null
  }
  if (cmd === 'packs_list') {
    const kind = (payload as { kind?: string } | undefined)?.kind
    return kind === 'shader'
      ? [{ name: 'BSL_v8.2.09.zip', size: 412_000, kind: 'shader', sha1: 'preview-bsl' }]
      : [{ name: 'Faithful 32x - 1.21.zip', size: 8_600_000, kind: 'resourcepack', sha1: 'preview-faithful' }]
  }
  // Écriture des réglages : fusionne et rend le fichier, comme le Rust.
  if (cmd === 'mc_options_write' || cmd === 'agent_options_write') {
    const key = cmd === 'mc_options_write' ? 'mc_options_read' : 'agent_options_read'
    const changes = (payload as { changes?: Array<{ key: string; value: string }> } | undefined)?.changes ?? []
    const merged = new Map((ANSWERS[key] as Array<{ key: string; value: string }>).map((e) => [e.key, e.value]))
    for (const c of changes) merged.set(c.key, c.value)
    ANSWERS[key] = [...merged].map(([k, value]) => ({ key: k, value }))
    return ANSWERS[key]
  }
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
  mockIPC((cmd, payload) => answer(cmd, payload), { shouldMockEvents: true })
  // Une partie restée « en cours » d'une session précédente de l'aperçu ne
  // finira jamais : on repart sans.
  useStore.setState({ runningInstances: [], gameRunning: false })
  // L'aperçu s'ouvre avec les serveurs sur l'accueil (réglage éteint par
  // défaut dans le launcher) : c'est la vue qu'on veut explorer. Une seule
  // fois, pour que le réglage reste ensuite libre de changer dans Réglages.
  if (!localStorage.getItem('yf-preview-home-servers')) {
    localStorage.setItem('yf-preview-home-servers', '1')
    useStore.setState({ showHomeServers: true })
  }
  console.info('[aperçu navigateur] Pas de Tauri : les commandes sont simulées (src/dev/browserPreview.ts).')
}
