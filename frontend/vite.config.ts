import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 关键：使用相对路径，确保 Electron 打包后能正确加载资源
  base: './',
  server: {
    port: 5173,
    strictPort: false, // 端口被占用时自动选择下一个可用端口
  },
  build: {
    // 确保打包输出使用相对路径
    assetsDir: 'assets',
    // 生成 sourcemap 便于调试
    sourcemap: false,
    rollupOptions: {
      output: {
        // 确保资源文件名不包含绝对路径
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',
      },
    },
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
