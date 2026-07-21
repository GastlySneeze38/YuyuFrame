import { useEffect, useRef, useState } from 'react'
import { formatBytes } from '@/lib/format'
import type { OwnershipData, TabId } from './types'
import { CANVAS, CELL, QUAD_OFFSET, VIEW_HALF, CHUNK_PX, ownerColor, tpsColor, latencyColor, pushSample } from './utils'
import { StatBox } from './StatBox'
import { StatRow } from './StatRow'
import { DiagLine } from './DiagLine'
import { Sparkline } from './Sparkline'
import { Chart } from './Chart'
import { PeersTable } from './PeersTable'
import { HealthBadge } from './HealthBadge'

export function AgentDetail({ data, error, label, showPeers, tab }: { data: OwnershipData | null; error?: boolean; label: string; showPeers?: boolean; tab: TabId }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [copied, setCopied] = useState(false)

  // Historique côté client (perdu au reload — backend ne stocke aucune série temporelle).
  const historyRef = useRef({
    tps: [] as number[],
    mspt: [] as number[],
    latency: [] as number[],
    blocksSentRate: [] as number[],
    liveBlocksAppliedRate: [] as number[],
    baselineBlocksAppliedRate: [] as number[],
    queueIn: [] as number[],
    queueOut: [] as number[],
  })
  const lastSampleRef = useRef<{ t: number; sent: number; liveApplied: number; baselineApplied: number } | null>(null)

  useEffect(() => {
    if (!data) return
    const h = historyRef.current
    const now = performance.now()
    let sentRate = 0
    let liveAppliedRate = 0
    let baselineAppliedRate = 0
    if (lastSampleRef.current) {
      const dt = (now - lastSampleRef.current.t) / 1000
      if (dt > 0) {
        sentRate = Math.max(0, (data.live_blocks_sent - lastSampleRef.current.sent) / dt)
        liveAppliedRate = Math.max(0, (data.live_blocks_applied - lastSampleRef.current.liveApplied) / dt)
        baselineAppliedRate = Math.max(0, (data.baseline_blocks_applied - lastSampleRef.current.baselineApplied) / dt)
      }
    }
    lastSampleRef.current = {
      t: now,
      sent: data.live_blocks_sent,
      liveApplied: data.live_blocks_applied,
      baselineApplied: data.baseline_blocks_applied,
    }

    pushSample(h.tps, data.tps)
    pushSample(h.mspt, data.mspt)
    if (data.latency_ms >= 0) pushSample(h.latency, data.latency_ms)
    pushSample(h.blocksSentRate, sentRate)
    pushSample(h.liveBlocksAppliedRate, liveAppliedRate)
    pushSample(h.baselineBlocksAppliedRate, baselineAppliedRate)
    pushSample(h.queueIn, data.live_queue)
    pushSample(h.queueOut, data.live_queue_out)
  }, [data])

  // Rendu canvas (carte d'ownership)
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !data) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const { my_x, my_z, quads } = data
    const myCx = Math.floor(my_x / 16)
    const myCz = Math.floor(my_z / 16)

    ctx.fillStyle = '#0A0A12'
    ctx.fillRect(0, 0, CANVAS, CANVAS)

    const map = new Map<string, number>()
    for (const q of quads) map.set(`${q.cx},${q.cz},${q.q}`, q.o)

    for (let dcx = -VIEW_HALF; dcx <= VIEW_HALF; dcx++) {
      for (let dcz = -VIEW_HALF; dcz <= VIEW_HALF; dcz++) {
        const cx = myCx + dcx
        const cz = myCz + dcz
        const screenX = (dcx + VIEW_HALF) * CHUNK_PX
        const screenZ = (dcz + VIEW_HALF) * CHUNK_PX

        for (let q = 0; q < 4; q++) {
          const owner = map.get(`${cx},${cz},${q}`)
          const [qcol, qrow] = QUAD_OFFSET[q]
          const px = screenX + qcol * CELL
          const pz = screenZ + qrow * CELL

          if (owner !== undefined) {
            ctx.fillStyle = ownerColor(owner)
            ctx.globalAlpha = owner === 0 ? 0.35 : 0.22
            ctx.fillRect(px + 1, pz + 1, CELL - 1, CELL - 1)
            ctx.globalAlpha = 1
          }
        }

        ctx.strokeStyle = 'rgba(255,255,255,0.08)'
        ctx.lineWidth = 0.5
        ctx.strokeRect(screenX, screenZ, CHUNK_PX, CHUNK_PX)
      }
    }

    const myScreenX = VIEW_HALF * CHUNK_PX
    const myScreenZ = VIEW_HALF * CHUNK_PX
    ctx.strokeStyle = 'rgba(255,255,255,0.35)'
    ctx.lineWidth = 1
    ctx.strokeRect(myScreenX, myScreenZ, CHUNK_PX, CHUNK_PX)

    const fracX = ((my_x % 16) + 16) % 16
    const fracZ = ((my_z % 16) + 16) % 16
    const dotX = myScreenX + (fracX / 16) * CHUNK_PX
    const dotZ = myScreenZ + (fracZ / 16) * CHUNK_PX
    ctx.beginPath()
    ctx.arc(dotX, dotZ, 3, 0, Math.PI * 2)
    ctx.fillStyle = '#ffffff'
    ctx.shadowColor = '#ffffff'
    ctx.shadowBlur = 6
    ctx.fill()
    ctx.shadowBlur = 0
  }, [data, tab])

  const myCount = data?.quads.filter(q => q.o === 0).length ?? 0
  const total = data?.quads.length ?? 0
  const h = historyRef.current

  const handleCopy = async () => {
    const payload = {
      captured_at: new Date().toISOString(),
      peer: { id: data?.peer_id ?? null, name: data?.peer_name ?? null },
      performance: {
        tps: data?.tps ?? null,
        mspt: data?.mspt ?? null,
        latency_ms: data && data.latency_ms >= 0 ? data.latency_ms : null,
        pairs: data?.pc ?? 0,
      },
      synchronisation_initiale: {
        reception: {
          chunks: data?.baseline_chunks_applied ?? 0,
          blocs: data?.baseline_blocks_applied ?? 0,
          file: data?.baseline_queue ?? 0,
        },
        emission: {
          chunks: data?.baseline_chunks_sent ?? 0,
          volume: formatBytes(data?.baseline_bytes_sent ?? 0),
          streams_actifs: data?.baseline_active_streams ?? 0,
        },
      },
      temps_reel: {
        ownership: {
          mes_quads: myCount,
          total_quads: total,
          chunks_simules: data?.chunks_computed ?? 0,
          chunks_ignores: data?.chunks_skipped ?? 0,
          hook_appele: data?.hook_calls ?? 0,
          diagnostic_asm: data?.tick_chunk_diag ?? null,
        },
        delta_recu: {
          blocs_appliques: data?.live_blocks_applied ?? 0,
          file: data?.live_queue ?? 0,
        },
        delta_envoye: {
          hook_send_appele: data?.send_hook_calls ?? 0,
          diagnostic_asm: data?.send_hook_diag ?? null,
          blocs: data?.live_blocks_sent ?? 0,
          entites: data?.live_entities_sent ?? 0,
          file_sortante: data?.live_queue_out ?? 0,
        },
      },
    }
    try {
      await navigator.clipboard.writeText(JSON.stringify(payload, null, 2))
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    } catch { /* clipboard best-effort — pas de feedback d'erreur si refusé/indisponible */ }
  }

  return (
    <div className="flex w-full flex-col gap-3">
      {/* ── Barre d'en-tête ── */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <div className={error ? 'w-[6px] h-[6px] rounded-full bg-[#ef4444] shadow-[0_0_5px_#ef4444]' : 'w-[6px] h-[6px] rounded-full bg-[#22c55e] shadow-[0_0_5px_#22c55e]'} />
          <span className="text-[11px] text-[rgba(255,255,255,0.45)] font-mono">
            {error ? 'Agent P2P inaccessible (port 3849)' : label}
          </span>
          {data && (
            <span className="text-[10px] text-[rgba(120,110,230,0.7)] font-mono font-semibold">
              ({data.peer_name} · {data.peer_id})
            </span>
          )}
        </div>
        <button
          onClick={handleCopy}
          disabled={!data}
          className={
            copied
              ? 'text-[10px] font-semibold font-mono tracking-[0.3px] px-[9px] py-[3px] rounded-[5px] cursor-pointer disabled:cursor-default transition-all duration-150 bg-[rgba(40,160,90,0.18)] border border-[rgba(40,160,90,0.35)] text-[rgba(80,210,130,0.9)]'
              : 'text-[10px] font-semibold font-mono tracking-[0.3px] px-[9px] py-[3px] rounded-[5px] cursor-pointer disabled:cursor-default transition-all duration-150 bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.4)]'
          }
        >
          {copied ? 'Copié ✓' : '{ } Copier en JSON'}
        </button>
      </div>

      {/* ── Aperçu — intro du serveur, pas un tableau de stats ── */}
      {tab === 'apercu' && (
        <div className="flex flex-col gap-4">
          <div
            className="flex items-center gap-6 border border-[rgba(255,255,255,0.08)] rounded-xl px-[22px] py-[20px] bg-[rgba(255,255,255,0.02)]"
          >
            <div className="w-[88px] h-[88px] shrink-0 rounded-2xl bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.08)] flex items-center justify-center">
              <svg viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.45)" strokeWidth={1.3} className="w-[42px] h-[42px]">
                <rect x="3" y="3" width="18" height="5.5" rx="1.1" />
                <rect x="3" y="10" width="18" height="5.5" rx="1.1" />
                <rect x="3" y="17" width="18" height="4" rx="1" />
                <circle cx="6.3" cy="5.75" r="0.9" fill={!error ? '#22c55e' : '#ef4444'} stroke="none" />
                <circle cx="6.3" cy="12.75" r="0.9" fill={!error ? '#22c55e' : '#ef4444'} stroke="none" />
              </svg>
            </div>
            <div className="flex flex-col gap-1 min-w-0">
              <span className="font-mono text-[13px] font-semibold text-[rgba(255,255,255,0.7)]">
                {error ? 'Agent P2P inaccessible' : (data ? data.peer_name : '—')}
              </span>
              <span className="font-mono text-[11px] text-[rgba(255,255,255,0.3)]">
                {data ? `${data.peer_id} · ${data.pc} pair(s) connecté(s)` : 'En attente de données…'}
              </span>
              <div className="flex items-baseline gap-2 mt-[8px]">
                <span className="font-mono text-[38px] font-bold" style={{ color: data ? tpsColor(data.tps) : 'rgba(255,255,255,0.25)' }}>
                  {data ? data.tps.toFixed(1) : '—'}
                </span>
                <span className="font-mono text-[12px] text-[rgba(255,255,255,0.35)]">TPS</span>
              </div>
            </div>
          </div>

          <div className="border border-[rgba(255,255,255,0.08)] rounded-xl overflow-hidden">
            <div className="px-[12px] py-[8px] border-b border-[rgba(255,255,255,0.06)] text-[10px] font-mono uppercase tracking-[0.8px] text-[rgba(255,255,255,0.35)]">
              Pairs & ping
            </div>
            <div className="flex flex-col px-[12px] py-[6px]">
              {data ? [data, ...(data.peers_reported ?? [])].map((p, i) => (
                <div key={p.peer_id} className={`flex items-center justify-between gap-3 py-[6px] ${i > 0 ? 'border-t border-[rgba(255,255,255,0.05)]' : ''}`}>
                  <div className="flex items-center gap-2 min-w-0">
                    <div className="w-[9px] h-[9px] rounded-sm shrink-0" style={{ background: ownerColor(i) }} />
                    <span className="font-mono text-[11px] text-[rgba(255,255,255,0.5)]">{i === 0 ? 'Moi' : p.peer_name}</span>
                  </div>
                  <span className="font-mono text-[11px]" style={{ color: i === 0 ? 'rgba(255,255,255,0.3)' : latencyColor(p.latency_ms) }}>
                    {i === 0 ? '—' : p.latency_ms >= 0 ? `${p.latency_ms} ms` : '—'}
                  </span>
                </div>
              )) : (
                <div className="py-[8px] font-mono text-[11px] text-[rgba(255,255,255,0.3)]">En attente de données…</div>
              )}
              {(data?.pc ?? 0) - 1 - (data?.peers_reported?.length ?? 0) > 0 && (
                <div className="py-[6px] border-t border-[rgba(255,255,255,0.05)] font-mono text-[10px] text-[rgba(255,255,255,0.3)]">
                  +{(data?.pc ?? 0) - 1 - (data?.peers_reported?.length ?? 0)} pair(s) distant(s) non détaillé(s)
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Performance — graphes temporels pleine largeur ── */}
      {tab === 'performance' && (
        <div className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(340px,1fr))]">
          <Chart title="TPS" data={h.tps} color={data ? tpsColor(data.tps) : '#818cf8'} />
          <Chart title="MSPT" data={h.mspt} color="#818cf8" unit="ms" />
          <Chart title="Latence" data={h.latency} color={data ? latencyColor(data.latency_ms) : '#818cf8'} unit="ms" />
          <Chart title="Blocs envoyés / s" data={h.blocksSentRate} color="#818cf8" />
          <Chart title="Blocs appliqués / s" data={h.liveBlocksAppliedRate} color="#22c55e" />
          <Chart title="File sortante" data={h.queueOut} color="#f59e0b" />
          <Chart title="File entrante" data={h.queueIn} color="#f59e0b" />
        </div>
      )}

      {/* ── Réseau — table des pairs + carte d'ownership en grand ── */}
      {tab === 'reseau' && (
        <div className="flex flex-col gap-3">
          {showPeers && data ? <PeersTable self={data} /> : (
            <div className="p-[14px] text-[11px] text-[rgba(255,255,255,0.3)] font-mono border border-[rgba(255,255,255,0.08)] rounded-lg">
              La table des pairs n'est disponible que sur l'agent local (propriétaire de l'agrégation).
            </div>
          )}
          <div className="border border-[rgba(255,255,255,0.08)] rounded-lg overflow-hidden">
            <div className="px-[10px] py-[8px] border-b border-[rgba(255,255,255,0.06)] text-[10px] uppercase tracking-[0.8px] text-[rgba(255,255,255,0.35)]">
              Carte d'ownership
            </div>
            <div className="flex justify-center p-[14px]">
              <div className="max-w-full border border-[rgba(255,255,255,0.08)] rounded-md overflow-hidden" style={{ width: CANVAS }}>
                <canvas ref={canvasRef} width={CANVAS} height={CANVAS} className="block w-full h-auto" />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Synchronisation : snapshot initial vs delta temps réel ── */}
      {tab === 'sync' && (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <div className="text-[10px] font-mono uppercase tracking-[1px] text-[rgba(255,255,255,0.35)]">
                Snapshot initial
              </div>
              <div className="text-[10px] font-mono text-[rgba(255,255,255,0.3)]">Handshake unique à la connexion (J1 → J2)</div>
            </div>
            <div className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(220px,1fr))]">
              <StatBox title="Réception (chez moi)">
                <StatRow label="Chunks" value={data?.baseline_chunks_applied ?? 0} />
                <StatRow label="Blocs" value={data?.baseline_blocks_applied ?? 0} />
                <StatRow label="Vitesse" value={`${(h.baselineBlocksAppliedRate.at(-1) ?? 0).toFixed(1)} /s`} />
                <Sparkline data={h.baselineBlocksAppliedRate} color="#f97316" />
                <StatRow label="File" value={data?.baseline_queue ?? 0} />
              </StatBox>
              <StatBox title="Émission (vers le pair)">
                <StatRow label="Chunks" value={data?.baseline_chunks_sent ?? 0} />
                <StatRow label="Volume" value={formatBytes(data?.baseline_bytes_sent ?? 0)} />
                <StatRow label="Streams actifs" value={data?.baseline_active_streams ?? 0} />
              </StatBox>
              <StatBox title="Ownership détaillé">
                <StatRow label="Chunks simulés" value={data?.chunks_computed ?? 0} />
                <StatRow label="Chunks ignorés" value={data?.chunks_skipped ?? 0} />
              </StatBox>
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <div className="text-[10px] font-mono uppercase tracking-[1px] text-[rgba(255,255,255,0.35)]">
                Delta temps réel
              </div>
              <div className="text-[10px] font-mono text-[rgba(255,255,255,0.3)]">Flux continu pendant la partie</div>
            </div>
            <div className="flex flex-wrap gap-2">
              <HealthBadge label="tickChunk hook (ownership)" ok={(data?.hook_calls ?? 0) > 0} />
              <HealthBadge label="send() hook (delta sortant)" ok={(data?.send_hook_calls ?? 0) > 0} />
            </div>
            <div className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(220px,1fr))]">
              <StatBox title="Reçu (chez moi)">
                <StatRow label="Blocs appliqués" value={data?.live_blocks_applied ?? 0} />
                <StatRow label="File entrante" value={data?.live_queue ?? 0} />
              </StatBox>
              <StatBox title="Émis (vers le pair)">
                <StatRow label="Blocs envoyés" value={data?.live_blocks_sent ?? 0} />
                <StatRow label="Entités envoyées" value={data?.live_entities_sent ?? 0} />
                <StatRow label="File sortante" value={data?.live_queue_out ?? 0} />
              </StatBox>
            </div>
          </div>
        </div>
      )}

      {/* ── Diagnostic ── */}
      {tab === 'diagnostic' && (
        <div className="flex flex-col gap-3">
          <DiagLine title="Diagnostic ASM tickChunk" value={data?.tick_chunk_diag} />
          <DiagLine title="Diagnostic pause (isPaused)" value={data?.is_paused_diag} />
          <DiagLine title="Diagnostic liste joueurs vide" value={data?.player_list_empty_diag} />
          <DiagLine title="Diagnostic ASM send() (delta sortant)" value={data?.send_hook_diag} />
        </div>
      )}
    </div>
  )
}
