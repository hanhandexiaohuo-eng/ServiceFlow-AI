/** SLA 策略：当前由后端代码定义，只读展示 */
import { request } from './request'

export interface SlaPolicy {
  priority: string
  hours: number
  warningMinutes: number
  label: string
}

export function listSlaPolicies(): Promise<SlaPolicy[]> {
  return request<SlaPolicy[]>('/api/sla/policies')
}
