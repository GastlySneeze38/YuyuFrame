import type { ReactNode } from 'react'

// Markdown « à la Discord » : le sous-ensemble que Discord affiche, rendu en
// éléments React (jamais de HTML brut : un contenu ne peut pas injecter de
// script, et seuls les liens http(s) deviennent cliquables).
//
// ⚠ Copie identique dans Server/Website/Frontend/src/components/Markdown.tsx
// et Server/Admin/Frontend/src/components/Markdown.tsx : une patch note doit
// s'afficher pareil dans l'aperçu du back-office, sur le site et dans la
// modale du launcher. Modifier les trois ensemble.
//
// Seule différence ici : le thème (voir `LAUNCHER_THEME` en bas), un peu plus
// grand et plus contrasté que celui du site — on lit une modale une fois, au
// démarrage, souvent en diagonale.
//
// Blocs : # / ## / ### titres, -# petit texte, listes - ou *, citations >,
// blocs de code ```, paragraphes (retours à la ligne conservés).
// En ligne : **gras**, *italique* ou _italique_, __souligné__, ~~barré~~,
// `code`, [texte](https://…), liens nus https://…

export type MarkdownTheme = {
  h1: string
  h2: string
  h3: string
  subtext: string
  p: string
  ul: string
  li: string
  quote: string
  pre: string
  code: string
  link: string
  /** Hauteur d'une ligne vide (= une ligne de texte). */
  blank: string
}

const INLINE =
  /(\*\*[^*\n]+?\*\*|__[^_\n]+?__|~~[^~\n]+?~~|`[^`\n]+`|\*[^*\s][^*\n]*?\*|_[^_\s][^_\n]*?_|\[[^\]\n]+\]\(https?:\/\/[^\s)]+\)|<https?:\/\/[^\s>]+>|https?:\/\/[^\s<>()]+)/g

function inline(text: string, theme: MarkdownTheme, key: string): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  let n = 0
  for (const m of text.matchAll(INLINE)) {
    const start = m.index ?? 0
    if (start > last) out.push(text.slice(last, start))
    const t = m[0]
    const k = `${key}-${n++}`
    const link = (href: string, label: ReactNode) => (
      <a key={k} href={href} target="_blank" rel="noopener noreferrer" className={theme.link}>
        {label}
      </a>
    )
    if (t.startsWith('**')) out.push(<strong key={k}>{inline(t.slice(2, -2), theme, k)}</strong>)
    else if (t.startsWith('__')) out.push(<u key={k}>{inline(t.slice(2, -2), theme, k)}</u>)
    else if (t.startsWith('~~')) out.push(<s key={k}>{inline(t.slice(2, -2), theme, k)}</s>)
    else if (t.startsWith('`')) out.push(<code key={k} className={theme.code}>{t.slice(1, -1)}</code>)
    else if (t.startsWith('[')) {
      const [, label, href] = /^\[([^\]]+)\]\((.+)\)$/.exec(t) ?? []
      out.push(link(href, inline(label, theme, k)))
    } else if (t.startsWith('<')) out.push(link(t.slice(1, -1), t.slice(1, -1)))
    else if (t.startsWith('http')) out.push(link(t, t))
    else out.push(<em key={k}>{inline(t.slice(1, -1), theme, k)}</em>)
    last = start + t.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

/** Lignes d'un paragraphe : retours à la ligne conservés. */
function lines(ls: string[], theme: MarkdownTheme, key: string): ReactNode[] {
  return ls.flatMap((l, i) => (i === 0 ? inline(l, theme, `${key}-${i}`) : [<br key={`${key}-br${i}`} />, ...inline(l, theme, `${key}-${i}`)]))
}

