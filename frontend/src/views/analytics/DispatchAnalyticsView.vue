<script setup lang="ts">
/**
 * 智能调度分析：数据全部来自后端 dispatch overview。
 * 采纳/拒绝按工单去重，待确认与无法判定不计入采纳率分母；负载变化明确不可用。
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
  fetchDispatchOverview,
  type AnalyticsQueryParams,
  type DispatchOverview,
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

const overview = ref<DispatchOverview | null>(null)
const loading = ref(false)
const errorMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    overview.value = await fetchDispatchOverview(params.value)
  } catch (e) {
    overview.value = null
    errorMessage.value = e instanceof Error ? e.message : '调度分析数据加载失败'
  } finally {
    loading.value = false
  }
}

function onRangeChange(value: DateRange): void {
  range.setCustom(value)
}

watch(
  params,
  () => {
    void load()
  },
  { immediate: true }
)

/* ---------------- 格式化 ---------------- */

function fmtPercent(value: number | null): string {
  return value === null ? '-' : `${value.toFixed(1)}%`
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

const stats = computed<StatItem[]>(() => {
  const data = overview.value
  return [
    {
      key: 'recommend',
      label: '推荐次数',
      value: data ? data.recommendationCount : '-',
      icon: 'sparkles',
      tone: 'brand',
      desc: 'RECOMMEND 日志行数',
    },
    {
      key: 'accepted',
      label: '已采纳',
      value: data ? data.acceptedCount : '-',
      icon: 'check',
      tone: 'emerald',
      desc: '推荐人与最终分配人一致',
    },
    {
      key: 'rejected',
      label: '已拒绝',
      value: data ? data.rejectedCount : '-',
      icon: 'close',
      tone: 'rose',
      desc: '已确认分配但换了其他人',
    },
    {
      key: 'pending',
      label: '待确认',
      value: data ? data.pendingCount : '-',
      icon: 'clock',
      tone: 'amber',
      desc: '推荐后尚无确认分配',
    },
    {
      key: 'unverifiable',
      label: '无法判定',
      value: data ? data.unverifiableCount : '-',
      icon: 'alert',
      tone: 'violet',
      desc: '推荐人未记录，不计入采纳率',
    },
    {
      key: 'adoption',
      label: '采纳率',
      value: fmtPercent(data?.adoptionRate ?? null),
      icon: 'trendUp',
      tone: 'cyan',
      desc: data ? `分母为已决策推荐 ${data.decisionCount} 次` : '分母为已决策推荐数',
    },
    {
      key: 'confirm',
      label: '平均确认间隔',
      value:
        data && data.avgConfirmSeconds !== null ? `${data.avgConfirmSeconds.toFixed(1)} 秒` : '-',
      icon: 'activity',
      tone: 'brand',
      desc: '推荐 → 确认分配间隔，非算法耗时',
    },
  ]
})

const notes = computed<string[]>(() => overview.value?.notes ?? [])

/* ---------------- 决策结果环形图 ---------------- */

interface DecisionSlice {
  name: string
  value: number
  color: string
}

const decisionSlices = computed<DecisionSlice[]>(() => {
  const data = overview.value
  if (!data) return []
  return [
    { name: '已采纳', value: data.acceptedCount, color: '#10b981' },
    { name: '已拒绝', value: data.rejectedCount, color: '#ef4444' },
    { name: '待确认', value: data.pendingCount, color: '#f59e0b' },
    { name: '无法判定', value: data.unverifiableCount, color: '#94a3b8' },
  ].filter((item) => item.value > 0)
})

const decisionTotal = computed(() =>
  decisionSlices.value.reduce((sum, item) => sum + item.value, 0)
)

function shareOf(value: number): string {
  if (decisionTotal.value === 0) return '0%'
  return `${Math.round((value / decisionTotal.value) * 100)}%`
}

const donutOption = computed<EChartsCoreOption>(() => {
  const items = decisionSlices.value
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: '{b}：{c} 单（{d}%）',
    },
    series: [
      {
        type: 'pie',
        radius: ['62%', '84%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scaleSize: 4 },
        data: items.map((item) => ({
          name: item.name,
          value: item.value,
          itemStyle: { color: item.color },
        })),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 采纳率趋势 ---------------- */

const trendPoints = computed(() =>
  (overview.value?.adoptionTrend ?? []).filter((point) => point.rate !== null)
)

const hasTrend = computed(() => trendPoints.value.length > 0)

const trendOption = computed<EChartsCoreOption>(() => {
  const points = trendPoints.value
  return {
    color: ['#4f46e5'],
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: '#cbd5e1', type: 'dashed' } },
      formatter: '{b}：{c}%',
    },
    grid: { left: 6, right: 12, top: 12, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((point) => point.date.slice(5)),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
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
        name: '采纳率',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: points.length <= 31,
        lineStyle: { width: 2.4 },
        areaStyle: { opacity: 0.1 },
        data: points.map((point) => point.rate),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 人员推荐次数 ---------------- */

const agentBars = computed(() =>
  [...(overview.value?.agents ?? [])].sort((a, b) => b.recommendCount - a.recommendCount).slice(0, 12)
)

const unknownRecommendCount = computed(() =>
  (overview.value?.agents ?? [])
    .filter((item) => item.agentId === null)
    .reduce((sum, item) => sum + item.recommendCount, 0)
)

const totalRecommendCount = computed(() =>
  (overview.value?.agents ?? []).reduce((sum, item) => sum + item.recommendCount, 0)
)

const unknownSharePercent = computed(() => {
  if (totalRecommendCount.value === 0) return 0
  return Math.round((unknownRecommendCount.value / totalRecommendCount.value) * 100)
})

const unknownDominant = computed(() => unknownSharePercent.value >= 50)

const agentHint = computed(() => {
  if (unknownRecommendCount.value === 0) return '推荐次数按推荐人聚合，按次数降序取前 12 人。'
  const base = `「未记录推荐人」占推荐总数 ${unknownSharePercent.value}%，以浅色柱单列。`
  return unknownDominant.value
    ? `${base}历史 RECOMMEND 记录未保存推荐人，人员分布参考意义有限。`
    : base
})

const agentOption = computed<EChartsCoreOption>(() => {
  const items = [...agentBars.value].reverse()
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: '{b}：{c} 次',
    },
    grid: { left: 6, right: 40, top: 8, bottom: 4, containLabel: true },
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
      data: items.map((item) => item.agentName),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        barWidth: 13,
        itemStyle: { borderRadius: [0, 7, 7, 0] },
        label: { show: true, position: 'right', color: '#334155', fontSize: 11, fontWeight: 600 },
        data: items.map((item) => ({
          value: item.recommendCount,
          itemStyle: { color: item.agentId === null ? '#cbd5e1' : '#4f46e5' },
        })),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 优先级调度效果 ---------------- */

const priorityHint =
  '仅统计已决策且可判定的推荐；左轴为次数，右轴为采纳率（无样本的优先级不绘制比率）。'

const priorityOption = computed<EChartsCoreOption>(() => {
  const items = overview.value?.priorityEffects ?? []
  return {
    color: ['#c7d2fe', '#4f46e5', '#f59e0b'],
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
      itemGap: 14,
      textStyle: { color: '#64748b', fontSize: 11 },
      data: ['已决策推荐', '已采纳', '采纳率'],
    },
    grid: { left: 6, right: 12, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: items.map((item) => PRIORITY_LABEL[item.priority] ?? item.priority),
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    yAxis: [
      {
        type: 'value',
        name: '次数',
        minInterval: 1,
        nameTextStyle: { color: '#94a3b8', fontSize: 11 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11 },
        splitLine: { lineStyle: { color: '#f1f4f9' } },
      },
      {
        type: 'value',
        name: '采纳率',
        min: 0,
        max: 100,
        nameTextStyle: { color: '#94a3b8', fontSize: 11 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 11, formatter: '{value}%' },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '已决策推荐',
        type: 'bar',
        barWidth: 14,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: items.map((item) => item.decisionCount),
      },
      {
        name: '已采纳',
        type: 'bar',
        barWidth: 14,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: items.map((item) => item.acceptedCount),
      },
      {
        name: '采纳率',
        type: 'bar',
        yAxisIndex: 1,
        barWidth: 14,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
        data: items.map((item) => item.adoptionRate),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 技能匹配 ---------------- */

interface SkillRow {
  state: string
  label: string
  count: number
  desc: string
}

const SKILL_STATE_META: Record<string, { label: string; desc: string }> = {
  MATCHED: { label: '完全匹配', desc: '推荐人员技能完整覆盖工单所需技能' },
  PARTIAL: { label: '部分匹配', desc: '仅覆盖部分所需技能' },
  UNMATCHED: { label: '不匹配', desc: '推荐人员不具备工单所需技能' },
  UNKNOWN: { label: '无法判定', desc: '工单未要求技能或缺少技能数据' },
}

const skillRows = computed<SkillRow[]>(() =>
  (overview.value?.skillMatch ?? []).map((item) => ({
    state: item.state,
    label: SKILL_STATE_META[item.state]?.label ?? item.state,
    count: item.count,
    desc: SKILL_STATE_META[item.state]?.desc ?? '',
  }))
)

const skillColumns: TableColumn[] = [
  { key: 'state', label: '状态', width: '132px', nowrap: true },
  { key: 'label', label: '中文含义', width: '112px', nowrap: true },
  { key: 'count', label: '工单数', width: '90px', align: 'right' },
  { key: 'desc', label: '说明' },
]

const skillHint = '基于当前技能配置判定，非调度时的技能快照。'
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>智能调度分析</h1>
        <p class="head-sub">
          采纳 / 拒绝按工单去重；待确认不计入采纳率分母 · 平均确认间隔为推荐 → 确认分配的时间差
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

    <section v-if="overview && overview.lowSample" class="warn">
      <p class="warn-title"><AppIcon name="alert" :size="15" />样本不足，结论仅供参考</p>
      <p class="warn-desc">
        区间内推荐次数少于 10 次，采纳率与趋势波动较大，建议扩大日期范围后再判断。
      </p>
    </section>

    <template v-if="!errorMessage">
      <section class="stats">
        <StatCard
          v-for="item in stats"
          :key="item.key"
          :label="item.label"
          :value="item.value"
          :icon="item.icon"
          :tone="item.tone"
          :desc="item.desc"
          :loading="loading && overview === null"
        />
      </section>

      <section class="grid grid-main">
        <ChartCard
          title="采纳率趋势"
          subtitle="按推荐日期聚合"
          hint="分母为已决策推荐数（采纳 + 拒绝）；rate 为 null 的日期不绘制。"
          :loading="loading && overview === null"
        >
          <BaseChart v-if="hasTrend" :option="trendOption" :height="268" />
          <EmptyState
            v-else
            compact
            icon="chart"
            title="暂无采纳率趋势"
            desc="所选区间内没有已决策的推荐记录"
          />
        </ChartCard>

        <ChartCard
          title="决策结果构成"
          subtitle="按工单去重"
          hint="待确认与无法判定不计入采纳率分母，四类之和可能大于已决策推荐数。"
          :loading="loading && overview === null"
        >
          <div v-if="decisionTotal > 0" class="donut-wrap">
            <BaseChart :option="donutOption" :height="188" />
            <div class="donut-center">
              <span class="donut-value num">{{ decisionTotal }}</span>
              <span class="donut-label">推荐结果</span>
            </div>
          </div>
          <ul v-if="decisionTotal > 0" class="dist-list">
            <li v-for="item in decisionSlices" :key="item.name">
              <span class="dot" :style="{ background: item.color }" />
              <span class="name">{{ item.name }}</span>
              <span class="count num">{{ item.value }}</span>
              <span class="pct num">{{ shareOf(item.value) }}</span>
            </li>
          </ul>
          <EmptyState v-else compact icon="route" title="暂无调度决策数据" />
        </ChartCard>
      </section>

      <section class="grid grid-2">
        <ChartCard
          title="人员推荐次数"
          subtitle="按推荐人聚合，取前 12 人"
          :hint="agentHint"
          :loading="loading && overview === null"
        >
          <BaseChart v-if="agentBars.length > 0" :option="agentOption" :height="264" />
          <EmptyState v-else compact icon="users" title="暂无人员推荐数据" />
        </ChartCard>

        <ChartCard
          title="优先级调度效果"
          subtitle="已决策推荐 / 已采纳 / 采纳率"
          :hint="priorityHint"
          :loading="loading && overview === null"
        >
          <BaseChart
            v-if="(overview?.priorityEffects.length ?? 0) > 0"
            :option="priorityOption"
            :height="264"
          />
          <EmptyState v-else compact icon="alert" title="暂无优先级调度数据" />
        </ChartCard>
      </section>

      <section class="grid grid-2">
        <ChartCard
          title="技能匹配分布"
          subtitle="推荐人员技能与工单需求比对"
          :hint="skillHint"
          body-class="flush"
          :loading="loading && overview === null"
        >
          <DataTable
            :columns="skillColumns"
            :rows="skillRows"
            row-key="state"
            dense
            :loading="loading && overview === null"
            empty-text="暂无技能匹配记录"
          >
            <template #cell-state="{ row }">
              <span class="mono">{{ row.state }}</span>
            </template>
            <template #cell-label="{ row }">
              <span class="skill-label">{{ row.label }}</span>
            </template>
            <template #cell-count="{ row }">
              <span class="num">{{ row.count }}</span>
            </template>
            <template #cell-desc="{ row }">
              <span class="skill-desc">{{ row.desc }}</span>
            </template>
          </DataTable>
        </ChartCard>

        <ChartCard title="调度前后负载变化" subtitle="需要调度前的负载快照">
          <EmptyState
            icon="gauge"
            title="暂不可用"
            :desc="overview ? overview.loadChangeNote : ''"
          />
        </ChartCard>
      </section>

      <ChartCard title="统计口径说明" subtitle="后端返回的 notes">
        <ul v-if="notes.length > 0" class="notes">
          <li v-for="(note, index) in notes" :key="index">{{ note }}</li>
        </ul>
        <EmptyState v-else compact icon="logs" title="暂无口径说明" />
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

.warn {
  padding: 11px 14px;
  border-radius: 10px;
  background: #fffbeb;
  border: 1px solid #fde68a;
}
.warn-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: #b45309;
}
.warn-desc {
  margin-top: 4px;
  padding-left: 22px;
  font-size: 12px;
  color: #92400e;
  line-height: 1.6;
}

/* 指标卡：7 张，四列两行 */
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

/* 环形图 */
.donut-wrap {
  position: relative;
}
.donut-center {
  position: absolute;
  inset: 0;
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
}
.donut-label {
  font-size: 11px;
  color: var(--ink-4);
}
.dist-list {
  list-style: none;
  margin-top: 6px;
}
.dist-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  font-size: 12px;
  color: var(--ink-2);
}
.dist-list .dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex: none;
}
.dist-list .name {
  flex: 1;
}
.dist-list .count {
  color: var(--ink-1);
  font-weight: 600;
}
.dist-list .pct {
  width: 38px;
  text-align: right;
  color: var(--ink-4);
}

/* 技能匹配表 */
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--ink-3);
}
.skill-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
}
.skill-desc {
  font-size: 12px;
  color: var(--ink-3);
}

/* 口径说明 */
.notes {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.notes li {
  position: relative;
  padding-left: 13px;
  font-size: 12px;
  color: var(--ink-3);
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
}
@media (max-width: 900px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
