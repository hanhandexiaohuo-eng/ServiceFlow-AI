/** AI 接口：分析、建议确认、会话与工单上下文 */
import { request } from './request'
import type { Ticket } from './tickets'
import type { DispatchRecommendation } from './dispatch'
import type { KnowledgeRecommendation } from './knowledge'

export type AiAnalysisType = 'TICKET_ANALYSIS' | 'PRIORITY' | 'SOLUTION' | 'DISPATCH' | 'CHAT'
export type AiAnalysisStatus = 'PENDING' | 'SUCCESS' | 'FAILED'
export type SuggestionType = 'CLASSIFICATION' | 'PRIORITY' | 'SOLUTION'
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXECUTED'

export interface AiSuggestion {
  id: number
  analysisId: number
  ticketId: number
  type: SuggestionType
  status: SuggestionStatus
  payload: Record<string, unknown>
  summary: string
  createdBy: number
  createdByName: string | null
  decidedBy: number | null
  decidedByName: string | null
  decidedAt: string | null
  decisionNote: string | null
  executionNote: string | null
  createdAt: string
}

export interface AiAnalysis {
  id: number
  ticketId: number
  ticketNo: string
  type: AiAnalysisType
  status: AiAnalysisStatus
  requestedBy: number
  requestedByName: string | null
  promptVersion: string | null
  model: string | null
  latencyMs: number | null
  createdAt: string
  completedAt: string | null
  errorCode: string | null
  errorMessage: string | null
  result: Record<string, unknown> | null
  dispatch: DispatchRecommendation | null
  suggestions: AiSuggestion[]
  knowledge: KnowledgeRecommendation[]
}

export interface AiAnalysisBrief {
  id: number
  ticketId: number
  ticketNo: string
  type: AiAnalysisType
  status: AiAnalysisStatus
  summary: string
  errorCode: string | null
  requestedByName: string | null
  createdAt: string
}

export interface AiSession {
  id: number
  ticketId: number
  ticketNo: string
  ticketTitle: string
  userId: number
  userName: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED'
  messageCount: number
  createdAt: string
  updatedAt: string
}

export interface AiMessage {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT'
  content: string
  analysisId: number | null
  createdAt: string
}

export interface AiSessionDetail {
  session: AiSession
  messages: AiMessage[]
  analyses: AiAnalysisBrief[]
}

export interface AiChatResult {
  userMessage: AiMessage
  assistantMessage: AiMessage
  analysis: AiAnalysis
}

export interface AiTicketContext {
  ticket: Ticket
  latestAnalyses: AiAnalysisBrief[]
  pendingSuggestions: AiSuggestion[]
  knowledge: KnowledgeRecommendation[]
  dispatch: DispatchRecommendation | null
}

export interface AiPage<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface AnalyzePayload {
  ticketId: number
  type: Exclude<AiAnalysisType, 'CHAT'>
  sessionId?: number
  question?: string
  forceRefresh?: boolean
}

export function runAnalysis(payload: AnalyzePayload): Promise<AiAnalysis> {
  return request<AiAnalysis>('/api/ai/analyses', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listAnalyses(params: {
  ticketId?: number
  type?: string
  status?: string
  page: number
  size: number
}): Promise<AiPage<AiAnalysisBrief>> {
  const query = new URLSearchParams()
  if (params.ticketId !== undefined) query.set('ticketId', String(params.ticketId))
  if (params.type) query.set('type', params.type)
  if (params.status) query.set('status', params.status)
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  return request<AiPage<AiAnalysisBrief>>(`/api/ai/analyses?${query}`)
}

export function getAnalysis(id: number): Promise<AiAnalysis> {
  return request<AiAnalysis>(`/api/ai/analyses/${id}`)
}

export function acceptSuggestion(id: number, note?: string): Promise<AiSuggestion> {
  return request<AiSuggestion>(`/api/ai/suggestions/${id}/accept`, {
    method: 'POST',
    body: JSON.stringify({ note: note ?? '' }),
  })
}

export function rejectSuggestion(id: number, note?: string): Promise<AiSuggestion> {
  return request<AiSuggestion>(`/api/ai/suggestions/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ note: note ?? '' }),
  })
}

export function getTicketAiContext(ticketId: number): Promise<AiTicketContext> {
  return request<AiTicketContext>(`/api/ai/tickets/${ticketId}/context`)
}

export function createAiSession(body: {
  ticketId: number
  title?: string
}): Promise<AiSession> {
  return request<AiSession>('/api/ai/sessions', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function listAiSessions(params: {
  ticketId?: number
  status?: string
  page: number
  size: number
}): Promise<AiPage<AiSession>> {
  const query = new URLSearchParams()
  if (params.ticketId !== undefined) query.set('ticketId', String(params.ticketId))
  if (params.status) query.set('status', params.status)
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  return request<AiPage<AiSession>>(`/api/ai/sessions?${query}`)
}

export function getAiSession(id: number): Promise<AiSessionDetail> {
  return request<AiSessionDetail>(`/api/ai/sessions/${id}`)
}

export function updateAiSession(
  id: number,
  body: { title?: string; status?: string }
): Promise<AiSession> {
  return request<AiSession>(`/api/ai/sessions/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function deleteAiSession(id: number): Promise<null> {
  return request<null>(`/api/ai/sessions/${id}`, { method: 'DELETE' })
}

export function askAiSession(sessionId: number, content: string): Promise<AiChatResult> {
  return request<AiChatResult>(`/api/ai/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}
