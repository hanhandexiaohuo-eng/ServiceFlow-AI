<script setup lang="ts">
/**
 * AI 效果分析：调用成功率与建议采纳率来自两个独立接口，分别展示。
 * 模型返回成功只代表调用链路成功，不代表建议内容正确，页面不计算模型准确率。
 */
import { computed, onMounted, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import AppIcon from '../../components/AppIcon.vue'
import BaseChart from '../../components/BaseChart.vue'
import ChartCard from '../../components/ChartCard.vue'
import DataTable from '../../components/DataTable.vue'
import DateRangeFilter from '../../components/DateRangeFilter.vue'
import EmptyState from '../../components/EmptyState.vue'
import LoadingState from '../../components/LoadingState.vue'
import StatCard from '../../components/StatCard.vue'
import { ANALYSIS_TYPE_LABEL, SUGGESTION_TYPE_LABEL } from '../../constants/ai'
import { useAnalyticsRange, type DateRange } from '../../composables/useAnalyticsRange'
import {
  fetchAiOverview,
  fetchAiSuggestions,
  type AiOverview,
  type AiSuggestionStats,
} from '../../api/analytics'
import type { TableColumn } from '../../types/ui'

type Tone = 'brand' | 'cyan' | 'amber' | 'emerald' | 'rose' | 'violet'

interface StatItem {
  key: string
  label: string
  value: number | string
  icon: string
  tone: Tone
  desc: string
}

/** 表格行只存放已格式化的展示值，模板里不再做类型判断 */
type TableRow = Record<string, string | number>

const { rangeParams, setCustom } = useAnalyticsRange()

const rangeModel = computed<DateRange>({
  get: () => rangeParams.value,
  set: (next) => setCustom(next),
})

const overview = ref<AiOverview | null>(null)
const suggestions = ref<AiSuggestionStats | null>(null)
const loading = ref(true)
const errorMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [callData, suggestionData] = await Promise.all([
      fetchAiOverview(rangeParams.value),
      fetchAiSuggestions(rangeParams.value),
    ])
    overview.value = callData
    suggestions.value = suggestionData
  } catch (error) {
    overview.value = null
    suggestions.value = null
    errorMessage.value = error instanceof Error ? error.message : 'AI 效果数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

const callRate = computed<number | null>(() => overview.value?.successRate ?? null)
const adoptionRate = computed<number | null>(() => suggestions.value?.adoptionRate ?? null)

const callDenominator = computed(() => {
  const data = overview.value
  return data ? data.successCount + data.failedCount : 0
})

const decidedCount = computed(() => {
  const data = suggestions.value
  return data ? data.accepted + data.rejected : 0
})

const features = computed(() => overview.value?.features ?? [])

const isEmpty = computed(() => {
  const callData = overview.value
  const suggestionData = suggestions.value
  if (!callData || !suggestionData) return false
  return callData.totalCalls === 0 && suggestionData.total === 0
})

const notes = computed<string[]>(() => {
  const merged = [...(overview.value?.notes ?? []), ...(suggestions.value?.notes ?? [])]
  return Array.from(new Set(merged))
})

function formatRate(value: number | null): string {
  if (value === null) return '-'
  const text = value.toFixed(1)
  return `${text.endsWith('.0') ? text.slice(0, -2) : text}%`
}

function formatLatency(value: number | null): string {
  if (value === null) return '-'
  return `${Math.round(value)} ms`
}

function typeLabel(value: string): string {
  return ANALYSIS_TYPE_LABEL[value] ?? value
}

/* ---------------- 指标卡 ---------------- */

const callStats = computed<StatItem[]>(() => {
  const data = overview.value
  return [
    {
      key: 'total',
      label: '调用总次数',
      value: data ? data.totalCalls : '-',
      icon: 'sparkles',
      tone: 'brand',
      desc: '区间内全部 AI 调用记录',
    },
    {
      key: 'success',
      label: '成功',
      value: data ? data.successCount : '-',
      icon: 'check',
      tone: 'emerald',
      desc: 'status = SUCCESS',
    },
    {
      key: 'failed',
      label: '失败',
      value: data ? data.failedCount : '-',
      icon: 'close',
      tone: 'rose',
      desc: 'status = FAILED',
    },
    {
      key: 'pending',
      label: '待处理',
      value: data ? data.pendingCount : '-',
      icon: 'clock',
      tone: 'amber',
      desc: 'PENDING 不计入成功率分母',
    },
    {
      key: 'rate',
      label: '调用成功率',
      value: data ? formatRate(data.successRate) : '-',
      icon: 'gauge',
      tone: 'cyan',
      desc: 'SUCCESS / (SUCCESS + FAILED)',
    },
    {
      key: 'latency',
      label: '平均响应耗时',
      value: data ? formatLatency(data.avgLatencyMs) : '-',
      icon: 'activity',
      tone: 'violet',
      desc: '仅 SUCCESS/FAILED 且 latency 非空',
    },
  ]
})

const suggestionStats = computed<StatItem[]>(() => {
  const data = suggestions.value
  return [
    {
      key: 'total',
      label: '建议总数',
      value: data ? data.total : '-',
      icon: 'bulb',
      tone: 'brand',
      desc: '区间内全部 AI 建议记录',
    },
    {
      key: 'accepted',
      label: '已采纳',
      value: data ? data.accepted : '-',
      icon: 'check',
      tone: 'emerald',
      desc: 'ACCEPTED + EXECUTED',
    },
    {
      key: 'rejected',
      label: '已拒绝',
      value: data ? data.rejected : '-',
      icon: 'close',
      tone: 'rose',
      desc: 'status = REJECTED',
    },
    {
      key: 'pending',
      label: '待确认',
      value: data ? data.pending : '-',
      icon: 'clock',
      tone: 'amber',
      desc: 'PENDING 不计入采纳率分母',
    },
    {
      key: 'rate',
      label: '建议采纳率',
      value: data ? formatRate(data.adoptionRate) : '-',
      icon: 'gauge',
      tone: 'cyan',
      desc: '已采纳 / 已决策',
    },
  ]
})

/* ---------------- 图表 ---------------- */

/** 固定枚举 5 类分析功能，区间内没有调用的类型显示 0，便于横向对比 */
const FEATURE_TYPES = ['TICKET_ANALYSIS', 'PRIORITY', 'SOLUTION', 'DISPATCH', 'CHAT']

const featureSeries = computed(() => {
  const map = new Map(features.value.map((item) => [item.analysisType, item]))
  return FEATURE_TYPES.map((type) => {
    const item = map.get(type)
    return {
      label: typeLabel(type),
      success: item?.success ?? 0,
      failed: item?.failed ?? 0,
    }
  })
})

const featureBarOption = computed<EChartsCoreOption>(() => {
  const items = featureSeries.value
  return {
    color: ['#10b981', '#ef4444'],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
    },
    legend: {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 9,
      itemHeight: 9,
      itemGap: 16,
      textStyle: { color: '#64748b', fontSize: 11 },
      data: ['成功', '失败'],
    },
    grid: { left: 6, right: 12, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: items.map((item) => item.label),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    series: [
      {
        name: '成功',
        type: 'bar',
        barWidth: 14,
        barGap: '24%',
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        data: items.map((item) => item.success),
      },
      {
        name: '失败',
        type: 'bar',
        barWidth: 14,
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        data: items.map((item) => item.failed),
      },
    ],
  } as EChartsCoreOption
})

const latencyItems = computed(() =>
  features.value.flatMap((item) =>
    item.avgLatencyMs === null
      ? []
      : [{ label: typeLabel(item.analysisType), value: item.avgLatencyMs }]
  )
)

const latencyOption = computed<EChartsCoreOption>(() => {
  const items = latencyItems.value
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      valueFormatter: (value: unknown) => `${value} ms`,
    },
    grid: { left: 6, right: 52, top: 8, bottom: 4, containLabel: true },
    xAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    yAxis: {
      type: 'category',
      data: items.map((item) => item.label),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        itemStyle: { color: '#4f46e5', borderRadius: [0, 7, 7, 0] },
        label: {
          show: true,
          position: 'right',
          formatter: '{c} ms',
          color: '#334155',
          fontSize: 11,
          fontWeight: 600,
        },
        data: items.map((item) => item.value),
      },
    ],
  } as EChartsCoreOption
})

