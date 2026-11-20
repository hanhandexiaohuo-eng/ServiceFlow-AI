<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AssignDrawer from '../components/AssignDrawer.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import SlaBadge from '../components/SlaBadge.vue'
import StatusTag from '../components/StatusTag.vue'
import TicketFormModal from '../components/TicketFormModal.vue'
import {
  EVENT_LABEL,
  STATUS_DESC,
  STATUS_FLOW,
  STATUS_LABEL,
  TYPE_LABEL,
  formatDateTime,
  getAllowedActions,
  getSla,
  statusStep,
  type TicketAction,
} from '../constants/ticket'
import {
  acceptTicket,
  cancelTicket,
  closeTicket,
  deleteTicket,
  getTicket,
  getTicketEvents,
  resolveTicket,
  startTicket,
  type Ticket,
  type TicketEvent,
} from '../api/tickets'
import { getCurrentUser } from '../api/request'
import { refreshTickets } from '../composables/useTicketStore'

const route = useRoute()
const router = useRouter()
const currentUser = getCurrentUser()
const isAdmin = currentUser?.role === 'ADMIN'

const ticket = ref<Ticket | null>(null)
/** 后端工单事件日志：时间线与操作记录共用同一份数据 */
const events = ref<TicketEvent[]>([])
const eventsError = ref('')
const loading = ref(true)
const errorMessage = ref('')
const formOpen = ref(false)
const assignOpen = ref(false)
const busy = ref(false)
const notice = ref('')
const actionError = ref('')

const id = computed(() => Number(route.params.id))

async function load() {
  loading.value = true
  errorMessage.value = ''
  eventsError.value = ''
  // 详情与事件并行拉取：事件失败只影响时间线，不阻塞工单详情
  const [detail, eventList] = await Promise.allSettled([
    getTicket(id.value),
    getTicketEvents(id.value),
  ])
  if (detail.status === 'fulfilled') {
    ticket.value = detail.value
  } else {
    ticket.value = null
    errorMessage.value = detail.reason instanceof Error ? detail.reason.message : '工单加载失败'
  }
  if (eventList.status === 'fulfilled') {
    events.value = eventList.value
  } else {
    events.value = []
    eventsError.value =
      eventList.reason instanceof Error ? eventList.reason.message : '操作记录加载失败'
  }
  loading.value = false
}

/** 动作完成后单独刷新事件，失败只提示，不回滚已成功的状态变更 */
async function loadEvents() {
  try {
    events.value = await getTicketEvents(id.value)
    eventsError.value = ''
  } catch (e) {
    eventsError.value = e instanceof Error ? e.message : '操作记录加载失败'
  }
}

/* ---------------- 派生信息 ---------------- */

const sla = computed(() => (ticket.value ? getSla(ticket.value) : null))

/** 后端 SLA 状态文案，与 constants 中的视觉状态分开维护 */
const SLA_STATE_TEXT: Record<string, string> = {
  ON_TRACK: '计时中',
  WARNING: '即将超时',
  OVERDUE: '已超时',
  COMPLETED: '计时结束',
}

/** 进度条宽度直接取后端 elapsedPercent，做一次边界保护 */
const slaPercent = computed(() => {
  const percent = ticket.value?.sla.elapsedPercent ?? 0
  return Math.min(100, Math.max(0, percent))
})

const flowSteps = computed(() =>
  STATUS_FLOW.map((status, index) => ({
    status,
    label: STATUS_LABEL[status] ?? status,
    desc: STATUS_DESC[status] ?? '',
    index,
  }))
)

const currentStep = computed(() => (ticket.value ? statusStep(ticket.value.status) : 0))

const actions = computed(() => (ticket.value ? getAllowedActions(ticket.value) : []))

/** 无可用动作时的说明：取消/关闭是终态，其余为权限或状态限制 */
const noActionHint = computed(() => {
  const status = ticket.value?.status
  if (status === 'CANCELLED') return '工单已取消，无可执行操作'
  if (status === 'CLOSED') return '工单已关闭，流程结束'
  return '当前账号暂无可用操作'
})

function statusLabel(status?: string | null): string {
  if (!status) return ''
  return STATUS_LABEL[status] ?? status
}

