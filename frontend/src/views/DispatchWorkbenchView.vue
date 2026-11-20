<script setup lang="ts">
/** 调度工作台：PENDING 工单池 + 只读推荐，确认分配仍由后端重新校验 */
import { computed, onMounted, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadBar from '../components/LoadBar.vue'
import LoadingState from '../components/LoadingState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import SlaBadge from '../components/SlaBadge.vue'
import { AVAILABILITY_LABEL } from '../constants/dispatch'
import { STATUS_LABEL, TYPE_LABEL, formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import {
  assignDispatch,
  getDispatchRecommendation,
  listPendingDispatchTickets,
  type DispatchCandidate,
  type DispatchRecommendation,
} from '../api/dispatch'
import type { Ticket } from '../api/tickets'

const isAdmin = getCurrentUser()?.role === 'ADMIN'

const tickets = ref<Ticket[]>([])
const listLoading = ref(false)
const listError = ref('')

const selectedId = ref<number | null>(null)
const recommendation = ref<DispatchRecommendation | null>(null)
const recLoading = ref(false)
const recError = ref('')

const assigningAgentId = ref<number | null>(null)
const notice = ref('')
const actionError = ref('')

async function loadTickets() {
  if (!isAdmin) return
  listLoading.value = true
  listError.value = ''
  try {
    const page = await listPendingDispatchTickets(1, 50)
    tickets.value = page.list
  } catch (e) {
    tickets.value = []
    listError.value = e instanceof Error ? e.message : '待分配工单加载失败'
  } finally {
    listLoading.value = false
  }
}

async function loadRecommendation(ticketId: number) {
  recLoading.value = true
  recError.value = ''
  actionError.value = ''
  recommendation.value = null
  try {
    recommendation.value = await getDispatchRecommendation(ticketId, 5)
  } catch (e) {
    recError.value = e instanceof Error ? e.message : '调度推荐加载失败'
  } finally {
    recLoading.value = false
  }
}

async function selectTicket(ticket: Ticket) {
  if (selectedId.value === ticket.id && recommendation.value) return
  selectedId.value = ticket.id
  await loadRecommendation(ticket.id)
}

function availabilityText(value: string): string {
  return AVAILABILITY_LABEL[value] ?? value
}

function remainingText(minutes: number): string {
  if (minutes < 0) return `超时 ${Math.abs(minutes)} 分钟`
  if (minutes < 60) return `剩余 ${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest > 0 ? `剩余 ${hours} 小时 ${rest} 分` : `剩余 ${hours} 小时`
}

interface WeightItem {
  key: string
  label: string
  value: number
}

const weightItems = computed<WeightItem[]>(() => {
  const weights = recommendation.value?.weights
  if (!weights) return []
  return [
    { key: 'skill', label: '技能匹配', value: weights.skill },
    { key: 'workload', label: '负载均衡', value: weights.workload },
    { key: 'sla', label: 'SLA 紧急度', value: weights.sla },
    { key: 'performance', label: '历史绩效', value: weights.performance },
  ]
})

interface ScoreDim {
  key: string
  label: string
  value: number
}

function scoreDims(candidate: DispatchCandidate): ScoreDim[] {
  return [
    { key: 'skill', label: '技能', value: candidate.breakdown.skillScore },
    { key: 'workload', label: '负载', value: candidate.breakdown.workloadScore },
    { key: 'sla', label: 'SLA', value: candidate.breakdown.slaScore },
    { key: 'performance', label: '绩效', value: candidate.breakdown.performanceScore },
  ]
}

async function confirmAssign(candidate: DispatchCandidate) {
  const rec = recommendation.value
  if (!rec || assigningAgentId.value !== null) return
  const ok = window.confirm(
    `确认将工单 ${rec.ticketNo} 分配给 ${candidate.name}？\n` +
      `总分 ${candidate.score.toFixed(1)} · 负载 ${candidate.occupied}/${candidate.maxConcurrent}`
  )
  if (!ok) return
  assigningAgentId.value = candidate.agentId
  actionError.value = ''
  notice.value = ''
  try {
    await assignDispatch({ ticketId: rec.ticketId, agentId: candidate.agentId })
    notice.value = `已分配：${rec.ticketNo} → ${candidate.name}`
    tickets.value = tickets.value.filter((t) => t.id !== rec.ticketId)
    recommendation.value = null
    selectedId.value = null
    await loadTickets()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '确认分配失败'
  } finally {
    assigningAgentId.value = null
  }
}

onMounted(loadTickets)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>调度工作台</h1>
        <p class="head-sub">
          待分配工单按后端推荐排序 · 推荐只读，确认分配由后端重新校验候选与工单状态
        </p>
      </div>
      <button v-if="isAdmin" class="btn" :disabled="listLoading" @click="loadTickets">
        <AppIcon name="refresh" :size="14" />刷新工单池
      </button>
    </section>

    <template v-if="!isAdmin">
      <section class="card gate">
        <EmptyState
          icon="alert"
          title="仅管理员可执行调度推荐与确认分配"
          desc="当前账号没有调度权限。工单分配请前往工单列表，由管理员在详情页完成。"
        >
          <router-link class="btn btn-primary" to="/tickets">前往工单列表</router-link>
        </EmptyState>
      </section>
    </template>

    <template v-else>
      <p v-if="notice" class="notice"><AppIcon name="check" :size="15" />{{ notice }}</p>
      <p v-if="listError" class="alert"><AppIcon name="alert" :size="15" />{{ listError }}</p>
      <p v-if="actionError" class="alert"><AppIcon name="alert" :size="15" />{{ actionError }}</p>

      <div class="workbench">
        <!-- 左：待分配工单池 -->
        <section class="card pool">
          <header class="card-head">
            <h2>待分配工单</h2>
            <span class="meta">
              PENDING · 共 <b class="num">{{ tickets.length }}</b> 条
            </span>
          </header>

          <div v-if="listLoading && tickets.length === 0" class="pad">
            <LoadingState :height="140" text="加载待分配工单…" />
          </div>
          <div v-else-if="tickets.length === 0" class="pad">
            <EmptyState compact icon="inbox" title="暂无待分配工单" desc="工单分配后会自动从该列表移除" />
          </div>
          <ul v-else class="pool-list">
            <li v-for="t in tickets" :key="t.id">
              <button
                class="pool-item"
                :class="{ active: selectedId === t.id }"
                @click="selectTicket(t)"
              >
                <div class="pool-top">
                  <span class="mono">{{ t.ticketNo }}</span>
                  <PriorityTag :priority="t.priority" />
                </div>
                <p class="pool-title" :title="t.title">{{ t.title }}</p>
                <div class="pool-foot">
                  <span class="type">{{ TYPE_LABEL[t.type] ?? t.type }}</span>
                  <span class="remain num">{{ remainingText(t.sla.remainingMinutes) }}</span>
                </div>
                <div class="pool-sla"><SlaBadge :ticket="t" show-warn-text /></div>
              </button>
            </li>
          </ul>
        </section>

        <!-- 右：推荐详情 -->
        <section class="card detail">
          <div v-if="selectedId === null" class="pad">
            <EmptyState
              icon="route"
              title="请选择左侧待分配工单"
              desc="选中工单后，将按技能、负载、SLA 与历史绩效四个维度生成候选排序。"
            />
          </div>

          <div v-else-if="recLoading" class="pad">
            <LoadingState :height="220" text="生成调度推荐…" />
          </div>

          <div v-else-if="recError" class="pad">
            <p class="alert"><AppIcon name="alert" :size="15" />{{ recError }}</p>
          </div>

          <template v-else-if="recommendation">
            <header class="rec-head">
              <div class="rec-title">
                <span class="mono">{{ recommendation.ticketNo }}</span>
                <PriorityTag :priority="recommendation.priority" />
                <span class="status">{{ STATUS_LABEL[recommendation.status] ?? recommendation.status }}</span>
              </div>
              <h2>{{ recommendation.title }}</h2>
              <p class="rec-sub">
                {{ TYPE_LABEL[recommendation.type] ?? recommendation.type }} ·
                SLA 状态 {{ recommendation.slaState }} ·
                {{ remainingText(recommendation.slaRemainingMinutes) }} ·
                推荐生成于 {{ formatDateTime(recommendation.generatedAt) }}
              </p>
            </header>

            <div class="rec-meta">
              <div class="block">
                <h3>所需技能</h3>
                <div class="chips">
                  <span
                    v-for="s in recommendation.requiredSkills"
                    :key="s.skillId"
                    class="chip"
                    :class="{ req: s.required }"
                  >
                    {{ s.skillName }}<em>{{ s.required ? '必需' : '加分' }}</em>
                  </span>
                  <span v-if="recommendation.requiredSkills.length === 0" class="muted">
                    该工单类型未配置技能要求
                  </span>
                </div>
              </div>
              <div class="block">
                <h3>当前权重（后端归一化）</h3>
                <div class="weights">
                  <span v-for="w in weightItems" :key="w.key" class="weight">
                    {{ w.label }} <b class="num">{{ (w.value * 100).toFixed(1) }}%</b>
                  </span>
                </div>
              </div>
            </div>

            <section class="cands">
              <header class="cands-head">
                <h3>候选排序</h3>
                <span class="meta">按总分降序 · 前 {{ recommendation.candidates.length }} 名</span>
              </header>

              <div v-if="recommendation.candidates.length === 0" class="pad">
                <EmptyState compact icon="users" title="暂无可用候选" desc="请查看下方排除名单中的原因" />
              </div>

              <article v-for="(c, index) in recommendation.candidates" :key="c.agentId" class="cand">
                <header class="cand-head">
                  <span class="rank num">#{{ index + 1 }}</span>
                  <div class="who">
                    <strong>{{ c.name }}</strong>
                    <span class="uname">{{ c.username }}</span>
                  </div>
                  <span class="avail" :class="`is-${c.availability}`">
                    {{ availabilityText(c.availability) }}
                  </span>
                  <span class="total">
                    <b class="num">{{ c.score.toFixed(1) }}</b><small>总分</small>
                  </span>
                  <button
                    class="btn btn-sm btn-primary"
                    :disabled="assigningAgentId !== null"
                    @click="confirmAssign(c)"
                  >
                    {{ assigningAgentId === c.agentId ? '分配中…' : '确认分配' }}
                  </button>
                </header>

                <div class="dims">
                  <div v-for="dim in scoreDims(c)" :key="dim.key" class="dim">
                    <span class="dim-label">{{ dim.label }}</span>
                    <LoadBar :rate="dim.value" level="NORMAL" :show-text="false" />
                    <span class="dim-value num">{{ dim.value.toFixed(1) }}</span>
                  </div>
                </div>

                <p class="facts">
                  负载率 <b class="num">{{ c.loadRate.toFixed(1) }}%</b>（{{ c.occupied }}/{{ c.maxConcurrent }}）
                  <template v-if="c.matchedSkills.length">
                    · 命中技能：{{ c.matchedSkills.join('、') }}
                  </template>
                </p>

                <ul v-if="c.reasons.length" class="reasons">
                  <li v-for="r in c.reasons" :key="r">{{ r }}</li>
                </ul>
                <ul v-if="c.warnings.length" class="warnings">
                  <li v-for="w in c.warnings" :key="w">{{ w }}</li>
                </ul>
              </article>
            </section>

            <section v-if="recommendation.excluded.length" class="excluded">
              <h3>排除名单</h3>
              <ul>
                <li v-for="e in recommendation.excluded" :key="e.agentId">
                  <strong>{{ e.name }}</strong>
                  <span>{{ e.reasons.join('；') }}</span>
                </li>
              </ul>
            </section>
          </template>
        </section>
      </div>
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
.pad {
  padding: 16px;
}
.notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #047857;
  font-size: 13px;
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

.workbench {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

/* 工单池 */
.pool {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 200px);
}
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--line-soft);
  flex: none;
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
.pool-list {
  list-style: none;
  overflow-y: auto;
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.pool-item {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  padding: 9px 10px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: background 0.14s ease, border-color 0.14s ease;
}
.pool-item:hover {
  background: #fafbfd;
  border-color: var(--line);
}
.pool-item.active {
  background: var(--brand-weak);
  border-color: #c7d2fe;
}
.pool-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.pool-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.45;
}
.pool-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: var(--ink-4);
}
.type {
  color: var(--ink-3);
}
.remain {
  color: var(--ink-3);
}
.pool-sla {
  margin-top: 2px;
}

/* 推荐详情 */
.detail {
  padding-bottom: 16px;
}
.rec-head {
  padding: 15px 18px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.rec-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.status {
  font-size: 11px;
  color: var(--ink-3);
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--line-soft);
}
.rec-head h2 {
  margin-top: 6px;
  font-size: 17px;
  font-weight: 600;
  color: var(--ink-1);
  line-height: 1.4;
}
.rec-sub {
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-4);
}
.rec-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line-soft);
}
.block h3,
.cands-head h3,
.excluded h3 {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--ink-4);
  text-transform: uppercase;
}
.chips,
.weights {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 7px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  background: #f8fafc;
  border: 1px solid var(--line);
  font-size: 12px;
  color: var(--ink-2);
}
.chip.req {
  background: #fef2f2;
  border-color: #fecaca;
  color: #b91c1c;
}
.chip em {
  font-style: normal;
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(148, 163, 184, 0.18);
}
.weight {
  font-size: 12px;
  color: var(--ink-3);
}
.weight b {
  color: var(--ink-1);
  margin-left: 2px;
}

/* 候选 */
.cands {
  padding: 14px 18px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cands-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.cand {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 11px 12px;
  background: #fff;
}
.cand-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.rank {
  flex: none;
  width: 30px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--brand-weak);
  color: var(--brand);
  font-size: 11px;
  font-weight: 700;
}
.who {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.who strong {
  font-size: 13px;
  color: var(--ink-1);
}
.uname {
  font-size: 11px;
  color: var(--ink-4);
}
.avail {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 999px;
  background: #f1f5f9;
  color: var(--ink-3);
}
.avail.is-AVAILABLE {
  background: #ecfdf5;
  color: #047857;
}
.avail.is-BUSY {
  background: #fffbeb;
  color: #b45309;
}
.avail.is-OFFLINE {
  background: #f1f5f9;
  color: #64748b;
}
.avail.is-DISABLED {
  background: #fef2f2;
  color: #b91c1c;
}
.total {
  margin-left: auto;
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
}
.total b {
  font-size: 19px;
  font-weight: 700;
  color: var(--ink-1);
}
.total small {
  font-size: 10px;
  color: var(--ink-4);
}
.dims {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px 14px;
  margin-top: 9px;
}
.dim {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.dim-label {
  flex: none;
  font-size: 11px;
  color: var(--ink-4);
}
.dim-value {
  flex: none;
  font-size: 11px;
  color: var(--ink-2);
  min-width: 26px;
  text-align: right;
}
.facts {
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink-4);
}
.facts b {
  color: var(--ink-2);
}
.reasons,
.warnings {
  list-style: none;
  margin-top: 7px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}
.reasons li,
.warnings li {
  font-size: 11px;
  color: var(--ink-3);
  padding-left: 10px;
  position: relative;
}
.reasons li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--emerald);
}
.warnings li {
  color: #b45309;
}
.warnings li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--amber);
}

/* 排除名单 */
.excluded {
  margin: 14px 18px 0;
  padding: 11px 12px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: #fafbfd;
}
.excluded ul {
  list-style: none;
  margin-top: 7px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.excluded li {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-3);
}
.excluded strong {
  flex: none;
  color: var(--ink-2);
  font-weight: 600;
}
.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--ink-3);
}
.muted {
  font-size: 12px;
  color: var(--ink-4);
}

@media (max-width: 1280px) {
  .workbench {
    grid-template-columns: minmax(0, 1fr);
  }
  .pool {
    max-height: 340px;
  }
  .dims {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .rec-meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
