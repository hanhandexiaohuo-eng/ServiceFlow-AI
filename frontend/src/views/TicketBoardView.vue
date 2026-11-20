<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AssignDrawer from '../components/AssignDrawer.vue'
import EmptyState from '../components/EmptyState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import SlaBadge from '../components/SlaBadge.vue'
import {
  BOARD_COLUMNS,
  PRIORITY_LABEL,
  PRIORITY_OPTIONS,
  PRIORITY_WEIGHT,
  STATUS_LABEL,
  TYPE_LABEL,
  formatDateTime,
  getSla,
  inBoardColumn,
} from '../constants/ticket'
import {
  acceptTicket,
  cancelTicket,
  closeTicket,
  resolveTicket,
  startTicket,
  type Ticket,
  type TicketActionKey,
} from '../api/tickets'
import { isLoading, refreshTickets, tickets } from '../composables/useTicketStore'

const router = useRouter()

/* ---------------- 筛选 ---------------- */

const keyword = ref('')
const priority = ref('')
const assignee = ref('')
const slaFilter = ref('')
const focus = ref('all')

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const who = assignee.value.trim().toLowerCase()
  return tickets.value.filter((t) => {
    if (priority.value && t.priority !== priority.value) return false
    if (who && !(t.assignee ?? '').toLowerCase().includes(who)) return false
    if (slaFilter.value && getSla(t).state !== slaFilter.value) return false
    if (q) {
      const hit =
        t.ticketNo.toLowerCase().includes(q) ||
        t.title.toLowerCase().includes(q) ||
        (t.assignee ?? '').toLowerCase().includes(q) ||
        (t.creator ?? '').toLowerCase().includes(q)
      if (!hit) return false
    }
    return true
  })
})

const hasFilter = computed(
  () => !!keyword.value || !!priority.value || !!assignee.value || !!slaFilter.value
)

function resetFilters() {
  keyword.value = ''
  priority.value = ''
  assignee.value = ''
  slaFilter.value = ''
  focus.value = 'all'
}

const visibleColumns = computed(() =>
  focus.value === 'all'
    ? BOARD_COLUMNS
    : BOARD_COLUMNS.filter((col) => col.key === focus.value)
)

/** 列内排序：SLA 最紧急的在前，已结束的排后；同剩余时间按优先级降序 */
function columnTickets(key: string): Ticket[] {
  return filtered.value
    .filter((t) => inBoardColumn(t, key))
    .sort((a, b) => {
      const sa = getSla(a)
      const sb = getSla(b)
      const va = sa.state === 'done' ? Number.MAX_SAFE_INTEGER : sa.remainingMs
      const vb = sb.state === 'done' ? Number.MAX_SAFE_INTEGER : sb.remainingMs
      if (va !== vb) return va - vb
      return (PRIORITY_WEIGHT[b.priority] ?? 0) - (PRIORITY_WEIGHT[a.priority] ?? 0)
    })
}

function slaClass(t: Ticket): string {
  const state = getSla(t).state
  if (state === 'overdue') return 'is-overdue'
  if (state === 'warn') return 'is-warn'
  if (state === 'done') return 'is-done'
  return ''
}

/* ---------------- 卡片展示 ---------------- */

const AVATAR_COLORS = ['#4f46e5', '#0891b2', '#d97706', '#059669', '#7c3aed', '#db2777']

function avatarStyle(name?: string | null): Record<string, string> {
  const value = name ?? ''
  let hash = 0
  for (let i = 0; i < value.length; i += 1) hash = (hash + value.charCodeAt(i)) % AVATAR_COLORS.length
  const color = AVATAR_COLORS[hash] ?? AVATAR_COLORS[0]
  return { background: `${color}1a`, color }
}

function initial(name?: string | null): string {
  return (name ?? '?').slice(0, 1)
}

/** 卡片只展示到分钟，压缩宽度 */
function shortTime(input: string): string {
  return formatDateTime(input).slice(5)
}

function open(t: Ticket) {
  router.push(`/tickets/${t.id}`)
}

/* ---------------- 拖拽状态流转 ---------------- */

const draggingId = ref<number | null>(null)
const dragOverKey = ref('')
const busyId = ref<number | null>(null)
const assignOpen = ref(false)
const assignTarget = ref<Ticket | null>(null)
const hint = ref<{ type: 'ok' | 'error'; text: string } | null>(null)

