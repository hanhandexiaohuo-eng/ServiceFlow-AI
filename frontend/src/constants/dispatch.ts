/** 人员调度展示映射：人员状态、负载等级、技能熟练度均由后端枚举驱动 */

export const AVAILABILITY_OPTIONS = ['AVAILABLE', 'BUSY', 'OFFLINE', 'DISABLED'] as const

export const AVAILABILITY_LABEL: Record<string, string> = {
  AVAILABLE: '可接单',
  BUSY: '繁忙',
  OFFLINE: '不可用',
  DISABLED: '已禁用',
}

export const LOAD_LEVEL_LABEL: Record<string, string> = {
  LOW: '低负载',
  NORMAL: '正常负载',
  HIGH: '高负载',
  FULL: '满负载',
}

export const LOAD_LEVEL_COLOR: Record<string, string> = {
  LOW: '#10b981',
  NORMAL: '#4f46e5',
  HIGH: '#f59e0b',
  FULL: '#ef4444',
}

export interface ProficiencyOption {
  value: number
  label: string
}

export const PROFICIENCY_OPTIONS: ProficiencyOption[] = [
  { value: 1, label: '初级' },
  { value: 2, label: '中级' },
  { value: 3, label: '高级' },
  { value: 4, label: '专家' },
]

export const DISPATCH_ACTION_LABEL: Record<string, string> = {
  RECOMMEND: '生成推荐',
  ASSIGN: '确认分配',
}
