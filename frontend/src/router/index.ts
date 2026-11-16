import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '../api/request'
import { MENU_GROUPS } from '../constants/menu'
import AppLayout from '../components/AppLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import TicketsView from '../views/TicketsView.vue'
import TicketBoardView from '../views/TicketBoardView.vue'
import MyTicketsView from '../views/MyTicketsView.vue'
import TicketDetailView from '../views/TicketDetailView.vue'
import SystemMonitorView from '../views/SystemMonitorView.vue'
import ComingSoonView from '../views/ComingSoonView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    group?: string
    desc?: string
    points?: string[]
  }
}

/** 已具备真实能力的页面 */
const realRoutes: RouteRecordRaw[] = [
  { path: '', name: 'dashboard', component: DashboardView, meta: { title: '数据驾驶舱', group: '工作台' } },
  { path: 'tickets', name: 'tickets', component: TicketsView, meta: { title: '工单列表', group: '工单中心' } },
  { path: 'tickets/board', name: 'ticket-board', component: TicketBoardView, meta: { title: '工单看板', group: '工单中心' } },
  { path: 'tickets/mine', name: 'ticket-mine', component: MyTicketsView, meta: { title: '我的工单', group: '工单中心' } },
  { path: 'tickets/:id(\\d+)', name: 'ticket-detail', component: TicketDetailView, meta: { title: '工单详情', group: '工单中心' } },
  { path: 'system/monitor', name: 'system-monitor', component: SystemMonitorView, meta: { title: '系统监控', group: '系统管理' } },
]

/** 尚未接入后端能力的菜单项：由菜单配置直接生成，保证「点击必有页面」 */
const soonRoutes: RouteRecordRaw[] = MENU_GROUPS.flatMap((group) =>
  group.items
    .filter((item) => item.soon)
    .map((item) => ({
      path: item.to.replace(/^\//, ''),
      name: `soon-${item.to.replace(/\//g, '-')}`,
      component: ComingSoonView,
      meta: { title: item.label, group: group.name, desc: item.desc, points: item.points },
    }))
)

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/', component: AppLayout, children: [...realRoutes, ...soonRoutes] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) {
    return { path: '/login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (to.path === '/login' && getToken()) {
    return '/'
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ServiceFlow AI` : 'ServiceFlow AI'
})
