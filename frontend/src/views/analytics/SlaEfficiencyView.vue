<script setup lang="ts">
/**
 * SLA 效率分析：响应/解决达标率、预警与超时、时长分布、优先级对比。
 * 口径以后端 SlaAnalyticsService 为准，页面只做格式化与可视化，不推算时限。
 */
import { computed, onMounted, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import AppIcon from '../../components/AppIcon.vue'
import BaseChart from '../../components/BaseChart.vue'
import ChartCard from '../../components/ChartCard.vue'
import DataTable from '../../components/DataTable.vue'
import DateRangeFilter from '../../components/DateRangeFilter.vue'
import EmptyState from '../../components/EmptyState.vue'
import StatCard from '../../components/StatCard.vue'
import {
  fetchSlaOverview,
  fetchSlaTrend,
  type AnalyticsDurationBucket,
  type AnalyticsPriority,
  type AnalyticsQueryParams,
  type AnalyticsSlaState,
  type SlaBucketCount,
  type SlaOverview,
  type SlaPriorityComparisonItem,
  type SlaTrendData,
} from '../../api/analytics'
import { PRIORITY_COLOR, PRIORITY_LABEL } from '../../constants/ticket'
import { useAnalyticsRange, type DateRange } from '../../composables/useAnalyticsRange'
import type { TableColumn } from '../../types/ui'

/* ---------------- 筛选条件 ---------------- */

const { rangeParams, setCustom, isInvalid } = useAnalyticsRange(30)
const rangeModel = computed<DateRange>({
  get: () => rangeParams.value,
  set: (value) => setCustom(value),
})

const TYPE_FILTERS = [
  { value: 'INCIDENT', label: '故障申报' },
  { value: 'SERVICE_REQUEST', label: '服务请求' },
  { value: 'CHANGE', label: '变更申请' },
  { value: 'CONSULT', label: '业务咨询' },
]
const PRIORITY_FILTERS = [
  { value: 'URGENT', label: '紧急' },
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
]

const ticketType = ref('')
const priority = ref('')

/* ---------------- 数据与加载 ---------------- */

const overview = ref<SlaOverview | null>(null)
const slaTrend = ref<SlaTrendData | null>(null)
const overviewLoading = ref(true)
const trendLoading = ref(true)
const errorMessage = ref('')

const loading = computed(() => overviewLoading.value || trendLoading.value)

function queryParams(): AnalyticsQueryParams {
  return {
    startDate: rangeParams.value.startDate,
    endDate: rangeParams.value.endDate,
    ticketType: ticketType.value || undefined,
    priority: priority.value || undefined,
  }
}

let requestSeq = 0

async function load(): Promise<void> {
  if (isInvalid.value) return
  const seq = ++requestSeq
  errorMessage.value = ''
  overviewLoading.value = true
  trendLoading.value = true
  const params = queryParams()
  try {
    const [overviewData, trendData] = await Promise.all([
      fetchSlaOverview(params),
      fetchSlaTrend(params),
    ])
    if (seq !== requestSeq) return
    overview.value = overviewData
    slaTrend.value = trendData
  } catch (e) {
    if (seq !== requestSeq) return
    overview.value = null
    slaTrend.value = null
    errorMessage.value = e instanceof Error ? e.message : 'SLA 效率数据加载失败'
  } finally {
    if (seq === requestSeq) {
      overviewLoading.value = false
      trendLoading.value = false
    }
  }
}

/* ---------------- 展示映射与格式化 ---------------- */

const SLA_STATE_LABEL: Record<AnalyticsSlaState, string> = {
  ON_TRACK: '正常',
  WARNING: '预警',
  OVERDUE: '超时',
  COMPLETED: '已完成',
}
const SLA_STATE_COLOR: Record<AnalyticsSlaState, string> = {
  ON_TRACK: '#10b981',
  WARNING: '#f59e0b',
  OVERDUE: '#ef4444',
  COMPLETED: '#94a3b8',
}
const BUCKET_LABEL: Record<AnalyticsDurationBucket, string> = {
  '<60': '1 小时内',
  '60-240': '1-4 小时',
  '240-480': '4-8 小时',
  '480-1440': '8-24 小时',
  '1440-4320': '1-3 天',
  '>4320': '3 天以上',
}

function formatRate(rate: number | null): string {
  return rate === null ? '-' : `${rate.toFixed(1)}%`
}

function formatMinutes(value: number | null): string {
  return value === null ? '-' : value.toFixed(1)
}

/* ---------------- 空数据判断 ---------------- */

const trendPoints = computed(() => slaTrend.value?.points ?? [])
const statusItems = computed(() => overview.value?.statusDistribution ?? [])
const firstResponseBuckets = computed(() => overview.value?.firstResponseBuckets ?? [])
const resolutionBuckets = computed(() => overview.value?.resolutionBuckets ?? [])
const priorityComparison = computed(() => overview.value?.priorityComparison ?? [])

const statusTotal = computed(() =>
  statusItems.value.reduce((sum, item) => sum + item.count, 0)
)
const bucketTotal = computed(
  () =>
    firstResponseBuckets.value.reduce((sum, item) => sum + item.count, 0) +
    resolutionBuckets.value.reduce((sum, item) => sum + item.count, 0)
)
const trendHasData = computed(() => trendPoints.value.some((p) => p.rate !== null))
const priorityHasData = computed(() =>
  priorityComparison.value.some((item) => item.denominator > 0)
)

const hasData = computed(() => {
  const data = overview.value
  if (!data) return false
  return (
    data.firstResponse.denominator > 0 ||
    data.resolution.denominator > 0 ||
    statusTotal.value > 0 ||
    bucketTotal.value > 0 ||
    data.warningCount > 0 ||
    data.overdueCount > 0 ||
    data.missingSla > 0
  )
})

const showEmpty = computed(() => !loading.value && !errorMessage.value && !hasData.value)

/* ---------------- 图表 option ---------------- */

const TOOLTIP_BASE = {
  backgroundColor: 'rgba(15,23,42,0.92)',
  borderWidth: 0,
  padding: [8, 12],
  textStyle: { color: '#e2e8f0', fontSize: 12 },
}

const trendOption = computed<EChartsCoreOption>(() => {
  const points = trendPoints.value
  return {
    tooltip: {
      trigger: 'axis',
      ...TOOLTIP_BASE,
      axisPointer: { type: 'line', lineStyle: { color: '#cbd5e1', type: 'dashed' } },
      formatter: (params: unknown) => {
        const list = (Array.isArray(params) ? params : [params]) as { dataIndex?: number }[]
        const point = points[list[0]?.dataIndex ?? 0]
        if (!point) return ''
        const rateText = point.rate === null ? '无样本' : `${point.rate.toFixed(1)}%`
        const sampleNote = point.sampleSufficient ? '' : '<br/>样本不足（分母 < 5）'
        return `${point.date}<br/>响应达标率：${rateText}<br/>达标 ${point.numerator} / 总样本 ${point.denominator}${sampleNote}`
      },
    },
    grid: { left: 6, right: 16, top: 16, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((p) => p.date.slice(5)),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11, interval: points.length > 31 ? 9 : 'auto' },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11, formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    series: [
      {
        name: '响应达标率',
        type: 'line',
        smooth: true,
        connectNulls: false,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: points.length <= 31,
        lineStyle: { width: 2.4, color: '#4f46e5' },
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        data: points.map((p) =>
          p.rate === null
            ? null
            : {
                value: p.rate,
                // 分母不足 5 的日期降低视觉权重，避免误读
                itemStyle: { color: p.sampleSufficient ? '#4f46e5' : '#c7d2fe' },
              }
        ),
      },
    ],
  } as EChartsCoreOption
})

const statusDonutOption = computed<EChartsCoreOption>(() => ({
  tooltip: {
    trigger: 'item',
    ...TOOLTIP_BASE,
    formatter: '{b}：{c} 单（{d}%）',
  },
  legend: {
    orient: 'vertical',
    right: 0,
    top: 'middle',
    icon: 'circle',
    itemWidth: 8,
    itemHeight: 8,
    itemGap: 10,
    textStyle: { color: '#64748b', fontSize: 11 },
  },
  series: [
    {
      type: 'pie',
      radius: ['56%', '78%'],
      center: ['32%', '50%'],
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      labelLine: { show: false },
      emphasis: { scaleSize: 4 },
      data: statusItems.value
        .filter((item) => item.count > 0)
        .map((item) => ({
          name: SLA_STATE_LABEL[item.state] ?? item.state,
          value: item.count,
          itemStyle: { color: SLA_STATE_COLOR[item.state] ?? '#4f46e5' },
        })),
    },
  ],
}))

function buildBucketOption(items: SlaBucketCount[], color: string): EChartsCoreOption {
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      ...TOOLTIP_BASE,
    },
    grid: { left: 6, right: 12, top: 14, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: items.map((item) => BUCKET_LABEL[item.bucket]),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 10, interval: 0 },
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
        name: '工单数',
        type: 'bar',
        barWidth: 26,
        itemStyle: { borderRadius: [6, 6, 0, 0], color },
        data: items.map((item) => item.count),
      },
    ],
  } as EChartsCoreOption
}

