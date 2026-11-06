export function Spinner() {
  return (
    <div className="flex h-40 items-center justify-center">
      <span className="h-8 w-8 animate-spin rounded-full border-2 border-[rgba(255,255,255,0.08)] border-t-[rgb(var(--accent)/0.8)]" />
    </div>
  )
}
