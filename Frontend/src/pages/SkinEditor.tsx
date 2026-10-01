import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { MOUSE, Raycaster, Vector2 } from 'three'
import type { BufferGeometry, Intersection, Mesh, Object3D, Texture } from 'three'
import { SkinViewer } from 'skinview3d'
import { api } from '@/api/client'
import type { SkinVariant } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError } from '@/stores/useErrorToast'
import { skinPreview } from '@/lib/skinCache'
import { putSkinDraft } from '@/lib/skinDraft'
import {
  SKIN_SIZE,
  brushRect,
  drawMannequin,
  floodFill,
  hexToRgba,
  linePixels,
  pixelAt,
  rgbaToHex,
  uvToPixel,
} from '@/lib/skinEditor'
import type { Pixel, Rect } from '@/lib/skinEditor'
import { useT } from '@/i18n'

/**
 * Éditeur de skin 3D.
 *
 * ── Comment la peinture atteint les pixels ────────────────────────────────
 * Un rayon part du pointeur à travers la caméra et touche le modèle. Chaque
 * partie du corps est un cube dont les six faces se déplient à des positions
 * fixes de la texture, donc l'intersection rend des coordonnées `uv` qui sont
 * déjà, à un facteur près, le pixel à peindre. On dessine alors sur le canevas
 * que `skinview3d` garde pour sa texture, et un `needsUpdate` suffit à faire
 * apparaître le trait en 3D. Rien n'est recréé, ni texture ni matériau.
 *
 * ── Ce qui a été pensé pour les performances ──────────────────────────────
 * - Un seul lancer de rayon par image, jamais un par événement de souris : le
 *   pointeur est noté dans une référence, et une boucle d'animation la lit.
 *   Un pointeur rapide émet bien plus d'événements que l'écran n'affiche
 *   d'images, et les traiter tous serait du travail jeté.
 * - Les traits sont reliés en espace texture (Bresenham) plutôt que
 *   sur-échantillonnés à l'écran : un trait vif reste plein sans coûter plus.
 * - L'état chaud — pointeur, dernier pixel, outil — vit dans des références,
 *   pas dans l'état React : peindre ne doit pas redessiner l'interface.
 * - `getImageData` n'est appelé qu'aux bornes d'un trait (annulation) et pour
 *   le pot de peinture, jamais pixel par pixel.
 *
 * ── Ce qui a été pensé pour la maniabilité ────────────────────────────────
 * Glisser sur le modèle peint, glisser sur le fond tourne, le clic droit
 * tourne toujours. Les contrôles sont coupés le temps d'un trait pour que la
 * caméra ne parte pas sous la main. Le pinceau est borné au rectangle de la
 * face touchée, sinon il baverait sur une autre partie du corps — deux zones
 * voisines dans l'atlas ne sont pas voisines sur le personnage.
 *
 * ── Ce qui a été pensé pour l'apprentissage ───────────────────────────────
 * Chaque outil porte son raccourci. La partie du corps survolée est nommée en
 * bas de l'écran. Les deux couches sont un choix explicite plutôt qu'une
 * devinette, parce que c'est la notion qu'il faut comprendre pour s'en sortir.
 * Et le travail en cours est gardé sur ce PC : fermer l'écran ne perd rien.
 */

type Tool = 'pencil' | 'eraser' | 'picker' | 'bucket'
type Layer = 'inner' | 'outer'
type PartId = 'head' | 'body' | 'rightArm' | 'leftArm' | 'rightLeg' | 'leftLeg'

const PART_IDS: PartId[] = ['head', 'body', 'rightArm', 'leftArm', 'rightLeg', 'leftLeg']

const TOOLS: { id: Tool; shortcut: string }[] = [
  { id: 'pencil', shortcut: 'B' },
  { id: 'eraser', shortcut: 'E' },
  { id: 'picker', shortcut: 'I' },
  { id: 'bucket', shortcut: 'G' },
]

const BRUSH_SIZES = [1, 2, 3, 4]

/** Palette de départ : des teintes qui servent vraiment à faire un
 *  personnage — peaux, cheveux, vêtements — plutôt qu'un nuancier. */
const PALETTE = [
  '#000000', '#3f3f46', '#71717a', '#d4d4d8', '#ffffff', '#f2c6a0', '#c68642', '#8d5524',
  '#b91c1c', '#ea580c', '#eab308', '#16a34a', '#0ea5e9', '#4b3fcf', '#a855f7', '#ec4899',
]

