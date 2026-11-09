import { useEffect, useRef } from 'react'

/**
 * Fond « carte topographique » : les courbes de niveau d'un bruit qui évolue
 * lentement, tracées sur un canevas. Le bruit est en trois dimensions (x, y
 * et le temps) : avancer dans le temps déforme le relief, et les courbes
 * ondulent, se resserrent, naissent et disparaissent.
 *
 * Le tracé se fait par « marching squares » : le canevas est découpé en
 * cases ; pour chaque case et chaque niveau, on regarde quels coins sont
 * au-dessus du niveau et on trace le segment qui les sépare, placé par
 * interpolation sur les bords.
 *
 * Animations réduites demandées par le système : une seule image, immobile.
 */

/** Côté d'une case, en pixels CSS : plus petit = courbes plus fines, plus cher. */
const CELL = 7
/** Écart entre deux niveaux : plus petit = plus de courbes. */
const LEVEL_STEP = 0.075
/** Taille du relief : plus petit = collines plus larges. */
const SCALE = 0.006
/** Vitesse d'évolution du relief, par seconde. */
const SPEED = 0.05
/** Une image sur deux suffit : le mouvement est lent. */
const FRAME_MS = 33

// ── Bruit de valeurs 3D, lissé ───────────────────────────────────────────

function hash(x: number, y: number, z: number): number {
  let h = (x * 374761393 + y * 668265263 + z * 2147483647) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295
}

function smooth(t: number): number {
  return t * t * t * (t * (t * 6 - 15) + 10)
}

function noise3(x: number, y: number, z: number): number {
  const x0 = Math.floor(x)
  const y0 = Math.floor(y)
  const z0 = Math.floor(z)
  const fx = smooth(x - x0)
  const fy = smooth(y - y0)
  const fz = smooth(z - z0)
  const lerp = (a: number, b: number, t: number) => a + (b - a) * t
  const layer = (z: number) =>
    lerp(
      lerp(hash(x0, y0, z), hash(x0 + 1, y0, z), fx),
      lerp(hash(x0, y0 + 1, z), hash(x0 + 1, y0 + 1, z), fx),
      fy,
    )
  return lerp(layer(z0), layer(z0 + 1), fz)
}

/** Deux octaves : un relief de fond et des détails par-dessus. */
function field(x: number, y: number, t: number): number {
  return noise3(x, y, t) * 0.7 + noise3(x * 2.1 + 17, y * 2.1 + 31, t * 1.3) * 0.3
}

// ── Le composant ─────────────────────────────────────────────────────────

