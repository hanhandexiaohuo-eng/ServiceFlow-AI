<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import ChartCard from '../components/ChartCard.vue'
import DataTable from '../components/DataTable.vue'
import PriorityTag from '../components/PriorityTag.vue'
import SlaBadge from '../components/SlaBadge.vue'
import StatCard from '../components/StatCard.vue'
import StatusTag from '../components/StatusTag.vue'
import { PRIORITY_LABEL } from '../constants/ticket'
import type { Ticket } from '../api/tickets'
import { listSlaPolicies, type SlaPolicy } from '../api/sla'
import {
  isLoading,
  loadError,
  refreshTickets,
  tickets,
} from '../composables/useTicketStore'
import type { TableColumn } from '../types/ui'

const router = useRouter()

/* ---------------- 策略：全部来自 GET /api/sla/policies ---------------- */

const policies = ref<SlaPolicy[]>([])
const policyLoading = ref(true)
const policyError = ref('')

async function loadPolicies() {
  policyLoading.value = true
  policyError.value = ''
  try {
    policies.value = await listSlaPolicies()
  } catch (e) {
    policyError.value = e instanceof Error ? e.message : 'SLA 策略加载失败'
  } finally {
    policyLoading.value = false
  }
}

async function reload() {
  await Promise.all([refreshTickets(true), loadPolicies()])
}

const policyColumns: TableColumn[] = [
  { key: 'priority', label: '优先级', width: '120px', nowrap: true },
  { key: 'hours', label: '响应时限', width: '150px', nowrap: true },
  { key: 'warningMinutes', label: '预警阈值', width: '160px', nowrap: true },
  { key: 'label', label: '策略名' },
]

const policyRows = computed<Record<string, any>[]>(() =>
  policies.value.map((p) => ({
    priority: p.priority,
    hours: p.hours,
    warningMinutes: p.warningMinutes,
    label: p.label,
  }))
)

/* ---------------- 实时监控：直接读取后端计算的 ticket.sla.state ---------------- */

type SlaStateKey = 'ON_TRACK' | 'WARNING' | 'OVERDUE' | 'COMPLETED'

interface MetricItem {
  key: SlaStateKey
  label: string
  value: number
  icon: string
  tone: 'brand' | 'cyan' | 'amber' | 'emerald' | 'rose' | 'violet'
  desc: string
}

const slaCounts = computed<Record<SlaStateKey, number>>(() => {
  const map: Record<SlaStateKey, number> = {
    ON_TRACK: 0,
    WARNING: 0,
    OVERDUE: 0,
    COMPLETED: 0,
  }
  tickets.value.forEach((t) => {
    map[t.sla.state] += 1
  })
  return map
})

const metrics = computed<MetricItem[]>(() => [
  {
    key: 'ON_TRACK',
    label: '正常',
    value: slaCounts.value.ON_TRACK,
    icon: 'activity',
    tone: 'emerald',
    desc: 'ON_TRACK · 剩余时间充足',
  },
  {
    key: 'WARNING',
    label: '即将超时',
    value: slaCounts.value.WARNING,
    icon: 'clock',
    tone: 'amber',
    desc: 'WARNING · 已进入预警阈值',
  },
  {
    key: 'OVERDUE',
    label: '已超时',
    value: slaCounts.value.OVERDUE,
    icon: 'alert',
    tone: 'rose',
    desc: 'OVERDUE · 已超过响应时限',
  },
  {
    key: 'COMPLETED',
    label: '已完成',
    value: slaCounts.value.COMPLETED,
    icon: 'check',
    tone: 'brand',
    desc: 'COMPLETED · SLA 计时已结束',
  },
])

/* ---------------- 最紧急工单 ---------------- */

const urgentTickets = computed(() =>
  [...tickets.value]
    .filter((t) => t.sla.state !== 'COMPLETED')
    .sort((a, b) => a.sla.remainingMinutes - b.sla.remainingMinutes)
    .slice(0, 10)
)

const urgentColumns: TableColumn[] = [
  { key: 'ticketNo', label: '工单编号', width: '152px', nowrap: true },
  { key: 'title', label: '标题' },
  { key: 'priority', label: '优先级', width: '82px' },
  { key: 'status', label: '状态', width: '100px' },
  { key: 'assignee', label: '处理人', width: '92px', nowrap: true },
  { key: 'sla', label: 'SLA 剩余时间', width: '170px', nowrap: true },
]

function openTicket(row: Record<string, any>) {
  router.push(`/tickets/${row.id}`)
}

