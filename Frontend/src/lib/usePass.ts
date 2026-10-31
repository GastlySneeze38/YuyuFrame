import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { demoData, readPass, type BattlePass, type Challenge, type Season } from '@/lib/competitive'
import { useDemo } from '@/stores/useDevFlags'
import { useStore } from '@/stores/useStore'

export interface PassData {
  loading: boolean
  /** `null` : rien à montrer (« bientôt ») — pas de route, pas de mission, pas de saison. */
  challenges: Challenge[] | null
  pass: BattlePass | null
  season: Season | null
}

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
  const [live, setLive] = useState<PassData>({ loading: true, challenges: null, pass: null, season: null })

  useEffect(() => {
    if (demo || !signedIn) {
      setLive({ loading: false, challenges: null, pass: null, season: null })
      return
    }
    let alive = true
    api.pass
      .state()
      .then((answer) => alive && setLive({ loading: false, ...readPass(answer) }))
      .catch(() => alive && setLive({ loading: false, challenges: null, pass: null, season: null }))
    return () => {
      alive = false
    }
  }, [demo, signedIn])

  if (demo) return { loading: false, challenges: demo.challenges, pass: demo.battlePass, season: demo.seasons?.[0] ?? null }
  return live
}
