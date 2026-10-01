/**
 * Logique de l'éditeur de skin, sans three.js ni React.
 *
 * Tout ce qui touche aux pixels vit ici : la conversion depuis les
 * coordonnées de texture, le remplissage, le mannequin de départ. Le reste —
 * la scène, les contrôles, l'interface — est dans `pages/SkinEditor.tsx`.
 * C'est aussi ce qui rend cette partie vérifiable sans navigateur.
 */

/** Un skin fait 64×64. Les 64×32 d'origine sont lus mais pas édités. */
export const SKIN_SIZE = 64

/** Rectangle de texture, en pixels, bornes hautes exclues. */
export interface Rect {
  x0: number
  y0: number
  x1: number
  y1: number
}

export interface Pixel {
  x: number
  y: number
}

/**
 * Coordonnées de texture → pixel.
 *
 * `v` est posé par skinview3d comme `1 − y / hauteur`, `y` étant compté
 * **depuis le haut** de l'image (lu dans son `setUVs`, pas supposé) : on
 * l'inverse donc ici. Se tromper de sens retournerait tout l'éditeur de haut
 * en bas sans rien casser d'autre, c'est le premier endroit à regarder si la
 * peinture tombe à côté.
 */
export function uvToPixel(u: number, v: number): Pixel {
  return {
    x: clampIndex(Math.floor(u * SKIN_SIZE)),
    y: clampIndex(Math.floor((1 - v) * SKIN_SIZE)),
  }
}

function clampIndex(value: number): number {
  return Math.min(SKIN_SIZE - 1, Math.max(0, value))
}

/**
 * Carré de pinceau centré sur un pixel, **borné au rectangle de la face**.
 *
 * Sans cette borne, un pinceau de plus d'un pixel déborderait sur la zone
 * voisine de l'atlas — qui n'est pas la face voisine du cube, mais une tout
 * autre partie du corps. On peindrait le dos d'un bras en voulant élargir un
 * trait sur le torse.
 */
export function brushRect(center: Pixel, size: number, face: Rect): Rect {
  const half = Math.floor((size - 1) / 2)
  return {
    x0: Math.max(face.x0, center.x - half),
    y0: Math.max(face.y0, center.y - half),
    x1: Math.min(face.x1, center.x - half + size),
    y1: Math.min(face.y1, center.y - half + size),
  }
}

/**
 * Pixels d'un segment, en coordonnées de texture (Bresenham).
 *
 * Un pointeur rapide ne produit qu'une poignée d'événements : sans relier les
 * points, un trait vif devient des pointillés. On ne relie que **dans une même
 * face** — d'une face à l'autre, deux pixels voisins à l'écran peuvent être
 * aux deux bouts de l'atlas, et la ligne traverserait le corps entier.
 */
export function linePixels(from: Pixel, to: Pixel): Pixel[] {
  const points: Pixel[] = []
  let { x, y } = from
  const dx = Math.abs(to.x - x)
  const dy = -Math.abs(to.y - y)
  const sx = x < to.x ? 1 : -1
  const sy = y < to.y ? 1 : -1
  let error = dx + dy

  // Garde-fou : 64×64, une ligne ne peut pas dépasser quelques dizaines de
  // pixels. Si jamais elle le faisait, autant s'arrêter que geler l'écran.
  for (let step = 0; step < SKIN_SIZE * 2; step += 1) {
    points.push({ x, y })
    if (x === to.x && y === to.y) break
    const double = error * 2
    if (double >= dy) {
      error += dy
      x += sx
    }
    if (double <= dx) {
      error += dx
      y += sy
    }
  }
  return points
}

export function rectContains(rect: Rect, pixel: Pixel): boolean {
  return pixel.x >= rect.x0 && pixel.x < rect.x1 && pixel.y >= rect.y0 && pixel.y < rect.y1
}

// ── Couleurs ─────────────────────────────────────────────────────────────────

export interface Rgba {
  r: number
  g: number
  b: number
  a: number
}

export function hexToRgba(hex: string, alpha = 255): Rgba {
  const clean = hex.replace('#', '')
  return {
    r: parseInt(clean.slice(0, 2), 16),
    g: parseInt(clean.slice(2, 4), 16),
    b: parseInt(clean.slice(4, 6), 16),
    a: alpha,
  }
}

export function rgbaToHex({ r, g, b }: Rgba): string {
  const part = (value: number) => value.toString(16).padStart(2, '0')
  return `#${part(r)}${part(g)}${part(b)}`
}

function sameColor(a: Rgba, b: Rgba): boolean {
  // Deux pixels entièrement transparents sont identiques quelles que soient
  // leurs composantes : un canevas efface en `rgba(0,0,0,0)`, un PNG importé
  // peut garder n'importe quoi sous un alpha nul.
  if (a.a === 0 && b.a === 0) return true
  return a.r === b.r && a.g === b.g && a.b === b.b && a.a === b.a
}

// ── Opérations sur l'image ───────────────────────────────────────────────────

/**
 * Pot de peinture, borné à la face.
 *
 * Il travaille sur un `ImageData` déjà extrait plutôt que d'appeler le
 * contexte pixel par pixel : `getImageData` est l'opération coûteuse, autant
 * ne la faire qu'une fois. Sur 64×64 le parcours lui-même est instantané.
 */
