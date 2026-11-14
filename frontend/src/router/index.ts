import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../api/request'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import TicketsView from '../views/TicketsView.vue'

/**
 * 路由表。除 /login 外全部需要登录，由全局守卫拦截。
 * 用 hash 模式：开发环境无需额外服务器配置，刷新不丢路由。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: HomeView },
    { path: '/tickets', component: TicketsView },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) {
    return '/login'
  }
  // 已登录用户访问登录页，直接回首页
  if (to.path === '/login' && getToken()) {
    return '/'
  }
  return true
})
