<script setup lang="ts">
/** AI 模型治理：仅管理员；供应商与密钥写操作需超级管理员，所有写操作二次确认并落审计 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import {
  SCENARIOS,
  createAiModel,
  createAiProvider,
  deleteAiQuota,
  fetchAiCallStats,
  fetchAiCalls,
  fetchAiMeta,
  listAiModels,
  listAiProviders,
  listAiQuotas,
  listAiRoutes,
  setDefaultAiModel,
  testAiProvider,
  updateAiModel,
  updateAiModelStatus,
  updateAiProvider,
  updateAiProviderKey,
  updateAiProviderStatus,
  updateAiRoute,
  upsertAiQuota,
  verifyAiModel,
  type AiCallLog,
  type AiCallStats,
  type AiModel,
  type AiProvider,
  type AiQuota,
  type AiScenarioRoute,
} from '../api/aiAdmin'

const isAdmin = getCurrentUser()?.role === 'ADMIN'

const tabs = [
  { key: 'stats', label: '调用统计' },
  { key: 'providers', label: '供应商' },
  { key: 'models', label: '模型目录' },
  { key: 'routes', label: '场景路由' },
  { key: 'quotas', label: '调用配额' },
]
const activeTab = ref('stats')

const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const notice = ref('')
const keyConfigured = ref(true)
const superAdmin = ref(false)

const providers = ref<AiProvider[]>([])
const models = ref<AiModel[]>([])
const routes = ref<AiScenarioRoute[]>([])
const quotas = ref<AiQuota[]>([])
const stats = ref<AiCallStats | null>(null)
const calls = ref<AiCallLog[]>([])
const statsDays = ref(7)

const usableModels = computed(() => models.value.filter((item) => item.enabled && item.verified))

const scenarioLabel = (value: string) => SCENARIOS.find((item) => item.value === value)?.label ?? value
const formatCost = (micros: number | null | undefined) =>
  micros ? `¥${(micros / 1_000_000).toFixed(4)}` : '—'

async function loadAll() {
  if (!isAdmin) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [meta, providerList, modelList, routeList, quotaList] = await Promise.all([
      fetchAiMeta(),
      listAiProviders(),
      listAiModels(),
      listAiRoutes(),
      listAiQuotas(),
    ])
    keyConfigured.value = meta.keyConfigured
    superAdmin.value = meta.superAdmin
    providers.value = providerList
    models.value = modelList
    routes.value = routeList
    quotas.value = quotaList
    syncRouteForms()
    await loadStats()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '治理配置加载失败'
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    const [stat, list] = await Promise.all([fetchAiCallStats(statsDays.value), fetchAiCalls(30)])
    stats.value = stat
    calls.value = list
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '调用统计加载失败'
  }
}

/* ---------------- 通用确认 ---------------- */
const confirmState = reactive({
  open: false,
  title: '',
  text: '',
  action: null as null | (() => Promise<void>),
})

function askConfirm(title: string, text: string, action: () => Promise<void>) {
  confirmState.open = true
  confirmState.title = title
  confirmState.text = text
  confirmState.action = action
}

async function runConfirm() {
  if (!confirmState.action) return
  busy.value = true
  try {
    await confirmState.action()
    confirmState.open = false
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    busy.value = false
  }
}

/* ---------------- 供应商 ---------------- */
const providerModal = reactive({
  open: false,
  mode: 'create' as 'create' | 'edit' | 'key',
  target: null as AiProvider | null,
  error: '',
})
const providerForm = reactive({ code: '', displayName: '', baseUrl: '', apiKey: '', enabled: false })

function openProviderCreate() {
  Object.assign(providerForm, { code: '', displayName: '', baseUrl: '', apiKey: '', enabled: false })
  providerModal.mode = 'create'
  providerModal.target = null
  providerModal.error = ''
  providerModal.open = true
}

function openProviderEdit(row: AiProvider) {
  Object.assign(providerForm, {
    code: row.code,
    displayName: row.displayName,
    baseUrl: row.baseUrl,
    apiKey: '',
    enabled: row.enabled,
  })
  providerModal.mode = 'edit'
  providerModal.target = row
  providerModal.error = ''
  providerModal.open = true
}

function openProviderKey(row: AiProvider) {
  providerForm.apiKey = ''
  providerModal.mode = 'key'
  providerModal.target = row
  providerModal.error = ''
  providerModal.open = true
}

