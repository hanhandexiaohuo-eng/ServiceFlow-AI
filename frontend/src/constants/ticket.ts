/** 工单元数据与展示映射：组件与页面共用，避免重复定义 */
import type { Ticket, TicketActionKey } from '../api/tickets'

export const STATUS_OPTIONS = [
  'PENDING',
  'ASSIGNED',
  'ACCEPTED',
  'PROCESSING',
  'RESOLVED',
  'CLOSED',
  'CANCELLED',
] as const

export const PRIORITY_OPTIONS = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const
export const TYPE_OPTIONS = ['INCIDENT', 'SERVICE_REQUEST', 'CHANGE', 'CONSULT'] as const

export const STATUS_LABEL: Record<string, string> = {
  PENDING: '待分配',
  ASSIGNED: '已分配',
  ACCEPTED: '已接单',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
}

/** 状态说明：详情页与看板悬浮提示共用 */
export const STATUS_DESC: Record<string, string> = {
  PENDING: '工单已创建，等待分配处理人',
  ASSIGNED: '已指定处理人，等待接单',
  ACCEPTED: '处理人已接单，等待开始处理',
  PROCESSING: '处理人正在处理',
  RESOLVED: '问题已解决，等待创建人确认关闭',
  CLOSED: '工单正常结束',
  CANCELLED: '工单已取消，停止 SLA 计时',
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
  AGENT: '处理人员',
}

/** 图表用色（与全局设计变量保持一致） */
export const STATUS_COLOR: Record<string, string> = {
  PENDING: '#f59e0b',
  ASSIGNED: '#0ea5e9',
  ACCEPTED: '#8b5cf6',
  PROCESSING: '#4f46e5',
  RESOLVED: '#10b981',
  CLOSED: '#94a3b8',
  CANCELLED: '#ef4444',
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

/** 排序权重：数字越大优先级/流程越靠后 */
export const PRIORITY_WEIGHT: Record<string, number> = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
  URGENT: 4,
}

export const STATUS_WEIGHT: Record<string, number> = {
  PENDING: 1,
  ASSIGNED: 2,
  ACCEPTED: 3,
  PROCESSING: 4,
  RESOLVED: 5,
  CLOSED: 6,
  CANCELLED: 7,
}

/* ---------------- SLA：全部读取后端计算结果 ---------------- */

export type SlaState = 'ok' | 'warn' | 'overdue' | 'done'

export interface SlaInfo {
  state: SlaState
  /** 截止时间戳 */
  dueAt: number
  /** 剩余毫秒（超时为负；已完成则按完成时间计算） */
  remainingMs: number
  /** 已消耗时间百分比 0-100（后端计算） */
  percent: number
  /** 短文本：剩余 2小时10分 / 超时 3小时 */
  short: string
  /** 悬浮提示：完整口径说明 */
  detail: string
  hours: number
}