/** Au-delà, l'annulation coûterait plus de mémoire qu'elle ne rend service.
 *  Un pas pèse 16 Ko (64×64 en RGBA), donc 40 pas tiennent dans 640 Ko. */
const MAX_HISTORY = 40

/** Le travail en cours est réécrit au plus toutes les 400 ms : un trait ne
 *  doit pas déclencher une sérialisation PNG à chaque pixel. */
const SAVE_DELAY_MS = 400

const storageKey = (account: string | null) => `yuyu.skinEditor.${account ?? 'default'}`

interface Target {
  object: Object3D
  part: PartId
  layer: Layer
}

export default function SkinEditor() {
  const t = useT()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const account = params.get('account')

  const [tool, setTool] = useState<Tool>('pencil')
  const [color, setColor] = useState('#4b3fcf')
  const [brush, setBrush] = useState(1)
  const [layer, setLayer] = useState<Layer>('inner')
  const [showOuter, setShowOuter] = useState(true)
  const [variant, setVariant] = useState<SkinVariant>('classic')

  const [ready, setReady] = useState(false)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [history, setHistory] = useState({ undo: 0, redo: 0 })
  const [hover, setHover] = useState<{ part: PartId; pixel: Pixel } | null>(null)

  const boxRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)
  const skinCtxRef = useRef<CanvasRenderingContext2D | null>(null)
  const textureRef = useRef<Texture | null>(null)
  const targetsRef = useRef<Target[]>([])

  const undoRef = useRef<ImageData[]>([])
  const redoRef = useRef<ImageData[]>([])
  const strokeRef = useRef<{ active: boolean; pixel: Pixel | null; face: Rect | null }>({
    active: false,
    pixel: null,
    face: null,
  })
  const pointerRef = useRef<{ x: number; y: number } | null>(null)
  const leftDownRef = useRef(false)
  const frameRef = useRef(0)
  const saveTimerRef = useRef(0)

  // L'état chaud est doublé en références : les gestionnaires de pointeur sont
  // attachés une fois pour toutes et ne doivent pas être recréés à chaque
  // changement d'outil ou de couleur.
  const toolRef = useRef(tool)
  toolRef.current = tool
  const colorRef = useRef(color)
  colorRef.current = color
  const brushRef = useRef(brush)
  brushRef.current = brush
  const layerRef = useRef(layer)
  layerRef.current = layer

  // ── Écriture sur la texture ───────────────────────────────────────────────

  const commit = useCallback(() => {
    if (textureRef.current) textureRef.current.needsUpdate = true
  }, [])

  /**
   * Aperçu du pinceau : les pixels exacts qu'il touchera, teintés sur le
   * modèle lui-même.
   *
   * Il est dessiné **sur la texture**, parce que c'est le seul endroit d'où il
   * épouse la géométrie — un curseur posé par-dessus l'écran ne saurait pas
   * passer l'arête d'un cube. Mais il ne doit jamais entrer dans le skin : on
   * garde donc l'image propre de côté et on la remet avant toute opération qui
   * lit le canevas (annulation, enregistrement, sauvegarde locale) ou qui le
   * modifie pour de bon.
   *
   * Une seule copie par survol, pas une par image : on repart de la copie à
   * chaque déplacement au lieu d'en reprendre une.
   */
  const cleanRef = useRef<ImageData | null>(null)

  const clearPreview = useCallback(() => {
    const ctx = skinCtxRef.current
    const clean = cleanRef.current
    if (!ctx || !clean) return
    ctx.putImageData(clean, 0, 0)
    cleanRef.current = null
    commit()
  }, [commit])

  const drawPreview = useCallback(
    (rect: Rect) => {
      const ctx = skinCtxRef.current
      if (!ctx) return
      const width = rect.x1 - rect.x0
      const height = rect.y1 - rect.y0
      if (width <= 0 || height <= 0) return

      if (cleanRef.current) ctx.putImageData(cleanRef.current, 0, 0)
      else cleanRef.current = ctx.getImageData(0, 0, SKIN_SIZE, SKIN_SIZE)

      ctx.save()
      ctx.globalAlpha = 0.55
      // La gomme ne montre pas une couleur mais un retrait : un voile clair
      // dit « ici » sans laisser croire qu'on va peindre en blanc.
      ctx.fillStyle = toolRef.current === 'eraser' ? '#ffffff' : colorRef.current
      ctx.fillRect(rect.x0, rect.y0, width, height)
      ctx.restore()
      commit()
    },
    [commit],
  )

  /**
   * Le skin tel qu'il sera enregistré — donc sans l'aperçu du pinceau.
   *
   * Quand un aperçu est affiché, on sérialise la copie propre depuis un
   * canevas de côté plutôt que de retirer puis remettre la teinte : enlever
   * l'aperçu sous les yeux de l'utilisateur à chaque sauvegarde automatique
   * ferait clignoter le modèle.
   */
  const serialise = useCallback((): string | null => {
    const ctx = skinCtxRef.current
    if (!ctx) return null
    const clean = cleanRef.current
    if (!clean) return ctx.canvas.toDataURL('image/png')

    const off = document.createElement('canvas')
    off.width = SKIN_SIZE
    off.height = SKIN_SIZE
    const offCtx = off.getContext('2d')
    if (!offCtx) return null
    offCtx.putImageData(clean, 0, 0)
    return off.toDataURL('image/png')
  }, [])

  const persist = useCallback(() => {
    window.clearTimeout(saveTimerRef.current)
    saveTimerRef.current = window.setTimeout(() => {
      const image = serialise()
      if (!image) return
      try {
        window.localStorage.setItem(storageKey(account), image)
      } catch {
        // Stockage plein ou refusé : le dessin reste à l'écran, il ne survivra
        // simplement pas à la fermeture. Pas de quoi interrompre le travail.
      }
    }, SAVE_DELAY_MS)
  }, [account, serialise])

  const snapshot = useCallback(() => {
    const ctx = skinCtxRef.current
    if (!ctx) return
    // Toujours avant de lire : l'historique ne doit pas retenir une teinte
    // d'aperçu, qui reviendrait comme un vrai coup de pinceau en annulant.
    clearPreview()
    undoRef.current.push(ctx.getImageData(0, 0, SKIN_SIZE, SKIN_SIZE))
    if (undoRef.current.length > MAX_HISTORY) undoRef.current.shift()
    redoRef.current = []
    setHistory({ undo: undoRef.current.length, redo: 0 })
  }, [clearPreview])

  const step = useCallback(
    (from: ImageData[], to: ImageData[]) => {
      const ctx = skinCtxRef.current
      const previous = from.pop()
      if (!ctx || !previous) return
      clearPreview()
      to.push(ctx.getImageData(0, 0, SKIN_SIZE, SKIN_SIZE))
      ctx.putImageData(previous, 0, 0)
      commit()
      persist()
      setDirty(true)
      setHistory({ undo: undoRef.current.length, redo: redoRef.current.length })
    },
    [clearPreview, commit, persist],
  )

  const undo = useCallback(() => step(undoRef.current, redoRef.current), [step])
  const redo = useCallback(() => step(redoRef.current, undoRef.current), [step])

  // ── Lancer de rayon ───────────────────────────────────────────────────────

  /**
   * Rectangle de la face touchée, en pixels de texture.
   *
   * Il se déduit des UV des sommets du triangle plutôt que d'une table écrite
   * à la main : les trois sommets d'un demi-quadrilatère suffisent à en
   * encadrer les quatre coins, et ça reste juste quand le modèle passe en bras
   * fins, dont les faces sont plus étroites.
   */
  const faceRectOf = (hit: Intersection): Rect | null => {
    const geometry = (hit.object as Mesh).geometry as BufferGeometry
    const uv = geometry.getAttribute('uv')
    if (!uv || !hit.face) return null
    const indices = [hit.face.a, hit.face.b, hit.face.c]
    const us = indices.map((i) => uv.getX(i))
    const vs = indices.map((i) => uv.getY(i))
    return {
      x0: Math.round(Math.min(...us) * SKIN_SIZE),
      y0: Math.round((1 - Math.max(...vs)) * SKIN_SIZE),
      x1: Math.round(Math.max(...us) * SKIN_SIZE),
      y1: Math.round((1 - Math.min(...vs)) * SKIN_SIZE),
    }
  }

  const targetOf = (object: Object3D): Target | null => {
    for (let node: Object3D | null = object; node; node = node.parent) {
      const found = targetsRef.current.find((target) => target.object === node)
      if (found) return found
    }
    return null
  }

  /** Première intersection appartenant à la couche choisie. */
  const pick = useCallback((x: number, y: number) => {
    const viewer = viewerRef.current
    const canvas = canvasRef.current
    if (!viewer || !canvas) return null

    const bounds = canvas.getBoundingClientRect()
    const pointer = new Vector2(
      ((x - bounds.left) / bounds.width) * 2 - 1,
      -((y - bounds.top) / bounds.height) * 2 + 1,
    )
    const raycaster = new Raycaster()
    raycaster.setFromCamera(pointer, viewer.camera)

    const hits = raycaster.intersectObject(viewer.playerObject.skin, true)
    for (const hit of hits) {
      const target = targetOf(hit.object)
      if (!target || target.layer !== layerRef.current || !hit.uv) continue
      const face = faceRectOf(hit)
      if (!face) continue
      return { pixel: uvToPixel(hit.uv.x, hit.uv.y), face, part: target.part }
    }
    return null
  }, [])

  // ── Outils ────────────────────────────────────────────────────────────────

  const paintPixel = (ctx: CanvasRenderingContext2D, pixel: Pixel, face: Rect) => {
    const rect = brushRect(pixel, brushRef.current, face)
    const width = rect.x1 - rect.x0
    const height = rect.y1 - rect.y0
    if (width <= 0 || height <= 0) return

    if (toolRef.current === 'eraser') {
      ctx.clearRect(rect.x0, rect.y0, width, height)
      return
    }
    ctx.fillStyle = colorRef.current
    ctx.fillRect(rect.x0, rect.y0, width, height)
  }

  const applyAt = useCallback(
    (x: number, y: number, starting: boolean) => {
      const ctx = skinCtxRef.current
      const found = pick(x, y)
      if (!ctx || !found) return

      if (toolRef.current === 'picker') {
        const image = ctx.getImageData(0, 0, SKIN_SIZE, SKIN_SIZE)
        const sampled = pixelAt(image, found.pixel)
        if (sampled.a > 0) {
          setColor(rgbaToHex(sampled))
          setTool('pencil')
        }
        return
      }

      if (toolRef.current === 'bucket') {
        if (!starting) return
        const image = ctx.getImageData(0, 0, SKIN_SIZE, SKIN_SIZE)
        if (!floodFill(image, found.pixel, found.face, hexToRgba(colorRef.current))) return
        ctx.putImageData(image, 0, 0)
        commit()
        persist()
        setDirty(true)
        return
      }

      // Relier au point précédent, mais seulement à l'intérieur d'une même
      // face : d'une face à l'autre, deux pixels voisins à l'écran peuvent
      // être aux deux bouts de l'atlas.
      const previous = strokeRef.current.pixel
      const sameFace = strokeRef.current.face === found.face
      if (!starting && previous && sameFace) {
        for (const pixel of linePixels(previous, found.pixel)) paintPixel(ctx, pixel, found.face)
      } else {
        paintPixel(ctx, found.pixel, found.face)
      }

      strokeRef.current.pixel = found.pixel
      strokeRef.current.face = found.face
      commit()
      setDirty(true)
    },
    [commit, persist, pick],
  )

  // ── Mise en place ─────────────────────────────────────────────────────────

  useEffect(() => {
    const canvas = canvasRef.current
    const box = boxRef.current
    if (!canvas || !box) return
    let disposed = false

    const { width, height } = box.getBoundingClientRect()
    canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true })
      ?? canvas.getContext('webgl', { alpha: true, premultipliedAlpha: true })

    const viewer = new SkinViewer({ canvas, width: width || 520, height: height || 520 })
    viewer.background = null
    viewer.zoom = 0.78
    viewer.fov = 45
    // Ni rotation automatique ni animation : on peint sur un modèle immobile,
    // et un personnage qui bouge sous le pinceau serait inutilisable.
    viewer.autoRotate = false
    viewer.controls.enablePan = true
    // Le gauche ne sert qu'à peindre : il est neutralisé en coupant les
    // contrôles dès qu'il s'enfonce (voir `onDown`), plutôt qu'en lui donnant
    // une valeur vide — `mouseButtons` les veut toutes les trois.
    viewer.controls.mouseButtons = { LEFT: MOUSE.ROTATE, MIDDLE: MOUSE.PAN, RIGHT: MOUSE.ROTATE }
    viewerRef.current = viewer

    /**
     * Molette **et** clic droit ensemble : on déplace le centre au lieu de
     * tourner.
     *
     * `OrbitControls` ne lit qu'un bouton, celui de l'événement, et décide à
     * l'enfoncement. On règle donc la correspondance juste avant qu'il ne la
     * lise : en phase de capture sur le conteneur, donc avant son propre
     * gestionnaire posé sur le canevas. `buttons` porte déjà le bouton qui
     * vient de s'enfoncer, les deux sont donc visibles ensemble.
     */
    const onDownCapture = (e: PointerEvent) => {
      const middleAndRight = (e.buttons & 4) !== 0 && (e.buttons & 2) !== 0
      viewer.controls.mouseButtons = {
        LEFT: MOUSE.ROTATE,
        MIDDLE: MOUSE.PAN,
        RIGHT: middleAndRight ? MOUSE.PAN : MOUSE.ROTATE,
      }
    }
    box.addEventListener('pointerdown', onDownCapture, { capture: true })

    const skin = viewer.playerObject.skin
    targetsRef.current = PART_IDS.flatMap((part) => [
      { object: skin[part].innerLayer, part, layer: 'inner' as Layer },
      { object: skin[part].outerLayer, part, layer: 'outer' as Layer },
    ])

    const ro = new ResizeObserver(() => {
      const r = box.getBoundingClientRect()
      if (r.width > 0 && r.height > 0) viewer.setSize(r.width, r.height)
    })
    ro.observe(box)

    const start = async () => {
      // Le modèle du compte avant tout le reste : il décide de la disposition
      // de la texture, donc partir en classique sur un compte aux bras fins
      // ferait peindre à côté sur les deux bras.
      if (account) {
        const current = await api.skin.current(account).catch(() => null)
        if (disposed) return
        if (current?.variant === 'slim') setVariant('slim')
      }

      const saved = readSaved(account)
      const source = saved ?? (account ? await skinPreview(account).catch(() => null) : null)
      if (disposed) return

      if (source) {
        await viewer.loadSkin(source).catch(() => {})
      } else {
        // Une texture vide rendrait un personnage invisible, donc impeignable.
        const blank = document.createElement('canvas')
        blank.width = SKIN_SIZE
        blank.height = SKIN_SIZE
        const blankCtx = blank.getContext('2d')
        if (blankCtx) drawMannequin(blankCtx)
        await viewer.loadSkin(blank.toDataURL('image/png')).catch(() => {})
      }
      if (disposed) return

      skinCtxRef.current = viewer.skinCanvas.getContext('2d', { willReadFrequently: true })
      textureRef.current = skin.map
      setReady(true)
    }
    void start()

    return () => {
      disposed = true
      window.clearTimeout(saveTimerRef.current)
      cancelAnimationFrame(frameRef.current)
      box.removeEventListener('pointerdown', onDownCapture, { capture: true })
      ro.disconnect()
      viewer.dispose()
      viewerRef.current = null
      skinCtxRef.current = null
      textureRef.current = null
    }
  }, [account])

  // ── Pointeur ──────────────────────────────────────────────────────────────

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !ready) return

    // Un seul traitement par image, quel que soit le débit d'événements.
    const frame = () => {
      frameRef.current = 0
      const at = pointerRef.current
      pointerRef.current = null
      if (!at) return

      if (strokeRef.current.active) {
        applyAt(at.x, at.y, false)
        return
      }

      const found = pick(at.x, at.y)
      if (!found) {
        clearPreview()
        setHover(null)
        return
      }
      setHover({ part: found.part, pixel: found.pixel })

      // Le crayon et la gomme montrent exactement leur empreinte. La pipette
      // ne touche rien et le pot remplit une zone qu'il faudrait calculer à
      // chaque image : pour eux, le pixel visé suffit à dire où l'on est.
      const painting = toolRef.current === 'pencil' || toolRef.current === 'eraser'
      drawPreview(brushRect(found.pixel, painting ? brushRef.current : 1, found.face))
    }
    const schedule = () => {
      if (frameRef.current === 0) frameRef.current = requestAnimationFrame(frame)
    }

    const onDown = (e: PointerEvent) => {
      if (e.button !== 0) return
      // Le bouton gauche ne tourne jamais : les contrôles sont coupés dès
      // qu'il s'enfonce, y compris à côté du personnage. Tourner, c'est le
      // clic droit, et seulement lui.
      e.preventDefault()
      const viewer = viewerRef.current
      if (viewer) viewer.controls.enabled = false
      leftDownRef.current = true
      canvas.setPointerCapture(e.pointerId)
      clearPreview()

      const found = pick(e.clientX, e.clientY)
      if (!found) return
      if (toolRef.current !== 'picker') snapshot()
      strokeRef.current = { active: true, pixel: null, face: null }
      applyAt(e.clientX, e.clientY, true)
    }

    const onMove = (e: PointerEvent) => {
      pointerRef.current = { x: e.clientX, y: e.clientY }
      schedule()
    }

    const onUp = (e: PointerEvent) => {
      if (!leftDownRef.current) return
      leftDownRef.current = false
      if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId)
      const viewer = viewerRef.current
      if (viewer) viewer.controls.enabled = true
      if (!strokeRef.current.active) return
      strokeRef.current = { active: false, pixel: null, face: null }
      persist()
    }

    const onLeave = () => {
      pointerRef.current = null
      clearPreview()
      setHover(null)
    }

    canvas.addEventListener('pointerdown', onDown)
    canvas.addEventListener('pointermove', onMove)
    canvas.addEventListener('pointerup', onUp)
    canvas.addEventListener('pointercancel', onUp)
    canvas.addEventListener('pointerleave', onLeave)
    return () => {
      canvas.removeEventListener('pointerdown', onDown)
      canvas.removeEventListener('pointermove', onMove)
      canvas.removeEventListener('pointerup', onUp)
      canvas.removeEventListener('pointercancel', onUp)
      canvas.removeEventListener('pointerleave', onLeave)
      cancelAnimationFrame(frameRef.current)
      frameRef.current = 0
    }
  }, [ready, applyAt, pick, persist, snapshot, clearPreview, drawPreview])

  // ── Réglages du modèle ────────────────────────────────────────────────────

  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer && ready) viewer.playerObject.skin.modelType = variant === 'slim' ? 'slim' : 'default'
  }, [variant, ready])

  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer && ready) viewer.playerObject.skin.setOuterLayerVisible(showOuter)
  }, [showOuter, ready])

  // Choisir la surcouche la rend forcément visible : peindre sur ce qu'on a
  // caché n'aurait aucun sens.
  useEffect(() => {
    if (layer === 'outer') setShowOuter(true)
  }, [layer])

  // Changer d'outil, de couleur ou de taille sans bouger la souris laisserait
  // un aperçu qui ment sur ce qui va se passer. Il se redessine au prochain
  // mouvement.
  useEffect(() => {
    clearPreview()
  }, [tool, color, brush, layer, clearPreview])

  // ── Raccourcis ────────────────────────────────────────────────────────────

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      if (target?.tagName === 'INPUT') return
      const key = e.key.toLowerCase()

      if ((e.ctrlKey || e.metaKey) && key === 'z') {
        e.preventDefault()
        if (e.shiftKey) redo()
        else undo()
        return
      }
      if ((e.ctrlKey || e.metaKey) && key === 'y') {
        e.preventDefault()
        redo()
        return
      }
      if (e.ctrlKey || e.metaKey) return

      if (key === 'b') setTool('pencil')
      if (key === 'e') setTool('eraser')
      if (key === 'i') setTool('picker')
      if (key === 'g') setTool('bucket')
      if (e.key === '[') setBrush((size) => Math.max(BRUSH_SIZES[0], size - 1))
      if (e.key === ']') setBrush((size) => Math.min(BRUSH_SIZES[BRUSH_SIZES.length - 1], size + 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [undo, redo])

  // ── Actions ───────────────────────────────────────────────────────────────

  const reset = useCallback(
    async (from: 'blank' | 'account') => {
      const ctx = skinCtxRef.current
      const viewer = viewerRef.current
      if (!ctx || !viewer) return
      snapshot()

      if (from === 'account' && account) {
        const current = await skinPreview(account).catch(() => null)
        if (current) {
          await viewer.loadSkin(current).catch(() => {})
          skinCtxRef.current = viewer.skinCanvas.getContext('2d', { willReadFrequently: true })
          textureRef.current = viewer.playerObject.skin.map
          commit()
          persist()
          setDirty(true)
          return
        }
      }
      drawMannequin(ctx)
      commit()
      persist()
      setDirty(true)
    },
    [account, commit, persist, snapshot],
  )

  const save = useCallback(async () => {
    const image = serialise()
    if (!image || saving) return
    setSaving(true)
    try {
      const checked = await api.skin.importBytes(image, variant)
      putSkinDraft({ source: checked.source, variant, dataUri: checked.data_uri })
      const query = new URLSearchParams()
      if (account) query.set('account', account)
      query.set('tryDraft', '1')
      navigate(`/skins?${query.toString()}`)
    } catch (e) {
      showError(e)
      setSaving(false)
    }
  }, [account, navigate, saving, serialise, variant])

  const backTo = account ? `/skins?account=${encodeURIComponent(account)}` : '/skins'

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader backTo={backTo}>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('skinEditor.title')}
          </h1>
          <p className="mt-0.5 text-[11.5px] text-txt-secondary">{t('skinEditor.subtitle')}</p>
        </div>
      </PageHeader>

      <div className="min-h-0 flex-1 overflow-y-auto px-7 py-5">
        <div className="mx-auto grid h-full min-h-[480px] w-full max-w-[1180px] grid-cols-1 gap-4 lg:grid-cols-[212px_1fr_212px]">
          <div className="flex min-h-0 flex-col gap-3">
            <Card title={t('skinEditor.tools')}>
              <div className="grid grid-cols-2 gap-1.5">
                {TOOLS.map((entry) => (
                  <ToolButton
                    key={entry.id}
                    active={tool === entry.id}
                    shortcut={entry.shortcut}
                    label={t(`skinEditor.tool_${entry.id}`)}
                    onClick={() => setTool(entry.id)}
                  />
                ))}
              </div>
            </Card>

            <Card title={t('skinEditor.brush')}>
              <div className="flex gap-1.5">
                {BRUSH_SIZES.map((size) => (
                  <button
                    key={size}
                    onClick={() => setBrush(size)}
                    className={`flex h-9 flex-1 items-center justify-center rounded-lg border text-[12.5px] tabular-nums transition-colors ${
                      brush === size
                        ? 'border-accent/50 bg-accent/20 text-txt-primary'
                        : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong'
                    }`}
                  >
                    {size}
                  </button>
                ))}
              </div>
            </Card>

            <Card title={t('skinEditor.color')}>
              <div className="flex items-center gap-2">
                <input
                  type="color"
                  value={color}
                  onChange={(e) => setColor(e.target.value)}
                  className="h-9 w-12 cursor-pointer rounded-lg border border-line bg-transparent"
                />
                <span className="font-mono text-[12px] uppercase text-txt-secondary">{color}</span>
              </div>
              <div className="mt-2 grid grid-cols-8 gap-1">
                {PALETTE.map((swatch) => (
                  <button
                    key={swatch}
                    onClick={() => setColor(swatch)}
                    title={swatch}
                    style={{ backgroundColor: swatch }}
                    className={`h-5 rounded border transition-transform hover:scale-110 ${
                      color.toLowerCase() === swatch ? 'border-white' : 'border-black/40'
                    }`}
                  />
                ))}
              </div>
            </Card>
          </div>

          <div className="flex min-h-0 flex-col gap-2">
            <div className="relative min-h-[320px] flex-1 rounded-2xl border border-line bg-surface-1">
              <div ref={boxRef} className="absolute inset-0">
                <canvas
                  ref={canvasRef}
                  className={`h-full w-full ${tool === 'picker' ? 'cursor-copy' : 'cursor-crosshair'}`}
                />
              </div>
              {!ready && (
                <div className="absolute inset-0 flex items-center justify-center">
                  <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
                </div>
              )}
            </div>

            <div className="flex items-center justify-between gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2">
              <p className="text-[11.5px] leading-relaxed text-txt-muted">{t('skinEditor.hint')}</p>
              <p className="shrink-0 font-mono text-[11.5px] tabular-nums text-txt-secondary">
                {hover ? `${t(`skinEditor.part_${hover.part}`)} · ${hover.pixel.x},${hover.pixel.y}` : '—'}
              </p>
            </div>
          </div>

          <div className="flex min-h-0 flex-col gap-3">
            <Card title={t('skinEditor.layer')}>
              <Segmented
                options={[
                  { id: 'inner', label: t('skinEditor.layerInner') },
                  { id: 'outer', label: t('skinEditor.layerOuter') },
                ]}
                value={layer}
                onChange={(next) => setLayer(next as Layer)}
              />
              <p className="mt-1.5 text-[11px] leading-relaxed text-txt-muted">
                {t('skinEditor.layerHint')}
              </p>
              <button
                onClick={() => setShowOuter(!showOuter)}
                disabled={layer === 'outer'}
                className="mt-2 w-full rounded-lg border border-line bg-surface-2 px-3 py-1.5 text-[12px] text-txt-secondary transition-colors hover:border-line-strong hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40"
              >
                {showOuter ? t('skinEditor.hideOverlay') : t('skinEditor.showOverlay')}
              </button>
            </Card>

            <Card title={t('skinEditor.model')}>
              <Segmented
                options={[
                  { id: 'classic', label: t('skinEditor.modelClassic') },
                  { id: 'slim', label: t('skinEditor.modelSlim') },
                ]}
                value={variant}
                onChange={(next) => setVariant(next as SkinVariant)}
              />
            </Card>

            <Card title={t('skinEditor.history')}>
              <div className="flex gap-1.5">
                <SmallButton onClick={undo} disabled={history.undo === 0}>
                  {t('skinEditor.undo')}
                </SmallButton>
                <SmallButton onClick={redo} disabled={history.redo === 0}>
                  {t('skinEditor.redo')}
                </SmallButton>
              </div>
              <div className="mt-1.5 flex gap-1.5">
                <SmallButton onClick={() => void reset('blank')}>{t('skinEditor.resetBlank')}</SmallButton>
                <SmallButton onClick={() => void reset('account')} disabled={!account}>
                  {t('skinEditor.resetAccount')}
                </SmallButton>
              </div>
            </Card>

            <div className="mt-auto flex flex-col gap-1.5">
              <Button onClick={() => void save()} loading={saving} disabled={!ready} fullWidth>
                {t('skinEditor.save')}
              </Button>
              <p className="text-center text-[11px] leading-relaxed text-txt-muted">
                {dirty ? t('skinEditor.savedLocally') : t('skinEditor.saveHint')}
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

