/** Bloc texte brut pour un diagnostic ASM exposé par le serveur HTTP d'ownership. */
export function DiagLine({ title, value }: { title: string; value: string | undefined }) {
  return (
    <div className="bg-[rgba(255,255,255,0.04)] rounded-md px-[12px] py-[10px]">
      <div className="text-[rgba(255,255,255,0.3)] mb-1 text-[10px] font-mono uppercase tracking-[1px]">{title}</div>
      <div className="text-[rgba(255,255,255,0.78)] break-all font-mono text-[11px]">{value || '—'}</div>
    </div>
  )
}
