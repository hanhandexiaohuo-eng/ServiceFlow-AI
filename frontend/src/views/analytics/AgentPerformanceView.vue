<script setup lang="ts">
/**
 * 人员绩效分析：口径与数值全部来自后端 analytics 接口。
 * 当前负载为实时快照，累计处理、已解决与达标率受所选日期区间与筛选约束。
 */
import { computed, ref, watch } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import AppIcon from '../../components/AppIcon.vue'
import BaseChart from '../../components/BaseChart.vue'
import ChartCard from '../../components/ChartCard.vue'
import DataTable from '../../components/DataTable.vue'
import DateRangeFilter from '../../components/DateRangeFilter.vue'
import EmptyState from '../../components/EmptyState.vue'
import StatCard from '../../components/StatCard.vue'
import { useAnalyticsRange, type DateRange } from '../../composables/useAnalyticsRange'
import { PRIORITY_LABEL, PRIORITY_OPTIONS, TYPE_LABEL, TYPE_OPTIONS } from '../../constants/ticket'
import {
  fetchAgentPerformance,
  fetchAgentTrend,
  fetchSlaOverview,
  type AgentPerformanceData,
  type AgentPerformanceItem,
  type AgentTrendData,
  type AgentTrendQueryParams,
  type AnalyticsQueryParams,
  type SlaOverview,
} from '../../api/analytics'
import type { TableColumn } from '../../types/ui'

/* ---------------- 区间与筛选 ---------------- */

const range = useAnalyticsRange(30)
const rangeModel = computed<DateRange>(() => ({
  startDate: range.startDate.value,
  endDate: range.endDate.value,
}))

const priority = ref('')
const ticketType = ref('')

const params = computed<AnalyticsQueryParams>(() => ({
  ...range.rangeParams.value,
  priority: priority.value || undefined,
  ticketType: ticketType.value || undefined,
}))

/* ---------------- 数据加载 ---------------- */

const performance = ref<AgentPerformanceData | null>(null)
const slaOverview = ref<SlaOverview | null>(null)
const trend = ref<AgentTrendData | null>(null)
const loading = ref(false)
const trendLoading = ref(false)
const errorMessage = ref('')
const trendError = ref('')
const agentFilter = ref(0)

async function loadPerformance(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [perf, sla] = await Promise.all([
      fetchAgentPerformance(params.value),
      fetchSlaOverview(params.value),
    ])
    performance.value = perf
    slaOverview.value = sla
  } catch (e) {
    performance.value = null
    slaOverview.value = null
    errorMessage.value = e instanceof Error ? e.message : '人员绩效数据加载失败'
  } finally {
    loading.value = false
  }
}

async function loadTrend(): Promise<void> {
  trendLoading.value = true
  trendError.value = ''
  const query: AgentTrendQueryParams = { ...params.value }
  if (agentFilter.value > 0) query.agentId = agentFilter.value
  try {
    trend.value = await fetchAgentTrend(query)
  } catch (e) {
    trend.value = null
    trendError.value = e instanceof Error ? e.message : '绩效趋势加载失败'
  } finally {
    trendLoading.value = false
  }
}

function onRangeChange(value: DateRange): void {
  range.setCustom(value)
}

watch(
  params,
  () => {
    void loadPerformance()
    void loadTrend()
  },
  { immediate: true }
)

// 人员切换只影响趋势，不重复请求绩效与 SLA 快照
watch(agentFilter, () => {
  void loadTrend()
})

/* ---------------- 格式化 ---------------- */

function fmtPercent(value: number | null): string {
  return value === null ? '-' : `${value.toFixed(1)}%`
}

function fmtMinutes(value: number | null): string {
  return value === null ? '-' : `${value.toFixed(1)} 分钟`
}

/* ---------------- 指标卡 ---------------- */

interface StatItem {
  key: string
  label: string
  value: number | string
  icon: string
  tone: 'brand' | 'cyan' | 'amber' | 'emerald' | 'rose' | 'violet'
  desc: string
}

const agents = computed<AgentPerformanceItem[]>(() => performance.value?.agents ?? [])

const teamResolved = computed(() => agents.value.reduce((sum, item) => sum + item.resolvedTotal, 0))