const firstResponseBucketOption = computed(() =>
  buildBucketOption(firstResponseBuckets.value, '#4f46e5')
)
const resolutionBucketOption = computed(() =>
  buildBucketOption(resolutionBuckets.value, '#06b6d4')
)

const priorityOption = computed<EChartsCoreOption>(() => {
  const rows = priorityComparison.value
  return {
    color: ['#4f46e5', '#ef4444'],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      ...TOOLTIP_BASE,
      formatter: (params: unknown) => {
        const list = (Array.isArray(params) ? params : [params]) as { dataIndex?: number }[]
        const row = rows[list[0]?.dataIndex ?? 0]
        if (!row) return ''
        const rateText = row.rate === null ? '无样本' : `${row.rate.toFixed(1)}%`
        return `${PRIORITY_LABEL[row.priority] ?? row.priority}<br/>响应达标率：${rateText}<br/>达标 ${row.numerator} / 总样本 ${row.denominator}<br/>当前超时：${row.overdue} 单`
      },
    },
    legend: {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 9,
      itemHeight: 9,
      itemGap: 14,
      textStyle: { color: '#64748b', fontSize: 11 },
      data: ['响应达标率', '当前超时数'],
    },
    grid: { left: 6, right: 10, top: 40, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((item) => PRIORITY_LABEL[item.priority] ?? item.priority),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    yAxis: [
      {
        type: 'value',
        name: '%',
        min: 0,
        max: 100,
        nameTextStyle: { color: '#94a3b8', fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11, formatter: '{value}%' },
        splitLine: { lineStyle: { color: '#f1f4f9' } },
      },
      {
        type: 'value',
        name: '单',
        minInterval: 1,
        nameTextStyle: { color: '#94a3b8', fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '响应达标率',
        type: 'bar',
        barWidth: 22,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: rows.map((item) => item.rate ?? 0),
      },
      {
        name: '当前超时数',
        type: 'bar',
        yAxisIndex: 1,
        barWidth: 22,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: rows.map((item) => item.overdue),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 优先级明细表 ---------------- */

type SlaSortKey = 'denominator' | 'numerator' | 'rate' | 'avgFirstResponseMinutes' | 'overdue'

const priorityColumns: TableColumn[] = [
  { key: 'priority', label: '优先级', width: '112px', nowrap: true },
  { key: 'denominator', label: '分母', width: '96px', align: 'right', sortable: true },
  { key: 'numerator', label: '分子', width: '96px', align: 'right', sortable: true },
  { key: 'rate', label: '达标率', width: '112px', align: 'right', sortable: true },
  { key: 'avgFirstResponseMinutes', label: '平均首响（分钟）', align: 'right', sortable: true },
  { key: 'overdue', label: '当前超时', width: '112px', align: 'right', sortable: true },
]

const SORTABLE_KEYS: SlaSortKey[] = [
  'denominator',
  'numerator',
  'rate',
  'avgFirstResponseMinutes',
  'overdue',
]
const sortKey = ref<SlaSortKey>('denominator')
const sortDir = ref<'asc' | 'desc'>('desc')

function onSort(key: string): void {
  if (!SORTABLE_KEYS.includes(key as SlaSortKey)) return
  const next = key as SlaSortKey
  if (sortKey.value === next) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = next
    sortDir.value = 'desc'
  }
}

interface SlaPriorityRow {
  priority: AnalyticsPriority
  denominator: number
  numerator: number
  rate: number | null
  avgFirstResponseMinutes: number | null
  overdue: number
}

const priorityRows = computed<SlaPriorityRow[]>(() => {
  const rows: SlaPriorityRow[] = priorityComparison.value.map((item: SlaPriorityComparisonItem) => ({
    priority: item.priority,
    denominator: item.denominator,
    numerator: item.numerator,
    rate: item.rate,
    avgFirstResponseMinutes: item.avgFirstResponseMinutes,
    overdue: item.overdue,
  }))
  const key = sortKey.value
  const dir = sortDir.value === 'asc' ? 1 : -1
  rows.sort((a, b) => {
    const left = a[key]
    const right = b[key]
    // 空值统一排在末尾，保证分子/分母为 0 或时长缺失时顺序稳定
    if (left === null && right === null) return 0
    if (left === null) return 1
    if (right === null) return -1
    return (left - right) * dir
  })
  return rows
})

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>SLA 效率分析</h1>
        <p class="head-sub">
          数据来源 /api/analytics/sla/overview、sla/trend ·
          响应达标按区间内工单的首次响应与 sla_due_at 截止时间比较，取消工单不进入任何分母
        </p>
      </div>
      <button class="btn" :disabled="loading" @click="load">
        <AppIcon name="refresh" :size="14" />刷新
      </button>
    </section>

    <section class="filter-bar">
      <DateRangeFilter v-model="rangeModel" label="统计范围" @change="load" />
      <div class="dims">
        <label class="dim">
          <span>工单类型</span>
          <select v-model="ticketType" :disabled="loading" @change="load">
            <option value="">全部类型</option>
            <option v-for="item in TYPE_FILTERS" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
        <label class="dim">
          <span>优先级</span>
          <select v-model="priority" :disabled="loading" @change="load">
            <option value="">全部优先级</option>
            <option v-for="item in PRIORITY_FILTERS" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
      </div>
    </section>

    <p v-if="errorMessage" class="alert">
      <AppIcon name="alert" :size="15" />{{ errorMessage }}
    </p>

    <section class="kpi-grid">
      <StatCard
        label="响应达标率"
        :value="formatRate(overview?.firstResponse.rate ?? null)"
        icon="gauge"
        tone="brand"
        :desc="`达标 ${overview?.firstResponse.numerator ?? 0} / 总样本 ${overview?.firstResponse.denominator ?? 0}`"
        :loading="overviewLoading"
      />
      <StatCard
        label="解决达标率"
        :value="formatRate(overview?.resolution.rate ?? null)"
        icon="check"
        tone="emerald"
        :desc="`代理口径 · 达标 ${overview?.resolution.numerator ?? 0} / 总样本 ${overview?.resolution.denominator ?? 0}`"
        :loading="overviewLoading"
      />
      <StatCard
        label="SLA 预警数"
        :value="overview?.warningCount ?? 0"
        icon="bell"
        tone="amber"
        desc="当前活动工单实时统计"
        :loading="overviewLoading"
      />
      <StatCard
        label="SLA 超时数"
        :value="overview?.overdueCount ?? 0"
        icon="alert"
        tone="rose"
        desc="当前活动工单实时统计"
        :loading="overviewLoading"
      />
      <StatCard
        label="平均首次响应（分钟）"
        :value="formatMinutes(overview?.avgFirstResponseMinutes ?? null)"
        icon="clock"
        tone="cyan"
        desc="区间内已响应工单均值"
        :loading="overviewLoading"
      />
      <StatCard
        label="平均解决时长（分钟）"
        :value="formatMinutes(overview?.avgResolutionMinutes ?? null)"
        icon="clock"
        tone="violet"
        desc="区间内已解决工单均值"
        :loading="overviewLoading"
      />
      <StatCard
        class="kpi-missing"
        label="SLA 记录缺失"
        :value="overview?.missingSla ?? 0"
        icon="database"
        tone="brand"
        desc="sla_due_at 为空，不计入任何分母"
        :loading="overviewLoading"
      />
    </section>

    <section v-if="showEmpty" class="empty-card">
      <EmptyState
        icon="inbox"
        title="该区间内暂无 SLA 统计样本"
        desc="调整日期区间，或清除工单类型 / 优先级筛选后再查看"
      />
    </section>

    <template v-else-if="!errorMessage">
      <section class="grid grid-main">
        <ChartCard
          title="每日响应达标率"
          subtitle="按首次响应统计 · 纵轴为达标率"
          hint="rate 为 null 的日期不绘制折线；分母小于 5 的日期以浅色圆点标记，样本不足时结论仅供参考。"
          :loading="trendLoading"
        >
          <BaseChart v-if="trendHasData" :option="trendOption" :height="288" />
          <EmptyState v-else compact icon="chart" title="暂无响应达标样本" />
        </ChartCard>

        <ChartCard
          title="SLA 状态分布"
          subtitle="当前活动工单的 SLA 状态"
          hint="ON_TRACK / WARNING / OVERDUE 为当前活动工单，COMPLETED 表示 SLA 计时已结束；中心数字为各状态合计。"
          :loading="overviewLoading"
        >
          <div v-if="statusTotal > 0" class="donut-wrap">
            <BaseChart :option="statusDonutOption" :height="212" />
            <div class="donut-center">
              <span class="donut-value num">{{ statusTotal }}</span>
              <span class="donut-label">工单总数</span>
            </div>
          </div>
          <EmptyState v-else compact icon="clock" title="暂无 SLA 状态数据" />
        </ChartCard>
      </section>

      <section class="grid grid-2">
        <ChartCard
          title="首次响应时长分布"
          subtitle="按耗时区间统计 · 横轴为时长桶"
          hint="桶基于首次响应耗时（分钟）划分，后端按固定 6 档返回，空桶补 0。"
          :loading="overviewLoading"
        >
          <BaseChart
            v-if="bucketTotal > 0"
            :option="firstResponseBucketOption"
            :height="236"
          />
          <EmptyState v-else compact icon="clock" title="暂无首次响应时长数据" />
        </ChartCard>

        <ChartCard
          title="解决时长分布"
          subtitle="按耗时区间统计 · 横轴为时长桶"
          hint="桶基于解决耗时（分钟）划分，与首次响应共用同一组分档区间。"
          :loading="overviewLoading"
        >
          <BaseChart
            v-if="bucketTotal > 0"
            :option="resolutionBucketOption"
            :height="236"
          />
          <EmptyState v-else compact icon="clock" title="暂无解决时长数据" />
        </ChartCard>
      </section>

      <ChartCard
        title="各优先级响应达标率"
        subtitle="柱：响应达标率 · 另附当前超时数"
        hint="左轴为响应达标率（%），右轴为当前超时数；tooltip 展示分子/分母；无分母样本的优先级柱高按 0 处理。"
        :loading="overviewLoading"
      >
        <BaseChart v-if="priorityHasData" :option="priorityOption" :height="264" />
        <EmptyState v-else compact icon="alert" title="暂无优先级达标数据" />
      </ChartCard>

      <ChartCard
        title="优先级对比明细"
        subtitle="分母/分子/达标率/平均首响/当前超时 · 点击表头排序"
        hint="分母为该优先级进入统计的工单数（取消工单不计入）；当前超时为实时值，不受日期区间限制。"
        body-class="flush"
        :loading="overviewLoading"
      >
        <DataTable
          :columns="priorityColumns"
          :rows="priorityRows"
          row-key="priority"
          dense
          :loading="overviewLoading && priorityRows.length === 0"
          empty-text="该区间内暂无优先级 SLA 数据"
          :sort-key="sortKey"
          :sort-dir="sortDir"
          @sort="onSort"
        >
          <template #cell-priority="{ row }">
            <span class="p-cell">
              <span class="p-dot" :style="{ background: PRIORITY_COLOR[row.priority] ?? '#94a3b8' }" />
              {{ PRIORITY_LABEL[row.priority] ?? row.priority }}
            </span>
          </template>
          <template #cell-denominator="{ row }">
            <span class="num">{{ row.denominator }}</span>
          </template>
          <template #cell-numerator="{ row }">
            <span class="num">{{ row.numerator }}</span>
          </template>
          <template #cell-rate="{ row }">
            <span class="num rate" :class="{ zero: row.rate === null }">
              {{ formatRate(row.rate) }}
            </span>
          </template>
          <template #cell-avgFirstResponseMinutes="{ row }">
            <span class="num muted-value">{{ formatMinutes(row.avgFirstResponseMinutes) }}</span>
          </template>
          <template #cell-overdue="{ row }">
            <span class="num" :class="{ danger: row.overdue > 0 }">{{ row.overdue }}</span>
          </template>
        </DataTable>
      </ChartCard>
    </template>

    <section v-if="overview && overview.notes.length > 0" class="notes">
      <header class="notes-head">
        <span class="notes-icon"><AppIcon name="bulb" :size="16" /></span>
        <h3>口径说明</h3>
      </header>
      <ul>
        <li v-for="note in overview.notes" :key="note">{{ note }}</li>
      </ul>
      <p class="notes-extra">
        项目 SLA 为响应时限，解决达标为同一截止时间下的代理指标；取消工单不进入分母。
      </p>
    </section>
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

/* 筛选区 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.dims {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dim {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.dim > span {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-3);
  white-space: nowrap;
}
.dim select {
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctrl);
  background: var(--panel);
  color: var(--ink-1);
  font-size: 12px;
}
.dim select:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.dim select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 指标卡 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
}
.kpi-missing {
  border-style: dashed;
  border-color: #c7d2fe;
}

.empty-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: 18px;
}

/* 环形图中心 */
.donut-wrap {
  position: relative;
}
.donut-center {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 64%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.donut-value {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink-1);
}
.donut-label {
  font-size: 11px;
  color: var(--ink-4);
}

/* 明细表 */
.p-cell {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--ink-2);
}
.p-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex: none;
}
.rate {
  font-weight: 600;
  color: var(--ink-1);
}
.rate.zero {
  color: var(--ink-4);
  font-weight: 500;
}
.danger {
  color: #b91c1c;
  font-weight: 600;
}
.muted-value {
  color: var(--ink-3);
}

/* 口径说明 */
.notes {
  padding: 14px 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-left: 3px solid var(--brand);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.notes-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notes-icon {
  color: var(--brand);
  display: flex;
}
.notes-head h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.notes ul {
  list-style: none;
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.notes li {
  position: relative;
  padding-left: 14px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}
.notes li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 8px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand);
  opacity: 0.55;
}
.notes-extra {
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-4);
}

@media (max-width: 1600px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1000px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .dims {
    width: 100%;
  }
  .dim {
    flex: 1;
  }
}
@media (max-width: 620px) {
  .kpi-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .dims {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