async function submitProvider() {
  providerModal.error = ''
  busy.value = true
  try {
    if (providerModal.mode === 'create') {
      if (!providerForm.code || !providerForm.displayName || !providerForm.baseUrl || !providerForm.apiKey) {
        providerModal.error = '编码、名称、Base URL 与 API Key 均不能为空'
        return
      }
      await createAiProvider({
        code: providerForm.code.trim(),
        displayName: providerForm.displayName.trim(),
        baseUrl: providerForm.baseUrl.trim(),
        apiKey: providerForm.apiKey.trim(),
        enabled: providerForm.enabled,
      })
      notice.value = '供应商已创建，密钥只写不读'
    } else if (providerModal.mode === 'edit' && providerModal.target) {
      await updateAiProvider(providerModal.target.id, {
        displayName: providerForm.displayName.trim(),
        baseUrl: providerForm.baseUrl.trim(),
      })
      notice.value = '供应商配置已更新'
    } else if (providerModal.mode === 'key' && providerModal.target) {
      if (!providerForm.apiKey.trim()) {
        providerModal.error = '请输入新的 API Key'
        return
      }
      await updateAiProviderKey(providerModal.target.id, providerForm.apiKey.trim())
      notice.value = 'API Key 已更新（不可回读）'
    }
    providerModal.open = false
    await loadAll()
  } catch (e) {
    providerModal.error = e instanceof Error ? e.message : '保存失败'
  } finally {
    busy.value = false
  }
}

function toggleProvider(row: AiProvider) {
  const next = !row.enabled
  askConfirm(next ? '启用供应商' : '停用供应商',
    `确认${next ? '启用' : '停用'}「${row.displayName}」？停用后相关场景将回退到环境变量配置。`,
    async () => {
      await updateAiProviderStatus(row.id, next)
      notice.value = `供应商「${row.displayName}」已${next ? '启用' : '停用'}`
      await loadAll()
    })
}

async function runProviderTest(row: AiProvider) {
  busy.value = true
  try {
    const result = await testAiProvider(row.id)
    notice.value = result.success
      ? `连接成功（${result.latencyMs}ms），匹配模型已自动标记为已验证`
      : `连接失败：${result.message}`
    await loadAll()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '连接测试失败'
  } finally {
    busy.value = false
  }
}

/* ---------------- 模型 ---------------- */
const modelModal = reactive({
  open: false,
  mode: 'create' as 'create' | 'edit',
  target: null as AiModel | null,
  error: '',
})
const modelForm = reactive({
  providerId: 0,
  modelCode: '',
  displayName: '',
  temperature: 0.2,
  maxTokens: 2048,
  timeoutSeconds: 60,
  costPer1kMicros: 0,
})

function openModelCreate() {
  Object.assign(modelForm, {
    providerId: providers.value[0]?.id ?? 0,
    modelCode: '',
    displayName: '',
    temperature: 0.2,
    maxTokens: 2048,
    timeoutSeconds: 60,
    costPer1kMicros: 0,
  })
  modelModal.mode = 'create'
  modelModal.target = null
  modelModal.error = ''
  modelModal.open = true
}

function openModelEdit(row: AiModel) {
  Object.assign(modelForm, {
    providerId: row.providerId,
    modelCode: row.modelCode,
    displayName: row.displayName,
    temperature: row.temperature,
    maxTokens: row.maxTokens,
    timeoutSeconds: row.timeoutSeconds,
    costPer1kMicros: row.costPer1kMicros,
  })
  modelModal.mode = 'edit'
  modelModal.target = row
  modelModal.error = ''
  modelModal.open = true
}

async function submitModel() {
  modelModal.error = ''
  busy.value = true
  try {
    if (modelModal.mode === 'create') {
      if (!modelForm.providerId || !modelForm.modelCode || !modelForm.displayName) {
        modelModal.error = '供应商、模型编码与名称不能为空'
        return
      }
      await createAiModel({
        providerId: modelForm.providerId,
        modelCode: modelForm.modelCode.trim(),
        displayName: modelForm.displayName.trim(),
        temperature: Number(modelForm.temperature),
        maxTokens: Number(modelForm.maxTokens),
        timeoutSeconds: Number(modelForm.timeoutSeconds),
        costPer1kMicros: Number(modelForm.costPer1kMicros),
      })
      notice.value = '模型已创建，需通过连接测试后才能启用'
    } else if (modelModal.target) {
      await updateAiModel(modelModal.target.id, {
        displayName: modelForm.displayName.trim(),
        temperature: Number(modelForm.temperature),
        maxTokens: Number(modelForm.maxTokens),
        timeoutSeconds: Number(modelForm.timeoutSeconds),
        costPer1kMicros: Number(modelForm.costPer1kMicros),
      })
      notice.value = '模型参数已更新'
    }
    modelModal.open = false
    await loadAll()
  } catch (e) {
    modelModal.error = e instanceof Error ? e.message : '保存失败'
  } finally {
    busy.value = false
  }
}

