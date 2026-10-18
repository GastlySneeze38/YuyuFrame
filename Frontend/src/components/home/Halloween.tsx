import { useEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { motion } from 'framer-motion'
import launchSoundUrl from '@/assets/sounds/halloween-launch.ogg'

/**
 * Habillage « Halloween » de la bannière d'accueil (thème `halloween` d'une
 * bannière publiée depuis le back-office). Deux couches, parce qu'elles ne
 * vivent pas à la même profondeur :
 *
 * - `HalloweenSky`, derrière la ligne d'arbres : lueur d'horizon, lune,
 *   éclairs, sorcière, essaim de chauves-souris, braises ;
 * - `HalloweenGround`, devant la bande du pseudo : citrouilles, yeux dans le
 *   noir, araignée, fantôme, brume.
 *
 * Tout est décoratif : `pointer-events-none` partout, rien ne doit voler un
 * clic au panneau. Les positions sont figées à l'avance (même règle que les
 * confettis) — seule la simulation de l'essaim tire au sort, une fois, dans
 * son effet.
 *
 * Citrouilles et fantôme sont dessinés en blocs comme les arbres et le ghast
 * (`Skyline.tsx`, `GhastFlight.tsx`) : c'est le style du panneau.
 */

/** Silhouette de chauve-souris, ailes ouvertes (viewBox 32 × 14). */
const BAT_PATH =
  'M16 4c-1 0-1.6-1.2-1.6-2 0 0-.8 1.4-1.4 1.6C11 2 8 .5 4 1c1.2 1 1.6 2.4 1.4 3.6C3.8 4 1.8 4.4 0 6c2.4-.2 4 .6 5 2 1-1 2.6-1.2 3.8-.4C9.6 6.4 11 6 12.6 7c.8 1.6 2 3 3.4 4 1.4-1 2.6-2.4 3.4-4 1.6-1 3-.6 3.8.6 1.2-.8 2.8-.6 3.8.4 1-1.4 2.6-2.2 5-2-1.8-1.6-3.8-2-5.4-1.4-.2-1.2.2-2.6 1.4-3.6-4-.5-7 1-8.6 2.6-.6-.2-1.4-1.6-1.4-1.6 0 .8-.6 2-1.6 2z'

const INK = '#0a0408'

// ── Ciel ────────────────────────────────────────────────────────────────────

const EMBERS = Array.from({ length: 18 }, (_, i) => ({
  left: `${((i * 29.3 + 7) % 100).toFixed(2)}%`,
  size: i % 4 === 0 ? 3 : 2,
  drift: ((i % 5) - 2) * 9,
  duration: 6 + (i % 5) * 1.3,
  delay: (i % 9) * 0.8,
}))

export function HalloweenSky() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden">
      {/* Lueur de citrouille à l'horizon, derrière la silhouette des arbres. */}
      <motion.div
        animate={{ opacity: [0.65, 1, 0.65] }}
        transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut' }}
        className="absolute inset-0 bg-[radial-gradient(90%_55%_at_50%_100%,rgba(255,110,20,0.38)_0%,rgba(160,40,10,0.16)_45%,transparent_75%)]"
      />

      {/* Éclair lointain : deux battements pâles, puis rien pendant un long
          moment. Volontairement faible — un vrai flash fatiguerait. */}
      <motion.div
        animate={{ opacity: [0, 0.16, 0.02, 0.1, 0] }}
        transition={{ duration: 0.9, times: [0, 0.12, 0.3, 0.42, 1], repeat: Infinity, repeatDelay: 17, delay: 8 }}
        className="absolute inset-0 bg-[radial-gradient(80%_60%_at_70%_10%,rgba(220,210,255,1)_0%,transparent_70%)]"
      />

      <Moon />

      {/* La sorcière passe devant la lune de temps en temps. */}
      <motion.div
        className="absolute top-[13%]"
        initial={{ left: '-12%' }}
        animate={{ left: '108%' }}
        transition={{ duration: 11, ease: 'linear', repeat: Infinity, repeatDelay: 19, delay: 5 }}
      >
        <motion.svg
          viewBox="0 0 60 32"
          className="h-[clamp(18px,4vh,30px)] w-auto"
          animate={{ y: [0, -6, 0, 4, 0], rotate: [-4, 3, -4] }}
          transition={{ duration: 2.6, repeat: Infinity, ease: 'easeInOut' }}
        >
          <g fill={INK}>
            <line x1="4" y1="25" x2="50" y2="19" stroke={INK} strokeWidth="1.8" strokeLinecap="round" />
            <polygon points="48,17 59,13 59,27 50,21" />
            <polygon points="17,24 25,11 33,12 36,22" />
            <circle cx="28" cy="10" r="3" />
            <rect x="22" y="8.5" width="13" height="1.6" />
            <polygon points="25,9 32,9 35,0" />
          </g>
        </motion.svg>
      </motion.div>

      <BatSwarm />

      {/* Braises : elles montent depuis le bas et s'éteignent en route. */}
      {EMBERS.map((e, i) => (
        <motion.span
          key={i}
          className="absolute bottom-0 rounded-full"
          style={{ left: e.left, width: e.size, height: e.size, background: '#ffa040', boxShadow: '0 0 6px 1px rgba(255,140,40,0.8)', willChange: 'transform' }}
          initial={{ y: 10, x: 0, opacity: 0 }}
          animate={{ y: -260, x: e.drift, opacity: [0, 0.95, 0.7, 0] }}
          transition={{ duration: e.duration, delay: e.delay, repeat: Infinity, ease: 'easeOut' }}
        />
      ))}
    </div>
  )
}

