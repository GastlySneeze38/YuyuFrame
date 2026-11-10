import { openUrl } from '@tauri-apps/plugin-opener'
import { inTauri } from '@/lib/platform'

/**
 * Ouvrir une adresse dans le navigateur du système (2026-11-10).
 *
 * **Tout lien vers l'extérieur passe par ici.** L'ancien `open` de
 * `@tauri-apps/plugin-shell` ne fait rien sur Android : sur téléphone, aucun
 * lien de l'appli ne s'ouvrait (site, paiement, NameMC, fiche d'un serveur…).
 * `tauri-plugin-opener` confie l'adresse au système, qui choisit le
 * navigateur, sur PC comme sur téléphone. Permission `opener:allow-open-url`
 * (`Backend/capabilities/default.json`) : http, https, mailto et tel.
 *
 * Hors de Tauri (aperçu dans le navigateur pendant le développement), un
 * nouvel onglet.
 */
export async function openExternal(url: string): Promise<void> {
  if (!inTauri) {
    window.open(url, '_blank', 'noopener,noreferrer')
    return
  }
  await openUrl(url)
}

/**
 * Les liens `<a href="https://…">` (notes de version, descriptions en
 * Markdown…) : un clic dessus essaierait d'ouvrir l'adresse dans la fenêtre
 * de l'appli, ou ne ferait rien sur Android. Un seul écouteur pour toute
 * l'appli les envoie au navigateur du système — `components/ui/Markdown.tsx`
 * reste donc identique à celui du site et du back-office.
 */
export function installExternalLinks(): void {
  document.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0) return
    const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
    if (!a) return
    const href = a.getAttribute('href') ?? ''
    if (!/^(https?:|mailto:|tel:)/i.test(href)) return
    e.preventDefault()
    void openExternal(href).catch(() => {})
  })
}
