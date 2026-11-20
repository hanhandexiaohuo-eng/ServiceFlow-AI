<script setup lang="ts">
/** AI 工作空间：三栏布局（功能/会话 · 对话与分析 · 工单上下文） */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AiResultPanel from '../components/AiResultPanel.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import PriorityTag from '../components/PriorityTag.vue'
import SlaBadge from '../components/SlaBadge.vue'
import StatusTag from '../components/StatusTag.vue'
import { ANALYSIS_TYPES, SUGGESTION_STATUS_LABEL, percent } from '../constants/ai'
import { formatDateTime } from '../constants/ticket'
import { listTickets, type Ticket } from '../api/tickets'
import {
  acceptSuggestion,
  askAiSession,
  createAiSession,
  getAiSession,
  getAnalysis,
  getTicketAiContext,
  listAiSessions,
  rejectSuggestion,
  runAnalysis,
  type AiAnalysis,
  type AiAnalysisType,
  type AiMessage,
  type AiSession,
  type AiSuggestion,
  type AiTicketContext
} from '../api/ai'
import { getCurrentUser } from '../api/request'

const route = useRoute()
const router = useRouter()

const tickets = ref<Ticket[]>([])
const selectedId = ref<number | null>(null)
const context = ref<AiTicketContext | null>(null)
const sessions = ref<AiSession[]>([])
const activeSessionId = ref<number | null>(null)
const messages = ref<AiMessage[]>([])
const lastAnalysis = ref<AiAnalysis | null>(null)
const question = ref('')

const loadingTickets = ref(true)
const loadingContext = ref(false)
const loadingSession = ref(false)
const analyzing = ref(false)
const sending = ref(false)
const busySuggestionId = ref<number | null>(null)
const errorMessage = ref('')
const noticeMessage = ref('')

const isAdmin = computed(() => getCurrentUser()?.role === 'ADMIN')
const selectedTicket = computed(() => context.value?.ticket ?? null)
const activeSession = computed(
  () => sessions.value.find((item) => item.id === activeSessionId.value) ?? null
)

