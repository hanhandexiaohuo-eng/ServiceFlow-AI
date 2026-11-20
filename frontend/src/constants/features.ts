/**
 * 前端功能开关：未接入后端能力的功能一律为 false。
 * 约定：关闭的能力在菜单中隐藏，直接访问 URL 时显示明确的「功能未启用」页面，
 * 不允许出现菜单可见但接口不存在、或页面可用但权限未校验的情况。
 */
export const FEATURE_FLAGS: Record<string, boolean> = {
  /** 用户管理：后端 /api/admin/users 已接入，仅管理员可见 */
  systemUsers: true,
  /** 操作日志：后端 /api/admin/audit-logs 只读查询已接入，仅管理员可见 */
  systemLogs: true,
  /** AI 模型管理：供应商/模型/场景路由/配额已接入，仅管理员可见 */
  systemAi: true,
}

/** 未声明 feature 的菜单默认可见 */
export function isFeatureEnabled(feature?: string): boolean {
  return !feature || FEATURE_FLAGS[feature] === true
}
