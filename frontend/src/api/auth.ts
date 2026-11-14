/** 认证 API 模块 */
import { request, saveAuth, type CurrentUser } from './request'

interface LoginResponse {
  token: string
  user: CurrentUser
}

/** 登录成功会保存 token + 用户信息到 localStorage；失败抛后端 message（如"用户名或密码错误"） */
export async function login(username: string, password: string): Promise<CurrentUser> {
  const data = await request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  saveAuth(data.token, data.user)
  return data.user
}
