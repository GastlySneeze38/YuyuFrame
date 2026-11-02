import { useEffect, useState } from 'react'
import { api } from '@/api/client'

/**
 * Les têtes de joueurs du launcher (classement, amis, historique des duels,
 * comptes…), et pourquoi elles passent toutes par ici.
 *
 * ── Le bon skin ──────────────────────────────────────────────────────────
 * Les têtes venaient de mc-heads.net, dont le cache garde parfois un skin
 * longtemps après son changement : un joueur se voyait en Steve dans le
 * classement alors que Mojang servait déjà son vrai skin. Toute adresse
 * `https://mc-heads.net/skin/<uuid>` est donc lue chez MOJANG, par le Rust
 * (`skin_heads`) ; mc-heads ne sert plus que de repli (pseudo sans UUID,
 * compte hors ligne, Mojang injoignable).
 *
 * ── La vitesse ───────────────────────────────────────────────────────────
 * - les demandes faites au même moment partent en UN appel au Rust (un
 *   classement en affiche quinze, le panneau des amis autant) ;
 * - chaque skin n'est résolu qu'une fois par session, les appels simultanés
 *   se partagent la même promesse ;
 * - côté Rust, le lien joueur → texture tient une demi-heure et la texture
 *   est sur le disque : relancer le launcher ne retélécharge rien.
 *
 * ── Le rendu ─────────────────────────────────────────────────────────────
 * La tête est découpée une fois en une image de 8 × 8 (visage puis couche du
 * dessus), agrandie ensuite par le navigateur en pixels nets. L'ancien rendu
 * agrandissait le skin entier en fond CSS et en montrait un morceau : sur un
 * écran à 125 %, les pixels voisins du skin débordaient sur les bords de la
 * tête.
 */

const MC_HEADS_SKIN = /^https:\/\/mc-heads\.net\/skin\/([^/?#]+)$/

/** Un UUID de compte (avec ou sans tirets), sinon `null`. */
function uuidOf(id: string): string | null {
  const clean = decodeURIComponent(id).replace(/-/g, '').toLowerCase()
  return /^[0-9a-f]{32}$/.test(clean) ? clean : null
}

// ── Les skins Mojang, par lots ─────────────────────────────────────────────

const skins = new Map<string, Promise<string | null>>()
let waiting: { uuid: string; resolve: (v: string | null) => void }[] = []
let flushTimer: ReturnType<typeof setTimeout> | null = null
/** Une page de classement en compte 15 : un lot de 32 couvre largement un écran. */
const BATCH = 32

function flush() {
  flushTimer = null
  const batch = waiting
  waiting = []
  for (let i = 0; i < batch.length; i += BATCH) {
    const slice = batch.slice(i, i + BATCH)
    api.skin
      .heads(slice.map((w) => w.uuid))
      .then((result) => slice.forEach((w, j) => w.resolve(result[j] ?? null)))
      .catch(() => slice.forEach((w) => w.resolve(null)))
  }
}

/** Le skin actuel d'un joueur chez Mojang (data URI), `null` s'il n'en a pas ou si Mojang ne répond pas. */
function mojangSkin(uuid: string): Promise<string | null> {
  const known = skins.get(uuid)
  if (known) return known
  const promise = new Promise<string | null>((resolve) => {
    waiting.push({ uuid, resolve })
    // Quelques millisecondes : le temps que toute la liste ait demandé sa tête.
    if (!flushTimer) flushTimer = setTimeout(flush, 16)
  })
  skins.set(uuid, promise)
  return promise
}

// ── La découpe ─────────────────────────────────────────────────────────────

const heads = new Map<string, Promise<string | null>>()

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    // mc-heads répond avec `Access-Control-Allow-Origin: *` : sans ceci le
    // canevas serait « sali » et ne se lirait plus.
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error(`image illisible : ${src.slice(0, 60)}`))
    img.src = src
  })
}

/** Le visage (8,8) puis la couche du dessus (40,8), en 8 × 8. */
function cropHead(img: HTMLImageElement): string | null {
  // Le skin peut être servi agrandi (multiple de 64 de large) : on suit son échelle.
  const scale = Math.max(1, Math.round(img.naturalWidth / 64))
  const canvas = document.createElement('canvas')
  canvas.width = 8
  canvas.height = 8
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(img, 8 * scale, 8 * scale, 8 * scale, 8 * scale, 0, 0, 8, 8)
  ctx.drawImage(img, 40 * scale, 8 * scale, 8 * scale, 8 * scale, 0, 0, 8, 8)
  try {
    return canvas.toDataURL('image/png')
  } catch {
    return null
  }
}

async function resolveHead(src: string): Promise<string | null> {
  const match = MC_HEADS_SKIN.exec(src)
  const uuid = match ? uuidOf(match[1]) : null
  let skin = src
  if (uuid) {
    const fresh = await mojangSkin(uuid)
    if (fresh) skin = fresh
  }
  try {
    return cropHead(await loadImage(skin))
  } catch {
    return null
  }
}

/** La tête 8 × 8 d'un skin (data URI ou adresse), découpée une fois par session. */
export function headOf(src: string): Promise<string | null> {
  const known = heads.get(src)
  if (known) return known
  const promise = resolveHead(src)
  heads.set(src, promise)
  return promise
}

/** La tête d'un skin pour un composant : `null` tant qu'elle n'est pas prête. */
export function useHead(src: string): string | null {
  const [head, setHead] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    setHead(null)
    headOf(src).then((h) => alive && setHead(h))
    return () => {
      alive = false
    }
  }, [src])
  return head
}

/** L'adresse de la tête d'un joueur : par UUID quand on l'a (lue chez Mojang), sinon par pseudo. */
export function playerSkinSrc(uuid: string | null | undefined, name?: string | null): string {
  const id = uuid ? uuid.replace(/-/g, '') : name || 'MHF_Steve'
  return `https://mc-heads.net/skin/${encodeURIComponent(id)}`
}
