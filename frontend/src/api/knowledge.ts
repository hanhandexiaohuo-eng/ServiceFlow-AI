/** 知识库接口：关键词检索与工单关联（第一版为 MySQL LIKE 检索，非向量检索） */
import { request } from './request'

export type KnowledgeStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface KnowledgeArticle {
  id: number
  title: string
  category: string
  problem: string
  solution: string
  techStack: string | null
  keywords: string | null
  sourceTicketId: number | null
  status: KnowledgeStatus
  createdByName: string | null
  updatedByName: string | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgeSummary {
  id: number
  title: string
  category: string
  keywords: string | null
  status: KnowledgeStatus
  updatedByName: string | null
  updatedAt: string
}

export interface KnowledgeRecommendation {
  articleId: number
  title: string
  category: string
  summary: string
  solutionPreview: string
  keywords: string | null
  relevance: number
  reason: string
  matchedKeywords: string[]
}

export interface KnowledgeCategory {
  category: string
  count: number
}

export interface LinkedTicket {
  ticketId: number
  ticketNo: string
  title: string
  status: string
  linkedAt: string
  linkedByName: string | null
  note: string | null
}

export interface KnowledgeLink {
  articleId: number
  title: string
  category: string
  keywords: string | null
  status: string
  linkedAt: string
  linkedByName: string | null
  note: string | null
}

export interface KnowledgePage<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface KnowledgeForm {
  title: string
  category: string
  problem: string
  solution: string
  techStack?: string
  keywords: string
  sourceTicketId?: number | null
  status?: string
}

export function listKnowledge(params: {
  keyword?: string
  category?: string
  status?: string
  page: number
  size: number
}): Promise<KnowledgePage<KnowledgeSummary>> {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.category) query.set('category', params.category)
  if (params.status) query.set('status', params.status)
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  return request<KnowledgePage<KnowledgeSummary>>(`/api/knowledge?${query}`)
}

export function listKnowledgeCategories(): Promise<KnowledgeCategory[]> {
  return request<KnowledgeCategory[]>('/api/knowledge/categories')
}

export function getKnowledge(id: number): Promise<KnowledgeArticle> {
  return request<KnowledgeArticle>(`/api/knowledge/${id}`)
}

export function createKnowledge(form: KnowledgeForm): Promise<KnowledgeArticle> {
  return request<KnowledgeArticle>('/api/knowledge', {
    method: 'POST',
    body: JSON.stringify(form),
  })
}

export function updateKnowledge(id: number, form: KnowledgeForm): Promise<KnowledgeArticle> {
  return request<KnowledgeArticle>(`/api/knowledge/${id}`, {
    method: 'PUT',
    body: JSON.stringify(form),
  })
}

export function listArticleTickets(id: number): Promise<LinkedTicket[]> {
  return request<LinkedTicket[]>(`/api/knowledge/${id}/tickets`)
}

export function linkArticleToTicket(
  id: number,
  body: { ticketId: number; note?: string }
): Promise<KnowledgeLink> {
  return request<KnowledgeLink>(`/api/knowledge/${id}/tickets`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function unlinkArticleFromTicket(id: number, ticketId: number): Promise<null> {
  return request<null>(`/api/knowledge/${id}/tickets/${ticketId}`, { method: 'DELETE' })
}

export function listTicketKnowledge(ticketId: number): Promise<KnowledgeLink[]> {
  return request<KnowledgeLink[]>(`/api/knowledge/tickets/${ticketId}`)
}
