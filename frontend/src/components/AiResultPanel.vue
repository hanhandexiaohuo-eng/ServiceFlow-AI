<script setup lang="ts">
/** AI 结构化结果渲染：按分析类型展示字段，不解析自由文本 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import PriorityTag from './PriorityTag.vue'
import {
  ANALYSIS_STATUS_LABEL,
  ANALYSIS_TYPE_LABEL,
  SUGGESTION_STATUS_LABEL,
  SUGGESTION_TYPE_LABEL,
  percent,
  type DispatchAssistResult,
  type PriorityResult,
  type SolutionResult,
  type TicketAnalysisResult
} from '../constants/ai'
import { formatDateTime } from '../constants/ticket'
import type { AiAnalysis, AiSuggestion } from '../api/ai'

const props = defineProps<{ analysis: AiAnalysis; busyId?: number | null }>()
const emit = defineEmits<{
  (e: 'decide', suggestion: AiSuggestion, accept: boolean): void
  (e: 'open-ticket', ticketId: number): void
}>()

const typeLabel = computed(() => ANALYSIS_TYPE_LABEL[props.analysis.type] ?? props.analysis.type)
const statusLabel = computed(
  () => ANALYSIS_STATUS_LABEL[props.analysis.status] ?? props.analysis.status
)
const ticketAnalysis = computed(
  () => (props.analysis.result ?? {}) as unknown as TicketAnalysisResult
)
const priorityResult = computed(() => (props.analysis.result ?? {}) as unknown as PriorityResult)
const solutionResult = computed(() => (props.analysis.result ?? {}) as unknown as SolutionResult)
const dispatchAssist = computed(() => (props.analysis.result ?? {}) as unknown as DispatchAssistResult)
const dispatchRule = computed(() => props.analysis.dispatch)

function agentName(agentId: number): string {
  const candidate = dispatchRule.value?.candidates.find((item) => item.agentId === agentId)
  return candidate ? candidate.name : `人员 #${agentId}`
}
</script>

<template>
  <article class="result-card">
    <header class="r-head">
      <div class="r-title">
        <span class="r-icon"><AppIcon name="sparkles" :size="15" /></span>
        <div>
          <h3>{{ typeLabel }}</h3>
          <p class="r-sub">
            工单
            <button class="link" @click="emit('open-ticket', analysis.ticketId)">
              {{ analysis.ticketNo }}
            </button>
            · {{ formatDateTime(analysis.createdAt) }}
          </p>
        </div>
      </div>
      <span class="state" :class="'is-' + analysis.status">{{ statusLabel }}</span>
    </header>

    <p v-if="analysis.status === 'FAILED'" class="fail">
      <AppIcon name="alert" :size="15" />
      <span>
        {{ analysis.errorMessage || 'AI 分析失败，请稍后重试' }}
        <em v-if="analysis.errorCode">（{{ analysis.errorCode }}）</em>
      </span>
    </p>

    <template v-else>
      <!-- 智能分析 -->
      <template v-if="analysis.type === 'TICKET_ANALYSIS'">
        <div class="fact-row">
          <div class="fact">
            <span>推荐分类</span>
            <b>{{ ticketAnalysis.recommendedType }}</b>
          </div>
          <div class="fact">
            <span>问题类型</span>
            <b>{{ ticketAnalysis.problemCategory }}</b>
          </div>
          <div class="fact">
            <span>推荐优先级</span>
            <PriorityTag :priority="ticketAnalysis.recommendedPriority" />
          </div>
          <div class="fact">
            <span>置信度</span>
            <b class="num">{{ percent(ticketAnalysis.confidence) }}</b>
          </div>
        </div>
        <section class="block">
          <h4>问题摘要</h4>
          <p>{{ ticketAnalysis.summary }}</p>
        </section>
        <div class="two-col">
          <section class="block">
            <h4>关键信息</h4>
            <ul>
              <li v-for="item in ticketAnalysis.keyFacts" :key="item">{{ item }}</li>
            </ul>
          </section>
          <section class="block">
            <h4>推荐理由</h4>
            <ul>
              <li v-for="item in ticketAnalysis.reasons" :key="item">{{ item }}</li>
            </ul>
          </section>
        </div>
        <div class="two-col">
          <section class="block">
            <h4>涉及技术领域</h4>
            <div class="chips">
              <span v-for="item in ticketAnalysis.technicalDomains" :key="item" class="chip">{{ item }}</span>
              <span v-if="!ticketAnalysis.technicalDomains?.length" class="muted">-</span>
            </div>
            <h4 class="mt">所需技能</h4>
            <div class="chips">
              <span v-for="item in ticketAnalysis.requiredSkills" :key="item" class="chip">{{ item }}</span>
              <span v-if="!ticketAnalysis.requiredSkills?.length" class="muted">-</span>
            </div>
          </section>
          <section class="block">
            <h4>影响范围</h4>
            <p>{{ ticketAnalysis.impactScope }}</p>
            <h4 class="mt">关键词</h4>
            <div class="chips">
              <span v-for="item in ticketAnalysis.keywords" :key="item" class="chip">{{ item }}</span>
            </div>
          </section>
        </div>
      </template>

      <!-- 优先级建议 -->
      <template v-else-if="analysis.type === 'PRIORITY'">
        <div class="fact-row">
          <div class="fact">
            <span>推荐优先级</span>
            <PriorityTag :priority="priorityResult.recommendedPriority" />
          </div>
          <div class="fact">
            <span>建议响应时限</span>
            <b class="num">{{ priorityResult.suggestedResponseHours }} 小时</b>
          </div>
          <div class="fact">
            <span>置信度</span>
            <b class="num">{{ percent(priorityResult.confidence) }}</b>
          </div>
        </div>
        <section class="block">
          <h4>判断依据</h4>
          <ul>
            <li v-for="item in priorityResult.reasons" :key="item">{{ item }}</li>
          </ul>
        </section>
        <div class="two-col">
          <section class="block">
            <h4>风险说明</h4>
            <ul>
              <li v-for="item in priorityResult.risks" :key="item">{{ item }}</li>
              <li v-if="!priorityResult.risks?.length" class="muted">未识别额外风险</li>
            </ul>
          </section>
          <section class="block">
            <h4>SLA 影响分析</h4>
            <p>{{ priorityResult.slaImpact }}</p>
          </section>
        </div>
        <p class="note">仅作为建议，不会直接覆盖系统 SLA 计算结果；采纳后由后端重算截止时间。</p>
      </template>

      <!-- 处理方案 -->
      <template v-else-if="analysis.type === 'SOLUTION'">
        <section class="block">
          <h4>问题分析</h4>
          <p>{{ solutionResult.problemAnalysis }}</p>
        </section>
        <div class="two-col">
          <section class="block">
            <h4>可能原因</h4>
            <ol>
              <li v-for="item in solutionResult.possibleCauses" :key="item">{{ item }}</li>
            </ol>
          </section>
          <section class="block">
            <h4>排查步骤</h4>
            <ol>
              <li v-for="item in solutionResult.diagnosticSteps" :key="item">{{ item }}</li>
            </ol>
          </section>
        </div>
        <section class="block">
          <h4>推荐解决方案</h4>
          <ol>
            <li v-for="item in solutionResult.recommendedSolution" :key="item">{{ item }}</li>
          </ol>
        </section>
        <div class="two-col">
          <section class="block">
            <h4>所需技术 / 工具</h4>
            <div class="chips">
              <span v-for="item in solutionResult.tools" :key="item" class="chip">{{ item }}</span>
              <span v-if="!solutionResult.tools?.length" class="muted">-</span>
            </div>
            <h4 class="mt">潜在风险</h4>
            <ul>
              <li v-for="item in solutionResult.risks" :key="item">{{ item }}</li>
              <li v-if="!solutionResult.risks?.length" class="muted">未识别额外风险</li>
            </ul>
          </section>
          <section class="block">
            <h4>验证方式</h4>
            <ol>
              <li v-for="item in solutionResult.verification" :key="item">{{ item }}</li>
            </ol>
            <h4 class="mt">适用条件</h4>
            <ul>
              <li v-for="item in solutionResult.preconditions" :key="item">{{ item }}</li>
              <li v-if="!solutionResult.preconditions?.length" class="muted">-</li>
            </ul>
          </section>
        </div>
        <p v-if="solutionResult.missingInformation?.length" class="warn">
          <AppIcon name="alert" :size="14" />
          信息不足，需要补充：{{ solutionResult.missingInformation.join('；') }}
        </p>
        <p v-if="solutionResult.escalationRecommended" class="escalate">
          <AppIcon name="route" :size="14" />
          建议升级处理：{{ solutionResult.escalationReason }}
        </p>
      </template>

      <!-- 调度辅助 -->
      <template v-else-if="analysis.type === 'DISPATCH'">
        <section class="block">
          <h4>AI 调度分析</h4>
          <p>{{ dispatchAssist.summary }}</p>
        </section>
        <div class="dispatch-grid">
          <section v-for="item in dispatchAssist.candidates" :key="item.agentId" class="dispatch-item">
            <header>
              <b>{{ agentName(item.agentId) }}</b>
              <span v-if="item.agentId === dispatchAssist.recommendedAgentId" class="pick">AI 推荐</span>
            </header>
            <p class="fit">{{ item.fit }}</p>
            <p v-if="item.advantages?.length" class="mini"><em>优势</em>{{ item.advantages.join('；') }}</p>
            <p v-if="item.gaps?.length" class="mini"><em>不足</em>{{ item.gaps.join('；') }}</p>
            <p v-if="item.risk" class="mini risk"><em>风险</em>{{ item.risk }}</p>
          </section>
        </div>
        <div v-if="dispatchRule" class="block">
          <h4>规则调度候选（第四阶段结果，最终分配需管理员确认）</h4>
          <ul class="rule-list">
            <li v-for="c in dispatchRule.candidates" :key="c.agentId">
              <b>{{ c.name }}</b>
              <span class="num">{{ c.score.toFixed(1) }} 分</span>
              <span class="muted">负载 {{ Math.round(c.loadRate * 100) }}% · {{ c.availability }}</span>
            </li>
          </ul>
        </div>
      </template>

      <section v-if="analysis.knowledge.length" class="block">
        <h4>推荐知识（真实知识条目）</h4>
        <ul class="knowledge">
          <li v-for="item in analysis.knowledge" :key="item.articleId">
            <div class="k-head">
              <b>{{ item.title }}</b>
              <span class="rel num">{{ item.relevance }}%</span>
            </div>
            <p>{{ item.solutionPreview }}</p>
            <p class="k-reason">{{ item.reason }}</p>
          </li>
        </ul>
      </section>

      <section v-if="analysis.suggestions.length" class="suggestions">
        <h4>AI 建议（需人工确认）</h4>
        <div v-for="s in analysis.suggestions" :key="s.id" class="s-item">
          <div class="s-main">
            <span class="s-type">{{ SUGGESTION_TYPE_LABEL[s.type] ?? s.type }}</span>
            <p>{{ s.summary }}</p>
            <p v-if="s.executionNote" class="s-note">{{ s.executionNote }}</p>
            <p v-else-if="s.decisionNote" class="s-note">备注：{{ s.decisionNote }}</p>
          </div>
          <div class="s-act">
            <span class="s-state" :class="'ss-' + s.status">
              {{ SUGGESTION_STATUS_LABEL[s.status] ?? s.status }}
            </span>
            <button
              v-if="s.status === 'PENDING'"
              class="btn btn-sm"
              :disabled="busyId === s.id"
              @click="emit('decide', s, true)"
            >
              采纳
            </button>
            <button
              v-if="s.status === 'PENDING'"
              class="btn btn-sm"
              :disabled="busyId === s.id"
              @click="emit('decide', s, false)"
            >
              拒绝
            </button>
          </div>
        </div>
      </section>

      <footer class="r-foot">
        <span v-if="analysis.model">模型 {{ analysis.model }}</span>
        <span v-if="analysis.promptVersion">Prompt {{ analysis.promptVersion }}</span>
        <span v-if="analysis.latencyMs !== null" class="num">耗时 {{ analysis.latencyMs }} ms</span>
        <span>发起人 {{ analysis.requestedByName || '-' }}</span>
      </footer>
    </template>
  </article>
</template>

<style scoped>
.result-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: var(--shadow-card);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.r-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.r-title {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.r-icon {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-weak);
  color: var(--brand);
  flex: none;
}
.r-head h3 {
  font-size: 15px;
  font-weight: 600;
}
.r-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-4);
}
.link {
  border: none;
  background: none;
  color: var(--brand);
  cursor: pointer;
  font-size: inherit;
  padding: 0;
}
.state {
  height: 22px;
  display: inline-flex;
  align-items: center;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 11px;
  background: #f8fafc;
  color: var(--ink-3);
  border: 1px solid var(--line);
  white-space: nowrap;
}
.state.is-SUCCESS {
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
}
.state.is-FAILED {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}
.state.is-PENDING {
  background: #fffbeb;
  color: #b45309;
  border-color: #fde68a;
}
.fail {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  font-size: 13px;
}
.fail em {
  font-style: normal;
  opacity: 0.75;
}
.fact-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}
.fact {
  background: #f8fafc;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  padding: 9px 11px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.fact > span {
  font-size: 11px;
  color: var(--ink-4);
}
.fact b {
  font-size: 14px;
  color: var(--ink-1);
}
.block {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.block h4 {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
}
.block p {
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.7;
  white-space: pre-wrap;
}
.block ul,
.block ol {
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.65;
}
.mt {
  margin-top: 8px;
}
.two-col {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  height: 22px;
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  border-radius: 6px;
  background: var(--brand-weak);
  color: #4338ca;
  font-size: 11px;
}
.note {
  font-size: 12px;
  color: var(--ink-4);
  background: #f8fafc;
  border-radius: 8px;
  padding: 8px 10px;
}
.warn,
.escalate {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  border-radius: 9px;
  padding: 9px 11px;
}
.warn {
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #b45309;
}
.escalate {
  background: #eef2ff;
  border: 1px solid #c7d2fe;
  color: #4338ca;
}
.dispatch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}
.dispatch-item {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 11px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dispatch-item header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}
.pick {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--brand-weak);
  color: var(--brand);
}
.fit {
  font-size: 12px;
  color: var(--ink-2);
}
.mini {
  font-size: 11px;
  color: var(--ink-3);
  line-height: 1.6;
}
.mini em {
  font-style: normal;
  color: var(--ink-4);
  margin-right: 4px;
}
.mini.risk {
  color: #b45309;
}
.rule-list {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rule-list li {
  display: flex;
  gap: 10px;
  align-items: baseline;
  font-size: 12px;
  color: var(--ink-2);
}
.knowledge {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.knowledge li {
  border: 1px solid var(--line-soft);
  border-radius: 9px;
  padding: 9px 11px;
  background: #fafbfd;
}
.k-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
}
.rel {
  color: var(--brand);
  font-size: 11px;
}
.knowledge p {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}
.k-reason {
  color: var(--ink-4);
  font-size: 11px;
}
.suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-top: 1px dashed var(--line);
  padding-top: 12px;
}
.suggestions h4 {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
}
.s-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  padding: 9px 11px;
  border-radius: 9px;
  background: #fafbfd;
  border: 1px solid var(--line-soft);
}
.s-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.s-type {
  font-size: 10px;
  color: var(--brand);
  font-weight: 600;
}
.s-main p {
  font-size: 12px;
  color: var(--ink-2);
}
.s-note {
  color: var(--ink-4);
  font-size: 11px;
}
.s-act {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.s-state {
  font-size: 11px;
  color: var(--ink-4);
}
.s-state.ss-EXECUTED {
  color: #047857;
}
.s-state.ss-ACCEPTED {
  color: #4338ca;
}
.s-state.ss-REJECTED {
  color: #b91c1c;
}
.r-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 11px;
  color: var(--ink-4);
  border-top: 1px solid var(--line-soft);
  padding-top: 10px;
}
</style>
