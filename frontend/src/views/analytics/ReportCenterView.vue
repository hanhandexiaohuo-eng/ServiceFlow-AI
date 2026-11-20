<script setup lang="ts">
/**
 * 综合报表中心：页面 JSON 与 CSV 导出由后端同一个 build 方法生成，
 * 前端只负责传相同的筛选条件，保证两边数字一致。
 */
import { computed, onMounted, ref } from 'vue'
import AppIcon from '../../components/AppIcon.vue'
import DataTable from '../../components/DataTable.vue'
import DateRangeFilter from '../../components/DateRangeFilter.vue'
import EmptyState from '../../components/EmptyState.vue'
import LoadingState from '../../components/LoadingState.vue'
import {
  PRIORITY_LABEL,
  PRIORITY_OPTIONS,
  TYPE_LABEL,
  TYPE_OPTIONS,
  formatDateTime,
} from '../../constants/ticket'
import { useAnalyticsRange, type DateRange } from '../../composables/useAnalyticsRange'
import {
  exportReport,
  fetchAgentPerformance,
  fetchReport,
  type AgentPerformanceItem,
  type AnalyticsReport,
  type AnalyticsReportType,
  type ReportQueryParams,
} from '../../api/analytics'
import type { TableColumn } from '../../types/ui'

interface ReportTypeOption {
  value: AnalyticsReportType
  label: string
}

const REPORT_TYPES: ReportTypeOption[] = [
  { value: 'TICKET_OPERATIONS', label: '工单运营汇总' },
  { value: 'SLA_EFFICIENCY', label: 'SLA 效率汇总' },
  { value: 'AGENT_PERFORMANCE', label: '人员绩效明细' },
  { value: 'DISPATCH_EFFECT', label: '智能调度效果' },
  { value: 'AI_EFFECT', label: 'AI 效果汇总' },
]

/** 明细行只保留字符串，渲染前统一做好 null / 布尔转换 */
type TextRow = Record<string, string>

const { rangeParams, setCustom } = useAnalyticsRange()

const rangeModel = computed<DateRange>({
  get: () => rangeParams.value,
  set: (next) => setCustom(next),
})

const reportType = ref<AnalyticsReportType>('TICKET_OPERATIONS')
const ticketType = ref<string>('')
const priority = ref<string>('')
/** 人员下拉取值（agentId 字符串形式，空串表示全部） */
const assigneeSelect = ref<string>('')
/** 人员列表不可用时的兜底手输 */
const assigneeInput = ref<string>('')

const agents = ref<AgentPerformanceItem[]>([])
const agentsLoading = ref(false)
const agentsFailed = ref(false)

const report = ref<AnalyticsReport | null>(null)
const generating = ref(false)
const exporting = ref(false)
const errorMessage = ref('')
const exportHint = ref('')

const assigneeId = computed<number | null>(() => {
  if (agentsFailed.value) {
    const parsed = Number(assigneeInput.value.trim())
    return Number.isInteger(parsed) && parsed > 0 ? parsed : null
  }
  if (!assigneeSelect.value) return null
  const parsed = Number(assigneeSelect.value)
  return Number.isFinite(parsed) ? parsed : null
})

function buildParams(): ReportQueryParams {
  return {
    type: reportType.value,
    startDate: rangeParams.value.startDate,
    endDate: rangeParams.value.endDate,
    ticketType: ticketType.value || undefined,
    priority: priority.value || undefined,
    assigneeId: assigneeId.value ?? undefined,
  }
}

async function loadAgents(): Promise<void> {
  agentsLoading.value = true
  try {
    const data = await fetchAgentPerformance({})
    agents.value = data.agents
  } catch {
    // 拉不到人员列表时不编造数据，改为手输 ID 或留空
    agents.value = []
    agentsFailed.value = true
  } finally {
    agentsLoading.value = false
  }
}

async function generate(): Promise<void> {
  generating.value = true
  errorMessage.value = ''
  exportHint.value = ''
  try {
    report.value = await fetchReport(buildParams())
  } catch (error) {
    report.value = null
    errorMessage.value = error instanceof Error ? error.message : '报表生成失败'
  } finally {
    generating.value = false
  }
}

async function exportCsv(): Promise<void> {
  exporting.value = true
  errorMessage.value = ''
  exportHint.value = ''
  try {
    await exportReport(buildParams())
    exportHint.value = '已按当前筛选条件导出'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '报表导出失败'
  } finally {
    exporting.value = false
  }
}

