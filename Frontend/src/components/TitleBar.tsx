import { getCurrentWindow } from '@tauri-apps/api/window'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

const win = getCurrentWindow()

export function TitleBar() {
  const t = useT()
  const { username, apiOnline } = useStore()

  const minimize = () => win.minimize()
  const maximize = () => win.toggleMaximize()
  const close = () => win.close()

  return (
    <div
      data-tauri-drag-region
      className="flex h-9 flex-shrink-0 items-center justify-between px-4 bg-[#09090D] border-b border-[rgba(255,255,255,0.05)]"
    >
      {/* Logo */}
      <div className="flex items-center gap-2">
        <div className="h-3.5 w-3.5 rounded-sm bg-[#4B3FCF]" />
        <span className="text-xs font-bold tracking-[0.2em] text-[rgba(255,255,255,0.5)]">
          YUYUFRAME
        </span>
        {apiOnline === false && (
          <span
            title={t('titleBar.apiOffline')}
            className="flex items-center justify-center"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" width={11} height={11} className="text-[rgba(255,160,60,0.55)]">
              <path d="M8.5 16.5a5 5 0 0 1 7 0M5 13a9.5 9.5 0 0 1 14 0M12 20h.01" strokeLinecap="round" />
              <line x1="3" y1="3" x2="21" y2="21" strokeLinecap="round" />
            </svg>
          </span>
        )}
      </div>

      {/* Center: user info + theme toggle */}
      <div className="flex items-center gap-3">
        {username && (
          <span className="text-xs text-[rgba(255,255,255,0.28)]">
            {username}
          </span>
        )}
      </div>

      {/* Window controls */}
      <div className="flex items-center gap-0.5">
        {/* Minimize */}
        <button
          onClick={minimize}
          className="flex h-7 w-7 items-center justify-center rounded transition-all duration-150 text-[rgba(190,70,70,0.45)] hover:bg-[rgba(180,60,60,0.18)] hover:text-[rgba(220,90,90,0.85)]"
        >
          <svg width="10" height="2" viewBox="0 0 10 2" fill="currentColor">
            <rect width="10" height="1.5" y="0.25" />
          </svg>
        </button>
        {/* Maximize */}
        <button
          onClick={maximize}
          className="flex h-7 w-7 items-center justify-center rounded transition-all duration-150 text-[rgba(255,255,255,0.18)] hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(255,255,255,0.45)]"
        >
          <svg width="9" height="9" viewBox="0 0 9 9" fill="none" stroke="currentColor" strokeWidth="1">
            <rect x="0.5" y="0.5" width="8" height="8" />
          </svg>
        </button>
        {/* Close */}
        <button
          onClick={close}
          className="flex h-7 w-7 items-center justify-center rounded transition-all duration-150 text-[rgba(225,60,60,0.65)] hover:bg-[rgba(220,45,45,0.22)] hover:text-[rgb(245,80,80)]"
        >
          <svg width="9" height="9" viewBox="0 0 9 9" fill="none" stroke="currentColor" strokeWidth="1.5">
            <line x1="0.5" y1="0.5" x2="8.5" y2="8.5" />
            <line x1="8.5" y1="0.5" x2="0.5" y2="8.5" />
          </svg>
        </button>
      </div>
    </div>
  )
}