export function TopoBackdrop({ color, className = '', still: stillProp = false }: {
  color: string
  className?: string
  /** Une seule image, immobile : le téléphone n'a pas d'animation en boucle. */
  still?: boolean
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    const still = stillProp || window.matchMedia('(prefers-reduced-motion: reduce)').matches

    let width = 0
    let height = 0
    let cols = 0
    let rows = 0
    let values = new Float32Array(0)

    // La taille vient de l'observateur, jamais d'un `getBoundingClientRect`
    // au montage : il forçait une mise en page en plein changement d'onglet.
    // Densité plafonnée à 2 : des traits fins à 22 % d'opacité ne gagnent rien
    // à 3, et le canevas pesait plus du double à remplir et à composer.
    const resize = (w: number, h: number) => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      width = w
      height = h
      canvas.width = Math.max(1, Math.round(width * dpr))
      canvas.height = Math.max(1, Math.round(height * dpr))
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      cols = Math.ceil(width / CELL) + 1
      rows = Math.ceil(height / CELL) + 1
      values = new Float32Array(cols * rows)
    }

    const draw = (t: number) => {
      // Le relief aux coins de chaque case.
      for (let j = 0; j < rows; j++) {
        for (let i = 0; i < cols; i++) {
          values[j * cols + i] = field(i * CELL * SCALE, j * CELL * SCALE, t)
        }
      }
      ctx.clearRect(0, 0, width, height)
      ctx.lineWidth = 1
      ctx.strokeStyle = `rgb(${color} / 0.22)`
      ctx.beginPath()
      for (let level = LEVEL_STEP; level < 1; level += LEVEL_STEP) {
        for (let j = 0; j < rows - 1; j++) {
          for (let i = 0; i < cols - 1; i++) {
            const a = values[j * cols + i] // haut gauche
            const b = values[j * cols + i + 1] // haut droit
            const c = values[(j + 1) * cols + i + 1] // bas droit
            const d = values[(j + 1) * cols + i] // bas gauche
            const code = (a > level ? 8 : 0) | (b > level ? 4 : 0) | (c > level ? 2 : 0) | (d > level ? 1 : 0)
            if (code === 0 || code === 15) continue
            const x = i * CELL
            const y = j * CELL
            // Où le niveau coupe chaque bord de la case.
            const top = () => [x + CELL * ((level - a) / (b - a)), y] as const
            const right = () => [x + CELL, y + CELL * ((level - b) / (c - b))] as const
            const bottom = () => [x + CELL * ((level - d) / (c - d)), y + CELL] as const
            const left = () => [x, y + CELL * ((level - a) / (d - a))] as const
            const seg = (p: readonly [number, number], q: readonly [number, number]) => {
              ctx.moveTo(p[0], p[1])
              ctx.lineTo(q[0], q[1])
            }
            switch (code) {
              case 1: case 14: seg(left(), bottom()); break
              case 2: case 13: seg(bottom(), right()); break
              case 3: case 12: seg(left(), right()); break
              case 4: case 11: seg(top(), right()); break
              case 6: case 9: seg(top(), bottom()); break
              case 7: case 8: seg(left(), top()); break
              case 5: seg(left(), top()); seg(bottom(), right()); break
              case 10: seg(top(), right()); seg(left(), bottom()); break
            }
          }
        }
      }
      ctx.stroke()
    }

    // Un point de départ au hasard : chaque ouverture a son propre relief.
    const start = Math.random() * 100
    let current = start

    // Figé : le premier dessin attend que le navigateur soit libre, puis
    // apparaît en fondu. L'écran qui le porte s'affiche d'abord, sans lui ;
    // dessiné au montage, il s'ajoutait au changement d'onglet, deux fois
    // même (au montage, puis au premier relevé de taille).
    let drawn = false
    let pending = 0
    const drawStill = () => {
      pending = 0
      draw(current)
      if (!drawn) {
        drawn = true
        canvas.style.opacity = '1'
      }
    }
    const later = (fn: () => void) =>
      typeof requestIdleCallback === 'function' ? requestIdleCallback(fn, { timeout: 400 }) : window.setTimeout(fn, 60)
    const cancelLater = (handle: number) =>
      typeof cancelIdleCallback === 'function' ? cancelIdleCallback(handle) : window.clearTimeout(handle)

    // Un redimensionnement vide le canevas : on redessine l'instant en cours.
    // Le premier relevé arrive juste après la mise en page du montage.
    const observer = new ResizeObserver(([entry]) => {
      const { width: w, height: h } = entry.contentRect
      if (w === width && h === height) return
      resize(w, h)
      if (!still) draw(current)
      else if (drawn) drawStill()
      else if (!pending) pending = later(drawStill)
    })
    observer.observe(canvas)

    let frame = 0
    let last = 0
    const loop = (now: number) => {
      frame = requestAnimationFrame(loop)
      if (now - last < FRAME_MS || width === 0) return
      last = now
      current = start + (now / 1000) * SPEED
      draw(current)
    }
    if (!still) frame = requestAnimationFrame(loop)

    return () => {
      cancelAnimationFrame(frame)
      if (pending) cancelLater(pending)
      observer.disconnect()
    }
  }, [color, stillProp])

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      className={`pointer-events-none absolute inset-0 h-full w-full ${stillProp ? 'opacity-0 transition-opacity duration-300' : ''} ${className}`}
    />
  )
}