/** Lune pâle entre le titre et la bannière ; son halo respire. */
function Moon() {
  return (
    <motion.div
      animate={{ scale: [1, 1.03, 1] }}
      transition={{ duration: 7, repeat: Infinity, ease: 'easeInOut' }}
      className="absolute left-[34%] top-[12%] h-[clamp(46px,9vh,76px)] w-[clamp(46px,9vh,76px)] rounded-full"
      style={{
        background: 'radial-gradient(circle at 38% 36%, #fff3d6 0%, #ffd38a 45%, #e8953f 100%)',
        boxShadow: '0 0 40px 8px rgba(255,180,90,0.35), 0 0 120px 30px rgba(255,120,40,0.18)',
      }}
    >
      {/* Cratères, à peine marqués. */}
      <span className="absolute left-[56%] top-[24%] h-[18%] w-[18%] rounded-full" style={{ background: 'rgba(200,110,40,0.28)' }} />
      <span className="absolute left-[26%] top-[58%] h-[13%] w-[13%] rounded-full" style={{ background: 'rgba(200,110,40,0.22)' }} />
      <span className="absolute left-[62%] top-[62%] h-[9%] w-[9%] rounded-full" style={{ background: 'rgba(200,110,40,0.2)' }} />
    </motion.div>
  )
}

// ── Essaim de chauves-souris ────────────────────────────────────────────────
//
// Une approximation des « boids » (Reynolds) : chaque chauve-souris ne regarde
// que ses voisines proches et combine trois envies — rester près du groupe,
// voler dans le même sens, ne pas percuter la plus proche — plus l'attrait
// d'un point qui se promène dans le ciel. De temps en temps, le groupe
// s'éparpille d'un coup puis se reforme, comme un vol qu'on a effrayé.
//
// Sur un canevas et non en éléments animés : 36 chauves-souris dont chacune
// dépend des autres, c'est une simulation, pas une suite d'images clés. Le
// coût est négligeable (36² comparaisons par image).

const BAT_COUNT = 36
/** Rayon dans lequel une voisine compte, en pixels. */
const NEIGHBOUR_RADIUS = 70
/** En dessous de cette distance, on s'écarte. */
const PERSONAL_SPACE = 16
const MIN_SPEED = 45
const MAX_SPEED = 125
/** L'éparpillement : toutes les SCATTER_EVERY secondes, pendant SCATTER_FOR. */
const SCATTER_EVERY = 13
const SCATTER_FOR = 1.3

interface Bat {
  x: number
  y: number
  vx: number
  vy: number
  size: number
  phase: number
  flap: number
}

