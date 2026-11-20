/** AI 模型治理 API：仅管理员；供应商与密钥写操作由后端二次鉴权（超级管理员）并落审计 */
import { request } from './request'

export interface AiProvider {
  id: number
  code: string
  displayName: string
  baseUrl: string
  keyMask: string | null
  hasKey: boolean
  enabled: boolean
  lastTestAt: string | null
  lastTestResult: string | null
  lastTestMessage: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface AiModel {
  id: number
  providerId: number
  providerCode: string
  modelCode: string
  displayName: string
  enabled: boolean
  verified: boolean
  isDefault: boolean
  temperature: number
  maxTokens: number
  timeoutSeconds: number
  costPer1kMicros: number
  createdAt: string | null
  updatedAt: string | null
}

export interface AiScenarioRoute {
  id: number
  scenario: string
  primaryModelId: number | null
  fallbackModelId: number | null
  temperature: number | null
  maxTokens: number | null
  timeoutSeconds: number | null
  maxRetries: number
  enabled: boolean
  updatedAt: string | null
}

export interface AiQuota {
  id: number
  scopeType: string
  scopeValue: string
  period: string
  maxCalls: number
  enabled: boolean
}

export interface ScenarioStat {
  scenario: string
  total: number
  success: number
  failed: number
  tokens: number
  costMicros: number
  avgLatencyMs: number
}

export interface AiCallStats {
  days: number
  total: number
  success: number
  failed: number
  failureRate: number
  tokens: number
  costMicros: number
  avgLatencyMs: number
  lastHourFailures: number
  alerting: boolean
  scenarios: ScenarioStat[]
}

export interface AiCallLog {
  id: number
  analysisId: number | null
  scenario: string
  modelName: string | null
  attempt: number
  status: string
  errorCode: string | null
  latencyMs: number | null
  totalTokens: number | null
  costMicros: number | null
  actorId: number | null
  actorDepartment: string | null
  createdAt: string | null
}

export interface AiGovernanceMeta {
  keyConfigured: boolean
  superAdmin: boolean
}

export interface AiProviderBody {
  code: string
  displayName: string
  baseUrl: string
  apiKey: string
  enabled?: boolean
}

export interface AiModelBody {
  providerId: number
  modelCode: string
  displayName: string
  temperature?: number
  maxTokens?: number
  timeoutSeconds?: number
  costPer1kMicros?: number
}

export interface AiRouteBody {
  primaryModelId: number | null
  fallbackModelId: number | null
  temperature: number | null
  maxTokens: number | null
  timeoutSeconds: number | null
  maxRetries: number
  enabled: boolean
}

export interface AiQuotaBody {
  scopeType: string
  scopeValue: string
  period: string
  maxCalls: number
  enabled: boolean
}

export const SCENARIOS = [
  { value: 'TICKET_ANALYSIS', label: '工单智能分析' },
  { value: 'PRIORITY', label: '优先级推荐' },
  { value: 'SOLUTION', label: '处置方案生成' },
  { value: 'KNOWLEDGE', label: '知识推荐' },
  { value: 'DISPATCH', label: '调度辅助' },
  { value: 'CHAT', label: '工单问答' },
]

export function fetchAiMeta(): Promise<AiGovernanceMeta> {
  return request<AiGovernanceMeta>('/api/admin/ai/meta')
}

export function listAiProviders(): Promise<AiProvider[]> {
  return request<AiProvider[]>('/api/admin/ai/providers')
}

export function createAiProvider(body: AiProviderBody): Promise<AiProvider> {
  return request<AiProvider>('/api/admin/ai/providers', { method: 'POST', body: JSON.stringify(body) })
}

export function updateAiProvider(id: number, body: { displayName: string; baseUrl: string }): Promise<AiProvider> {
  return request<AiProvider>(`/api/admin/ai/providers/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function updateAiProviderKey(id: number, apiKey: string): Promise<AiProvider> {
  return request<AiProvider>(`/api/admin/ai/providers/${id}/key`, {
    method: 'PUT',
    body: JSON.stringify({ apiKey }),
  })
}

export function updateAiProviderStatus(id: number, enabled: boolean): Promise<AiProvider> {
  return request<AiProvider>(`/api/admin/ai/providers/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ enabled }),
  })
}

export function testAiProvider(id: number, modelCode?: string) {
  return request<{ success: boolean; message: string; latencyMs: number }>(
    `/api/admin/ai/providers/${id}/test`,
    { method: 'POST', body: JSON.stringify({ modelCode: modelCode ?? null }) }
  )
}

export function listAiModels(): Promise<AiModel[]> {
  return request<AiModel[]>('/api/admin/ai/models')
}

export function createAiModel(body: AiModelBody): Promise<AiModel> {
  return request<AiModel>('/api/admin/ai/models', { method: 'POST', body: JSON.stringify(body) })
}

export function updateAiModel(
  id: number,
  body: { displayName: string; temperature: number; maxTokens: number; timeoutSeconds: number; costPer1kMicros: number }
): Promise<AiModel> {
  return request<AiModel>(`/api/admin/ai/models/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function updateAiModelStatus(id: number, enabled: boolean): Promise<AiModel> {
  return request<AiModel>(`/api/admin/ai/models/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ enabled }),
  })
}

export function verifyAiModel(id: number): Promise<AiModel> {
  return request<AiModel>(`/api/admin/ai/models/${id}/verify`, { method: 'POST' })
}

export function setDefaultAiModel(id: number): Promise<AiModel> {
  return request<AiModel>(`/api/admin/ai/models/${id}/default`, { method: 'POST' })
}

export function listAiRoutes(): Promise<AiScenarioRoute[]> {
  return request<AiScenarioRoute[]>('/api/admin/ai/routes')
}

export function updateAiRoute(scenario: string, body: AiRouteBody): Promise<AiScenarioRoute> {
  return request<AiScenarioRoute>(`/api/admin/ai/routes/${scenario}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function listAiQuotas(): Promise<AiQuota[]> {
  return request<AiQuota[]>('/api/admin/ai/quotas')
}

export function upsertAiQuota(body: AiQuotaBody): Promise<AiQuota> {
  return request<AiQuota>('/api/admin/ai/quotas', { method: 'PUT', body: JSON.stringify(body) })
}

export function deleteAiQuota(id: number): Promise<void> {
  return request<void>(`/api/admin/ai/quotas/${id}`, { method: 'DELETE' })
}

export function fetchAiCallStats(days = 7): Promise<AiCallStats> {
  return request<AiCallStats>(`/api/admin/ai/call-stats?days=${days}`)
}

export function fetchAiCalls(limit = 50): Promise<AiCallLog[]> {
  return request<AiCallLog[]>(`/api/admin/ai/calls?limit=${limit}`)
}
