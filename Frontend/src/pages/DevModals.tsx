import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { useModalQueue, type QueuedModal } from '@/stores/useModalQueue'

/**
 * Atelier des modales — écran de développement.
 *
 * ── Pourquoi il existe ────────────────────────────────────────────────────
 * Chacune de ces modales attend un événement qu'on ne peut pas provoquer à la
 * demande : une mise à jour qui vient de s'installer, un plantage de
 * Minecraft, une réponse de l'équipe, dix parties jouées. Les relire à l'œil
 * demandait donc soit de faire planter le jeu pour de vrai, soit de trafiquer
 * l'état persisté à la main — et de recommencer à chaque retouche.
 *
 * Ici, chaque bouton dépose la MÊME demande que la vraie source, avec des
 * données d'exemple. C'est donc bien la file qui décide de l'ordre et du
 * compteur : cet écran ne court-circuite rien, il ne fait que fournir la
 * matière. Ce qu'on voit est ce que verra la personne.
 *
 * ── Ce qu'il n'est pas ────────────────────────────────────────────────────
 * Il n'est pas compilé dans la version publiée : la route qui y mène est
 * posée sous `import.meta.env.DEV` (voir App.tsx), donc ce fichier n'entre
 * même pas dans le paquet final.
 */

/** Une demande d'exemple par genre, aussi proche que possible du réel. */
const SAMPLES: { label: string; hint: string; item: QueuedModal }[] = [
  {
    label: 'Notes de version',
    hint: 'Annonce publiée depuis le back-office',
    item: {
      kind: 'patchNotes',
      key: 'dev-patch-notes',
      data: {
        title: 'Refonte complète de l’interface !',
        version: 'v0.1.1',
        publishedAt: new Date().toISOString(),
        // Du Markdown comme celui du site : titres, gras, paragraphes et
        // listes — c'est tout l'intérêt de l'échantillon.
        body: [
          '## 🎮 Le client intégré, enfin présenté',
          '',
          'L’engrenage de la page d’accueil n’ouvre plus la page des mods : il ouvre la fenêtre du client intégré. On y lit ce que c’est, et on choisit d’y jouer **avec ou sans**.',
          '',
          '⚠️ Sur les versions où il ne peut pas se charger, il n’est plus passé à la machine Java **du tout**.',
          '',
          '## 🌍 Huit langues',
          '',
          '- Quatre nouvelles : italien, portugais, polonais et russe',
          '- La bonne est devinée dès le premier lancement',
          '',
          '## 🔧 Corrections',
          '',
          '- Le menu des cartes d’instance ne se fait plus couper en bas de liste',
          '- La bannière d’accueil ne se pose plus sur le nom du joueur',
        ].join('\n'),
      },
    },
  },
  {
    label: 'Reconnexion',
    hint: 'Le système d’authentification a changé',
    item: { kind: 'reconnect', key: 'dev-reconnect', data: null },
  },
  {
    label: 'Plantage',
    hint: 'Minecraft s’est fermé tout seul',
    item: {
      kind: 'crash',
      key: 'dev-crash',
      data: {
        reportId: 'dev-report',
        instanceId: 'dev-instance',
        title: 'java.lang.NoSuchMethodError: net.minecraft.client.Options',
        cause: 'crash_report',
      },
    },
  },
  {
    label: 'Plantage — statut',
    hint: 'L’équipe a donné un statut à un rapport envoyé',
    item: {
      kind: 'crashStatus',
      key: 'dev-crash-status',
      data: {
        reportId: 'dev-remote-report',
        publicId: 'CR-4F2A',
        title: 'java.lang.NoSuchMethodError: net.minecraft.client.Options',
        status: 'fixed',
        note: 'Venait de Sodium 0.5.3, corrigé dans la 0.5.4 — mets le mod à jour.',
      },
    },
  },
  {
    label: 'Support — réponse',
    hint: 'L’équipe a répondu à une demande',
    item: {
      kind: 'support',
      key: 'dev-support-answered',
      data: {
        ticketId: 'dev-ticket',
        publicId: 'YF-4F2A',
        subject: 'Crash au lancement d’une instance Forge',
        status: 'answered',
      },
    },
  },
  {
    label: 'Support — close',
    hint: 'Une demande vient d’être close',
    item: {
      kind: 'support',
      key: 'dev-support-closed',
      data: {
        ticketId: 'dev-ticket-2',
        publicId: 'YF-91C3',
        subject: 'Sauvegarde cloud bloquée à 40 %',
        status: 'closed',
      },
    },
  },
  {
    label: 'Rappel d’achat',
    hint: 'Compte hors ligne, aucun compte Microsoft',
    item: { kind: 'offlineReminder', key: 'dev-offline', data: null, remember: false },
  },
  {
    label: 'Demande d’avis',
    hint: 'Après une partie, au-delà du seuil',
    item: { kind: 'review', key: 'dev-review', data: { sessions: 14 } },
  },
]

export default function DevModals() {
  const push = useModalQueue((s) => s.push)
  const forgetAll = useModalQueue((s) => s.forgetAll)
  const current = useModalQueue((s) => s.current)
  const pending = useModalQueue((s) => s.pending)
  const seen = useModalQueue((s) => s.seen)

  /** Redépose en repartant de zéro : une demande déjà vue serait refusée. */
  function show(item: QueuedModal) {
    forgetAll()
    push(item)
  }

  /** Toutes d'un coup : c'est le seul moyen de voir l'ordre et le compteur. */
  function showAll() {
    forgetAll()
    SAMPLES.forEach((s) => push(s.item))
  }

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <PageHeader backTo="/home">
        <div className="flex min-w-0 flex-col">
          <span className="text-[14px] font-bold text-txt-primary">Atelier des modales</span>
          <span className="text-[11px] text-txt-muted">
            Écran de développement — absent de la version publiée
          </span>
        </div>
      </PageHeader>

      <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-6">
        <div className="flex items-center gap-2">
          <Button variant="primary" onClick={showAll}>
            Tout déposer (ordre + compteur)
          </Button>
          <Button onClick={forgetAll}>Oublier les modales vues</Button>
        </div>

        {/* L'état de la file en clair : sans lui, on ne distingue pas une
            demande refusée pour cause de « déjà vue » d'une demande qui ne
            part pas du tout. */}
        <div className="flex flex-col gap-1 rounded-xl border border-border bg-surface-2 px-4 py-3 text-[12px] text-txt-secondary">
          <span>À l’écran : <b className="text-txt-primary">{current?.kind ?? '—'}</b></span>
          <span>En attente : <b className="text-txt-primary">{pending.map((p) => p.kind).join(', ') || '—'}</b></span>
          <span>Déjà vues (retenues) : <b className="text-txt-primary">{seen.length}</b></span>
        </div>

        <div className="grid grid-cols-2 gap-2">
          {SAMPLES.map((sample) => (
            <button
              key={sample.item.key}
              onClick={() => show(sample.item)}
              className="flex flex-col items-start gap-0.5 rounded-xl border border-border bg-surface-2 px-4 py-3 text-left transition-colors hover:border-accent/40 hover:bg-surface-3"
            >
              <span className="text-[13px] font-semibold text-txt-primary">{sample.label}</span>
              <span className="text-[11px] text-txt-muted">{sample.hint}</span>
            </button>
          ))}
        </div>

        <p className="text-[11px] leading-relaxed text-txt-muted">
          Les boutons oublient d’abord les modales déjà vues : sans ça, une
          demande déposée deux fois serait refusée par la file — ce qui est
          exactement ce qu’on lui demande de faire en vrai.
        </p>
      </div>
    </div>
  )
}