function BatSwarm() {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = ref.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    const shape = new Path2D(BAT_PATH)

    let w = 0
    let h = 0
    const resize = () => {
      const rect = canvas.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      w = rect.width
      h = rect.height
      canvas.width = Math.max(1, Math.round(w * dpr))
      canvas.height = Math.max(1, Math.round(h * dpr))
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(canvas)

    // Le vol part groupé, déjà dans le ciel : arriver de l'extérieur
    // laisserait le panneau vide les premières secondes.
    const bats: Bat[] = Array.from({ length: BAT_COUNT }, () => ({
      x: w * 0.25 + Math.random() * 120,
      y: h * 0.25 + Math.random() * 60,
      vx: 50 + Math.random() * 30,
      vy: (Math.random() - 0.5) * 30,
      size: 9 + Math.random() * 8,
      phase: Math.random() * Math.PI * 2,
      flap: 10 + Math.random() * 6,
    }))

    const draw = () => {
      ctx.clearRect(0, 0, w, h)
      for (const b of bats) {
        const scale = b.size / 32
        const dir = b.vx < 0 ? -1 : 1
        // Les ailes battent : on écrase la silhouette verticalement.
        const wings = 0.3 + 0.7 * Math.abs(Math.cos(b.phase))
        ctx.save()
        ctx.translate(b.x, b.y)
        ctx.rotate(Math.atan2(b.vy, Math.abs(b.vx)) * 0.4 * dir)
        ctx.scale(scale * dir, scale * wings)
        ctx.translate(-16, -7)
        ctx.fillStyle = INK
        ctx.fill(shape)
        // Un liseré orangé, la lumière de l'horizon : sans lui, du noir sur
        // un ciel presque noir ne se verrait qu'en passant devant la lune.
        ctx.lineWidth = 1.2 / scale
        ctx.strokeStyle = 'rgba(255,150,80,0.32)'
        ctx.stroke(shape)
        ctx.restore()
      }
    }

    // Mouvement réduit demandé par le système : un vol figé, dessiné une fois.
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      draw()
      return () => observer.disconnect()
    }

    let frame = 0
    let last = performance.now()
    let clock = 0
    const step = (now: number) => {
      // Borné : au retour d'un onglet caché, un pas de plusieurs secondes
      // enverrait tout le monde hors du ciel.
      const dt = Math.min((now - last) / 1000, 0.05)
      last = now
      clock += dt

      const scattering = clock % SCATTER_EVERY > SCATTER_EVERY - SCATTER_FOR
      // Le point qui attire l'essaim balaie le ciel en courbe de Lissajous,
      // au-dessus des arbres.
      const tx = w * (0.5 + 0.42 * Math.sin(clock * 0.13))
      const ty = h * (0.3 + 0.16 * Math.sin(clock * 0.31 + 1))
      const ceiling = 8
      const floor = h * 0.68

      for (const b of bats) {
        let n = 0
        let cx = 0
        let cy = 0
        let ax = 0
        let ay = 0
        let sx = 0
        let sy = 0
        for (const o of bats) {
          if (o === b) continue
          const dx = o.x - b.x
          const dy = o.y - b.y
          const d2 = dx * dx + dy * dy
          if (d2 > NEIGHBOUR_RADIUS * NEIGHBOUR_RADIUS) continue
          n++
          cx += o.x
          cy += o.y
          ax += o.vx
          ay += o.vy
          if (d2 < PERSONAL_SPACE * PERSONAL_SPACE) {
            const d = Math.sqrt(d2) || 1
            sx -= dx / d / d
            sy -= dy / d / d
          }
        }

        let fx = 0
        let fy = 0
        if (n > 0) {
          const cohesion = scattering ? -3 : 0.9
          fx += (cx / n - b.x) * cohesion + (ax / n - b.vx) * 1.1
          fy += (cy / n - b.y) * cohesion + (ay / n - b.vy) * 1.1
        }
        fx += sx * 1400
        fy += sy * 1400
        if (!scattering) {
          fx += (tx - b.x) * 0.35
          fy += (ty - b.y) * 0.35
        }
        // Un peu d'hésitation propre à chacune : un vol trop régulier a
        // l'air mécanique.
        fx += (Math.random() - 0.5) * 90
        fy += (Math.random() - 0.5) * 90
        // Bords souples : on revient vers le ciel plutôt que de rebondir.
        if (b.y < ceiling) fy += (ceiling - b.y) * 6
        if (b.y > floor) fy -= (b.y - floor) * 6
        if (b.x < -40) fx += (-40 - b.x) * 4
        if (b.x > w + 40) fx -= (b.x - w - 40) * 4

        b.vx += fx * dt
        b.vy += fy * dt
        const speed = Math.hypot(b.vx, b.vy) || 1
        const max = scattering ? MAX_SPEED * 1.5 : MAX_SPEED
        const clamped = Math.min(Math.max(speed, MIN_SPEED), max)
        b.vx = (b.vx / speed) * clamped
        b.vy = (b.vy / speed) * clamped
        b.x += b.vx * dt
        b.y += b.vy * dt
        b.phase += b.flap * dt
      }

      draw()
      frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)

    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
    }
  }, [])

  return <canvas ref={ref} className="absolute inset-0 h-full w-full" />
}