/** 时间线：直接使用后端事件日志，按 createdAt 升序，最新一条高亮 */
const timeline = computed(() =>
  events.value.map((ev, index) => {
    const fromLabel = statusLabel(ev.fromStatus)
    const toLabel = statusLabel(ev.toStatus)
    const statusText = fromLabel && toLabel ? `${fromLabel} → ${toLabel}` : toLabel ? `状态：${toLabel}` : ''
    const desc = [statusText, ev.note ?? ''].filter(Boolean).join(' · ')
    return {
      id: ev.id,
      title: EVENT_LABEL[ev.action] ?? ev.action,
      meta: `${ev.operator || '系统'} · ${formatDateTime(ev.createdAt)}`,
      desc,
      tone: index === events.value.length - 1 ? 'current' : 'done',
    }
  })
)

/* ---------------- 状态操作 ---------------- */

async function runAction(action: TicketAction) {
  const current = ticket.value
  if (!current || busy.value) return
  if (action.key === 'ASSIGN' || action.key === 'TRANSFER') {
    assignOpen.value = true
    return
  }

  busy.value = true
  notice.value = ''
  actionError.value = ''
  try {
    let updated: Ticket | null = null
    if (action.key === 'ACCEPT') {
      if (!confirm(`确认接单工单 ${current.ticketNo}？`)) return
      updated = await acceptTicket(current.id)
    } else if (action.key === 'START') {
      if (!confirm(`确认开始处理工单 ${current.ticketNo}？`)) return
      updated = await startTicket(current.id)
    } else if (action.key === 'RESOLVE') {
      const resolution = window.prompt('解决说明（可选）') ?? ''
      updated = await resolveTicket(current.id, resolution)
    } else if (action.key === 'CLOSE') {
      const note = window.prompt('关闭备注（可选）') ?? ''
      updated = await closeTicket(current.id, note)
    } else if (action.key === 'CANCEL') {
      const reason = window.prompt('取消原因（可选）') ?? ''
      updated = await cancelTicket(current.id, reason)
    }
    if (!updated) return
    ticket.value = updated
    await Promise.all([refreshTickets(true), loadEvents()])
    notice.value = `${action.label}成功，当前状态：${statusLabel(updated.status)}`
  } catch (e) {
    // 后端 403/409 的业务文案直接展示
    actionError.value = e instanceof Error ? e.message : `${action.label}失败`
  } finally {
    busy.value = false
  }
}

async function onAssigned() {
  await Promise.all([load(), refreshTickets(true)])
  notice.value = '处理人已更新'
  actionError.value = ''
}

async function onSaved() {
  await Promise.all([load(), refreshTickets(true)])
  notice.value = '工单信息已保存'
  actionError.value = ''
}

