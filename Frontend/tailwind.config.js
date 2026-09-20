/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{ts,tsx}', './index.html'],
  theme: {
    extend: {
      // Toutes les couleurs viennent des jetons de src/index.css : aucun
      // `rgba(...)` en dur dans les écrans (voir l'audit des interfaces).
      colors: {
        bg: {
          primary: 'rgb(var(--bg-primary) / <alpha-value>)',
          secondary: 'rgb(var(--bg-secondary) / <alpha-value>)',
          card: 'rgb(var(--bg-card) / <alpha-value>)',
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
        'banner-glow': 'bannerGlow 2s ease-in-out infinite',
        'terrain-float': 'terrainFloat 3s ease-in-out infinite',
        'star-pulse': 'starPulse 2s ease-in-out infinite',
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
        bannerGlow: {
          '0%, 100%': { opacity: '0.2' },
          '50%': { opacity: '1' },
        },
        terrainFloat: {
          '0%, 100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-14px)' },
        },
        starPulse: {
          '0%, 100%': { filter: 'brightness(0.6)' },
          '50%': { filter: 'brightness(5)' },
        },
      },
    },
  },
  plugins: [],
}
