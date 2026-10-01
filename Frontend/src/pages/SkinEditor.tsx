import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AnimatePresence } from 'framer-motion'
import { save as savePicker } from '@tauri-apps/plugin-dialog'
import { MOUSE, Raycaster, Vector2 } from 'three'
import type { BufferGeometry, Intersection, Mesh, Object3D, Texture } from 'three'
import { SkinViewer } from 'skinview3d'
import { api } from '@/api/client'
import type { SkinVariant } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { ModalShell } from '@/components/ui/ModalShell'
import { SkinFace } from '@/components/ui/SkinFace'
import { bakeSkin } from '@/lib/skinBake'
import { showError } from '@/stores/useErrorToast'
import { skinPreview } from '@/lib/skinCache'
import { putSkinDraft } from '@/lib/skinDraft'
import {
  FULL_RECT,
  REGIONS,
  SKIN_SIZE,
  brushRect,
  drawMannequin,
  floodFill,
  hexToRgba,
  linePixels,
  pixelAt,
  regionAt,
  rgbaToHex,
  uvToPixel,
} from '@/lib/skinEditor'
import type { PartId, Pixel, Rect } from '@/lib/skinEditor'
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

/** Sur quoi on travaille : le personnage, ou le fichier à plat. */
type Mode = '3d' | '2d'

/** Où peindre, une fois le pointeur résolu — par un rayon en 3D, par une
 *  simple règle de trois en 2D. Le reste du traitement est commun. */
interface Spot {
  pixel: Pixel
  /** Bornes du pinceau : la face touchée en 3D, l'atlas entier en 2D. */
  face: Rect
  part: PartId | null
}

/** Ce qui a produit une étape — affiché tel quel dans l'historique. */
type StepLabel = 'start' | 'pencil' | 'eraser' | 'bucket' | 'blank' | 'account'

interface HistoryEntry {
  /** Identifiant stable, qui ne bouge pas quand l'historique est tronqué —
   *  c'est lui qui sert de clé au cache des rendus cuits. */
  id: number
  label: StepLabel
  /** Le skin à cette étape, pour la vignette et le rendu. */
  thumb: string
}
const PART_IDS: PartId[] = ['head', 'body', 'rightArm', 'leftArm', 'rightLeg', 'leftLeg']

const TOOLS: { id: Tool; shortcut: string }[] = [
  { id: 'pencil', shortcut: 'B' },
  { id: 'eraser', shortcut: 'E' },
  { id: 'picker', shortcut: 'I' },
  { id: 'bucket', shortcut: 'G' },
]

/**
 * Bornes du pinceau.
 *
 * Large exprès : en 3D le pinceau est de toute façon borné à la face touchée,
 * donc une grande valeur revient à « remplir cette face d'un clic » ; en 2D,
 * où il n'est borné que par l'atlas, elle couvre une zone entière. Un curseur
 * au pixel près plutôt que quatre tailles figées, pour que les deux usages
 * soient atteignables.
 */
const MIN_BRUSH = 1
const MAX_BRUSH = 32

/**
 * Fond en damier, pour les deux vues.
 *
 * Un fond uni sombre fait disparaître les skins noirs, et pire : il rend un
 * pixel noir et un pixel **transparent** rigoureusement identiques, alors que
 * c'est toute la différence entre le corps et la surcouche. Deux gris proches
 * en damier règlent les deux — ce n'est pas tant leur clarté qui détache la
 * silhouette que le motif, qu'aucun skin ne reproduit.
 *
 * Le dégradé conique est la façon la plus courte d'écrire un damier : ses
 * quatre quarts forment un carrelage de 2×2, donc une case vaut la moitié de
 * `tile`.
 */
const CHECKER_LIGHT = '#34343c'
const CHECKER_DARK = '#26262c'

function checkerStyle(tile: number) {
  return {
    backgroundImage: `conic-gradient(${CHECKER_LIGHT} 0 25%, ${CHECKER_DARK} 0 50%, ${CHECKER_LIGHT} 0 75%, ${CHECKER_DARK} 0)`,
    backgroundSize: `${tile}px ${tile}px`,
  }
}

/** Une case = 4 pixels de texture : assez fin pour servir de repère, assez
 *  gros pour ne pas grésiller quand la vue est petite. */
const checkerForTexture = (displaySize: number) => checkerStyle((displaySize / SKIN_SIZE) * 8)

/** Palette de départ : des teintes qui servent vraiment à faire un
 *  personnage — peaux, cheveux, vêtements — plutôt qu'un nuancier. */
const PALETTE = [
  '#000000', '#3f3f46', '#71717a', '#d4d4d8', '#ffffff', '#f2c6a0', '#c68642', '#8d5524',
  '#b91c1c', '#ea580c', '#eab308', '#16a34a', '#0ea5e9', '#4b3fcf', '#a855f7', '#ec4899',
]

