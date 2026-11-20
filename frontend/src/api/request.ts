/**
 * 统一请求封装：所有业务 API 走这里。
 *
 * 会话安全模型（服务端为准）：
 * - 访问令牌只存在于服务端下发的 HttpOnly Cookie（sf_session），前端脚本读不到、也不保存任何 Token。
 * - 写操作走 CSRF 双提交：从可读 Cookie（sf_csrf）取值，回填 X-CSRF-Token 请求头。
 * - 本地只缓存展示用的用户信息（用户名/显示名/角色），不含任何凭据。
 * - 401 → 清本地缓存并跳登录；403「请先修改初始密码」→ 跳强制改密页。
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

const USER_KEY = 'sf_user'
const CSRF_COOKIE = 'sf_csrf'

/** 后端在「必须修改初始密码」时返回的 403 文案，用于前端路由分流 */
export const PASSWORD_CHANGE_REQUIRED_MESSAGE = '请先修改初始密码'

/**
 * API 基址：默认为空串，即同源请求（开发时由 Vite 代理 /api，生产由 nginx 反代 /api）。
 * 前后端分域部署时通过构建期变量 VITE_API_BASE_URL 指定，代码中不写死任何环境地址。
 */
const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

/** 拼接 API 基址；已是绝对地址的 URL 原样返回 */
export function apiUrl(path: string): string {
  return /^https?:\/\//i.test(path) ? path : `${API_BASE}${path}`
}

export interface CurrentUser {
  id: number
  username: string
  name: string
  role: string
}

/** 读取指定 Cookie；不存在时返回 null */
function readCookie(name: string): string | null {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const item = part.trim()
    if (item.startsWith(prefix)) {
      const value = item.slice(prefix.length)
      try {
        return decodeURIComponent(value)
      } catch {
        return value
      }
    }
  }
  return null
}

/** 缓存当前用户（仅展示信息，不含凭据） */
export function saveCurrentUser(user: CurrentUser): void {
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

/** 本地是否存在会话提示（真实有效性以服务端 Cookie 校验为准） */
export function hasSessionHint(): boolean {
  return getCurrentUser() !== null
}

/** 清除本地用户缓存；服务端 Cookie 由退出接口负责失效 */
export function clearAuth(): void {
  localStorage.removeItem(USER_KEY)
}

async function redirectToLogin(): Promise<void> {
  // 动态导入避免 request.ts ↔ router 循环依赖
  const { router } = await import('../router')
  if (router.currentRoute.value.path !== '/login') {
    await router.push('/login')
  }
}

async function redirectToChangePassword(): Promise<void> {
  const { router } = await import('../router')
  if (router.currentRoute.value.path !== '/change-password') {
    await router.push('/change-password')
  }
}

function isUnsafeMethod(method: string): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())
}

/** 组装请求头：JSON 默认值 + CSRF 双提交令牌（写操作） */
function buildHeaders(options: RequestInit, jsonBody: boolean): Record<string, string> {
  const headers: Record<string, string> = {
    ...(jsonBody ? { 'Content-Type': 'application/json' } : {}),
    ...(options.headers as Record<string, string> | undefined),
  }
  const method = (options.method ?? 'GET').toString()
  if (isUnsafeMethod(method)) {
    const csrf = readCookie(CSRF_COOKIE)
    if (csrf) {
      headers['X-CSRF-Token'] = csrf
    }
  }
  return headers
}

export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const jsonBody = typeof options.body === 'string' || options.body === undefined
  const response = await fetch(apiUrl(url), {
    ...options,
    headers: buildHeaders(options, jsonBody),
    credentials: 'same-origin',
  })
  const body = (await response.json()) as ApiResponse<T>
  if (response.status === 401) {
    clearAuth()
    await redirectToLogin()
    throw new Error(body.message || '未登录或登录已过期')
  }
  if (response.status === 403 && body.message === PASSWORD_CHANGE_REQUIRED_MESSAGE) {
    await redirectToChangePassword()
    throw new Error(body.message)
  }
  if (!response.ok) {
    throw new Error(body.message || `请求失败：${response.status}`)
  }
  return body.data
}

/** 从 Content-Disposition 取文件名，兼容 filename="..." 与 RFC 5987 编码 */
function filenameFromDisposition(disposition: string | null): string | null {
  if (!disposition) return null
  const match = /filename\*?=(?:UTF-8'')?(?:"([^"]*)"|([^;]*))/i.exec(disposition)
  if (!match) return null
  const raw = (match[1] ?? match[2] ?? '').trim()
  if (!raw) return null
  try {
    return decodeURIComponent(raw)
  } catch {
    return raw
  }
}

/**
 * 文件下载：复用 Cookie 会话，响应是二进制而非 JSON。
 * 非 2xx 时仍按 JSON 错误体解析，把后端 message 原样抛给调用方。
 */
export async function downloadFile(path: string, filenameFallback = 'download'): Promise<void> {
  const response = await fetch(apiUrl(path), { credentials: 'same-origin' })
  if (!response.ok) {
    let message = `下载失败：${response.status}`
    try {
      const body = (await response.json()) as ApiResponse<unknown>
      if (body && typeof body.message === 'string' && body.message.trim()) {
        message = body.message
      }
    } catch {
      // 错误体不是 JSON 时保留状态码提示
    }
    if (response.status === 401) {
      clearAuth()
      await redirectToLogin()
    }
    throw new Error(message)
  }

  const filename =
    filenameFromDisposition(response.headers.get('Content-Disposition')) ?? filenameFallback
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
