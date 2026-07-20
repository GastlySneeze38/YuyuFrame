import type { ReactNode } from 'react'

export function MenuItem({ icon, label, danger, onClick }: { icon: ReactNode; label: string; danger?: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="flex w-full items-center gap-2 rounded-lg text-left transition-all duration-150"
      style={{ fontSize: 12, fontWeight: 500, padding: '7px 9px', color: danger ? 'rgba(248,113,113,0.85)' : 'rgba(255,255,255,0.75)' }}
      onMouseEnter={(e) => { e.currentTarget.style.background = danger ? 'rgba(200,50,50,0.15)' : 'rgba(255,255,255,0.08)' }}
      onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent' }}
    >
      {icon}
      {label}
    </button>
  )
}
