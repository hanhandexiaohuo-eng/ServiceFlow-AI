<script setup lang="ts">
/**
 * 工单运营分析：总量、每日趋势、状态/类型/优先级分布。
 * 数值全部来自后端聚合接口，空区间直接展示空状态，不在前端补造数据。
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
  fetchAnalyticsOverview,
  fetchTicketDistribution,
  fetchTicketTrend,
  type AnalyticsOverview,
  type AnalyticsQueryParams,
  type TicketDistributionData,
  type TicketTrendData,
} from '../../api/analytics'
import {
  PRIORITY_COLOR,
  PRIORITY_LABEL,
  STATUS_COLOR,
  STATUS_LABEL,
  TYPE_COLOR,
  TYPE_LABEL,
} from '../../constants/ticket'
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

function emptyOverview(): AnalyticsOverview {
  return { total: 0, resolved: 0, closed: 0, cancelled: 0, processing: 0 }
}

const overview = ref<AnalyticsOverview>(emptyOverview())
const trend = ref<TicketTrendData>({ points: [] })
const distribution = ref<TicketDistributionData>({ byType: [], byStatus: [], byPriority: [] })

const overviewLoading = ref(true)
const trendLoading = ref(true)
const distributionLoading = ref(true)
const errorMessage = ref('')

const loading = computed(
  () => overviewLoading.value || trendLoading.value || distributionLoading.value
)

function queryParams(): AnalyticsQueryParams {
  return {
    startDate: rangeParams.value.startDate,
    endDate: rangeParams.value.endDate,
    ticketType: ticketType.value || undefined,
    priority: priority.value || undefined,
  }
}

// 筛选变化可能连续触发，只接受最后一次请求的结果
let requestSeq = 0

async function load(): Promise<void> {
  if (isInvalid.value) return
  const seq = ++requestSeq
  errorMessage.value = ''
  overviewLoading.value = true
  trendLoading.value = true
  distributionLoading.value = true
  const params = queryParams()
  try {
    const [overviewData, trendData, distributionData] = await Promise.all([
      fetchAnalyticsOverview(params),
      fetchTicketTrend(params),
      fetchTicketDistribution(params),
    ])
    if (seq !== requestSeq) return
    overview.value = overviewData
    trend.value = trendData
    distribution.value = distributionData
  } catch (e) {
    if (seq !== requestSeq) return
    overview.value = emptyOverview()
    trend.value = { points: [] }
    distribution.value = { byType: [], byStatus: [], byPriority: [] }
    errorMessage.value = e instanceof Error ? e.message : '工单运营数据加载失败'
  } finally {
    if (seq === requestSeq) {
      overviewLoading.value = false
      trendLoading.value = false
      distributionLoading.value = false
    }
  }
}

/* ---------------- 空数据判断 ---------------- */

const trendHasData = computed(() =>
  trend.value.points.some((p) => p.created > 0 || p.resolved > 0 || p.closed > 0)
)

const statusTotal = computed(() =>
  distribution.value.byStatus.reduce((sum, item) => sum + item.count, 0)
)

const typeItems = computed(() => distribution.value.byType.filter((item) => item.count > 0))

const priorityHasData = computed(() =>
  distribution.value.byPriority.some((item) => item.total > 0)
)

const hasData = computed(
  () =>
    overview.value.total > 0 ||
    trendHasData.value ||
    statusTotal.value > 0 ||
    typeItems.value.length > 0 ||
    priorityHasData.value
)

const showEmpty = computed(
  () => !loading.value && !errorMessage.value && !hasData.value
)

/* ---------------- 图表 option ---------------- */

const TOOLTIP_BASE = {
  backgroundColor: 'rgba(15,23,42,0.92)',
  borderWidth: 0,
  padding: [8, 12],
  textStyle: { color: '#e2e8f0', fontSize: 12 },
}

const trendOption = computed<EChartsCoreOption>(() => {
  const points = trend.value.points
  return {
    color: ['#4f46e5', '#10b981', '#06b6d4'],
    tooltip: {
      trigger: 'axis',
      ...TOOLTIP_BASE,
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
      data: ['新增', '已解决', '已关闭'],
    },
    grid: { left: 6, right: 14, top: 34, bottom: 4, containLabel: true },
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
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    series: [
      {
        name: '新增',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 4,
        showSymbol: points.length <= 31,
        lineStyle: { width: 2.4 },
        areaStyle: { opacity: 0.08 },
        data: points.map((p) => p.created),
      },
      {
        name: '已解决',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 4,
        showSymbol: points.length <= 31,
        lineStyle: { width: 2.4 },
        data: points.map((p) => p.resolved),
      },
      {
        name: '已关闭',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 4,
        showSymbol: points.length <= 31,
        lineStyle: { width: 2 },
        data: points.map((p) => p.closed),
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
    itemGap: 9,
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
      data: distribution.value.byStatus
        .filter((item) => item.count > 0)
        .map((item) => ({
          name: STATUS_LABEL[item.status] ?? item.status,
          value: item.count,
          itemStyle: { color: STATUS_COLOR[item.status] ?? '#4f46e5' },
        })),
    },
  ],
}))

