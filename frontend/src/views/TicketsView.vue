<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DataTable from '../components/DataTable.vue'
import AppIcon from '../components/AppIcon.vue'
import PriorityTag from '../components/PriorityTag.vue'
import StatusTag from '../components/StatusTag.vue'
import TicketFormModal from '../components/TicketFormModal.vue'
import {
  PRIORITY_LABEL,
  PRIORITY_OPTIONS,
  STATUS_LABEL,
  STATUS_OPTIONS,
  TYPE_LABEL,
  formatDateTime,
} from '../constants/ticket'
import { deleteTicket, type Ticket } from '../api/tickets'
import { getCurrentUser } from '../api/request'
import { isLoading, refreshTickets, tickets as allTickets } from '../composables/useTicketStore'
import type { TableColumn } from '../types/ui'

const route = useRoute()
const router = useRouter()
const isAdmin = getCurrentUser()?.role === 'ADMIN'

const keyword = ref(String(route.query.q ?? ''))
const status = ref('')
const priority = ref('')
const page = ref(1)
const size = 10

const columns: TableColumn[] = [
  { key: 'ticketNo', label: '工单编号', width: '150px', nowrap: true },
  { key: 'title', label: '标题' },
  { key: 'type', label: '类型', width: '100px', nowrap: true },
  { key: 'priority', label: '优先级', width: '80px' },
  { key: 'status', label: '状态', width: '96px' },
  { key: 'creator', label: '创建人', width: '100px', nowrap: true },
  { key: 'assignee', label: '处理人', width: '100px', nowrap: true },
  { key: 'createdAt', label: '创建时间', width: '140px', nowrap: true },
  { key: 'actions', label: '操作', width: '104px', align: 'right' },
]

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return allTickets.value.filter((t) => {
    if (status.value && t.status !== status.value) return false
    if (priority.value && t.priority !== priority.value) return false
    if (q) {
      const hit =
        t.title.toLowerCase().includes(q) ||
        t.ticketNo.toLowerCase().includes(q) ||
        (t.assignee ?? '').toLowerCase().includes(q) ||
        (t.creator ?? '').toLowerCase().includes(q)
      if (!hit) return false
    }
    return true
  })
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / size)))
const paged = computed(() => filtered.value.slice((page.value - 1) * size, page.value * size))

watch([keyword, status, priority], () => {
  page.value = 1
})
watch(
  () => route.query.q,
  (q) => {
    keyword.value = String(q ?? '')
  }
)
watch(totalPages, (max) => {
  if (page.value > max) page.value = max
})

/* 弹窗 */
const modalOpen = ref(false)
const editing = ref<Ticket | null>(null)

function openCreate() {
  editing.value = null
  modalOpen.value = true
}

function openEdit(t: Ticket) {
  editing.value = t
  modalOpen.value = true
}

async function onSaved() {
  page.value = 1
  await refreshTickets(true)
}

async function remove(t: Ticket) {
  if (!confirm(`确认删除工单 ${t.ticketNo}？删除后不可恢复。`)) return
  try {
    await deleteTicket(t.id)
    await refreshTickets(true)
  } catch (e) {
    alert(e instanceof Error ? e.message : '删除失败')
  }
}

function resetFilters() {
  keyword.value = ''
  status.value = ''
  priority.value = ''
  if (route.query.q || route.query.new) router.replace({ path: '/tickets' })
}

onMounted(async () => {
  await refreshTickets()
  if (route.query.new === '1') {
    openCreate()
    router.replace({ path: '/tickets' })
  }
})
</script>

