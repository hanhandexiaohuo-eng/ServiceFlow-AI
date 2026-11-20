import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { hasSessionHint } from '../api/request'
import { fetchMe } from '../api/auth'
import { MENU_GROUPS } from '../constants/menu'
import AppLayout from '../components/AppLayout.vue'
import LoginView from '../views/LoginView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    group?: string
    desc?: string
    points?: string[]
  }
}

/** 已具备真实能力的页面。除登录与整体布局外全部按路由懒加载，避免首屏加载所有页面与 ECharts */
const realRoutes: RouteRecordRaw[] = [
  { path: '', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '数据驾驶舱', group: '工作台' } },
  { path: 'tickets', name: 'tickets', component: () => import('../views/TicketsView.vue'), meta: { title: '工单列表', group: '工单中心' } },
  { path: 'tickets/board', name: 'ticket-board', component: () => import('../views/TicketBoardView.vue'), meta: { title: '工单看板', group: '工单中心' } },
  { path: 'tickets/mine', name: 'ticket-mine', component: () => import('../views/MyTicketsView.vue'), meta: { title: '我的工单', group: '工单中心' } },
  { path: 'tickets/:id(\\d+)', name: 'ticket-detail', component: () => import('../views/TicketDetailView.vue'), meta: { title: '工单详情', group: '工单中心' } },
  { path: 'workflow', name: 'workflow-overview', component: () => import('../views/WorkflowOverviewView.vue'), meta: { title: '工作流总览', group: '工作流' } },
  { path: 'workflow/sla', name: 'sla-management', component: () => import('../views/SlaManagementView.vue'), meta: { title: 'SLA 管理', group: '工作流' } },
  { path: 'dispatch', name: 'dispatch-workbench', component: () => import('../views/DispatchWorkbenchView.vue'), meta: { title: '调度工作台', group: '智能调度' } },
  { path: 'dispatch/agents', name: 'agent-management', component: () => import('../views/AgentManagementView.vue'), meta: { title: '人员管理', group: '智能调度' } },
  { path: 'dispatch/workload', name: 'agent-workload', component: () => import('../views/AgentWorkloadView.vue'), meta: { title: '人员负载', group: '智能调度' } },
  { path: 'dispatch/history', name: 'dispatch-history', component: () => import('../views/DispatchHistoryView.vue'), meta: { title: '调度历史', group: '智能调度' } },
  { path: 'ai/workspace', name: 'ai-workspace', component: () => import('../views/AiWorkspaceView.vue'), meta: { title: 'AI 工作空间', group: 'AI 智能中心' } },
  { path: 'ai/history', name: 'ai-history', component: () => import('../views/AiHistoryView.vue'), meta: { title: 'AI 分析历史', group: 'AI 智能中心' } },
  { path: 'ai/analyses/:id(\\d+)', name: 'ai-analysis-detail', component: () => import('../views/AiAnalysisDetailView.vue'), meta: { title: 'AI 分析详情', group: 'AI 智能中心' } },
  { path: 'knowledge', name: 'knowledge-base', component: () => import('../views/KnowledgeBaseView.vue'), meta: { title: '知识库', group: '知识库' } },
  { path: 'system/monitor', name: 'system-monitor', component: () => import('../views/SystemMonitorView.vue'), meta: { title: '系统监控', group: '系统管理' } },
  { path: 'system/users', name: 'system-users', component: () => import('../views/SystemUsersView.vue'), meta: { title: '用户管理', group: '系统管理' } },
  { path: 'system/ai', name: 'system-ai', component: () => import('../views/SystemAiView.vue'), meta: { title: 'AI 模型管理', group: '系统管理' } },
  { path: 'system/logs', name: 'system-logs', component: () => import('../views/SystemLogsView.vue'), meta: { title: '操作日志', group: '系统管理' } },
  { path: 'analytics/tickets', name: 'analytics-tickets', component: () => import('../views/analytics/TicketOperationsView.vue'), meta: { title: '工单运营分析', group: '数据分析' } },
  { path: 'analytics/sla', name: 'analytics-sla', component: () => import('../views/analytics/SlaEfficiencyView.vue'), meta: { title: 'SLA 效率分析', group: '数据分析' } },
  { path: 'analytics/agents', name: 'analytics-agents', component: () => import('../views/analytics/AgentPerformanceView.vue'), meta: { title: '人员绩效分析', group: '数据分析' } },
  { path: 'analytics/dispatch', name: 'analytics-dispatch', component: () => import('../views/analytics/DispatchAnalyticsView.vue'), meta: { title: '智能调度分析', group: '数据分析' } },
  { path: 'analytics/ai', name: 'analytics-ai', component: () => import('../views/analytics/AiAnalyticsView.vue'), meta: { title: 'AI 效果分析', group: '数据分析' } },
  { path: 'analytics/reports', name: 'analytics-reports', component: () => import('../views/analytics/ReportCenterView.vue'), meta: { title: '综合报表中心', group: '数据分析' } },
]

/** 尚未接入后端能力的菜单项：由菜单配置直接生成，保证「点击必有页面」 */
const soonRoutes: RouteRecordRaw[] = MENU_GROUPS.flatMap((group) =>
  group.items
    .filter((item) => item.soon)
    .map((item) => ({
      path: item.to.replace(/^\//, ''),
      name: `soon-${item.to.replace(/\//g, '-')}`,
      component: () => import('../views/ComingSoonView.vue'),
      meta: { title: item.label, group: group.name, desc: item.desc, points: item.points },
    }))
)

// 兼容旧版 hash 链接（如 /#/system/users）：先归一化路径，再启用 history 路由
if (window.location.hash.startsWith('#/')) {
  window.history.replaceState(null, '', window.location.hash.slice(1))
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('../views/ChangePasswordView.vue'),
      meta: { title: '修改密码' },
    },
    { path: '/', component: AppLayout, children: [...realRoutes, ...soonRoutes] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (to.path === '/login') {
    return hasSessionHint() ? '/' : true
  }
  if (hasSessionHint()) {
    return true
  }
  // 本地无用户缓存时，尝试用仍然有效的 Cookie 会话恢复（避免误判未登录）
  try {
    await fetchMe()
    return true
  } catch {
    return {
      path: '/login',
      query: to.fullPath === '/' ? undefined : { redirect: to.fullPath },
    }
  }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ServiceFlow AI` : 'ServiceFlow AI'
})