async function runVerify(row: AiModel) {
  busy.value = true
  try {
    await verifyAiModel(row.id)
    notice.value = `模型「${row.modelCode}」连接测试通过，已标记为已验证`
    await loadAll()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '连接测试失败'
  } finally {
    busy.value = false
  }
}

function toggleModel(row: AiModel) {
  const next = !row.enabled
  askConfirm(next ? '启用模型' : '停用模型',
    next
      ? `确认启用「${row.modelCode}」？启用后可用于场景路由与默认模型。`
      : `确认停用「${row.modelCode}」？已引用该模型的场景将回退到环境变量配置。`,
    async () => {
      await updateAiModelStatus(row.id, next)
      notice.value = `模型「${row.modelCode}」已${next ? '启用' : '停用'}`
      await loadAll()
    })
}

function askDefault(row: AiModel) {
  askConfirm('设为全局默认模型', `确认将「${row.modelCode}」设为默认模型？未单独配置主模型的场景将使用它。`, async () => {
    await setDefaultAiModel(row.id)
    notice.value = `默认模型已切换为「${row.modelCode}」`
    await loadAll()
  })
}

/* ---------------- 场景路由 ---------------- */
interface RouteForm {
  scenario: string
  primaryModelId: number | null
  fallbackModelId: number | null
  temperature: number | null
  maxTokens: number | null
  timeoutSeconds: number | null
  maxRetries: number
  enabled: boolean
}
const routeForms = ref<RouteForm[]>([])

function syncRouteForms() {
  routeForms.value = SCENARIOS.map((item) => {
    const route = routes.value.find((row) => row.scenario === item.value)
    return {
      scenario: item.value,
      primaryModelId: route?.primaryModelId ?? null,
      fallbackModelId: route?.fallbackModelId ?? null,
      temperature: route?.temperature ?? null,
      maxTokens: route?.maxTokens ?? null,
      timeoutSeconds: route?.timeoutSeconds ?? null,
      maxRetries: route?.maxRetries ?? 1,
      enabled: route?.enabled ?? false,
    }
  })
}

function saveRoute(form: RouteForm) {
  const enabled = form.enabled
  askConfirm('保存场景路由', `确认保存「${scenarioLabel(form.scenario)}」的路由配置？启用后将按所配模型调用。`, async () => {
    await updateAiRoute(form.scenario, {
      primaryModelId: form.primaryModelId,
      fallbackModelId: form.fallbackModelId,
      temperature: form.temperature,
      maxTokens: form.maxTokens,
      timeoutSeconds: form.timeoutSeconds,
      maxRetries: Number(form.maxRetries),
      enabled,
    })
    notice.value = `「${scenarioLabel(form.scenario)}」路由已保存`
    await loadAll()
  })
}

/* ---------------- 配额 ---------------- */
const quotaModal = reactive({ open: false, error: '' })
const quotaForm = reactive({ scopeType: 'GLOBAL', scopeValue: '', period: 'DAY', maxCalls: 100, enabled: true })

function openQuotaCreate() {
  Object.assign(quotaForm, { scopeType: 'GLOBAL', scopeValue: '', period: 'DAY', maxCalls: 100, enabled: true })
  quotaModal.error = ''
  quotaModal.open = true
}

async function submitQuota() {
  quotaModal.error = ''
  busy.value = true
  try {
    if (quotaForm.scopeType !== 'GLOBAL' && !quotaForm.scopeValue.trim()) {
      quotaModal.error = '非全局配额必须填写作用范围'
      return
    }
    await upsertAiQuota({
      scopeType: quotaForm.scopeType,
      scopeValue: quotaForm.scopeValue.trim(),
      period: quotaForm.period,
      maxCalls: Number(quotaForm.maxCalls),
      enabled: quotaForm.enabled,
    })
    notice.value = '配额已保存'
    quotaModal.open = false
    await loadAll()
  } catch (e) {
    quotaModal.error = e instanceof Error ? e.message : '保存失败'
  } finally {
    busy.value = false
  }
}