async function remove() {
  if (!ticket.value) return
  if (!confirm(`确认删除工单 ${ticket.value.ticketNo}？删除后不可恢复。`)) return
  try {
    await deleteTicket(ticket.value.id)
    await refreshTickets(true)
    router.push('/tickets')
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <!-- 面包屑 + 页面级操作 -->
    <div class="crumb-row">
      <div class="crumb">
        <button class="back-btn" @click="router.push('/tickets')">
          <AppIcon name="chevronLeft" :size="14" />返回
        </button>
        <span class="sep">/</span>
        <button class="crumb-link" @click="router.push('/tickets')">工单管理</button>
        <span class="sep">/</span>
        <span class="crumb-current">{{ ticket?.ticketNo ?? '工单详情' }}</span>
      </div>
      <div v-if="ticket" class="crumb-actions">
        <button v-if="ticket.canEdit" class="btn btn-sm" @click="formOpen = true">
          <AppIcon name="tickets" :size="13" />编辑工单
        </button>
        <button v-if="isAdmin" class="btn btn-sm danger-btn" @click="remove">删除</button>
      </div>
    </div>

    <div v-if="loading" class="card"><LoadingState text="正在加载工单…" :height="180" /></div>

    <div v-else-if="errorMessage || !ticket" class="card">
      <EmptyState icon="alert" title="工单不可用" :desc="errorMessage || '未找到该工单'">
        <button class="btn btn-sm" @click="router.push('/tickets')">返回列表</button>
      </EmptyState>
    </div>

    <template v-else>
      <!-- 工单标题区 -->
      <section class="head-card">
        <div class="head-top">
          <span class="no">{{ ticket.ticketNo }}</span>
          <StatusTag :status="ticket.status" />
          <PriorityTag :priority="ticket.priority" />
          <span class="type-chip">{{ TYPE_LABEL[ticket.type] ?? ticket.type }}</span>
          <span class="spacer" />
          <SlaBadge :ticket="ticket" show-warn-text />
        </div>
        <h1>{{ ticket.title }}</h1>
        <div class="head-meta">
          <span><AppIcon name="myTicket" :size="13" />创建人 {{ ticket.creator }}</span>
          <span><AppIcon name="clock" :size="13" />创建于 {{ formatDateTime(ticket.createdAt) }}</span>
          <span><AppIcon name="activity" :size="13" />更新于 {{ formatDateTime(ticket.updatedAt) }}</span>
          <span><AppIcon name="users" :size="13" />处理人 {{ ticket.assignee || '未分配' }}</span>
          <span v-if="ticket.firstResponseAt">
            <AppIcon name="clock" :size="13" />首次响应 {{ formatDateTime(ticket.firstResponseAt) }}
          </span>
          <span v-if="ticket.resolvedAt">
            <AppIcon name="check" :size="13" />解决于 {{ formatDateTime(ticket.resolvedAt) }}
          </span>
        </div>
      </section>

      <!-- 状态流转 + 业务操作 -->
      <section class="action-card">
        <div class="flow">
          <span class="flow-label">状态流转</span>
          <!-- 取消是终态，不参与主流程进度 -->
          <div v-if="ticket.status === 'CANCELLED'" class="cancelled-box">
            <span class="cancelled-title"><AppIcon name="close" :size="12" />工单已取消</span>
            <span v-if="ticket.cancelledAt" class="cancelled-meta">
              取消于 {{ formatDateTime(ticket.cancelledAt) }}
            </span>
          </div>
          <ol v-else class="steps">
            <li
              v-for="step in flowSteps"
              :key="step.status"
              :class="{ done: step.index < currentStep, current: step.index === currentStep }"
              :title="step.desc"
            >
              <span class="step-dot">
                <AppIcon v-if="step.index < currentStep" name="check" :size="11" />
                <template v-else>{{ step.index + 1 }}</template>
              </span>
              <span class="step-label">{{ step.label }}</span>
            </li>
          </ol>
        </div>
        <div class="action-btns">
          <button
            v-for="action in actions"
            :key="action.key"
            class="btn"
            :class="{ 'btn-primary': action.primary }"
            :disabled="busy"
            :title="action.hint"
            @click="runAction(action)"
          >
            <AppIcon :name="action.icon" :size="14" />{{ action.label }}
          </button>
          <span v-if="actions.length === 0" class="closed-hint">
            <AppIcon name="check" :size="13" />{{ noActionHint }}
          </span>
        </div>
      </section>

      <div v-if="notice || actionError" class="banner" :class="{ error: !!actionError }">
        <AppIcon :name="actionError ? 'alert' : 'check'" :size="14" />
        <span>{{ actionError || notice }}</span>
        <button class="banner-close" @click="actionError = ''; notice = ''">
          <AppIcon name="close" :size="12" />
        </button>
      </div>

      <div class="layout">
        <!-- 左栏：内容 + 时间线 + 协作区 -->
        <div class="main-col">
          <section class="card block">
            <header class="block-head">
              <h2>问题描述</h2>
            </header>
            <p v-if="ticket.description" class="desc">{{ ticket.description }}</p>
            <p v-else class="desc empty">该工单未填写描述</p>
          </section>

          <section class="card block">
            <header class="block-head">
              <h2>处理时间线</h2>
              <span class="head-note">来自后端工单事件</span>
            </header>
            <ul v-if="timeline.length" class="timeline">
              <li v-for="item in timeline" :key="item.id" :class="item.tone">
                <span class="t-dot" />
                <div class="t-body">
                  <p class="t-title">{{ item.title }}</p>
                  <p class="t-meta">{{ item.meta }}</p>
                  <p v-if="item.desc" class="t-desc">{{ item.desc }}</p>
                </div>
              </li>
            </ul>
            <p v-else class="list-empty">{{ eventsError || '暂无操作记录' }}</p>
          </section>

          <section class="card block">
            <header class="block-head">
              <h2>评论</h2>
              <span class="tag-pending">接口待接入</span>
            </header>
            <div class="comment-box">
              <span class="avatar">{{ (currentUser?.name ?? 'U').slice(0, 1) }}</span>
              <textarea
                rows="2"
                disabled
                placeholder="评论功能需要后端 /api/tickets/{id}/comments 接口，当前未接入，暂不可提交。"
              />
            </div>
            <div class="comment-foot">
              <span class="muted">后端的工单模块目前没有评论实体与接口，这里保留容器待接入。</span>
              <button class="btn btn-sm" disabled>提交评论</button>
            </div>
          </section>

          <div class="two-col">
            <section class="card block">
              <header class="block-head">
                <h2>操作记录</h2>
                <span class="head-note">{{ events.length }} 条</span>
              </header>
              <ul v-if="events.length" class="log-list">
                <li v-for="(ev, index) in events" :key="ev.id">
                  <span class="log-dot" :class="{ active: index === events.length - 1 }" />
                  <div>
                    <p class="log-title">{{ EVENT_LABEL[ev.action] ?? ev.action }}</p>
                    <p class="log-meta">{{ ev.operator || '系统' }} · {{ formatDateTime(ev.createdAt) }}</p>
                    <p v-if="ev.note" class="log-note">{{ ev.note }}</p>
                  </div>
                </li>
              </ul>
              <p v-else class="list-empty">{{ eventsError || '暂无操作记录' }}</p>
            </section>

            <section class="card block">
              <header class="block-head">
                <h2>附件</h2>
                <span class="tag-pending">待接入</span>
              </header>
              <div class="attach-empty">
                <AppIcon name="layers" :size="20" />
                <p>附件上传与下载需要后端文件服务，当前未接入</p>
              </div>
            </section>
          </div>
        </div>

        <!-- 右栏：属性 + SLA -->
        <aside class="side-col">
          <section class="card block">
            <header class="block-head">
              <h2>工单信息</h2>
            </header>
            <dl class="info-list">
              <div>
                <dt>当前状态</dt>
                <dd><StatusTag :status="ticket.status" /></dd>
              </div>
              <div>
                <dt>优先级</dt>
                <dd><PriorityTag :priority="ticket.priority" /></dd>
              </div>
              <div>
                <dt>工单类型</dt>
                <dd>{{ TYPE_LABEL[ticket.type] ?? ticket.type }}</dd>
              </div>
              <div>
                <dt>创建人</dt>
                <dd>{{ ticket.creator }}</dd>
              </div>
              <div>
                <dt>当前处理人</dt>
                <dd :class="{ muted: !ticket.assignee }">{{ ticket.assignee || '未分配' }}</dd>
              </div>
              <div>
                <dt>创建时间</dt>
                <dd class="num">{{ formatDateTime(ticket.createdAt) }}</dd>
              </div>
              <div>
                <dt>更新时间</dt>
                <dd class="num">{{ formatDateTime(ticket.updatedAt) }}</dd>
              </div>
              <div>
                <dt>SLA 剩余</dt>
                <dd><SlaBadge :ticket="ticket" show-warn-text /></dd>
              </div>
            </dl>
          </section>

          <section v-if="sla" class="card block sla-card" :class="`s-${sla.state}`">
            <header class="block-head">
              <h2>SLA 计时</h2>
              <span class="head-note">数据来自后端 SLA 服务</span>
            </header>
            <p class="sla-big">{{ sla.short }}</p>
            <div class="sla-bar">
              <i :style="{ width: `${slaPercent}%` }" />
            </div>
            <dl class="sla-rows">
              <div>
                <dt>SLA 策略</dt>
                <dd>{{ ticket.sla.policyLabel }}</dd>
              </div>
              <div>
                <dt>响应时限</dt>
                <dd>{{ ticket.sla.hours }} 小时</dd>
              </div>
              <div>
                <dt>截止时间</dt>
                <dd class="num">{{ formatDateTime(ticket.sla.dueAt) }}</dd>
              </div>
              <div>
                <dt>计时状态</dt>
                <dd>{{ SLA_STATE_TEXT[ticket.sla.state] ?? ticket.sla.state }}</dd>
              </div>
              <div>
                <dt>首次响应</dt>
                <dd class="num">{{ formatDateTime(ticket.firstResponseAt) }}</dd>
              </div>
              <div v-if="ticket.sla.completedAt">
                <dt>完成时间</dt>
                <dd class="num">{{ formatDateTime(ticket.sla.completedAt) }}</dd>
              </div>
            </dl>
          </section>

          <section class="card block">
            <header class="block-head">
              <h2>流转规则</h2>
            </header>
            <p class="rule-text">
              状态只能通过 <code>/assign</code>、<code>/transfer</code>、<code>/accept</code>、
              <code>/start</code>、<code>/resolve</code>、<code>/close</code>、<code>/cancel</code>
              变更，服务端校验当前状态与操作权限，并发冲突返回 <code>409</code>。
            </p>
          </section>
        </aside>
      </div>
    </template>

    <TicketFormModal :open="formOpen" :ticket="ticket" @close="formOpen = false" @saved="onSaved" />
    <AssignDrawer :open="assignOpen" :ticket="ticket" @close="assignOpen = false" @saved="onAssigned" />
  </div>
</template>

<style scoped>
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

/* 面包屑 */
.crumb-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-4);
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: none;
  background: none;
  color: var(--ink-3);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.back-btn:hover {
  color: var(--brand);
}
.sep {
  color: #cbd5e1;
}
.crumb-link {
  border: none;
  background: none;
  color: var(--ink-3);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.crumb-link:hover {
  color: var(--brand);
}
.crumb-current {
  font-family: Consolas, monospace;
  color: var(--ink-2);
}
.crumb-actions {
  display: flex;
  gap: 8px;
}
.danger-btn {
  color: var(--rose);
  border-color: #fecaca;
}
.danger-btn:hover {
  background: #fef2f2;
}

/* 标题区 */
.head-card {
  padding: 18px 22px 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.head-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.head-top .spacer {
  flex: 1;
}
.no {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: var(--ink-4);
}
.type-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 6px;
  background: var(--line-soft);
  color: var(--ink-3);
  font-size: 12px;
}
.head-card h1 {
  margin-top: 10px;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 1.4;
}
.head-meta {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 10px;
  font-size: 12px;
  color: var(--ink-4);
}
.head-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 状态流转操作区 */
.action-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 18px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  flex-wrap: wrap;
}
.flow {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.flow-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-4);
  white-space: nowrap;
}
.steps {
  display: flex;
  align-items: center;
  list-style: none;
  gap: 0;
}
.steps li {
  display: flex;
  align-items: center;
  gap: 6px;
  position: relative;
  padding-right: 42px;
  color: var(--ink-4);
}
.steps li:last-child {
  padding-right: 0;
}
.steps li:not(:last-child)::after {
  content: '';
  position: absolute;
  right: 8px;
  top: 50%;
  width: 26px;
  height: 1px;
  background: var(--line);
}
.steps li.done:not(:last-child)::after {
  background: #a5b4fc;
}
.step-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid var(--line);
  background: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--ink-4);
  flex: none;
}
.steps li.done .step-dot {
  background: #eef2ff;
  border-color: #c7d2fe;
  color: var(--brand);
}
.steps li.current .step-dot {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.step-label {
  font-size: 12px;
  white-space: nowrap;
}
.steps li.current .step-label {
  color: var(--ink-1);
  font-weight: 600;
}
.steps li.done .step-label {
  color: var(--ink-2);
}

/* 取消终态提示：替代流程进度条 */
.cancelled-box {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 5px 12px;
  border-radius: 8px;
  border: 1px solid #fecaca;
  background: #fef2f2;
}
.cancelled-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #b91c1c;
}
.cancelled-meta {
  font-size: 11px;
  color: #dc2626;
}