/** Au-delà, l'historique coûterait plus de mémoire qu'il ne rend service. Une
 *  étape pèse 16 Ko (64×64 en RGBA) plus sa vignette, donc 40 étapes tiennent
 *  largement sous le mégaoctet. Les plus anciennes sont oubliées en premier. */
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
  const [params, setParams] = useSearchParams()
  const account = params.get('account')

  const [tool, setTool] = useState<Tool>('pencil')
  const [color, setColor] = useState('#4b3fcf')
  const [brush, setBrush] = useState(1)
  const [layer, setLayer] = useState<Layer>('inner')
  /** Les deux couches se masquent indépendamment : travailler la surcouche
   *  demande souvent d'ôter le corps, exactement comme l'inverse. */
  const [showInner, setShowInner] = useState(true)
  const [showOuter, setShowOuter] = useState(true)
  const [variant, setVariant] = useState<SkinVariant>('classic')

  const [ready, setReady] = useState(false)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [at, setAt] = useState(0)
  const [hover, setHover] = useState<{ part: PartId | null; pixel: Pixel } | null>(null)
  const [showSteps, setShowSteps] = useState(false)
  const [showPoses, setShowPoses] = useState(false)
  const [mode, setMode] = useState<Mode>('3d')
  /** Côté le plus large possible pour la vue 2D : elle doit rester carrée et
   *  tenir dans son cadre, quelle que soit la forme de la fenêtre. */
  const [flatSize, setFlatSize] = useState(320)

  const boxRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)
  const skinCtxRef = useRef<CanvasRenderingContext2D | null>(null)
  const textureRef = useRef<Texture | null>(null)
  const targetsRef = useRef<Target[]>([])

  /**
   * Historique linéaire : une suite d'états, et un curseur dedans.
   *
   * Deux piles (annuler / rétablir) suffisaient à reculer d'un pas, mais ne
   * permettaient pas de montrer où l'on en est ni de sauter ailleurs. Ici
   * chaque état est gardé en entier — 16 Ko pour un 64×64, c'est moins cher
   * que de rejouer des opérations — avec sa vignette et ce qui l'a produit.
   *
   * Les images vivent dans des références et seules les vignettes passent par
   * l'état React : recopier des `ImageData` à chaque rendu ne servirait à rien.
   */
  const statesRef = useRef<ImageData[]>([])
  const metasRef = useRef<HistoryEntry[]>([])
  const atRef = useRef(0)
  const nextIdRef = useRef(0)
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
  const showInnerRef = useRef(showInner)
  showInnerRef.current = showInner
  const showOuterRef = useRef(showOuter)
  showOuterRef.current = showOuter

  // ── Écriture sur la texture ───────────────────────────────────────────────

  const flatRef = useRef<HTMLCanvasElement>(null)
  const miniRef = useRef<HTMLCanvasElement>(null)
  const flatBoxRef = useRef<HTMLDivElement>(null)

  /**
   * Les deux emplacements où la 3D peut vivre, et le cadre qu'elle occupe.
   *
   * Le canevas 3D n'est **jamais déplacé dans le DOM** : il reste un enfant
   * unique de la zone de contenu, posé en absolu sur l'emplacement actif. Le
   * déplacer pour de bon — par un portail React, par exemple — le remonterait,
   * et le contexte WebGL mourrait à chaque changement de vue.
   *
   * Les coordonnées sont calculées contre la zone de contenu et corrigées du
   * défilement, pour rester justes si la fenêtre devient trop petite et que
   * l'écran se met à défiler.
   */
  const contentRef = useRef<HTMLDivElement>(null)
  const mainSlotRef = useRef<HTMLDivElement>(null)
  const miniSlotRef = useRef<HTMLDivElement>(null)
  const miniBoxRef = useRef<HTMLDivElement>(null)
  const [frame, setFrame] = useState({ left: 0, top: 0, width: 0, height: 0 })
  /** Côté de l'aperçu de la colonne. Mesuré comme la grande vue : la carte
   *  absorbe la hauteur que les autres laissent, et le carré s'y adapte. */
  const [miniSize, setMiniSize] = useState(160)

  /**
   * Un seul `commit` pour les trois surfaces.
   *
   * La texture est la source, le reste n'en est qu'une copie : la grande vue
   * 2D et la vignette de la colonne sont deux canevas 64×64 agrandis par le
   * CSS, redessinés d'un `drawImage` à chaque changement. C'est ce qui les
   * rend « en temps réel » sans rien synchroniser à la main — et comme
   * l'aperçu du pinceau vit lui aussi sur la texture, il apparaît dans les
   * trois d'un coup.
   */
  const commit = useCallback(() => {
    if (textureRef.current) textureRef.current.needsUpdate = true
    const source = skinCtxRef.current?.canvas
    if (!source) return
    for (const target of [flatRef.current, miniRef.current]) {
      const ctx = target?.getContext('2d')
      if (!ctx) continue
      ctx.clearRect(0, 0, SKIN_SIZE, SKIN_SIZE)
      ctx.drawImage(source, 0, 0)
      // Masquer une couche vaut aussi pour le fichier : sinon on la cacherait
      // sur le personnage et elle resterait là, à plat, juste à côté. Seule la
      // copie est gommée — la texture, elle, garde tout.
      for (const region of REGIONS) {
        const visible = region.layer === 'inner' ? showInnerRef.current : showOuterRef.current
        if (visible) continue
        const { x0, y0, x1, y1 } = region.rect
        ctx.clearRect(x0, y0, x1 - x0, y1 - y0)
      }
    }
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

  /**
   * Enregistre l'état courant comme une étape.
   *
   * Revenir en arrière puis dessiner coupe ce qui suivait : c'est la règle
   * habituelle, et la seule qui garde l'historique lisible — sans quoi il
   * faudrait montrer un arbre.
   */
  const pushState = useCallback(
    (label: StepLabel) => {
      const ctx = skinCtxRef.current
      if (!ctx) return
      // Toujours avant de lire : l'historique ne doit pas retenir une teinte
      // d'aperçu, qui reviendrait comme un vrai coup de pinceau.
      clearPreview()

      const states = statesRef.current.slice(0, atRef.current + 1)
      const metas = metasRef.current.slice(0, atRef.current + 1)
      nextIdRef.current += 1
      states.push(ctx.getImageData(0, 0, SKIN_SIZE, SKIN_SIZE))
      metas.push({ id: nextIdRef.current, label, thumb: ctx.canvas.toDataURL('image/png') })
      if (states.length > MAX_HISTORY) {
        states.shift()
        metas.shift()
      }

      statesRef.current = states
      metasRef.current = metas
      atRef.current = states.length - 1
      setHistory(metas)
      setAt(atRef.current)
    },
    [clearPreview],
  )

  /** Saut direct à une étape — c'est ce que les deux piles ne savaient pas faire. */
  const goTo = useCallback(
    (index: number) => {
      const ctx = skinCtxRef.current
      const image = statesRef.current[index]
      if (!ctx || !image || index === atRef.current) return
      clearPreview()
      ctx.putImageData(image, 0, 0)
      atRef.current = index
      setAt(index)
      commit()
      persist()
      setDirty(true)
    },
    [clearPreview, commit, persist],
  )

  const undo = useCallback(() => goTo(atRef.current - 1), [goTo])
  const redo = useCallback(() => goTo(atRef.current + 1), [goTo])

  /**
   * Ne garder que l'étape où l'on est.
   *
   * Le dessin ne bouge pas — c'est déjà celui qu'on voit — donc rien à
   * redessiner ni à réenregistrer : on ne jette que le chemin qui y mène.
   * Utile quand quarante étapes d'essais encombrent la liste et qu'on repart
   * du résultat.
   */
  const clearHistory = useCallback(() => {
    const states = statesRef.current
    const metas = metasRef.current
    const index = atRef.current
    if (states.length <= 1 || !states[index]) return

    statesRef.current = [states[index]]
    metasRef.current = [metas[index]]
    atRef.current = 0
    setHistory(metasRef.current)
    setAt(0)
  }, [])

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

  /**
   * Le traitement d'un point, une fois qu'on sait où il tombe.
   *
   * Les deux vues ne diffèrent que par la résolution du pointeur : un rayon
   * en 3D, une règle de trois en 2D. Tout le reste — outils, trait continu,
   * historique — est commun, et c'est ce qui garantit qu'une retouche faite
   * dans une vue est exactement celle qu'on aurait faite dans l'autre.
   */
  const applyTo = useCallback(
    (found: Spot, starting: boolean) => {
      const ctx = skinCtxRef.current
      if (!ctx) return

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
        // Le pot agit d'un coup : son étape se note tout de suite, sans
        // attendre un relâchement qui ne changerait plus rien.
        pushState('bucket')
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
    [commit, persist, pushState],
  )

  /** Résolution 3D : le rayon décide du pixel et de la face. */
  const applyAt = useCallback(
    (x: number, y: number, starting: boolean) => {
      const found = pick(x, y)
      if (found) applyTo(found, starting)
    },
    [applyTo, pick],
  )

  /**
   * Résolution 2D : le pointeur sur le canevas agrandi donne le pixel
   * directement. Le pinceau n'y est borné que par l'atlas — déborder d'une
   * zone sur l'autre est justement ce qu'on vient y faire.
   */
  const pickFlat = useCallback((clientX: number, clientY: number): Spot | null => {
    const canvas = flatRef.current
    if (!canvas) return null
    const bounds = canvas.getBoundingClientRect()
    if (bounds.width <= 0 || bounds.height <= 0) return null
    const x = Math.floor(((clientX - bounds.left) / bounds.width) * SKIN_SIZE)
    const y = Math.floor(((clientY - bounds.top) / bounds.height) * SKIN_SIZE)
    if (x < 0 || y < 0 || x >= SKIN_SIZE || y >= SKIN_SIZE) return null
    const pixel = { x, y }
    return { pixel, face: FULL_RECT, part: regionAt(pixel)?.part ?? null }
  }, [])

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

      // Skin rapporté du catalogue (`?load=`). Il passe devant le brouillon
      // local : on vient d'aller le chercher, c'est lui qu'on veut comme base.
      // Son modèle vient du catalogue, qui le connaît.
      const wanted = params.get('load')
      let imported: string | null = null
      if (wanted) {
        if (params.get('variant') === 'slim') setVariant('slim')
        const checked = await api.skin.checkUrl(wanted).catch((e) => {
          showError(e)
          return null
        })
        if (disposed) return
        imported = checked?.data_uri ?? null
      }

      const saved = imported ?? readSaved(account)
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
      // Premier report vers la vue 2D et la vignette : sans lui, elles
      // resteraient vides jusqu'au premier coup de pinceau.
      commit()

      // L'adresse ne reste pas dans l'URL : recharger l'écran ne doit pas
      // réimporter par-dessus le travail en cours.
      if (wanted) {
        const kept = new URLSearchParams()
        if (account) kept.set('account', account)
        setParams(kept, { replace: true })
      }
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
    if (!canvas || !ready || mode !== '3d') return

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
      const painted = strokeRef.current.pixel !== null
      strokeRef.current = { active: false, pixel: null, face: null }
      persist()
      // Une étape par trait, pas par pixel : annuler doit défaire le geste
      // qu'on vient de faire, pas son dernier point. Le pot et la pipette ne
      // passent pas par là.
      if (painted && (toolRef.current === 'pencil' || toolRef.current === 'eraser')) {
        pushState(toolRef.current)
      }
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
  }, [ready, mode, applyAt, pick, persist, pushState, clearPreview, drawPreview])

  // ── Pointeur, vue 2D ──────────────────────────────────────────────────────
  //
  // Même découpage qu'en 3D — une image par traitement, trait relié, aperçu du
  // pinceau — mais sans caméra à ménager : il n'y a rien à faire tourner ici,
  // donc le bouton gauche a le canevas pour lui seul.
  useEffect(() => {
    const canvas = flatRef.current
    if (!canvas || !ready || mode !== '2d') return

    const frame = () => {
      frameRef.current = 0
      const at = pointerRef.current
      pointerRef.current = null
      if (!at) return

      if (strokeRef.current.active) {
        const found = pickFlat(at.x, at.y)
        if (found) applyTo(found, false)
        return
      }

      const found = pickFlat(at.x, at.y)
      if (!found) {
        clearPreview()
        setHover(null)
        return
      }
      setHover({ part: found.part, pixel: found.pixel })
      const painting = toolRef.current === 'pencil' || toolRef.current === 'eraser'
      drawPreview(brushRect(found.pixel, painting ? brushRef.current : 1, found.face))
    }
    const schedule = () => {
      if (frameRef.current === 0) frameRef.current = requestAnimationFrame(frame)
    }

    const onDown = (e: PointerEvent) => {
      if (e.button !== 0) return
      e.preventDefault()
      canvas.setPointerCapture(e.pointerId)
      clearPreview()
      const found = pickFlat(e.clientX, e.clientY)
      if (!found) return
      strokeRef.current = { active: true, pixel: null, face: null }
      applyTo(found, true)
    }

    const onMove = (e: PointerEvent) => {
      pointerRef.current = { x: e.clientX, y: e.clientY }
      schedule()
    }

    const onUp = (e: PointerEvent) => {
      if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId)
      if (!strokeRef.current.active) return
      const painted = strokeRef.current.pixel !== null
      strokeRef.current = { active: false, pixel: null, face: null }
      persist()
      if (painted && (toolRef.current === 'pencil' || toolRef.current === 'eraser')) {
        pushState(toolRef.current)
      }
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
  }, [ready, mode, applyTo, pickFlat, persist, pushState, clearPreview, drawPreview])

  // Les deux aperçus restent carrés et tiennent dans leur cadre : on mesure
  // plutôt que de se fier à un rapport CSS, qui casse dès que le cadre devient
  // plus haut que large — et c'est exactement ce qui arrive sur une fenêtre
  // étroite.
  useEffect(() => {
    const box = flatBoxRef.current
    if (!box || mode !== '2d') return
    const measure = () => {
      const { width, height } = box.getBoundingClientRect()
      if (width > 0 && height > 0) setFlatSize(Math.max(SKIN_SIZE, Math.floor(Math.min(width, height))))
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(box)
    return () => ro.disconnect()
  }, [mode])

  useEffect(() => {
    const box = miniBoxRef.current
    if (!box) return
    const measure = () => {
      const { width, height } = box.getBoundingClientRect()
      if (width > 0 && height > 0) setMiniSize(Math.max(48, Math.floor(Math.min(width, height))))
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(box)
    return () => ro.disconnect()
  }, [])

  // La 3D suit l'emplacement actif : le grand cadre quand on travaille
  // dessus, la carte de la colonne quand on travaille à plat.
  useEffect(() => {
    const content = contentRef.current
    const slot = mode === '3d' ? mainSlotRef.current : miniSlotRef.current
    if (!content || !slot) return

    const measure = () => {
      const outer = content.getBoundingClientRect()
      const inner = slot.getBoundingClientRect()
      setFrame({
        left: inner.left - outer.left + content.scrollLeft,
        top: inner.top - outer.top + content.scrollTop,
        width: inner.width,
        height: inner.height,
      })
    }
    measure()

    const ro = new ResizeObserver(measure)
    ro.observe(slot)
    ro.observe(content)
    content.addEventListener('scroll', measure)
    return () => {
      ro.disconnect()
      content.removeEventListener('scroll', measure)
    }
  }, [mode, ready])

  // Passer d'une vue à l'autre ne doit pas laisser une teinte d'aperçu ni un
  // trait à moitié commencé derrière soi.
  useEffect(() => {
    clearPreview()
    setHover(null)
    strokeRef.current = { active: false, pixel: null, face: null }
  }, [mode, clearPreview])

  // ── Réglages du modèle ────────────────────────────────────────────────────

  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer && ready) viewer.playerObject.skin.modelType = variant === 'slim' ? 'slim' : 'default'
  }, [variant, ready])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer || !ready) return
    viewer.playerObject.skin.setInnerLayerVisible(showInner)
    viewer.playerObject.skin.setOuterLayerVisible(showOuter)
    // La copie à plat suit la même règle que le personnage.
    commit()
  }, [showInner, showOuter, ready, commit])

  // Choisir une couche la rend forcément visible : peindre sur ce qu'on a
  // caché n'aurait aucun sens.
  useEffect(() => {
    if (layer === 'outer') setShowOuter(true)
    else setShowInner(true)
  }, [layer])

  // L'état de départ est une étape comme les autres : sans elle, on ne
  // pourrait pas revenir avant son premier trait.
  useEffect(() => {
    if (ready && statesRef.current.length === 0) pushState('start')
  }, [ready, pushState])

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
      // La modale des étapes a ses propres touches : lui laisser la main
      // évite qu'une flèche change d'étape ET de taille de pinceau.
      if (showSteps) return
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
      if (e.key === '[') setBrush((size) => Math.max(MIN_BRUSH, size - 1))
      if (e.key === ']') setBrush((size) => Math.min(MAX_BRUSH, size + 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [undo, redo, showSteps])

  // ── Actions ───────────────────────────────────────────────────────────────

  const reset = useCallback(
    async (from: 'blank' | 'account') => {
      const ctx = skinCtxRef.current
      const viewer = viewerRef.current
      if (!ctx || !viewer) return
      clearPreview()

      if (from === 'account' && account) {
        const current = await skinPreview(account).catch(() => null)
        if (current) {
          await viewer.loadSkin(current).catch(() => {})
          skinCtxRef.current = viewer.skinCanvas.getContext('2d', { willReadFrequently: true })
          textureRef.current = viewer.playerObject.skin.map
          commit()
          persist()
          setDirty(true)
          pushState('account')
          return
        }
      }
      drawMannequin(ctx)
      commit()
      persist()
      setDirty(true)
      pushState('blank')
    },
    [account, clearPreview, commit, persist, pushState],
  )

  /** Le PNG sur le disque, à l'emplacement que l'utilisateur choisit. */
  const download = useCallback(async () => {
    const image = serialise()
    if (!image) return
    try {
      const path = await savePicker({
        defaultPath: 'skin.png',
        filters: [{ name: 'PNG', extensions: ['png'] }],
      })
      if (!path) return
      await api.skin.exportPng(image, path)
    } catch (e) {
      showError(e)
    }
  }, [serialise])

  /** Le catalogue, avec le retour marqué : « essayer » y rapportera le skin
   *  ici comme base de dessin, et non sur l'écran Skins pour le porter. */
  const openCatalog = useCallback(() => {
    const query = new URLSearchParams({ to: 'editor' })
    if (account) query.set('account', account)
    navigate(`/skins/catalog?${query.toString()}`)
  }, [account, navigate])

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

      {/* Rien ne défile : l'écran tient toujours dans la fenêtre, et ce sont
          les deux aperçus qui absorbent les écarts de taille. */}
      <div ref={contentRef} className="relative min-h-0 flex-1 overflow-hidden px-4 py-4 xl:px-7 xl:py-5">
        {/* Toujours trois colonnes, même étroites : les empiler sur une
            fenêtre réduite demanderait de défiler, ce qu'on refuse ici. Leur
            largeur suit la fenêtre, et les cartes s'y plient. */}
        {/* Plafond haut : les colonnes sont fixes, donc tout ce qu'on relâche
            va au milieu — et le milieu est la seule chose qui gagne à être
            grande. 1180 px laissait un tiers de l'écran en marges sur un
            1920. La vue 2D reste bornée par la hauteur, donc rien ne s'emballe
            sur un écran très large. */}
        <div className="mx-auto grid h-full w-full max-w-[1700px] grid-cols-[164px_1fr_164px] gap-3 md:grid-cols-[196px_1fr_196px] xl:grid-cols-[248px_1fr_248px] xl:gap-4 2xl:grid-cols-[280px_1fr_280px]">
          {/* `overflow-y-auto` en dernier recours : à toute taille raisonnable
              rien ne défile, mais sur une fenêtre extrême mieux vaut atteindre
              une commande en défilant que la voir chevaucher sa voisine. */}
          <div className="flex min-h-0 flex-col gap-3 overflow-y-auto">
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

              {/* La taille tenait dans sa propre carte, pour quatre chiffres :
                  elle coûtait un en-tête et deux rembourrages de plus que ce
                  qu'elle montrait. Elle revient ici, sous les outils auxquels
                  elle s'applique, et en curseur — toute la plage au pixel
                  près, dans moins de place qu'avant. */}
              <div className="mt-2.5 flex items-center gap-2">
                <input
                  type="range"
                  min={MIN_BRUSH}
                  max={MAX_BRUSH}
                  step={1}
                  value={brush}
                  title={t('skinEditor.brush')}
                  aria-label={t('skinEditor.brush')}
                  onChange={(e) => setBrush(Number(e.target.value))}
                  className="h-1 min-w-0 flex-1 cursor-pointer appearance-none rounded-full bg-[rgba(255,255,255,0.1)] accent-[#4B3FCF]"
                />
                <span className="w-5 shrink-0 text-right font-mono text-[11px] tabular-nums text-txt-muted">
                  {brush}
                </span>
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

            {/* La carte montre toujours la vue sur laquelle on ne travaille
                PAS, et cliquer échange les deux. Le canevas 2D reste monté et
                seulement masqué quand la 3D vient s'y poser : le démonter
                ferait perdre sa taille à l'emplacement, donc son cadre.

                Le carré ne peut pas dépasser la largeur de la colonne : au-delà
                la carte s'étirerait sans rien montrer de plus. Le plafond suit
                donc les paliers de largeur, et la place en trop reste sous la
                carte plutôt que dedans. */}
            <button
              onClick={() => setMode(mode === '3d' ? '2d' : '3d')}
              className={`flex min-h-[132px] max-h-[190px] flex-1 flex-col rounded-2xl border p-2.5 text-left transition-colors md:max-h-[222px] xl:max-h-[274px] xl:p-3 2xl:max-h-[306px] ${
                mode === '2d'
                  ? 'border-accent/50 bg-accent/10'
                  : 'border-line bg-surface-1 hover:border-accent/40 hover:bg-surface-2'
              }`}
            >
              <div className="mb-2 flex shrink-0 items-baseline justify-between gap-1">
                <p className="truncate text-[10.5px] font-semibold uppercase tracking-wide text-txt-muted">
                  {mode === '2d' ? t('skinEditor.modelTitle') : t('skinEditor.flatTitle')}
                </p>
                <p className="shrink-0 text-[10px] text-accent-text">
                  {mode === '2d' ? t('skinEditor.backTo3d') : t('skinEditor.editFlat')}
                </p>
              </div>
              {/* La carte absorbe la hauteur que les autres laissent, et le
                  carré se mesure dedans : sur une fenêtre basse il rétrécit au
                  lieu de pousser le reste hors de l'écran. */}
              <div ref={miniBoxRef} className="flex min-h-0 flex-1 items-center justify-center">
                <div
                  ref={miniSlotRef}
                  style={{ width: miniSize, height: miniSize }}
                  className="relative overflow-hidden rounded-lg border border-line"
                >
                  <div className="absolute inset-0" style={checkerForTexture(miniSize)} />
                  {mode === '3d' && <RegionGrid active={layer} pass="under" />}
                  <canvas
                    ref={miniRef}
                    width={SKIN_SIZE}
                    height={SKIN_SIZE}
                    style={{ imageRendering: 'pixelated' }}
                    className={`absolute inset-0 h-full w-full ${mode === '2d' ? 'invisible' : ''}`}
                  />
                  {mode === '3d' && <RegionGrid active={layer} pass="over" />}
                </div>
              </div>
            </button>
          </div>

          <div className="flex min-h-0 flex-col gap-2">
            <div
              ref={mainSlotRef}
              className="relative min-h-0 flex-1 rounded-2xl border border-line bg-surface-1"
            >
              {/* Vue 2D : le fichier lui-même, agrandi au pixel. La 3D, elle,
                  vient se poser par-dessus ce cadre depuis l'extérieur. */}
              {/* Le rembourrage est sur le cadre extérieur et la mesure sur
                  l'intérieur : `getBoundingClientRect` rend la boîte AVEC son
                  rembourrage, donc mesurer ici donnait un carré trop grand qui
                  débordait du cadre — très visible en plein écran, où la zone
                  est bien plus haute que large. */}
              <div className={mode === '2d' ? 'absolute inset-0 p-4' : 'hidden'}>
                <div ref={flatBoxRef} className="flex h-full w-full items-center justify-center">
                  {/* Quatre plans : damier, teinte de la couche active,
                      dessin, puis contours. Le damier quitte le canevas pour
                      que la teinte puisse se glisser entre les deux. */}
                  <div className="relative" style={{ width: flatSize, height: flatSize }}>
                    <div
                      className="absolute inset-0 rounded-lg"
                      style={checkerForTexture(flatSize)}
                    />
                    <RegionGrid active={layer} pass="under" />
                    <canvas
                      ref={flatRef}
                      width={SKIN_SIZE}
                      height={SKIN_SIZE}
                      style={{ imageRendering: 'pixelated' }}
                      className={`absolute inset-0 h-full w-full rounded-lg ${tool === 'picker' ? 'cursor-copy' : 'cursor-crosshair'}`}
                    />
                    <RegionGrid active={layer} pass="over" />
                  </div>
                </div>
              </div>

              {/* Le cadre 3D ayant son propre fond opaque, la pastille doit
                  vivre dans celui des deux cadres qui est devant. */}
              {mode === '2d' && <HoverChip hover={hover} />}

              {!ready && (
                <div className="absolute inset-0 flex items-center justify-center">
                  <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
                </div>
              )}
            </div>

          </div>

          <div className="flex min-h-0 flex-col gap-3 overflow-y-auto">
            <Card title={t('skinEditor.layer')}>
              <Segmented
                options={[
                  { id: 'inner', label: t('skinEditor.layerInner') },
                  { id: 'outer', label: t('skinEditor.layerOuter') },
                ]}
                value={layer}
                onChange={(next) => setLayer(next as Layer)}
              />
              {/* Visibilité, indépendante du choix de ce qu'on peint. Celle de
                  la couche active est verrouillée : la masquer reviendrait à
                  peindre à l'aveugle. */}
              <div className="mt-1.5 flex gap-1.5">
                <EyeButton
                  label={t('skinEditor.layerInner')}
                  shown={showInner}
                  disabled={layer === 'inner'}
                  onClick={() => setShowInner(!showInner)}
                />
                <EyeButton
                  label={t('skinEditor.layerOuter')}
                  shown={showOuter}
                  disabled={layer === 'outer'}
                  onClick={() => setShowOuter(!showOuter)}
                />
              </div>
            </Card>

            {/* « Base » plutôt que « Modèle » : la carte dit sur quoi on
                dessine — quelle morphologie, et à partir de quoi on repart.
                Les deux reprises étaient dans l'historique, où elles n'avaient
                rien à faire : elles ne parcourent pas le passé, elles
                redéfinissent le départ. */}
            <Card title={t('skinEditor.base')}>
              <Segmented
                options={[
                  { id: 'classic', label: t('skinEditor.modelClassic') },
                  { id: 'slim', label: t('skinEditor.modelSlim') },
                ]}
                value={variant}
                onChange={(next) => setVariant(next as SkinVariant)}
              />
              <div className="mt-1.5 grid grid-cols-2 gap-1.5">
                <SmallButton onClick={() => void reset('blank')} title={t('skinEditor.resetBlank')}>
                  {t('skinEditor.resetBlankShort')}
                </SmallButton>
                <SmallButton
                  onClick={() => void reset('account')}
                  disabled={!account}
                  title={t('skinEditor.resetAccount')}
                >
                  {t('skinEditor.resetAccountShort')}
                </SmallButton>
              </div>
            </Card>

            {/* Trois lignes, hauteur naturelle, jamais étirée.
                L'ancienne version empilait six commandes dans une carte
                élastique : sur une fenêtre moyenne elles débordaient sous le
                bouton d'enregistrement, sur une petite elles le chevauchaient,
                et sur une grande elles flottaient dans du vide. Les actions
                rares ont rejoint les écrans auxquels elles appartiennent —
                effacer l'historique est dans la modale des étapes, les
                reprises dans « Base ». */}
            <Card title={t('skinEditor.history')} counter={history.length > 0 ? `${at + 1}/${history.length}` : '—'}>
              <div className="grid grid-cols-2 gap-1.5">
                <SmallButton onClick={undo} disabled={at <= 0}>
                  {t('skinEditor.undo')}
                </SmallButton>
                <SmallButton onClick={redo} disabled={at >= history.length - 1}>
                  {t('skinEditor.redo')}
                </SmallButton>
              </div>
              <div className="mt-1.5">
                <SmallButton onClick={() => setShowSteps(true)} disabled={history.length === 0}>
                  {t('skinEditor.viewSteps')}
                </SmallButton>
              </div>
            </Card>

            <div className="mt-auto flex shrink-0 flex-col gap-1.5">
              <Button onClick={() => void save()} loading={saving} disabled={!ready} fullWidth>
                {t('skinEditor.save')}
              </Button>
              <p className="text-center text-[11px] leading-relaxed text-txt-muted">
                {dirty ? t('skinEditor.savedLocally') : t('skinEditor.saveHint')}
              </p>
            </div>
          </div>
        </div>

        {/* Le personnage, posé sur l'emplacement actif. Il est écrit ici, hors
            de la grille, pour n'être monté qu'une fois : c'est ce qui lui
            permet de changer de place sans perdre son contexte WebGL. */}
        <div
          ref={boxRef}
          onClick={() => { if (mode === '2d') setMode('3d') }}
          // Damier plus large ici : derrière un personnage, un motif fin
          // grésillerait pendant la rotation.
          style={{ ...frame, ...checkerStyle(28) }}
          className={`absolute overflow-hidden transition-[border-color] ${
            mode === '2d'
              ? 'cursor-pointer rounded-lg border border-line hover:border-accent/50'
              : 'rounded-2xl'
          }`}
        >
          <canvas
            ref={canvasRef}
            className={`h-full w-full ${
              mode === '3d' ? (tool === 'picker' ? 'cursor-copy' : 'cursor-crosshair') : ''
            }`}
          />
          {mode === '3d' && <HoverChip hover={hover} />}

          {/* Trois actions qui concernent le skin entier, pas le pinceau :
              leur place est sur la vue, pas dans les cartes d'outils. Elles
              sont hors du canevas, donc un clic ne part jamais faire tourner
              la caméra. */}
          {mode === '3d' && ready && (
            <div className="absolute right-2 top-2 flex flex-col gap-1.5">
              <ViewAction label={t('skinEditor.download')} onClick={() => void download()}>
                <path d="M12 3v12m0 0 4-4m-4 4-4-4" />
                <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
              </ViewAction>
              <ViewAction label={t('skinEditor.fromCatalog')} onClick={openCatalog}>
                <rect x="3" y="3" width="7" height="7" rx="1.5" />
                <rect x="14" y="3" width="7" height="7" rx="1.5" />
                <rect x="3" y="14" width="7" height="7" rx="1.5" />
                <rect x="14" y="14" width="7" height="7" rx="1.5" />
              </ViewAction>
              <ViewAction label={t('skins.poses')} onClick={() => setShowPoses(true)}>
                <circle cx="12" cy="5" r="2" />
                <path d="M12 8v6m0 0-3 6m3-6 3 6M7 10h10" />
              </ViewAction>
            </div>
          )}
        </div>
      </div>

      <AnimatePresence>
        {showSteps && (
          <StepsModal
            entries={history}
            at={at}
            slim={variant === 'slim'}
            onPick={(index) => {
              goTo(index)
              setShowSteps(false)
            }}
            onClear={clearHistory}
            onClose={() => setShowSteps(false)}
          />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {showPoses && <PosesModal onClose={() => setShowPoses(false)} />}
      </AnimatePresence>
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

/**
 * Ce qu'on pointe, en pastille et seulement quand on pointe quelque chose.
 *
 * La nommer aide surtout dans la vue à plat, où rien ne dit quel rectangle est
 * un bras. En 2D, les coins inutilisés de l'atlas n'appartiennent à aucune
 * partie — on le dit plutôt que de nommer au hasard.
 */
function HoverChip({ hover }: { hover: { part: PartId | null; pixel: Pixel } | null }) {
  const t = useT()
  if (!hover) return null
  return (
    <p className="pointer-events-none absolute bottom-2 left-2 rounded bg-black/55 px-2 py-0.5 font-mono text-[10.5px] tabular-nums text-txt-secondary">
      {hover.part ? t(`skinEditor.part_${hover.part}`) : t('skinEditor.partNone')} · {hover.pixel.x},
      {hover.pixel.y}
    </p>
  )
}

/**
 * Le découpage de l'atlas, posé par-dessus la vue 2D.
 *
 * Sans lui, le fichier à plat est douze rectangles indistincts : rien ne dit
 * lequel est un bras ni où finit le torse. Les zones intérieures sont en trait
 * plein, les surcouches en pointillés — la même distinction que le sélecteur
 * de couche à droite.
 *
 * En coordonnées de texture (`viewBox` 0→64), donc net à n'importe quelle
 * taille d'affichage, et `pointer-events-none` pour ne jamais intercepter un
 * coup de pinceau.
 */
/**
 * En deux passes, de part et d'autre du dessin.
 *
 * `under` teinte la couche qu'on peint. Elle passe **sous** le canevas, donc
 * elle ne colore que les pixels encore vides : poser cette teinte au-dessus
 * délavait le dessin, ce qui est exactement l'inverse du but — on veut
 * repérer la zone, pas l'altérer.
 *
 * `over` garde ce qui doit rester visible par-dessus : le voile qui estompe
 * l'autre couche (il n'estomperait rien s'il passait dessous) et tous les
 * contours.
 */
function RegionGrid({ active, pass }: { active: Layer; pass: 'under' | 'over' }) {
  return (
    <svg
      viewBox={`0 0 ${SKIN_SIZE} ${SKIN_SIZE}`}
      className="pointer-events-none absolute inset-0 h-full w-full"
      aria-hidden
    >
      {REGIONS.map((region) => {
        const mine = region.layer === active
        if (pass === 'under' && !mine) return null
        const box = {
          x: region.rect.x0,
          y: region.rect.y0,
          width: region.rect.x1 - region.rect.x0,
          height: region.rect.y1 - region.rect.y0,
        }
        const key = `${region.part}-${region.layer}`

        if (pass === 'under') {
          return <rect key={key} {...box} fill="rgba(60,50,170,0.30)" />
        }
        return (
          <rect
            key={key}
            {...box}
            fill={mine ? 'none' : 'rgba(0,0,0,0.42)'}
            stroke={mine ? 'rgba(129,140,248,0.6)' : 'rgba(255,255,255,0.16)'}
            strokeWidth={mine ? 0.4 : 0.25}
            strokeDasharray={region.layer === 'outer' ? '1 1' : undefined}
          />
        )
      })}
    </svg>
  )
}

/** `shrink-0` : seules les cartes d'aperçu et l'historique absorbent la
 *  hauteur libre, les autres gardent la leur quelle que soit la fenêtre. */
function Card({
  title,
  counter,
  children,
}: {
  title: string
  /** Petit compteur aligné à droite du titre, quand la carte en a un. */
  counter?: string
  children: React.ReactNode
}) {
  return (
    <div className="shrink-0 rounded-2xl border border-line bg-surface-1 p-2.5 xl:p-3">
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <p className="truncate text-[10.5px] font-semibold uppercase tracking-wide text-txt-muted">{title}</p>
        {counter && (
          <p className="shrink-0 font-mono text-[10.5px] tabular-nums text-txt-muted">{counter}</p>
        )}
      </div>
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
      className={`flex h-10 items-center justify-between rounded-lg border px-3 text-[12.5px] transition-colors ${
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
          className={`h-9 flex-1 border-l border-line text-[12.5px] transition-colors first:border-l-0 ${
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

/**
 * Les étapes en grand.
 *
 * Chaque étape montre un **rendu 3D du personnage entier**, cuit une fois par
 * `lib/skinBake.ts` puis gardé en cache — une tête de vingt pixels ne disait
 * pas si on avait peint une jambe. Le rendu arrive après coup, donc la tête
 * sert de premier jet le temps qu'il cuise : la grille est remplie tout de
 * suite plutôt que vide puis peuplée.
 *
 * La navigation au clavier double le clic : flèches pour parcourir, Début et
 * Fin pour les bouts, Entrée pour y aller. Survoler déplace aussi la mise en
 * avant, pour que souris et clavier ne se contredisent pas.
 */
function StepsModal({
  entries,
  at,
  slim,
  onPick,
  onClear,
  onClose,
}: {
  entries: HistoryEntry[]
  at: number
  slim: boolean
  onPick: (index: number) => void
  onClear: () => void
  onClose: () => void
}) {
  const t = useT()
  const [focused, setFocused] = useState(at)
  const [renders, setRenders] = useState<Record<number, string>>({})

  useEffect(() => {
    let cancelled = false
    entries.forEach((entry) => {
      bakeSkin(`step:${entry.id}`, entry.thumb, slim)
        .then((image) => {
          if (!cancelled) setRenders((known) => ({ ...known, [entry.id]: image }))
        })
        .catch(() => {})
    })
    return () => { cancelled = true }
  }, [entries, slim])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
        e.preventDefault()
        setFocused((index) => Math.max(0, index - 1))
      }
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
        e.preventDefault()
        setFocused((index) => Math.min(entries.length - 1, index + 1))
      }
      if (e.key === 'Home') setFocused(0)
      if (e.key === 'End') setFocused(entries.length - 1)
      if (e.key === 'Enter') {
        e.preventDefault()
        onPick(focused)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [entries.length, focused, onPick])

  return (
    <ModalShell title={t('skinEditor.stepsTitle')} onClose={onClose} maxWidth="max-w-3xl">
      <div className="flex flex-col gap-3">
        <p className="text-[12px] leading-relaxed text-txt-secondary">{t('skinEditor.stepsHint')}</p>

        <div className="grid max-h-[52vh] grid-cols-2 gap-2 overflow-y-auto pr-1 sm:grid-cols-3 md:grid-cols-5">
          {entries.map((entry, index) => (
            <StepCard
              key={entry.id}
              entry={entry}
              index={index}
              render={renders[entry.id] ?? null}
              current={index === at}
              focused={index === focused}
              ahead={index > at}
              onHover={() => setFocused(index)}
              onClick={() => onPick(index)}
            />
          ))}
        </div>

        {/* `border-line-soft` et jamais `border-line/60` : ces jetons portent
            déjà leur alpha, un suffixe d'opacité donne une couleur invalide et
            le navigateur retombe sur un trait blanc. */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          {/* Effacer l'historique se fait ici, devant la liste qu'on jette —
              pas depuis une colonne où l'on ne voit pas ce qu'on perd. */}
          <div className="flex items-center gap-3">
            <ConfirmButton
              label={t('skinEditor.clearHistory')}
              confirmLabel={t('skinEditor.clearHistoryConfirm')}
              disabled={entries.length <= 1}
              onConfirm={onClear}
            />
            <p className="font-mono text-[11.5px] tabular-nums text-txt-muted">
              {focused + 1}/{entries.length}
            </p>
          </div>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => onPick(entries.length - 1)} disabled={at === entries.length - 1}>
              {t('skinEditor.backToLatest')}
            </Button>
            <Button size="sm" onClick={() => onPick(focused)}>{t('skinEditor.goToStep')}</Button>
          </div>
        </div>
      </div>
    </ModalShell>
  )
}

function StepCard({
  entry,
  index,
  render,
  current,
  focused,
  ahead,
  onHover,
  onClick,
}: {
  entry: HistoryEntry
  index: number
  render: string | null
  current: boolean
  focused: boolean
  ahead: boolean
  onHover: () => void
  onClick: () => void
}) {
  const t = useT()
  const cardRef = useRef<HTMLButtonElement>(null)

  // La grille défile : l'étape mise en avant doit rester visible quand on la
  // parcourt aux flèches.
  useEffect(() => {
    if (focused) cardRef.current?.scrollIntoView({ block: 'nearest' })
  }, [focused])

  return (
    <button
      ref={cardRef}
      onMouseEnter={onHover}
      onClick={onClick}
      className={`flex flex-col items-center gap-1 rounded-xl border p-2 transition-colors ${
        focused ? 'border-accent/60 bg-accent/15' : 'border-line bg-surface-2 hover:border-line-strong'
      } ${ahead ? 'opacity-45' : ''}`}
    >
      <div className="flex h-[92px] w-full items-center justify-center">
        {render ? (
          <img src={render} alt="" className="h-full w-full object-contain" draggable={false} />
        ) : (
          <SkinFace dataUri={entry.thumb} size={40} className="rounded" />
        )}
      </div>
      <p className="w-full truncate text-center text-[11.5px] text-txt-secondary">
        {t(`skinEditor.step_${entry.label}`)}
      </p>
      <p className="font-mono text-[10.5px] tabular-nums text-txt-muted">
        {current ? t('skinEditor.stepCurrent') : index + 1}
      </p>
    </button>
  )
}

/**
 * `min-h` et non `h` : en colonne, `flex-1` étire le bouton sur la hauteur
 * libre ; en ligne, il le partage en largeur. Une hauteur fixe annulerait le
 * premier cas, et c'est ce qui laissait des commandes tassées sous un vide.
 */
/**
 * Bouton d'action posé sur la vue 3D. L'icône se passe en `children`, le reste
 * de la balise SVG est commun.
 *
 * Fond opaque : posés sur un damier, des boutons translucides laissaient le
 * motif transparaître derrière l'icône.
 */
function ViewAction({
  label,
  onClick,
  children,
}: {
  label: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      title={label}
      aria-label={label}
      className="flex h-10 w-10 items-center justify-center rounded-lg border border-line bg-surface-2 text-txt-secondary transition-colors hover:border-accent/50 hover:bg-surface-3 hover:text-txt-primary"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-[19px] w-[19px]">
        {children}
      </svg>
    </button>
  )
}

/**
 * Positions du personnage.
 *
 * Les mêmes que l'écran Skins, et **inertes des deux côtés** : la mécanique
 * sera posée une fois pour les deux écrans plutôt que deux fois. `skinview3d`
 * fournit déjà `IdleAnimation`, `WalkingAnimation`, `RunningAnimation` et
 * `FlyingAnimation` pour le jour où on les branche.
 */
const POSES = ['standing', 'walking', 'running', 'flying', 'sitting', 'waving'] as const

function PosesModal({ onClose }: { onClose: () => void }) {
  const t = useT()
  return (
    <ModalShell title={t('skins.poses')} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <p className="text-[12px] leading-relaxed text-txt-secondary">{t('skins.posesSoon')}</p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {POSES.map((pose) => (
            <button
              key={pose}
              disabled
              className="h-10 cursor-not-allowed rounded-lg border border-line bg-surface-2 text-[12.5px] text-txt-muted opacity-60"
            >
              {t(`skins.pose${pose.charAt(0).toUpperCase()}${pose.slice(1)}`)}
            </button>
          ))}
        </div>
      </div>
    </ModalShell>
  )
}

/** Bascule de visibilité d'une couche : l'œil dit l'état, le libellé dit de
 *  quoi on parle. */
function EyeButton({
  label,
  shown,
  disabled,
  onClick,
}: {
  label: string
  shown: boolean
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      className={`flex h-8 flex-1 items-center justify-center gap-1.5 rounded-lg border px-1.5 text-[11.5px] transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
        shown
          ? 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
          : 'border-line bg-surface-2 text-txt-muted hover:border-line-strong'
      }`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5 shrink-0">
        {shown ? (
          <>
            <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
            <circle cx="12" cy="12" r="3" />
          </>
        ) : (
          <>
            <path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.5 0 10 7 10 7a18 18 0 0 1-2.16 3.19M6.6 6.6A18 18 0 0 0 2 11s3.5 7 10 7a9 9 0 0 0 5.4-1.6" />
            <path d="m2 2 20 20" />
          </>
        )}
      </svg>
      <span className="truncate">{label}</span>
    </button>
  )
}

/**
 * Bouton qui demande confirmation sur lui-même.
 *
 * Effacer l'historique ne touche pas au dessin, mais ne se rattrape pas : il
 * faut un garde-fou. Une modale serait disproportionnée pour ça — le bouton se
 * retourne et attend un second clic, puis revient tout seul si on le laisse.
 */
function ConfirmButton({
  label,
  confirmLabel,
  disabled,
  onConfirm,
}: {
  label: string
  confirmLabel: string
  disabled?: boolean
  onConfirm: () => void
}) {
  const [asking, setAsking] = useState(false)

  useEffect(() => {
    if (!asking) return
    const timer = window.setTimeout(() => setAsking(false), 4000)
    return () => window.clearTimeout(timer)
  }, [asking])

  // Une fois désactivé — plus rien à effacer, par exemple — la question n'a
  // plus lieu d'être.
  useEffect(() => {
    if (disabled) setAsking(false)
  }, [disabled])

  return (
    <button
      onClick={() => {
        if (asking) {
          onConfirm()
          setAsking(false)
        } else {
          setAsking(true)
        }
      }}
      disabled={disabled}
      className={`h-8 shrink-0 rounded-lg border px-3 text-[12px] transition-colors disabled:cursor-not-allowed disabled:opacity-35 ${
        asking
          ? 'border-danger/50 bg-danger/15 text-danger'
          : 'border-line bg-surface-2 text-txt-secondary hover:border-line-strong hover:text-txt-primary'
      }`}
    >
      {asking ? confirmLabel : label}
    </button>
  )
}

/**
 * Hauteur fixe, largeur pleine.
 *
 * Elle a d'abord été élastique pour remplir une carte qui s'étirait : c'est
 * ce qui la faisait déborder dès que la fenêtre manquait de hauteur, les
 * boutons refusant de descendre sous leur minimum. Les cartes ayant repris
 * une taille naturelle, le bouton n'a plus à négocier — il se range en grille
 * quand il en faut deux de front.
 */
function SmallButton({
  onClick,
  disabled,
  title,
  children,
}: {
  onClick: () => void
  disabled?: boolean
  title?: string
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="h-9 w-full min-w-0 truncate rounded-lg border border-line bg-surface-2 px-2 text-[12.5px] text-txt-secondary transition-colors hover:border-line-strong hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-35"
    >
      {children}
    </button>
  )
}
