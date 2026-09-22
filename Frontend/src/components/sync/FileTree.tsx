import { useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { SNAP, press } from '@/lib/motion'
import { formatBytes } from '@/lib/format'
import { useT } from '@/i18n'
import type { SyncFile } from '@/types'

/**
 * L'arborescence de ce qui est réellement stocké sur le serveur.
 *
 * Le manifeste est une liste plate de chemins — deux mille lignes de
 * « config/quelquechose/…/truc.json » n'apprennent rien. Reconstruire les
 * dossiers et les replier permet enfin de répondre à la question qu'on se
 * pose : « qu'est-ce qu'il y a là-dedans, et qu'est-ce qui pèse ? »
 *
 * Les dossiers sont triés par taille, pas par nom : ce qui remplit le quota
 * se lit en haut, sans chercher.
 */

interface Node {
  name: string
  /** Chemin complet, pour les clés React. */
  path: string
  size: number
  files: number
  children: Node[]
}

function build(files: SyncFile[]): Node {
  const root: Node = { name: '', path: '', size: 0, files: 0, children: [] }

  for (const file of files) {
    const parts = file.path.split('/')
    let node = root
    node.size += file.size
    node.files += 1

    for (let i = 0; i < parts.length; i++) {
      const isLeaf = i === parts.length - 1
      const path = parts.slice(0, i + 1).join('/')
      let next = node.children.find((c) => c.name === parts[i])
      if (!next) {
        next = { name: parts[i], path, size: 0, files: 0, children: [] }
        node.children.push(next)
      }
      next.size += file.size
      if (isLeaf) next.files = 1
      else next.files += 1
      node = next
    }
  }

  const sort = (n: Node) => {
    // Les dossiers avant les fichiers, puis du plus lourd au plus léger.
    n.children.sort((a, b) => Number(b.children.length > 0) - Number(a.children.length > 0) || b.size - a.size)
    n.children.forEach(sort)
  }
  sort(root)
  return root
}

function Row({ node, depth, defaultOpen }: { node: Node; depth: number; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen)
  const isDir = node.children.length > 0
  const t = useT()

  return (
    <>
      <motion.button
        {...press}
        onClick={() => isDir && setOpen((v) => !v)}
        disabled={!isDir}
        style={{ paddingLeft: 8 + depth * 14 }}
        className={`flex w-full items-center gap-2 rounded-lg py-1.5 pr-2.5 text-left transition-colors duration-150 ${
          isDir ? 'hover:bg-surface-2' : 'cursor-default'
        }`}
      >
        {isDir ? (
          <motion.svg viewBox="0 0 24 24" fill="currentColor" width={9} height={9} animate={{ rotate: open ? 90 : 0 }} transition={SNAP} className="shrink-0 text-txt-muted">
            <path d="M8 5v14l11-7z" />
          </motion.svg>
        ) : (
          <span className="h-[9px] w-[9px] shrink-0" />
        )}
        <span className={`min-w-0 flex-1 truncate text-[11.5px] ${isDir ? 'font-semibold text-txt-primary' : 'font-mono text-txt-secondary'}`}>
          {node.name}
        </span>
        {isDir && <span className="shrink-0 text-[10px] text-txt-muted">{t('sync.fileCount', { count: node.files })}</span>}
        <span className="w-16 shrink-0 text-right text-[10.5px] tabular-nums text-txt-muted">{formatBytes(node.size)}</span>
      </motion.button>

      <AnimatePresence initial={false}>
        {isDir && open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            {node.children.map((child) => (
              <Row key={child.path} node={child} depth={depth + 1} defaultOpen={false} />
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </>
  )
}

export function FileTree({ files }: { files: SyncFile[] }) {
  const t = useT()
  const [query, setQuery] = useState('')

  const tree = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return build(needle ? files.filter((f) => f.path.toLowerCase().includes(needle)) : files)
  }, [files, query])

  return (
    <div className="flex min-h-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('sync.storedContent')}</span>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('sync.searchFile')}
          className="h-7 w-52 rounded-lg border border-line bg-surface-2 px-2.5 text-[11px] text-txt-primary outline-none transition-colors duration-150 placeholder:text-txt-muted focus:border-accent/45"
        />
      </div>

      {tree.children.length === 0 ? (
        <p className="py-6 text-center text-[11.5px] text-txt-muted">{query ? t('sync.noFileMatches') : t('sync.nothingStored')}</p>
      ) : (
        <div className="max-h-[420px] overflow-y-auto">
          {/* Les dossiers du premier niveau sont ouverts : c'est là que se
              trouve ce qu'on vient voir. */}
          {tree.children.map((child) => (
            <Row key={child.path} node={child} depth={0} defaultOpen />
          ))}
        </div>
      )}
    </div>
  )
}
