import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { getCurrentWebview } from '@tauri-apps/api/webview'
import { displayName } from '@/components/mods/modUtils'

/**
 * Socle commun des deux panneaux de l'accueil (mods, packs) : le cadre qui se
 * déplie vers le haut, le dépôt de fichiers, l'empilement d'icônes.
 */

/** Hauteur du panneau fermé : une seule ligne. */
export const CLOSED_H = 56
/** Hauteur du panneau ouvert, bornée par la fenêtre. */
const OPEN_H = 'min(440px, calc(100vh - 200px))'
/** Courbe de sortie franche (Emil Kowalski) : le mouvement part tout de suite. */
const EASE_OUT = 'cubic-bezier(0.23, 1, 0.32, 1)'

/**
 * Le panneau : l'en-tête est EN BAS, là où il est posé sur l'accueil, et le
 * contenu apparaît au-dessus de lui. Ouvrir ne déplace donc pas ce qu'on
 * vient de cliquer.
 *
 * Deux couches. La ligne d'en-tête, toujours là. Et, derrière elle, le
 * panneau ouvert, à sa hauteur finale dès le départ, qui n'anime que
 * `transform` et `opacity` : il monte de quelques pixels en grandissant
 * depuis le bas (comme une bulle depuis ce qui l'ouvre), sans rien remettre
 * en page et sans repeindre à chaque image.
 *
 * Remplace un `clip-path` animé : celui-ci repeignait tout le panneau à
 * chaque image et, dans WebView2, faisait clignoter en blanc le bord du haut
 * pendant le dépliage.
 *
 * Le contenu n'est monté qu'à la première ouverture, puis gardé : fermé, un
 * panneau ne coûte que sa ligne.
 */
export function ShelfPanel({ open, onToggle, header, children, dropping, headerRef }: {
  open: boolean
  onToggle: () => void
  header: ReactNode
  children: ReactNode
  /** Un fichier est survolé au-dessus du panneau. */
  dropping: boolean
  /** Ligne d'en-tête : sert de zone de dépôt quand le panneau est fermé. */
  headerRef: RefObject<HTMLDivElement | null>
}) {
  const layerRef = useRef<HTMLDivElement>(null)
  const [mounted, setMounted] = useState(open)
  useEffect(() => { if (open) setMounted(true) }, [open])

  // Fermé, le panneau est transparent mais toujours là : `inert` le retire
  // du parcours au clavier et des clics.
  useEffect(() => {
    const el = layerRef.current
    if (!el) return
    if (open) el.removeAttribute('inert')
    else el.setAttribute('inert', '')
  }, [open, mounted])

  return (
    <div className="relative min-w-0" style={{ height: CLOSED_H }}>
      {mounted && (
        <div
          ref={layerRef}
          className={`absolute inset-x-0 bottom-0 z-40 flex origin-bottom flex-col rounded-xl border bg-bg-secondary shadow-[0_-12px_40px_rgba(0,0,0,0.45)] ${
            dropping ? 'border-[rgb(var(--accent-hover)/0.7)]' : 'border-[rgb(var(--accent)/0.35)]'
          }`}
          style={{
            height: OPEN_H,
            paddingBottom: CLOSED_H,
            opacity: open ? 1 : 0,
            transform: open ? 'none' : 'translateY(10px) scale(0.97)',
            // Fermer va plus vite qu'ouvrir : le système répond, il ne se
            // met pas en scène.
            transition: open
              ? `transform 240ms ${EASE_OUT}, opacity 160ms ${EASE_OUT}`
              : `transform 160ms ${EASE_OUT}, opacity 120ms ease-out`,
            pointerEvents: open ? 'auto' : 'none',
            willChange: 'transform, opacity',
          }}
        >
          {children}
        </div>
      )}
      <div
        ref={headerRef}
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onClick={onToggle}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle() } }}
        // Au-dessus du panneau ouvert : fermé, elle porte son propre fond et
        // sa bordure ; ouvert, elle devient transparente et se fond dans le
        // panneau, séparée du contenu par un filet.
        className={`absolute inset-0 z-50 flex cursor-pointer items-center gap-2.5 rounded-xl border px-3 outline-none transition-[background-color,border-color] duration-150 focus-visible:ring-1 focus-visible:ring-[rgb(var(--accent)/0.5)] ${
          dropping
            ? 'border-[rgb(var(--accent-hover)/0.7)] bg-[rgb(var(--accent)/0.12)]'
            : open
              ? 'rounded-t-none border-transparent border-t-[rgba(255,255,255,0.07)] bg-transparent'
              : 'border-[rgba(255,255,255,0.07)] bg-bg-secondary hover:border-[rgba(255,255,255,0.12)]'
        }`}
      >
        {header}
        <svg
          viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
          className="ml-auto h-4 w-4 flex-shrink-0 text-[rgba(255,255,255,0.4)] transition-transform duration-200"
          style={{ transform: open ? 'rotate(180deg)' : 'none', transitionTimingFunction: EASE_OUT }}
        >
          <path d="M6 15l6-6 6 6" />
        </svg>
      </div>
    </div>
  )
}

/**
 * File d'attente des lectures d'icônes (jars, `pack.png`) : quatre à la fois.
 * Une instance de 200 mods lançait sinon 200 lectures de fichier ensemble,
 * qui se disputaient le disque et bloquaient le pont avec Rust.
 */
