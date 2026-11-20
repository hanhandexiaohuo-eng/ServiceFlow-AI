<script setup lang="ts">
/** 调度历史：只读审计视图，仅管理员可查看 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import { DISPATCH_ACTION_LABEL } from '../constants/dispatch'
import { formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import { listDispatchHistory, type DispatchLog } from '../api/dispatch'
import type { TableColumn } from '../types/ui'

const PAGE_SIZE = 10

const isAdmin = getCurrentUser()?.role === 'ADMIN'

const logs = ref<DispatchLog[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const errorMessage = ref('')

const filters = reactive({ ticketId: '', agentId: '' })
const applied = reactive({ ticketId: '', agentId: '' })

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function parseId(value: string): number | null {
  const num = Number(value.trim())
  return Number.isInteger(num) && num > 0 ? num : null
}

async function load() {
  if (!isAdmin) return
  const ticketId = applied.ticketId.trim() ? parseId(applied.ticketId) : undefined
  const agentId = applied.agentId.trim() ? parseId(applied.agentId) : undefined
  if (ticketId === null || agentId === null) {
    errorMessage.value = '工单 ID 与人员 ID 需为正整数'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listDispatchHistory({ ticketId, agentId, page: page.value, size: PAGE_SIZE })
    logs.value = result.list
    total.value = result.total
  } catch (e) {
    logs.value = []
    total.value = 0
    errorMessage.value = e instanceof Error ? e.message : '调度历史加载失败'
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  Object.assign(applied, { ticketId: filters.ticketId, agentId: filters.agentId })
  page.value = 1
  load()
}

function resetFilters() {
  Object.assign(filters, { ticketId: '', agentId: '' })
  Object.assign(applied, { ticketId: '', agentId: '' })
  page.value = 1
  load()
}

function goPage(next: number) {
  page.value = Math.min(Math.max(1, next), totalPages.value)
  load()
}

const columns: TableColumn[] = [
  { key: 'createdAt', label: '时间', width: '146px', nowrap: true },
  { key: 'action', label: '动作', width: '100px', nowrap: true },
  { key: 'ticketNo', label: '工单号', width: '152px', nowrap: true },
  { key: 'agentName', label: '人员', width: '92px', nowrap: true },
  { key: 'score', label: '分数', width: '76px', align: 'right', nowrap: true },
  { key: 'candidateCount', label: '候选数', width: '76px', align: 'right', nowrap: true },
  { key: 'operatorName', label: '操作人', width: '92px', nowrap: true },
  { key: 'note', label: '备注' },
]

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>调度历史</h1>
        <p class="head-sub">推荐与确认分配的审计记录 · 数据来自后端调度日志</p>
      </div>
      <button v-if="isAdmin" class="btn" :disabled="loading" @click="load">
        <AppIcon name="refresh" :size="14" />刷新
      </button>
    </section>

    <template v-if="!isAdmin">
      <section class="card gate">
        <EmptyState icon="alert" title="仅管理员可查看调度历史" desc="当前账号没有调度审计权限。" />
      </section>
    </template>

    <template v-else>
      <p v-if="errorMessage" class="alert"><AppIcon name="alert" :size="15" />{{ errorMessage }}</p>

      <section class="filters-card">
        <div class="filter-grid">
          <label class="f-field">
            <span>工单 ID</span>
            <input v-model="filters.ticketId" inputmode="numeric" placeholder="按工单 ID 过滤" @keyup.enter="applyFilters" />
          </label>
          <label class="f-field">
            <span>人员 ID</span>
            <input v-model="filters.agentId" inputmode="numeric" placeholder="按人员 ID 过滤" @keyup.enter="applyFilters" />
          </label>
          <div class="filter-actions">
            <button class="btn btn-sm" @click="resetFilters">重置</button>
            <button class="btn btn-sm btn-primary" @click="applyFilters">
              <AppIcon name="search" :size="13" />查询
            </button>
          </div>
        </div>
      </section>

      <section class="card">
        <header class="card-head">
          <h2>调度记录</h2>
          <span class="meta">共 <b class="num">{{ total }}</b> 条 · 每页 {{ PAGE_SIZE }} 条</span>
        </header>

        <DataTable
          :columns="columns"
          :rows="logs"
          row-key="id"
          :loading="loading && logs.length === 0"
          empty-text="暂无调度记录"
        >
          <template #cell-createdAt="{ row }">
            <span class="time">{{ formatDateTime(row.createdAt) }}</span>
          </template>
          <template #cell-action="{ row }">
            <span class="action" :class="{ assign: row.action === 'ASSIGN' }">
              {{ DISPATCH_ACTION_LABEL[row.action] ?? row.action }}
            </span>
          </template>
          <template #cell-ticketNo="{ row }">
            <span class="mono">{{ row.ticketNo }}</span>
          </template>
          <template #cell-agentName="{ row }">
            <span v-if="row.agentName">{{ row.agentName }}</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-score="{ row }">
            <span class="num">{{ row.score === null ? '-' : row.score.toFixed(1) }}</span>
          </template>
          <template #cell-candidateCount="{ row }">
            <span class="num">{{ row.candidateCount === null ? '-' : row.candidateCount }}</span>
          </template>
          <template #cell-operatorName="{ row }">
            <span>{{ row.operatorName }}</span>
          </template>
          <template #cell-note="{ row }">
            <span class="note" :title="row.note ?? ''">{{ row.note ?? '-' }}</span>
          </template>
        </DataTable>

        <footer class="pager">
          <span class="pager-info">
            第 <b class="num">{{ page }}</b> / <b class="num">{{ totalPages }}</b> 页 · 共
            <b class="num">{{ total }}</b> 条
          </span>
          <div class="pager-btns">
            <button class="pg" :disabled="page <= 1 || loading" @click="goPage(1)">«</button>
            <button class="pg" :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
            <button class="pg" :disabled="page >= totalPages || loading" @click="goPage(page + 1)">下一页</button>
            <button class="pg" :disabled="page >= totalPages || loading" @click="goPage(totalPages)">»</button>
          </div>
        </footer>
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
.filters-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: 14px 16px;
}
.filter-grid {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.f-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 190px;
}
.f-field > span {
  font-size: 11px;
  color: var(--ink-4);
  font-weight: 600;
  letter-spacing: 0.03em;
}
.f-field input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fafbfd;
  font-size: 12px;
  color: var(--ink-1);
}
.f-field input:focus {
  outline: none;
  background: #fff;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
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
.time {
  font-size: 12px;
  color: var(--ink-4);
  font-variant-numeric: tabular-nums;
}
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.action {
  display: inline-flex;
  align-items: center;
  height: 21px;
  padding: 0 7px;
  border-radius: 5px;
  font-size: 11px;
  background: var(--line-soft);
  color: var(--ink-3);
}
.action.assign {
  background: var(--brand-weak);
  color: #4338ca;
}
.note {
  display: block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ink-3);
}
.muted {
  color: var(--ink-4);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 18px;
  border-top: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.pager-info {
  font-size: 12px;
  color: var(--ink-4);
}
.pager-info b {
  color: var(--ink-2);
}
.pager-btns {
  display: flex;
  gap: 4px;
}
.pg {
  min-width: 28px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
}
.pg:hover:not(:disabled) {
  border-color: #c7d2fe;
  color: var(--brand);
}
.pg:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