const teamAvgFirstResponse = computed<number | null>(() => {
  const values = agents.value
    .map((item) => item.avgFirstResponseMinutes)
    .filter((value): value is number => value !== null)
  if (values.length === 0) return null
  return values.reduce((sum, value) => sum + value, 0) / values.length
})

const rankedCount = computed(() => agents.value.filter((item) => !item.lowSample).length)

const stats = computed<StatItem[]>(() => [
  {
    key: 'agents',
    label: '参与统计人员数',
    value: agents.value.length,
    icon: 'users',
    tone: 'brand',
    desc: '含区间内暂无工单的处理人员',
  },
  {
    key: 'resolved',
    label: '团队已解决数',
    value: teamResolved.value,
    icon: 'check',
    tone: 'emerald',
    desc: '所选区间与筛选下的已解决合计',
  },
  {
    key: 'response',
    label: '团队平均首响（分钟）',
    value: teamAvgFirstResponse.value === null ? '-' : teamAvgFirstResponse.value.toFixed(1),
    icon: 'clock',
    tone: 'cyan',
    desc: '有首响样本人员的平均值（分钟）',
  },
  {
    key: 'sla',
    label: '团队 SLA 达标率',
    value: fmtPercent(slaOverview.value?.overallRate ?? null),
    icon: 'gauge',
    tone: 'violet',
    desc: 'SLA 总览的分子 / 分母口径',
  },
  {
    key: 'ranked',
    label: '有效排名人数',
    value: rankedCount.value,
    icon: 'award',
    tone: 'amber',
    desc: '区间分配 ≥ 5 单，参与排名',
  },
])

/* ---------------- 排行榜 ---------------- */

const rankingItems = computed<AgentPerformanceItem[]>(() =>
  [...agents.value].sort((a, b) => b.score - a.score).slice(0, 10)
)

const rankingHint =
  '综合分 = 0.5×SLA响应达标分 + 0.2×完成率分 + 0.2×优先级加权完成量分 + 0.1×负载健康分；' +
  '样本不足（区间分配少于 5 单）的人员以浅色显示，不参与有效排名。'

