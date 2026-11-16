export interface HealthResponse {
  service: string
  status: string
  version: string
  timestamp: string
}

export interface ComponentCheck {
  name: string
  status: string
  message: string
}

export interface ReadinessResponse {
  service: string
  status: string
  version: string
  timestamp: string
  checks: ComponentCheck[]
}

/** 就绪检查：后端会附带 MySQL 等依赖组件状态（失败时返回 503，但仍带 JSON 体） */
export async function checkReadiness(): Promise<ReadinessResponse> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch('/api/health/ready', { signal: controller.signal })
    const data = (await response.json()) as ReadinessResponse
    if (!data || !Array.isArray(data.checks)) {
      throw new Error('就绪检查响应格式不符合预期')
    }
    return data
  } finally {
    clearTimeout(timer)
  }
}

export async function checkBackendHealth(): Promise<HealthResponse> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch('/api/health', { signal: controller.signal })
    if (!response.ok) {
      throw new Error(
        response.status >= 500
          ? `后端返回 ${response.status}，服务可能未就绪`
          : `后端返回 ${response.status}`
      )
    }
    const data = (await response.json()) as HealthResponse
    if (!data.service || !data.status) {
      throw new Error('后端响应格式不符合预期')
    }
    return data
  } catch (error) {
    if (error instanceof TypeError) {
      throw new Error('无法连接后端服务，请确认 Java 后端已在 8080 端口启动')
    }
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('请求超时（5 秒），后端可能未启动或响应缓慢')
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}