const SLA_STATE_MAP: Record<string, SlaState> = {
  ON_TRACK: 'ok',
  WARNING: 'warn',
  OVERDUE: 'overdue',
  COMPLETED: 'done',
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

export function formatDateTimeMs(ts: number): string {
  const d = new Date(ts)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 毫秒转「x天x小时 / x小时x分 / x分钟」 */
export function formatDuration(ms: number): string {
  const abs = Math.abs(ms)
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (abs >= day) {
    const d = Math.floor(abs / day)
    const h = Math.floor((abs % day) / hour)
    return h > 0 ? `${d}天${h}小时` : `${d}天`
  }
  if (abs >= hour) {
    const h = Math.floor(abs / hour)
    const m = Math.floor((abs % hour) / minute)
    return m > 0 ? `${h}小时${m}分` : `${h}小时`
  }
  return `${Math.max(1, Math.floor(abs / minute))}分钟`
}

/**
 * SLA 展示适配器：不在这里推算截止时间。
 * 后端已返回 state / remainingMinutes / elapsedPercent / dueAt，前端只负责格式化。
 */
export function getSla(ticket: Ticket): SlaInfo {
  const sla = ticket.sla
  const dueAt = new Date(sla.dueAt).getTime()
  const remainingMs = sla.remainingMinutes * 60 * 1000
  const state = SLA_STATE_MAP[sla.state] ?? 'ok'
  const parts = [sla.policyLabel, `截止 ${formatDateTimeMs(dueAt)}`, '后端 SLA 计算']
  if (sla.completedAt) {
    parts.push(`完成于 ${formatDateTime(sla.completedAt)}`)
  }

  let short: string
  if (state === 'done') {
    short = remainingMs >= 0 ? '按时完成' : '超时完成'
  } else if (state === 'overdue') {
    short = `超时 ${formatDuration(remainingMs)}`
  } else {
    short = `剩余 ${formatDuration(remainingMs)}`
  }

  return {
    state,
    dueAt,
    remainingMs,
    percent: sla.elapsedPercent,
    short,
    detail: parts.join(' · '),
    hours: sla.hours,
  }
}

/* ---------------- 状态流转：动作元数据 + 服务端允许动作 ---------------- */

/** 主流程（不含取消）：详情页流程条使用 */
export const STATUS_FLOW = [
  'PENDING',
  'ASSIGNED',
  'ACCEPTED',
  'PROCESSING',
  'RESOLVED',
  'CLOSED',
] as const

export function statusStep(status: string): number {
  return Math.max(0, STATUS_FLOW.indexOf(status as (typeof STATUS_FLOW)[number]))
}

export interface ActionPrompt {
  title: string
  placeholder: string
  optional: boolean
}

export interface TicketAction {
  key: TicketActionKey
  label: string
  icon: string
  hint: string
  primary?: boolean
  prompt?: ActionPrompt
}

export const ACTION_META: Record<TicketActionKey, TicketAction> = {
  ASSIGN: {
    key: 'ASSIGN',
    label: '分配',
    icon: 'users',
    hint: '指定处理人，工单进入已分配',
    primary: true,
  },
  TRANSFER: {
    key: 'TRANSFER',
    label: '转交',
    icon: 'route',
    hint: '更换处理人，工单回到已分配并等待新处理人接单',
  },
  ACCEPT: {
    key: 'ACCEPT',
    label: '接单',
    icon: 'check',
    hint: '确认接单，工单进入已接单',
    primary: true,
  },
  START: {
    key: 'START',
    label: '开始处理',
    icon: 'activity',
    hint: '开始处理，工单进入处理中',
    primary: true,
  },
  RESOLVE: {
    key: 'RESOLVE',
    label: '解决',
    icon: 'check',
    hint: '标记问题已解决，等待创建人关闭',
    primary: true,
    prompt: { title: '解决说明', placeholder: '填写解决方式（可选）', optional: true },
  },
  CLOSE: {
    key: 'CLOSE',
    label: '关闭工单',
    icon: 'check',
    hint: '确认解决结果并关闭工单',
    primary: true,
    prompt: { title: '关闭备注', placeholder: '填写关闭备注（可选）', optional: true },
  },
  CANCEL: {
    key: 'CANCEL',
    label: '取消工单',
    icon: 'close',
    hint: '终止工单，停止 SLA 计时',
    prompt: { title: '取消原因', placeholder: '填写取消原因（可选）', optional: true },
  },
}

/** 按后端返回的 allowedActions 生成按钮，前端不做权限推断 */
export function getAllowedActions(ticket: Ticket): TicketAction[] {
  return (ticket.allowedActions ?? [])
    .map((key) => ACTION_META[key])
    .filter((item): item is TicketAction => Boolean(item))
}

/* ---------------- 看板列（7 状态） ---------------- */

export interface BoardColumn {
  key: string
  label: string
  color: string
  desc: string
}

export const BOARD_COLUMNS: BoardColumn[] = [
  { key: 'PENDING', label: '待分配', color: '#f59e0b', desc: '等待分配处理人' },
  { key: 'ASSIGNED', label: '已分配', color: '#0ea5e9', desc: '等待处理人接单' },
  { key: 'ACCEPTED', label: '已接单', color: '#8b5cf6', desc: '处理人已接单' },
  { key: 'PROCESSING', label: '处理中', color: '#4f46e5', desc: '处理人正在处理' },
  { key: 'RESOLVED', label: '已解决', color: '#10b981', desc: '等待创建人关闭' },
  { key: 'CLOSED', label: '已关闭', color: '#94a3b8', desc: '正常结束' },
  { key: 'CANCELLED', label: '已取消', color: '#ef4444', desc: '非正常结束' },
]

export function inBoardColumn(ticket: Ticket, key: string): boolean {
  return ticket.status === key
}

/* ---------------- 事件与格式化 ---------------- */

export const EVENT_LABEL: Record<string, string> = {
  CREATE: '创建工单',
  UPDATE: '编辑工单',
  ASSIGN: '分配',
  TRANSFER: '转交',
  ACCEPT: '接单',
  START: '开始处理',
  RESOLVE: '解决',
  CLOSE: '关闭',
  CANCEL: '取消',
}

export function formatDateTime(input?: string | null): string {
  if (!input) return '-'
  return input.replace('T', ' ').slice(0, 16)
}

export function formatDate(input?: string | null): string {
  if (!input) return '-'
  return input.slice(0, 10)
}
