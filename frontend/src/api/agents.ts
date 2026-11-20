/** 人员、技能与负载 API：字段与后端 VO 保持一致，组件不直接写 fetch */
import { request } from './request'

export interface AgentSkill {
  skillId: number
  skillCode: string
  skillName: string
  proficiencyLevel: number
  proficiencyLabel: string
}

/** id 即 userId */
export interface Agent {
  id: number
  username: string
  name: string
  role: string
  accountStatus: number
  availability: string
  effectiveAvailability: string
  maxConcurrent: number
  skills: AgentSkill[]
  createdAt: string
  updatedAt: string
}

/** 负载全部来自后端统计，均值为 null 表示暂无完成样本 */
export interface Workload {
  agentId: number
  name: string
  username: string
  role: string
  accountStatus: number
  availability: string
  effectiveAvailability: string
  maxConcurrent: number
  assignedCount: number
  acceptedCount: number
  processingCount: number
  occupied: number
  loadRate: number
  loadLevel: string
  canAccept: boolean
  todayCompleted: number
  weekCompleted: number
  avgHandlingMinutes: number | null
  avgSlaMinutes: number | null
  slaAchievedRate: number | null
  completedSample: number
}

export interface WorkloadOverview {
  totalAgents: number
  availableAgents: number
  busyAgents: number
  offlineAgents: number
  disabledAgents: number
  lowLoad: number
  normalLoad: number
  highLoad: number
  fullLoad: number
  avgLoadRate: number
  totalOccupied: number
  unassignedPending: number
  generatedAt: string
}

/** 技能字典项：技能列表只能来自后端，不在前端写死 */
export interface Skill {
  id: number
  skillCode: string
  skillName: string
  description: string | null
}

export interface AgentSkillInput {
  skillId: number
  proficiencyLevel: number
}

export interface AgentCreateBody {
  userId?: number
  username?: string
  name?: string
  password?: string
  maxConcurrent?: number
  availability?: string
  skills?: AgentSkillInput[]
}

export interface AgentUpdateBody {
  maxConcurrent?: number
  availability?: string
}

export function listAgents(params?: {
  keyword?: string
  availability?: string
  skillCode?: string
}): Promise<Agent[]> {
  const query = new URLSearchParams()
  if (params?.keyword) query.set('keyword', params.keyword)
  if (params?.availability) query.set('availability', params.availability)
  if (params?.skillCode) query.set('skillCode', params.skillCode)
  const suffix = query.toString()
  return request<Agent[]>(suffix ? `/api/agents?${suffix}` : '/api/agents')
}

export function getAgent(id: number): Promise<Agent> {
  return request<Agent>(`/api/agents/${id}`)
}

export function updateAgentAvailability(id: number, availability: string): Promise<Agent> {
  return request<Agent>(`/api/agents/${id}/availability`, {
    method: 'PUT',
    body: JSON.stringify({ availability }),
  })
}

export function listAgentSkills(id: number): Promise<AgentSkill[]> {
  return request<AgentSkill[]>(`/api/agents/${id}/skills`)
}

export function replaceAgentSkills(
  id: number,
  skills: AgentSkillInput[]
): Promise<AgentSkill[]> {
  return request<AgentSkill[]>(`/api/agents/${id}/skills`, {
    method: 'PUT',
    body: JSON.stringify({ skills }),
  })
}

export function createAgent(body: AgentCreateBody): Promise<Agent> {
  return request<Agent>('/api/agents', { method: 'POST', body: JSON.stringify(body) })
}

export function updateAgent(id: number, body: AgentUpdateBody): Promise<Agent> {
  return request<Agent>(`/api/agents/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function listWorkload(): Promise<Workload[]> {
  return request<Workload[]>('/api/agents/workload')
}

export function getAgentWorkload(id: number): Promise<Workload> {
  return request<Workload>(`/api/agents/${id}/workload`)
}

export function getWorkloadOverview(): Promise<WorkloadOverview> {
  return request<WorkloadOverview>('/api/agents/workload/overview')
}

export function listSkills(): Promise<Skill[]> {
  return request<Skill[]>('/api/skills')
}

export function createSkill(body: {
  skillCode: string
  skillName: string
  description?: string
}): Promise<Skill> {
  return request<Skill>('/api/skills', { method: 'POST', body: JSON.stringify(body) })
}