/** 单个百分比环形图：两个指标各用一张，避免合并后概念混淆 */
function ringOption(rate: number | null, color: string): EChartsCoreOption {
  const value = rate ?? 0
  return {
    tooltip: { show: false },
    series: [
      {
        type: 'pie',
        radius: ['74%', '90%'],
        center: ['50%', '50%'],
        silent: true,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        data: [
          { value, itemStyle: { color } },
          { value: Math.max(0, 100 - value), itemStyle: { color: '#eef2f7' } },
        ],
      },
    ],
  } as EChartsCoreOption
}

const callRingOption = computed(() => ringOption(callRate.value, '#4f46e5'))
const adoptionRingOption = computed(() => ringOption(adoptionRate.value, '#10b981'))

/* ---------------- 表格 ---------------- */

const suggestionColumns: TableColumn[] = [
  { key: 'typeLabel', label: '建议类型', width: '150px', nowrap: true },
  { key: 'total', label: '总数', width: '80px', align: 'right' },
  { key: 'pending', label: '待确认', width: '88px', align: 'right' },
  { key: 'accepted', label: '已采纳', width: '88px', align: 'right' },
  { key: 'rejected', label: '已拒绝', width: '88px', align: 'right' },
  { key: 'decided', label: '已决策', width: '88px', align: 'right' },
  { key: 'adoptionRateText', label: '采纳率', width: '96px', align: 'right', nowrap: true },
]

