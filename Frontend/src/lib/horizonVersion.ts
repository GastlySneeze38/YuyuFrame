/**
 * Les versions d'Horizon telles qu'on les nomme au joueur.
 *
 * `instances.loader_version` d'une instance Horizon vaut :
 * - `''` : le canal Stable (une Nightly par semaine, voir `horizon/release.rs`) ;
 * - `'nightly'` : la dernière Nightly publiée ;
 * - un build précis : `nightly-2026-11-21-f037744`, ou une ancienne version
 *   numérotée (`v0.2.97`).
 */

import { formatDate } from '@/lib/format'

export const HORIZON_NIGHTLY = 'nightly'

const BUILD = /^nightly-(\d{4}-\d{2}-\d{2})-([0-9a-f]{7,40})$/

/** La date (`AAAA-MM-JJ`) et le commit d'un build de Nightly, sinon `null`. */
export function horizonBuild(version: string): { date: string; commit: string } | null {
  const m = BUILD.exec(version)
  return m ? { date: m[1], commit: m[2] } : null
}

/** « 21 novembre » dans la langue de l'interface (`sga` s'écrit en français). */
export function horizonDay(date: string, language: string): string {
  const d = new Date(`${date}T12:00:00Z`)
  if (Number.isNaN(d.getTime())) return date
  return formatDate(d, language === 'sga' ? 'fr' : language, { day: 'numeric', month: 'long', timeZone: 'UTC' })
}

/** Le nom d'une version précise : « Nightly du 21 novembre · f037744 », « v0.2.97 ». */
export function horizonVersionName(
  version: string,
  locale: string,
  t: (key: 'instancesPage.horizon.nightlyOf' | 'instancesPage.horizon.legacy', vars: Record<string, string>) => string,
): string {
  const build = horizonBuild(version)
  if (build) return t('instancesPage.horizon.nightlyOf', { date: horizonDay(build.date, locale), commit: build.commit })
  return t('instancesPage.horizon.legacy', { version })
}
