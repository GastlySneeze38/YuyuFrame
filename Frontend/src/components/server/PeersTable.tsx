import type { OwnershipData } from './types'
import { ownerColor, tpsColor, latencyColor } from './utils'
import { useT } from '@/i18n'

const thClass = 'text-left px-[10px] py-[7px] text-[rgba(255,255,255,0.35)] text-[9px] uppercase tracking-[0.5px] font-semibold'
const tdBase = 'px-[10px] py-[7px] align-middle'
const tdColor = 'text-[rgba(255,255,255,0.7)]'

/**
 * Table des pairs connus : self + peers_reported[] (agents locaux multi-instance).
 * Les vrais pairs P2P distants ne sont pas détaillés individuellement côté agent
 * (pas de latence/débit par pair distant) — comptés honnêtement en ligne "non détaillés".
 */
export function PeersTable({ self }: { self: OwnershipData }) {
  const t = useT()
  const rows = [self, ...(self.peers_reported ?? [])]
  const undetailed = Math.max(0, (self.pc ?? 0) - rows.length)

  return (
    <div className="border border-[rgba(255,255,255,0.08)] rounded-lg overflow-hidden">
      <div className="px-[10px] py-[8px] border-b border-[rgba(255,255,255,0.06)] text-[10px] uppercase tracking-[0.8px] text-[rgba(255,255,255,0.35)]">
        {t('server.peersConnected')}
      </div>
      {/* overflow-x-auto isolé du cadre arrondi (overflow-hidden au-dessus) —
          sur un écran étroit, le tableau scrolle horizontalement au lieu de
          voir ses dernières colonnes silencieusement coupées. */}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-collapse text-[11px]">
          <thead>
            <tr className="bg-[rgba(255,255,255,0.04)]">
              <th className={thClass}></th>
              <th className={thClass}>{t('server.thPeer')}</th>
              <th className={thClass}>{t('server.thPosition')}</th>
              <th className={thClass}>{t('server.thTps')}</th>
              <th className={thClass}>{t('server.thLatency')}</th>
              <th className={thClass}>{t('server.thQuads')}</th>
              <th className={thClass}>{t('server.thBlocksSent')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p, i) => {
              const myQuads = p.quads.filter(q => q.o === 0).length
              return (
                <tr key={p.peer_id} className="border-t border-[rgba(255,255,255,0.05)]">
                  <td className={tdBase}><div className="w-[8px] h-[8px] rounded-full" style={{ background: ownerColor(i) }} /></td>
                  <td className={tdBase}>
                    <div className="text-white">{p.peer_name}</div>
                    <div className="text-[rgba(255,255,255,0.3)] font-mono text-[9px]">{p.peer_id}</div>
                  </td>
                  <td className={`${tdBase} ${tdColor}`}>{Math.floor(p.my_x)}, {Math.floor(p.my_z)}</td>
                  <td className={tdBase} style={{ color: tpsColor(p.tps) }}>{p.tps.toFixed(1)}</td>
                  <td className={tdBase} style={{ color: latencyColor(p.latency_ms) }}>{p.latency_ms >= 0 ? p.latency_ms + ' ms' : '—'}</td>
                  <td className={`${tdBase} ${tdColor}`}>{myQuads}</td>
                  <td className={`${tdBase} ${tdColor}`}>{p.live_blocks_sent}</td>
                </tr>
              )
            })}
            {undetailed > 0 && (
              <tr className="border-t border-[rgba(255,255,255,0.05)]">
                <td colSpan={7} className={`${tdBase} text-[rgba(255,255,255,0.3)] italic`}>
                  + {undetailed} {t('server.remotePeersUndetailedFull')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
