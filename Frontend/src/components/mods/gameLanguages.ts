import type { FlagSpec } from '@/components/ui/Flag'

/**
 * Langues de Minecraft proposées dans le sélecteur.
 *
 * Le jeu en livre plus de cent trente, dont beaucoup de variantes régionales
 * et quelques blagues (pirate, alien). Les lister toutes ferait une fenêtre
 * qu'on ne parcourt pas. Celles-ci sont les langues réellement jouées, et le
 * code reste saisissable à la main dans la vue avancée pour tout le reste —
 * une valeur absente de cette liste est d'ailleurs affichée telle quelle
 * plutôt qu'écrasée (voir LanguagePickerModal).
 *
 * `code` est la valeur exacte attendue par `options.txt` des versions
 * modernes : minuscules, tiret bas. `english` ne sert qu'à la recherche — on
 * tape souvent « german » en ayant l'interface en français.
 */

export interface GameLanguage {
  code: string
  /** Nom dans sa propre langue, tel qu'affiché. */
  native: string
  /** Nom anglais, cherchable et non affiché. */
  english: string
  flag: FlagSpec
}

// Couleurs reprises des spécifications officielles quand elles existent.
const FR: FlagSpec = { type: 'v', colors: ['#002395', '#ffffff', '#ED2939'] }
const ES: FlagSpec = { type: 'h', colors: ['#AA151B', '#F1BF00', '#F1BF00', '#AA151B'] }
const PT: FlagSpec = { type: 'v', colors: ['#046A38', '#046A38', '#DA291C'] }
const NL: FlagSpec = { type: 'h', colors: ['#AE1C28', '#ffffff', '#21468B'] }
const RU: FlagSpec = { type: 'h', colors: ['#ffffff', '#0039A6', '#D52B1E'] }

