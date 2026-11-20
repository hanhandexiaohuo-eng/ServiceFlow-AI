<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DataTable from '../components/DataTable.vue'
import PriorityTag from '../components/PriorityTag.vue'
import StatusTag from '../components/StatusTag.vue'
import TicketFormModal from '../components/TicketFormModal.vue'
import { formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import { isLoading, refreshTickets, tickets } from '../composables/useTicketStore'
import type { TableColumn } from '../types/ui'

const router = useRouter()
const user = getCurrentUser()
const isAdmin = user?.role === 'ADMIN'
const modalOpen = ref(false)

/** 我创建的 + 指派给我的（管理员查看全部中与自身相关的） */
const mine = computed(() => {
  const name = user?.name ?? ''
  return tickets.value.filter((t) => t.creator === name || t.assignee === name)
})
const stats = computed(() => ({
  total: mine.value.length,
  pending: mine.value.filter((t) => t.status === 'PENDING').length,
  running: mine.value.filter(
    (t) => t.status === 'ASSIGNED' || t.status === 'ACCEPTED' || t.status === 'PROCESSING'
  ).length,
  done: mine.value.filter((t) => t.status === 'RESOLVED' || t.status === 'CLOSED').length,
}))

const columns: TableColumn[] = [
  { key: 'ticketNo', label: '工单编号', width: '150px', nowrap: true },
  { key: 'title', label: '标题' },
  { key: 'priority', label: '优先级', width: '80px' },
  { key: 'status', label: '状态', width: '96px' },
  { key: 'creator', label: '创建人', width: '100px', nowrap: true },
  { key: 'createdAt', label: '创建时间', width: '140px', nowrap: true },
  { key: 'actions', label: '操作', width: '70px', align: 'right' },
]

async function onSaved() {
  await refreshTickets(true)
}

onMounted(() => refreshTickets())
</script>

<template>
  <div class="page">
    <section class="stats">
      <div class="stat">
        <span class="label">与我相关</span>
        <span class="value num">{{ stats.total }}</span>
      </div>
      <div class="stat">
        <span class="label">待分配</span>
        <span class="value num">{{ stats.pending }}</span>
      </div>
      <div class="stat">
        <span class="label">进行中</span>
        <span class="value num">{{ stats.running }}</span>
      </div>
      <div class="stat">
        <span class="label">已完成</span>
        <span class="value num">{{ stats.done }}</span>
      </div>
    </section>

    <section class="card">
      <header class="head">
        <div>
          <h3>我的工单</h3>
          <p>创建人或处理人为「{{ user?.name }}」的工单{{ isAdmin ? '（管理员视角）' : '' }}</p>
        </div>
        <button class="btn btn-primary btn-sm" @click="modalOpen = true">新建工单</button>
      </header>

      <DataTable
        :columns="columns"
        :rows="mine"
        :loading="isLoading && tickets.length === 0"
        clickable
        empty-text="暂无与你相关的工单"
        @row-click="(row) => router.push(`/tickets/${row.id}`)"
      >
        <template #cell-ticketNo="{ row }"><span class="mono">{{ row.ticketNo }}</span></template>
        <template #cell-title="{ row }"><span class="cell-title">{{ row.title }}</span></template>
        <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
        <template #cell-createdAt="{ row }">
          <span class="time">{{ formatDateTime(row.createdAt) }}</span>
        </template>
        <template #cell-actions="{ row }">
          <span class="link" @click.stop="router.push(`/tickets/${row.id}`)">查看</span>
        </template>
      </DataTable>
    </section>

    <TicketFormModal :open="modalOpen" :ticket="null" @close="modalOpen = false" @saved="onSaved" />
  </div>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.stat {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow-card);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.label {
  font-size: 11px;
  letter-spacing: 0.05em;
  color: var(--ink-4);
}
.value {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-1);
}
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 12px;
}
.head h3 {
  font-size: 15px;
  font-weight: 600;
}
.head p {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.mono {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.cell-title {
  display: block;
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-1);
}
.time {
  font-size: 12px;
  color: var(--ink-4);
}
.link {
  font-size: 12px;
  color: var(--brand);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}

@media (max-width: 900px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