const typeOption = computed<EChartsCoreOption>(() => {
  const items = typeItems.value
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      ...TOOLTIP_BASE,
    },
    grid: { left: 6, right: 48, top: 10, bottom: 4, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    yAxis: {
      type: 'category',
      data: items.map((item) => TYPE_LABEL[item.type] ?? item.type),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    series: [
      {
        name: '工单数',
        type: 'bar',
        barWidth: 16,
        itemStyle: { borderRadius: [0, 7, 7, 0] },
        label: { show: true, position: 'right', color: '#334155', fontSize: 12, fontWeight: 600 },
        data: items.map((item) => ({
          value: item.count,
          itemStyle: { color: TYPE_COLOR[item.type] ?? '#4f46e5' },
        })),
      },
    ],
  } as EChartsCoreOption
})

const priorityOption = computed<EChartsCoreOption>(() => {
  const items = distribution.value.byPriority
  return {
    color: ['#4f46e5', '#10b981', '#f59e0b'],
    tooltip: {
      trigger: 'axis',
      ...TOOLTIP_BASE,
      axisPointer: { type: 'shadow' },
    },
    legend: {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 9,
      itemHeight: 9,
      itemGap: 12,
      textStyle: { color: '#64748b', fontSize: 11 },
      data: ['总数', '已解决', '平均处理时长（分钟）'],
    },
    grid: { left: 6, right: 10, top: 40, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: items.map((item) => PRIORITY_LABEL[item.priority] ?? item.priority),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    yAxis: [
      {
        type: 'value',
        name: '工单数',
        minInterval: 1,
        nameTextStyle: { color: '#94a3b8', fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11 },
        splitLine: { lineStyle: { color: '#f1f4f9' } },
      },
      {
        type: 'value',
        name: '分钟',
        nameTextStyle: { color: '#94a3b8', fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '总数',
        type: 'bar',
        barWidth: 20,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: items.map((item) => item.total),
      },
      {
        name: '已解决',
        type: 'bar',
        barWidth: 20,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: items.map((item) => item.resolved),
      },
      {
        name: '平均处理时长（分钟）',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        connectNulls: false,
        lineStyle: { width: 2.2 },
        itemStyle: { color: '#f59e0b' },
        data: items.map((item) => item.avgHandleMinutes),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 优先级明细表 ---------------- */

type PrioritySortKey = 'total' | 'resolved' | 'overdue' | 'avgHandleMinutes'

const priorityColumns: TableColumn[] = [
  { key: 'priority', label: '优先级', width: '112px', nowrap: true },
  { key: 'total', label: '总数', width: '104px', align: 'right', sortable: true },
  { key: 'resolved', label: '已解决', width: '104px', align: 'right', sortable: true },
  { key: 'overdue', label: '超时', width: '104px', align: 'right', sortable: true },
  { key: 'avgHandleMinutes', label: '平均处理时长', align: 'right', sortable: true },
]

const SORTABLE_KEYS: PrioritySortKey[] = ['total', 'resolved', 'overdue', 'avgHandleMinutes']
const sortKey = ref<PrioritySortKey>('total')
const sortDir = ref<'asc' | 'desc'>('desc')

function onSort(key: string): void {
  if (!SORTABLE_KEYS.includes(key as PrioritySortKey)) return
  const next = key as PrioritySortKey
  if (sortKey.value === next) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = next
    sortDir.value = 'desc'
  }
}

const priorityRows = computed(() => {
  const rows = distribution.value.byPriority.map((item) => ({
    priority: item.priority,
    total: item.total,
    resolved: item.resolved,
    overdue: item.overdue,
    avgHandleMinutes: item.avgHandleMinutes,
  }))
  const key = sortKey.value
  const dir = sortDir.value === 'asc' ? 1 : -1
  rows.sort((a, b) => {
    const left = a[key]
    const right = b[key]
    // 空值统一排在末尾，避免平均时长缺失时排序跳动
    if (left === null && right === null) return 0
    if (left === null) return 1
    if (right === null) return -1
    return (left - right) * dir
  })
  return rows
})

function formatMinutes(value: number | null): string {
  return value === null ? '-' : `${value.toFixed(1)} 分钟`
}

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>工单运营分析</h1>
        <p class="head-sub">
          数据来源 /api/analytics/overview、tickets/trend、tickets/distribution ·
          按创建时间落在所选区间的工单统计，处理中为当前实时值
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
        label="区间工单总数"
        :value="overview.total"
        icon="tickets"
        tone="brand"
        desc="按创建时间落在区间内"
        :loading="overviewLoading"
      />
      <StatCard
        label="已解决"
        :value="overview.resolved"
        icon="check"
        tone="emerald"
        desc="按 resolved_at 统计"
        :loading="overviewLoading"
      />
      <StatCard
        label="已关闭"
        :value="overview.closed"
        icon="check"
        tone="violet"
        desc="按 closed_at 统计"
        :loading="overviewLoading"
      />
      <StatCard
        label="已取消"
        :value="overview.cancelled"
        icon="close"
        tone="rose"
        desc="按 cancelled_at 统计"
        :loading="overviewLoading"
      />
      <StatCard
        label="当前处理中"
        :value="overview.processing"
        icon="activity"
        tone="cyan"
        desc="实时 · 不随区间结束时间变化"
        :loading="overviewLoading"
      />
    </section>

    <section v-if="showEmpty" class="empty-card">
      <EmptyState
        icon="inbox"
        title="该区间内暂无工单数据"
        desc="调整日期区间，或清除工单类型 / 优先级筛选后再查看"
      />
    </section>

    <template v-else-if="!errorMessage">
      <section class="grid grid-main">
        <ChartCard
          title="每日新增 / 解决 / 关闭"
          subtitle="按天聚合 · 纵轴为工单数"
          hint="新增按创建时间、已解决按 resolved_at、已关闭按 closed_at 落在当天的工单计数；无数据日期由后端补 0。"
          :loading="trendLoading"
        >
          <BaseChart v-if="trendHasData" :option="trendOption" :height="300" />
          <EmptyState v-else compact icon="chart" title="暂无每日趋势数据" />
        </ChartCard>

        <ChartCard
          title="当前状态分布"
          subtitle="区间内创建的工单 · 当前状态"
          hint="按工单当前状态归类，不是历史时点快照；中心数字为区间内创建工单总数。"
          :loading="distributionLoading"
        >
          <div v-if="statusTotal > 0" class="donut-wrap">
            <BaseChart :option="statusDonutOption" :height="212" />
            <div class="donut-center">
              <span class="donut-value num">{{ statusTotal }}</span>
              <span class="donut-label">工单总数</span>
            </div>
          </div>
          <EmptyState v-else compact icon="tickets" title="暂无状态分布数据" />
        </ChartCard>
      </section>

      <section class="grid grid-2">
        <ChartCard
          title="工单类型分布"
          subtitle="项目无独立分类字段，按工单类型统计"
          hint="类型取工单 ticketType 字段；百分比为各类型占区间内工单总数的比例。"
          :loading="distributionLoading"
        >
          <BaseChart v-if="typeItems.length > 0" :option="typeOption" :height="240" />
          <EmptyState v-else compact icon="tag" title="暂无类型分布数据" />
        </ChartCard>

        <ChartCard
          title="优先级对比"
          subtitle="柱：总数与已解决 · 折线：平均处理时长"
          hint="平均处理时长取已解决工单的处理耗时均值（分钟）；该优先级无已解决工单时折线断开。"
          :loading="distributionLoading"
        >
          <BaseChart v-if="priorityHasData" :option="priorityOption" :height="240" />
          <EmptyState v-else compact icon="alert" title="暂无优先级数据" />
        </ChartCard>
      </section>

      <ChartCard
        title="优先级明细"
        subtitle="区间内工单按优先级聚合 · 点击表头排序"
        hint="超时为当前 SLA 已超时的工单数；平均处理时长 = 已解决工单 (resolved_at - created_at) 的均值。"
        body-class="flush"
        :loading="distributionLoading"
      >
        <DataTable
          :columns="priorityColumns"
          :rows="priorityRows"
          row-key="priority"
          dense
          :loading="distributionLoading && priorityRows.length === 0"
          empty-text="该区间内暂无优先级明细"
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
          <template #cell-total="{ row }">
            <span class="num">{{ row.total }}</span>
          </template>
          <template #cell-resolved="{ row }">
            <span class="num">{{ row.resolved }}</span>
          </template>
          <template #cell-overdue="{ row }">
            <span class="num" :class="{ danger: row.overdue > 0 }">{{ row.overdue }}</span>
          </template>
          <template #cell-avgHandleMinutes="{ row }">
            <span class="num muted-value">{{ formatMinutes(row.avgHandleMinutes) }}</span>
          </template>
        </DataTable>
      </ChartCard>
    </template>
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
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
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
.danger {
  color: #b91c1c;
  font-weight: 600;
}
.muted-value {
  color: var(--ink-3);
}

@media (max-width: 1600px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
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
