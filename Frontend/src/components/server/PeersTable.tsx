import type { CSSProperties } from 'react'
import type { OwnershipData } from './types'
import { ownerColor, tpsColor, latencyColor } from './utils'

const thStyle: CSSProperties = { textAlign: 'left', padding: '7px 10px', color: 'rgba(255,255,255,0.35)', fontSize: 9, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 }
const tdStyle: CSSProperties = { padding: '7px 10px', color: 'rgba(255,255,255,0.7)', verticalAlign: 'middle' }

/**
 * Table des pairs connus : self + peers_reported[] (agents locaux multi-instance).
 * Les vrais pairs P2P distants ne sont pas détaillés individuellement côté agent
 * (pas de latence/débit par pair distant) — comptés honnêtement en ligne "non détaillés".
 */
export function PeersTable({ self }: { self: OwnershipData }) {
  const rows = [self, ...(self.peers_reported ?? [])]
  const undetailed = Math.max(0, (self.pc ?? 0) - rows.length)

  return (
    <div style={{ border: '1px solid rgba(255,255,255,0.08)', borderRadius: 8, overflow: 'hidden' }}>
      <div style={{ padding: '8px 10px', borderBottom: '1px solid rgba(255,255,255,0.06)', fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.8, color: 'rgba(255,255,255,0.35)' }}>
        Pairs connectés
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11 }}>
        <thead>
          <tr style={{ background: 'rgba(255,255,255,0.04)' }}>
            <th style={thStyle}></th>
            <th style={thStyle}>Pair</th>
            <th style={thStyle}>Position</th>
            <th style={thStyle}>TPS</th>
            <th style={thStyle}>Latence</th>
            <th style={thStyle}>Quads</th>
            <th style={thStyle}>Blocs env.</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((p, i) => {
            const myQuads = p.quads.filter(q => q.o === 0).length
            return (
              <tr key={p.peer_id} style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}>
                <td style={tdStyle}><div style={{ width: 8, height: 8, borderRadius: '50%', background: ownerColor(i) }} /></td>
                <td style={tdStyle}>
                  <div style={{ color: '#fff' }}>{p.peer_name}</div>
                  <div style={{ color: 'rgba(255,255,255,0.3)', fontFamily: 'monospace', fontSize: 9 }}>{p.peer_id}</div>
                </td>
                <td style={tdStyle}>{Math.floor(p.my_x)}, {Math.floor(p.my_z)}</td>
                <td style={{ ...tdStyle, color: tpsColor(p.tps) }}>{p.tps.toFixed(1)}</td>
                <td style={{ ...tdStyle, color: latencyColor(p.latency_ms) }}>{p.latency_ms >= 0 ? p.latency_ms + ' ms' : '—'}</td>
                <td style={tdStyle}>{myQuads}</td>
                <td style={tdStyle}>{p.live_blocks_sent}</td>
              </tr>
            )
          })}
          {undetailed > 0 && (
            <tr style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}>
              <td colSpan={7} style={{ ...tdStyle, color: 'rgba(255,255,255,0.3)', fontStyle: 'italic' }}>
                + {undetailed} pair(s) distant(s) non détaillé(s) — pas de métriques par pair distant côté agent
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
