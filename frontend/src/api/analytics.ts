/**
 * 运营分析 API：13 个只读统计接口与 CSV 导出。
 * 字段名与类型严格对应后端 analytics 模块实际返回结构，可空值统一用 `| null`。
 */
import { downloadFile, request } from './request'

export type AnalyticsTicketType = 'INCIDENT' | 'SERVICE_REQUEST' | 'CHANGE' | 'CONSULT'
export type AnalyticsPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type AnalyticsTicketStatus =
  | 'PENDING'
  | 'ASSIGNED'
  | 'ACCEPTED'
  | 'PROCESSING'
  | 'RESOLVED'
  | 'CLOSED'
  | 'CANCELLED'
export type AnalyticsSlaState = 'ON_TRACK' | 'WARNING' | 'OVERDUE' | 'COMPLETED'
export type AnalyticsDurationBucket =
  | '<60'
  | '60-240'
  | '240-480'
  | '480-1440'
  | '1440-4320'
  | '>4320'
export type AnalyticsReportType =
  | 'TICKET_OPERATIONS'
  | 'SLA_EFFICIENCY'
  | 'AGENT_PERFORMANCE'
  | 'DISPATCH_EFFECT'
  | 'AI_EFFECT'

/** 所有统计接口共用的查询参数，日期格式 yyyy-MM-dd */
export interface AnalyticsQueryParams {
  startDate?: string
  endDate?: string
  ticketType?: string
  priority?: string
  assigneeId?: number
}

export interface AgentTrendQueryParams extends AnalyticsQueryParams {
  agentId?: number
}

export interface ReportQueryParams extends AnalyticsQueryParams {
  type: AnalyticsReportType
}

/* ---------------- 工单运营 ---------------- */

export interface AnalyticsOverview {
  total: number
  resolved: number
  closed: number
  cancelled: number
  processing: number
}

export interface TicketTrendPoint {
  date: string
  created: number
  resolved: number
  closed: number
}

export interface TicketTrendData {
  points: TicketTrendPoint[]
}

export interface TicketTypeDistributionItem {
  type: string
  count: number
  percent: number | null
}

export interface TicketStatusDistributionItem {
  status: AnalyticsTicketStatus
  count: number
  percent: number | null
}

export interface TicketPriorityDistributionItem {
  priority: AnalyticsPriority
  total: number
  resolved: number
  overdue: number
  avgHandleMinutes: number | null
}

export interface TicketDistributionData {
  byType: TicketTypeDistributionItem[]
  byStatus: TicketStatusDistributionItem[]
  byPriority: TicketPriorityDistributionItem[]
}

/* ---------------- SLA 效率 ---------------- */

export interface SlaRateBlock {
  numerator: number
  denominator: number
  rate: number | null
}

export interface SlaResolutionBlock extends SlaRateBlock {
  /** 后端固定 true：解决达标是同一截止时间下的代理口径 */
  proxy: boolean
}

export interface SlaStateCount {
  state: AnalyticsSlaState
  count: number
}

export interface SlaBucketCount {
  bucket: AnalyticsDurationBucket
  count: number
}

export interface SlaPriorityComparisonItem {
  priority: AnalyticsPriority
  denominator: number
  numerator: number
  rate: number | null
  avgFirstResponseMinutes: number | null
  overdue: number
}

export interface SlaOverview {
  firstResponse: SlaRateBlock
  resolution: SlaResolutionBlock
  overallRate: number | null
  overallNumerator: number
  overallDenominator: number
  warningCount: number
  overdueCount: number
  overdueCompleted: number
  missingSla: number
  avgFirstResponseMinutes: number | null
  avgResolutionMinutes: number | null
  statusDistribution: SlaStateCount[]
  firstResponseBuckets: SlaBucketCount[]
  resolutionBuckets: SlaBucketCount[]
  priorityComparison: SlaPriorityComparisonItem[]
  notes: string[]
}

export interface SlaTrendPoint {
  date: string
  denominator: number
  numerator: number
  rate: number | null
  sampleSufficient: boolean
}

export interface SlaTrendData {
  points: SlaTrendPoint[]
}

