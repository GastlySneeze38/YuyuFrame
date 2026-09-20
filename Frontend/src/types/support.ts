/**
 * Tickets de support — forme de référence : `Server/LauncherAPI/src/routes/
 * support.rs`. Le backend Rust laisse passer les corps tels quels, donc ces
 * types et ceux du serveur doivent rester d'accord ; la spécification vivante
 * est servie par `GET /v1/openapi.json`.
 */

export interface SupportCategory {
  code: string
  label: string
  description: string
}

/** open | answered | waiting | closed */
export type TicketStatus = 'open' | 'answered' | 'waiting' | 'closed'

export interface TicketSummary {
  id: string
  /** Identifiant court à donner à l'équipe (« YF-4F2A »). */
  public_id: string
  subject: string
  category: string
  status: TicketStatus
  created_at: string
  updated_at: string
  /** L'équipe a répondu depuis la dernière lecture. */
  has_unread: boolean
}

export interface TicketMessage {
  id: string
  /** user (le joueur) | staff (l'équipe) */
  author_kind: 'user' | 'staff'
  author_name: string
  body: string
  created_at: string
}

/** Le détail reprend les champs du résumé, aplatis, plus la conversation. */
export type TicketDetail = TicketSummary & { messages: TicketMessage[] }
