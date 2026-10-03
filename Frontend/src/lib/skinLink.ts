/**
 * Un skin dans un lien (`yuyuframe://skin/…`, `Backend/src/share_link.rs`).
 *
 * Rien n'est hébergé : ce sont les **pixels** qui voyagent. Le Rust ne fait
 * que compresser et découper (`share_link_build_bytes`) ; la mise en forme
 * est ici, parce que c'est l'éditeur qui tient les pixels — le launcher
 * n'embarque pas de décodeur PNG côté Rust.
 *
 * Un PNG compresse mal une seconde fois. On envoie donc l'image sous la forme
 * que zstd réduit le mieux :
 * - **palette** (jusqu'à 256 couleurs, le cas de presque tous les skins) : la
 *   liste des couleurs, puis un octet par pixel ;
 * - **plans** au-delà (skins ombrés au dégradé) : les quatre canaux l'un après
 *   l'autre, chaque pixel noté par son écart au pixel de gauche — comme le
 *   filtre « Sub » du PNG.
 * Les pixels entièrement transparents sont ramenés à zéro : leur couleur ne se
 * voit pas, et la garder coûterait des caractères.
 *
 * Format : `[version][drapeaux]` puis, en palette, `[n − 1][n × RGBA][4096
 * indices]` ; en plans, `[4 × 4096 octets]`. Drapeaux : bit 0 = bras fins,
 * bit 1 = plans. Toujours 64×64 : l'éditeur convertit les anciens 64×32.
 *
 * Ce qui arrive d'un lien vient de n'importe qui : `decodeSkin` n'accepte que
 * les longueurs exactes, et ne produit jamais autre chose qu'une image 64×64.
 */

import { SKIN_SIZE } from '@/lib/skinEditor'

const VERSION = 1
const FLAG_SLIM = 1
const FLAG_PLANES = 2
const PIXELS = SKIN_SIZE * SKIN_SIZE
const MAX_PALETTE = 256

export interface SharedSkin {
  image: ImageData
  slim: boolean
}

export function encodeSkin(image: ImageData, slim: boolean): Uint8Array {
  const rgba = new Uint8Array(image.data)
  for (let i = 0; i < PIXELS; i++) {
    if (rgba[i * 4 + 3] === 0) rgba.fill(0, i * 4, i * 4 + 4)
  }

  const colors = new Map<number, number>()
  const indices = new Uint8Array(PIXELS)
  const view = new DataView(rgba.buffer)
  let fits = true
  for (let i = 0; i < PIXELS; i++) {
    const color = view.getUint32(i * 4)
    let index = colors.get(color)
    if (index === undefined) {
      if (colors.size === MAX_PALETTE) {
        fits = false
        break
      }
      index = colors.size
      colors.set(color, index)
    }
    indices[i] = index
  }

  if (fits) {
    const out = new Uint8Array(3 + colors.size * 4 + PIXELS)
    out[0] = VERSION
    out[1] = slim ? FLAG_SLIM : 0
    out[2] = colors.size - 1
    const palette = new DataView(out.buffer, 3, colors.size * 4)
    for (const [color, index] of colors) palette.setUint32(index * 4, color)
    out.set(indices, 3 + colors.size * 4)
    return out
  }

  const out = new Uint8Array(2 + PIXELS * 4)
  out[0] = VERSION
  out[1] = (slim ? FLAG_SLIM : 0) | FLAG_PLANES
  for (let channel = 0; channel < 4; channel++) {
    for (let i = 0; i < PIXELS; i++) {
      const left = i % SKIN_SIZE === 0 ? 0 : rgba[(i - 1) * 4 + channel]
      out[2 + channel * PIXELS + i] = (rgba[i * 4 + channel] - left) & 0xff
    }
  }
  return out
}

/** `null` pour tout ce qui n'a pas exactement la forme attendue. */
export function decodeSkin(bytes: Uint8Array): SharedSkin | null {
  if (bytes.length < 3 || bytes[0] !== VERSION) return null
  const flags = bytes[1]
  if (flags & ~(FLAG_SLIM | FLAG_PLANES)) return null
  const rgba = new Uint8ClampedArray(PIXELS * 4)

  if (flags & FLAG_PLANES) {
    if (bytes.length !== 2 + PIXELS * 4) return null
    for (let channel = 0; channel < 4; channel++) {
      let previous = 0
      for (let i = 0; i < PIXELS; i++) {
        if (i % SKIN_SIZE === 0) previous = 0
        previous = (previous + bytes[2 + channel * PIXELS + i]) & 0xff
        rgba[i * 4 + channel] = previous
      }
    }
  } else {
    const count = bytes[2] + 1
    const start = 3 + count * 4
    if (bytes.length !== start + PIXELS) return null
    for (let i = 0; i < PIXELS; i++) {
      const index = bytes[start + i]
      if (index >= count) return null
      rgba.set(bytes.subarray(3 + index * 4, 7 + index * 4), i * 4)
    }
  }
  return { image: new ImageData(rgba, SKIN_SIZE, SKIN_SIZE), slim: (flags & FLAG_SLIM) !== 0 }
}

/** L'image d'un lien, en PNG : c'est ce que le visualiseur sait charger. */
export function skinDataUri(image: ImageData): string | null {
  const canvas = document.createElement('canvas')
  canvas.width = SKIN_SIZE
  canvas.height = SKIN_SIZE
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.putImageData(image, 0, 0)
  return canvas.toDataURL('image/png')
}

/**
 * Bras fins ou larges, quand rien ne le dit (un fichier PNG nu). La colonne
 * de droite du bras droit n'existe pas sur un modèle fin : vide alors que le
 * bras est dessiné, c'est un skin aux bras fins. Une déduction, pas une
 * certitude — le sélecteur de morphologie reste là pour corriger.
 */
export function looksSlim(image: ImageData): boolean {
  const alpha = (x: number, y: number) => image.data[(y * SKIN_SIZE + x) * 4 + 3]
  return alpha(54, 20) === 0 && alpha(55, 31) === 0 && alpha(46, 20) !== 0
}

// ── Lien cliqué ─────────────────────────────────────────────────────────────
//
// `App.tsx` reçoit le lien, l'éditeur l'utilise : entre les deux, l'éditeur
// n'est peut-être pas encore monté. Le lien attend donc ici, et les parties
// d'un long lien s'y ajoutent une à une.

let pending = ''
const listeners = new Set<(link: string) => void>()

export function pushPendingSkinLink(url: string) {
  pending = pending ? `${pending}\n${url}` : url
  listeners.forEach((listener) => listener(pending))
}

/** Ce qui attend, sans le retirer : la fenêtre ouverte continue d'accumuler. */
export function pendingSkinLink(): string {
  return pending
}

export function clearPendingSkinLink() {
  pending = ''
}

export function onPendingSkinLink(listener: (link: string) => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
