<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AssignDrawer from '../components/AssignDrawer.vue'
import DataTable from '../components/DataTable.vue'
import PriorityTag from '../components/PriorityTag.vue'
import SlaBadge from '../components/SlaBadge.vue'
import StatusTag from '../components/StatusTag.vue'
import TicketFormModal from '../components/TicketFormModal.vue'
import {
  PRIORITY_LABEL,
  PRIORITY_OPTIONS,
  PRIORITY_WEIGHT,
  STATUS_LABEL,
  STATUS_OPTIONS,
  STATUS_WEIGHT,
  TYPE_LABEL,
  TYPE_OPTIONS,
  formatDateTime,
  getSla,
  type SlaInfo,
} from '../constants/ticket'
import { deleteTicket, type Ticket } from '../api/tickets'
import { getCurrentUser } from '../api/request'
import {
  isLoading,
  isTruncated,
  refreshTickets,
  tickets,
  totalCount,
} from '../composables/useTicketStore'
import type { TableColumn } from '../types/ui'

const route = useRoute()
const router = useRouter()
const currentUser = getCurrentUser()
const isAdmin = currentUser?.role === 'ADMIN'

/* ---------------- 筛选（草稿 + 已应用） ---------------- */

interface Filters {
  ticketNo: string
  title: string
  type: string
  priority: string
  status: string
  creator: string
  assignee: string
  from: string
  to: string
  sla: string
}

function blankFilters(): Filters {
  return {
    ticketNo: '',
    title: '',
    type: '',
    priority: '',
    status: '',
    creator: '',
    assignee: '',
    from: '',
    to: '',
    sla: '',
  }
}

const filters = reactive<Filters>(blankFilters())
const applied = reactive<Filters>(blankFilters())

function applyFilters() {
  Object.assign(applied, filters)
  quick.value = 'all'
  page.value = 1
}

function resetFilters() {
  Object.assign(filters, blankFilters())
  Object.assign(applied, blankFilters())
  quick.value = 'all'
  page.value = 1
  sortKey.value = 'createdAt'
  sortDir.value = 'desc'
  if (route.query.q || route.query.new) router.replace({ path: '/tickets' })
}

const hasFilter = computed(() =>
  Object.values(applied).some((v) => v !== '') || quick.value !== 'all'
)

/* ---------------- SLA（读取后端计算结果） ---------------- */

const slaMap = computed(() => {
  const map = new Map<number, SlaInfo>()
  tickets.value.forEach((t) => map.set(t.id, getSla(t)))
  return map
})

const stats = computed(() => {
  let unassigned = 0
  let inProgress = 0
  let overdue = 0
  let warn = 0
  let done = 0
  tickets.value.forEach((t) => {
    if (t.status === 'PENDING') unassigned += 1
    if (t.status === 'ACCEPTED' || t.status === 'PROCESSING') inProgress += 1
    if (t.status === 'RESOLVED' || t.status === 'CLOSED') done += 1
    if (t.sla?.state === 'OVERDUE') overdue += 1
    if (t.sla?.state === 'WARNING') warn += 1
  })
  return { unassigned, inProgress, overdue, warn, done }
})

/* ---------------- 快捷筛选 ---------------- */

type QuickKey = 'all' | 'unassigned' | 'inprogress' | 'overdue'
const quick = ref<QuickKey>('all')

const quickLabel = computed(() => {
  switch (quick.value) {
    case 'unassigned':
      return '待分配'
    case 'inprogress':
      return '处理中'
    case 'overdue':
      return 'SLA 已超时'
    default:
      return '全部'
  }
})

function toggleQuick(key: QuickKey) {
  quick.value = quick.value === key ? 'all' : key
  page.value = 1
}

/* ---------------- 过滤 + 排序 + 分页 ---------------- */

