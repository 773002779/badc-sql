import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

/** 本地开发把 /api 代理到控制面，避免浏览器跨域；生产由网关/反代做同源。 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  server: {
    port: 5173,
    // 编辑器/工具写文件时会留下 .xxx.tmpdir 临时目录，Vite 监听到会因 EBUSY 崩掉
    watch: { ignored: ['**/.*.tmpdir/**', '**/.git/**', '**/dist/**'] },
    proxy: {
      '/api': {
        target: process.env.BADCSQL_API ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
          query: ['@tanstack/react-query'],
          codemirror: [
            'codemirror',
            '@codemirror/lang-sql',
            '@codemirror/theme-one-dark',
          ],
        },
      },
    },
  },
});