// ── Sol ─────────────────────────────────────────────────────────────────────

/**
 * Citrouilles posées de part et d'autre du pseudo (centré en bas), jamais
 * sous lui, ni sur le repère de version en bas à droite.
 * `hop` : sautille de temps en temps ; `wobble` : se dandine.
 */
const PUMPKINS = [
  { left: '5%', size: 'clamp(26px,6vh,44px)', delay: 0, hop: false, wobble: true },
  { left: '13%', size: 'clamp(18px,4vh,30px)', delay: 0.7, hop: true, wobble: false },
  { left: '22%', size: 'clamp(14px,3vh,22px)', delay: 1.3, hop: false, wobble: false },
  { right: '13%', size: 'clamp(22px,5vh,38px)', delay: 0.4, hop: false, wobble: false },
  { right: '22%', size: 'clamp(15px,3.2vh,24px)', delay: 1.9, hop: true, wobble: true },
]

/** Paires d'yeux qui s'allument dans les arbres et clignent. */
const EYES = [
  { left: '30%', bottom: '17%', delay: 2, color: '#ffe066' },
  { left: '64%', bottom: '21%', delay: 6.5, color: '#9dff6a' },
  { left: '82%', bottom: '15%', delay: 11, color: '#ff6a6a' },
]

export function HalloweenGround() {
  return (
    <div className="pointer-events-none absolute inset-0 z-10 overflow-hidden">
      <Fog />

      {EYES.map((e, i) => (
        <motion.div
          key={i}
          className="absolute flex gap-[5px]"
          style={{ left: e.left, bottom: e.bottom }}
          // Allumés quelques secondes, deux clignements, puis disparus.
          animate={{ opacity: [0, 1, 1, 0.05, 1, 1, 0.05, 1, 0], scaleY: [1, 1, 1, 0.1, 1, 1, 0.1, 1, 1] }}
          transition={{ duration: 4, times: [0, 0.08, 0.35, 0.4, 0.45, 0.7, 0.74, 0.78, 1], repeat: Infinity, repeatDelay: 9, delay: e.delay }}
        >
          {[0, 1].map((k) => (
            <span key={k} className="h-[3px] w-[4px] rounded-full" style={{ background: e.color, boxShadow: `0 0 6px 1px ${e.color}` }} />
          ))}
        </motion.div>
      ))}

      <Spider />

      {PUMPKINS.map((p, i) => (
        <Pumpkin key={i} {...p} />
      ))}

      <Ghost />
    </div>
  )
}

