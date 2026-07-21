import { useEffect, useRef, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { invoke } from '@tauri-apps/api/core'

interface LogLine {
  id: number
  line: string
  level: 'out' | 'err'
}

function lineClassName(line: string, level: 'out' | 'err'): string {
  // ── Priorité maximale : FATAL ──────────────────────────────────────────────
  if (/^\[FATAL\]/.test(line) || /\/(?:FATAL)\]/.test(line))
    return 'text-[#ff4444] font-bold border-l-2 border-l-[#ff444466] pl-1.5'

  // ── Erreurs critiques ──────────────────────────────────────────────────────
  if (/\[!\]/.test(line))
    return 'text-[#fb923c] font-semibold'

  if (/^\[ERR\]/.test(line) || /\/ERROR\]/.test(line))
    return 'text-[#f87171]'

  // ── Warnings ───────────────────────────────────────────────────────────────
  if (/^\[WARN\]/.test(line) || /\/WARN\]/.test(line))
    return 'text-[#facc15]'

  // ── INFO standard (Mixin/INFO, Render thread/INFO, Worker/INFO…) ───────────
  if (/\/INFO\]/.test(line) || /^\[INFO\]/.test(line))
    return 'text-[rgba(175,175,185,0.6)]'

  // ── DEBUG et TRACE : très atténués (Mixin/DEBUG, Worker/DEBUG, etc.) ───────
  if (/\/DEBUG\]|\/TRACE\]|\[Mixin\/DEBUG\]/.test(line) || /\[Mixin\/DEBUG\]/.test(line))
    return 'text-[rgba(120,120,130,0.55)]'

  // ── Lignes [Mixin/DEBUG] (format bracket) ──────────────────────────────────
  // Capturé par la regex ci-dessus, mais on garde la compat P2PLog
  if (/^\[DEBUG\]/.test(line))
    return 'text-[rgba(120,120,130,0.55)]'

  // ── Lignes P2P (tag [P2P] ou [P2P-*]) : bleuté distinctif, Initialisation ─
  if (/^\[P2P/.test(line))
    return 'text-[#d87dfc]'  // sky-300

  // ── Stderr générique (non capturé ci-dessus) ───────────────────────────────
  if (level === 'err')
    return 'text-[#fca5a5]'

  // ── Default : texte neutre ─────────────────────────────────────────────────
  return 'text-[rgba(175,175,185,0.6)]'
}

const MAX_LINES = 3000

