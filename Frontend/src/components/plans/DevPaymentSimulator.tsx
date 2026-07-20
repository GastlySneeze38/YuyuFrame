import { useState } from 'react'

export function DevPaymentSimulator({ onSimulate }: { onSimulate: (plan: string) => Promise<void> }) {
  const [simulating, setSimulating] = useState(false)
  const [simMsg, setSimMsg] = useState<{ ok: boolean; text: string } | null>(null)

  const run = async (plan: string) => {
    setSimulating(true)
    setSimMsg(null)
    try {
      await onSimulate(plan)
      setSimMsg({ ok: true, text: `✓ Plan ${plan} activé (simulation)` })
    } catch (e) {
      setSimMsg({ ok: false, text: String(e) })
    } finally {
      setSimulating(false)
      setTimeout(() => setSimMsg(null), 4000)
    }
  }

  return (
    <div
      className="flex flex-col gap-3 rounded-2xl p-5"
      style={{ background: 'rgba(255,200,0,0.04)', border: '1px dashed rgba(255,200,0,0.25)' }}
    >
      <div className="flex items-center gap-2">
        <span style={{ fontSize: 10, fontWeight: 800, color: 'rgba(255,200,0,0.6)', letterSpacing: '0.1em' }}>
          DEV ONLY
        </span>
        <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.4)', fontWeight: 500 }}>
          Simuler un paiement Lemon Squeezy
        </span>
      </div>
      <div className="flex gap-2">
        {['premium', 'ultimate'].map((plan) => (
          <button
            key={plan}
            disabled={simulating}
            onClick={() => run(plan)}
            className="rounded-lg px-3 py-1.5 font-semibold transition-all duration-150 active:scale-95"
            style={{
              fontSize: 11,
              background: plan === 'premium' ? 'rgba(129,140,248,0.12)' : 'rgba(245,158,11,0.12)',
              color: plan === 'premium' ? '#818cf8' : '#f59e0b',
              border: `1px solid ${plan === 'premium' ? 'rgba(129,140,248,0.25)' : 'rgba(245,158,11,0.25)'}`,
              opacity: simulating ? 0.5 : 1,
              cursor: simulating ? 'not-allowed' : 'pointer',
            }}
          >
            {plan}
          </button>
        ))}
        <button
          disabled={simulating}
          onClick={() => run('free')}
          className="rounded-lg px-3 py-1.5 font-semibold transition-all duration-150 active:scale-95"
          style={{
            fontSize: 11,
            background: 'rgba(255,255,255,0.05)',
            color: 'rgba(255,255,255,0.35)',
            border: '1px solid rgba(255,255,255,0.1)',
            opacity: simulating ? 0.5 : 1,
            cursor: simulating ? 'not-allowed' : 'pointer',
          }}
        >
          reset → free
        </button>
      </div>
      {simMsg && (
        <p style={{ fontSize: 11, color: simMsg.ok ? '#4ade80' : '#f87171', fontWeight: 600 }}>
          {simMsg.text}
        </p>
      )}
    </div>
  )
}