function showHint(type: 'ok' | 'error', text: string) {
  hint.value = { type, text }
}

function onDragStart(t: Ticket, event: DragEvent) {
  // 动作提交过程中不再接受新的拖拽，避免重复流转
  if (busyId.value !== null) return
  draggingId.value = t.id
  hint.value = null
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(t.id))
  }
}

function onDragEnd() {
  draggingId.value = null
  dragOverKey.value = ''
}

/** 拖到列内子元素同样会触发 dragleave，这里只处理真正离开整列的情况 */
function onDragLeave(key: string, event: DragEvent) {
  const current = event.currentTarget as HTMLElement | null
  const next = event.relatedTarget as Node | null
  if (current && next && current.contains(next)) return
  if (dragOverKey.value === key) dragOverKey.value = ''
}

/** 动作成功后按后端最新数据刷新看板，失败展示后端可读错误 */
async function runAction(ticket: Ticket, label: string, action: () => Promise<Ticket>) {
  busyId.value = ticket.id
  try {
    const updated = await action()
    await refreshTickets(true)
    const statusText = STATUS_LABEL[updated.status] ?? updated.status
    showHint('ok', `${ticket.ticketNo} ${label}成功，当前状态：${statusText}`)
  } catch (e) {
    showHint('error', e instanceof Error ? e.message : `${ticket.ticketNo} ${label}失败`)
  } finally {
    busyId.value = null
  }
}

const REJECT_TEXT = '当前状态或你的权限不允许该操作'

/** 拖拽落列：目标状态决定要提交的动作，动作是否合法完全以后端 allowedActions 为准 */
async function handleDrop(target: string) {
  const id = draggingId.value
  draggingId.value = null
  dragOverKey.value = ''
  if (id === null) return

  const ticket = tickets.value.find((t) => t.id === id)
  if (!ticket) return
  // 落回原列直接忽略
  if (ticket.status === target) return

  const allowed = (key: TicketActionKey) => ticket.allowedActions.includes(key)
  const reject = () => showHint('error', `${ticket.ticketNo} ${REJECT_TEXT}`)

  switch (target) {
    case 'ASSIGNED':
      // 分配与转交都需要选择处理人，统一交给抽屉提交
      if (allowed('ASSIGN') || allowed('TRANSFER')) {
        assignTarget.value = ticket
        assignOpen.value = true
      } else {
        reject()
      }
      return
    case 'ACCEPTED':
      if (!allowed('ACCEPT')) {
        reject()
        return
      }
      if (!confirm(`确认接单工单 ${ticket.ticketNo}？`)) return
      await runAction(ticket, '接单', () => acceptTicket(ticket.id))
      return
    case 'PROCESSING':
      if (!allowed('START')) {
        reject()
        return
      }
      if (!confirm(`确认开始处理工单 ${ticket.ticketNo}？`)) return
      await runAction(ticket, '开始处理', () => startTicket(ticket.id))
      return
    case 'RESOLVED': {
      if (!allowed('RESOLVE')) {
        reject()
        return
      }
      const resolution = prompt('解决说明（可选）')
      if (resolution === null) return
      await runAction(ticket, '解决', () => resolveTicket(ticket.id, resolution))
      return
    }
    case 'CLOSED': {
      if (!allowed('CLOSE')) {
        reject()
        return
      }
      const note = prompt('关闭备注（可选）')
      if (note === null) return
      await runAction(ticket, '关闭', () => closeTicket(ticket.id, note))
      return
    }
    case 'CANCELLED': {
      if (!allowed('CANCEL')) {
        reject()
        return
      }
      const reason = prompt('取消原因（可选）')
      if (reason === null) return
      await runAction(ticket, '取消', () => cancelTicket(ticket.id, reason))
      return
    }
    case 'PENDING':
      showHint('error', '无法直接回到待分配，请使用转交')
      return
    default:
      return
  }
}

/** 分配/转交抽屉保存后强制刷新看板 */
function onAssignSaved() {
  const no = assignTarget.value?.ticketNo ?? '工单'
  void refreshTickets(true)
  showHint('ok', `${no} 处理人已更新`)
}

onMounted(() => refreshTickets())
</script>