function Pumpkin({ left, right, size, delay, hop, wobble }: { left?: string; right?: string; size: string; delay: number; hop: boolean; wobble: boolean }) {
  return (
    <motion.div
      className="absolute bottom-[clamp(4px,1vh,10px)]"
      style={{ left, right, width: size, height: size, transformOrigin: '50% 100%' }}
      animate={
        hop
          ? { y: [0, -16, 0, -6, 0], scaleY: [1, 1.08, 0.86, 1.04, 1] }
          : wobble
            ? { rotate: [0, -7, 6, -3, 0] }
            : undefined
      }
      transition={{ duration: hop ? 0.9 : 1.4, repeat: Infinity, repeatDelay: hop ? 6 : 4.5, delay: delay + 2 }}
    >
      {/* Lueur de la bougie au pied de la citrouille. */}
      <motion.div
        className="absolute -inset-[60%] rounded-full"
        style={{ background: 'radial-gradient(circle, rgba(255,150,40,0.38) 0%, transparent 60%)' }}
        animate={{ opacity: [0.6, 1, 0.75, 0.95, 0.6] }}
        transition={{ duration: 1.6, repeat: Infinity, delay }}
      />
      <svg viewBox="0 0 12 12" shapeRendering="crispEdges" className="relative h-full w-full">
        <rect x="1" y="2" width="10" height="9" fill="#e07a12" />
        <rect x="0" y="3" width="12" height="7" fill="#e07a12" />
        <rect x="3" y="2" width="1" height="9" fill="#b85d0c" />
        <rect x="8" y="2" width="1" height="9" fill="#b85d0c" />
        <rect x="5" y="0" width="2" height="2" fill="#4a6b1f" />
        <rect x="7" y="1" width="2" height="1" fill="#5f8a26" />
        {/* Le visage sculpté vacille comme une flamme. */}
        <motion.g
          fill="#ffd34d"
          animate={{ opacity: [1, 0.7, 1, 0.85, 1] }}
          transition={{ duration: 0.8 + delay * 0.3, repeat: Infinity }}
        >
          <rect x="2" y="4" width="2" height="2" />
          <rect x="8" y="4" width="2" height="2" />
          <rect x="5" y="6" width="2" height="1" />
          <rect x="2" y="8" width="8" height="1" />
          <rect x="3" y="9" width="2" height="1" />
          <rect x="7" y="9" width="2" height="1" />
        </motion.g>
      </svg>
    </motion.div>
  )
}

/** Une araignée descend au bout de son fil, se balance, et remonte. */
function Spider() {
  return (
    <motion.div
      className="absolute left-[55%] top-0 flex flex-col items-center"
      style={{ transformOrigin: '50% 0%' }}
      animate={{ rotate: [0, 6, -6, 4, 0] }}
      transition={{ duration: 3, repeat: Infinity, ease: 'easeInOut' }}
    >
      <motion.div
        className="w-px"
        style={{ background: 'rgba(255,255,255,0.28)' }}
        initial={{ height: 0 }}
        animate={{ height: [0, 70, 58, 66, 66, 0] }}
        transition={{ duration: 9, times: [0, 0.25, 0.35, 0.45, 0.8, 1], repeat: Infinity, repeatDelay: 10, delay: 4, ease: 'easeInOut' }}
      />
      <svg viewBox="0 0 16 12" className="-mt-px h-[11px] w-[15px]">
        <g stroke={INK} strokeWidth="1" fill="none">
          <path d="M6 5 L1 2 M6 6 L0 6 M6 7 L1 10 M10 5 L15 2 M10 6 L16 6 M10 7 L15 10" />
        </g>
        <ellipse cx="8" cy="6" rx="3" ry="3.4" fill={INK} />
        <circle cx="7" cy="5" r="0.7" fill="#ff5a3a" />
        <circle cx="9" cy="5" r="0.7" fill="#ff5a3a" />
      </svg>
    </motion.div>
  )
}

/** Un petit fantôme sort de derrière la grosse citrouille, ondule et s'en va. */
function Ghost() {
  return (
    <motion.div
      className="absolute bottom-[clamp(10px,3vh,26px)] left-[6%] h-[clamp(18px,4.4vh,32px)]"
      initial={{ opacity: 0 }}
      animate={{ opacity: [0, 0.85, 0.85, 0], y: [10, -30, -70, -110], x: [0, 14, -8, 18] }}
      transition={{ duration: 5, times: [0, 0.2, 0.7, 1], repeat: Infinity, repeatDelay: 12, delay: 7, ease: 'easeOut' }}
    >
      <svg viewBox="0 0 10 11" shapeRendering="crispEdges" className="h-full w-auto drop-shadow-[0_0_8px_rgba(220,220,255,0.55)]">
        <g fill="#eeeef6">
          <rect x="2" y="0" width="6" height="1" />
          <rect x="1" y="1" width="8" height="1" />
          <rect x="0" y="2" width="10" height="7" />
          <rect x="0" y="9" width="2" height="2" />
          <rect x="4" y="9" width="2" height="2" />
          <rect x="8" y="9" width="2" height="2" />
        </g>
        <g fill="#1a1426">
          <rect x="2" y="3" width="2" height="2" />
          <rect x="6" y="3" width="2" height="2" />
          <rect x="4" y="6" width="2" height="2" />
        </g>
      </svg>
    </motion.div>
  )
}

