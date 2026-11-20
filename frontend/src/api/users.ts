/** 启用用户列表：分配 / 转交处理人的真实候选 */
import { request } from './request'

export interface UserOption {
  id: number
  username: string
  name: string
  role: string
}

export function listUsers(): Promise<UserOption[]> {
  return request<UserOption[]>('/api/users')
}

/** 仅返回可承担工单的人员（管理员与处理人员），用于分配 / 转交候选 */
export function listAssignableUsers(): Promise<UserOption[]> {
  return request<UserOption[]>('/api/users?assignable=true')
}
