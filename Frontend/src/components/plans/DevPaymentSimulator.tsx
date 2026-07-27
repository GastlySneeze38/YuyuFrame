import { useState } from 'react'
import { useT } from '@/i18n'

export function DevPaymentSimulator({ onSimulate }: { onSimulate: (plan: string) => Promise<void> }) {
  const t = useT()
  const [simulating, setSimulating] = useState(false)
  const [simMsg, setSimMsg] = useState<{ ok: boolean; text: string } | null>(null)

  const run = async (plan: string) => {
    setSimulating(true)
    setSimMsg(null)
    try {
      await onSimulate(plan)
      setSimMsg({ ok: true, text: t('plans.devSimulator.activated', { plan }) })
    } catch (e) {
      setSimMsg({ ok: false, text: String(e) })
    } finally {
      setSimulating(false)
      setTimeout(() => setSimMsg(null), 4000)
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-2xl p-5 bg-[rgba(255,200,0,0.04)] border border-dashed border-[rgba(255,200,0,0.25)]">
      <div className="flex items-center gap-2">
        <span className="text-[10px] font-extrabold text-[rgba(255,200,0,0.6)] tracking-[0.1em]">
          DEV ONLY
        </span>
        <span className="text-[12px] text-[rgba(255,255,255,0.4)] font-medium">
          {t('plans.devSimulator.simulatePayment')}
        </span>
      </div>
      <div className="flex gap-2">
        {['premium', 'ultimate'].map((plan) => (
          <button
            key={plan}
            disabled={simulating}
            onClick={() => run(plan)}
            className={`rounded-lg px-3 py-1.5 font-semibold transition-all duration-150 active:scale-95 text-[11px] ${simulating ? 'opacity-50 cursor-not-allowed' : 'opacity-100 cursor-pointer'} ${plan === 'premium' ? 'bg-[rgba(129,140,248,0.12)] text-[#818cf8] border border-[rgba(129,140,248,0.25)]' : 'bg-[rgba(245,158,11,0.12)] text-[#f59e0b] border border-[rgba(245,158,11,0.25)]'}`}
          >
            {plan}
          </button>
        ))}
        <button
          disabled={simulating}
          onClick={() => run('free')}
          className={`rounded-lg px-3 py-1.5 font-semibold transition-all duration-150 active:scale-95 text-[11px] bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.35)] border border-[rgba(255,255,255,0.1)] ${simulating ? 'opacity-50 cursor-not-allowed' : 'opacity-100 cursor-pointer'}`}
        >
          reset → free
        </button>
      </div>
      {simMsg && (
        <p className={`text-[11px] font-semibold ${simMsg.ok ? 'text-[#4ade80]' : 'text-[#f87171]'}`}>
          {simMsg.text}
        </p>
      )}
    </div>
  )
}