<template>
  <div class="page">
    <section class="board-head">
      <div>
        <h1>工单看板</h1>
        <p>
          7 个业务状态分列 · 当前展示 <b class="num">{{ filtered.length }}</b> / 全量
          <b class="num">{{ tickets.length }}</b> 条 · 点击卡片查看详情
        </p>
      </div>
      <div class="head-right">
        <span v-if="isLoading" class="sync">同步中…</span>
        <button class="btn" :disabled="isLoading" @click="refreshTickets(true)">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
      </div>
    </section>

    <!-- 筛选 + 状态视图 -->
    <section class="toolbar">
      <div class="filters">
        <div class="search">
          <AppIcon name="search" :size="14" />
          <input v-model="keyword" placeholder="搜索编号、标题、处理人" />
        </div>
        <select v-model="priority">
          <option value="">全部优先级</option>
          <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
        </select>
        <select v-model="slaFilter">
          <option value="">全部 SLA</option>
          <option value="overdue">已超时</option>
          <option value="warn">即将超时</option>
          <option value="ok">正常</option>
          <option value="done">已结束</option>
        </select>
        <input v-model="assignee" class="who" placeholder="处理人" />
        <button v-if="hasFilter" class="btn btn-sm" @click="resetFilters">重置</button>
      </div>
      <div class="view-tabs">
        <button :class="{ active: focus === 'all' }" @click="focus = 'all'">全部状态</button>
        <button
          v-for="col in BOARD_COLUMNS"
          :key="col.key"
          :class="{ active: focus === col.key }"
          @click="focus = col.key"
        >
          {{ col.label }}
        </button>
      </div>
    </section>

    <div class="notice">
      <AppIcon name="route" :size="14" />
      <p>拖拽卡片可发起状态流转；仅当前账号与工单状态允许的动作会成功，越权或非法流转由后端拒绝。</p>
    </div>

    <!-- 拖拽结果提示：成功与失败都在页面内展示 -->
    <div v-if="hint" class="banner" :class="{ error: hint.type === 'error' }">
      <AppIcon :name="hint.type === 'ok' ? 'check' : 'alert'" :size="14" />
      <span>{{ hint.text }}</span>
      <button class="banner-close" title="关闭" @click="hint = null">
        <AppIcon name="close" :size="12" />
      </button>
    </div>

    <!-- 看板 -->
    <div class="board" :class="{ single: focus !== 'all' }">
      <section
        v-for="col in visibleColumns"
        :key="col.key"
        class="column"
        :class="{ over: dragOverKey === col.key }"
        @dragover.prevent="dragOverKey = col.key"
        @dragleave="onDragLeave(col.key, $event)"
        @drop.prevent="handleDrop(col.key)"
      >
        <header class="col-head">
          <span class="bar" :style="{ background: col.color }" />
          <strong>{{ col.label }}</strong>
          <span class="col-count num">{{ columnTickets(col.key).length }}</span>
          <span class="col-desc" :title="col.desc">{{ col.desc }}</span>
        </header>

        <div class="col-body">
          <article
            v-for="t in columnTickets(col.key)"
            :key="t.id"
            class="t-card"
            :class="[slaClass(t), { dragging: draggingId === t.id, busy: busyId === t.id }]"
            draggable="true"
            @click="open(t)"
            @dragstart="onDragStart(t, $event)"
            @dragend="onDragEnd"
          >
            <div class="card-top">
              <span class="no">{{ t.ticketNo }}</span>
              <PriorityTag :priority="t.priority" />
            </div>
            <h4 :title="t.title">{{ t.title }}</h4>
            <div class="card-meta">
              <span class="type">{{ TYPE_LABEL[t.type] ?? t.type }}</span>
              <span class="created num" :title="formatDateTime(t.createdAt)">{{ shortTime(t.createdAt) }}</span>
            </div>
            <footer class="card-foot">
              <span class="who-cell">
                <span class="avatar" :style="avatarStyle(t.assignee)">{{ initial(t.assignee) }}</span>
                <span class="who-name" :class="{ none: !t.assignee }">{{ t.assignee || '未分配' }}</span>
              </span>
              <SlaBadge :ticket="t" />
            </footer>
          </article>

          <EmptyState
            v-if="columnTickets(col.key).length === 0"
            compact
            icon="inbox"
            title="暂无工单"
            :desc="col.desc"
          />
        </div>
      </section>
    </div>

    <!-- 分配/转交抽屉：处理人或转交目标由抽屉内的启用账号列表选择 -->
    <AssignDrawer
      :open="assignOpen"
      :ticket="assignTarget"
      @close="assignOpen = false"
      @saved="onAssignSaved"
    />
  </div>
