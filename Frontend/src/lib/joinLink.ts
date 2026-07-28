import type { JoinRequest } from '@/components/servers/JoinServerModal'

/** Parse `yuyuframe://join?data=<base64url>` — le payload est le même base64
 * url-safe produit côté Rust (discord.rs::build_join_url) et relayé tel quel
 * par la page de redirection LauncherAPI (join.rs) : mêmes caractères des
 * deux côtés, jamais besoin d'échapper quoi que ce soit. `atob` ne connaît
 * que l'alphabet base64 standard, d'où le remap avant décodage. */
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
    return { ip: json.ip, mcVersion: json.mc_version, loader: json.loader }
  } catch {
    return null
  }
}
