/** 工单 API：组件不直接写 fetch，统一走这里 */
import { request } from './request'

export type TicketActionKey =
  | 'ASSIGN'
  | 'TRANSFER'
  | 'ACCEPT'
  | 'START'
  | 'RESOLVE'
  | 'CLOSE'
  | 'CANCEL'

export interface TicketSla {
  policyLabel: string
  hours: number
  dueAt: string
  state: 'ON_TRACK' | 'WARNING' | 'OVERDUE' | 'COMPLETED'
  /** 剩余分钟，超时为负 */
  remainingMinutes: number
  elapsedPercent: number
  completedAt: string | null
}

export interface Ticket {
  id: number
  ticketNo: string
  title: string
  description: string | null
  type: string
  priority: string
  status: string
  creator: string
  creatorId: number | null
  assignee: string | null
  assigneeId: number | null
  createdAt: string
  updatedAt: string
  firstResponseAt: string | null
  resolvedAt: string | null
  closedAt: string | null
  cancelledAt: string | null
  sla: TicketSla
  /** 服务端按当前登录用户计算，前端只负责渲染 */
  allowedActions: TicketActionKey[]
  canEdit: boolean
}

export interface TicketEvent {
  id: number
  action: string
  fromStatus: string | null
  toStatus: string | null
  operatorId: number | null
  operator: string
  note: string | null
  createdAt: string
}

export interface PageData {
  list: Ticket[]
  total: number
  page: number
  size: number
}

/** 创建/编辑表单。状态与处理人不在编辑表单内，由工作流接口负责 */
export interface TicketForm {
  title: string
  description: string
  type: string
  priority: string
  /** 仅创建时可选：指定后初始状态为 ASSIGNED */
  assignee?: string
}

export function listTickets(params: {
  status?: string
  priority?: string
  page: number
  size: number
}): Promise<PageData> {
  const query = new URLSearchParams()
  if (params.status) query.set('status', params.status)
  if (params.priority) query.set('priority', params.priority)
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  return request<PageData>(`/api/tickets?${query}`)
}

export function getTicket(id: number): Promise<Ticket> {
  return request<Ticket>(`/api/tickets/${id}`)
}

/**
 * 拉取全量工单（用于驾驶舱聚合统计与前端筛选）。
 * 后端单页上限 100，这里按页循环直到取完或达到 maxRecords。
 */
export async function listAllTickets(maxRecords = 1000): Promise<{
  list: Ticket[]
  total: number
  truncated: boolean
}> {
  const size = 100
  const first = await listTickets({ page: 1, size })
  const list = [...first.list]
  let page = 2
  while (list.length < first.total && list.length < maxRecords && first.list.length === size) {
    const next = await listTickets({ page, size })
    if (next.list.length === 0) break
    list.push(...next.list)
    page += 1
  }
  return { list, total: first.total, truncated: list.length < first.total }
}

export function createTicket(form: TicketForm): Promise<Ticket> {
  const body: Record<string, string> = {
    title: form.title,
    description: form.description,
    type: form.type,
    priority: form.priority,
  }
  if (form.assignee && form.assignee.trim()) {
    body.assignee = form.assignee.trim()
  }
  return request<Ticket>('/api/tickets', { method: 'POST', body: JSON.stringify(body) })
}

/** 只编辑内容；状态/处理人必须走工作流接口 */
export function updateTicket(id: number, form: TicketForm): Promise<Ticket> {
  return request<Ticket>(`/api/tickets/${id}`, {
    method: 'PUT',
    body: JSON.stringify({
      title: form.title,
      description: form.description,
      type: form.type,
      priority: form.priority,
    }),
  })
}

export function deleteTicket(id: number): Promise<null> {
  return request<null>(`/api/tickets/${id}`, { method: 'DELETE' })
}

export function getTicketEvents(id: number): Promise<TicketEvent[]> {
  return request<TicketEvent[]>(`/api/tickets/${id}/events`)
}

/* ---------------- 工作流动作：全部由后端状态机校验 ---------------- */

function postAction(
  id: number,
  action: string,
  body?: Record<string, string>
): Promise<Ticket> {
  return request<Ticket>(`/api/tickets/${id}/${action}`, {
    method: 'POST',
    body: body ? JSON.stringify(body) : undefined,
  })
}

export function assignTicket(id: number, assignee: string): Promise<Ticket> {
  return postAction(id, 'assign', { assignee })
}

export function transferTicket(id: number, assignee: string): Promise<Ticket> {
  return postAction(id, 'transfer', { assignee })
}

export function acceptTicket(id: number): Promise<Ticket> {
  return postAction(id, 'accept')
}

export function startTicket(id: number): Promise<Ticket> {
  return postAction(id, 'start')
}

export function resolveTicket(id: number, resolution?: string): Promise<Ticket> {
  return postAction(id, 'resolve', { resolution: resolution ?? '' })
}

export function closeTicket(id: number, note?: string): Promise<Ticket> {
  return postAction(id, 'close', { note: note ?? '' })
}

export function cancelTicket(id: number, reason?: string): Promise<Ticket> {
  return postAction(id, 'cancel', { reason: reason ?? '' })
}