export function floodFill(image: ImageData, start: Pixel, face: Rect, color: Rgba): boolean {
  const target = pixelAt(image, start)
  if (sameColor(target, color)) return false

  const stack: Pixel[] = [start]
  const seen = new Set<number>()
  let changed = false

  while (stack.length > 0) {
    const pixel = stack.pop() as Pixel
    if (!rectContains(face, pixel)) continue
    const key = pixel.y * SKIN_SIZE + pixel.x
    if (seen.has(key)) continue
    seen.add(key)
    if (!sameColor(pixelAt(image, pixel), target)) continue

    writePixel(image, pixel, color)
    changed = true
    stack.push({ x: pixel.x + 1, y: pixel.y })
    stack.push({ x: pixel.x - 1, y: pixel.y })
    stack.push({ x: pixel.x, y: pixel.y + 1 })
    stack.push({ x: pixel.x, y: pixel.y - 1 })
  }
  return changed
}

export function pixelAt(image: ImageData, { x, y }: Pixel): Rgba {
  const at = (y * image.width + x) * 4
  return { r: image.data[at], g: image.data[at + 1], b: image.data[at + 2], a: image.data[at + 3] }
}

function writePixel(image: ImageData, { x, y }: Pixel, color: Rgba): void {
  const at = (y * image.width + x) * 4
  image.data[at] = color.r
  image.data[at + 1] = color.g
  image.data[at + 2] = color.b
  image.data[at + 3] = color.a
}

// ── Mannequin de départ ──────────────────────────────────────────────────────

export type PartId = 'head' | 'body' | 'rightArm' | 'leftArm' | 'rightLeg' | 'leftLeg'

export interface Region {
  part: PartId
  layer: 'inner' | 'outer'
  rect: Rect
}

/**
 * Découpage de l'atlas 64×64.
 *
 * La peinture en 3D n'en a pas besoin — le rectangle de chaque face se déduit
 * de la géométrie touchée. Elle sert à deux choses que la 3D ne donne pas : le
 * mannequin de départ, et la **lecture de la vue 2D**, où rien ne dit quel
 * rectangle est un bras tant qu'on ne l'a pas dessiné par-dessus.
 *
 * Les bras fins occupent deux colonnes de moins, mais on garde la largeur
 * classique : les colonnes en trop ne sont lues par personne, et ça évite une
 * seconde table à tenir d'accord avec la première.
 */
export const REGIONS: Region[] = [
  { part: 'head', layer: 'inner', rect: { x0: 0, y0: 0, x1: 32, y1: 16 } },
  { part: 'head', layer: 'outer', rect: { x0: 32, y0: 0, x1: 64, y1: 16 } },
  { part: 'rightLeg', layer: 'inner', rect: { x0: 0, y0: 16, x1: 16, y1: 32 } },
  { part: 'body', layer: 'inner', rect: { x0: 16, y0: 16, x1: 40, y1: 32 } },
  { part: 'rightArm', layer: 'inner', rect: { x0: 40, y0: 16, x1: 56, y1: 32 } },
  { part: 'rightLeg', layer: 'outer', rect: { x0: 0, y0: 32, x1: 16, y1: 48 } },
  { part: 'body', layer: 'outer', rect: { x0: 16, y0: 32, x1: 40, y1: 48 } },
  { part: 'rightArm', layer: 'outer', rect: { x0: 40, y0: 32, x1: 56, y1: 48 } },
  { part: 'leftLeg', layer: 'outer', rect: { x0: 0, y0: 48, x1: 16, y1: 64 } },
  { part: 'leftLeg', layer: 'inner', rect: { x0: 16, y0: 48, x1: 32, y1: 64 } },
  { part: 'leftArm', layer: 'inner', rect: { x0: 32, y0: 48, x1: 48, y1: 64 } },
  { part: 'leftArm', layer: 'outer', rect: { x0: 48, y0: 48, x1: 64, y1: 64 } },
]

/** Zone sous un pixel — `null` dans les coins inutilisés de l'atlas. */
export function regionAt(pixel: Pixel): Region | null {
  return REGIONS.find((region) => rectContains(region.rect, pixel)) ?? null
}

/** L'atlas entier. En 2D, le pinceau n'est borné que par lui : franchir les
 *  zones est précisément ce qu'on vient y faire. */
export const FULL_RECT: Rect = { x0: 0, y0: 0, x1: SKIN_SIZE, y1: SKIN_SIZE }

const INNER_REGIONS: Rect[] = REGIONS.filter((region) => region.layer === 'inner').map((r) => r.rect)

/** Gris neutre : un mannequin, pas un skin qui prétendrait être fini. */
const MANNEQUIN = '#9aa0a6'

/**
 * Skin de départ quand on part de rien.
 *
 * Une texture entièrement transparente rendrait un personnage invisible, donc
 * impossible à peindre : on remplit la couche intérieure et on laisse la
 * surcouche vide, ce qui est exactement l'état d'un skin neuf.
 */
export function drawMannequin(ctx: CanvasRenderingContext2D): void {
  ctx.clearRect(0, 0, SKIN_SIZE, SKIN_SIZE)
  ctx.fillStyle = MANNEQUIN
  for (const region of INNER_REGIONS) {
    ctx.fillRect(region.x0, region.y0, region.x1 - region.x0, region.y1 - region.y0)
  }
}
