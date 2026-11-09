import { useEffect, useRef, type CSSProperties } from 'react'

/** Déplacement (px) à partir duquel on décide du sens du geste. */
const SLOP = 8
/** Largeur de la pastille (`.touch-range`, index.css) : la piste utile est plus courte d'autant. */
const THUMB = 24

/**
 * Le curseur des réglages du téléphone (2026-11-09).
 *
 * Un `<input type="range">` natif prend la valeur **dès que le doigt touche la
 * piste**, avant de savoir si l'on fait défiler la page : en descendant dans
 * une longue liste de réglages (options de Minecraft), chaque curseur sous le
 * pouce changeait de valeur au passage. Ici le doigt ne passe plus par le
 * curseur natif (`pointer-events: none` au toucher) mais par ce cadre :
 * - glissé franchement horizontal : on suit le doigt (le défilement est
 *   alors bloqué pour ce geste) ;
 * - glissé vertical : rien, la page défile ;
 * - appui sans glissé : la valeur va sous le doigt.
 *
 * Le curseur natif reste là pour le clavier, les lecteurs d'écran et la
 * souris (PC, aperçu dans le navigateur).
 */
export function TouchRange({ min, max, step = 1, value, onChange, label, className = 'w-full' }: {
  min: number
  max: number
  step?: number
  value: number
  onChange: (v: number) => void
  label: string
  className?: string
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const latest = useRef({ min, max, step, value, onChange })
  latest.current = { min, max, step, value, onChange }

  useEffect(() => {
    const wrap = wrapRef.current!
    let id: number | null = null
    let x0 = 0
    let y0 = 0
    let state: 'idle' | 'deciding' | 'sliding' | 'ignored' = 'idle'

    const setFromX = (x: number) => {
      const { min, max, step, value, onChange } = latest.current
      const r = inputRef.current!.getBoundingClientRect()
      const ratio = Math.max(0, Math.min(1, (x - r.left - THUMB / 2) / Math.max(1, r.width - THUMB)))
      const raw = min + ratio * (max - min)
      const snapped = Math.min(max, Math.max(min, Math.round((raw - min) / step) * step + min))
      // Les pas décimaux (0,1) laissent traîner des 0,30000000000000004.
      const v = Number(snapped.toFixed(6))
      if (v !== value) onChange(v)
    }
    const find = (list: TouchList) => {
      for (let i = 0; i < list.length; i++) if (list[i].identifier === id) return list[i]
      return null
    }

    const onStart = (e: TouchEvent) => {
      if (e.touches.length !== 1) { state = 'ignored'; return }
      const t = e.touches[0]
      id = t.identifier
      x0 = t.clientX
      y0 = t.clientY
      state = 'deciding'
    }
    const onMove = (e: TouchEvent) => {
      const t = find(e.changedTouches)
      if (!t || state === 'idle' || state === 'ignored') return
      if (state === 'deciding') {
        const dx = Math.abs(t.clientX - x0)
        const dy = Math.abs(t.clientY - y0)
        if (dx < SLOP && dy < SLOP) return
        if (dx <= dy || !e.cancelable) { state = 'ignored'; return }
        state = 'sliding'
      }
      e.preventDefault()
      setFromX(t.clientX)
    }
    const onEnd = (e: TouchEvent) => {
      const t = find(e.changedTouches)
      if (!t) return
      // Un appui sans glissé : la valeur va sous le doigt.
      if (state === 'deciding') setFromX(t.clientX)
      state = 'idle'
      id = null
    }
    wrap.addEventListener('touchstart', onStart, { passive: true })
    wrap.addEventListener('touchmove', onMove, { passive: false })
    wrap.addEventListener('touchend', onEnd, { passive: true })
    wrap.addEventListener('touchcancel', () => { state = 'idle'; id = null }, { passive: true })
    return () => {
      wrap.removeEventListener('touchstart', onStart)
      wrap.removeEventListener('touchmove', onMove)
      wrap.removeEventListener('touchend', onEnd)
    }
  }, [])

  const fill = ((value - min) / (max - min)) * 100
  return (
    <div ref={wrapRef} className={className}>
      <input
        ref={inputRef}
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
        style={{ '--fill': `${Math.max(0, Math.min(100, fill))}%` } as CSSProperties}
        className="touch-range w-full"
      />
    </div>
  )
}
