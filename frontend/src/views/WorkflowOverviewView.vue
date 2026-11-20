<script setup lang="ts">
import { computed, onMounted } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import ChartCard from '../components/ChartCard.vue'
import DataTable from '../components/DataTable.vue'
import StatCard from '../components/StatCard.vue'
import StatusTag from '../components/StatusTag.vue'
import {
  ACTION_META,
  STATUS_COLOR,
  STATUS_DESC,
  STATUS_LABEL,
  STATUS_OPTIONS,
} from '../constants/ticket'
import type { TicketActionKey } from '../api/tickets'
import {
  isLoading,
  loadError,
  refreshTickets,
  tickets,
  totalCount,
} from '../composables/useTicketStore'
import type { TableColumn } from '../types/ui'

type Tone = 'brand' | 'cyan' | 'amber' | 'emerald' | 'rose' | 'violet'

const STATUS_TONE: Record<string, Tone> = {
  PENDING: 'amber',
  ASSIGNED: 'cyan',
  ACCEPTED: 'violet',
  PROCESSING: 'brand',
  RESOLVED: 'emerald',
  CLOSED: 'cyan',
  CANCELLED: 'rose',
}

const STATUS_ICON: Record<string, string> = {
  PENDING: 'inbox',
  ASSIGNED: 'users',
  ACCEPTED: 'check',
  PROCESSING: 'activity',
  RESOLVED: 'check',
  CLOSED: 'layers',
  CANCELLED: 'close',
}

/* ---------------- 实时统计：全部来自 useTicketStore 的 tickets ---------------- */

const counts = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  STATUS_OPTIONS.forEach((s) => {
    map[s] = 0
  })
  tickets.value.forEach((t) => {
    map[t.status] = (map[t.status] ?? 0) + 1
  })
  return map
})

interface SummaryItem {
  key: string
  label: string
  value: number
  icon: string
  tone: Tone
  desc: string
}

const summary = computed<SummaryItem[]>(() => {
  const items: SummaryItem[] = [
    {
      key: 'ALL',
      label: '工单总量',
      value: totalCount.value,
      icon: 'tickets',
      tone: 'brand',
      desc: '全部工单实时聚合',
    },
  ]
  STATUS_OPTIONS.forEach((s) => {
    items.push({
      key: s,
      label: STATUS_LABEL[s] ?? s,
      value: counts.value[s] ?? 0,
      icon: STATUS_ICON[s] ?? 'tickets',
      tone: STATUS_TONE[s] ?? 'brand',
      desc: STATUS_DESC[s] ?? '',
    })
  })
  return items
})

interface FlowNode {
  key: string
  label: string
  desc: string
  count: number
  color: string
}

const flow = computed<FlowNode[]>(() =>
  STATUS_OPTIONS.map((s) => ({
    key: s,
    label: STATUS_LABEL[s] ?? s,
    desc: STATUS_DESC[s] ?? '',
    count: counts.value[s] ?? 0,
    color: STATUS_COLOR[s] ?? '#94a3b8',
  }))
)

/* ---------------- 动作规则：静态说明 + 服务端 allowedActions 实时统计 ---------------- */

interface ActionRule {
  key: TicketActionKey
  from: string[]
  to: string
  permission: string
}

const ACTION_RULES: ActionRule[] = [
  { key: 'ASSIGN', from: ['PENDING'], to: 'ASSIGNED', permission: '创建人或 ADMIN' },
  {
    key: 'TRANSFER',
    from: ['ASSIGNED', 'ACCEPTED', 'PROCESSING'],
    to: 'ASSIGNED',
    permission: '当前处理人或 ADMIN',
  },
  { key: 'ACCEPT', from: ['ASSIGNED'], to: 'ACCEPTED', permission: '当前处理人或 ADMIN' },
  { key: 'START', from: ['ACCEPTED'], to: 'PROCESSING', permission: '当前处理人或 ADMIN' },
  { key: 'RESOLVE', from: ['PROCESSING'], to: 'RESOLVED', permission: '当前处理人或 ADMIN' },
  { key: 'CLOSE', from: ['RESOLVED'], to: 'CLOSED', permission: '创建人或 ADMIN' },
  {
    key: 'CANCEL',
    from: ['PENDING', 'ASSIGNED', 'ACCEPTED', 'PROCESSING'],
    to: 'CANCELLED',
    permission: '创建人或 ADMIN',
  },
]

const ruleColumns: TableColumn[] = [
  { key: 'action', label: '动作', width: '168px', nowrap: true },
  { key: 'from', label: '允许的源状态', width: '360px' },
  { key: 'to', label: '目标状态', width: '112px' },
  { key: 'permission', label: '权限要求', width: '176px', nowrap: true },
  { key: 'active', label: '当前可执行工单', width: '132px', align: 'right', nowrap: true },
]

const ruleRows = computed<Record<string, any>[]>(() =>
  ACTION_RULES.map((rule) => ({
    key: rule.key,
    action: ACTION_META[rule.key].label,
    from: rule.from,
    to: rule.to,
    permission: rule.permission,
    active: tickets.value.filter((t) => (t.allowedActions ?? []).includes(rule.key)).length,
  }))
)

