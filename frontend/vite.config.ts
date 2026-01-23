import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false, // 端口被占用时自动选择下一个可用端口
  },
  optimizeDeps: {
    // 排除 react-window 的预构建，让它直接使用原始模块
    exclude: ['react-window'],
    esbuildOptions: {
      // 确保正确处理 CommonJS 模块
      mainFields: ['module', 'main'],
    },
  },
})
