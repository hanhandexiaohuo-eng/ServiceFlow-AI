/**
 * 工单数据仓库：驾驶舱、顶栏徽标、列表页共享同一份数据，避免重复请求。
 * 所有统计都由真实接口 /api/tickets 拉取后在前端聚合，不使用任何模拟数据。
 */
import { computed, reactive } from 'vue'
import { listAllTickets, type Ticket } from '../api/tickets'
import {
  PRIORITY_COLOR,
  PRIORITY_LABEL,
  PRIORITY_OPTIONS,
  STATUS_COLOR,
  STATUS_LABEL,
  STATUS_OPTIONS,
  TYPE_COLOR,
  TYPE_LABEL,
  TYPE_OPTIONS,
} from '../constants/ticket'

const MAX_RECORDS = 1000
const CACHE_TTL = 30_000

const state = reactive({
  tickets: [] as Ticket[],
  total: 0,
  truncated: false,
  loading: false,
  error: '',
  updatedAt: 0,
})

let inflight: Promise<void> | null = null

export async function refreshTickets(force = false): Promise<void> {
  if (!force && state.updatedAt && Date.now() - state.updatedAt < CACHE_TTL) return
  if (inflight) return inflight
  state.loading = true
  state.error = ''
  inflight = (async () => {
    try {
      const data = await listAllTickets(MAX_RECORDS)
      state.tickets = data.list
      state.total = data.total
      state.truncated = data.truncated
      state.updatedAt = Date.now()
    } catch (e) {
      state.error = e instanceof Error ? e.message : '工单数据加载失败'
    } finally {
      state.loading = false
      inflight = null
    }
  })()
  return inflight
}

/* ---------------- 基础工具 ---------------- */

function dayKey(date: Date): string {
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${m}-${d}`
}

export function todayKey(): string {
  return dayKey(new Date())
}

function keyOf(value?: string | null): string {
  return value ? value.slice(0, 10) : ''
}

function withinDays(value: string | null | undefined, days: number): boolean {
  if (!value) return false
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) return false
  return Date.now() - time <= days * 24 * 60 * 60 * 1000
}

/* ---------------- 派生数据 ---------------- */

export const tickets = computed(() => state.tickets)
export const totalCount = computed(() => state.total)
export const isLoading = computed(() => state.loading)
export const loadError = computed(() => state.error)
export const isTruncated = computed(() => state.truncated)
export const updatedAt = computed(() => state.updatedAt)

export const todayNewCount = computed(
  () => state.tickets.filter((t) => keyOf(t.createdAt) === todayKey()).length
)

/** 待分配：待处理且尚未指派处理人（后端无“已分配”状态，用此口径近似） */
export const unassignedCount = computed(
  () => state.tickets.filter((t) => t.status === 'OPEN' && !t.assignee).length
)

export const inProgressCount = computed(
  () => state.tickets.filter((t) => t.status === 'IN_PROGRESS').length
)

export const doneCount = computed(
  () => state.tickets.filter((t) => t.status === 'RESOLVED' || t.status === 'CLOSED').length
)

export const urgentHighCount = computed(
  () => state.tickets.filter((t) => t.priority === 'URGENT' || t.priority === 'HIGH').length
)

export function distribution(
  keys: readonly string[],
  labelMap: Record<string, string>,
  colorMap: Record<string, string>,
  pick: (t: Ticket) => string
) {
  const counts = new Map<string, number>()
  keys.forEach((k) => counts.set(k, 0))
  state.tickets.forEach((t) => {
    const k = pick(t)
    counts.set(k, (counts.get(k) ?? 0) + 1)
  })
  return keys.map((k) => ({
    key: k,
    label: labelMap[k] ?? k,
    value: counts.get(k) ?? 0,
    color: colorMap[k] ?? '#94a3b8',
  }))
}

export const statusDistribution = computed(() =>
  distribution(STATUS_OPTIONS, STATUS_LABEL, STATUS_COLOR, (t) => t.status)
)
export const priorityDistribution = computed(() =>
  distribution(PRIORITY_OPTIONS, PRIORITY_LABEL, PRIORITY_COLOR, (t) => t.priority)
)
export const typeDistribution = computed(() =>
  distribution(TYPE_OPTIONS, TYPE_LABEL, TYPE_COLOR, (t) => t.type)
)

/**
 * 趋势数据。新增按 createdAt 统计（准确）；
 * 已完成按 updatedAt 近似（后端暂无状态流转记录表）；处理中为当前处于处理中且创建于当日。
 */
export function buildTrend(days: number) {
  const labels: string[] = []
  const created: number[] = []
  const resolved: number[] = []
  const running: number[] = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)

  for (let i = days - 1; i >= 0; i -= 1) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    const key = dayKey(d)
    labels.push(`${d.getMonth() + 1}/${d.getDate()}`)
    created.push(state.tickets.filter((t) => keyOf(t.createdAt) === key).length)
    resolved.push(
      state.tickets.filter(
        (t) => (t.status === 'RESOLVED' || t.status === 'CLOSED') && keyOf(t.updatedAt) === key
      ).length
    )
    running.push(
      state.tickets.filter((t) => t.status === 'IN_PROGRESS' && keyOf(t.createdAt) === key).length
    )
  }
  return { labels, created, resolved, running }
}

/** 最近更新（按 id 倒序即最新创建顺序） */
export const recentTickets = computed(() => state.tickets.slice(0, 8))

export const recentUpdateCount = computed(
  () => state.tickets.filter((t) => withinDays(t.updatedAt, 1)).length
)

/** 我的待办：与当前登录用户相关的真实工单 */
export function buildMyTodos(userName: string, isAdmin: boolean) {
  const mine = state.tickets.filter(
    (t) => isAdmin || t.creator === userName || t.assignee === userName
  )
  const pending = mine.filter((t) => t.status === 'OPEN')
  const assignedToMe = state.tickets.filter((t) => t.assignee === userName && t.status === 'OPEN')
  const active = mine.filter((t) => t.status === 'IN_PROGRESS')
  return { pending, assignedToMe, active }
}
