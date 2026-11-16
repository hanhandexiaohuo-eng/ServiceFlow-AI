/** 工单 API 模块：组件不直接写 fetch，统一走这里 */
import { request } from './request'

export interface Ticket {
  id: number
  ticketNo: string
  title: string
  description: string | null
  type: string
  priority: string
  status: string
  creator: string
  assignee: string | null
  createdAt: string
  updatedAt: string
}

export interface PageData {
  list: Ticket[]
  total: number
  page: number
  size: number
}

/** 创建/编辑表单。creator 不在其中：创建人由后端根据登录身份确定，客户端无权指定 */
export interface TicketForm {
  title: string
  description: string
  type: string
  priority: string
  status?: string
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
  return request<Ticket>('/api/tickets', { method: 'POST', body: JSON.stringify(form) })
}

export function updateTicket(id: number, form: TicketForm): Promise<Ticket> {
  return request<Ticket>(`/api/tickets/${id}`, { method: 'PUT', body: JSON.stringify(form) })
}

export function deleteTicket(id: number): Promise<null> {
  return request<null>(`/api/tickets/${id}`, { method: 'DELETE' })
}
