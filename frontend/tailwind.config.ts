import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx,js,jsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'sans-serif'],
        mono: ['JetBrains Mono', 'SF Mono', 'Menlo', 'monospace'],
      },
      colors: {
        background: {
          DEFAULT: '#050608',
        },
        surface: {
          DEFAULT: '#0F0F10',
          elevated: '#0F0F0F',
        },
        accent: {
          DEFAULT: '#38bdf8',
          soft: 'rgba(56,189,248,0.15)',
          glow: 'rgba(56,189,248,0.35)',
        },
        glow: {
          blue: 'rgba(56,189,248,0.35)',
          purple: 'rgba(129,140,248,0.65)',
          amber: 'rgba(251,191,36,0.35)',
          emerald: 'rgba(16,185,129,0.35)',
        },
      },
      boxShadow: {
        glow: '0 0 40px rgba(56,189,248,0.35)',
        'glow-sm': '0 0 20px rgba(56,189,248,0.25)',
        'glow-md': '0 0 40px rgba(56,189,248,0.35)',
        'glow-lg': '0 0 64px rgba(129,140,248,0.65)',
        'glow-amber': '0 0 0 4px rgba(251,191,36,0.35)',
        'glow-emerald': '0 0 0 4px rgba(16,185,129,0.35)',
        'panel': '0 40px 120px rgba(0,0,0,0.85)',
      },
      backdropBlur: {
        xs: '2px',
      },
    },
  },
  plugins: [],
};

export default config;