export default function Markdown({ text, theme }: { text: string; theme: MarkdownTheme }) {
  const src = text.replace(/\r\n/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let i = 0

  while (i < src.length) {
    const line = src[i]
    const key = `b${i}`

    // Ligne vide = une vraie ligne vide, comme Discord (et non un simple écart
    // entre paragraphes) : deux retours à la ligne se voient.
    if (line.trim() === '') {
      blocks.push(<div key={key} className={theme.blank} aria-hidden="true" />)
      i++
      continue
    }
    if (line.trimStart().startsWith('```')) {
      const code: string[] = []
      i++
      while (i < src.length && !src[i].trimStart().startsWith('```')) code.push(src[i++])
      i++ // fermeture
      blocks.push(
        <pre key={key} className={theme.pre}>
          <code>{code.join('\n')}</code>
        </pre>,
      )
      continue
    }
    const heading = /^(#{1,3}) (.+)$/.exec(line)
    if (heading) {
      const cls = [theme.h1, theme.h2, theme.h3][heading[1].length - 1]
      const content = inline(heading[2], theme, key)
      blocks.push(
        heading[1].length === 1 ? <h3 key={key} className={cls}>{content}</h3> : heading[1].length === 2 ? <h4 key={key} className={cls}>{content}</h4> : <h5 key={key} className={cls}>{content}</h5>,
      )
      i++
      continue
    }
    const sub = /^-# (.+)$/.exec(line)
    if (sub) {
      blocks.push(<p key={key} className={theme.subtext}>{inline(sub[1], theme, key)}</p>)
      i++
      continue
    }
    if (/^\s*[-*] .+/.test(line)) {
      const items: string[] = []
      while (i < src.length && /^\s*[-*] .+/.test(src[i])) items.push(src[i++].replace(/^\s*[-*] /, ''))
      blocks.push(
        <ul key={key} className={theme.ul}>
          {items.map((it, j) => (
            <li key={j} className={theme.li}>
              {inline(it, theme, `${key}-${j}`)}
            </li>
          ))}
        </ul>,
      )
      continue
    }
    if (/^> ?/.test(line)) {
      const quoted: string[] = []
      while (i < src.length && /^> ?/.test(src[i])) quoted.push(src[i++].replace(/^> ?/, ''))
      blocks.push(<blockquote key={key} className={theme.quote}>{lines(quoted, theme, key)}</blockquote>)
      continue
    }
    const para: string[] = []
    while (
      i < src.length &&
      src[i].trim() !== '' &&
      !/^(#{1,3} |-# |\s*[-*] |> ?|\s*```)/.test(src[i])
    ) {
      para.push(src[i++])
    }
    // Ligne qui ressemble à un début de bloc sans en être un (« - » ou « -# »
    // seuls) : affichée telle quelle, sinon la boucle n'avancerait plus.
    if (para.length === 0) para.push(src[i++])
    blocks.push(<p key={key} className={theme.p}>{lines(para, theme, key)}</p>)
  }

  return <>{blocks}</>
}

/**
 * Rendu du launcher — celui du site (`SITE_THEME` là-bas), avec deux écarts
 * assumés : les titres de section sont un cran plus marqués, et le corps est
 * en `txt-secondary` sur 13 px plutôt que 14 px atténué. Le site se lit
 * assis ; une modale s'ouvre au démarrage, devant quelqu'un qui voulait juste
 * lancer une partie.
 */
export const LAUNCHER_THEME: MarkdownTheme = {
  h1: 'text-[15px] font-bold text-txt-primary mt-4 mb-1 first:mt-0',
  h2: 'text-[14px] font-bold text-txt-primary mt-4 mb-1 first:mt-0',
  h3: 'text-[13px] font-bold text-txt-primary mt-3 mb-1 first:mt-0',
  subtext: 'text-[11.5px] text-txt-muted',
  p: 'text-[13px] text-txt-secondary leading-relaxed',
  ul: 'list-disc pl-5 text-[13px] text-txt-secondary flex flex-col gap-1',
  li: 'leading-relaxed marker:text-accent-hover',
  quote: 'border-l-2 border-accent/50 pl-3 text-[13px] text-txt-secondary',
  pre: 'rounded-lg bg-bg-primary/60 border border-line p-3 text-[12px] my-2 overflow-x-auto',
  code: 'rounded bg-bg-primary/60 px-1 py-0.5 text-[0.9em] text-txt-primary',
  link: 'text-accent-hover hover:underline',
  // Une ligne vide vaut une vraie ligne, mais pas plus : sur un écran de
  // 450 px de haut, les doubles sauts d'une note bien aérée mangeraient la
  // moitié de la place.
  blank: 'h-[0.7rem]',
}