/** Dessin en cours gardé sur ce PC — fermer l'écran ne doit rien perdre. */
function readSaved(account: string | null): string | null {
  try {
    return window.localStorage.getItem(storageKey(account))
  } catch {
    return null
  }
}

// ── Briques d'interface ──────────────────────────────────────────────────────

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-2xl border border-line bg-surface-1 p-3">
      <p className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-txt-muted">{title}</p>
      {children}
    </div>
  )
}

function ToolButton({
  active,
  label,
  shortcut,
  onClick,
}: {
  active: boolean
  label: string
  shortcut: string
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={`flex h-9 items-center justify-between rounded-lg border px-2.5 text-[12px] transition-colors ${
        active
          ? 'border-accent/50 bg-accent/20 text-txt-primary'
          : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
      }`}
    >
      {label}
      {/* Le raccourci est écrit sur le bouton : c'est là qu'on le cherche, pas
          dans une aide séparée qu'on n'ouvrira jamais. */}
      <span className="ml-1 rounded bg-black/40 px-1 font-mono text-[10px] text-txt-muted">{shortcut}</span>
    </button>
  )
}

function Segmented({
  options,
  value,
  onChange,
}: {
  options: { id: string; label: string }[]
  value: string
  onChange: (id: string) => void
}) {
  return (
    <div className="flex overflow-hidden rounded-lg border border-line">
      {options.map((option) => (
        <button
          key={option.id}
          onClick={() => onChange(option.id)}
          className={`h-8 flex-1 border-l border-line text-[12px] transition-colors first:border-l-0 ${
            value === option.id
              ? 'bg-accent/20 text-txt-primary'
              : 'bg-surface-2 text-txt-secondary hover:bg-surface-3'
          }`}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function SmallButton({
  onClick,
  disabled,
  children,
}: {
  onClick: () => void
  disabled?: boolean
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="h-8 flex-1 rounded-lg border border-line bg-surface-2 text-[12px] text-txt-secondary transition-colors hover:border-line-strong hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-35"
    >
      {children}
    </button>
  )
}
