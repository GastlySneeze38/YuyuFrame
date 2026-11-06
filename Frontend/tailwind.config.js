/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{ts,tsx}', './index.html'],
  // `hover:` ne s'applique qu'avec une souris : `@media (hover: hover) and
  // (pointer: fine)`. Au doigt, le navigateur simule un survol au premier
  // toucher et le laisse collé jusqu'au toucher suivant — un bouton restait
  // éclairci après avoir été touché. Rien ne change avec une souris.
  future: {
    hoverOnlyWhenSupported: true,
  },
  theme: {
    extend: {
      // Le launcher est une fenêtre redimensionnable, pas une page web : sa
      // hauteur descend très bas et c'est elle, pas la largeur, qui contraint
      // le panneau de lancement (titre, avatar, sélecteur, bouton empilés).
      // Les clamp(vh) rétrécissent bien chaque élément, mais rétrécir ne
      // suffit plus en dessous d'un certain point — il faut en retirer. D'où
      // ce palier, exprimé en requête brute puisque Tailwind ne propose que
      // des paliers de largeur.
      // Volontairement bas : au-dessus, tout tient déjà en rétrécissant, et
      // faire disparaître un bloc alors qu'il reste de la place se voit comme
      // un saut. Ce palier n'est franchi que là où la place manque vraiment.
      // Il est déclaré comme variant (plugin en bas de fichier) et non dans
      // `screens` : un palier `raw` dans `screens` fait désactiver par
      // Tailwind tous les variants `max-sm:`, `max-md:`… — les retouches
      // « téléphone » écrites ainsi étaient ignorées sans erreur.
      // Toutes les couleurs viennent des jetons de src/index.css : aucun
      // `rgba(...)` en dur dans les écrans (voir l'audit des interfaces).
      colors: {
        bg: {
          primary: 'rgb(var(--bg-primary) / <alpha-value>)',
          secondary: 'rgb(var(--bg-secondary) / <alpha-value>)',
          card: 'rgb(var(--bg-card) / <alpha-value>)',
          raised: 'rgb(var(--bg-raised) / <alpha-value>)',
        },
        // Surfaces empilées (cartes, champs, survols) : l'opacité est déjà
        // dans le jeton, d'où `rgb(var(--…))` sans <alpha-value>.
        surface: {
          1: 'rgb(var(--surface-1))',
          2: 'rgb(var(--surface-2))',
          3: 'rgb(var(--surface-3))',
          4: 'rgb(var(--surface-4))',
        },
        // Trois niveaux de trait plutôt qu'un suffixe d'opacité : ces jetons
        // portent déjà leur alpha, donc `border-line/60` donnerait une
        // couleur invalide (et un trait blanc à l'écran).
        line: {
          soft: 'rgb(var(--line-soft))',
          DEFAULT: 'rgb(var(--line))',
          strong: 'rgb(var(--line-strong))',
        },
        accent: {
          DEFAULT: 'rgb(var(--accent) / <alpha-value>)',
          hover: 'rgb(var(--accent-hover) / <alpha-value>)',
          text: 'rgb(var(--accent-text) / <alpha-value>)',
        },
        txt: {
          primary: 'rgb(var(--txt-primary) / <alpha-value>)',
          secondary: 'rgb(var(--txt-secondary))',
          muted: 'rgb(var(--txt-muted))',
        },
        success: 'rgb(var(--success) / <alpha-value>)',
        warning: 'rgb(var(--warning) / <alpha-value>)',
        danger: 'rgb(var(--danger) / <alpha-value>)',
        // Ancien jeton, le temps de convertir les écrans un par un.
        border: 'rgb(var(--border) / <alpha-value>)',
      },
      transitionTimingFunction: {
        out: 'var(--ease-out)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      animation: {
        'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'spin-slow': 'spin 2s linear infinite',
        'float': 'float 5s ease-in-out infinite',
        'float-slow': 'float 8s ease-in-out infinite',
        'banner-flash': 'bannerFlash 1.1s ease-out forwards',
        'fade-in-up': 'fadeInUp 0.5s cubic-bezier(0.16, 1, 0.3, 1) both',
        'grow-x': 'growX 0.6s cubic-bezier(0.16, 1, 0.3, 1) both',
        'grow-y': 'growY 0.6s cubic-bezier(0.16, 1, 0.3, 1) both',
      },
      keyframes: {
        float: {
          '0%, 100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-10px)' },
        },
        fadeInUp: {
          '0%': { opacity: '0', transform: 'translateY(10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        growX: {
          '0%': { transform: 'scaleX(0)' },
          '100%': { transform: 'scaleX(1)' },
        },
        growY: {
          '0%': { transform: 'scaleY(0)' },
          '100%': { transform: 'scaleY(1)' },
        },
        bannerFlash: {
          '0%': { opacity: '0' },
          '12%': { opacity: '1' },
          '100%': { opacity: '0' },
        },
      },
    },
  },
  plugins: [
    // Requêtes de conteneur : une carte s'adapte à SA place, pas à la
    // fenêtre (la même carte est large seule et étroite entre deux colonnes).
    // Le parent porte `[container-type:inline-size]` (largeur) ou
    // `[container-type:size]` (largeur et hauteur, s'il a une hauteur fixée).
    //   cw-sm / cw-md / cw-lg : largeur du conteneur ≥ 380 / 520 / 680 px
    //   ch-md / ch-lg        : hauteur du conteneur ≥ 260 / 340 px
    require('tailwindcss/plugin')(({ addVariant }) => {
      addVariant('cw-sm', '@container (min-width: 380px)')
      addVariant('cw-md', '@container (min-width: 520px)')
      addVariant('cw-lg', '@container (min-width: 680px)')
      addVariant('ch-md', '@container (min-height: 260px)')
      addVariant('ch-lg', '@container (min-height: 340px)')
      // Fenêtre basse (voir le commentaire de `theme.extend`).
      addVariant('short', '@media (max-height: 600px)')
    }),
  ],
}
