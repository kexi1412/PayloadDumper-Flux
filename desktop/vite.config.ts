import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  esbuild: { jsx: 'automatic' },
  build: { target: 'chrome140', outDir: 'dist', sourcemap: false },
  server: { host: '127.0.0.1', strictPort: true, port: 5173 },
});
