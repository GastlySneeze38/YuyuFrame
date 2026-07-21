// Avant ce fichier, ce bloc existait en 2 copies quasi identiques
// (Stats.tsx PremiumGate et Sync.tsx SyncPremiumGate), qui ne différaient
// que par la taille (variante "compact") et le texte.

export function PremiumGate({
  description,
  features,
  onUpgrade,
  compact = false,
}: {
  description: string
  features: string[]
  onUpgrade: () => void
  compact?: boolean
}) {
  const iconSize = compact ? 24 : 28
  const btnIconSize = compact ? 13 : 14

  return (
    <div
      className={`flex flex-col items-center gap-6 rounded-2xl px-8 text-center bg-[rgba(75,63,207,0.06)] border border-[rgba(75,63,207,0.2)] ${compact ? 'py-12' : 'py-16'}`}
    >
      <div
        className={`flex items-center justify-center rounded-2xl bg-[rgba(75,63,207,0.15)] border border-[rgba(129,140,248,0.3)] ${compact ? 'w-14 h-14' : 'w-16 h-16'}`}
      >
        <svg viewBox="0 0 24 24" fill="#818cf8" width={iconSize} height={iconSize}>
          <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z" />
        </svg>
      </div>

      <div className="flex flex-col gap-2">
        <h2 className={`font-black text-white tracking-[-0.01em] ${compact ? 'text-[18px]' : 'text-[22px]'}`}>
          Fonctionnalité Premium
        </h2>
        <p className={`text-[rgba(255,255,255,0.45)] leading-[1.6] ${compact ? 'text-[12px] max-w-[360px]' : 'text-[13px] max-w-[420px]'}`}>
          {description}
        </p>
      </div>

      <div
        className={`flex flex-col gap-2 rounded-xl p-4 text-left bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)] w-full ${compact ? 'max-w-[320px]' : 'max-w-[360px]'}`}
      >
        {features.map((feat) => (
          <div key={feat} className="flex items-center gap-2.5">
            <svg viewBox="0 0 16 16" fill="none" width={14} height={14}>
              <circle cx="8" cy="8" r="7" fill="rgba(75,63,207,0.25)" />
              <path d="M4.5 8l2.5 2.5 4.5-5" stroke="#818cf8" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span className="text-[12px] text-[rgba(255,255,255,0.6)]">{feat}</span>
          </div>
        ))}
      </div>

      <button
        onClick={onUpgrade}
        className={`flex items-center gap-2 rounded-xl font-bold transition-all duration-150 active:scale-95 text-[13px] bg-[#4B3FCF] text-white shadow-[0_4px_20px_rgba(75,63,207,0.4)] hover:bg-[#6155e8] ${compact ? 'h-10 px-6' : 'h-11 px-7'}`}
      >
        <svg viewBox="0 0 20 20" fill="#f59e0b" width={btnIconSize} height={btnIconSize}>
          <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
        </svg>
        Voir les plans
      </button>
    </div>
  )
}
