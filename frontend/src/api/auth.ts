/** 认证 API 模块：会话令牌由服务端写入 HttpOnly Cookie，前端不接触任何 Token */
import { request, saveCurrentUser, type CurrentUser } from './request'

export interface LoginResult {
  user: CurrentUser
  passwordChangeRequired: boolean
}

interface LoginResponse {
  user: CurrentUser
  passwordChangeRequired: boolean
}

/**
 * 登录：成功后服务端下发 sf_session（HttpOnly）与 sf_csrf（可读）Cookie。
 * 失败抛后端 message（统一为「用户名或密码错误」，不区分账号是否存在）。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  const data = await request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  saveCurrentUser(data.user)
  return { user: data.user, passwordChangeRequired: data.passwordChangeRequired }
}

/** 修改密码：成功后服务端重新签发会话，首次改密标记清除 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<LoginResult> {
  const data = await request<LoginResponse>('/api/auth/password', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  })
  saveCurrentUser(data.user)
  return { user: data.user, passwordChangeRequired: data.passwordChangeRequired }
}

/** 退出登录：服务端失效会话并清除 Cookie */
export async function logout(): Promise<void> {
  await request<null>('/api/auth/logout', { method: 'POST' })
}

/** 查询当前登录用户（用于刷新页面后恢复用户缓存） */
export async function fetchMe(): Promise<CurrentUser> {
  const user = await request<CurrentUser>('/api/auth/me')
  saveCurrentUser(user)
  return user
}
