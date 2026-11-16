<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import StatusTag from '../components/StatusTag.vue'
import TicketFormModal from '../components/TicketFormModal.vue'
import { deleteTicket, getTicket, type Ticket } from '../api/tickets'
import { getCurrentUser } from '../api/request'
import { TYPE_LABEL, formatDateTime } from '../constants/ticket'
import { refreshTickets } from '../composables/useTicketStore'

const route = useRoute()
const router = useRouter()
const isAdmin = getCurrentUser()?.role === 'ADMIN'

const ticket = ref<Ticket | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const modalOpen = ref(false)

const id = computed(() => Number(route.params.id))

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    ticket.value = await getTicket(id.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '工单加载失败'
  } finally {
    loading.value = false
  }
}

async function onSaved() {
  await Promise.all([load(), refreshTickets(true)])
}

async function remove() {
  if (!ticket.value) return
  if (!confirm(`确认删除工单 ${ticket.value.ticketNo}？删除后不可恢复。`)) return
  try {
    await deleteTicket(ticket.value.id)
    await refreshTickets(true)
    router.push('/tickets')
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="back">
      <button class="back-btn" @click="router.push('/tickets')">
        <AppIcon name="chevronLeft" :size="14" />返回工单列表
      </button>
    </div>

    <div v-if="loading" class="card"><LoadingState text="正在加载工单…" :height="180" /></div>

    <div v-else-if="errorMessage || !ticket" class="card">
      <EmptyState icon="alert" title="工单不可用" :desc="errorMessage || '未找到该工单'">
        <button class="btn btn-sm" @click="router.push('/tickets')">返回列表</button>
      </EmptyState>
    </div>

    <template v-else>
      <section class="card hero">
        <div class="hero-main">
          <div class="no">{{ ticket.ticketNo }}</div>
          <h1>{{ ticket.title }}</h1>
          <div class="tags">
            <StatusTag :status="ticket.status" />
            <PriorityTag :priority="ticket.priority" />
            <span class="type">{{ TYPE_LABEL[ticket.type] ?? ticket.type }}</span>
          </div>
        </div>
        <div class="hero-actions">
          <button class="btn" @click="modalOpen = true">
            <AppIcon name="tickets" :size="14" />编辑工单
          </button>
          <button v-if="isAdmin" class="btn danger" @click="remove">删除</button>
        </div>
      </section>

      <div class="columns">
        <section class="card block">
          <h3>问题描述</h3>
          <p v-if="ticket.description" class="desc">{{ ticket.description }}</p>
          <p v-else class="desc empty">该工单未填写描述</p>

          <h3 class="mt">工单属性</h3>
          <dl class="props">
            <div><dt>工单类型</dt><dd>{{ TYPE_LABEL[ticket.type] ?? ticket.type }}</dd></div>
            <div><dt>优先级</dt><dd><PriorityTag :priority="ticket.priority" /></dd></div>
            <div><dt>当前状态</dt><dd><StatusTag :status="ticket.status" /></dd></div>
            <div><dt>处理人</dt><dd>{{ ticket.assignee || '未指派' }}</dd></div>
          </dl>
        </section>

        <aside class="card block side">
          <h3>流转信息</h3>
          <ul class="timeline">
            <li>
              <span class="dot" />
              <div>
                <p class="t-title">创建工单</p>
                <p class="t-meta">{{ ticket.creator }} · {{ formatDateTime(ticket.createdAt) }}</p>
              </div>
            </li>
            <li>
              <span class="dot active" />
              <div>
                <p class="t-title">最近更新</p>
                <p class="t-meta">{{ formatDateTime(ticket.updatedAt) }}</p>
              </div>
            </li>
          </ul>
          <p class="tip">
            状态流转记录需后端提供操作日志接口，当前仅展示创建与更新时间。
          </p>
        </aside>
      </div>
    </template>

    <TicketFormModal
      :open="modalOpen"
      :ticket="ticket"
      @close="modalOpen = false"
      @saved="onSaved"
    />
  </div>
</template>

<style scoped>
.back {
  display: flex;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  color: var(--ink-3);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 0;
}
.back-btn:hover {
  color: var(--brand);
}
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 22px;
}
.no {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: var(--ink-4);
}
.hero h1 {
  margin-top: 6px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.type {
  font-size: 12px;
  color: var(--ink-3);
  background: var(--line-soft);
  padding: 3px 9px;
  border-radius: 6px;
}
.hero-actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.btn.danger {
  color: var(--rose);
  border-color: #fecaca;
}
.btn.danger:hover {
  background: #fef2f2;
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}
.block {
  padding: 18px 20px;
}
.block h3 {
  font-size: 14px;
  font-weight: 600;
}
.block h3.mt {
  margin-top: 22px;
}
.desc {
  margin-top: 10px;
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.75;
  white-space: pre-wrap;
  background: #fafbfd;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  padding: 12px 14px;
}
.desc.empty {
  color: var(--ink-4);
}
.props {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.props div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  background: #fcfdfe;
}
.props dt {
  font-size: 11px;
  color: var(--ink-4);
}
.props dd {
  font-size: 13px;
  color: var(--ink-1);
}

.timeline {
  list-style: none;
  margin-top: 14px;
  position: relative;
  padding-left: 6px;
}
.timeline li {
  display: flex;
  gap: 10px;
  padding-bottom: 16px;
  position: relative;
}
.timeline li:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: 0;
  width: 1px;
  background: var(--line);
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #cbd5e1;
  flex: none;
  margin-top: 4px;
}
.dot.active {
  background: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.t-title {
  font-size: 13px;
  color: var(--ink-1);
}
.t-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.tip {
  margin-top: 4px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
  font-size: 11px;
  line-height: 1.6;
}

@media (max-width: 1100px) {
  .columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
