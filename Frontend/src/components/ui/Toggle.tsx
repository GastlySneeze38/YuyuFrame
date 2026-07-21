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
        className={`relative flex-shrink-0 rounded-full border-0 cursor-pointer w-10 h-[22px] ${checked ? 'bg-[#4B3FCF]' : 'bg-[rgba(255,255,255,0.1)]'}`}
      >
        <span
          className={`absolute transition-all duration-200 top-[3px] w-4 h-4 rounded-full bg-white shadow-[0_1px_4px_rgba(0,0,0,0.4)] ${checked ? 'left-[21px]' : 'left-[3px]'}`}
        />
      </button>
    )
  }

  return (
    <button
      onClick={onChange}
      title={title}
      className={`relative flex-shrink-0 rounded-full transition-all duration-200 w-11 h-6 border ${checked ? 'bg-[rgba(75,63,207,0.8)] border-[rgba(75,63,207,1)]' : 'bg-[rgba(255,255,255,0.1)] border-[rgba(255,255,255,0.15)]'}`}
    >
      <span
        className={`absolute top-0.5 rounded-full bg-white transition-all duration-200 w-[18px] h-[18px] ${checked ? 'left-[22px]' : 'left-0.5'}`}
      />
    </button>
  )
}
