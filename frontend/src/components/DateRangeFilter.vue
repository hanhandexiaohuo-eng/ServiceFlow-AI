<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import {
  formatIsoDate,
  presetDateRange,
  type DateRange,
  type RangePreset,
} from '../composables/useAnalyticsRange'

const props = withDefaults(
  defineProps<{
    modelValue: DateRange
    label?: string
  }>(),
  { label: '统计范围' }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: DateRange): void
  /** 仅在区间合法时触发，页面直接用它发起查询 */
  (e: 'change', value: DateRange): void
}>()

const PRESETS: { label: string; value: RangePreset }[] = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
]

const isInvalid = computed(() => {
  const { startDate, endDate } = props.modelValue
  if (!startDate || !endDate) return false
  return startDate > endDate
})

function diffDays(start: string, end: string): number {
  const startTime = Date.parse(`${start}T00:00:00`)
  const endTime = Date.parse(`${end}T00:00:00`)
  if (Number.isNaN(startTime) || Number.isNaN(endTime)) return -1
  return Math.round((endTime - startTime) / 86400000) + 1
}

/** 只在区间正好等于「截至今天的 7/30/90 天」时高亮预设，避免误判自定义区间 */
const activePreset = computed<RangePreset | null>(() => {
  const { startDate, endDate } = props.modelValue
  if (isInvalid.value || !startDate || !endDate) return null
  if (endDate !== formatIsoDate(new Date())) return null
  const days = diffDays(startDate, endDate)
  if (days === 7 || days === 30 || days === 90) return days
  return null
})

function emitRange(next: DateRange): void {
  emit('update:modelValue', next)
  const invalid = Boolean(next.startDate && next.endDate && next.startDate > next.endDate)
  if (!invalid) emit('change', next)
}

function choosePreset(days: RangePreset): void {
  emitRange(presetDateRange(days))
}

function onStartInput(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  emitRange({ startDate: value, endDate: props.modelValue.endDate })
}

function onEndInput(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  emitRange({ startDate: props.modelValue.startDate, endDate: value })
}
</script>

<template>
  <div class="range-filter">
    <span class="label">{{ label }}</span>

    <div class="seg">
      <button
        v-for="item in PRESETS"
        :key="item.value"
        type="button"
        :class="{ active: activePreset === item.value }"
        @click="choosePreset(item.value)"
      >
        {{ item.label }}
      </button>
    </div>

    <div class="dates">
      <input
        type="date"
        :value="modelValue.startDate"
        aria-label="开始日期"
        @input="onStartInput"
      />
      <span class="tilde">~</span>
      <input type="date" :value="modelValue.endDate" aria-label="结束日期" @input="onEndInput" />
    </div>

    <p v-if="isInvalid" class="error">
      <AppIcon name="alert" :size="14" />
      开始日期不能晚于结束日期
    </p>
  </div>
</template>

<style scoped>
.range-filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 14px;
  padding: 10px 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-3);
  white-space: nowrap;
}

/* 与驾驶舱一致的分段控件风格 */
.seg {
  display: flex;
  background: #f1f5f9;
  border-radius: 8px;
  padding: 3px;
}
.seg button {
  border: none;
  background: transparent;
  color: var(--ink-3);
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.seg button:hover {
  color: var(--ink-1);
}
.seg button.active {
  background: #fff;
  color: var(--brand);
  font-weight: 600;
  box-shadow: var(--shadow-card);
}

.dates {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dates input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctrl);
  background: var(--panel);
  color: var(--ink-1);
  font-size: 12px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.dates input:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.tilde {
  font-size: 12px;
  color: var(--ink-4);
}

.error {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #b91c1c;
}
</style>