</template>

<style scoped>
.board-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.board-head h1 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.board-head p {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-4);
}
.board-head b {
  color: var(--ink-2);
  font-weight: 600;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sync {
  font-size: 12px;
  color: var(--ink-4);
}

/* 工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow-card);
  flex-wrap: wrap;
}
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
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
  left: 9px;
  pointer-events: none;
}
.search input {
  width: 236px;
  height: 32px;
  padding: 0 10px 0 30px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fafbfd;
  font-size: 12px;
}
.filters select,
.filters .who {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
  font-size: 12px;
  color: var(--ink-2);
}
.filters .who {
  width: 110px;
}
.search input:focus,
.filters select:focus,
.filters .who:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.view-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border-radius: 8px;
  background: #f4f6fa;
  flex-wrap: wrap;
}
.view-tabs button {
  height: 26px;
  padding: 0 9px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-3);
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}
.view-tabs button:hover {
  color: var(--ink-1);
}
.view-tabs button.active {
  background: #fff;
  color: var(--brand);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
}

/* 拖拽说明 */
.notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: 10px;
  background: #f5f7ff;
  border: 1px solid #e0e7ff;
  color: #4338ca;
  font-size: 11px;
  line-height: 1.6;
}
.notice p {
  flex: 1;
  min-width: 0;
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

/* 看板网格 */
.board {
  display: grid;
  grid-template-columns: repeat(7, minmax(206px, 1fr));
  gap: 12px;
  align-items: start;
  overflow-x: auto;
  padding-bottom: 2px;
}
.board.single {
  grid-template-columns: minmax(260px, 420px);
}
.column {
  background: #f8f9fc;
  border: 1px solid var(--line);
  border-radius: 12px;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
/* 拖拽悬停列高亮 */
.column.over {
  border-color: var(--brand);
  background: #fff;
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.col-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 11px 9px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.bar {
  width: 3px;
  height: 13px;
  border-radius: 2px;
  flex: none;
}
.col-head strong {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-1);
}
.col-count {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: #eaeef5;
  color: var(--ink-3);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.col-desc {
  width: 100%;
  font-size: 10.5px;
  color: var(--ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.col-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 9px;
  min-height: 140px;
  max-height: calc(100vh - 335px);
  overflow-y: auto;
}
/* 单列聚焦时卡片可两列排布，避免拉伸过宽 */
.board.single .col-body {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  align-content: start;
}
.board.single .empty {
  grid-column: 1 / -1;
}

/* 工单卡片 */
.t-card {
  position: relative;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 11px 9px;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}
.t-card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 6px 18px rgba(79, 70, 229, 0.12);
  transform: translateY(-1px);
}
.t-card.is-overdue {
  border-color: #fecaca;
  background: #fffafa;
}
.t-card.is-warn {
  border-color: #fde68a;
  background: #fffdf7;
}
.t-card.is-done {
  background: #fdfdfe;
}
/* 正在拖拽与动作提交中的卡片 */
.t-card.dragging {
  opacity: 0.45;
  border-style: dashed;
}
.t-card.busy {
  opacity: 0.6;
  pointer-events: none;
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.no {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 10.5px;
  color: var(--ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.t-card h4 {
  margin-top: 7px;
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--ink-1);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-top: 7px;
}
.type {
  font-size: 10.5px;
  color: var(--ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed var(--line-soft);
}
.card-foot :deep(.sla) {
  height: 20px;
  padding: 0 6px;
  gap: 4px;
  font-size: 10px;
}
.who-cell {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  flex: 1;
}
.avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  font-size: 10px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.who-name {
  font-size: 11px;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 76px;
}
.who-name.none {
  color: var(--amber);
}
.created {
  font-size: 10.5px;
  color: var(--ink-4);
  white-space: nowrap;
  flex: none;
}

@media (max-width: 1600px) {
  .col-body {
    max-height: calc(100vh - 360px);
  }
}
@media (max-width: 900px) {
  .search input {
    width: 170px;
  }
}
</style>
