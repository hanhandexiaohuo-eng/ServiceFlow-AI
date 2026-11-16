<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '../components/EmptyState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import {
  STATUS_COLOR,
  STATUS_LABEL,
  STATUS_OPTIONS,
  TYPE_LABEL,
  formatDateTime,
} from '../constants/ticket'
import type { Ticket } from '../api/tickets'
import { isLoading, refreshTickets, tickets } from '../composables/useTicketStore'

const router = useRouter()

function byStatus(status: string): Ticket[] {
  return tickets.value.filter((t) => t.status === status)
}

const total = computed(() => tickets.value.length)

function open(t: Ticket) {
  router.push(`/tickets/${t.id}`)
}

onMounted(() => refreshTickets())
</script>

<template>
  <div class="page">
    <section class="board-head">
      <div>
        <h3>工单看板</h3>
        <p>按当前状态分列展示，共 <strong class="num">{{ total }}</strong> 条工单；点击卡片查看详情。</p>
      </div>
      <button class="btn btn-sm" @click="refreshTickets(true)">刷新</button>
    </section>

    <div class="board" :class="{ loading: isLoading && total === 0 }">
      <section v-for="status in STATUS_OPTIONS" :key="status" class="column">
        <header class="col-head">
          <span class="bar" :style="{ background: STATUS_COLOR[status] }" />
          <strong>{{ STATUS_LABEL[status] }}</strong>
          <span class="count num">{{ byStatus(status).length }}</span>
        </header>

        <div class="col-body">
          <article
            v-for="t in byStatus(status)"
            :key="t.id"
            class="card"
            @click="open(t)"
          >
            <div class="card-top">
              <span class="no">{{ t.ticketNo }}</span>
              <PriorityTag :priority="t.priority" />
            </div>
            <h4>{{ t.title }}</h4>
            <p class="meta">{{ TYPE_LABEL[t.type] ?? t.type }} · {{ t.creator }}</p>
            <footer class="card-foot">
              <span>{{ formatDateTime(t.createdAt) }}</span>
              <span class="assignee" :class="{ none: !t.assignee }">{{ t.assignee || '未指派' }}</span>
            </footer>
          </article>

          <EmptyState
            v-if="byStatus(status).length === 0"
            compact
            icon="inbox"
            title="暂无工单"
            desc=""
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.board-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.board-head h3 {
  font-size: 15px;
  font-weight: 600;
}
.board-head p {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.board-head strong {
  color: var(--ink-2);
}

.board {
  display: grid;
  grid-template-columns: repeat(4, minmax(240px, 1fr));
  gap: 14px;
  align-items: start;
  overflow-x: auto;
  padding-bottom: 4px;
}
.column {
  background: #fbfcfe;
  border: 1px solid var(--line);
  border-radius: 12px;
  min-width: 0;
}
.col-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 12px;
  border-bottom: 1px solid var(--line);
}
.bar {
  width: 3px;
  height: 14px;
  border-radius: 2px;
}
.col-head strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.count {
  margin-left: auto;
  min-width: 22px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #eef2f7;
  color: var(--ink-3);
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.col-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  min-height: 120px;
}
.card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 11px 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.1);
  transform: translateY(-1px);
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.no {
  font-family: Consolas, monospace;
  font-size: 11px;
  color: var(--ink-4);
}
.card h4 {
  margin-top: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.meta {
  margin-top: 6px;
  font-size: 11px;
  color: var(--ink-4);
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--line-soft);
  font-size: 11px;
  color: var(--ink-4);
}
.assignee {
  color: var(--ink-3);
}
.assignee.none {
  color: var(--amber);
}

.board.loading {
  opacity: 0.6;
}

@media (max-width: 1200px) {
  .board {
    grid-template-columns: repeat(4, minmax(260px, 1fr));
  }
}
</style>