.action-btns {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.closed-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ink-4);
}

/* 操作结果条 */
.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: 8px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #047857;
  font-size: 12px;
}
.banner.error {
  background: #fef2f2;
  border-color: #fecaca;
  color: #b91c1c;
}
.banner-close {
  margin-left: auto;
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  opacity: 0.7;
  display: inline-flex;
  padding: 2px;
}
.banner-close:hover {
  opacity: 1;
}

/* 左右分栏 */
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}
.main-col,
.side-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.side-col {
  position: sticky;
  top: 76px;
}
.block {
  padding: 16px 18px;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.block-head h2 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
  letter-spacing: 0.01em;
}
.head-note {
  font-size: 11px;
  color: var(--ink-4);
}

.desc {
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.8;
  white-space: pre-wrap;
  background: #fafbfd;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  padding: 12px 14px;
}
.desc.empty {
  color: var(--ink-4);
}

/* 时间线 */
.timeline {
  list-style: none;
  padding-left: 2px;
}
.timeline li {
  display: flex;
  gap: 12px;
  position: relative;
  padding-bottom: 18px;
}
.timeline li:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 14px;
  bottom: 0;
  width: 1px;
  background: var(--line);
}
.t-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #cbd5e1;
  flex: none;
  margin-top: 3px;
  z-index: 1;
}
.timeline li.current .t-dot {
  background: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.timeline li.done .t-dot {
  background: var(--emerald);
  box-shadow: 0 0 0 3px #ecfdf5;
}
.t-body {
  min-width: 0;
}
.t-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-1);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.t-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.t-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
  overflow-wrap: anywhere;
}