onMounted(loadAgents)

/* ---------------- 结果区展示 ---------------- */

const reportName = computed(() => report.value?.reportName ?? '')
const rangeLabel = computed(() => report.value?.range.label ?? '')
const generatedAtText = computed(() => formatDateTime(report.value?.generatedAt))
const reportTypeLabel = computed(() => {
  const type = report.value?.type
  if (!type) return ''
  return REPORT_TYPES.find((item) => item.value === type)?.label ?? type
})

const metrics = computed(() => report.value?.metrics ?? [])
const notes = computed<string[]>(() => report.value?.notes ?? [])
const detailRowCount = computed(() => report.value?.detailRows.length ?? 0)

const filterTags = computed<{ label: string; value: string }[]>(() => {
  const data = report.value
  if (!data) return []
  const { ticketType: typeValue, priority: priorityValue, assigneeId: idValue } = data.filters
  const assignee = data.filters.assigneeName ?? (idValue === null ? '全部' : String(idValue))
  return [
    {
      label: '工单类型',
      value: typeValue ? TYPE_LABEL[typeValue] ?? typeValue : '全部',
    },
    {
      label: '优先级',
      value: priorityValue ? PRIORITY_LABEL[priorityValue] ?? priorityValue : '全部',
    },
    { label: '处理人员', value: assignee },
  ]
})

const detailColumns = computed<TableColumn[]>(() =>
  (report.value?.detailColumns ?? []).map((column) => ({
    key: column.key,
    label: column.label,
    nowrap: true,
  }))
)

function toCellText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number' || typeof value === 'string') return String(value)
  return '-'
}

const detailRows = computed<TextRow[]>(() => {
  const data = report.value
  if (!data) return []
  return data.detailRows.map((row, index) => {
    const text: TextRow = { __rowKey: String(index + 1) }
    for (const column of data.detailColumns) {
      text[column.key] = toCellText(row[column.key])
    }
    return text
  })
})

function formatMetricValue(value: number | null): string {
  if (value === null) return '-'
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}
</script>

