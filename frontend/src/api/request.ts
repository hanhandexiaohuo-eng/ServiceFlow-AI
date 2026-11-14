/**
 * 统一请求封装：所有业务 API 走这里。
 * - 自动携带 Authorization: Bearer <token>（token 只存 localStorage，不打日志、不上 URL）
 * - 收到 401 → 清除本地登录态并跳转登录页（登录接口自身的 401 除外，由登录页展示错误）
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

const TOKEN_KEY = 'sf_token'
const USER_KEY = 'sf_user'

export interface CurrentUser {
  id: number
  username: string
  name: string
  role: string
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function saveAuth(token: string, user: CurrentUser): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function getCurrentUser(): CurrentUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as CurrentUser
  } catch {
    return null
  }
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

async function redirectToLogin(): Promise<void> {
  // 动态导入避免 request.ts ↔ router 循环依赖
  const { router } = await import('../router')
  if (router.currentRoute.value.path !== '/login') {
    await router.push('/login')
  }
}

export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  }
  const token = getToken()
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  const response = await fetch(url, { ...options, headers })
  const body = (await response.json()) as ApiResponse<T>
  if (response.status === 401) {
    clearAuth()
    await redirectToLogin()
    throw new Error(body.message || '未登录或登录已过期')
  }
  if (!response.ok) {
    throw new Error(body.message || `请求失败：${response.status}`)
  }
  return body.data
}
