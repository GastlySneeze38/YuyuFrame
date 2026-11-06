import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { crashUnseen, useCrashWatch } from '@/stores/useCrashWatch'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError } from '@/stores/useErrorToast'
import { SNAP, listItemVariants, listVariants, press } from '@/lib/motion'
import { useT } from '@/i18n'
import { useIsMobile } from '@/mobile/useIsMobile'
import { PushedScreen } from '@/mobile/ui/PushedScreen'
import type { CrashEntry, CrashStatus, LocalCrashReport, LocalCrashSummary, RemoteCrashSummary } from '@/types/crash'

/**
 * Rapports de plantage, dans l'onglet Support.
 *
 * Quand le jeu se ferme tout seul, le launcher a déjà tout capturé — trace,
 * mods, machine, fin du journal — et l'a écrit sur le disque. Cet écran est
 * l'endroit où on le lit, où on décide de l'envoyer, et où on lit la réponse
 * de l'équipe.
 *
 * Rien ne part avant le clic sur « Envoyer », et le rapport montré ici est
 * exactement celui qui partira : c'est la seule façon honnête de demander
 * l'autorisation d'envoyer le journal de quelqu'un.
 */

const STATUS_STYLES: Record<CrashStatus, string> = {
  new: 'bg-surface-3 text-txt-muted',
  investigating: 'bg-warning/15 text-warning',
  known: 'bg-accent/15 text-accent-hover',
  fixed: 'bg-success/15 text-success',
  wont_fix: 'bg-surface-3 text-txt-muted',
  duplicate: 'bg-surface-3 text-txt-muted',
  not_a_bug: 'bg-surface-3 text-txt-muted',
}

/** Fusionne les deux sources en une seule liste, la plus récente d'abord. */
function merge(local: LocalCrashSummary[], remote: RemoteCrashSummary[]): CrashEntry[] {
  const byRemoteId = new Map(remote.map((r) => [r.id, r]))
  const entries: CrashEntry[] = local.map((l) => ({
    key: l.id,
    local: l,
    remote: (l.sent_id && byRemoteId.get(l.sent_id)) || null,
  }))
  // Rapports envoyés depuis un autre PC : ils n'ont pas de fichier ici, mais
  // ils font partie de ce que l'équipe a de nous — les cacher donnerait
  // l'impression qu'un signalement s'est perdu.
  const claimed = new Set(local.map((l) => l.sent_id).filter(Boolean))
  for (const r of remote) {
    if (!claimed.has(r.id)) entries.push({ key: r.id, local: null, remote: r })
  }
  const at = (e: CrashEntry) => e.local?.occurred_at ?? e.remote?.occurred_at ?? ''
  return entries.sort((a, b) => at(b).localeCompare(at(a)))
}

const when = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—'

