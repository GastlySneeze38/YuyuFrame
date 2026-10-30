// Les bannières de profil que le launcher sait dessiner. La liste des
// identifiants est la même que `BANNERS` côté serveur
// (Server/LauncherAPI/src/launcher/friends/profile.rs) : une bannière se
// rajoute aux deux endroits. Ce sont des dégradés et non des images envoyées
// par les joueurs : rien à modérer.

export const BANNERS: Record<string, string> = {
  aurora: 'radial-gradient(120% 140% at 15% 0%, #3ee6b8aa, transparent 60%), radial-gradient(90% 120% at 90% 20%, #7b72e9cc, transparent 60%), linear-gradient(135deg, #10233a, #1b1440)',
  sunset: 'linear-gradient(160deg, #ff8a5c 0%, #e8487a 45%, #5b2a86 100%)',
  ocean: 'radial-gradient(100% 120% at 80% 0%, #5ad1ff99, transparent 60%), linear-gradient(160deg, #0c4a6e, #0b1f3a)',
  forest: 'radial-gradient(90% 120% at 20% 0%, #8ee06f88, transparent 60%), linear-gradient(160deg, #1f5b3a, #0f2418)',
  nether: 'radial-gradient(90% 120% at 70% 100%, #ff7a2aaa, transparent 60%), linear-gradient(160deg, #6b1414, #2a0707)',
  end: 'radial-gradient(80% 110% at 30% 20%, #d7c6ff55, transparent 60%), linear-gradient(160deg, #2a1d4a, #0d0a17)',
  night: 'radial-gradient(60% 80% at 80% 10%, #f5f3ff33, transparent 60%), linear-gradient(180deg, #0b1230, #05060f)',
  candy: 'linear-gradient(135deg, #ff9ad5 0%, #b28dff 50%, #7fd3ff 100%)',
  gold: 'radial-gradient(90% 120% at 25% 0%, #fff2b366, transparent 60%), linear-gradient(150deg, #c9922e, #5a3b0c)',
  mono: 'linear-gradient(160deg, #3a3a44, #141418)',
}

export const BANNER_IDS = Object.keys(BANNERS)

/** Couleurs d'accent proposées (le serveur accepte n'importe quel #rrggbb). */
export const ACCENTS = ['#7b72e9', '#3ba55d', '#5ad1ff', '#f5b74a', '#ff6b8b', '#e8487a', '#b28dff', '#e5e7eb']

/** Le fond du bandeau : la bannière choisie, sinon un dégradé de la couleur donnée. */
export function bannerBackground(banner: string | null, fallbackColor: string): string {
  if (banner && BANNERS[banner]) return BANNERS[banner]
  return `linear-gradient(135deg, ${fallbackColor}66, ${fallbackColor}14 75%)`
}