/* ---------------- 人员绩效 ---------------- */

export interface AgentPerformanceItem {
  agentId: number
  agentName: string
  username: string
  active: boolean
  maxConcurrent: number | null
  assignedTotal: number
  resolvedTotal: number
  currentOccupied: number
  completionRate: number | null
  avgFirstResponseMinutes: number | null
  avgResolutionMinutes: number | null
  slaRate: number | null
  loadPercent: number | null
  overdueTotal: number
  weightedResolved: number
  slaScore: number
  completionScore: number
  volumeScore: number
  loadScore: number
  score: number
  lowSample: boolean
  rank: number | null
}

export interface AgentPerformanceData {
  agents: AgentPerformanceItem[]
  maxWeightedResolved: number
  notes: string[]
}

export interface AgentRankingData {
  items: AgentPerformanceItem[]
  notes: string[]
}

export interface AgentTrendPoint {
  date: string
  resolved: number
  avgFirstResponseMinutes: number | null
  responseSlaRate: number | null
  denominator: number
}

export interface AgentTrendData {
  points: AgentTrendPoint[]
  note: string
}

/* ---------------- 智能调度 ---------------- */

export interface DispatchAgentDistribution {
  agentId: number | null
  agentName: string
  recommendCount: number
  acceptedCount: number
  rejectedCount: number
}

export interface DispatchPriorityEffect {
  priority: AnalyticsPriority
  decisionCount: number
  acceptedCount: number
  adoptionRate: number | null
}

export interface DispatchSkillMatch {
  state: 'MATCHED' | 'PARTIAL' | 'UNMATCHED' | 'UNKNOWN'
  count: number
}

export interface DispatchAdoptionPoint {
  date: string
  decisionCount: number
  acceptedCount: number
  rate: number | null
}

export interface DispatchOverview {
  recommendationCount: number
  assignedAfterRecommend: number
  acceptedCount: number
  rejectedCount: number
  unverifiableCount: number
  pendingCount: number
  decisionCount: number
  adoptionRate: number | null
  rejectionRate: number | null
  avgConfirmSeconds: number | null
  lowSample: boolean
  loadChangeAvailable: boolean
  loadChangeNote: string
  agents: DispatchAgentDistribution[]
  priorityEffects: DispatchPriorityEffect[]
  skillMatch: DispatchSkillMatch[]
  adoptionTrend: DispatchAdoptionPoint[]
  notes: string[]
}

/* ---------------- AI 效果 ---------------- */

export interface AiFeatureStat {
  analysisType: string
  total: number
  success: number
  failed: number
  successRate: number | null
  avgLatencyMs: number | null
}

export interface AiErrorStat {
  errorCode: string
  count: number
}

export interface AiOverview {
  totalCalls: number
  successCount: number
  failedCount: number
  pendingCount: number
  successRate: number | null
  avgLatencyMs: number | null
  features: AiFeatureStat[]
  errors: AiErrorStat[]
  notes: string[]
}

export interface AiSuggestionTypeStat {
  suggestionType: 'CLASSIFICATION' | 'PRIORITY' | 'SOLUTION'
  total: number
  pending: number
  accepted: number
  rejected: number
  decided: number
  adoptionRate: number | null
}

export interface AiSuggestionStats {
  total: number
  pending: number
  accepted: number
  rejected: number
  decided: number
  adoptionRate: number | null
  byType: AiSuggestionTypeStat[]
  notes: string[]
}

/* ---------------- 综合报表 ---------------- */

export interface ReportMetric {
  key: string
  label: string
  value: number | null
  formula: string
}

export interface ReportColumn {
  key: string
  label: string
}

/** 明细行按 detailColumns 的 key 组织，值可能为字符串/数字/布尔/null */
export interface ReportRow {
  [key: string]: string | number | boolean | null
}

export interface ReportRange {
  startDate: string
  endDate: string
  label: string
}

export interface ReportFilters {
  ticketType: string | null
  priority: string | null
  assigneeId: number | null
  assigneeName: string | null
}