/* 空态 */
.list-empty {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-4);
  background: #fbfcfe;
  border: 1px dashed var(--line);
  border-radius: 8px;
  padding: 12px;
}

/* 评论 */
.comment-box {
  display: flex;
  gap: 10px;
}
.avatar {
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 50%;
  background: var(--brand-weak);
  color: var(--brand);
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.comment-box textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  background: #fafbfd;
  color: var(--ink-4);
  min-width: 0;
}
.comment-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
}
.comment-foot .muted {
  font-size: 11px;
}

/* 操作记录 / 附件 */
.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.log-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 320px;
  overflow-y: auto;
}
.log-list li {
  display: flex;
  gap: 10px;
}
.log-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #cbd5e1;
  margin-top: 6px;
  flex: none;
}
.log-dot.active {
  background: var(--brand);
}
.log-title {
  font-size: 12px;
  color: var(--ink-1);
}
.log-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.log-note {
  margin-top: 3px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-3);
  overflow-wrap: anywhere;
}
.attach-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 12px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  color: var(--ink-4);
  text-align: center;
}
.attach-empty p {
  font-size: 11px;
  line-height: 1.6;
  max-width: 220px;
}

/* 属性表 */
.info-list {
  display: flex;
  flex-direction: column;
}
.info-list > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--line-soft);
}
.info-list > div:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.info-list dt {
  font-size: 12px;
  color: var(--ink-4);
  flex: none;
}
.info-list dd {
  font-size: 12px;
  color: var(--ink-1);
  text-align: right;
  min-width: 0;
  overflow-wrap: anywhere;
}
.info-list dd.muted {
  color: var(--amber);
}

