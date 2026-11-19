import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
  // TZ de Brasília: datas civis (vencimentos) não podem voltar um dia como em UTC-3 (NF-e nº 28, 2026-11-19).
  test: { env: { TZ: 'America/Sao_Paulo' }, environment: 'jsdom', setupFiles: './src/test/setup.ts', globals: true, testTimeout: 20000 },
})