/** Deux nappes de brume qui glissent en sens contraire au ras du sol. */
function Fog() {
  return (
    <>
      <motion.div
        className="absolute bottom-0 h-[30%] w-[160%] opacity-60"
        style={{ background: 'radial-gradient(40% 60% at 25% 100%, rgba(170,150,200,0.22) 0%, transparent 70%), radial-gradient(35% 50% at 70% 100%, rgba(170,150,200,0.18) 0%, transparent 70%)' }}
        initial={{ x: '-30%' }}
        animate={{ x: ['-30%', '0%', '-30%'] }}
        transition={{ duration: 26, repeat: Infinity, ease: 'easeInOut' }}
      />
      <motion.div
        className="absolute bottom-0 h-[22%] w-[160%] opacity-50"
        style={{ background: 'radial-gradient(30% 60% at 45% 100%, rgba(200,180,230,0.2) 0%, transparent 70%)' }}
        initial={{ x: '0%' }}
        animate={{ x: ['0%', '-35%', '0%'] }}
        transition={{ duration: 19, repeat: Infinity, ease: 'easeInOut' }}
      />
    </>
  )
}

// ── Bouton de lancement ─────────────────────────────────────────────────────

let launchSound: HTMLAudioElement | null = null

/**
 * Le son du clic sur « Lancer ». Un seul élément audio, créé au premier
 * appel puis rembobiné : en recréer un à chaque clic relirait le fichier.
 * Un refus de lecture (format non pris en charge, sortie audio absente) ne
 * doit jamais empêcher le lancement : il est avalé, avec une trace.
 */
export function playHalloweenLaunchSound() {
  try {
    if (!launchSound) {
      launchSound = new Audio(launchSoundUrl)
      launchSound.volume = 0.8
    }
    launchSound.currentTime = 0
    launchSound.play().catch((e) => console.warn('Son de lancement Halloween :', e))
  } catch (e) {
    console.warn('Son de lancement Halloween :', e)
  }
}

/** Gouttes de bave verte qui pendent du haut du bouton : x, largeur, durée. */
const DRIPS = [
  { left: '9%', width: 5, duration: 3.4, delay: 0 },
  { left: '27%', width: 4, duration: 4.2, delay: 1.1 },
  { left: '61%', width: 6, duration: 3.8, delay: 0.5 },
  { left: '84%', width: 4, duration: 4.6, delay: 1.8 },
]

/**
 * Décor DANS le bouton (qui coupe ce qui dépasse) : bave qui coule du bord
 * haut, et une petite citrouille qui cligne à gauche du texte.
 */
export function HalloweenButtonFx() {
  return (
    <span aria-hidden className="pointer-events-none absolute inset-0 z-[5]">
      {/* Le rebord de bave, puis les gouttes qui s'étirent et retombent. */}
      <span className="absolute inset-x-0 top-0 h-[3px] bg-[#7cff4f]/70" />
      {DRIPS.map((d, i) => (
        <motion.span
          key={i}
          className="absolute top-0 rounded-b-full bg-[#7cff4f]/70"
          style={{ left: d.left, width: d.width, transformOrigin: '50% 0%' }}
          initial={{ height: 3 }}
          animate={{ height: [3, 12, 16, 3], opacity: [0.9, 0.9, 0.5, 0.9] }}
          transition={{ duration: d.duration, times: [0, 0.6, 0.75, 1], repeat: Infinity, delay: d.delay, ease: 'easeIn' }}
        />
      ))}
      <motion.svg
        viewBox="0 0 12 12"
        shapeRendering="crispEdges"
        // Centrée par `y` et non par une classe : Motion réécrit `transform`.
        className="absolute left-[clamp(8px,1.4vw,16px)] top-1/2 h-[42%] w-auto"
        initial={{ y: '-50%' }}
        animate={{ rotate: [0, -10, 8, 0], y: ['-50%', '-62%', '-50%', '-50%'] }}
        transition={{ duration: 1.2, repeat: Infinity, repeatDelay: 2.5 }}
      >
        <rect x="1" y="2" width="10" height="9" fill="#ffa53a" />
        <rect x="0" y="3" width="12" height="7" fill="#ffa53a" />
        <rect x="3" y="2" width="1" height="9" fill="#d9700f" />
        <rect x="8" y="2" width="1" height="9" fill="#d9700f" />
        <rect x="5" y="0" width="2" height="2" fill="#4a6b1f" />
        <g fill="#3b0d05">
          <rect x="2" y="4" width="2" height="2" />
          <rect x="8" y="4" width="2" height="2" />
          <rect x="2" y="8" width="8" height="1" />
          <rect x="3" y="9" width="2" height="1" />
          <rect x="7" y="9" width="2" height="1" />
        </g>
      </motion.svg>
    </span>
  )
}