export default function Console() {
  const [logs, setLogs] = useState<LogLine[]>([])
  const [running, setRunning] = useState(true)
  const [copied, setCopied] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)
  const counterRef = useRef(0)
  // Ref pour accumulation entre renders — évite setState à chaque ligne
  const pendingRef = useRef<LogLine[]>([])
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  function addLine(line: string, level: 'out' | 'err') {
    pendingRef.current.push({ id: counterRef.current++, line, level })
    // setTimeout, PAS requestAnimationFrame : rAF est throttlé/carrément mis en
    // pause par le moteur WebView quand cette fenêtre n'est pas au premier
    // plan (typiquement dès que la fenêtre du jeu prend le focus, juste après
    // le lancement) — les lignes s'accumulaient alors indéfiniment dans
    // pendingRef SANS jamais atteindre l'état affiché tant que la console ne
    // revenait pas au premier plan, donnant l'impression de lignes "perdues"
    // alors qu'elles étaient bien reçues. setTimeout continue de se déclencher
    // même fenêtre en arrière-plan.
    if (flushTimerRef.current === null) {
      flushTimerRef.current = setTimeout(() => {
        flushTimerRef.current = null
        const batch = pendingRef.current.splice(0)
        if (batch.length > 0) {
          setLogs((prev) => {
            const next = [...prev, ...batch]
            return next.length > MAX_LINES ? next.slice(-MAX_LINES) : next
          })
        }
      }, 16)
    }
  }

  function copyAll() {
    const text = logs.map((l) => l.line).join('\n')
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  useEffect(() => {
    const win = getCurrentWindow()
    const myShortId = win.label.replace('mc-console-', '')
    setSessionId(myShortId)

    const unsubPromises = [
      listen<{ line: string; level: 'out' | 'err'; instance_id?: string }>('game_log', (e) => {
        if (!e.payload.instance_id || e.payload.instance_id === myShortId) {
          addLine(e.payload.line, e.payload.level)
        }
      }),
      listen<{ running: boolean; instance_id: string }>('game_state', (e) => {
        if (!e.payload.running && e.payload.instance_id.startsWith(myShortId)) {
          setRunning(false)
        }
      }),
    ]

    // Signale au backend que le listener game_log est bien attaché — débloque
    // l'attente posée côté Rust (register_console_waiter dans launch_game),
    // qui sinon pouvait émettre ses tout premiers logs avant que ce listener
    // n'existe (lancement rapide, ex: 1.8.9 vanilla tout en cache), les
    // perdant silencieusement.
    Promise.all(unsubPromises).then(() => {
      invoke('console_ready', { consoleLabel: win.label }).catch(() => {})
    })

    return () => {
      if (flushTimerRef.current !== null) clearTimeout(flushTimerRef.current)
      unsubPromises.forEach((p) => p.then((fn) => fn()))
    }
  }, [])

  useEffect(() => {
    const sel = window.getSelection()
    if (sel && !sel.isCollapsed) return
    bottomRef.current?.scrollIntoView({ behavior: 'instant' })
  }, [logs])

  return (
    <div className="flex h-screen flex-col bg-[#07070C] [font-family:monospace]">

      {/* Drag bar + window controls */}
      <div
        data-tauri-drag-region
        className="flex flex-shrink-0 items-center h-9 bg-[#09090D] border-b border-b-[rgba(255,255,255,0.07)] select-none"
      >
        {/* Titre (drag zone) */}
        <div
          data-tauri-drag-region
          className="flex flex-1 items-center gap-2 px-4 pointer-events-none"
        >
          <div
            className={`w-1.5 h-1.5 rounded-full ${running ? 'bg-[#4ade80] shadow-[0_0_5px_#4ade80]' : 'bg-[rgba(255,255,255,0.2)] shadow-none'}`}
          />
          <span className={`text-[11px] ${running ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(255,255,255,0.25)]'}`}>
            {running ? 'Minecraft en cours...' : 'Jeu terminé'}
          </span>
          {sessionId && (
            <span className="text-[11px] text-[rgba(255,255,255,0.3)] font-semibold">
              {sessionId}
            </span>
          )}
          <span className="text-[11px] text-[rgba(255,255,255,0.15)]">{logs.length} lignes</span>
        </div>

        {/* Boutons — hors drag region (pointer-events explicite) */}
        <div className="flex items-center gap-0.5 px-2 pointer-events-auto">
          <button
            onClick={copyAll}
            disabled={logs.length === 0}
            className={`[background:none] border rounded text-[11px] py-px px-2 mr-1.5 disabled:cursor-default cursor-pointer border-[rgba(255,255,255,0.12)] ${copied ? 'text-[#4ade80]' : 'text-[rgba(255,255,255,0.4)]'}`}
          >
            {copied ? '✓ Copié' : 'Copier'}
          </button>
          <button
            onClick={() => getCurrentWindow().minimize()}
            className="[background:none] border-none cursor-pointer w-7 h-7 text-[rgba(255,255,255,0.3)] flex items-center justify-center hover:bg-[rgba(255,255,255,0.07)]"
          >
            <svg width="10" height="2" viewBox="0 0 10 2" fill="currentColor"><rect width="10" height="1.5" y="0.25" /></svg>
          </button>
          <button
            onClick={() => getCurrentWindow().close()}
            className="[background:none] border-none cursor-pointer w-7 h-7 text-[rgba(220,60,60,0.6)] flex items-center justify-center hover:bg-[rgba(220,45,45,0.2)] hover:text-[rgb(245,80,80)]"
          >
            <svg width="9" height="9" viewBox="0 0 9 9" fill="none" stroke="currentColor" strokeWidth="1.5">
              <line x1="0.5" y1="0.5" x2="8.5" y2="8.5" /><line x1="8.5" y1="0.5" x2="0.5" y2="8.5" />
            </svg>
          </button>
        </div>
      </div>

      {/* Logs */}
      <div className="selectable min-h-0 flex-1 overflow-y-auto px-4 py-3 [overflow-anchor:none]">
        {logs.length === 0 && (
          <p className="text-[12px] text-[rgba(255,255,255,0.18)] m-0">
            En attente des logs...
          </p>
        )}
        {logs.map((log) => (
          <div
            key={log.id}
            className={`leading-[1.55] whitespace-pre-wrap break-all text-[11px] ${lineClassName(log.line, log.level)}`}
          >
            {log.line}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
    </div>
  )
}