const filtered = computed(() => {
  const f = applied
  const no = f.ticketNo.trim().toLowerCase()
  const title = f.title.trim().toLowerCase()
  const creator = f.creator.trim().toLowerCase()
  const assignee = f.assignee.trim().toLowerCase()

  return tickets.value.filter((t) => {
    if (quick.value === 'unassigned' && t.status !== 'PENDING') return false
    if (
      quick.value === 'inprogress' &&
      t.status !== 'ACCEPTED' &&
      t.status !== 'PROCESSING'
    )
      return false
    if (quick.value === 'overdue' && t.sla?.state !== 'OVERDUE') return false

    if (no && !t.ticketNo.toLowerCase().includes(no)) return false
    if (title && !t.title.toLowerCase().includes(title)) return false
    if (f.type && t.type !== f.type) return false
    if (f.priority && t.priority !== f.priority) return false
    if (f.status && t.status !== f.status) return false
    if (creator && !(t.creator ?? '').toLowerCase().includes(creator)) return false
    if (assignee && !(t.assignee ?? '').toLowerCase().includes(assignee)) return false

    const day = t.createdAt.slice(0, 10)
    if (f.from && day < f.from) return false
    if (f.to && day > f.to) return false

    if (f.sla) {
      const state = slaMap.value.get(t.id)?.state ?? 'ok'
      if (state !== f.sla) return false
    }
    return true
  })
})

const sortKey = ref('createdAt')
const sortDir = ref<'asc' | 'desc'>('desc')

function onSort(key: string) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDir.value = key === 'createdAt' ? 'desc' : 'asc'
  }
  page.value = 1
}

const sorted = computed(() => {
  const list = [...filtered.value]
  const dir = sortDir.value === 'asc' ? 1 : -1
  list.sort((a, b) => {
    let result = 0
    switch (sortKey.value) {
      case 'ticketNo':
        result = a.ticketNo.localeCompare(b.ticketNo)
        break
      case 'title':
        result = a.title.localeCompare(b.title, 'zh-CN')
        break
      case 'priority':
        result = (PRIORITY_WEIGHT[a.priority] ?? 0) - (PRIORITY_WEIGHT[b.priority] ?? 0)
        break
      case 'status':
        result = (STATUS_WEIGHT[a.status] ?? 0) - (STATUS_WEIGHT[b.status] ?? 0)
        break
      case 'sla': {
        const sa = slaMap.value.get(a.id)
        const sb = slaMap.value.get(b.id)
        const va = !sa || sa.state === 'done' ? Number.MAX_SAFE_INTEGER : sa.remainingMs
        const vb = !sb || sb.state === 'done' ? Number.MAX_SAFE_INTEGER : sb.remainingMs
        result = va - vb
        break
      }
      default:
        result = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
    }
    return result * dir
  })
  return list
})

const size = ref(10)
const page = ref(1)
const totalPages = computed(() => Math.max(1, Math.ceil(sorted.value.length / size.value)))
const paged = computed(() =>
  sorted.value.slice((page.value - 1) * size.value, page.value * size.value)
)