export function CrashPanel({ signedIn }: { signedIn: boolean }) {
  const t = useT()
  const [entries, setEntries] = useState<CrashEntry[] | null>(null)
  const [openKey, setOpenKey] = useState<string | null>(null)

  /**
   * Ouvrir un rapport vaut lecture de son statut : c'est ce qui éteint la
   * pastille (voir `stores/useCrashWatch.ts`). Fermer la modale qui annonce
   * le changement ne suffit pas — on peut la fermer sans avoir rien lu.
   *
   * Seuls les rapports envoyés comptent : un rapport local n'a pas de statut.
   */
  const openEntry = useCallback((entry: CrashEntry) => {
    setOpenKey(entry.key)
    if (entry.remote) {
      useCrashWatch.getState().acknowledge(entry.remote.id, entry.remote.status, entry.remote.status_updated_at)
    }
  }, [])

  // `?crash=<id>` — la modale « ton rapport a bougé » renvoie ici avec le
  // rapport en question. Sans ça elle déposerait sur une liste où il faudrait
  // le retrouver soi-même, ce qui est précisément ce qu'on voulait éviter.
  // Une fois par demande : la liste se recharge à chaque relecture de la
  // veille, et rouvrir ce rapport à chaque fois écraserait le choix de
  // l'utilisateur (et l'acquitterait sans qu'il l'ait regardé).
  const wanted = new URLSearchParams(useLocation().search).get('crash')
  const handled = useRef<string | null>(null)
  useEffect(() => {
    if (!wanted || !entries || handled.current === wanted) return
    const entry = entries.find((e) => e.remote?.id === wanted)
    if (entry) {
      handled.current = wanted
      openEntry(entry)
    }
  }, [wanted, entries, openEntry])

  // Ce que l'utilisateur a déjà ouvert : le point rouge des lignes. Et
  // `known`, qui change à chaque relecture de la veille : un statut arrivé
  // pendant qu'on est sur cet écran recharge la liste — sinon ouvrir le
  // rapport acquitterait l'ancien statut, et la pastille resterait allumée.
  const acknowledged = useCrashWatch((s) => s.acknowledged)
  const known = useCrashWatch((s) => s.known)

  const reload = useCallback(async () => {
    const local = await api.crashes.list().catch(() => [] as LocalCrashSummary[])
    // Sans compte connecté, il n'y a rien à demander au serveur : les
    // rapports locaux se lisent quand même, et c'est important — on peut très
    // bien planter avant d'avoir un compte.
    const remote = signedIn ? await api.crashes.remote().catch(() => [] as RemoteCrashSummary[]) : []
    setEntries(merge(local, remote))
  }, [signedIn])

  useEffect(() => {
    reload()
  }, [reload, known])

  const open = useMemo(() => entries?.find((e) => e.key === openKey) ?? null, [entries, openKey])
  const mobile = useIsMobile()
  // Garde le dernier rapport le temps que l'écran poussé reparte.
  const lastOpen = useRef<CrashEntry | null>(null)
  if (open) lastOpen.current = open

  if (entries === null) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <ButtonSpinner size={28} color="rgb(var(--accent-text))" trackColor="rgba(255,255,255,0.08)" />
      </div>
    )
  }

  if (entries.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 px-10 text-center">
        <p className="text-[13px] font-semibold max-sm:text-[17px]">{t('crash.emptyTitle')}</p>
        <p className="max-w-md text-[11px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t('crash.emptyText')}</p>
      </div>
    )
  }

  // ── Téléphone ── la liste sur toute la largeur, le rapport en écran poussé.
  if (mobile) {
    const shown = open ?? lastOpen.current
    return (
      <>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(24px,env(safe-area-inset-bottom))] pt-3">
          <section className="flex flex-col gap-2">
            <h2 className="px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('crash.listTitle')}</h2>
            <div className="flex flex-col divide-y divide-white/[0.06] overflow-hidden rounded-2xl border border-white/[0.06] bg-white/[0.04]">
              {entries.map((e) => {
                const title = e.local?.title ?? e.remote?.title ?? ''
                const status = e.remote?.status ?? null
                return (
                  <button
                    key={e.key}
                    onClick={() => openEntry(e)}
                    className="flex min-h-[68px] w-full items-center gap-3 px-4 py-3 text-left active:bg-white/[0.04]"
                  >
                    <span className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="flex items-center gap-2">
                        <span className="truncate font-mono text-[14px] font-semibold text-white">{title}</span>
                        {e.remote && crashUnseen(acknowledged, e.remote) && <span className="h-2.5 w-2.5 shrink-0 rounded-full bg-danger" />}
                      </span>
                      <span className="flex items-center gap-2">
                        {status ? (
                          <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-[11px] font-bold uppercase tracking-wide ${STATUS_STYLES[status]}`}>
                            {t(`crash.status.${status}`)}
                          </span>
                        ) : (
                          <span className="shrink-0 rounded-md bg-warning/15 px-1.5 py-0.5 text-[11px] font-bold uppercase tracking-wide text-warning">
                            {t('crash.notSent')}
                          </span>
                        )}
                        <span className="truncate text-[13px] text-white/55">{e.local?.instance_name ?? e.remote?.mc_version ?? ''}</span>
                      </span>
                    </span>
                    <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-white/35"><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
                  </button>
                )
              })}
            </div>
          </section>
        </div>
        <PushedScreen
          open={!!open}
          onBack={() => setOpenKey(null)}
          backLabel={t('crash.listTitle')}
          title={shown?.local?.instance_name ?? shown?.remote?.mc_version ?? t('crash.listTitle')}
          subtitle={shown ? when(shown.local?.occurred_at ?? shown.remote?.occurred_at) : undefined}
          bodyClassName=""
        >
          {shown && <CrashDetail key={shown.key} entry={shown} signedIn={signedIn} onChanged={reload} onGone={() => setOpenKey(null)} />}
        </PushedScreen>
      </>
    )
  }

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[300px_minmax(0,1fr)]">
      <div className="flex min-h-0 flex-col border-r border-line-soft">
        <p className="px-4 pt-4 pb-2 text-[11px] font-semibold uppercase tracking-wider text-txt-muted">
          {t('crash.listTitle')}
        </p>
        <motion.div
          variants={listVariants}
          initial="initial"
          animate="animate"
          className="flex min-h-0 flex-col gap-1 overflow-y-auto px-3 pb-3"
        >
          {entries.map((e) => {
            const title = e.local?.title ?? e.remote?.title ?? ''
            const status = e.remote?.status ?? null
            return (
              <motion.button
                key={e.key}
                variants={listItemVariants}
                layout
                {...press}
                onClick={() => openEntry(e)}
                className={`flex flex-col gap-1.5 rounded-xl border px-3 py-2.5 text-left transition-colors duration-150 ${
                  e.key === openKey ? 'border-accent/40 bg-accent/10' : 'border-transparent hover:border-line hover:bg-surface-1'
                }`}
              >
                <span className="flex items-center gap-2">
                  <span className="truncate text-[12px] font-semibold leading-snug">{title}</span>
                  {/* Statut changé depuis la dernière ouverture : c'est ce
                      point que la pastille de l'accueil désigne. */}
                  {e.remote && crashUnseen(acknowledged, e.remote) && <span className="h-2 w-2 shrink-0 rounded-full bg-danger" />}
                </span>
                <span className="flex items-center gap-2">
                  {status ? (
                    <span className={`rounded-md px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ${STATUS_STYLES[status]}`}>
                      {t(`crash.status.${status}`)}
                    </span>
                  ) : (
                    <span className="rounded-md bg-warning/15 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-warning">
                      {t('crash.notSent')}
                    </span>
                  )}
                  <span className="truncate text-[10px] text-txt-muted">
                    {e.local?.instance_name ?? e.remote?.mc_version ?? ''}
                  </span>
                </span>
              </motion.button>
            )
          })}
        </motion.div>
      </div>

      {/* Pas d'AnimatePresence ici : un panneau qui change d'enfant à chaque
          sélection s'y retrouve figé à son état initial, donc cliquable mais
          invisible. Le `key` suffit à rejouer l'entrée. */}
      {open ? (
        <CrashDetail key={open.key} entry={open} signedIn={signedIn} onChanged={reload} onGone={() => setOpenKey(null)} />
      ) : (
        <div className="flex items-center justify-center px-10 text-center text-[12px] text-txt-secondary">
          {t('crash.pickOne')}
        </div>
      )}
    </div>
  )
}

// ── Le rapport ───────────────────────────────────────────────────────────────

function Row({ label, value }: { label: string; value: string | number | null | undefined }) {
  if (value === null || value === undefined || value === '') return null
  return (
    // Téléphone : le libellé au-dessus de sa valeur — une colonne de 144 px
    // ne laissait presque rien à la valeur.
    <div className="flex gap-3 text-[11.5px] max-sm:flex-col max-sm:gap-0.5">
      <span className="w-36 shrink-0 text-txt-muted max-sm:w-auto max-sm:text-[13px] max-sm:text-white/55">{label}</span>
      <span className="selectable min-w-0 break-words text-txt-secondary max-sm:text-[15px] max-sm:text-white/85">{value}</span>
    </div>
  )
}

/** Section dépliable — la trace et le journal font des milliers de lignes :
 *  les afficher d'office enterrerait tout le reste. */
function Fold({ title, count, children }: { title: string; count?: number; children: React.ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="rounded-xl border border-line">
      <motion.button
        {...press}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left max-sm:min-h-[52px] max-sm:gap-3 max-sm:px-4"
      >
        <motion.svg viewBox="0 0 24 24" fill="currentColor" width={9} height={9} animate={{ rotate: open ? 90 : 0 }} transition={SNAP} className="text-txt-muted max-sm:h-3 max-sm:w-3">
          <path d="M8 5v14l11-7z" />
        </motion.svg>
        <span className="flex-1 text-[12px] font-semibold max-sm:text-[15px]">{title}</span>
        {count !== undefined && <span className="text-[10px] text-txt-muted max-sm:text-[13px]">{count}</span>}
      </motion.button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="border-t border-line-soft px-3 py-2.5">{children}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function Raw({ text }: { text: string }) {
  return (
    <pre className="selectable max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-surface-1 p-2.5 font-mono text-[10.5px] leading-relaxed text-txt-secondary max-sm:text-[12px]">
      {text}
    </pre>
  )
}

function CrashDetail({
  entry,
  signedIn,
  onChanged,
  onGone,
}: {
  entry: CrashEntry
  signedIn: boolean
  onChanged: () => Promise<void>
  onGone: () => void
}) {
  const t = useT()
  const [report, setReport] = useState<LocalCrashReport | null>(null)
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const localId = entry.local?.id ?? null

  useEffect(() => {
    setReport(null)
    setConfirmDelete(false)
    if (localId) api.crashes.get(localId).then(setReport).catch(() => setReport(null))
  }, [localId])

  const remote = entry.remote
  const title = entry.local?.title ?? remote?.title ?? ''

  async function send() {
    if (!localId) return
    setBusy(true)
    try {
      await api.crashes.send(localId)
      await onChanged()
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function copy() {
    if (!localId) return
    try {
      await navigator.clipboard.writeText(await api.crashes.asText(localId))
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    } catch (e) {
      showError(errorMessage(e))
    }
  }

  async function remove() {
    setBusy(true)
    try {
      if (remote) await api.crashes.hide(remote.id, localId)
      else if (localId) await api.crashes.delete(localId)
      await onChanged()
      onGone()
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      transition={SNAP}
      className="flex min-h-0 flex-col gap-3 overflow-y-auto px-6 py-4 max-sm:gap-4 max-sm:overflow-visible max-sm:px-4 max-sm:pb-[max(24px,env(safe-area-inset-bottom))]"
    >
      <div className="flex flex-col gap-1">
        <p className="selectable break-words font-mono text-[12.5px] leading-snug text-txt-primary max-sm:text-[14px]">{title}</p>
        <p className="text-[10.5px] text-txt-muted max-sm:text-[13px] max-sm:text-white/55">
          {t(`crash.kind.${entry.local?.kind ?? remote?.kind ?? 'exit'}`)} · {when(entry.local?.occurred_at ?? remote?.occurred_at)}
          {remote?.public_id ? ` · ${remote.public_id}` : ''}
        </p>
      </div>

      {/* La réponse de l'équipe en premier : c'est la seule chose qu'on vient
          vraiment chercher quand on revient sur un rapport déjà envoyé. */}
      {remote ? (
        <div className="flex flex-col gap-2 rounded-xl border border-line bg-surface-1 px-3.5 py-3">
          <div className="flex items-center gap-2">
            <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide max-sm:text-[11px] ${STATUS_STYLES[remote.status]}`}>
              {t(`crash.status.${remote.status}`)}
            </span>
            <span className="text-[10.5px] text-txt-muted max-sm:text-[13px] max-sm:text-white/55">{t(`crash.statusHint.${remote.status}`)}</span>
          </div>
          {remote.status_note && <p className="text-[12px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{remote.status_note}</p>}
        </div>
      ) : (
        <div className="flex flex-col gap-2.5 rounded-xl border border-accent/30 bg-accent/[0.07] px-3.5 py-3 max-sm:gap-3 max-sm:rounded-2xl max-sm:p-4">
          <p className="text-[11.5px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t('crash.sendIntro')}</p>
          <div className="max-sm:[&>*]:h-12 max-sm:[&>*]:w-full max-sm:[&>*]:text-[16px]">
            <Button size="sm" variant="primary" onClick={send} loading={busy} disabled={!signedIn || busy || !localId}>
              {t('crash.send')}
            </Button>
          </div>
          {!signedIn && <p className="text-[10.5px] text-txt-muted max-sm:text-[13px] max-sm:text-white/55">{t('crash.signInFirst')}</p>}
        </div>
      )}

      {report ? (
        <>
          <div className="flex flex-col gap-1 rounded-xl border border-line px-3.5 py-3 max-sm:gap-3 max-sm:rounded-2xl max-sm:p-4">
            <Row label={t('crash.field.instance')} value={`${report.instance_name} — Minecraft ${report.mc_version} · ${report.loader}`} />
            <Row label={t('crash.field.uptime')} value={t('crash.uptimeValue', { seconds: Math.round(report.uptime_ms / 1000) })} />
            <Row label={t('crash.field.exitCode')} value={report.exit_code} />
            <Row label={t('crash.field.java')} value={report.java_version} />
            <Row label={t('crash.field.ram')} value={report.ram_alloc_mb ? `${report.ram_alloc_mb} Mio` : null} />
            <Row label={t('crash.field.system')} value={`${report.os} ${report.os_version ?? ''} (${report.arch})`} />
            <Row label={t('crash.field.cpu')} value={report.cpu} />
            <Row label={t('crash.field.gpu')} value={report.gpu} />
            <Row label={t('crash.field.signature')} value={report.signature} />
          </div>

          {report.stack_trace && (
            <Fold title={t('crash.fold.trace')}>
              <Raw text={report.stack_trace} />
            </Fold>
          )}
          <Fold title={t('crash.fold.log')}>
            <Raw text={report.log_tail || '—'} />
          </Fold>
          <Fold title={t('crash.fold.mods')} count={report.mods.length}>
            <ul className="max-h-72 overflow-auto font-mono text-[10.5px] max-sm:text-[12px]">
              {report.mods.map((m) => (
                <li key={m.name} className={m.enabled ? 'text-txt-secondary' : 'text-txt-muted line-through'}>
                  {m.name}
                </li>
              ))}
              {report.mods.length === 0 && <li className="text-txt-muted">{t('crash.noMods')}</li>}
            </ul>
          </Fold>
          <Fold title={t('crash.fold.jvm')} count={report.jvm_args.length}>
            <Raw text={report.jvm_args.join('\n')} />
          </Fold>

          <div className="flex items-center gap-2 pb-2 max-sm:[&>*]:h-12 max-sm:[&>*]:flex-1 max-sm:[&>*]:text-[15px]">
            <Button size="sm" variant="secondary" onClick={copy}>
              {copied ? t('crash.copied') : t('crash.copy')}
            </Button>
            <Button
              size="sm"
              variant={confirmDelete ? 'danger' : 'ghost'}
              onClick={() => (confirmDelete ? remove() : setConfirmDelete(true))}
              disabled={busy}
            >
              {confirmDelete ? t('crash.confirmDelete') : t('crash.delete')}
            </Button>
          </div>
        </>
      ) : (
        // Rapport envoyé depuis un autre PC : le serveur garde le contenu,
        // mais il n'y a rien à relire ici.
        <p className="text-[11.5px] leading-relaxed text-txt-muted max-sm:text-[15px] max-sm:text-white/60">{t('crash.otherDevice')}</p>
      )}
    </motion.div>
  )
}
