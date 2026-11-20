/** AI 模块展示元数据：标签、类型选项与结构化结果类型 */
import type { AiAnalysisType, SuggestionStatus, SuggestionType } from '../api/ai'

export const ANALYSIS_TYPES: Array<{
  key: Exclude<AiAnalysisType, 'CHAT'>
  label: string
  desc: string
  icon: string
}> = [
  { key: 'TICKET_ANALYSIS', label: '智能分析', desc: '分类、摘要、技能与影响范围', icon: 'sparkles' },
  { key: 'PRIORITY', label: '优先级建议', desc: '结合影响面与 SLA 给出优先级', icon: 'gauge' },
  { key: 'SOLUTION', label: '处理方案', desc: '原因、排查步骤与验证方式', icon: 'bulb' },
  { key: 'DISPATCH', label: '调度辅助', desc: '基于规则调度候选做解释', icon: 'route' },
]

export const ANALYSIS_TYPE_LABEL: Record<string, string> = {
  TICKET_ANALYSIS: '智能分析',
  PRIORITY: '优先级建议',
  SOLUTION: '处理方案',
  DISPATCH: '调度辅助',
  CHAT: '会话追问',
}

export const ANALYSIS_STATUS_LABEL: Record<string, string> = {
  PENDING: '分析中',
  SUCCESS: '已完成',
  FAILED: '失败',
}

export const SUGGESTION_TYPE_LABEL: Record<SuggestionType, string> = {
  CLASSIFICATION: '分类建议',
  PRIORITY: '优先级建议',
  SOLUTION: '处理方案',
}

export const SUGGESTION_STATUS_LABEL: Record<SuggestionStatus, string> = {
  PENDING: '待确认',
  ACCEPTED: '已采纳',
  REJECTED: '已拒绝',
  EXECUTED: '已执行',
}

export const KNOWLEDGE_CATEGORY_LABEL: Record<string, string> = {
  NETWORK: '网络',
  SYSTEM: '系统',
  DATABASE: '数据库',
  SECURITY: '安全',
  SOFTWARE: '软件',
  ACCOUNT: '账号',
  HARDWARE: '硬件',
  OTHER: '其他',
}

export const KNOWLEDGE_CATEGORIES = Object.keys(KNOWLEDGE_CATEGORY_LABEL)

export const KNOWLEDGE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
}

/* ---------------- 结构化结果：字段与 Python Schema 一一对应 ---------------- */

export interface TicketAnalysisResult {
  recommendedType: string
  typeConfidence: number
  problemCategory: string
  summary: string
  keyFacts: string[]
  technicalDomains: string[]
  requiredSkills: string[]
  impactScope: string
  recommendedPriority: string
  priorityConfidence: number
  keywords: string[]
  reasons: string[]
  confidence: number
}

export interface PriorityResult {
  recommendedPriority: string
  reasons: string[]
  risks: string[]
  slaImpact: string
  suggestedResponseHours: number
  confidence: number
}

export interface SolutionResult {
  problemAnalysis: string
  possibleCauses: string[]
  diagnosticSteps: string[]
  recommendedSolution: string[]
  tools: string[]
  risks: string[]
  verification: string[]
  escalationRecommended: boolean
  escalationReason: string
  preconditions: string[]
  missingInformation: string[]
  confidence: number
}

export interface DispatchAssistCandidate {
  agentId: number
  fit: string
  advantages: string[]
  gaps: string[]
  risk: string
}

export interface DispatchAssistResult {
  summary: string
  recommendedAgentId: number | null
  candidates: DispatchAssistCandidate[]
  confidence: number
}

export interface ChatResult {
  answer: string
  referencedFacts: string[]
  followUpQuestions: string[]
  confidence: number
}

export function percent(value: unknown): string {
  const num = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(num)) return '-'
  return `${Math.round(num * 100)}%`
}
