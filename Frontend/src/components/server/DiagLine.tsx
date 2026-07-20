/** Bloc texte brut pour un diagnostic ASM exposé par le serveur HTTP d'ownership. */
export function DiagLine({ title, value }: { title: string; value: string | undefined }) {
  return (
    <div style={{ background: 'rgba(255,255,255,0.04)', borderRadius: 6, padding: '10px 12px' }}>
      <div style={{ color: 'rgba(255,255,255,0.3)', marginBottom: 4, fontSize: 10, fontFamily: 'monospace', textTransform: 'uppercase', letterSpacing: 1 }}>{title}</div>
      <div style={{ color: 'rgba(255,255,255,0.78)', wordBreak: 'break-all', fontFamily: 'monospace', fontSize: 11 }}>{value || '—'}</div>
    </div>
  )
}