const pageList = computed(() => {
  const total = totalPages.value
  const start = Math.max(1, Math.min(page.value - 2, total - 4))
  const end = Math.min(total, start + 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

const rangeText = computed(() => {
  if (sorted.value.length === 0) return '0 条'
  const from = (page.value - 1) * size.value + 1
  const to = Math.min(page.value * size.value, sorted.value.length)
  return `${from}-${to} 条`
})

watch([() => sorted.value.length, size], () => {
  if (page.value > totalPages.value) page.value = totalPages.value
})
watch(
  () => route.query.q,
  (q) => {
    const value = String(q ?? '')
    filters.ticketNo = value
    applied.ticketNo = value
    page.value = 1
  }
)

/* ---------------- 表格列 ---------------- */

const columns: TableColumn[] = [
  { key: 'ticketNo', label: '工单编号', width: '152px', nowrap: true, sortable: true },
  { key: 'title', label: '工单标题', sortable: true },
  { key: 'type', label: '类型', width: '104px', nowrap: true },
  { key: 'priority', label: '优先级', width: '82px', sortable: true },
  { key: 'status', label: '状态', width: '104px', sortable: true },
  { key: 'creator', label: '创建人', width: '88px', nowrap: true },
  { key: 'assignee', label: '处理人', width: '88px', nowrap: true },
  { key: 'createdAt', label: '创建时间', width: '140px', nowrap: true, sortable: true },
  { key: 'sla', label: 'SLA 剩余时间', width: '158px', nowrap: true, sortable: true },
  { key: 'actions', label: '操作', width: '152px', align: 'right' },
]

function rowClassOf(row: Record<string, any>): string {
  const state = slaMap.value.get(Number(row.id))?.state
  if (state === 'overdue') return 'row-overdue'
  if (state === 'warn') return 'row-warn'
  return ''
}

function goDetail(row: Record<string, any>) {
  router.push(`/tickets/${row.id}`)
}

/* ---------------- 创建 / 编辑 / 分配 / 删除 ---------------- */

const formOpen = ref(false)
const editing = ref<Ticket | null>(null)

function openCreate() {
  editing.value = null
  formOpen.value = true
}

function openEdit(t: Ticket) {
  editing.value = t
  formOpen.value = true
}

async function onSaved() {
  await refreshTickets(true)
}

const assignOpen = ref(false)
const assigning = ref<Ticket | null>(null)

/** 行内分配/转交入口：转交优先，其次分配；无可用动作则不展示 */
function assignActionOf(row: Record<string, any>): 'TRANSFER' | 'ASSIGN' | null {
  const actions = Array.isArray(row.allowedActions) ? (row.allowedActions as string[]) : []
  if (actions.includes('TRANSFER')) return 'TRANSFER'
  if (actions.includes('ASSIGN')) return 'ASSIGN'
  return null
}

function openAssign(t: Ticket) {
  assigning.value = t
  assignOpen.value = true
}

async function onAssigned() {
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

onMounted(async () => {
  const q = String(route.query.q ?? '')
  if (q) {
    filters.ticketNo = q
    applied.ticketNo = q
  }
  await refreshTickets()
  if (route.query.new === '1') {
    openCreate()
    router.replace({ path: '/tickets' })
  }
})
</script>

<template>
  <div class="page">
    <!-- 页头：标题 + 总量 + 创建入口 -->
    <section class="page-head">
      <div class="head-left">
        <h1>工单管理</h1>
        <p class="head-sub">
          共 <b class="num">{{ totalCount }}</b> 条工单 · 待分配
          <b class="num">{{ stats.unassigned }}</b> · 处理中
          <b class="num">{{ stats.inProgress }}</b> · 已超时
          <b class="num danger">{{ stats.overdue }}</b>
        </p>
      </div>
      <div class="head-right">
        <span v-if="isLoading" class="sync">同步中…</span>
        <span v-else-if="stats.warn > 0" class="warn-hint">
          <AppIcon name="alert" :size="13" />{{ stats.warn }} 条即将超时
        </span>
        <button class="btn" :disabled="isLoading" @click="refreshTickets(true)">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button class="btn btn-primary" @click="openCreate">
          <AppIcon name="plus" :size="15" />创建工单
        </button>
      </div>
    </section>

    <!-- 指标条：点击可快捷筛选 -->
    <section class="kpis">
      <button class="kpi" :class="{ active: quick === 'all' }" @click="quick = 'all'">
        <span class="kpi-label">工单总量</span>
        <span class="kpi-value num">{{ totalCount }}</span>
        <span class="kpi-hint">实时接口聚合</span>
      </button>
      <button
        class="kpi tone-amber"
        :class="{ active: quick === 'unassigned' }"
        @click="toggleQuick('unassigned')"
      >
        <span class="kpi-label">待分配</span>
        <span class="kpi-value num">{{ stats.unassigned }}</span>
        <span class="kpi-hint">待处理且无处理人</span>
      </button>
      <button
        class="kpi tone-brand"
        :class="{ active: quick === 'inprogress' }"
        @click="toggleQuick('inprogress')"
      >
        <span class="kpi-label">处理中</span>
        <span class="kpi-value num">{{ stats.inProgress }}</span>
        <span class="kpi-hint">已接单 + 处理中</span>
      </button>
      <button
        class="kpi tone-rose"
        :class="{ active: quick === 'overdue' }"
        @click="toggleQuick('overdue')"
      >
        <span class="kpi-label">已超时</span>
        <span class="kpi-value num">{{ stats.overdue }}</span>
        <span class="kpi-hint">SLA 已超时（后端计算）</span>
      </button>
    </section>

    <!-- 组合筛选 -->
    <section class="filters-card">
      <div class="filter-grid">
        <label class="f-field">
          <span>工单编号</span>
          <input v-model="filters.ticketNo" placeholder="如 SF20261116-XXXX" @keyup.enter="applyFilters" />
        </label>
        <label class="f-field">
          <span>工单标题</span>
          <input v-model="filters.title" placeholder="标题关键字" @keyup.enter="applyFilters" />
        </label>
        <label class="f-field">
          <span>工单类型</span>
          <select v-model="filters.type">
            <option value="">全部类型</option>
            <option v-for="t in TYPE_OPTIONS" :key="t" :value="t">{{ TYPE_LABEL[t] }}</option>
          </select>
        </label>
        <label class="f-field">
          <span>优先级</span>
          <select v-model="filters.priority">
            <option value="">全部优先级</option>
            <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
          </select>
        </label>
        <label class="f-field">
          <span>工单状态</span>
          <select v-model="filters.status">
            <option value="">全部状态</option>
            <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
          </select>
        </label>
        <label class="f-field">
          <span>创建人</span>
          <input v-model="filters.creator" placeholder="创建人姓名" @keyup.enter="applyFilters" />
        </label>
        <label class="f-field">
          <span>处理人</span>
          <input v-model="filters.assignee" placeholder="处理人姓名" @keyup.enter="applyFilters" />
        </label>
        <label class="f-field">
          <span>创建时间范围</span>
          <div class="date-range">
            <input v-model="filters.from" type="date" />
            <em>至</em>
            <input v-model="filters.to" type="date" />
          </div>
        </label>
        <label class="f-field">
          <span>SLA 状态</span>
          <select v-model="filters.sla">
            <option value="">全部</option>
            <option value="overdue">已超时</option>
            <option value="warn">即将超时</option>
            <option value="ok">正常</option>
            <option value="done">已结束</option>
          </select>
        </label>
      </div>
      <div class="filter-foot">
        <p class="sla-note">
          <AppIcon name="clock" :size="13" />
          SLA 由后端按优先级策略计算：紧急 4h / 高 8h / 中 24h / 低 72h，剩余 120 分钟进入预警，已解决/已关闭/已取消停止计时。
        </p>
        <div class="filter-actions">
          <button class="btn btn-sm" @click="resetFilters">重置筛选</button>
          <button class="btn btn-sm btn-primary" @click="applyFilters">
            <AppIcon name="search" :size="13" />查询
          </button>
        </div>
      </div>
    </section>

    <!-- 数据表格 -->
    <section class="table-card">
      <header class="table-head">
        <div class="table-title">
          <h2>工单列表</h2>
          <span class="result">
            筛选结果 <b class="num">{{ sorted.length }}</b> 条 · 全量
            <b class="num">{{ tickets.length }}</b> 条
          </span>
        </div>
        <div class="table-tools">
          <span v-if="hasFilter" class="applied-chip">筛选生效</span>
          <span v-if="quick !== 'all'" class="quick-chip">
            快捷筛选：{{ quickLabel }}
            <button @click="quick = 'all'">×</button>
          </span>
          <span v-if="isTruncated" class="truncated">仅加载前 1000 条</span>
        </div>
      </header>

      <DataTable
        :columns="columns"
        :rows="paged"
        :loading="isLoading && tickets.length === 0"
        :sort-key="sortKey"
        :sort-dir="sortDir"
        :row-class="rowClassOf"
        clickable
        empty-text="没有符合筛选条件的工单"
        @sort="onSort"
        @row-click="goDetail"
      >
        <template #cell-ticketNo="{ row }">
          <span class="mono">{{ row.ticketNo }}</span>
        </template>
        <template #cell-title="{ row }">
          <span class="cell-title" :title="row.title">{{ row.title }}</span>
        </template>
        <template #cell-type="{ row }">
          <span class="type-text">{{ TYPE_LABEL[row.type] ?? row.type }}</span>
        </template>
        <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
        <template #cell-creator="{ row }">
          <span class="person">{{ row.creator }}</span>
        </template>
        <template #cell-assignee="{ row }">
          <span v-if="row.assignee" class="person">{{ row.assignee }}</span>
          <span v-else class="person none">未分配</span>
        </template>
        <template #cell-createdAt="{ row }">
          <span class="time" :title="row.createdAt">{{ formatDateTime(row.createdAt) }}</span>
        </template>
        <template #cell-sla="{ row }">
          <SlaBadge :ticket="row as Ticket" show-warn-text />
        </template>
        <template #cell-actions="{ row }">
          <div class="row-actions" @click.stop>
            <button class="link" @click="router.push(`/tickets/${row.id}`)">详情</button>
            <button v-if="row.canEdit" class="link" @click="openEdit(row as Ticket)">编辑</button>
            <button
              v-if="assignActionOf(row)"
              class="link"
              @click="openAssign(row as Ticket)"
            >
              {{ assignActionOf(row) === 'TRANSFER' ? '转交' : '分配' }}
            </button>
            <button v-if="isAdmin" class="link danger" @click="remove(row as Ticket)">删除</button>
          </div>
        </template>
      </DataTable>

      <footer class="pager">
        <span class="pager-info">
          共 <b class="num">{{ sorted.length }}</b> 条 · 当前 {{ rangeText }}
          <template v-if="stats.overdue > 0"> · 超时 <b class="num danger">{{ stats.overdue }}</b></template>
        </span>
        <div class="pager-btns">
          <button class="pg" :disabled="page <= 1" title="首页" @click="page = 1">«</button>
          <button class="pg" :disabled="page <= 1" @click="page -= 1">上一页</button>
          <button
            v-for="p in pageList"
            :key="p"
            class="pg num"
            :class="{ active: p === page }"
            @click="page = p"
          >
            {{ p }}
          </button>
          <button class="pg" :disabled="page >= totalPages" @click="page += 1">下一页</button>
          <button class="pg" :disabled="page >= totalPages" title="末页" @click="page = totalPages">»</button>
        </div>
        <label class="page-size">
          每页
          <select v-model.number="size">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
          </select>
          条
        </label>
      </footer>
    </section>

    <TicketFormModal :open="formOpen" :ticket="editing" @close="formOpen = false" @saved="onSaved" />
    <AssignDrawer :open="assignOpen" :ticket="assigning" @close="assignOpen = false" @saved="onAssigned" />
  </div>
</template>

<style scoped>
/* 页头 */
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
.head-sub b {
  color: var(--ink-2);
  font-weight: 600;
}
.danger {
  color: var(--rose) !important;
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
.warn-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 9px;
  border-radius: 6px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #b45309;
  font-size: 11px;
}

/* 指标条：紧凑四宫格，区别于驾驶舱大卡片 */
.kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--shadow-card);
}
.kpi {
  display: grid;
  grid-template-columns: 1fr auto;
  grid-template-areas:
    'label hint'
    'value hint';
  align-items: center;
  gap: 2px 8px;
  padding: 12px 16px;
  background: var(--panel);
  border: none;
  text-align: left;
  cursor: pointer;
  transition: background 0.14s ease;
  --tone: var(--ink-4);
}
.kpi:hover {
  background: #fbfcfe;
}
.kpi.active {
  background: color-mix(in srgb, var(--tone) 7%, #fff);
  box-shadow: inset 0 -2px 0 var(--tone);
}
.kpi-label {
  grid-area: label;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--ink-3);
}
.kpi-value {
  grid-area: value;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink-1);
}
.kpi-hint {
  grid-area: hint;
  font-size: 11px;
  color: var(--ink-4);
  text-align: right;
  max-width: 120px;
}
.tone-amber {
  --tone: var(--amber);
}
.tone-brand {
  --tone: var(--brand);
}
.tone-rose {
  --tone: var(--rose);
}
.tone-rose .kpi-value {
  color: var(--rose);
}

/* 筛选区 */
.filters-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: 14px 16px 12px;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px 12px;
}
.f-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.f-field > span {
  font-size: 11px;
  color: var(--ink-4);
  font-weight: 600;
  letter-spacing: 0.03em;
}
.f-field input,
.f-field select {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fafbfd;
  font-size: 12px;
  color: var(--ink-1);
  min-width: 0;
}
.f-field input:focus,
.f-field select:focus {
  outline: none;
  background: #fff;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.date-range {
  display: flex;
  align-items: center;
  gap: 6px;
}
.date-range input {
  flex: 1;
  min-width: 0;
  padding: 0 6px;
}
.date-range em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-4);
  flex: none;
}
.filter-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--line-soft);
  flex-wrap: wrap;
}
.sla-note {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--ink-4);
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
}
.filter-actions {
  display: flex;
  gap: 8px;
}

