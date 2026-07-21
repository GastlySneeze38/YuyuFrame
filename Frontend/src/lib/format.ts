// Formatteurs partagés — avant ce fichier, chacune de ces fonctions existait
// en 2 à 3 copies légèrement différentes (précision, arrondis) dispersées
// dans Mods.tsx, ImportSourceModal.tsx, Instances.tsx, Sync.tsx et Stats.tsx.

/** Taille de fichier lisible (octets/Ko/Mo). Unifie les 3 variantes qui
 * affichaient une précision différente selon la page. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} o`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} Ko`
  return `${(bytes / (1024 * 1024)).toFixed(1)} Mo`
}

/** RAM en Mo/Go pour l'affichage (ex: "4096" → "4 Go"). */
export function formatRam(mb: number): string {
  return mb >= 1024 ? `${mb / 1024} Go` : `${mb} Mo`
}

/** Paliers de RAM proposés à la création/import d'une instance. */
export const RAM_OPTIONS = [1024, 2048, 4096, 6144, 8192]

/** Nombre de téléchargements Modrinth compact ("1.2k", "3.4M"). */
export function formatDownloadCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(0)}k`
  return String(n)
}

/** Timestamp unix (secondes) → date + heure complètes ("03/07/26 14:32"). */
export function formatDateTime(ts: number): string {
  return new Date(ts * 1000).toLocaleString('fr-FR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

/** Timestamp unix (secondes) → date courte ("3 mars"). */
export function formatShortDate(ts: number): string {
  return new Date(ts * 1000).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })
}

/** Timestamp unix (secondes) → heure ("14:32"). */
export function formatTime(ts: number): string {
  return new Date(ts * 1000).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

/** Timestamp unix (secondes) → durée relative ("à l'instant", "il y a 5 min"). */
export function formatRelativeTime(ts: number): string {
  const diff = Date.now() / 1000 - ts
  if (diff < 60) return "à l'instant"
  if (diff < 3600) return `il y a ${Math.floor(diff / 60)} min`
  if (diff < 86400) return `il y a ${Math.floor(diff / 3600)}h`
  return `il y a ${Math.floor(diff / 86400)}j`
}

/** Durée en secondes → durée de jeu lisible ("2h 15m", "< 1 min"). */
export function formatDuration(secs: number): string {
  if (secs < 60) return '< 1 min'
  if (secs < 3600) return `${Math.floor(secs / 60)} min`
  const h = Math.floor(secs / 3600)
  const m = Math.floor((secs % 3600) / 60)
  return m > 0 ? `${h}h ${m}m` : `${h}h`
}

/** Les 14 derniers jours (aujourd'hui inclus) en "YYYY-MM-DD", pour l'axe des stats. */
export function getLast14Days(): string[] {
  return Array.from({ length: 14 }, (_, i) => {
    const d = new Date()
    d.setDate(d.getDate() - (13 - i))
    return d.toISOString().split('T')[0]
  })
}

/** "YYYY-MM-DD" → abréviation du jour de la semaine sur 2 lettres ("lu", "ma"...). */
export function formatDayLabel(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return d.toLocaleDateString('fr-FR', { weekday: 'short' }).slice(0, 2)
}
