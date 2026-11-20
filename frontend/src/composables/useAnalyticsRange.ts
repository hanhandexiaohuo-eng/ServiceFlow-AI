/**
 * 运营分析日期区间：默认最近 30 天，提供 7/30/90 天预设与自定义区间。
 * 全部为本地日期（yyyy-MM-dd），不走 UTC，避免时区导致日期偏移。
 */
import { computed, ref } from 'vue'

export type RangePreset = 7 | 30 | 90

export interface DateRange {
  startDate: string
  endDate: string
}

/** 本地时区格式化为 yyyy-MM-dd */
export function formatIsoDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 最近 N 天（含今天），返回 yyyy-MM-dd */
export function presetDateRange(days: RangePreset): DateRange {
  const end = new Date()
  const start = new Date(end.getFullYear(), end.getMonth(), end.getDate() - (days - 1))
  return { startDate: formatIsoDate(start), endDate: formatIsoDate(end) }
}

export function useAnalyticsRange(initialPreset: RangePreset = 30) {
  const initial = presetDateRange(initialPreset)
  const startDate = ref(initial.startDate)
  const endDate = ref(initial.endDate)
  const preset = ref<RangePreset | null>(initialPreset)

  const isInvalid = computed(() => {
    if (!startDate.value || !endDate.value) return false
    // yyyy-MM-dd 可直接按字典序比较
    return startDate.value > endDate.value
  })

  function setPreset(days: RangePreset): void {
    const range = presetDateRange(days)
    startDate.value = range.startDate
    endDate.value = range.endDate
    preset.value = days
  }

  function setCustom(range: Partial<DateRange>): void {
    if (range.startDate !== undefined) startDate.value = range.startDate
    if (range.endDate !== undefined) endDate.value = range.endDate
    preset.value = null
  }

  /** 直接展开进 AnalyticsQueryParams，避免页面重复拼装 */
  const rangeParams = computed<DateRange>(() => ({
    startDate: startDate.value,
    endDate: endDate.value,
  }))

  return { startDate, endDate, preset, setPreset, setCustom, rangeParams, isInvalid }
}
