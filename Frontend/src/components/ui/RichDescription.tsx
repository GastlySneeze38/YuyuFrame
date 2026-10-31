import { useMemo } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { open as openUrl } from '@tauri-apps/plugin-shell'

/**
 * Une description venue d'ailleurs (fiche Modrinth d'un serveur…) : du
 * Markdown où le HTML est permis, comme sur leur site. On la convertit
 * (`marked`) puis on la **nettoie** (`DOMPurify`) avant de l'afficher.
 *
 * Le texte est écrit par un inconnu et la fenêtre du launcher n'a pas de
 * CSP pour rattraper une erreur : le nettoyage est donc strict.
 * - Seules quelques balises de mise en forme passent : ni script, ni iframe,
 *   ni formulaire, ni `<style>`.
 * - Aucun attribut `style` ni `class` : une mise en page étrangère pourrait
 *   se poser par-dessus l'interface.
 * - Liens et images en `https://` uniquement. Un lien s'ouvre dans le
 *   navigateur du système, jamais dans le launcher ; une image se charge sans
 *   envoyer d'adresse de provenance.
 * - Les vidéos YouTube intégrées deviennent un lien vers la vidéo.
 */

const purify = DOMPurify(window)

const ALLOWED_TAGS = [
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'br', 'hr', 'strong', 'b', 'em', 'i', 'u', 's', 'del', 'sub', 'sup',
  'small', 'mark', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'details', 'summary', 'center', 'span', 'div',
]
const ALLOWED_ATTR = ['href', 'src', 'alt', 'title', 'width', 'height', 'colspan', 'rowspan', 'align', 'open']

purify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
  if (node.tagName === 'IMG') {
    // Une image dont l'adresse a été refusée n'a plus rien à montrer.
    if (!node.getAttribute('src')) {
      node.remove()
      return
    }
    node.setAttribute('loading', 'lazy')
    node.setAttribute('referrerpolicy', 'no-referrer')
    node.setAttribute('draggable', 'false')
  }
})

/** `<iframe src=".../embed/ID">` de YouTube → un lien vers la vidéo. */
function youtubeToLinks(html: string): string {
  return html.replace(
    /<iframe[^>]*src=["']https:\/\/(?:www\.)?(?:youtube\.com|youtube-nocookie\.com)\/embed\/([\w-]{6,20})[^"']*["'][^>]*>\s*<\/iframe>/gi,
    (_, id: string) => `<p><a href="https://www.youtube.com/watch?v=${id}">▶ YouTube</a></p>`,
  )
}

export function toSafeHtml(source: string): string {
  const html = marked.parse(source, { gfm: true, breaks: false, async: false }) as string
  return purify.sanitize(youtubeToLinks(html), {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOWED_URI_REGEXP: /^https:\/\//i,
    ALLOW_DATA_ATTR: false,
  })
}

export function RichDescription({ source, className = '' }: { source: string; className?: string }) {
  const html = useMemo(() => toSafeHtml(source), [source])
  return (
    <div
      className={`rich-text ${className}`}
      // Nettoyé par `toSafeHtml` juste au-dessus.
      dangerouslySetInnerHTML={{ __html: html }}
      onClick={(e) => {
        const link = (e.target as HTMLElement).closest('a')
        if (!link) return
        // Jamais de navigation dans le launcher : le lien part au navigateur.
        e.preventDefault()
        const href = link.getAttribute('href') ?? ''
        if (href.startsWith('https://')) void openUrl(href)
      }}
    />
  )
}