/** Directions de l'envolée, en pixels depuis le centre du bouton. */
const BURST = Array.from({ length: 12 }, (_, i) => {
  const angle = (i / 12) * Math.PI * 2 + (i % 2) * 0.25
  const reach = 90 + (i % 3) * 45
  return { x: Math.cos(angle) * reach * 1.6, y: Math.sin(angle) * reach * 0.8 - 30, size: 14 + (i % 3) * 5, rotate: (i % 2 ? 1 : -1) * (20 + i * 9), delay: (i % 4) * 0.04 }
})

/**
 * Au clic : une onde orange et une nuée de chauves-souris qui s'échappe du
 * bouton. Une `key` nouvelle à chaque clic rejoue l'animation.
 *
 * ── Pourquoi un portail en position fixe ──────────────────────────────────
 * Posée dans la page, à côté du bouton, l'envolée vivait dans la colonne qui
 * défile : les chauves-souris qui partent à plusieurs centaines de pixels
 * agrandissaient sa zone de défilement pendant leur vol, et une barre de
 * défilement apparaissait d'un coup au clic. Rendue dans `body`, en `fixed`
 * à l'endroit mesuré de `anchor`, elle ne compte plus dans aucune mise en
 * page. Pas `fixed` sans portail : un ancêtre animé par Motion porte un
 * `transform`, qui fait de lui le repère d'un enfant `fixed`.
 */
export function HalloweenLaunchBurst({ anchor }: { anchor: RefObject<HTMLElement | null> }) {
  // Mesuré une fois, au montage : l'envolée dure une seconde, le bouton ne
  // bouge pas entre-temps.
  const [rect] = useState(() => anchor.current?.getBoundingClientRect() ?? null)
  if (!rect) return null
  return createPortal(
    <span
      aria-hidden
      className="pointer-events-none fixed z-50 flex items-center justify-center"
      style={{ left: rect.left, top: rect.top, width: rect.width, height: rect.height }}
    >
      <motion.span
        className="absolute inset-0 rounded-xl border-2 border-[#ff8a1f]"
        initial={{ opacity: 0.9, scale: 1 }}
        animate={{ opacity: 0, scale: 1.35 }}
        transition={{ duration: 0.7, ease: 'easeOut' }}
      />
      <motion.span
        className="absolute inset-0 rounded-xl bg-[#ff8a1f]"
        initial={{ opacity: 0.55 }}
        animate={{ opacity: 0 }}
        transition={{ duration: 0.45 }}
      />
      {BURST.map((b, i) => (
        <motion.svg
          key={i}
          viewBox="0 0 32 14"
          width={b.size}
          height={(b.size * 14) / 32}
          className="absolute"
          initial={{ x: 0, y: 0, opacity: 0, scale: 0.4, rotate: 0 }}
          animate={{ x: b.x, y: b.y, opacity: [0, 1, 1, 0], scale: [0.4, 1, 1, 0.8], rotate: b.rotate }}
          transition={{ duration: 1.1, delay: b.delay, ease: 'easeOut' }}
        >
          <path d={BAT_PATH} fill={INK} stroke="rgba(255,150,80,0.6)" strokeWidth="1" />
        </motion.svg>
      ))}
    </span>,
    document.body,
  )
}
