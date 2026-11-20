/** 调度 API：推荐只读，确认分配由后端重新校验并走工单工作流 */
import { request } from './request'
import type { Ticket } from './tickets'

export interface ScoreBreakdown {
  skillScore: number
  workloadScore: number
  slaScore: number
  performanceScore: number
  total: number
  skillWeight: number
  workloadWeight: number
  slaWeight: number
  performanceWeight: number
}

/** 实际参与计算的权重（后端已归一化） */
export interface DispatchWeights {
  skill: number
  workload: number
  sla: number
  performance: number
}

export interface DispatchCandidate {
  agentId: number
  name: string
  username: string
  availability: string
  loadRate: number
  occupied: number
  maxConcurrent: number
  score: number
  breakdown: ScoreBreakdown
  matchedSkills: string[]
  missingOptionalSkills: string[]
  reasons: string[]
  warnings: string[]
}

export interface DispatchExcluded {
  agentId: number
  name: string
  reasons: string[]
}

export interface SkillRequirement {
  skillId: number
  skillCode: string
  skillName: string
  required: boolean
}

export interface DispatchRecommendation {
  ticketId: number
  ticketNo: string
  title: string
  type: string
  priority: string
  status: string
  slaState: string
  slaRemainingMinutes: number
  slaUrgencyScore: number
  requiredSkills: SkillRequirement[]
  candidates: DispatchCandidate[]
  excluded: DispatchExcluded[]
  weights: DispatchWeights
  generatedAt: string
}

export interface DispatchLog {
  id: number
  ticketId: number
  ticketNo: string
  action: string
  operatorId: number | null
  operatorName: string
  agentId: number | null
  agentName: string | null
  score: number | null
  candidateCount: number | null
  note: string | null
  detail: string | null
  createdAt: string
}

export interface DispatchAssignResult {
  ticket: Ticket
  logId: number
  chosen: DispatchCandidate
}

export interface DispatchTicketPage {
  list: Ticket[]
  total: number
  page: number
  size: number
}

export interface DispatchHistoryPage {
  list: DispatchLog[]
  total: number
  page: number
  size: number
}

export function listPendingDispatchTickets(
  page = 1,
  size = 20
): Promise<DispatchTicketPage> {
  const query = new URLSearchParams()
  query.set('page', String(page))
  query.set('size', String(size))
  return request<DispatchTicketPage>(`/api/dispatch/tickets?${query}`)
}

export function getDispatchRecommendation(
  ticketId: number,
  limit?: number
): Promise<DispatchRecommendation> {
  const query = new URLSearchParams()
  if (limit !== undefined) query.set('limit', String(limit))
  const suffix = query.toString()
  return request<DispatchRecommendation>(
    suffix
      ? `/api/dispatch/recommendations/${ticketId}?${suffix}`
      : `/api/dispatch/recommendations/${ticketId}`
  )
}

export function assignDispatch(body: {
  ticketId: number
  agentId: number
  note?: string
}): Promise<DispatchAssignResult> {
  return request<DispatchAssignResult>('/api/dispatch/assign', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function listDispatchHistory(params: {
  ticketId?: number
  agentId?: number
  page: number
  size: number
}): Promise<DispatchHistoryPage> {
  const query = new URLSearchParams()
  if (params.ticketId !== undefined) query.set('ticketId', String(params.ticketId))
  if (params.agentId !== undefined) query.set('agentId', String(params.agentId))
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  return request<DispatchHistoryPage>(`/api/dispatch/history?${query}`)
}
