import type { JoinRequest } from '@/components/servers/JoinServerModal'

/** Parse `yuyuframe://join?data=<base64url>` — le payload est le même base64
 * url-safe produit côté Rust (discord.rs::build_join_url) et relayé tel quel
 * par la page de redirection LauncherAPI (join.rs). `atob` ne connaît que
 * l'alphabet base64 standard, d'où le remap avant décodage. `host` : le ticket
 * du joueur qu'on rejoint, pour l'ajouter en ami ensuite. */
export function parseJoinUrl(url: string): JoinRequest | null {
  try {
    const parsed = new URL(url)
    const data = parsed.searchParams.get('data')
    if (!data) return null
    const standard = data.replace(/-/g, '+').replace(/_/g, '/')
    const padded = standard + '='.repeat((4 - (standard.length % 4)) % 4)
    const json = JSON.parse(atob(padded))
    if (typeof json.ip !== 'string' || typeof json.mc_version !== 'string' || typeof json.loader !== 'string') {
      return null
    }
    const host = typeof json.host === 'string' && /^[A-Za-z0-9_-]{20,80}$/.test(json.host) ? json.host : undefined
    return { ip: json.ip, mcVersion: json.mc_version, loader: json.loader, host }
  } catch {
    return null
  }
}

/** `yuyuframe://invite/<invitation>` (bouton du message privé Discord) →
 *  l'identifiant de l'invitation. */
export function parseInviteUrl(url: string): string | null {
  const match = /^yuyuframe:\/\/invite\/([0-9a-fA-F-]{36})\/?$/.exec(url.trim())
  return match ? match[1] : null
}

/** `yuyuframe://friend/Ghasty-4821` (ou `#`, `%23`) → « Ghasty#4821 ». */
export function parseFriendUrl(url: string): string | null {
  const match = /^yuyuframe:\/\/friend\/(.+?)\/?$/.exec(url.trim())
  if (!match) return null
  let raw: string
  try {
    raw = decodeURIComponent(match[1])
  } catch {
    return null
  }
  const code = /^([A-Za-z0-9_.-]{3,32})[#-](\d{1,4})$/.exec(raw)
  return code ? `${code[1]}#${code[2].padStart(4, '0')}` : null
}

/** Mon lien d'ami à coller sur Discord (page-pont de LauncherAPI, `/f/{code}`). */
export function friendLinkOf(code: string): string {
  return `https://api.yuyuframe.eu/f/${code.replace('#', '-')}`
}
