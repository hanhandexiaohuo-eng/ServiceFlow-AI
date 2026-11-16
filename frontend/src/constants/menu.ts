/** 左侧导航配置：同时作为 ComingSoon 路由的数据来源，保证菜单与路由不脱节 */
export interface MenuItem {
  label: string
  to: string
  icon: string
  /** true 表示页面尚未接入后端能力，点击进入「待接入」说明页 */
  soon?: boolean
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
        soon: true,
        desc: '可视化编排工单流转节点与审批链路，替代当前固定状态机。',
        points: ['节点编排与条件分支', '自动分配规则配置', '流转记录追踪'],
      },
      {
        label: 'SLA 管理',
        to: '/workflow/sla',
        icon: 'clock',
        soon: true,
        desc: '定义响应与解决时限、升级策略，是「超时工单」指标的数据来源。',
        points: ['SLA 策略配置', '超时升级提醒', '达标率统计'],
      },
    ],
  },
  {
    name: '智能调度',
    items: [
      {
        label: '调度驾驶舱',
        to: '/dispatch',
        icon: 'route',
        soon: true,
        desc: '实时调度视图与工单池分配建议，需要后端提供调度与分派接口。',
        points: ['工单池实时视图', '智能分派建议', '调度任务跟踪'],
      },
      {
        label: '人员负载',
        to: '/dispatch/workload',
        icon: 'users',
        soon: true,
        desc: '查看成员在手工单与负载均衡情况，依赖人员维度统计接口。',
        points: ['成员在手工单', '负载热力图', '负载上限预警'],
      },
    ],
  },
  {
    name: 'AI 智能中心',
    items: [
      {
        label: 'AI 助手',
        to: '/ai/assistant',
        icon: 'sparkles',
        soon: true,
        desc: '基于大模型的工单问答与处置建议，待接入 Python AI 服务与 DeepSeek。',
        points: ['工单上下文问答', '处置建议生成', '会话历史'],
      },
      {
        label: '智能分类',
        to: '/ai/classify',
        icon: 'tag',
        soon: true,
        desc: '自动识别工单类型与优先级，当前工单类型仍由人工选择。',
        points: ['文本分类模型接入', '优先级预测', '人工校正回流'],
      },
      {
        label: '处理方案',
        to: '/ai/solutions',
        icon: 'bulb',
        soon: true,
        desc: '依据历史工单生成可复用的处理方案。',
        points: ['相似工单检索', '方案模板生成', '方案采纳统计'],
      },
    ],
  },
  {
    name: '数据分析',
    items: [
      {
        label: '工单趋势',
        to: '/analytics/trend',
        icon: 'chart',
        soon: true,
        desc: '多维度趋势与对比分析，需要后端统计聚合接口以支持任意时间范围。',
        points: ['多周期趋势对比', '类型 / 优先级下钻', '导出报表'],
      },
      {
        label: '处理效率',
        to: '/analytics/efficiency',
        icon: 'gauge',
        soon: true,
        desc: '首响时长、解决时长与积压分析，需要状态流转时间戳。',
        points: ['首响 / 解决时长', '积压与老化分析', '达标率趋势'],
      },
      {
        label: '人员绩效',
        to: '/analytics/performance',
        icon: 'award',
        soon: true,
        desc: '成员处理量与质量指标，依赖人员与实际处理人绑定。',
        points: ['处理量排行', '满意度与返工率', '绩效周期对比'],
      },
    ],
  },
  {
    name: '知识库',
    items: [
      {
        label: '知识文章',
        to: '/knowledge',
        icon: 'book',
        soon: true,
        desc: '沉淀可复用的解决方案文档，当前数据库暂无知识库表结构。',
        points: ['文章目录与检索', '关联工单类型', '版本与权限'],
      },
    ],
  },
  {
    name: '系统管理',
    items: [
      {
        label: '用户管理',
        to: '/system/users',
        icon: 'users',
        soon: true,
        desc: '账号、角色与权限配置，当前仅支持登录与角色判断。',
        points: ['用户列表与状态', '角色权限分配', '操作审计'],
      },
      { label: '系统监控', to: '/system/monitor', icon: 'monitor' },
      {
        label: '操作日志',
        to: '/system/logs',
        icon: 'logs',
        soon: true,
        desc: '系统关键操作留痕查询，需要后端审计表与写入逻辑。',
        points: ['操作记录检索', '变更前后对比', '导出审计日志'],
      },
    ],
  },
]