async function loadTicketOptions() {
  loadingTickets.value = true
  try {
    const page = await listTickets({ page: 1, size: 100 })
    tickets.value = page.list
    const fromQuery = Number(route.query.ticketId)
    const preferred = Number.isFinite(fromQuery) && fromQuery > 0
      ? page.list.find((item) => item.id === fromQuery)
      : page.list[0]
    if (preferred) {
      selectedId.value = preferred.id
      await selectTicket(preferred.id)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '工单列表加载失败'
  } finally {
    loadingTickets.value = false
  }
}

async function selectTicket(ticketId: number) {
  selectedId.value = ticketId
  lastAnalysis.value = null
  messages.value = []
  activeSessionId.value = null
  context.value = null
  errorMessage.value = ''
  noticeMessage.value = ''
  void router.replace({ query: { ticketId: String(ticketId) } })
  await Promise.all([loadContext(ticketId), loadSessions(ticketId)])
}

async function loadContext(ticketId: number) {
  loadingContext.value = true
  try {
    context.value = await getTicketAiContext(ticketId)
  } catch (error) {
    context.value = null
    errorMessage.value = error instanceof Error ? error.message : '工单上下文加载失败'
  } finally {
    loadingContext.value = false
  }
}

async function loadSessions(ticketId: number) {
  try {
    const page = await listAiSessions({ ticketId, page: 1, size: 30 })
    sessions.value = page.list
  } catch {
    sessions.value = []
  }
}

async function openSession(sessionId: number) {
  if (activeSessionId.value === sessionId) return
  loadingSession.value = true
  errorMessage.value = ''
  try {
    const detail = await getAiSession(sessionId)
    activeSessionId.value = sessionId
    messages.value = detail.messages
    lastAnalysis.value = null
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '会话加载失败'
  } finally {
    loadingSession.value = false
  }
}

async function newSession() {
  if (!selectedId.value) return
  try {
    const session = await createAiSession({ ticketId: selectedId.value })
    sessions.value = [session, ...sessions.value]
    activeSessionId.value = session.id
    messages.value = []
    lastAnalysis.value = null
    noticeMessage.value = '已创建新的 AI 会话'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '会话创建失败'
  }
}

async function analyze(type: Exclude<AiAnalysisType, 'CHAT'>) {
  if (!selectedId.value || analyzing.value) return
  analyzing.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  lastAnalysis.value = null
  try {
    const analysis = await runAnalysis({
      ticketId: selectedId.value,
      type,
      sessionId: activeSessionId.value ?? undefined,
      forceRefresh: true
    })
    lastAnalysis.value = analysis
    if (analysis.status === 'FAILED') {
      errorMessage.value = analysis.errorMessage || 'AI 分析失败，请稍后重试'
    }
    if (selectedId.value) await loadContext(selectedId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'AI 请求失败，请稍后重试'
  } finally {
    analyzing.value = false
  }
}

async function send() {
  const content = question.value.trim()
  if (!content || !selectedId.value || sending.value) return
  sending.value = true
  errorMessage.value = ''
  try {
    let sessionId = activeSessionId.value
    if (!sessionId) {
      const session = await createAiSession({ ticketId: selectedId.value })
      sessions.value = [session, ...sessions.value]
      sessionId = session.id
      activeSessionId.value = session.id
    }
    const result = await askAiSession(sessionId, content)
    messages.value = [...messages.value, result.userMessage, result.assistantMessage]
    lastAnalysis.value = result.analysis
    question.value = ''
    if (selectedId.value) await loadContext(selectedId.value)
    await loadSessions(selectedId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'AI 回复失败，请稍后重试'
  } finally {
    sending.value = false
  }
}

async function decide(suggestion: AiSuggestion, accept: boolean) {
  busySuggestionId.value = suggestion.id
  errorMessage.value = ''
  try {
    if (accept) {
      await acceptSuggestion(suggestion.id)
      noticeMessage.value = '建议已采纳，工单已按既有业务规则更新'
    } else {
      await rejectSuggestion(suggestion.id)
      noticeMessage.value = '建议已拒绝'
    }
    if (selectedId.value) await loadContext(selectedId.value)
    if (lastAnalysis.value) {
      lastAnalysis.value = await getAnalysis(lastAnalysis.value.id)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '操作失败'
  } finally {
    busySuggestionId.value = null
  }
}

function openAnalysis(id: number) {
  void router.push(`/ai/analyses/${id}`)
}

function openTicket(ticketId: number) {
  void router.push(`/tickets/${ticketId}`)
}

function onTicketChange() {
  if (selectedId.value) void selectTicket(selectedId.value)
}

onMounted(loadTicketOptions)
</script>

<template>
  <div class="page ai-workspace">
    <div class="ws-head">
      <div>
        <h2 class="ws-title">AI 工作空间</h2>
        <p class="ws-desc">围绕真实工单生成分析、方案与知识推荐；所有建议均需人工确认后才会写回业务。</p>
      </div>
      <div class="ws-picker">
        <span class="picker-label">关联工单</span>
        <select
          v-model.number="selectedId"
          :disabled="loadingTickets || !tickets.length"
          @change="onTicketChange"
        >
          <option v-for="ticket in tickets" :key="ticket.id" :value="ticket.id">
            {{ ticket.ticketNo }} · {{ ticket.title }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="errorMessage" class="banner banner-error">
      <AppIcon name="alert" :size="15" />
      <span>{{ errorMessage }}</span>
      <button class="banner-x" @click="errorMessage = ''"><AppIcon name="close" :size="13" /></button>
    </div>
    <div v-if="noticeMessage" class="banner banner-ok">
      <AppIcon name="check" :size="15" />
      <span>{{ noticeMessage }}</span>
      <button class="banner-x" @click="noticeMessage = ''"><AppIcon name="close" :size="13" /></button>
    </div>

    <div v-if="loadingTickets" class="ws-state"><LoadingState text="正在加载工单" /></div>
    <div v-else-if="!tickets.length" class="ws-state">
      <EmptyState title="暂无可关联工单" description="先在工单中心创建工单，再回到 AI 工作空间。" />
    </div>

    <div v-else class="ws-grid">
      <!-- 左栏 -->
      <aside class="pane pane-left">
        <section class="card">
          <h3 class="card-title">AI 功能</h3>
          <button
            v-for="item in ANALYSIS_TYPES"
            :key="item.key"
            class="feature"
            :disabled="analyzing || !selectedId"
            @click="analyze(item.key)"
          >
            <span class="feature-icon"><AppIcon :name="item.icon" :size="16" /></span>
            <span class="feature-main">
              <b>{{ item.label }}</b>
              <em>{{ item.desc }}</em>
            </span>
            <AppIcon name="arrowRight" :size="14" />
          </button>
        </section>

        <section class="card grow">
          <div class="card-head">
            <h3 class="card-title">历史会话</h3>
            <button class="btn btn-sm" :disabled="!selectedId" @click="newSession">
              <AppIcon name="plus" :size="13" /> 新建
            </button>
          </div>
          <div v-if="loadingSession" class="mini-state"><LoadingState text="加载会话" /></div>
          <ul v-else-if="sessions.length" class="session-list">
            <li
              v-for="session in sessions"
              :key="session.id"
              :class="{ active: session.id === activeSessionId }"
              @click="openSession(session.id)"
            >
              <b>{{ session.title }}</b>
              <span>{{ session.messageCount }} 条消息 · {{ session.status === 'ACTIVE' ? '进行中' : '已归档' }}</span>
            </li>
          </ul>
          <p v-else class="mini-empty">暂无会话，点击「新建」开始追问或使用上方功能分析。</p>
        </section>
      </aside>

      <!-- 中栏 -->
      <main class="pane pane-center">
        <section class="card chat-card">
          <header class="card-head chat-head">
            <div>
              <h3 class="card-title">{{ activeSession ? activeSession.title : '分析与对话' }}</h3>
              <p class="chat-sub">
                {{ selectedTicket ? `${selectedTicket.ticketNo} · ${selectedTicket.title}` : '未选择工单' }}
              </p>
            </div>
            <span v-if="activeSession" class="tag-soft">
              {{ activeSession.status === 'ACTIVE' ? '会话进行中' : '已归档' }}
            </span>
          </header>

          <div class="thread">
            <div v-if="!messages.length && !lastAnalysis && !analyzing" class="thread-empty">
              <AppIcon name="sparkles" :size="22" />
              <p>选择左侧 AI 功能对当前工单进行分析，或在下方输入问题与 AI 追问。</p>
              <p class="hint">分析结果会结构化展示，分类/优先级等建议必须人工采纳。</p>
            </div>

            <div v-for="message in messages" :key="message.id" class="bubble" :class="message.role.toLowerCase()">
              <span class="bubble-role">{{ message.role === 'USER' ? '我' : 'AI' }}</span>
              <div class="bubble-body">
                <p>{{ message.content }}</p>
                <em>{{ formatDateTime(message.createdAt) }}</em>
              </div>
            </div>

            <div v-if="analyzing" class="thinking">
              <span class="dot" /><span class="dot" /><span class="dot" />
              <span>AI 正在结合工单上下文分析…</span>
            </div>

            <AiResultPanel
              v-if="lastAnalysis"
              :analysis="lastAnalysis"
              :busy-id="busySuggestionId"
              @decide="decide"
              @open-ticket="openTicket"
            />

            <div v-if="sending" class="thinking">
              <span class="dot" /><span class="dot" /><span class="dot" />
              <span>AI 正在生成回复…</span>
            </div>
          </div>

          <div class="composer">
            <textarea
              v-model="question"
              rows="2"
              placeholder="针对当前工单提问，例如：这个故障最可能的原因是什么？（Ctrl + Enter 发送）"
              :disabled="sending || !selectedId"
              @keydown.ctrl.enter.prevent="send"
            />
            <button class="btn btn-primary send" :disabled="sending || !selectedId || !question.trim()" @click="send">
              <AppIcon name="arrowRight" :size="15" />
              发送
            </button>
          </div>
        </section>
      </main>

      <!-- 右栏 -->
      <aside class="pane pane-right">
        <div v-if="loadingContext" class="card"><LoadingState text="加载工单上下文" /></div>
        <template v-else-if="context">
          <section class="card">
            <h3 class="card-title">当前工单</h3>
            <button class="ticket-link" @click="openTicket(context.ticket.id)">
              {{ context.ticket.ticketNo }}
              <AppIcon name="arrowRight" :size="13" />
            </button>
            <p class="ticket-title">{{ context.ticket.title }}</p>
            <div class="ticket-meta">
              <StatusTag :status="context.ticket.status" />
              <PriorityTag :priority="context.ticket.priority" />
            </div>
            <div class="kv">
              <div><span>创建人</span><b>{{ context.ticket.creator }}</b></div>
              <div><span>处理人</span><b>{{ context.ticket.assignee || '未分配' }}</b></div>
              <div><span>SLA</span><SlaBadge :ticket="context.ticket" /></div>
            </div>
          </section>

          <section class="card">
            <h3 class="card-title">待确认建议</h3>
            <div v-if="context.pendingSuggestions.length" class="pending-list">
              <div v-for="s in context.pendingSuggestions" :key="s.id" class="pending-item">
                <p>{{ s.summary }}</p>
                <div class="pending-act">
                  <span class="pending-state">{{ SUGGESTION_STATUS_LABEL[s.status] }}</span>
                  <button class="btn btn-sm" :disabled="busySuggestionId === s.id" @click="decide(s, true)">采纳</button>
                  <button class="btn btn-sm" :disabled="busySuggestionId === s.id" @click="decide(s, false)">拒绝</button>
                </div>
              </div>
            </div>
            <p v-else class="mini-empty">暂无待确认建议</p>
          </section>

          <section class="card">
            <h3 class="card-title">最近分析</h3>
            <ul v-if="context.latestAnalyses.length" class="brief-list">
              <li v-for="item in context.latestAnalyses" :key="item.id" @click="openAnalysis(item.id)">
                <b>{{ item.type }}</b>
                <span>{{ item.summary }}</span>
                <em>{{ formatDateTime(item.createdAt) }}</em>
              </li>
            </ul>
            <p v-else class="mini-empty">尚未产生分析记录</p>
          </section>

          <section class="card">
            <h3 class="card-title">推荐知识</h3>
            <ul v-if="context.knowledge.length" class="kb-list">
              <li v-for="item in context.knowledge" :key="item.articleId">
                <b>{{ item.title }}</b>
                <span>{{ item.reason }}</span>
                <em>相关度 {{ item.relevance }}%</em>
              </li>
            </ul>
            <p v-else class="mini-empty">没有匹配的知识条目</p>
          </section>

          <section v-if="isAdmin" class="card">
            <h3 class="card-title">调度快照</h3>
            <div v-if="context.dispatch">
              <p class="mini-note">来自第四阶段规则调度，最终分配需管理员确认。</p>
              <ul class="dispatch-list">
                <li v-for="c in context.dispatch.candidates.slice(0, 3)" :key="c.agentId">
                  <b>{{ c.name }}</b>
                  <span class="num">{{ c.score.toFixed(1) }} 分</span>
                  <em>负载 {{ Math.round(c.loadRate * 100) }}% · {{ c.availability }}</em>
                </li>
              </ul>
            </div>
            <p v-else class="mini-empty">暂无调度快照，先执行一次「调度辅助」分析。</p>
          </section>
        </template>
        <div v-else class="card">
          <p class="mini-empty">该工单暂无可展示上下文（可能无访问权限）。</p>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.ai-workspace {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ws-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.ws-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-1);
}
.ws-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
}
.ws-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 6px 10px;
}
.picker-label {
  font-size: 11px;
  color: var(--ink-4);
}
.ws-picker select {
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--ink-1);
  min-width: 260px;
  max-width: 380px;
  outline: none;
}
.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 12px;
}
.banner-error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
}
.banner-ok {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #047857;
}
.banner-x {
  margin-left: auto;
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  display: inline-flex;
}
.ws-state {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 24px;
}
.ws-grid {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr) 300px;
  gap: 14px;
  align-items: stretch;
  min-height: 560px;
}
.pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow-card);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.card.grow {
  flex: 1;
  min-height: 220px;
}
.card-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
  letter-spacing: 0.02em;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.feature {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  border: 1px solid var(--line-soft);
  background: #fbfcfe;
  border-radius: 9px;
  padding: 9px 10px;
  cursor: pointer;
  color: var(--ink-3);
  transition: all 0.15s ease;
}
.feature:hover:not(:disabled) {
  border-color: #c7d2fe;
  background: var(--brand-weak);
}
.feature:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.feature-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--brand-weak);
  color: var(--brand);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.feature-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.feature-main b {
  font-size: 12px;
  color: var(--ink-1);
}
.feature-main em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-4);
}
.session-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
  max-height: 420px;
}
.session-list li {
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  cursor: pointer;
}
.session-list li.active {
  border-color: #c7d2fe;
  background: var(--brand-weak);
}
.session-list b {
  font-size: 12px;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-list span {
  font-size: 10px;
  color: var(--ink-4);
}
.mini-empty {
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.7;
}
.mini-state {
  padding: 12px 0;
}
.mini-note {
  font-size: 11px;
  color: var(--ink-4);
  margin-bottom: 6px;
}
.chat-card {
  flex: 1;
  padding: 0;
  overflow: hidden;
}
.chat-head {
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}
.chat-sub {
  margin-top: 3px;
  font-size: 11px;
  color: var(--ink-4);
}
.tag-soft {
  font-size: 10px;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--brand-weak);
  color: var(--brand);
  white-space: nowrap;
}
.thread {
  flex: 1;
  overflow: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: linear-gradient(180deg, #fcfdff 0%, #ffffff 100%);
  min-height: 320px;
  max-height: 620px;
}
.thread-empty {
  margin: auto;
  text-align: center;
  color: var(--ink-3);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  max-width: 340px;
  font-size: 12px;
  line-height: 1.7;
}
.thread-empty svg {
  color: var(--brand);
}
.thread-empty .hint {
  color: var(--ink-4);
  font-size: 11px;
}
.bubble {
  display: flex;
  gap: 8px;
  max-width: 92%;
}
.bubble.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.bubble-role {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  flex: none;
  background: #f1f5f9;
  color: var(--ink-3);
}
.bubble.user .bubble-role {
  background: var(--brand);
  color: #fff;
}
.bubble-body {
  background: #f8fafc;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  padding: 9px 11px;
}
.bubble.user .bubble-body {
  background: var(--brand-weak);
  border-color: #dfe4ff;
}
.bubble-body p {
  font-size: 12.5px;
  color: var(--ink-2);
  line-height: 1.7;
  white-space: pre-wrap;
}
.bubble-body em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-4);
  display: block;
  margin-top: 4px;
}
.thinking {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--ink-3);
  background: #f8fafc;
  border-radius: 9px;
  padding: 8px 11px;
  align-self: flex-start;
}
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand);
  animation: blink 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.15s;
}
.dot:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; }
  40% { opacity: 1; }
}
.composer {
  border-top: 1px solid var(--line);
  padding: 12px 14px;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  background: #fff;
}
.composer textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 8px 10px;
  font-size: 12.5px;
  font-family: inherit;
  line-height: 1.6;
  color: var(--ink-1);
  outline: none;
}
.composer textarea:focus {
  border-color: #c7d2fe;
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.08);
}
.send {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 34px;
}
.ticket-link {
  border: none;
  background: none;
  padding: 0;
  color: var(--brand);
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  align-self: flex-start;
}
.ticket-title {
  font-size: 13px;
  color: var(--ink-1);
  line-height: 1.6;
}
.ticket-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.kv {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-top: 1px dashed var(--line);
  padding-top: 8px;
}
.kv > div {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
}
.kv span {
  color: var(--ink-4);
}
.kv b {
  color: var(--ink-2);
  font-weight: 500;
}
.pending-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pending-item {
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pending-item p {
  font-size: 11.5px;
  color: var(--ink-2);
  line-height: 1.6;
}
.pending-act {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pending-state {
  font-size: 10px;
  color: #b45309;
  margin-right: auto;
}
.brief-list,
.kb-list,
.dispatch-list {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.brief-list li,
.kb-list li,
.dispatch-list li {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  padding: 7px 9px;
  cursor: pointer;
}
.brief-list b,
.kb-list b,
.dispatch-list b {
  font-size: 11.5px;
  color: var(--ink-1);
}
.brief-list span,
.kb-list span {
  font-size: 11px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.brief-list em,
.kb-list em,
.dispatch-list em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-4);
}
.dispatch-list li {
  flex-direction: row;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  cursor: default;
}
.dispatch-list .num {
  color: var(--brand);
  font-size: 11px;
}
</style>