<template>
  <div class="page">
    <section class="toolbar">
      <div class="filters">
        <div class="search">
          <AppIcon name="search" :size="15" />
          <input v-model="keyword" placeholder="搜索编号、标题、处理人或创建人" />
        </div>
        <select v-model="status">
          <option value="">全部状态</option>
          <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
        </select>
        <select v-model="priority">
          <option value="">全部优先级</option>
          <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
        </select>
        <button v-if="keyword || status || priority" class="btn btn-sm" @click="resetFilters">重置</button>
      </div>
      <div class="tools">
        <button class="btn" :disabled="isLoading" title="刷新" @click="refreshTickets(true)">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button class="btn btn-primary" @click="openCreate">
          <AppIcon name="plus" :size="15" />新建工单
        </button>
      </div>
    </section>

    <section class="table-card">
      <header class="card-head">
        <div>
          <h3>工单列表</h3>
          <p>
            共 <strong class="num">{{ allTickets.length }}</strong> 条
            <template v-if="filtered.length !== allTickets.length">
              · 筛选后 <strong class="num">{{ filtered.length }}</strong> 条
            </template>
          </p>
        </div>
      </header>

      <DataTable
        :columns="columns"
        :rows="paged"
        :loading="isLoading && allTickets.length === 0"
        clickable
        empty-text="没有符合条件的工单"
        @row-click="(row) => router.push(`/tickets/${row.id}`)"
      >
        <template #cell-ticketNo="{ row }">
          <span class="mono">{{ row.ticketNo }}</span>
        </template>
        <template #cell-title="{ row }">
          <span class="cell-title">{{ row.title }}</span>
        </template>
        <template #cell-type="{ row }">{{ TYPE_LABEL[row.type] ?? row.type }}</template>
        <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
        <template #cell-assignee="{ row }">{{ row.assignee || '-' }}</template>
        <template #cell-createdAt="{ row }">
          <span class="time">{{ formatDateTime(row.createdAt) }}</span>
        </template>
        <template #cell-actions="{ row }">
          <div class="row-actions" @click.stop>
            <button class="link" @click="openEdit(row as Ticket)">编辑</button>
            <button v-if="isAdmin" class="link danger" @click="remove(row as Ticket)">删除</button>
          </div>
        </template>
      </DataTable>

      <footer class="pager">
        <span class="pager-info">
          第 <strong class="num">{{ page }}</strong> / <span class="num">{{ totalPages }}</span> 页
        </span>
        <div class="pager-btns">
          <button class="btn btn-sm" :disabled="page <= 1" @click="page -= 1">上一页</button>
          <button class="btn btn-sm" :disabled="page >= totalPages" @click="page += 1">下一页</button>
        </div>
      </footer>
    </section>

    <TicketFormModal :open="modalOpen" :ticket="editing" @close="modalOpen = false" @saved="onSaved" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  flex-wrap: wrap;
}
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  position: relative;
  display: flex;
  align-items: center;
  color: var(--ink-4);
}
.search > svg {
  position: absolute;
  left: 10px;
  pointer-events: none;
}
.search input {
  width: 268px;
  height: 34px;
  padding: 0 12px 0 32px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fafbfd;
  font-size: 13px;
}
.search input:focus {
  outline: none;
  background: #fff;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
select {
  height: 34px;
  padding: 0 30px 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: var(--ink-2);
  cursor: pointer;
}
select:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.tools {
  display: flex;
  gap: 8px;
}

.table-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 18px 12px;
}
.card-head h3 {
  font-size: 15px;
  font-weight: 600;
}
.card-head p {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.card-head strong {
  color: var(--ink-2);
  font-weight: 600;
}
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.cell-title {
  display: block;
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-1);
}
.time {
  font-size: 12px;
  color: var(--ink-4);
}
.row-actions {
  display: inline-flex;
  gap: 4px;
  justify-content: flex-end;
}
.link {
  border: none;
  background: none;
  padding: 2px 4px;
  font-size: 12px;
  color: var(--brand);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.link.danger {
  color: var(--rose);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
  border-top: 1px solid var(--line-soft);
}
.pager-info {
  font-size: 12px;
  color: var(--ink-4);
}
.pager-info strong {
  color: var(--ink-2);
}
.pager-btns {
  display: flex;
  gap: 8px;
}

@media (max-width: 900px) {
  .search input {
    width: 180px;
  }
}
</style>