const rankingOption = computed<EChartsCoreOption>(() => {
  // 类目轴自下而上排列，反转后综合分最高者显示在顶部
  const items = [...rankingItems.value].reverse()
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: '{b}：{c} 分',
    },
    grid: { left: 6, right: 46, top: 8, bottom: 4, containLabel: true },
    xAxis: {
      type: 'value',
      max: 100,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    yAxis: {
      type: 'category',
      data: items.map((item) => item.agentName),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        itemStyle: { borderRadius: [0, 7, 7, 0] },
        label: { show: true, position: 'right', color: '#334155', fontSize: 11, fontWeight: 600 },
        data: items.map((item) => ({
          value: item.score,
          itemStyle: { color: item.lowSample ? '#c7d2fe' : '#4f46e5' },
        })),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 当前负载分布 ---------------- */

interface LoadBucket {
  key: string
  label: string
  color: string
  value: number
}

const LOAD_BUCKETS: { key: string; label: string; color: string }[] = [
  { key: 'lt50', label: '< 50%', color: '#10b981' },
  { key: '50-79', label: '50-79%', color: '#06b6d4' },
  { key: '80-99', label: '80-99%', color: '#f59e0b' },
  { key: 'gte100', label: '≥ 100%', color: '#ef4444' },
  { key: 'none', label: '未配置并发', color: '#94a3b8' },
]

function loadBucketKey(loadPercent: number | null): string {
  if (loadPercent === null) return 'none'
  if (loadPercent < 50) return 'lt50'
  if (loadPercent < 80) return '50-79'
  if (loadPercent < 100) return '80-99'
  return 'gte100'
}

const loadDistribution = computed<LoadBucket[]>(() =>
  LOAD_BUCKETS.map((bucket) => ({
    ...bucket,
    value: agents.value.filter((item) => loadBucketKey(item.loadPercent) === bucket.key).length,
  }))
)

const loadHint = '按「当前占用 / 最大并发」分档，为实时快照，不受所选日期区间影响；未配置最大并发单独成档。'

const loadOption = computed<EChartsCoreOption>(() => {
  const items = loadDistribution.value
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: '{b}：{c} 人',
    },
    grid: { left: 6, right: 16, top: 22, bottom: 4, containLabel: true },
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
        type: 'bar',
        barWidth: 26,
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        label: { show: true, position: 'top', color: '#334155', fontSize: 12, fontWeight: 600 },
        data: items.map((item) => ({
          value: item.value,
          itemStyle: { color: item.color },
        })),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 明细表排序 ---------------- */

const sortKey = ref('score')
const sortDir = ref<'asc' | 'desc'>('desc')

type CellValue = string | number | boolean | null

function cellValue(item: AgentPerformanceItem, key: string): CellValue {
  const record = item as unknown as Record<string, CellValue>
  return record[key] ?? null
}

function toNumber(value: CellValue): number | null {
  if (typeof value === 'number') return value
  if (typeof value === 'boolean') return value ? 1 : 0
  return null
}

const sortedAgents = computed<AgentPerformanceItem[]>(() => {
  const list = [...agents.value]
  const dir = sortDir.value === 'asc' ? 1 : -1
  list.sort((a, b) => {
    if (sortKey.value === 'agentName') {
      return a.agentName.localeCompare(b.agentName, 'zh-Hans-CN') * dir
    }
    const left = toNumber(cellValue(a, sortKey.value))
    const right = toNumber(cellValue(b, sortKey.value))
    if (left === null && right === null) return 0
    if (left === null) return 1
    if (right === null) return -1
    return (left - right) * dir
  })
  return list
})

function onSort(key: string): void {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
    return
  }
  sortKey.value = key
  sortDir.value = key === 'agentName' ? 'asc' : 'desc'
}

const columns: TableColumn[] = [
  { key: 'rank', label: '排名', width: '86px', align: 'center', sortable: true },
  { key: 'agentName', label: '人员', width: '150px', sortable: true },
  { key: 'active', label: '状态', width: '76px', nowrap: true, sortable: true },
  { key: 'assignedTotal', label: '累计处理', width: '92px', align: 'right', sortable: true },
  { key: 'resolvedTotal', label: '已解决', width: '84px', align: 'right', sortable: true },
  { key: 'currentOccupied', label: '当前处理中', width: '100px', align: 'right', sortable: true },
  { key: 'completionRate', label: '完成率', width: '90px', align: 'right', sortable: true },
  { key: 'avgFirstResponseMinutes', label: '平均首响', width: '100px', align: 'right', sortable: true },
  { key: 'avgResolutionMinutes', label: '平均解决', width: '100px', align: 'right', sortable: true },
  { key: 'slaRate', label: 'SLA 达标率', width: '108px', align: 'right', sortable: true },
  { key: 'loadPercent', label: '当前负载', width: '96px', align: 'right', sortable: true },
  { key: 'overdueTotal', label: '超时数', width: '80px', align: 'right', sortable: true },
  { key: 'score', label: '综合分', width: '88px', align: 'right', sortable: true },
]

const scoreColumns: TableColumn[] = [
  { key: 'agentName', label: '人员', width: '170px' },
  { key: 'slaScore', label: 'SLA 响应分', width: '112px', align: 'right' },
  { key: 'completionScore', label: '完成率分', width: '112px', align: 'right' },
  { key: 'volumeScore', label: '加权量分', width: '112px', align: 'right' },
  { key: 'loadScore', label: '负载健康分', width: '112px', align: 'right' },
  { key: 'score', label: '综合分', width: '100px', align: 'right' },
]

function loadToneClass(loadPercent: number | null): string {
  if (loadPercent === null) return 'load-none'
  if (loadPercent < 50) return 'load-ok'
  if (loadPercent < 80) return 'load-mid'
  if (loadPercent < 100) return 'load-high'
  return 'load-over'
}

/* ---------------- 绩效趋势 ---------------- */

const agentOptions = computed<AgentPerformanceItem[]>(() =>
  [...agents.value].sort((a, b) => a.agentName.localeCompare(b.agentName, 'zh-Hans-CN'))
)

const hasTrendData = computed(() =>
  (trend.value?.points ?? []).some(
    (point) => point.resolved > 0 || point.avgFirstResponseMinutes !== null
  )
)

const trendHint =
  trend.value?.note ?? '按日补零，响应达标率与 SLA 模块同口径；平均首响无样本的日期留空。'

const trendOption = computed<EChartsCoreOption>(() => {
  const points = trend.value?.points ?? []
  const showSymbol = points.length <= 31
  return {
    color: ['#4f46e5', '#f59e0b'],
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: '#cbd5e1', type: 'dashed' } },
    },
    legend: {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 9,
      itemHeight: 9,
      itemGap: 16,
      textStyle: { color: '#64748b', fontSize: 11 },
      data: ['已解决数', '平均首响（分钟）'],
    },
    grid: { left: 6, right: 12, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((point) => point.date.slice(5)),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    yAxis: [
      {
        type: 'value',
        name: '已解决',
        minInterval: 1,
        nameTextStyle: { color: '#94a3b8', fontSize: 11 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11 },
        splitLine: { lineStyle: { color: '#f1f4f9' } },
      },
      {
        type: 'value',
        name: '分钟',
        nameTextStyle: { color: '#94a3b8', fontSize: 11 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '已解决数',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol,
        lineStyle: { width: 2.4 },
        areaStyle: { opacity: 0.1 },
        data: points.map((point) => point.resolved),
      },
      {
        name: '平均首响（分钟）',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol,
        connectNulls: false,
        lineStyle: { width: 2 },
        data: points.map((point) => point.avgFirstResponseMinutes),
      },
    ],
  } as EChartsCoreOption
})
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>人员绩效分析</h1>
        <p class="head-sub">
          当前负载为实时快照；累计处理、已解决、首响与 SLA 达标率按所选日期区间统计 · 无样本的比率显示 -
        </p>
      </div>
    </section>

    <section class="toolbar">
      <div class="range-wrap">
        <DateRangeFilter :model-value="rangeModel" @change="onRangeChange" />
      </div>
      <div class="extra-filters">
        <label class="f-field">
          <span>优先级</span>
          <select v-model="priority">
            <option value="">全部优先级</option>
            <option v-for="item in PRIORITY_OPTIONS" :key="item" :value="item">
              {{ PRIORITY_LABEL[item] }}
            </option>
          </select>
        </label>
        <label class="f-field">
          <span>工单类型</span>
          <select v-model="ticketType">
            <option value="">全部类型</option>
            <option v-for="item in TYPE_OPTIONS" :key="item" :value="item">
              {{ TYPE_LABEL[item] }}
            </option>
          </select>
        </label>
      </div>
    </section>

    <p v-if="errorMessage" class="alert">
      <AppIcon name="alert" :size="15" />{{ errorMessage }}
    </p>

    <section class="stats">
      <StatCard
        v-for="item in stats"
        :key="item.key"
        :label="item.label"
        :value="item.value"
        :icon="item.icon"
        :tone="item.tone"
        :desc="item.desc"
        :loading="loading && performance === null"
      />
    </section>

    <section class="grid grid-main">
      <ChartCard
        title="综合分排行榜 TOP 10"
        subtitle="按综合分降序，浅色为样本不足"
        :hint="rankingHint"
        :loading="loading && performance === null"
      >
        <BaseChart v-if="rankingItems.length > 0" :option="rankingOption" :height="300" />
        <EmptyState
          v-else
          icon="award"
          title="暂无排名数据"
          desc="该区间与筛选条件下没有可统计的人员绩效"
        />
      </ChartCard>

      <ChartCard
        title="当前负载分布"
        subtitle="占用 / 最大并发 · 实时"
        :hint="loadHint"
        :loading="loading && performance === null"
      >
        <BaseChart v-if="agents.length > 0" :option="loadOption" :height="300" />
        <EmptyState v-else compact icon="gauge" title="暂无负载数据" />
      </ChartCard>
    </section>

    <ChartCard
      title="人员绩效明细"
      subtitle="默认按综合分降序"
      hint="点击表头切换排序；低样本人员（rank 为空）在排名列显示「样本不足」。"
      body-class="flush"
      :loading="loading && performance === null"
    >
      <DataTable
        :columns="columns"
        :rows="sortedAgents"
        row-key="agentId"
        :loading="loading && performance === null"
        :sort-key="sortKey"
        :sort-dir="sortDir"
        empty-text="没有符合条件的人员绩效数据"
        @sort="onSort"
      >
        <template #cell-rank="{ row }">
          <span v-if="row.rank !== null" class="rank num">{{ row.rank }}</span>
          <span v-else class="tag-low">样本不足</span>
        </template>
        <template #cell-agentName="{ row }">
          <div class="who">
            <strong>{{ row.agentName }}</strong>
            <span class="uname">{{ row.username }}</span>
          </div>
        </template>
        <template #cell-active="{ row }">
          <span class="acct" :class="{ off: !row.active }">{{ row.active ? '启用' : '已禁用' }}</span>
        </template>
        <template #cell-assignedTotal="{ row }">
          <span class="num">{{ row.assignedTotal }}</span>
        </template>
        <template #cell-resolvedTotal="{ row }">
          <span class="num">{{ row.resolvedTotal }}</span>
        </template>
        <template #cell-currentOccupied="{ row }">
          <span class="num">{{ row.currentOccupied }}</span>
        </template>
        <template #cell-completionRate="{ row }">
          <span class="num">{{ fmtPercent(row.completionRate) }}</span>
        </template>
        <template #cell-avgFirstResponseMinutes="{ row }">
          <span class="num muted">{{ fmtMinutes(row.avgFirstResponseMinutes) }}</span>
        </template>
        <template #cell-avgResolutionMinutes="{ row }">
          <span class="num muted">{{ fmtMinutes(row.avgResolutionMinutes) }}</span>
        </template>
        <template #cell-slaRate="{ row }">
          <span class="num">{{ fmtPercent(row.slaRate) }}</span>
        </template>
        <template #cell-loadPercent="{ row }">
          <span class="num" :class="loadToneClass(row.loadPercent)">
            {{ row.loadPercent === null ? '未配置' : `${row.loadPercent.toFixed(1)}%` }}
          </span>
        </template>
        <template #cell-overdueTotal="{ row }">
          <span class="num" :class="{ danger: row.overdueTotal > 0 }">{{ row.overdueTotal }}</span>
        </template>
        <template #cell-score="{ row }">
          <b class="num score">{{ row.score.toFixed(1) }}</b>
        </template>
      </DataTable>
    </ChartCard>

    <section class="card explain">
      <header class="explain-head">
        <h2>综合分说明</h2>
        <p class="head-sub">
          四项分项均为 0-100 分；样本不足人员照常计算分项，但不参与有效排名
        </p>
      </header>

      <p class="formula">
        综合分 = 0.5 × SLA 响应达标分 + 0.2 × 完成率分 + 0.2 × 优先级加权完成量分 + 0.1 × 负载健康分
      </p>

      <div class="score-cols">
        <div class="score-col">
          <span class="weight">50%</span>
          <b>SLA 响应达标分 slaScore</b>
          <span>区间内首响达标工单占比</span>
        </div>
        <div class="score-col">
          <span class="weight">20%</span>
          <b>完成率分 completionScore</b>
          <span>已解决 / 累计分配</span>
        </div>
        <div class="score-col">
          <span class="weight">20%</span>
          <b>加权完成量分 volumeScore</b>
          <span>优先级加权完成量 / 团队最高值 × 100</span>
        </div>
        <div class="score-col">
          <span class="weight">10%</span>
          <b>负载健康分 loadScore</b>
          <span>100 − 当前负载率，未配置并发计 0</span>
        </div>
      </div>

      <div class="explain-table">
        <DataTable
          :columns="scoreColumns"
          :rows="sortedAgents"
          row-key="agentId"
          dense
          :loading="loading && performance === null"
          empty-text="暂无综合分分项数据"
        >
          <template #cell-agentName="{ row }">
            <span class="explain-name">{{ row.agentName }}</span>
          </template>
          <template #cell-slaScore="{ row }">
            <span class="num">{{ row.slaScore.toFixed(1) }}</span>
          </template>
          <template #cell-completionScore="{ row }">
            <span class="num">{{ row.completionScore.toFixed(1) }}</span>
          </template>
          <template #cell-volumeScore="{ row }">
            <span class="num">{{ row.volumeScore.toFixed(1) }}</span>
          </template>
          <template #cell-loadScore="{ row }">
            <span class="num">{{ row.loadScore.toFixed(1) }}</span>
          </template>
          <template #cell-score="{ row }">
            <b class="num score">{{ row.score.toFixed(1) }}</b>
          </template>
        </DataTable>
      </div>

      <ul v-if="performance && performance.notes.length" class="notes">
        <li v-for="(note, index) in performance.notes" :key="index">{{ note }}</li>
      </ul>
    </section>

    <ChartCard
      title="绩效趋势"
      :subtitle="agentFilter === 0 ? '全团队按日聚合' : '所选人员按日聚合'"
      :hint="trendHint"
      :loading="trendLoading"
    >
      <template #actions>
        <select v-model.number="agentFilter" class="mini-select" aria-label="选择人员">
          <option :value="0">全团队</option>
          <option v-for="item in agentOptions" :key="item.agentId" :value="item.agentId">
            {{ item.agentName }}
          </option>
        </select>
      </template>
      <p v-if="trendError" class="alert">
        <AppIcon name="alert" :size="15" />{{ trendError }}
      </p>
      <BaseChart v-else-if="hasTrendData" :option="trendOption" :height="288" />
      <EmptyState
        v-else
        compact
        icon="chart"
        title="暂无趋势数据"
        desc="所选区间与人员没有已解决或首响记录"
      />
    </ChartCard>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.page-head h1 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink-1);
}
.head-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-4);
  line-height: 1.6;
}

