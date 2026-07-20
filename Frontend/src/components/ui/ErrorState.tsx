export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex h-40 flex-col items-center justify-center gap-3">
      <span style={{ color: 'rgba(255,255,255,0.3)', fontSize: 13 }}>{message}</span>
      <button onClick={onRetry} style={{ fontSize: 12, color: '#7872e8', textDecoration: 'underline' }}>Réessayer</button>
    </div>
  )
}
