/** 左侧导航配置：同时作为 ComingSoon 路由的数据来源，保证菜单与路由不脱节 */
export interface MenuItem {
  label: string
  to: string
  icon: string
  /** true 表示页面尚未接入后端能力，点击进入「待接入」说明页 */
  soon?: boolean
  /** 对应的功能开关（见 features.ts）；关闭时菜单隐藏、直链显示未启用 */
  feature?: string
  /** 仅管理员可见的入口；非管理员不展示，直链由页面二次校验 */
  adminOnly?: boolean
  desc?: string
  points?: string[]
}

export interface MenuGroup {
  name: string
  items: MenuItem[]
}

export const MENU_GROUPS: MenuGroup[] = [
  {
    name: '工作台',
    items: [{ label: '数据驾驶舱', to: '/', icon: 'dashboard' }],
  },
  {
    name: '工单中心',
    items: [
      { label: '工单列表', to: '/tickets', icon: 'tickets' },
      { label: '工单看板', to: '/tickets/board', icon: 'board' },
      { label: '我的工单', to: '/tickets/mine', icon: 'myTicket' },
    ],
  },
  {
    name: '工作流',
    items: [
      {
        label: '工作流总览',
        to: '/workflow',
        icon: 'workflow',
        desc: '可视化编排工单流转节点与审批链路，替代当前固定状态机。',
        points: ['节点编排与条件分支', '自动分配规则配置', '流转记录追踪'],
      },
      {
        label: 'SLA 管理',
        to: '/workflow/sla',
        icon: 'clock',
        desc: '定义响应与解决时限、升级策略，是「超时工单」指标的数据来源。',
        points: ['SLA 策略配置', '超时升级提醒', '达标率统计'],
      },
    ],
  },
  {
    name: '智能调度',
    items: [
      { label: '调度工作台', to: '/dispatch', icon: 'route' },
      { label: '人员管理', to: '/dispatch/agents', icon: 'users' },
      { label: '人员负载', to: '/dispatch/workload', icon: 'gauge' },
      { label: '调度历史', to: '/dispatch/history', icon: 'logs' },
    ],
  },
  {
    name: 'AI 智能中心',
    items: [
      { label: 'AI 工作空间', to: '/ai/workspace', icon: 'sparkles' },
      { label: '分析历史', to: '/ai/history', icon: 'logs' },
    ],
  },
  {
    name: '数据分析',
    items: [
      { label: '工单运营分析', to: '/analytics/tickets', icon: 'chart' },
      { label: 'SLA 效率分析', to: '/analytics/sla', icon: 'clock' },
      { label: '人员绩效分析', to: '/analytics/agents', icon: 'award' },
      { label: '智能调度分析', to: '/analytics/dispatch', icon: 'route' },
      { label: 'AI 效果分析', to: '/analytics/ai', icon: 'sparkles' },
      { label: '综合报表中心', to: '/analytics/reports', icon: 'logs' },
    ],
  },
  {
    name: '知识库',
    items: [{ label: '知识文章', to: '/knowledge', icon: 'book' }],
  },
  {
    name: '系统管理',
    items: [
      {
        label: '用户管理',
        to: '/system/users',
        icon: 'users',
        feature: 'systemUsers',
        adminOnly: true,
      },
      { label: '系统监控', to: '/system/monitor', icon: 'monitor' },
      {
        label: 'AI 模型管理',
        to: '/system/ai',
        icon: 'sparkles',
        feature: 'systemAi',
        adminOnly: true,
      },
      {
        label: '操作日志',
        to: '/system/logs',
        icon: 'logs',
        feature: 'systemLogs',
        adminOnly: true,
      },
    ],
  },
]
