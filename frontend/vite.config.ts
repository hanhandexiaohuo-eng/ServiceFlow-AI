import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // 代理目标来自 VITE_PROXY_TARGET，不在代码中写死端口
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [vue()],
    server: {
      // 显式绑定 IPv4，避免 Windows 上 localhost 只解析到 ::1 导致 127.0.0.1 访问失败
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      // 把框架与图表库拆成独立长缓存 chunk，业务页面按路由懒加载
      rollupOptions: {
        output: {
          manualChunks(id: string) {
            if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) {
              return 'echarts'
            }
            if (id.includes('node_modules/vue') || id.includes('node_modules/@vue')) {
              return 'vue'
            }
            return undefined
          },
        },
      },
    },
  }
})