onMounted(() => {
  refreshTickets()
})
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>工作流总览</h1>
        <p class="head-sub">7 状态工单状态机 · 服务端校验</p>
      </div>
      <button class="btn" :disabled="isLoading" @click="refreshTickets(true)">
        <AppIcon name="refresh" :size="14" />刷新
      </button>
    </section>

    <p v-if="loadError" class="alert">
      <AppIcon name="alert" :size="15" />{{ loadError }}
    </p>

    <!-- 实时统计 -->
    <section class="summary-grid">
      <StatCard
        v-for="item in summary"
        :key="item.key"
        :label="item.label"
        :value="item.value"
        :icon="item.icon"
        :tone="item.tone"
        :desc="item.desc"
        :loading="isLoading"
      />
    </section>

    <!-- 状态流程 -->
    <ChartCard
      title="状态流程"
      subtitle="按固定状态机顺序展示，数量来自实时工单数据"
      hint="取消是流程之外的终止分支：工单进入 CANCELLED 后停止 SLA 计时，且不可再流转。"
    >
      <div class="flow">
        <div
          v-for="node in flow"
          :key="node.key"
          class="node"
          :class="{ terminal: node.key === 'CANCELLED' }"
          :style="{ '--node': node.color }"
        >
          <span v-if="node.key !== 'CANCELLED'" class="arrow" aria-hidden="true">›</span>
          <div class="node-head">
            <span class="dot" :style="{ background: node.color }" />
            <span class="node-label">{{ node.label }}</span>
          </div>
          <p class="node-count num">{{ node.count }}<small>单</small></p>
          <p class="node-desc">{{ node.desc }}</p>
          <em v-if="node.key === 'CANCELLED'" class="abnormal">非正常终止</em>
        </div>
      </div>
    </ChartCard>

    <!-- 动作规则 -->
    <ChartCard
      title="动作规则"
      subtitle="每个动作的源状态、目标状态与权限要求均由后端状态机执行"
      hint="「当前可执行工单」按服务端返回的 allowedActions 统计，代表当前登录用户此刻可以操作的工单数。"
      body-class="flush"
    >
      <DataTable
        :columns="ruleColumns"
        :rows="ruleRows"
        row-key="key"
        :loading="isLoading && tickets.length === 0"
        dense
        empty-text="暂无动作规则"
      >
        <template #cell-action="{ row }">
          <span class="action-name">{{ row.action }}</span>
          <code class="action-code">{{ row.key }}</code>
        </template>
        <template #cell-from="{ row }">
          <span class="status-group">
            <template v-for="(s, i) in row.from" :key="s">
              <StatusTag :status="s" />
              <span v-if="Number(i) < Number(row.from.length) - 1" class="slash">/</span>
            </template>
          </span>
        </template>
        <template #cell-to="{ row }"><StatusTag :status="row.to" /></template>
        <template #cell-permission="{ row }">
          <span class="permission">{{ row.permission }}</span>
        </template>
        <template #cell-active="{ row }">
          <b class="num active-count">{{ row.active }}</b> 单
        </template>
      </DataTable>
    </ChartCard>

    <!-- 校验说明 -->
    <section class="rules-note">
      <div class="note-item tone-amber">
        <span class="note-icon"><AppIcon name="alert" :size="16" /></span>
        <div>
          <h4>非法流转返回 403</h4>
          <p>源状态、权限或动作不匹配时，服务端直接拒绝，并提示当前操作不被允许。</p>
        </div>
      </div>
      <div class="note-item tone-violet">
        <span class="note-icon"><AppIcon name="refresh" :size="16" /></span>
        <div>
          <h4>并发冲突返回 409</h4>
          <p>状态已被他人抢先改变时，本次操作失败，需要重新加载工单后再试。</p>
        </div>
      </div>
      <div class="note-item tone-rose">
        <span class="note-icon"><AppIcon name="close" :size="16" /></span>
        <div>
          <h4>终态不可再流转</h4>
          <p>CLOSED 与 CANCELLED 是终态，不再提供任何流转动作。</p>
        </div>
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

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

/* 状态流程 */
.flow {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 14px;
}
.node {
  position: relative;
  min-width: 0;
  padding: 12px 12px 13px;
  border: 1px solid var(--line-soft);
  border-top: 3px solid var(--node);
  border-radius: 10px;
  background: #fafbfd;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.node:hover {
  border-color: #dbe1ea;
  box-shadow: var(--shadow-card);
}
.node.terminal {
  background: #fffafa;
  border-color: #fee2e2;
  border-top-color: var(--node);
}
.arrow {
  position: absolute;
  top: 50%;
  right: -11px;
  transform: translateY(-50%);
  font-size: 17px;
  line-height: 1;
  color: #cbd5e1;
  z-index: 1;
  pointer-events: none;
}
.node-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.node-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
  white-space: nowrap;
}
.node-count {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--ink-1);
}
.node-count small {
  margin-left: 3px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-4);
}
.node-desc {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-4);
}
.abnormal {
  display: inline-flex;
  align-items: center;
  height: 18px;
  margin-top: 6px;
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid #fecaca;
  background: #fef2f2;
  color: #b91c1c;
  font-style: normal;
  font-size: 10px;
  white-space: nowrap;
}

/* 动作规则表 */
.action-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.action-code {
  margin-left: 6px;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--ink-4);
}
.status-group {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.slash {
  font-size: 11px;
  color: var(--ink-4);
}
.permission {
  font-size: 12px;
  color: var(--ink-3);
}
.active-count {
  color: var(--brand);
  font-weight: 700;
}

/* 校验说明 */
.rules-note {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.note-item {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--tone);
  border-radius: var(--radius-card);
  background: var(--panel);
  box-shadow: var(--shadow-card);
  --tone: var(--ink-4);
}
.note-icon {
  flex: none;
  margin-top: 1px;
  color: var(--tone);
}
.note-item h4 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.note-item p {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--ink-4);
}
.tone-amber {
  --tone: var(--amber);
}
.tone-violet {
  --tone: var(--violet);
}
.tone-rose {
  --tone: var(--rose);
}

@media (max-width: 1200px) {
  .flow {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .arrow {
    display: none;
  }
}
@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .rules-note {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 900px) {
  .flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