/* 筛选栏 */
.toolbar {
  display: flex;
  align-items: stretch;
  gap: 12px;
  flex-wrap: wrap;
}
.range-wrap {
  flex: 1 1 640px;
  min-width: 0;
  display: flex;
}
.range-wrap > .range-filter {
  width: 100%;
}
.extra-filters {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.f-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 132px;
}
.f-field > span {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-3);
  white-space: nowrap;
}
.f-field select {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctrl);
  background: #fafbfd;
  font-size: 12px;
  color: var(--ink-1);
}
.f-field select:focus {
  outline: none;
  background: #fff;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
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

/* 指标卡 */
.stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}

/* 卡片与表格单元 */
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  min-width: 0;
  overflow: hidden;
}
.who {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.who strong {
  font-size: 13px;
  color: var(--ink-1);
  font-weight: 600;
}
.uname {
  font-size: 11px;
  color: var(--ink-4);
}
.rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 22px;
  padding: 0 7px;
  border-radius: 6px;
  background: var(--brand-weak);
  color: #4338ca;
  font-size: 12px;
  font-weight: 600;
}
.tag-low {
  display: inline-flex;
  align-items: center;
  height: 21px;
  padding: 0 7px;
  border-radius: 5px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
  white-space: nowrap;
}
.acct {
  font-size: 11px;
  color: #047857;
}
.acct.off {
  color: #b91c1c;
  font-weight: 700;
}
.muted {
  color: var(--ink-4);
}
.danger {
  color: var(--rose);
  font-weight: 600;
}
.score {
  color: var(--ink-1);
}
.load-ok {
  color: #047857;
}
.load-mid {
  color: #0e7490;
}
.load-high {
  color: #b45309;
}
.load-over {
  color: #b91c1c;
  font-weight: 600;
}
.load-none {
  color: var(--ink-4);
}
.mini-select {
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctrl);
  background: var(--panel);
  color: var(--ink-2);
  font-size: 12px;
  max-width: 160px;
}
.mini-select:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}

/* 综合分说明 */
.explain {
  padding: 16px 18px 18px;
}
.explain-head h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
}
.formula {
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fafbfd;
  border: 1px dashed var(--line);
  font-size: 12px;
  color: var(--ink-2);
  line-height: 1.7;
}
.score-cols {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}
.score-col {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 12px;
  border-radius: 10px;
  border: 1px solid var(--line-soft);
  background: #fcfdfe;
  min-width: 0;
}
.score-col b {
  font-size: 12px;
  color: var(--ink-1);
  font-weight: 600;
}
.score-col > span:last-child {
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.5;
}
.weight {
  align-self: flex-start;
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--brand-weak);
  color: #4338ca;
  font-size: 11px;
  font-weight: 700;
}
.explain-table {
  margin-top: 12px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  overflow: hidden;
}
.explain-name {
  font-size: 12px;
  color: var(--ink-2);
}
.notes {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.notes li {
  position: relative;
  padding-left: 12px;
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.6;
}
.notes li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 8px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #cbd5e1;
}

@media (max-width: 1440px) {
  .stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .score-cols {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .score-cols {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
