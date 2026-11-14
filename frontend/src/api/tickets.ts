/** 工单 API 模块：组件不直接写 fetch，统一走这里 */
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

export interface TicketForm {
  title: string
  description: string
  type: string
  priority: string
  status?: string
  creator?: string
  assignee?: string
}

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/** 统一请求封装：非 2xx 时抛出后端返回的 message */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok) {
    throw new Error(body.message || `请求失败：${response.status}`)
  }
  return body.data
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

export function createTicket(form: TicketForm): Promise<Ticket> {
  return request<Ticket>('/api/tickets', { method: 'POST', body: JSON.stringify(form) })
}

export function updateTicket(id: number, form: TicketForm): Promise<Ticket> {
  return request<Ticket>(`/api/tickets/${id}`, { method: 'PUT', body: JSON.stringify(form) })
}

export function deleteTicket(id: number): Promise<null> {
  return request<null>(`/api/tickets/${id}`, { method: 'DELETE' })
}
