/** 管理端用户与审计日志 API：仅 ADMIN 可用，写操作由后端鉴权 + CSRF + 审计 */
import { request } from './request'

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface AdminUser {
  id: number
  username: string
  name: string
  role: string
  status: number
  department: string | null
  passwordChangeRequired: boolean
  lastLoginAt: string | null
  createdAt: string | null
}

export interface AdminUserQuery {
  keyword?: string
  role?: string
  status?: number
  page: number
  size: number
}

export interface AdminUserCreateBody {
  username: string
  name: string
  role: string
  department?: string
  password: string
}

export interface AuditLog {
  id: number
  actorId: number | null
  actorUsername: string | null
  action: string
  objectType: string | null
  objectId: string | null
  detail: string | null
  ip: string | null
  requestId: string | null
  result: string
  failureReason: string | null
  createdAt: string | null
}

export interface AuditLogQuery {
  action?: string
  actor?: string
  result?: string
  from?: string
  to?: string
  page: number
  size: number
}

function queryString(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === '') return
    search.set(key, String(value))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export async function listAdminUsers(query: AdminUserQuery): Promise<PageResult<AdminUser>> {
  return request<PageResult<AdminUser>>(`/api/admin/users${queryString({ ...query })}`)
}

export async function createAdminUser(body: AdminUserCreateBody): Promise<AdminUser> {
  return request<AdminUser>('/api/admin/users', { method: 'POST', body: JSON.stringify(body) })
}

export async function updateAdminUserProfile(
  id: number,
  body: { name: string; department?: string }
): Promise<AdminUser> {
  return request<AdminUser>(`/api/admin/users/${id}/profile`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export async function updateAdminUserStatus(id: number, status: number): Promise<AdminUser> {
  return request<AdminUser>(`/api/admin/users/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  })
}

export async function updateAdminUserRole(id: number, role: string): Promise<AdminUser> {
  return request<AdminUser>(`/api/admin/users/${id}/role`, {
    method: 'PUT',
    body: JSON.stringify({ role }),
  })
}

export async function resetAdminUserPassword(id: number, password: string): Promise<void> {
  return request<void>(`/api/admin/users/${id}/password-reset`, {
    method: 'POST',
    body: JSON.stringify({ password }),
  })
}

export async function listAuditLogs(query: AuditLogQuery): Promise<PageResult<AuditLog>> {
  return request<PageResult<AuditLog>>(`/api/admin/audit-logs${queryString({ ...query })}`)
}