function askDeleteQuota(row: AiQuota) {
  askConfirm('删除配额', `确认删除 ${row.scopeType} ${row.scopeValue || '全局'} 的配额？`, async () => {
    await deleteAiQuota(row.id)
    notice.value = '配额已删除'
    await loadAll()
  })
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>AI 模型管理</h1>
        <p class="head-sub">供应商密钥只写不可读；模型需验证后启用；调用前脱敏并记录用量与异常审计</p>
      </div>
      <div v-if="isAdmin" class="head-right">
        <button class="btn" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
      </div>
    </section>

    <template v-if="!isAdmin">
      <section class="card gate">
        <EmptyState icon="alert" title="仅管理员可访问" desc="当前账号没有 AI 模型治理权限。" />
      </section>
    </template>

    <template v-else>
      <p v-if="notice" class="notice"><AppIcon name="check" :size="15" />{{ notice }}</p>
      <p v-if="errorMessage" class="alert"><AppIcon name="alert" :size="15" />{{ errorMessage }}</p>
      <p v-if="!keyConfigured" class="alert">
        <AppIcon name="alert" :size="15" />服务端未配置 AI_SECRET_KEY，暂时无法保存供应商密钥；现有调用仍使用环境变量配置。
      </p>

      <div class="tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="tab"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- 调用统计 -->
      <template v-if="activeTab === 'stats'">
        <section class="stat-grid">
          <div class="card stat">
            <span class="stat-label">调用总量</span>
            <strong class="stat-value">{{ stats?.total ?? 0 }}</strong>
            <span class="muted">最近 {{ stats?.days ?? statsDays }} 天</span>
          </div>
          <div class="card stat">
            <span class="stat-label">失败率</span>
            <strong class="stat-value" :class="{ danger: (stats?.failureRate ?? 0) > 20 }">
              {{ stats?.failureRate ?? 0 }}%
            </strong>
            <span class="muted">失败 {{ stats?.failed ?? 0 }} / 成功 {{ stats?.success ?? 0 }}</span>
          </div>
          <div class="card stat">
            <span class="stat-label">Token 用量</span>
            <strong class="stat-value">{{ (stats?.tokens ?? 0).toLocaleString() }}</strong>
            <span class="muted">成本 {{ formatCost(stats?.costMicros) }}</span>
          </div>
          <div class="card stat">
            <span class="stat-label">平均耗时</span>
            <strong class="stat-value">{{ stats?.avgLatencyMs ?? 0 }} ms</strong>
            <span class="muted" :class="{ danger: stats?.alerting }">
              近 1 小时失败 {{ stats?.lastHourFailures ?? 0 }} 次{{ stats?.alerting ? '（告警）' : '' }}
            </span>
          </div>
        </section>

        <section class="card">
          <header class="card-head">
            <h2>场景维度</h2>
            <select v-model.number="statsDays" class="inline-select" @change="loadStats">
              <option :value="1">近 1 天</option>
              <option :value="7">近 7 天</option>
              <option :value="30">近 30 天</option>
            </select>
          </header>
          <table class="ai-table">
            <thead>
              <tr>
                <th>场景</th><th class="num-col">调用</th><th class="num-col">成功</th>
                <th class="num-col">失败</th><th class="num-col">Token</th>
                <th class="num-col">成本</th><th class="num-col">平均耗时</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in stats?.scenarios ?? []" :key="row.scenario">
                <td>{{ scenarioLabel(row.scenario) }}</td>
                <td class="num-col">{{ row.total }}</td>
                <td class="num-col">{{ row.success }}</td>
                <td class="num-col">{{ row.failed }}</td>
                <td class="num-col">{{ row.tokens.toLocaleString() }}</td>
                <td class="num-col">{{ formatCost(row.costMicros) }}</td>
                <td class="num-col">{{ row.avgLatencyMs }} ms</td>
              </tr>
              <tr v-if="!stats?.scenarios?.length">
                <td colspan="7" class="empty">暂无调用记录</td>
              </tr>
            </tbody>
          </table>
        </section>

        <section class="card">
          <header class="card-head"><h2>最近调用</h2><span class="meta">最多 30 条，不含 Prompt 与正文</span></header>
          <table class="ai-table">
            <thead>
              <tr>
                <th>时间</th><th>场景</th><th>模型</th><th>状态</th>
                <th class="num-col">耗时</th><th class="num-col">Token</th><th>错误码</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in calls" :key="row.id">
                <td class="time">{{ row.createdAt ? formatDateTime(row.createdAt) : '—' }}</td>
                <td>{{ scenarioLabel(row.scenario) }}</td>
                <td>{{ row.modelName || '环境变量默认' }}</td>
                <td>
                  <span class="pill" :class="row.status === 'SUCCESS' ? 'ok' : 'bad'">{{ row.status }}</span>
                </td>
                <td class="num-col">{{ row.latencyMs ?? '—' }} ms</td>
                <td class="num-col">{{ row.totalTokens ?? '—' }}</td>
                <td class="mono">{{ row.errorCode || '—' }}</td>
              </tr>
              <tr v-if="!calls.length">
                <td colspan="7" class="empty">暂无调用记录</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- 供应商 -->
      <template v-if="activeTab === 'providers'">
        <section class="card">
          <header class="card-head">
            <h2>供应商</h2>
            <button class="btn btn-sm btn-primary" :disabled="!superAdmin" @click="openProviderCreate">
              <AppIcon name="plus" :size="14" />新增供应商
            </button>
          </header>
          <p v-if="!superAdmin" class="muted hint">当前账号为普通管理员，只能调整场景路由与模型启停；供应商与密钥需超级管理员。</p>
          <table class="ai-table">
            <thead>
              <tr>
                <th>名称 / 编码</th><th>Base URL</th><th>密钥</th><th>状态</th>
                <th>最近测试</th><th class="actions-col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in providers" :key="row.id">
                <td>
                  <div class="stack">
                    <strong>{{ row.displayName }}</strong>
                    <span class="mono muted">{{ row.code }}</span>
                  </div>
                </td>
                <td class="mono">{{ row.baseUrl }}</td>
                <td class="mono">{{ row.hasKey ? row.keyMask : '未配置' }}</td>
                <td>
                  <span class="pill" :class="row.enabled ? 'ok' : 'off'">{{ row.enabled ? '启用' : '停用' }}</span>
                </td>
                <td>
                  <div class="stack">
                    <span :class="row.lastTestResult === 'SUCCESS' ? 'ok-text' : row.lastTestResult ? 'bad-text' : 'muted'">
                      {{ row.lastTestResult === 'SUCCESS' ? '成功' : row.lastTestResult === 'FAILURE' ? '失败' : '未测试' }}
                    </span>
                    <span class="muted tiny">{{ row.lastTestMessage || '' }}</span>
                  </div>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="link" :disabled="!superAdmin" @click="openProviderEdit(row)">编辑</button>
                    <button class="link" :disabled="!superAdmin" @click="openProviderKey(row)">更换密钥</button>
                    <button class="link" :disabled="busy" @click="runProviderTest(row)">测试连接</button>
                    <button class="link" :disabled="!superAdmin" @click="toggleProvider(row)">
                      {{ row.enabled ? '停用' : '启用' }}
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="!providers.length">
                <td colspan="6" class="empty">尚未配置供应商</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- 模型 -->
      <template v-if="activeTab === 'models'">
        <section class="card">
          <header class="card-head">
            <h2>模型目录</h2>
            <button class="btn btn-sm btn-primary" :disabled="!superAdmin || !providers.length" @click="openModelCreate">
              <AppIcon name="plus" :size="14" />新增模型
            </button>
          </header>
          <table class="ai-table">
            <thead>
              <tr>
                <th>模型</th><th>供应商</th><th class="num-col">温度</th>
                <th class="num-col">最大输出</th><th class="num-col">超时</th>
                <th>验证 / 状态</th><th class="actions-col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in models" :key="row.id">
                <td>
                  <div class="stack">
                    <strong>{{ row.displayName }}</strong>
                    <span class="mono muted">{{ row.modelCode }}</span>
                  </div>
                </td>
                <td class="mono">{{ row.providerCode }}</td>
                <td class="num-col">{{ row.temperature }}</td>
                <td class="num-col">{{ row.maxTokens }}</td>
                <td class="num-col">{{ row.timeoutSeconds }}s</td>
                <td>
                  <div class="stack">
                    <span class="pill" :class="row.verified ? 'ok' : 'off'">{{ row.verified ? '已验证' : '未验证' }}</span>
                    <span class="pill" :class="row.enabled ? 'ok' : 'off'">{{ row.enabled ? '启用' : '停用' }}</span>
                    <span v-if="row.isDefault" class="pill default">默认</span>
                  </div>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="link" @click="openModelEdit(row)">参数</button>
                    <button class="link" :disabled="busy" @click="runVerify(row)">测试验证</button>
                    <button class="link" @click="toggleModel(row)">{{ row.enabled ? '停用' : '启用' }}</button>
                    <button class="link" :disabled="!superAdmin || !row.enabled || !row.verified" @click="askDefault(row)">设默认</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!models.length">
                <td colspan="7" class="empty">尚未配置模型</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- 场景路由 -->
      <template v-if="activeTab === 'routes'">
        <section class="card">
          <header class="card-head">
            <h2>场景路由</h2>
            <span class="meta">只有已启用且已验证的模型可被路由引用；停用路由时回退到环境变量配置</span>
          </header>
          <table class="ai-table">
            <thead>
              <tr>
                <th>场景</th><th>主模型</th><th>备用模型</th>
                <th class="num-col">温度</th><th class="num-col">最大输出</th>
                <th class="num-col">超时(s)</th><th class="num-col">重试</th><th>启用</th>
                <th class="actions-col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="form in routeForms" :key="form.scenario">
                <td>{{ scenarioLabel(form.scenario) }}</td>
                <td>
                  <select v-model="form.primaryModelId">
                    <option :value="null">默认模型</option>
                    <option v-for="model in usableModels" :key="model.id" :value="model.id">
                      {{ model.displayName }}
                    </option>
                  </select>
                </td>
                <td>
                  <select v-model="form.fallbackModelId">
                    <option :value="null">无</option>
                    <option v-for="model in usableModels" :key="model.id" :value="model.id">
                      {{ model.displayName }}
                    </option>
                  </select>
                </td>
                <td><input v-model.number="form.temperature" type="number" step="0.1" min="0" max="2" /></td>
                <td><input v-model.number="form.maxTokens" type="number" min="64" max="32768" /></td>
                <td><input v-model.number="form.timeoutSeconds" type="number" min="5" max="600" /></td>
                <td><input v-model.number="form.maxRetries" type="number" min="0" max="3" /></td>
                <td><input v-model="form.enabled" type="checkbox" /></td>
                <td><button class="btn btn-sm" :disabled="busy" @click="saveRoute(form)">保存</button></td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- 配额 -->
      <template v-if="activeTab === 'quotas'">
        <section class="card">
          <header class="card-head">
            <h2>调用配额</h2>
            <button class="btn btn-sm btn-primary" :disabled="!superAdmin" @click="openQuotaCreate">
              <AppIcon name="plus" :size="14" />新增配额
            </button>
          </header>
          <table class="ai-table">
            <thead>
              <tr><th>范围</th><th>对象</th><th>周期</th><th class="num-col">上限次数</th><th>状态</th><th class="actions-col">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in quotas" :key="row.id">
                <td>{{ row.scopeType }}</td>
                <td>{{ row.scopeValue || '全部' }}</td>
                <td>{{ row.period === 'DAY' ? '每日' : '每月' }}</td>
                <td class="num-col">{{ row.maxCalls }}</td>
                <td><span class="pill" :class="row.enabled ? 'ok' : 'off'">{{ row.enabled ? '启用' : '停用' }}</span></td>
                <td><button class="link" :disabled="!superAdmin" @click="askDeleteQuota(row)">删除</button></td>
              </tr>
              <tr v-if="!quotas.length">
                <td colspan="6" class="empty">未配置配额时不做调用次数限制</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- 供应商弹窗 -->
      <div v-if="providerModal.open" class="mask" @click.self="providerModal.open = false">
        <div class="modal" role="dialog" aria-modal="true">
          <header class="modal-head">
            <div>
              <h3>
                {{ providerModal.mode === 'create' ? '新增供应商' : providerModal.mode === 'edit' ? '编辑供应商' : '更换 API Key' }}
              </h3>
              <p class="modal-sub">密钥仅在服务端加密保存；提交后任何接口都不会回显明文</p>
            </div>
            <button class="close" title="关闭" @click="providerModal.open = false">
              <AppIcon name="close" :size="16" />
            </button>
          </header>
          <div class="modal-body">
            <p v-if="providerModal.error" class="alert">{{ providerModal.error }}</p>
            <template v-if="providerModal.mode === 'create'">
              <div class="row">
                <label class="field">
                  <span>供应商编码 <i>*</i></span>
                  <input v-model="providerForm.code" maxlength="32" placeholder="如 deepseek" />
                </label>
                <label class="field">
                  <span>显示名称 <i>*</i></span>
                  <input v-model="providerForm.displayName" maxlength="64" placeholder="DeepSeek 官方" />
                </label>
              </div>
            </template>
            <label v-if="providerModal.mode !== 'key'" class="field">
              <span>Base URL <i>*</i></span>
              <input v-model="providerForm.baseUrl" maxlength="255" placeholder="https://api.deepseek.com" />
            </label>
            <label v-if="providerModal.mode !== 'edit'" class="field">
              <span>API Key <i>*</i></span>
              <input v-model="providerForm.apiKey" type="password" autocomplete="new-password" placeholder="只写不回读" />
            </label>
            <label v-if="providerModal.mode === 'create'" class="check">
              <input v-model="providerForm.enabled" type="checkbox" />创建后立即启用（需先配置密钥）
            </label>
          </div>
          <footer class="modal-foot">
            <button class="btn" @click="providerModal.open = false">取消</button>
            <button class="btn btn-primary" :disabled="busy" @click="submitProvider">保存</button>
          </footer>
        </div>
      </div>

      <!-- 模型弹窗 -->
      <div v-if="modelModal.open" class="mask" @click.self="modelModal.open = false">
        <div class="modal" role="dialog" aria-modal="true">
          <header class="modal-head">
            <div>
              <h3>{{ modelModal.mode === 'create' ? '新增模型' : '模型参数' }}</h3>
              <p class="modal-sub">新建模型默认停用，连接测试通过后可启用并设为默认</p>
            </div>
            <button class="close" title="关闭" @click="modelModal.open = false">
              <AppIcon name="close" :size="16" />
            </button>
          </header>
          <div class="modal-body">
            <p v-if="modelModal.error" class="alert">{{ modelModal.error }}</p>
            <div v-if="modelModal.mode === 'create'" class="row">
              <label class="field">
                <span>供应商 <i>*</i></span>
                <select v-model.number="modelForm.providerId">
                  <option v-for="provider in providers" :key="provider.id" :value="provider.id">
                    {{ provider.displayName }}
                  </option>
                </select>
              </label>
              <label class="field">
                <span>模型编码 <i>*</i></span>
                <input v-model="modelForm.modelCode" maxlength="64" placeholder="deepseek-chat" />
              </label>
            </div>
            <label class="field">
              <span>显示名称 <i>*</i></span>
              <input v-model="modelForm.displayName" maxlength="64" />
            </label>
            <div class="row">
              <label class="field">
                <span>温度</span>
                <input v-model.number="modelForm.temperature" type="number" step="0.1" min="0" max="2" />
              </label>
              <label class="field">
                <span>最大输出 Token</span>
                <input v-model.number="modelForm.maxTokens" type="number" min="64" max="32768" />
              </label>
            </div>
            <div class="row">
              <label class="field">
                <span>超时（秒）</span>
                <input v-model.number="modelForm.timeoutSeconds" type="number" min="5" max="600" />
              </label>
              <label class="field">
                <span>每千 Token 成本（微元）</span>
                <input v-model.number="modelForm.costPer1kMicros" type="number" min="0" />
              </label>
            </div>
          </div>
          <footer class="modal-foot">
            <button class="btn" @click="modelModal.open = false">取消</button>
            <button class="btn btn-primary" :disabled="busy" @click="submitModel">保存</button>
          </footer>
        </div>
      </div>

      <!-- 配额弹窗 -->
      <div v-if="quotaModal.open" class="mask" @click.self="quotaModal.open = false">
        <div class="modal" role="dialog" aria-modal="true">
          <header class="modal-head">
            <div>
              <h3>新增 / 更新配额</h3>
              <p class="modal-sub">同一范围与周期重复保存会覆盖原上限</p>
            </div>
            <button class="close" title="关闭" @click="quotaModal.open = false">
              <AppIcon name="close" :size="16" />
            </button>
          </header>
          <div class="modal-body">
            <p v-if="quotaModal.error" class="alert">{{ quotaModal.error }}</p>
            <div class="row">
              <label class="field">
                <span>范围</span>
                <select v-model="quotaForm.scopeType">
                  <option value="GLOBAL">全局</option>
                  <option value="DEPARTMENT">部门</option>
                  <option value="ROLE">角色</option>
                  <option value="USER">用户</option>
                  <option value="SCENARIO">场景</option>
                </select>
              </label>
              <label class="field">
                <span>对象</span>
                <input
                  v-model="quotaForm.scopeValue"
                  maxlength="64"
                  :disabled="quotaForm.scopeType === 'GLOBAL'"
                  :placeholder="quotaForm.scopeType === 'GLOBAL' ? '全局无需填写' : quotaForm.scopeType === 'USER' ? '用户 ID' : quotaForm.scopeType === 'ROLE' ? 'ADMIN / AGENT / USER' : quotaForm.scopeType === 'SCENARIO' ? '如 CHAT' : '部门名称'"
                />
              </label>
            </div>
            <div class="row">
              <label class="field">
                <span>周期</span>
                <select v-model="quotaForm.period">
                  <option value="DAY">每日</option>
                  <option value="MONTH">每月</option>
                </select>
              </label>
              <label class="field">
                <span>上限次数</span>
                <input v-model.number="quotaForm.maxCalls" type="number" min="1" max="1000000" />
              </label>
            </div>
            <label class="check"><input v-model="quotaForm.enabled" type="checkbox" />启用该配额</label>
          </div>
          <footer class="modal-foot">
            <button class="btn" @click="quotaModal.open = false">取消</button>
            <button class="btn btn-primary" :disabled="busy" @click="submitQuota">保存</button>
          </footer>
        </div>
      </div>

      <!-- 通用确认 -->
      <div v-if="confirmState.open" class="mask" @click.self="confirmState.open = false">
        <div class="modal modal-sm" role="dialog" aria-modal="true">
          <header class="modal-head">
            <div><h3>{{ confirmState.title }}</h3><p class="modal-sub">此操作会记录审计日志</p></div>
            <button class="close" title="关闭" @click="confirmState.open = false">
              <AppIcon name="close" :size="16" />
            </button>
          </header>
          <div class="modal-body"><p>{{ confirmState.text }}</p></div>
          <footer class="modal-foot">
            <button class="btn" @click="confirmState.open = false">取消</button>
            <button class="btn btn-primary" :disabled="busy" @click="runConfirm">确认</button>
          </footer>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.tab {
  padding: 7px 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-muted);
  cursor: pointer;
  font-size: 13px;
}
.tab.active {
  border-color: var(--primary);
  color: var(--primary);
  background: rgba(37, 99, 235, 0.08);
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}
.stat {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-label {
  color: var(--text-muted);
  font-size: 12px;
}
.stat-value {
  font-size: 24px;
  font-weight: 600;
}
.danger {
  color: var(--danger);
}
.ai-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.ai-table th,
.ai-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: middle;
}
.ai-table th {
  color: var(--text-muted);
  font-weight: 500;
  font-size: 12px;
  white-space: nowrap;
}
.ai-table td input[type='number'],
.ai-table td select {
  width: 100%;
  max-width: 140px;
}
.num-col {
  text-align: right;
  white-space: nowrap;
}
.actions-col {
  text-align: right;
}
.row-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-wrap: wrap;
}
.empty {
  text-align: center;
  color: var(--text-muted);
  padding: 24px 0;
}
.stack {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.pill {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--surface-muted, #f1f5f9);
  color: var(--text-muted);
}
.pill.ok {
  background: rgba(22, 163, 74, 0.12);
  color: var(--success, #15803d);
}
.pill.bad {
  background: rgba(220, 38, 38, 0.12);
  color: var(--danger);
}
.pill.off {
  background: rgba(100, 116, 139, 0.12);
}
.pill.default {
  background: rgba(37, 99, 235, 0.12);
  color: var(--primary);
}
.ok-text {
  color: var(--success, #15803d);
  font-size: 12px;
}
.bad-text {
  color: var(--danger);
  font-size: 12px;
}
.tiny {
  font-size: 11px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.time {
  white-space: nowrap;
  color: var(--text-muted);
}
.hint {
  padding: 0 16px 8px;
  font-size: 12px;
}
.inline-select {
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: var(--surface);
}
.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-top: 6px;
}
.modal-sm {
  max-width: 420px;
}
</style>
