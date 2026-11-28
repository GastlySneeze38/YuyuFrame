// Connecter un appareil depuis un autre déjà connecté : un code de 8
// caractères (LauncherAPI `account/link.rs`), affiché en texte et en QR code.
//
// Le QR code porte le lien `yuyuframe://link/<code>` : scanné dans l'écran
// d'accueil d'un téléphone, ou cliqué / collé n'importe où, il mène au même
// endroit. Le code nu se tape sur un PC.

/** Les caractères qu'un code peut contenir (ni 0, O, 1 ni I). */
const ALPHABET = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/

/** Le lien que porte le QR code. */
export function linkUrl(code: string): string {
  return `yuyuframe://link/${code}`
}

/** « ABCD2345 » → « ABCD-2345 », plus facile à recopier. */
export function formatCode(code: string): string {
  return code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code
}

/**
 * Le code contenu dans ce qu'on a tapé, collé ou scanné : le lien du QR
 * code, ou le code seul avec ou sans tiret. `null` si ça n'en est pas un.
 */
export function parseLinkCode(text: string): string | null {
  const raw = text.trim()
  const fromUrl = /yuyuframe:\/\/link\/([A-Za-z0-9-]+)/i.exec(raw)?.[1] ?? raw
  const code = fromUrl.replace(/[^A-Za-z0-9]/g, '').toUpperCase()
  return ALPHABET.test(code) ? code : null
}

/** Un lien `yuyuframe://link/…` (cliqué, ou ouvert par l'appareil photo). */
export function isLinkUrl(url: string): boolean {
  return /^yuyuframe:\/\/link\//i.test(url.trim())
}

/**
 * Lit un QR code avec la caméra du téléphone (plugin de Tauri, Android et
 * iOS seulement). La permission est demandée au premier scan. `null` si on
 * annule ou si le code lu n'est pas un code de connexion.
 */
export async function scanLinkCode(): Promise<string | null> {
  const scanner = await import('@tauri-apps/plugin-barcode-scanner')
  let perm = await scanner.checkPermissions()
  if (perm !== 'granted') perm = await scanner.requestPermissions()
  if (perm !== 'granted') throw new Error('camera')
  const result = await scanner.scan({ windowed: false, formats: [scanner.Format.QRCode] })
  return parseLinkCode(result.content)
}

/** Un lien de connexion reçu avant que l'écran d'accueil ne soit là. */
let pending: string | null = null
let lastCode: string | null = null
let lastAt = 0
const listeners = new Set<(code: string) => void>()

/** Un lien `yuyuframe://link/…` ouvert : l'écran d'accueil s'en sert. */
export function pushLinkCode(code: string) {
  // Au démarrage d'un téléphone, le même lien peut arriver deux fois (lien
  // de lancement + événement) : un code ne sert qu'une fois, le second
  // essai afficherait une erreur.
  const now = Date.now()
  if (code === lastCode && now - lastAt < 5000) return
  lastCode = code
  lastAt = now
  if (listeners.size === 0) pending = code
  listeners.forEach((l) => l(code))
}

/** L'écran d'accueil écoute ; un code arrivé avant lui est rendu aussitôt. */
export function onLinkCode(listener: (code: string) => void): () => void {
  listeners.add(listener)
  if (pending) {
    listener(pending)
    pending = null
  }
  return () => {
    listeners.delete(listener)
  }
}
