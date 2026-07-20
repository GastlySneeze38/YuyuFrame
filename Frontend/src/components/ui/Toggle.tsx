// Avant ce fichier, ce switch (pilule + pastille glissante) existait en 4
// copies quasi identiques : 3x dans Settings.tsx ("md", avec bordure) et
// 1x dans ModRow.tsx ("sm", sans bordure, couleur "on" pleine).
export function Toggle({
  checked,
  onChange,
  size = 'md',
  title,
}: {
  checked: boolean
  onChange: () => void
  size?: 'sm' | 'md'
  title?: string
}) {
  if (size === 'sm') {
    return (
      <button
        onClick={onChange}
        title={title}
        className="relative flex-shrink-0"
        style={{ width: 40, height: 22, borderRadius: 11, background: checked ? '#4B3FCF' : 'rgba(255,255,255,0.1)', border: 'none', cursor: 'pointer' }}
      >
        <span
          className="absolute transition-all duration-200"
          style={{ top: 3, left: checked ? 21 : 3, width: 16, height: 16, borderRadius: '50%', background: 'white', boxShadow: '0 1px 4px rgba(0,0,0,0.4)' }}
        />
      </button>
    )
  }

  return (
    <button
      onClick={onChange}
      title={title}
      className="relative flex-shrink-0 rounded-full transition-all duration-200"
      style={{
        width: 44, height: 24,
        background: checked ? 'rgba(75,63,207,0.8)' : 'rgba(255,255,255,0.1)',
        border: `1px solid ${checked ? 'rgba(75,63,207,1)' : 'rgba(255,255,255,0.15)'}`,
      }}
    >
      <span
        className="absolute top-0.5 rounded-full bg-white transition-all duration-200"
        style={{ width: 18, height: 18, left: checked ? 22 : 2 }}
      />
    </button>
  )
}