<template>
  <div class="page">
    <section class="page-head">
      <h1>综合报表中心</h1>
      <p class="head-sub">
        导出与页面使用同一筛选条件、同一计算逻辑；CSV 为 UTF-8 BOM 编码，Excel 可直接打开。
        未生成报表前不会展示任何示例数据。
      </p>
    </section>

    <DateRangeFilter v-model="rangeModel" label="统计范围" />

    <section class="panel">
      <div class="form-grid">
        <label class="field">
          <span>报表类型</span>
          <select v-model="reportType">
            <option v-for="item in REPORT_TYPES" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>

        <label class="field">
          <span>工单类型</span>
          <select v-model="ticketType">
            <option value="">全部类型</option>
            <option v-for="item in TYPE_OPTIONS" :key="item" :value="item">
              {{ TYPE_LABEL[item] }}
            </option>
          </select>
        </label>

        <label class="field">
          <span>优先级</span>
          <select v-model="priority">
            <option value="">全部优先级</option>
            <option v-for="item in PRIORITY_OPTIONS" :key="item" :value="item">
              {{ PRIORITY_LABEL[item] }}
            </option>
          </select>
        </label>

        <label class="field">
          <span>处理人员</span>
          <select v-if="!agentsFailed" v-model="assigneeSelect" :disabled="agentsLoading">
            <option value="">全部人员</option>
            <option v-for="agent in agents" :key="agent.agentId" :value="String(agent.agentId)">
              {{ agent.agentName }}{{ agent.active ? '' : '（已禁用）' }}
            </option>
          </select>
          <input v-else v-model="assigneeInput" type="number" min="1" placeholder="处理人 ID，可留空" />
          <span v-if="agentsFailed" class="field-hint warn">
            人员列表加载失败，可手动输入处理人 ID 或留空表示全部
          </span>
          <span v-else class="field-hint">
            {{ assigneeId === null ? '当前为全部人员' : `已选处理人 ID ${assigneeId}` }}
          </span>
        </label>
      </div>

      <div class="form-actions">
        <button class="btn btn-primary" :disabled="generating" @click="generate">
          <AppIcon name="chart" :size="14" />{{ generating ? '生成中…' : '生成报表' }}
        </button>
        <button class="btn" :disabled="exporting || generating" @click="exportCsv">
          <AppIcon name="logs" :size="14" />{{ exporting ? '导出中…' : '导出 CSV' }}
        </button>
        <span v-if="exportHint" class="export-hint">
          <AppIcon name="check" :size="13" />{{ exportHint }}
        </span>
      </div>
    </section>

    <p v-if="errorMessage" class="alert">
      <AppIcon name="alert" :size="15" />{{ errorMessage }}
    </p>

    <section v-if="generating" class="panel">
      <LoadingState text="正在生成报表" :height="180" />
    </section>

    <template v-else-if="report">
      <section class="panel report-head">
        <div class="report-title">
          <h2>{{ reportName }}</h2>
          <p class="report-sub">
            {{ reportTypeLabel }}
            <span class="sep">·</span>
            生成时间 <span class="num">{{ generatedAtText }}</span>
            <span class="sep">·</span>
            统计范围 <span class="num">{{ rangeLabel }}</span>
          </p>
        </div>
        <div class="filter-tags">
          <span v-for="tag in filterTags" :key="tag.label" class="filter-tag">
            {{ tag.label }}：<b>{{ tag.value }}</b>
          </span>
        </div>
      </section>

      <section class="panel">
        <header class="panel-head">
          <h2>核心指标</h2>
          <span class="meta">共 {{ metrics.length }} 项</span>
        </header>
        <div v-if="metrics.length" class="metrics">
          <div v-for="metric in metrics" :key="metric.key" class="metric">
            <span class="metric-label">{{ metric.label }}</span>
            <span class="metric-value num">{{ formatMetricValue(metric.value) }}</span>
            <span class="metric-formula">{{ metric.formula }}</span>
          </div>
        </div>
        <EmptyState v-else compact icon="chart" title="该报表没有指标数据" />
      </section>

      <section class="panel flush">
        <header class="panel-head">
          <h2>明细数据</h2>
          <span class="meta">共 <b class="num">{{ detailRowCount }}</b> 行</span>
        </header>
        <DataTable
          :columns="detailColumns"
          :rows="detailRows"
          row-key="__rowKey"
          dense
          empty-text="当前筛选条件下没有明细数据"
        />
      </section>

      <section v-if="notes.length" class="panel notes-panel">
        <h2 class="section-title">口径说明</h2>
        <ul class="notes">
          <li v-for="(note, index) in notes" :key="index">{{ note }}</li>
        </ul>
      </section>
    </template>

    <section v-else-if="!errorMessage" class="panel">
      <EmptyState
        icon="chart"
        title="尚未生成报表"
        desc="选择报表类型与筛选条件后点击「生成报表」；点击「导出 CSV」可直接下载当前筛选条件下的报表。"
      />
    </section>
  </div>
</template>

<style scoped>
.page-head h1 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink-1);
}
.head-sub {
  margin-top: 4px;
  max-width: 980px;
  font-size: 12px;
  color: var(--ink-4);
  line-height: 1.7;
}

.panel {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: 16px 18px;
  min-width: 0;
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

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 14px;
}
.field select,
.field input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctrl);
  background: var(--panel);
  color: var(--ink-1);
  font-size: 13px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.field select:focus,
.field input:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.field-hint {
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.6;
}
.field-hint.warn {
  color: #b45309;
}

.form-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-soft);
}
.export-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #047857;
}

.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 20px;
  flex-wrap: wrap;
}
.report-title h2 {
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
}
.report-sub {
  margin-top: 5px;
  font-size: 12px;
  color: var(--ink-4);
}
.sep {
  margin: 0 5px;
  color: #cbd5e1;
}
.filter-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.filter-tag {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: #f8fafc;
  font-size: 12px;
  color: var(--ink-3);
}
.filter-tag b {
  margin-left: 3px;
  color: var(--ink-1);
  font-weight: 600;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.panel-head h2 {
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

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  background: #fafbfd;
  min-width: 0;
}
.metric-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-3);
}
.metric-value {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--ink-1);
}
.metric-formula {
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.6;
}

.panel.flush {
  padding: 0;
  overflow: hidden;
}
.panel.flush .panel-head {
  margin-bottom: 0;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line-soft);
}

.notes-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.notes {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.notes li {
  position: relative;
  padding-left: 14px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.7;
}
.notes li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 8px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #cbd5e1;
}
</style>
