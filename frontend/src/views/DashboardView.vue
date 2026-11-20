<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsCoreOption } from 'echarts/core'
import BaseChart from '../components/BaseChart.vue'
import ChartCard from '../components/ChartCard.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import StatCard from '../components/StatCard.vue'
import StatusTag from '../components/StatusTag.vue'
import AppIcon from '../components/AppIcon.vue'
import ServiceHealth from '../components/ServiceHealth.vue'
import { getCurrentUser } from '../api/request'
import { ROLE_LABEL, formatDateTime } from '../constants/ticket'
import {
  buildMyTodos,
  buildTrend,
  doneCount,
  inProgressCount,
  isLoading,
  loadError,
  overdueCount,
  priorityDistribution,
  recentTickets,
  refreshTickets,
  statusDistribution,
  todayNewCount,
  totalCount,
  typeDistribution,
  unassignedCount,
  warningCount,
} from '../composables/useTicketStore'
import type { TableColumn } from '../types/ui'

const router = useRouter()
const user = getCurrentUser()
const isAdmin = user?.role === 'ADMIN'
const roleLabel = ROLE_LABEL[user?.role ?? ''] ?? user?.role ?? '-'

/* ---------------- 欢迎区 ---------------- */
const now = ref(new Date())

const greeting = computed(() => {
  const h = now.value.getHours()
  if (h < 6) return '凌晨好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const dateText = computed(() => {
  const d = now.value
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 · ${week}`
})
const clockText = computed(() =>
  now.value.toLocaleTimeString('zh-CN', { hour12: false })
)

/* ---------------- 趋势 ---------------- */
const ranges = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
]
const range = ref(30)
const trend = computed(() => buildTrend(range.value))

const trendOption = computed<EChartsCoreOption>(() => {
  const labels = trend.value.labels
  return {
    color: ['#4f46e5', '#10b981', '#06b6d4'],
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
      data: ['新增工单', '已完成', '处理中'],
    },
    grid: { left: 6, right: 12, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: labels,
      axisLine: { lineStyle: { color: '#e7eaf0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11, interval: range.value > 30 ? 9 : 'auto' },
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
        name: '新增工单',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol: range.value <= 30,
        lineStyle: { width: 2.4 },
        areaStyle: { opacity: 0.1 },
        data: trend.value.created,
      },
      {
        name: '已完成',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol: range.value <= 30,
        lineStyle: { width: 2.4 },
        data: trend.value.resolved,
      },
      {
        name: '处理中',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol: range.value <= 30,
        lineStyle: { width: 2 },
        data: trend.value.running,
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 状态 / 优先级 / 类型 ---------------- */
const statusTotal = computed(() => statusDistribution.value.reduce((sum, i) => sum + i.value, 0))
const typeTotal = computed(() => typeDistribution.value.reduce((sum, i) => sum + i.value, 0))

function percent(value: number, total: number): string {
  if (!total) return '0%'
  return `${Math.round((value / total) * 100)}%`
}

const donutOption = computed<EChartsCoreOption>(() => {
  const items = statusDistribution.value
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: '{b}：{c} 单（{d}%）',
    },
    series: [
      {
        type: 'pie',
        radius: ['64%', '84%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scaleSize: 4 },
        data: items.map((i) => ({ name: i.label, value: i.value, itemStyle: { color: i.color } })),
      },
    ],
  } as EChartsCoreOption
})

const typeDonutOption = computed<EChartsCoreOption>(() => {
  const items = typeDistribution.value
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: '{b}：{c} 单（{d}%）',
    },
    legend: {
      orient: 'vertical',
      right: 6,
      top: 'middle',
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { color: '#64748b', fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['56%', '80%'],
        center: ['40%', '50%'],
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scaleSize: 4 },
        data: items.map((i) => ({ name: i.label, value: i.value, itemStyle: { color: i.color } })),
      },
    ],
  } as EChartsCoreOption
})

const priorityOption = computed<EChartsCoreOption>(() => {
  // 类目轴自下而上排列，直接使用「低 → 紧急」即可让紧急显示在顶部
  const items = priorityDistribution.value
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderWidth: 0,
      textStyle: { color: '#e2e8f0', fontSize: 12 },
    },
    grid: { left: 6, right: 40, top: 8, bottom: 4, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { lineStyle: { color: '#f1f4f9' } },
    },
    yAxis: {
      type: 'category',
      data: items.map((i) => i.label),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#475569', fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        itemStyle: { borderRadius: [0, 7, 7, 0] },
        label: { show: true, position: 'right', color: '#334155', fontSize: 12, fontWeight: 600 },
        data: items.map((i) => ({ value: i.value, itemStyle: { color: i.color } })),
      },
    ],
  } as EChartsCoreOption
})

/* ---------------- 最近工单 / 我的待办 ---------------- */
const columns: TableColumn[] = [
  { key: 'ticketNo', label: '工单编号', width: '148px', nowrap: true },
  { key: 'title', label: '标题' },
  { key: 'priority', label: '优先级', width: '78px' },
  { key: 'status', label: '状态', width: '92px' },
  { key: 'creator', label: '创建人', width: '92px', nowrap: true },
  { key: 'assignee', label: '处理人', width: '92px', nowrap: true },
  { key: 'createdAt', label: '创建时间', width: '132px', nowrap: true },
  { key: 'actions', label: '', width: '56px', align: 'right' },
]

const todos = computed(() => buildMyTodos(user?.name ?? '', isAdmin))

function openTicket(row: Record<string, any>) {
  router.push(`/tickets/${row.id}`)
}

/* ---------------- 服务健康（见 ServiceHealth 组件） ---------------- */

let clockTimer = 0

onMounted(() => {
  refreshTickets()
  // 顶部时间每秒刷新，保持「当前时间」真实
  clockTimer = window.setInterval(() => {
    now.value = new Date()
  }, 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="page dashboard">
    <!-- 一、欢迎区 -->
    <section class="welcome">
      <div class="welcome-main">
        <p class="date">{{ dateText }} · <span class="num clock">{{ clockText }}</span></p>
        <h1>{{ greeting }}，{{ user?.name ?? '访客' }}</h1>
        <p class="sub">
          当前角色 <strong>{{ roleLabel }}</strong>
          <span class="sep">·</span>
          今日新增 <strong>{{ todayNewCount }}</strong> 单
          <span class="sep">·</span>
          待分配 <strong>{{ unassignedCount }}</strong> 单
          <span class="sep">·</span>
          处理中 <strong>{{ inProgressCount }}</strong> 单
        </p>
      </div>
      <div class="welcome-actions">
        <button class="btn btn-primary" @click="router.push('/tickets?new=1')">
          <AppIcon name="plus" :size="15" />创建工单
        </button>
        <button class="btn" @click="router.push('/tickets')">
          <AppIcon name="tickets" :size="15" />查看工单
        </button>
      </div>
    </section>

    <p v-if="loadError" class="alert">
      <AppIcon name="alert" :size="15" />{{ loadError }}
    </p>

    <!-- 二、核心指标 -->
    <section class="grid grid-kpi">
      <StatCard label="工单总量" :value="totalCount" icon="tickets" tone="brand" desc="全部工单实时聚合" :loading="isLoading" />
      <StatCard label="今日新增" :value="todayNewCount" icon="trendUp" tone="cyan" desc="今日 0 点后创建" :loading="isLoading" />
      <StatCard label="待分配" :value="unassignedCount" icon="inbox" tone="amber" desc="待处理且未指派处理人" :loading="isLoading" />
      <StatCard label="处理中" :value="inProgressCount" icon="activity" tone="violet" desc="当前处理中的工单" :loading="isLoading" />
      <StatCard label="已完成" :value="doneCount" icon="check" tone="emerald" desc="已解决 + 已关闭" :loading="isLoading" />
      <StatCard
        label="超时工单"
        :value="overdueCount"
        icon="alert"
        tone="rose"
        desc="SLA 已超时且未结束"
        :loading="isLoading"
      />
    </section>

    <!-- 三、趋势 + 四、状态分布 -->
    <section class="grid grid-main">
      <ChartCard
        title="工单趋势分析"
        subtitle="按天聚合新增、已完成与处理中工单"
        hint="新增按创建时间统计；已完成优先按解决/关闭时间统计；处理中按当前状态与创建日期统计。"
        :loading="isLoading"
      >
        <template #actions>
          <div class="seg">
            <button
              v-for="r in ranges"
              :key="r.value"
              :class="{ active: range === r.value }"
              @click="range = r.value"
            >
              {{ r.label }}
            </button>
          </div>
        </template>
        <BaseChart v-if="totalCount > 0" :option="trendOption" :height="288" />
        <EmptyState v-else icon="chart" title="暂无趋势数据" desc="创建工单后，这里会按天展示新增与完成趋势" />
      </ChartCard>

      <ChartCard title="工单状态分布" subtitle="当前工单状态占比" :loading="isLoading">
        <div v-if="statusTotal > 0" class="donut-wrap">
          <BaseChart :option="donutOption" :height="188" />
          <div class="donut-center">
            <span class="donut-value num">{{ statusTotal }}</span>
            <span class="donut-label">工单总数</span>
          </div>
        </div>
        <ul v-if="statusTotal > 0" class="dist-list">
          <li v-for="item in statusDistribution" :key="item.key">
            <span class="dot" :style="{ background: item.color }" />
            <span class="name">{{ item.label }}</span>
            <span class="count num">{{ item.value }}</span>
            <span class="pct num">{{ percent(item.value, statusTotal) }}</span>
          </li>
        </ul>
        <EmptyState v-else compact icon="tickets" title="暂无工单" />
        <p class="note">状态由后端工作流引擎维护，共 7 种状态；SLA 由服务端实时计算。</p>
      </ChartCard>
    </section>

    <!-- 五、优先级与类型 -->
    <section class="grid grid-2">
      <ChartCard title="优先级分布" subtitle="紧急 → 低" :loading="isLoading">
        <BaseChart v-if="totalCount > 0" :option="priorityOption" :height="212" />
        <EmptyState v-else compact icon="alert" title="暂无优先级数据" />
      </ChartCard>

      <ChartCard title="工单类型分布" subtitle="按类型统计占比" :loading="isLoading">
        <BaseChart v-if="typeTotal > 0" :option="typeDonutOption" :height="212" />
        <EmptyState v-else compact icon="tag" title="暂无类型数据" />
      </ChartCard>
    </section>

    <!-- 六、最近工单 + 七、我的待办 -->
    <section class="grid grid-main">
      <ChartCard title="最近工单" subtitle="按创建时间倒序" body-class="flush" :loading="isLoading">
        <template #actions>
          <router-link to="/tickets" class="link-more">全部工单 →</router-link>
        </template>
        <DataTable
          :columns="columns"
          :rows="recentTickets"
          clickable
          dense
          empty-text="暂无工单，先创建第一条工单"
          @row-click="openTicket"
        >
          <template #cell-ticketNo="{ row }">
            <span class="mono">{{ row.ticketNo }}</span>
          </template>
          <template #cell-title="{ row }">
            <span class="cell-title">{{ row.title }}</span>
          </template>
          <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-assignee="{ row }">{{ row.assignee || '-' }}</template>
          <template #cell-createdAt="{ row }">
            <span class="muted-time">{{ formatDateTime(row.createdAt) }}</span>
          </template>
          <template #cell-actions>
            <span class="row-link">查看</span>
          </template>
        </DataTable>
      </ChartCard>

      <ChartCard title="我的待办" subtitle="与当前登录用户相关" :loading="isLoading">
        <div class="todo-stats">
          <div class="todo-stat">
            <span class="todo-num num">{{ todos.pending.length }}</span>
            <span class="todo-name">待处理</span>
          </div>
          <div class="todo-stat">
            <span class="todo-num num">{{ todos.assignedToMe.length }}</span>
            <span class="todo-name">待接单</span>
          </div>
          <div class="todo-stat">
            <span class="todo-num num">{{ warningCount }}</span>
            <span class="todo-name">临近 SLA</span>
          </div>
        </div>

        <ul v-if="todos.pending.length" class="todo-list">
          <li v-for="t in todos.pending.slice(0, 5)" :key="t.id" @click="openTicket(t)">
            <div class="todo-row">
              <span class="todo-title">{{ t.title }}</span>
              <PriorityTag :priority="t.priority" />
            </div>
            <p class="todo-meta">{{ t.ticketNo }} · {{ formatDateTime(t.createdAt) }}</p>
          </li>
        </ul>
        <EmptyState v-else compact icon="check" title="暂无待处理工单" desc="当前没有需要你处理的工单" />
        <router-link to="/tickets/mine" class="todo-more">查看我的工单 →</router-link>
      </ChartCard>
    </section>

    <!-- 八、服务健康状态 -->
    <ServiceHealth />
  </div>
</template>

<style scoped>
/* 欢迎区 */
.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 22px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  border-left: 3px solid var(--brand);
}
.date {
  font-size: 12px;
  color: var(--ink-4);
}
.clock {
  color: var(--ink-3);
}
.welcome h1 {
  margin-top: 6px;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.sub {
  margin-top: 6px;
  font-size: 13px;
  color: var(--ink-3);
}
.sub strong {
  color: var(--ink-1);
  font-weight: 600;
}
.sep {
  margin: 0 6px;
  color: #cbd5e1;
}
.welcome-actions {
  display: flex;
  gap: 10px;
  flex: none;
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

/* 分段控件 */
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
.seg button.active {
  background: #fff;
  color: var(--brand);
  font-weight: 600;
  box-shadow: var(--shadow-card);
}

/* 环形图中心 */
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
.note {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.6;
}

/* 最近工单 */
.link-more {
  font-size: 12px;
  color: var(--brand);
}
.link-more:hover {
  text-decoration: underline;
}
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.cell-title {
  display: block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-1);
}
.muted-time {
  font-size: 12px;
  color: var(--ink-4);
}
.row-link {
  font-size: 12px;
  color: var(--brand);
}

/* 我的待办 */
.todo-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.todo-stat {
  position: relative;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fafbfd;
  border: 1px solid var(--line-soft);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.todo-num {
  font-size: 19px;
  font-weight: 700;
  color: var(--ink-1);
}
.todo-name {
  font-size: 11px;
  color: var(--ink-4);
}
.todo-stat.pending .todo-num {
  color: var(--ink-4);
}
.todo-stat em {
  position: absolute;
  top: 8px;
  right: 8px;
  font-style: normal;
  font-size: 10px;
  color: #92400e;
  background: #fef3c7;
  padding: 1px 5px;
  border-radius: 4px;
}
.todo-list {
  list-style: none;
  margin-top: 10px;
}
.todo-list li {
  padding: 9px 2px;
  border-bottom: 1px solid var(--line-soft);
  cursor: pointer;
}
.todo-list li:last-child {
  border-bottom: none;
}
.todo-list li:hover .todo-title {
  color: var(--brand);
}
.todo-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.todo-title {
  font-size: 13px;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s ease;
}
.todo-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.todo-more {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  color: var(--brand);
  text-align: right;
}

@media (max-width: 1280px) {
  .welcome {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
