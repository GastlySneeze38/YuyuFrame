export function StatBox({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-[rgba(255,255,255,0.04)] rounded-md px-[10px] py-[8px] flex-[1_1_130px] min-w-0">
      <div className="text-[rgba(255,255,255,0.3)] mb-[6px] text-[10px] font-mono uppercase tracking-[1px]">{title}</div>
      <div className="flex flex-col gap-1">{children}</div>
    </div>
  )
}
