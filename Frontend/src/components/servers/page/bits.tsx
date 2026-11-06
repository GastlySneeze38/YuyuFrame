import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { ServerPingInfo } from '@/api/client'
import { useStore, useStorePick } from '@/stores/useStore'
import { t as translate, useT } from '@/i18n'
import type { PingState } from '@/lib/serverPing'

/** Briques communes aux trois onglets de l'écran Serveurs. */

/** Couleur des courbes du fond, au format attendu par `TopoBackdrop` : un
 *  gris neutre, pour que le fond reste un décor et ne teinte pas l'écran. */
export const SERVERS_TINT = '150 150 165'

/** Fond des panneaux : presque opaque, pour que le texte se détache des
 *  courbes qui passent dessous. */
export const PANEL = 'border border-white/[0.07] bg-[rgb(var(--bg-card))]/95'

/** Le MOTD d'un serveur, avec ses couleurs et ses styles, comme dans la
 *  liste du jeu. Sans morceaux (ancienne réponse), le texte brut. */
export function Motd({ info, lines = 2, className = '' }: { info: ServerPingInfo; lines?: 1 | 2; className?: string }) {
  const spans = info.motd_spans?.length ? info.motd_spans : [{ text: info.motd, color: null, bold: false, italic: false, underlined: false, strikethrough: false }]
  return (
    <p className={`m-0 whitespace-pre-line break-words text-[12px] leading-[1.35] text-[#aaaaaa] max-sm:text-[13px] ${lines === 1 ? 'line-clamp-1' : 'line-clamp-2'} ${className}`}>
      {spans.map((s, i) => (
        <span
          key={i}
          style={{
            color: s.color ?? undefined,
            fontWeight: s.bold ? 700 : undefined,
            fontStyle: s.italic ? 'italic' : undefined,
            textDecoration: [s.underlined && 'underline', s.strikethrough && 'line-through'].filter(Boolean).join(' ') || undefined,
          }}
        >
          {s.text}
        </span>
      ))}
    </p>
  )
}

/** Barres de signal du jeu : vertes, jaunes ou rouges selon la latence. */
export function Signal({ state }: { state: PingState }) {
  const t = useT()
  const ms = state.status === 'ok' ? state.info.latency_ms : null
  const bars = ms === null ? 0 : ms <= 100 ? 4 : ms <= 200 ? 3 : ms <= 400 ? 2 : 1
  const color = state.status === 'offline' ? '#f87171' : ms === null ? '' : ms <= 100 ? '#4ade80' : ms <= 300 ? '#facc15' : '#f87171'
  return (
    <div
      className="flex h-[11px] flex-shrink-0 items-end gap-[2px]"
      title={state.status === 'offline' ? t('servers.offline') : ms !== null ? `${ms} ms` : t('servers.pingInProgress')}
    >
      {[4, 6, 8, 11].map((h, i) => (
        <span key={i} className="w-[2.5px] rounded-[1px]" style={{ height: h, background: i < bars ? color : 'rgba(255,255,255,0.12)' }} />
      ))}
    </div>
  )
}

/** L'icône d'un serveur : celle qu'il envoie au ping, sinon une icône neutre. */
export function ServerFavicon({ ping, src, size = 44 }: { ping?: ServerPingInfo | null; src?: string | null; size?: number }) {
  const image = src ?? ping?.favicon ?? null
  return (
    <div
      className="flex flex-shrink-0 items-center justify-center overflow-hidden rounded-xl border border-white/[0.06] bg-white/[0.04] text-white/30"
      style={{ width: size, height: size }}
    >
      {image ? (
        <img src={image} alt="" className="h-full w-full object-cover [image-rendering:pixelated]" draggable={false} />
      ) : (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" width={size * 0.45} height={size * 0.45}>
          <rect x="3" y="4" width="18" height="6" rx="1" />
          <rect x="3" y="14" width="18" height="6" rx="1" />
          <circle cx="7" cy="7" r="0.6" fill="currentColor" />
          <circle cx="7" cy="17" r="0.6" fill="currentColor" />
        </svg>
      )}
    </div>
  )
}

/** Régions de Modrinth : traduites quand on les connaît, brutes sinon. */
export function regionLabel(region: string): string {
  const key = `servers.page.regions.${region}`
  const label = translate(key)
  return label === key ? region : label
}