/* 表格区 */
.table-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line-soft);
}
.table-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.table-title h2 {
  font-size: 14px;
  font-weight: 600;
}
.result {
  font-size: 12px;
  color: var(--ink-4);
}
.result b {
  color: var(--ink-2);
  font-weight: 600;
}
.table-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.quick-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 4px 0 9px;
  border-radius: 999px;
  background: var(--brand-weak);
  color: var(--brand);
  font-size: 11px;
}
.quick-chip button {
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  line-height: 1;
}
.quick-chip button:hover {
  background: #e0e7ff;
}
.truncated {
  font-size: 11px;
  color: var(--amber);
}
.applied-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #047857;
  font-size: 11px;
}
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.cell-title {
  display: block;
  max-width: 460px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-1);
  font-weight: 500;
}
.type-text {
  font-size: 12px;
  color: var(--ink-3);
}
.person {
  font-size: 12px;
  color: var(--ink-2);
}
.person.none {
  color: var(--amber);
}
.time {
  font-size: 12px;
  color: var(--ink-4);
  font-variant-numeric: tabular-nums;
}
.row-actions {
  display: inline-flex;
  gap: 2px;
  justify-content: flex-end;
}
.link {
  border: none;
  background: none;
  padding: 2px 4px;
  font-size: 12px;
  color: var(--brand);
  cursor: pointer;
  white-space: nowrap;
}
.link:hover {
  text-decoration: underline;
}
.link.danger {
  color: var(--rose);
}

