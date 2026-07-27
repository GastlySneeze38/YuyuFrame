export interface QuadEntry { cx: number; cz: number; q: number; o: number }

export interface OwnershipData {
  peer_id: string
  peer_name: string
  my_x: number
  my_z: number
  pc: number
  tps: number
  mspt: number
  latency_ms: number
  chunks_computed: number
  chunks_skipped: number
  hook_calls: number
  tick_chunk_diag: string
  is_paused_diag: string
  player_list_empty_diag: string
  send_hook_diag: string
  baseline_blocks_applied: number
  baseline_chunks_applied: number
  baseline_queue: number
  baseline_chunks_sent: number
  baseline_bytes_sent: number
  baseline_active_streams: number
  live_blocks_applied: number
  live_queue: number
  send_hook_calls: number
  live_blocks_sent: number
  live_entities_sent: number
  live_queue_out: number
  quads: QuadEntry[]
  peers_reported?: OwnershipData[]
}

export const TAB_IDS = ['apercu', 'performance', 'reseau', 'sync', 'diagnostic'] as const
export type TabId = typeof TAB_IDS[number]