export const GAME_LANGUAGES: GameLanguage[] = [
  { code: 'fr_fr', native: 'Français', english: 'French', flag: FR },
  { code: 'fr_ca', native: 'Français canadien', english: 'Canadian French', flag: { type: 'v', colors: ['#D80621', '#ffffff', '#D80621'] } },
  { code: 'en_us', native: 'English (US)', english: 'English United States', flag: { type: 'canton', stripes: ['#B22234', '#ffffff', '#B22234', '#ffffff', '#B22234', '#ffffff', '#B22234'], canton: '#3C3B6E', w: 1.2, h: 1.14 } },
  { code: 'en_gb', native: 'English (UK)', english: 'English British', flag: { type: 'union' } },
  { code: 'de_de', native: 'Deutsch', english: 'German', flag: { type: 'h', colors: ['#000000', '#DD0000', '#FFCE00'] } },
  { code: 'es_es', native: 'Español', english: 'Spanish', flag: ES },
  { code: 'es_mx', native: 'Español de México', english: 'Mexican Spanish', flag: { type: 'v', colors: ['#006847', '#ffffff', '#CE1126'] } },
  { code: 'it_it', native: 'Italiano', english: 'Italian', flag: { type: 'v', colors: ['#008C45', '#F4F5F0', '#CD212A'] } },
  { code: 'pt_pt', native: 'Português', english: 'Portuguese', flag: PT },
  { code: 'pt_br', native: 'Português do Brasil', english: 'Brazilian Portuguese', flag: { type: 'disc', bg: '#009C3B', disc: '#FFDF00' } },
  { code: 'nl_nl', native: 'Nederlands', english: 'Dutch', flag: NL },
  { code: 'pl_pl', native: 'Polski', english: 'Polish', flag: { type: 'h', colors: ['#ffffff', '#DC143C'] } },
  { code: 'ru_ru', native: 'Русский', english: 'Russian', flag: RU },
  { code: 'uk_ua', native: 'Українська', english: 'Ukrainian', flag: { type: 'h', colors: ['#0057B7', '#FFDD00'] } },
  { code: 'cs_cz', native: 'Čeština', english: 'Czech', flag: { type: 'wedge', colors: ['#ffffff', '#D7141A'], wedge: '#11457E' } },
  { code: 'sk_sk', native: 'Slovenčina', english: 'Slovak', flag: { type: 'h', colors: ['#ffffff', '#0B4EA2', '#EE1C25'] } },
  { code: 'hu_hu', native: 'Magyar', english: 'Hungarian', flag: { type: 'h', colors: ['#CE2939', '#ffffff', '#477050'] } },
  { code: 'ro_ro', native: 'Română', english: 'Romanian', flag: { type: 'v', colors: ['#002B7F', '#FCD116', '#CE1126'] } },
  { code: 'bg_bg', native: 'Български', english: 'Bulgarian', flag: { type: 'h', colors: ['#ffffff', '#00966E', '#D62612'] } },
  { code: 'el_gr', native: 'Ελληνικά', english: 'Greek', flag: { type: 'canton', stripes: ['#0D5EAF', '#ffffff', '#0D5EAF', '#ffffff', '#0D5EAF'], canton: '#0D5EAF', w: 1.1, h: 1.2 } },
  { code: 'tr_tr', native: 'Türkçe', english: 'Turkish', flag: { type: 'disc', bg: '#E30A17', disc: '#ffffff', cx: 1.1 } },
  { code: 'sv_se', native: 'Svenska', english: 'Swedish', flag: { type: 'cross', bg: '#006AA7', cross: '#FECC00' } },
  { code: 'nb_no', native: 'Norsk bokmål', english: 'Norwegian', flag: { type: 'cross', bg: '#BA0C2F', cross: '#ffffff', inner: '#00205B' } },
  { code: 'da_dk', native: 'Dansk', english: 'Danish', flag: { type: 'cross', bg: '#C8102E', cross: '#ffffff' } },
  { code: 'fi_fi', native: 'Suomi', english: 'Finnish', flag: { type: 'cross', bg: '#ffffff', cross: '#003580' } },
  { code: 'is_is', native: 'Íslenska', english: 'Icelandic', flag: { type: 'cross', bg: '#02529C', cross: '#ffffff', inner: '#DC1E35' } },
  { code: 'ja_jp', native: '日本語', english: 'Japanese', flag: { type: 'disc', bg: '#ffffff', disc: '#BC002D' } },
  { code: 'ko_kr', native: '한국어', english: 'Korean', flag: { type: 'disc', bg: '#ffffff', disc: '#CD2E3A' } },
  { code: 'zh_cn', native: '简体中文', english: 'Simplified Chinese', flag: { type: 'canton', stripes: ['#EE1C25'], canton: '#FFFF00', w: 0.42, h: 0.42 } },
  { code: 'zh_tw', native: '繁體中文', english: 'Traditional Chinese', flag: { type: 'canton', stripes: ['#FE0000'], canton: '#000095', w: 1.5, h: 1 } },
  { code: 'vi_vn', native: 'Tiếng Việt', english: 'Vietnamese', flag: { type: 'disc', bg: '#DA251D', disc: '#FFFF00' } },
  { code: 'th_th', native: 'ไทย', english: 'Thai', flag: { type: 'h', colors: ['#A51931', '#F4F5F8', '#2D2A4A', '#F4F5F8', '#A51931'] } },
  { code: 'id_id', native: 'Bahasa Indonesia', english: 'Indonesian', flag: { type: 'h', colors: ['#CE1126', '#ffffff'] } },
  { code: 'ms_my', native: 'Bahasa Melayu', english: 'Malay', flag: { type: 'h', colors: ['#CC0001', '#ffffff', '#CC0001', '#ffffff'] } },
  { code: 'ar_sa', native: 'العربية', english: 'Arabic', flag: { type: 'h', colors: ['#006C35'] } },
  { code: 'he_il', native: 'עברית', english: 'Hebrew', flag: { type: 'h', colors: ['#ffffff', '#0038B8', '#ffffff', '#0038B8', '#ffffff'] } },
  { code: 'hi_in', native: 'हिन्दी', english: 'Hindi', flag: { type: 'h', colors: ['#FF9933', '#ffffff', '#138808'] } },
  { code: 'ca_es', native: 'Català', english: 'Catalan', flag: { type: 'h', colors: ['#FCDD09', '#DA121A', '#FCDD09', '#DA121A', '#FCDD09'] } },
  { code: 'et_ee', native: 'Eesti', english: 'Estonian', flag: { type: 'h', colors: ['#0072CE', '#000000', '#ffffff'] } },
  { code: 'lt_lt', native: 'Lietuvių', english: 'Lithuanian', flag: { type: 'h', colors: ['#FDB913', '#006A44', '#C1272D'] } },
  { code: 'lv_lv', native: 'Latviešu', english: 'Latvian', flag: { type: 'h', colors: ['#9E3039', '#ffffff', '#9E3039'] } },
]

export const LANGUAGE_BY_CODE = new Map(GAME_LANGUAGES.map((l) => [l.code, l]))