/** Étiquette Modrinth (`pvp`, `keep-inventory`…) : traduite quand on la
 *  connaît, sinon mise en forme (« Keep inventory »). */
export function categoryLabel(name: string): string {
  const key = `servers.page.categories.${name}`
  const label = translate(key)
  return label === key ? name.replace(/-/g, ' ').replace(/^./, (c) => c.toUpperCase()) : label
}

export const REGIONS =['europe', 'us_east', 'us_west', 'south_america', 'asia', 'australia', 'russia']

/** Les langues les plus présentes dans le catalogue. */
export const LANGUAGES = ['en', 'fr', 'de', 'es', 'pt', 'ru', 'pl', 'it', 'nl', 'tr', 'zh', 'ja', 'ko']

/** Nom d'une langue dans la langue de l'interface (« anglais », « English »…). */
export function languageName(code: string): string {
  const ui = useStore.getState().language
  try {
    const names = new Intl.DisplayNames([ui === 'sga' ? 'fr' : ui], { type: 'language' })
    const name = names.of(code)
    return name ? name.charAt(0).toUpperCase() + name.slice(1) : code
  } catch {
    return code
  }
}

/** Nombre court : 33 138 → « 33,1 k ». */
export function compact(n: number): string {
  const ui = useStore.getState().language
  try {
    return new Intl.NumberFormat(ui === 'sga' ? 'fr' : ui, { notation: 'compact', maximumFractionDigits: 1 }).format(n)
  } catch {
    return String(n)
  }
}

/**
 * Aucune instance : rien à lister ni où ranger un serveur. Une carte en haut
 * de l'onglet — pas une phrase flottant au milieu du vide — qui dit pourquoi
 * et mène là où on en crée une.
 */
export function NoInstance() {
  const t = useT()
  const navigate = useNavigate()
  return (
    <div className="flex w-full flex-col items-center gap-4 rounded-2xl border border-white/[0.06] bg-white/[0.04] px-6 py-8 text-center sm:mx-auto sm:mt-10 sm:max-w-md">
      <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-accent/15 text-accent-hover">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
          <rect x="3" y="4" width="18" height="6" rx="1.5" />
          <rect x="3" y="14" width="18" height="6" rx="1.5" />
          <path d="M7 7h.01M7 17h.01" />
        </svg>
      </span>
      <p className="max-w-xs text-[14px] leading-relaxed text-white/75 max-sm:text-[15px]">{t('servers.page.mine.noInstance')}</p>
      <button
        onClick={() => navigate('/instances')}
        className="press h-10 rounded-xl bg-[rgb(var(--accent))] px-5 text-[13px] font-semibold text-white transition-colors hover:bg-[#5b4fe0] max-sm:h-12 max-sm:w-full max-sm:rounded-2xl max-sm:text-[15px]"
      >
        {t('servers.page.mine.createInstance')}
      </button>
    </div>
  )
}

/** Lance une instance directement sur un serveur, comme les cartes de
 *  l'accueil, puis revient à l'accueil où la partie se suit. */
export function useJoinServer() {
  const navigate = useNavigate()
  const { avoidBetaDependencies, showConsole, setSelectedInstanceId, setInstanceRunning, isAgentEnabled } =
    useStorePick('avoidBetaDependencies', 'showConsole', 'setSelectedInstanceId', 'setInstanceRunning', 'isAgentEnabled', 'agentOptOut')
  return async (instanceId: string, address: string) => {
    await api.launch.start(instanceId, avoidBetaDependencies, showConsole, address, isAgentEnabled(instanceId))
    setSelectedInstanceId(instanceId)
    setInstanceRunning(instanceId, true)
    navigate('/home')
  }
}

/** Liste déroulante sobre, aux couleurs de l'écran. */
export function Select<T extends string>({
  value,
  onChange,
  options,
  className = '',
}: {
  value: T
  onChange: (v: T) => void
  options: { value: T; label: string }[]
  className?: string
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value as T)}
      className={`h-9 cursor-pointer rounded-xl border border-line bg-surface-2 px-3 text-[12.5px] text-txt-primary outline-none transition-colors hover:border-white/20 focus:border-accent/60 ${className}`}
    >
      {options.map((o) => (
        <option key={o.value} value={o.value} className="bg-[rgb(var(--bg-raised))]">
          {o.label}
        </option>
      ))}
    </select>
  )
}