const suggestionRows = computed<TableRow[]>(() =>
  (suggestions.value?.byType ?? []).map((item) => ({
    typeLabel: SUGGESTION_TYPE_LABEL[item.suggestionType],
    total: item.total,
    pending: item.pending,
    accepted: item.accepted,
    rejected: item.rejected,
    decided: item.decided,
    adoptionRateText: formatRate(item.adoptionRate),
  }))
)

const errorColumns: TableColumn[] = [
  { key: 'errorCode', label: '错误码', nowrap: true },
  { key: 'count', label: '失败次数', width: '110px', align: 'right' },
]

const errorRows = computed<TableRow[]>(() =>
  (overview.value?.errors ?? []).map((item) => ({
    errorCode: String(item.errorCode ?? '').trim() || 'UNKNOWN',
    count: item.count,
  }))
)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <h1>AI 效果分析</h1>
      <p class="head-sub">
        调用成功率与建议采纳率是两个独立指标：模型返回成功不等于建议正确。页面不计算模型准确率，
        所有数字来自后端统计，无法计算时显示 -。
      </p>
    </section>

    <DateRangeFilter v-model="rangeModel" label="统计区间" @change="load" />

    <p v-if="errorMessage" class="alert">
      <AppIcon name="alert" :size="15" />{{ errorMessage }}
    </p>

    <section v-if="loading" class="panel">
      <LoadingState text="正在加载 AI 效果数据" :height="220" />
    </section>

    <section v-else-if="isEmpty" class="panel">
      <EmptyState
        icon="sparkles"
        title="所选区间内暂无 AI 效果数据"
        desc="当前日期范围内没有 AI 调用或建议记录，可调整统计区间后重试。"
      />
    </section>

    <template v-else-if="!errorMessage">
      <div class="group-head">
        <h2 class="section-title">AI 调用</h2>
        <span class="group-hint">成功率 = SUCCESS / (SUCCESS + FAILED)，PENDING 不计入分母</span>
      </div>
      <section class="stats">
        <StatCard
          v-for="item in callStats"
          :key="item.key"
          :label="item.label"
          :value="item.value"
          :icon="item.icon"
          :tone="item.tone"
          :desc="item.desc"
        />
      </section>

      <div class="group-head">
        <h2 class="section-title">AI 建议</h2>
        <span class="group-hint">采纳率 = (ACCEPTED + EXECUTED) / 已决策，待确认不计入分母</span>
      </div>
      <section class="stats">
        <StatCard
          v-for="item in suggestionStats"
          :key="item.key"
          :label="item.label"
          :value="item.value"
          :icon="item.icon"
          :tone="item.tone"
          :desc="item.desc"
        />
      </section>

      <div class="grid grid-2">
        <ChartCard
          title="AI 调用成功率"
          subtitle="SUCCESS / (SUCCESS + FAILED)"
          hint="PENDING 调用不进入分母；该指标只反映调用链路是否成功，不代表建议内容正确。"
        >
          <div v-if="callRate !== null && overview" class="gauge">
            <div class="gauge-ring">
              <BaseChart :option="callRingOption" :height="168" />
              <div class="gauge-center">
                <span class="gauge-value num">{{ formatRate(callRate) }}</span>
                <span class="gauge-text">调用成功率</span>
              </div>
            </div>
            <p class="gauge-desc">
              成功 <b class="num">{{ overview.successCount }}</b> 次 / 有效调用
              <b class="num">{{ callDenominator }}</b> 次；待处理
              <b class="num">{{ overview.pendingCount }}</b> 次不计入分母
            </p>
          </div>
          <EmptyState
            v-else
            compact
            icon="gauge"
            title="暂无可计算的调用成功率"
            desc="区间内没有 SUCCESS 或 FAILED 调用"
          />
        </ChartCard>

        <ChartCard
          title="AI 建议采纳率"
          subtitle="(ACCEPTED + EXECUTED) / 已决策"
          hint="待确认建议不进入分母；采纳率反映业务侧是否接受建议，与调用是否成功无关。"
        >
          <div v-if="adoptionRate !== null && suggestions" class="gauge">
            <div class="gauge-ring">
              <BaseChart :option="adoptionRingOption" :height="168" />
              <div class="gauge-center">
                <span class="gauge-value num">{{ formatRate(adoptionRate) }}</span>
                <span class="gauge-text">建议采纳率</span>
              </div>
            </div>
            <p class="gauge-desc">
              已采纳 <b class="num">{{ suggestions.accepted }}</b> 条 / 已决策
              <b class="num">{{ decidedCount }}</b> 条；待确认
              <b class="num">{{ suggestions.pending }}</b> 条不计入分母
            </p>
          </div>
          <EmptyState
            v-else
            compact
            icon="bulb"
            title="暂无可计算的建议采纳率"
            desc="区间内没有已决策的 AI 建议"
          />
        </ChartCard>
      </div>

      <ChartCard
        title="各功能调用量"
        subtitle="按 analysisType 分组统计成功与失败"
        hint="仅展示区间内实际产生过调用记录的功能类型。"
      >
        <BaseChart v-if="features.length" :option="featureBarOption" :height="300" />
        <EmptyState v-else icon="chart" title="暂无调用记录" desc="所选区间内没有 AI 调用" />
      </ChartCard>

      <div class="grid grid-2">
        <ChartCard
          title="各功能平均耗时"
          subtitle="单位：毫秒，仅统计 latency_ms 非空的 SUCCESS/FAILED 调用"
          hint="横向柱长度代表平均响应耗时，不代表建议质量。"
        >
          <BaseChart v-if="latencyItems.length" :option="latencyOption" :height="260" />
          <EmptyState
            v-else
            compact
            icon="clock"
            title="暂无耗时数据"
            desc="区间内没有记录响应耗时的调用"
          />
        </ChartCard>

        <ChartCard
          title="错误码明细"
          subtitle="仅统计 FAILED 调用"
          hint="error_code 为空或空白时归为 UNKNOWN；后端最多返回前 10 个错误码。"
          body-class="flush"
        >
          <DataTable
            :columns="errorColumns"
            :rows="errorRows"
            row-key="errorCode"
            dense
            empty-text="区间内没有失败调用"
          />
        </ChartCard>
      </div>

      <ChartCard
        title="建议类型采纳情况"
        subtitle="分类建议 / 优先级建议 / 处理方案"
        hint="采纳率 = (ACCEPTED + EXECUTED) / (ACCEPTED + EXECUTED + REJECTED)，待确认不计入分母。"
        body-class="flush"
      >
        <DataTable
          :columns="suggestionColumns"
          :rows="suggestionRows"
          row-key="typeLabel"
          dense
          empty-text="区间内没有 AI 建议记录"
        />
      </ChartCard>

      <section class="panel notes-panel">
        <h2 class="section-title">口径说明</h2>
        <ul class="notes">
          <li v-for="(note, index) in notes" :key="index">{{ note }}</li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page-head h1 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink-1);
}
.head-sub {
  margin-top: 4px;
  max-width: 980px;
  font-size: 12px;
  color: var(--ink-4);
  line-height: 1.7;
}

.panel {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  min-width: 0;
}
.panel:not(.notes-panel) {
  padding: 16px 18px;
}

.alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  font-size: 13px;
}

.group-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 12px;
  flex-wrap: wrap;
  margin-bottom: -6px;
}
.group-hint {
  font-size: 12px;
  color: var(--ink-4);
}

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}

.gauge {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
}
.gauge-ring {
  position: relative;
  width: 200px;
  max-width: 100%;
}
.gauge-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.gauge-value {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink-1);
}
.gauge-text {
  font-size: 11px;
  color: var(--ink-4);
}
.gauge-desc {
  font-size: 12px;
  color: var(--ink-3);
  text-align: center;
  line-height: 1.7;
}
.gauge-desc b {
  color: var(--ink-1);
  font-weight: 600;
}

.notes-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 18px;
}
.notes {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.notes li {
  position: relative;
  padding-left: 14px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.7;
}
.notes li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 8px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #cbd5e1;
}
</style>