onMounted(async () => {
  await Promise.all([refreshTickets(), loadPolicies()])
})
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>SLA 管理</h1>
        <p class="head-sub">
          响应时限与预警阈值由后端策略定义 · 前端只做格式化展示
        </p>
      </div>
      <button class="btn" :disabled="isLoading || policyLoading" @click="reload">
        <AppIcon name="refresh" :size="14" />刷新
      </button>
    </section>

    <p v-if="policyError" class="alert">
      <AppIcon name="alert" :size="15" />{{ policyError }}
    </p>
    <p v-if="loadError" class="alert">
      <AppIcon name="alert" :size="15" />{{ loadError }}
    </p>

    <!-- 响应策略 -->
    <ChartCard
      title="响应策略"
      subtitle="GET /api/sla/policies · 策略由后端代码定义，页面只读"
      hint="响应时限与预警阈值均来自后端返回的策略数据，前端不硬编码任何数值。"
      body-class="flush"
      :loading="policyLoading"
    >
      <DataTable
        :columns="policyColumns"
        :rows="policyRows"
        row-key="priority"
        :loading="policyLoading && policies.length === 0"
        empty-text="后端暂未返回 SLA 策略"
      >
        <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
        <template #cell-hours="{ row }">
          <span class="policy-value num">{{ row.hours }}<small>小时</small></span>
          <span class="policy-hint">{{ PRIORITY_LABEL[row.priority] ?? row.priority }} 优先级的响应时限</span>
        </template>
        <template #cell-warningMinutes="{ row }">
          <span class="policy-value num">{{ row.warningMinutes }}<small>分钟</small></span>
          <span class="policy-hint">截止前 {{ row.warningMinutes }} 分钟进入预警</span>
        </template>
        <template #cell-label="{ row }">
          <span class="policy-name">{{ row.label }}</span>
        </template>
      </DataTable>
    </ChartCard>

    <!-- 实时监控 -->
    <section class="monitor">
      <header class="monitor-head">
        <h2>实时监控</h2>
        <p>
          口径：工单 <code>sla.state</code>（后端计算）· 当前统计
          <b class="num">{{ tickets.length }}</b> 条工单
        </p>
      </header>
      <div class="monitor-grid">
        <StatCard
          v-for="item in metrics"
          :key="item.key"
          :label="item.label"
          :value="item.value"
          :icon="item.icon"
          :tone="item.tone"
          :desc="item.desc"
          :loading="isLoading"
        />
      </div>
    </section>

    <!-- 最紧急工单 -->
    <ChartCard
      title="最紧急工单"
      subtitle="未完成工单按剩余时间升序 · 前 10 条"
      hint="点击任意行进入工单详情；SlaBadge 展示的剩余时间与预警状态均来自后端 SLA 计算结果。"
      body-class="flush"
      :loading="isLoading && tickets.length === 0"
    >
      <DataTable
        :columns="urgentColumns"
        :rows="urgentTickets"
        dense
        clickable
        empty-text="暂无未完成的工单"
        @row-click="openTicket"
      >
        <template #cell-ticketNo="{ row }">
          <span class="mono">{{ row.ticketNo }}</span>
        </template>
        <template #cell-title="{ row }">
          <span class="cell-title" :title="row.title">{{ row.title }}</span>
        </template>
        <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
        <template #cell-assignee="{ row }">
          <span v-if="row.assignee" class="person">{{ row.assignee }}</span>
          <span v-else class="person none">未分配</span>
        </template>
        <template #cell-sla="{ row }">
          <SlaBadge :ticket="row as Ticket" show-warn-text />
        </template>
      </DataTable>
    </ChartCard>

    <!-- 说明 -->
    <section class="readonly-note">
      <span class="note-icon"><AppIcon name="clock" :size="16" /></span>
      <div>
        <h4>策略只读，展示统一</h4>
        <p>
          SLA 策略当前由后端定义、页面只读；前端不推算截止时间，只对后端返回的状态、剩余时间与百分比做格式化展示。
          工单进入已解决、已关闭或已取消后停止计时。
        </p>
      </div>
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

/* 策略表 */
.policy-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.policy-value small {
  margin-left: 3px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-4);
}
.policy-hint {
  display: block;
  margin-top: 1px;
  font-size: 11px;
  color: var(--ink-4);
}
.policy-name {
  font-size: 13px;
  color: var(--ink-2);
}

/* 实时监控 */
.monitor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.monitor-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.monitor-head h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
}
.monitor-head p {
  font-size: 11px;
  color: var(--ink-4);
}
.monitor-head code {
  font-family: Consolas, 'Courier New', monospace;
  color: var(--ink-3);
}
.monitor-head b {
  color: var(--ink-2);
  font-weight: 600;
}
.monitor-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

/* 最紧急工单 */
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.cell-title {
  display: block;
  max-width: 440px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-1);
  font-weight: 500;
}
.person {
  font-size: 12px;
  color: var(--ink-2);
}
.person.none {
  color: var(--amber);
}

/* 说明 */
.readonly-note {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--brand);
  border-radius: var(--radius-card);
  background: var(--panel);
  box-shadow: var(--shadow-card);
}
.note-icon {
  flex: none;
  margin-top: 1px;
  color: var(--brand);
}
.readonly-note h4 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.readonly-note p {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-4);
}

@media (max-width: 1100px) {
  .monitor-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .cell-title {
    max-width: 220px;
  }
}
</style>
