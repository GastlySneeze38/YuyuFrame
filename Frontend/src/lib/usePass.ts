import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { demoData, readPass, type BattlePass, type Challenge, type MissionsMeta, type Season } from '@/lib/competitive'
import { showError } from '@/stores/useErrorToast'
import { useDemo } from '@/stores/useDevFlags'
import { useStore } from '@/stores/useStore'
import { readCache, writeCache } from './persistentCache'

/** Préfixe `user:` : effacé à la déconnexion. */
const PASS_CACHE = 'user:pass-state'

export interface PassData {
  loading: boolean
  /** `null` : rien à montrer (« bientôt ») — pas de route, pas de mission, pas de saison. */
  challenges: Challenge[] | null
  pass: BattlePass | null
  season: Season | null
  /** Relances et bonus de journée ; `null` tant qu'on ne sait pas. */
  meta: MissionsMeta | null
  /** Remplace une mission du jour par une autre (la relance du jour). */
  reroll: (id: string) => Promise<void>
}

const EMPTY = { loading: false, challenges: null, pass: null, season: null, meta: null }
const DEMO_META: MissionsMeta = { rerollsLeft: 1, dayBonusXp: 200, dayBonusDone: false }

/**
 * Missions et passe de la saison, pour les écrans Missions et Passe :
 * données de démonstration si l'atelier les demande, sinon `GET /v1/pass`.
 * Sans compte, sans réseau ou sans la route, tout vaut `null` et les écrans
 * disent « bientôt » — jamais une erreur.
 */
export function usePass(): PassData {
  const demoOn = useDemo()
  const username = useStore((s) => s.username)
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const demo = useMemo(() => (demoOn ? demoData(username ?? 'Steve') : null), [demoOn, username])
  const [live, setLive] = useState<Omit<PassData, 'reroll'>>({ ...EMPTY, loading: true })
  // En démo, la relance retire la mission de la liste : de quoi voir le geste.
  const [demoRerolled, setDemoRerolled] = useState<string[]>([])

  useEffect(() => {
    if (demo || !signedIn) {
      setLive(EMPTY)
      return
    }
    let alive = true
    // Dernier état connu d'abord (2026-11-06), puis le frais ; une panne
    // garde l'ancien plutôt que de vider l'écran.
    const cached = readCache<Parameters<typeof readPass>[0]>(PASS_CACHE, 2 * 24 * 3600_000)?.value
    if (cached) setLive({ loading: false, ...readPass(cached) })
    api.pass
      .state()
      .then((answer) => {
        writeCache(PASS_CACHE, answer)
        if (alive) setLive({ loading: false, ...readPass(answer) })
      })
      .catch(() => alive && !cached && setLive(EMPTY))
    return () => {
      alive = false
    }
  }, [demo, signedIn])

  const reroll = useCallback(
    async (id: string) => {
      if (demo) {
        setDemoRerolled((ids) => [...ids, id])
        return
      }
      try {
        // Le serveur remplace la mission et rend l'état à jour.
        setLive({ loading: false, ...readPass(await api.pass.reroll(Number(id))) })
      } catch (e) {
        showError(e)
      }
    },
    [demo],
  )

  if (demo) {
    const challenges = demo.challenges?.filter((c) => !demoRerolled.includes(c.id)) ?? null
    const meta = { ...DEMO_META, rerollsLeft: Math.max(0, DEMO_META.rerollsLeft - demoRerolled.length) }
    return {
      loading: false,
      challenges: challenges?.map((c) => ({ ...c, rerollable: c.rerollable && meta.rerollsLeft > 0 })) ?? null,
      pass: demo.battlePass,
      season: demo.seasons?.[0] ?? null,
      meta,
      reroll,
    }
  }
  return { ...live, reroll }
}
