import { useMemo } from 'react'
import { STATUS_COLORS, liveCustom } from '@/lib/friends'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import type { FriendPresence, MinecraftAccount, MyProfile, PublicProfile, ShowcaseView } from '@/types/friends'
import { FriendAvatar } from './FriendBits'
import { ProfileDetails, ThoughtBubble, useMyServer } from './FriendProfile'
import { ProfileLayout } from './ProfileLayout'

/**
 * Ma présence telle que mes amis la reçoivent, refaite ici sans attendre le
 * serveur : mon statut (invisible = hors ligne), et ce que je fais si je le
 * montre — la partie en cours, son serveur si je montre mon serveur.
 */
export function useMyPresence(): FriendPresence {
  const me = useFriends((s) => s.list?.me)
  const settings = useFriends((s) => s.list?.settings)
  const running = useStore((s) => s.runningInstances)
  const instances = useStore((s) => s.instances)
  const server = useMyServer(running.length > 0)
  const instance = running.length > 0 ? instances.find((i) => i.id === running[0]) ?? null : null

  const shows = !!settings?.server_visibility && settings.server_visibility !== 'nobody'
  const shown = me?.shown ?? me?.status ?? 'online'
  const state = shown === 'invisible' ? 'offline' : shown
  const custom = me?.custom ?? null
  const activity: FriendPresence['activity'] =
    state === 'offline' || settings?.activity_visibility === 'nobody'
      ? null
      : instance
        ? {
            kind: 'game',
            mc_version: instance.mc_version,
            loader: instance.loader,
            duel: null,
            server: shows ? server : null,
          }
        : { kind: 'launcher' }
  return { state, custom, activity, last_seen: null, server_hidden: activity?.kind === 'game' && !shows && server !== null }
}

/** Mes comptes Minecraft, le principal en tête (comme les reçoivent mes amis). */
export function myAccounts(profile: MyProfile): MinecraftAccount[] {
  const main = profile.main_mc_uuid ?? profile.accounts[0]?.uuid ?? null
  return [...profile.accounts]
    .map((a) => ({ ...a, main: a.uuid === main }))
    .sort((a, b) => Number(b.main) - Number(a.main))
}

/** Mon Discord lié, tel que mes amis le voient : le pseudo seulement si je le montre. */
export function useMyDiscord(): PublicProfile['discord'] {
  const linked = useFriends((s) => s.discord?.linked ?? null)
  return linked ? { username: linked.show_to_friends ? linked.username : null } : null
}

/**
 * Ma fiche telle que la verra un ami, de la bannière aux « amis depuis » :
 * même mise en page et mêmes blocs que la fiche d'un ami (`ProfileDetails`),
 * rien ne réagit. Ce que je réserve à moi seul (`visibility: 'me'`) n'y est
 * pas — c'est ce qu'un ami ne voit pas. La date « amis depuis » est
 * aujourd'hui : celle d'un ami ajouté à l'instant.
 */
export function ProfilePreview({ draft, showcase, username }: { draft: MyProfile; showcase: ShowcaseView[]; username: string }) {
  const presence = useMyPresence()
  const discord = useMyDiscord()
  const accounts = useMemo(() => myAccounts(draft), [draft])
  const today = useMemo(() => new Date().toISOString(), [])
  const bannerShown = draft.banner_visibility !== 'me'
  const custom = liveCustom(presence)
  const bio = draft.bio_visibility !== 'me' && !draft.bio_hidden ? draft.bio : null
  return (
    <ProfileLayout
      banner={bannerShown ? draft.banner : null}
      accent={bannerShown ? draft.accent : null}
      color={STATUS_COLORS[presence.state]}
      avatar={<FriendAvatar skin={`https://mc-heads.net/skin/${accounts[0]?.uuid ?? 'MHF_Steve'}`} state={presence.state} size={72} />}
      bubble={custom ? <ThoughtBubble accent={bannerShown ? draft.accent : null}>{custom}</ThoughtBubble> : null}
      name={username}
    >
      {bio && <p className="whitespace-pre-line px-4 pt-1.5 text-[12.5px] leading-relaxed text-txt-secondary [overflow-wrap:anywhere]">{bio}</p>}
      <ProfileDetails
        presence={presence}
        showcase={draft.showcase_visibility !== 'me' ? showcase : []}
        discord={discord}
        accounts={accounts}
        since={today}
        friendId={null}
      />
    </ProfileLayout>
  )
}