/* SLA 卡 */
.sla-big {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--ink-1);
  font-variant-numeric: tabular-nums;
}
.sla-bar {
  margin: 10px 0 12px;
  height: 6px;
  border-radius: 999px;
  background: var(--line-soft);
  overflow: hidden;
}
.sla-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--brand);
  transition: width 0.3s ease;
}
.s-warn .sla-big {
  color: #b45309;
}
.s-warn .sla-bar i {
  background: var(--amber);
}
.s-overdue .sla-big {
  color: var(--rose);
}
.s-overdue .sla-bar i {
  background: var(--rose);
}
.s-done .sla-big {
  font-size: 16px;
  color: var(--ink-3);
}
.s-done .sla-bar i {
  background: var(--emerald);
}
.sla-rows {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.sla-rows > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.sla-rows dt {
  color: var(--ink-4);
}
.sla-rows dd {
  color: var(--ink-2);
  text-align: right;
}

.rule-text {
  font-size: 12px;
  color: var(--ink-2);
  line-height: 1.75;
}
.rule-text code {
  font-family: Consolas, monospace;
  font-size: 11px;
  background: var(--line-soft);
  padding: 1px 4px;
  border-radius: 4px;
}

.tag-pending {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #b45309;
  font-size: 10px;
  font-weight: 500;
  white-space: nowrap;
}

@media (max-width: 1360px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 300px;
  }
}
@media (max-width: 1100px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .side-col {
    position: static;
  }
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
  .steps li {
    padding-right: 30px;
  }
  .steps li:not(:last-child)::after {
    width: 16px;
  }
}
</style>
