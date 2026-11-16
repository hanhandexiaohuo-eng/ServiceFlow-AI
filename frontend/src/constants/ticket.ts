/** 工单元数据与展示映射：组件与页面共用，避免重复定义 */

export const STATUS_OPTIONS = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const
export const PRIORITY_OPTIONS = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const
export const TYPE_OPTIONS = ['INCIDENT', 'SERVICE_REQUEST', 'CHANGE', 'CONSULT'] as const

export const STATUS_LABEL: Record<string, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}

export const PRIORITY_LABEL: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}

export const TYPE_LABEL: Record<string, string> = {
  INCIDENT: '故障申报',
  SERVICE_REQUEST: '服务请求',
  CHANGE: '变更申请',
  CONSULT: '业务咨询',
}

export const ROLE_LABEL: Record<string, string> = {
  ADMIN: '管理员',
  USER: '普通用户',
}

/** 图表用色（与全局设计变量保持一致） */
export const STATUS_COLOR: Record<string, string> = {
  OPEN: '#f59e0b',
  IN_PROGRESS: '#4f46e5',
  RESOLVED: '#10b981',
  CLOSED: '#94a3b8',
}

export const PRIORITY_COLOR: Record<string, string> = {
  LOW: '#94a3b8',
  MEDIUM: '#4f46e5',
  HIGH: '#f59e0b',
  URGENT: '#ef4444',
}

export const TYPE_COLOR: Record<string, string> = {
  INCIDENT: '#ef4444',
  SERVICE_REQUEST: '#4f46e5',
  CHANGE: '#06b6d4',
  CONSULT: '#8b5cf6',
}

export function formatDateTime(input?: string | null): string {
  if (!input) return '-'
  return input.replace('T', ' ').slice(0, 16)
}

export function formatDate(input?: string | null): string {
  if (!input) return '-'
  return input.slice(0, 10)
}
