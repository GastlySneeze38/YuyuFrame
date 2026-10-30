import { useFriends } from '@/stores/useFriends'

/**
 * Suis-je en « Ne pas déranger » ? Alors rien ne m'interrompt : ni notice
 * d'ami (en ligne, demande, nouvel ami), ni modale du support ou d'un rapport
 * de plantage. Les pastilles, elles, restent — on retrouve tout en revenant.
 *
 * Les notifications Windows suivent la même règle côté Rust
 * (`Backend/src/friends/notify.rs`), qui connaît mon statut par la liste
 * d'amis et le flux.
 */
export function doNotDisturb(): boolean {
  return useFriends.getState().list?.me.status === 'dnd'
}
