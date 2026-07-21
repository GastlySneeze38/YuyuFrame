export const OWNERSHIP_API = 'http://127.0.0.1:3849/ownership'
export const POLL_MS = 500
export const CELL = 8          // px par quadrant (2×2 par chunk)
export const VIEW_HALF = 10    // chunks affichés de chaque côté du centre
export const CHUNK_PX = CELL * 2
export const CANVAS = (VIEW_HALF * 2 + 1) * CHUNK_PX   // total px
export const HISTORY_CAP = 180  // ~90s à 500ms/poll

const COLORS = [
  '#22c55e',  // 0 — moi (vert)
  '#ef4444',  // 1
  '#3b82f6',  // 2
  '#f97316',  // 3
  '#a855f7',  // 4
  '#06b6d4',  // 5
]

// q: 0=NW, 1=NE, 2=SW, 3=SE → offsets en quadrants (col, row) dans le chunk
export const QUAD_OFFSET: [number, number][] = [[0, 0], [1, 0], [0, 1], [1, 1]]

export function ownerColor(o: number): string {
  return COLORS[o % COLORS.length]
}

export function tpsColor(tps: number): string {
  if (tps >= 19) return '#22c55e'
  if (tps >= 15) return '#eab308'
  return '#ef4444'
}

export function latencyColor(ms: number): string {
  if (ms < 0) return 'rgba(255,255,255,0.3)'
  if (ms <= 60) return '#22c55e'
  if (ms <= 150) return '#eab308'
  return '#ef4444'
}

export function pushSample(arr: number[], v: number): void {
  arr.push(v)
  if (arr.length > HISTORY_CAP) arr.shift()
}