export interface AnalyticsReport {
  reportName: string
  type: AnalyticsReportType
  generatedAt: string
  range: ReportRange
  filters: ReportFilters
  metrics: ReportMetric[]
  detailColumns: ReportColumn[]
  detailRows: ReportRow[]
  notes: string[]
}

/* ---------------- 查询串与接口调用 ---------------- */

type QueryWithAgent = AnalyticsQueryParams & { agentId?: number }

function appendParams(query: URLSearchParams, params: QueryWithAgent): void {
  if (params.startDate) query.set('startDate', params.startDate)
  if (params.endDate) query.set('endDate', params.endDate)
  if (params.ticketType) query.set('ticketType', params.ticketType)
  if (params.priority) query.set('priority', params.priority)
  if (typeof params.assigneeId === 'number' && Number.isFinite(params.assigneeId)) {
    query.set('assigneeId', String(params.assigneeId))
  }
  if (typeof params.agentId === 'number' && Number.isFinite(params.agentId)) {
    query.set('agentId', String(params.agentId))
  }
}

function toQuery(params: QueryWithAgent = {}): string {
  const query = new URLSearchParams()
  appendParams(query, params)
  const text = query.toString()
  return text ? `?${text}` : ''
}

function reportQuery(params: ReportQueryParams): string {
  const query = new URLSearchParams()
  query.set('type', params.type)
  appendParams(query, params)
  return query.toString()
}

export function fetchAnalyticsOverview(
  params: AnalyticsQueryParams = {}
): Promise<AnalyticsOverview> {
  return request<AnalyticsOverview>(`/api/analytics/overview${toQuery(params)}`)
}

export function fetchTicketTrend(params: AnalyticsQueryParams = {}): Promise<TicketTrendData> {
  return request<TicketTrendData>(`/api/analytics/tickets/trend${toQuery(params)}`)
}

export function fetchTicketDistribution(
  params: AnalyticsQueryParams = {}
): Promise<TicketDistributionData> {
  return request<TicketDistributionData>(`/api/analytics/tickets/distribution${toQuery(params)}`)
}

export function fetchSlaOverview(params: AnalyticsQueryParams = {}): Promise<SlaOverview> {
  return request<SlaOverview>(`/api/analytics/sla/overview${toQuery(params)}`)
}

export function fetchSlaTrend(params: AnalyticsQueryParams = {}): Promise<SlaTrendData> {
  return request<SlaTrendData>(`/api/analytics/sla/trend${toQuery(params)}`)
}

export function fetchAgentPerformance(
  params: AnalyticsQueryParams = {}
): Promise<AgentPerformanceData> {
  return request<AgentPerformanceData>(`/api/analytics/agents/performance${toQuery(params)}`)
}

export function fetchAgentRanking(params: AnalyticsQueryParams = {}): Promise<AgentRankingData> {
  return request<AgentRankingData>(`/api/analytics/agents/ranking${toQuery(params)}`)
}

export function fetchAgentTrend(params: AgentTrendQueryParams = {}): Promise<AgentTrendData> {
  return request<AgentTrendData>(`/api/analytics/agents/trend${toQuery(params)}`)
}

export function fetchDispatchOverview(params: AnalyticsQueryParams = {}): Promise<DispatchOverview> {
  return request<DispatchOverview>(`/api/analytics/dispatch/overview${toQuery(params)}`)
}

export function fetchAiOverview(params: AnalyticsQueryParams = {}): Promise<AiOverview> {
  return request<AiOverview>(`/api/analytics/ai/overview${toQuery(params)}`)
}

export function fetchAiSuggestions(params: AnalyticsQueryParams = {}): Promise<AiSuggestionStats> {
  return request<AiSuggestionStats>(`/api/analytics/ai/suggestions${toQuery(params)}`)
}

export function fetchReport(params: ReportQueryParams): Promise<AnalyticsReport> {
  return request<AnalyticsReport>(`/api/analytics/reports?${reportQuery(params)}`)
}

export function exportReport(params: ReportQueryParams): Promise<void> {
  return downloadFile(
    `/api/analytics/reports/export?${reportQuery(params)}`,
    `phase6-${params.type.toLowerCase()}.csv`
  )
}
