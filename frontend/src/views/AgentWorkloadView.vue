<script setup lang="ts">
/** 人员负载：全部指标来自后端实时统计，空值统一显示 - */
import { computed, onMounted, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadBar from '../components/LoadBar.vue'
import StatCard from '../components/StatCard.vue'
import { LOAD_LEVEL_COLOR, LOAD_LEVEL_LABEL } from '../constants/dispatch'
import { getCurrentUser } from '../api/request'
import {
  getWorkloadOverview,
  listWorkload,
  type Workload,
  type WorkloadOverview,
} from '../api/agents'
import type { TableColumn } from '../types/ui'

const currentUser = getCurrentUser()
const canView =
  currentUser?.role === 'ADMIN' || currentUser?.role === 'AGENT'

const workloads = ref<Workload[]>([])
const overview = ref<WorkloadOverview | null>(null)
const loading = ref(false)
const errorMessage = ref('')

async function load() {
  if (!canView) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [summary, list] = await Promise.all([getWorkloadOverview(), listWorkload()])
    overview.value = summary
    workloads.value = list
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '负载数据加载失败'
  } finally {
    loading.value = false
  }
}

interface StatItem {
  key: string
  label: string
  value: number | string
  icon: string
  tone: 'brand' | 'cyan' | 'amber' | 'emerald' | 'rose' | 'violet'
  desc: string
}

const stats = computed<StatItem[]>(() => {
  const o = overview.value
  return [
    {
      key: 'total',
      label: '总人数',
      value: o ? o.totalAgents : '-',
      icon: 'users',
      tone: 'brand',
      desc: '全部处理人员账号',
    },
    {
      key: 'available',
      label: '可接单',
      value: o ? o.availableAgents : '-',
      icon: 'check',
      tone: 'emerald',
      desc: '状态为可接单',
    },
    {
      key: 'busy',
      label: '繁忙',
      value: o ? o.busyAgents : '-',
      icon: 'activity',
      tone: 'amber',
      desc: '繁忙状态人员',
    },
    {
      key: 'pending',
      label: '待分配工单',
      value: o ? o.unassignedPending : '-',
      icon: 'inbox',
      tone: 'rose',
      desc: 'PENDING 工单数量',
    },
    {
      key: 'load',
      label: '平均负载率',
      value: o ? `${o.avgLoadRate.toFixed(1)}%` : '-',
      icon: 'gauge',
      tone: 'violet',
      desc: '占用 / 最大并发',
    },
  ]
})

const columns: TableColumn[] = [
  { key: 'name', label: '姓名', width: '160px' },
  { key: 'maxConcurrent', label: '最大并发', width: '88px', align: 'center' },
  { key: 'occupied', label: '占用', width: '200px' },
  { key: 'loadRate', label: '负载', width: '150px' },
  { key: 'loadLevel', label: '等级', width: '100px', nowrap: true },
  { key: 'todayCompleted', label: '今日完成', width: '88px', align: 'center' },
  { key: 'weekCompleted', label: '本周完成', width: '88px', align: 'center' },
  { key: 'avgHandlingMinutes', label: '平均处理时长', width: '116px', align: 'center', nowrap: true },
  { key: 'slaAchievedRate', label: 'SLA 达成率', width: '108px', align: 'center', nowrap: true },
  { key: 'canAccept', label: '可接单', width: '84px', align: 'center', nowrap: true },
]

function levelText(level: string): string {
  return LOAD_LEVEL_LABEL[level] ?? level
}

function levelColor(level: string): string {
  return LOAD_LEVEL_COLOR[level] ?? '#94a3b8'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>人员负载</h1>
        <p class="head-sub">
          占用、负载率与绩效均来自工单真实记录 · 无完成样本时平均时长与达成率显示 -
        </p>
      </div>
      <button v-if="canView" class="btn" :disabled="loading" @click="load">
        <AppIcon name="refresh" :size="14" />刷新
      </button>
    </section>

    <template v-if="!canView">
      <section class="card gate">
        <EmptyState
          icon="alert"
          title="仅管理员与处理人员可查看人员负载"
          desc="当前账号没有负载查看权限。"
        />
      </section>
    </template>

    <template v-else>
      <p v-if="errorMessage" class="alert"><AppIcon name="alert" :size="15" />{{ errorMessage }}</p>

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

      <section class="card">
        <header class="card-head">
          <h2>负载明细</h2>
          <span class="meta">共 <b class="num">{{ workloads.length }}</b> 人</span>
        </header>

        <DataTable
          :columns="columns"
          :rows="workloads"
          row-key="agentId"
          :loading="loading && workloads.length === 0"
          empty-text="暂无处理人员负载数据"
        >
          <template #cell-name="{ row }">
            <div class="who">
              <strong>{{ row.name }}</strong>
              <span class="uname">{{ row.username }}</span>
            </div>
          </template>
          <template #cell-maxConcurrent="{ row }">
            <span class="num">{{ row.maxConcurrent }}</span>
          </template>
          <template #cell-occupied="{ row }">
            <div class="occ">
              <b class="num">{{ row.occupied }}</b>
              <span class="occ-sub">
                待接 {{ row.assignedCount }} / 已接 {{ row.acceptedCount }} / 处理中
                {{ row.processingCount }}
              </span>
            </div>
          </template>
          <template #cell-loadRate="{ row }">
            <LoadBar :rate="row.loadRate" :level="row.loadLevel" />
          </template>
          <template #cell-loadLevel="{ row }">
            <span class="level" :style="{ color: levelColor(row.loadLevel) }">
              {{ levelText(row.loadLevel) }}
            </span>
          </template>
          <template #cell-todayCompleted="{ row }">
            <span class="num">{{ row.todayCompleted }}</span>
          </template>
          <template #cell-weekCompleted="{ row }">
            <span class="num">{{ row.weekCompleted }}</span>
          </template>
          <template #cell-avgHandlingMinutes="{ row }">
            <span class="num muted">
              {{ row.avgHandlingMinutes === null ? '-' : `${row.avgHandlingMinutes} 分钟` }}
            </span>
          </template>
          <template #cell-slaAchievedRate="{ row }">
            <span class="num muted">
              {{ row.slaAchievedRate === null ? '-' : `${row.slaAchievedRate.toFixed(1)}%` }}
            </span>
          </template>
          <template #cell-canAccept="{ row }">
            <span class="accept" :class="{ no: !row.canAccept }">
              {{ row.canAccept ? '可接单' : '不可接单' }}
            </span>
          </template>
        </DataTable>
      </section>
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
}
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  min-width: 0;
  overflow: hidden;
}
.gate {
  padding: 8px;
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
.stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.card-head h2 {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-1);
}
.meta {
  font-size: 11px;
  color: var(--ink-4);
}
.meta b {
  color: var(--ink-2);
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
.occ {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.occ b {
  font-size: 14px;
  color: var(--ink-1);
}
.occ-sub {
  font-size: 10px;
  color: var(--ink-4);
  white-space: nowrap;
}
.level {
  font-size: 12px;
  font-weight: 600;
}
.muted {
  color: var(--ink-4);
}
.accept {
  display: inline-flex;
  align-items: center;
  height: 21px;
  padding: 0 7px;
  border-radius: 5px;
  font-size: 11px;
  background: #ecfdf5;
  color: #047857;
}
.accept.no {
  background: #f1f5f9;
  color: #64748b;
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