/* 行级 SLA 提示 */
:deep(tr.row-overdue) td:first-child {
  box-shadow: inset 3px 0 0 var(--rose);
}
:deep(tr.row-overdue) {
  background: #fffafa;
}
:deep(tr.row-overdue:hover) {
  background: #fff5f5;
}
:deep(tr.row-warn) td:first-child {
  box-shadow: inset 3px 0 0 var(--amber);
}
:deep(tr.row-warn:hover) {
  background: #fffdf7;
}

/* 操作列吸右：1366 宽度下横向滚动时操作入口始终可见 */
:deep(thead th:last-child) {
  position: sticky;
  right: 0;
  z-index: 2;
  background: #fafbfd;
  box-shadow: -8px 0 10px -8px rgba(15, 23, 42, 0.18);
}
:deep(tbody td:last-child) {
  position: sticky;
  right: 0;
  z-index: 1;
  background: #fff;
  box-shadow: -8px 0 10px -8px rgba(15, 23, 42, 0.14);
}
:deep(tbody tr:hover td:last-child) {
  background: #fafbfd;
}
:deep(tbody tr.row-overdue td:last-child) {
  background: #fffafa;
}
:deep(tbody tr.row-warn td:last-child) {
  background: #fffdf7;
}

/* 分页 */
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
.pg.active {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
  font-weight: 600;
}
.page-size {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-4);
}
.page-size select {
  height: 28px;
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  color: var(--ink-2);
}

@media (max-width: 1600px) {
  .filter-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1100px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filter-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .cell-title {
    max-width: 220px;
  }
}
</style>
