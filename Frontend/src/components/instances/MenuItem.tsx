import type { ReactNode } from 'react'

export function MenuItem({ icon, label, danger, onClick }: { icon: ReactNode; label: string; danger?: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`flex w-full items-center gap-2 rounded-lg text-left transition-all duration-150 text-[12px] font-medium py-[7px] px-[9px] bg-transparent ${
        danger ? 'text-[rgba(248,113,113,0.85)] hover:bg-[rgba(200,50,50,0.15)]' : 'text-[rgba(255,255,255,0.75)] hover:bg-[rgba(255,255,255,0.08)]'
      }`}
    >
      {icon}
      {label}
    </button>
  )
}
