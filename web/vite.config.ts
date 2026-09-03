import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

// ADR-0009: one static bundle - index.html, one JS file, one CSS file, no
// external requests. The phone serves this directory over plain HTTP from a LAN
// IP (ADR-0006), so there is no secure context and no CDN to reach.
export default defineConfig({
  plugins: [preact()],
  base: './',
  build: {
    // Inline every asset so the bundle never issues a second request.
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        entryFileNames: 'app.js',
        assetFileNames: 'app.[ext]',
      },
    },
  },
})