const ICON_CONCURRENCY = 4
let iconRunning = 0
const iconWaiting: (() => void)[] = []
export function queueIcon<T>(load: () => Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const run = () => {
      iconRunning++
      load().then(resolve, reject).finally(() => {
        iconRunning--
        iconWaiting.shift()?.()
      })
    }
    if (iconRunning < ICON_CONCURRENCY) run()
    else iconWaiting.push(run)
  })
}

/**
 * Dépôt de fichiers sur un élément. Passe par Tauri et non par le DOM : la
 * webview a le sien coupé, et lui ne donnerait pas le chemin du fichier (voir
 * `InstanceSettingsModal`). La position arrive en pixels physiques.
 */
export function useFileDrop(
  zone: () => HTMLElement | null,
  accepts: (path: string) => boolean,
  onDrop: (paths: string[]) => void,
): boolean {
  const [hovering, setHovering] = useState(false)
  const latest = useRef({ zone, accepts, onDrop })
  latest.current = { zone, accepts, onDrop }

  useEffect(() => {
    let unlisten: (() => void) | undefined
    let cancelled = false
    const inside = (p: { x: number; y: number }) => {
      const box = latest.current.zone()?.getBoundingClientRect()
      if (!box) return false
      const x = p.x / window.devicePixelRatio
      const y = p.y / window.devicePixelRatio
      return x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
    }
    getCurrentWebview()
      .onDragDropEvent((event) => {
        const e = event.payload
        if (e.type === 'over') { setHovering(inside(e.position)); return }
        if (e.type === 'drop') {
          const ok = inside(e.position)
          setHovering(false)
          const files = e.paths.filter((p) => latest.current.accepts(p))
          if (ok && files.length > 0) latest.current.onDrop(files)
          return
        }
        setHovering(false)
      })
      .then((fn) => { if (cancelled) fn(); else unlisten = fn })
      .catch(() => {})
    return () => { cancelled = true; unlisten?.() }
  }, [])

  return hovering
}

/** Combien d'éléments de `size` px, chevauchés de `overlap` px, tiennent en largeur. */
export function useStackCapacity(size: number, overlap: number) {
  const ref = useRef<HTMLDivElement>(null)
  const [count, setCount] = useState(0)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const measure = () => {
      const w = el.getBoundingClientRect().width
      const n = w < size ? 0 : 1 + Math.floor((w - size) / (size - overlap))
      setCount((c) => (c === n ? c : n))
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [size, overlap])
  return [ref, count] as const
}

/**
 * Nom lisible d'un fichier : `sodium-fabric-0.6.5+mc1.21.4` → « Sodium ».
 * Sert quand Modrinth ne connaît pas le fichier. On garde les mots jusqu'au
 * premier qui ressemble à une version ou à un loader.
 */
const LOADER_WORDS = new Set(['fabric', 'forge', 'neoforge', 'quilt', 'mc'])
export function prettyName(file: string): string {
  const words = displayName(file).replace(/\.zip$/i, '').split(/[-_+ ]+/).filter(Boolean)
  const kept: string[] = []
  for (const w of words) {
    const l = w.toLowerCase()
    if (/^v?\d/.test(l) || /^mc\d/.test(l)) break
    if (kept.length > 0 && LOADER_WORDS.has(l)) break
    kept.push(w)
  }
  const out = (kept.length ? kept : words.slice(0, 1)).join(' ')
  return out.charAt(0).toUpperCase() + out.slice(1)
}

/** Teinte stable tirée d'un nom, pour ce qui n'a pas d'image (identité, voulu en dur). */
export function tintFor(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360
  return `linear-gradient(135deg, hsl(${h} 45% 30%), hsl(${(h + 40) % 360} 50% 17%))`
}

/** Bouton de corbeille en deux temps : un clic arme, le second supprime. */
export function DeleteButton({ label, confirmLabel, onConfirm, disabled }: {
  label: string
  confirmLabel: string
  onConfirm: () => void
  disabled?: boolean
}) {
  const [armed, setArmed] = useState(false)
  useEffect(() => {
    if (!armed) return
    const timer = setTimeout(() => setArmed(false), 2500)
    return () => clearTimeout(timer)
  }, [armed])
  return (
    <button
      type="button"
      disabled={disabled}
      title={label}
      aria-label={armed ? confirmLabel : label}
      onClick={(e) => { e.stopPropagation(); if (armed) { setArmed(false); onConfirm() } else setArmed(true) }}
      className={`flex h-7 flex-shrink-0 items-center justify-center rounded-md text-[11px] font-semibold transition-[opacity,background-color,color] duration-150 disabled:hidden ${
        armed
          ? 'bg-danger/15 px-2 text-danger opacity-100'
          : 'w-7 text-[rgba(255,255,255,0.4)] opacity-0 hover:bg-danger/15 hover:text-danger group-hover:opacity-100 focus-visible:opacity-100'
      }`}
    >
      {armed ? confirmLabel : (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
          <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 002 2h6a2 2 0 002-2l1-12M9 7V4h6v3" />
        </svg>
      )}
    </button>
  )
}

/** Interrupteur visuel (la pastille glisse, en `transform` seulement). */
export function Switch({ on }: { on: boolean }) {
  return (
    <span className={`relative h-4 w-7 flex-shrink-0 rounded-full transition-colors duration-200 ${on ? 'bg-[rgb(var(--accent))]' : 'bg-[rgba(255,255,255,0.14)]'}`}>
      <span
        className="absolute left-[2px] top-[2px] h-3 w-3 rounded-full bg-white transition-transform duration-200"
        style={{ transform: on ? 'translateX(12px)' : 'none', transitionTimingFunction: EASE_OUT }}
      />
    </span>
  )
}
