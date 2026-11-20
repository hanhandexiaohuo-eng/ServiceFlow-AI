<script setup lang="ts">
/** 操作日志：只读审计查询，仅管理员；无编辑/删除入口，导出后续受控开放 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import { listAuditLogs, type AuditLog } from '../api/admin'
import type { TableColumn } from '../types/ui'

const currentUser = getCurrentUser()
const isAdmin = currentUser?.role === 'ADMIN'

const rows = ref<AuditLog[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const errorMessage = ref('')

const filters = reactive({ action: '', actor: '', result: '', from: '', to: '' })
const applied = reactive({ action: '', actor: '', result: '', from: '', to: '' })

const actionOptions = [
  'LOGIN',
  'LOGOUT',
  'PASSWORD_CHANGE',
  'AUTH_FAILURE',
  'USER_CREATE',
  'USER_UPDATE',
  'USER_ENABLE',
  'USER_DISABLE',
  'USER_ROLE_CHANGE',
  'USER_PASSWORD_RESET',
]

const pageCount = computed(() => Math.max(Math.ceil(total.value / size), 1))

async function load() {
  if (!isAdmin) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAuditLogs({
      action: applied.action || undefined,
      actor: applied.actor.trim() || undefined,
      result: applied.result || undefined,
      from: applied.from || undefined,
      to: applied.to || undefined,
      page: page.value,
      size,
    })
    rows.value = result.list
    total.value = result.total
  } catch (e) {
    rows.value = []
    total.value = 0
    errorMessage.value = e instanceof Error ? e.message : '操作日志加载失败'
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  Object.assign(applied, { ...filters })
  page.value = 1
  load()
}

function resetFilters() {
  Object.assign(filters, { action: '', actor: '', result: '', from: '', to: '' })
  Object.assign(applied, { action: '', actor: '', result: '', from: '', to: '' })
  page.value = 1
  load()
}

function goPage(next: number) {
  if (next < 1 || next > pageCount.value || next === page.value) return
  page.value = next
  load()
}

const columns: TableColumn[] = [
  { key: 'createdAt', label: '时间', width: '150px', nowrap: true },
  { key: 'actorUsername', label: '操作者', width: '120px', nowrap: true },
  { key: 'action', label: '动作', width: '150px', nowrap: true },
  { key: 'object', label: '对象', width: '140px' },
  { key: 'result', label: '结果', width: '84px', nowrap: true },
  { key: 'detail', label: '摘要', width: '240px' },
  { key: 'ip', label: '来源 IP', width: '120px', nowrap: true },
  { key: 'requestId', label: '请求追踪 ID', width: '170px' },
  { key: 'failureReason', label: '失败原因', width: '140px' },
]

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>操作日志</h1>
        <p class="head-sub">只追加、不可编辑删除；记录不含密码、Token、API Key 等敏感内容</p>
      </div>
      <div v-if="isAdmin" class="head-right">
        <button class="btn" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
      </div>
    </section>

    <template v-if="!isAdmin">
      <section class="card gate">
        <EmptyState icon="alert" title="仅管理员可访问" desc="当前账号没有操作日志查看权限。" />
      </section>
    </template>

    <template v-else>
      <section class="filters-card">
        <div class="filter-grid">
          <label class="f-field">
            <span>动作</span>
            <select v-model="filters.action">
              <option value="">全部动作</option>
              <option v-for="action in actionOptions" :key="action" :value="action">{{ action }}</option>
            </select>
          </label>
          <label class="f-field">
            <span>操作者</span>
            <input v-model="filters.actor" placeholder="用户名关键字" @keyup.enter="applyFilters" />
          </label>
          <label class="f-field">
            <span>结果</span>
            <select v-model="filters.result">
              <option value="">全部结果</option>
              <option value="SUCCESS">成功</option>
              <option value="FAILURE">失败</option>
            </select>
          </label>
          <label class="f-field">
            <span>开始日期</span>
            <input v-model="filters.from" type="date" />
          </label>
          <label class="f-field">
            <span>结束日期</span>
            <input v-model="filters.to" type="date" />
          </label>
          <div class="filter-actions">
            <button class="btn btn-sm" @click="resetFilters">重置</button>
            <button class="btn btn-sm btn-primary" @click="applyFilters">
              <AppIcon name="search" :size="13" />查询
            </button>
          </div>
        </div>
      </section>

      <p v-if="errorMessage" class="alert"><AppIcon name="alert" :size="15" />{{ errorMessage }}</p>

      <section class="card">
        <header class="card-head">
          <h2>审计记录</h2>
          <span class="meta">共 <b class="num">{{ total }}</b> 条 · 第 {{ page }} / {{ pageCount }} 页</span>
        </header>

        <DataTable
          :columns="columns"
          :rows="rows"
          row-key="id"
          :loading="loading && rows.length === 0"
          empty-text="没有符合条件的审计记录"
        >
          <template #cell-createdAt="{ row }">
            <span class="time">{{ row.createdAt ? formatDateTime(row.createdAt) : '—' }}</span>
          </template>
          <template #cell-actorUsername="{ row }">
            <span>{{ row.actorUsername || '匿名 / 未登录' }}</span>
          </template>
          <template #cell-action="{ row }">
            <code class="action">{{ row.action }}</code>
          </template>
          <template #cell-object="{ row }">
            <span>{{ row.objectType || '—' }}<template v-if="row.objectId"> #{{ row.objectId }}</template></span>
          </template>
          <template #cell-result="{ row }">
            <span class="result" :class="{ failed: row.result !== 'SUCCESS' }">{{ row.result }}</span>
          </template>
          <template #cell-detail="{ row }">
            <span class="detail" :title="row.detail || ''">{{ row.detail || '—' }}</span>
          </template>
          <template #cell-ip="{ row }">
            <span class="time">{{ row.ip || '—' }}</span>
          </template>
          <template #cell-requestId="{ row }">
            <span class="time">{{ row.requestId || '—' }}</span>
          </template>
          <template #cell-failureReason="{ row }">
            <span class="muted">{{ row.failureReason || '—' }}</span>
          </template>
        </DataTable>

        <div class="pager">
          <button class="btn btn-sm" :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
          <span class="muted">{{ page }} / {{ pageCount }}</span>
          <button class="btn btn-sm" :disabled="page >= pageCount || loading" @click="goPage(page + 1)">下一页</button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.action {
  font-size: 12px;
  color: var(--text-secondary);
}
.result {
  font-size: 12px;
  color: var(--success);
}
.result.failed {
  color: var(--danger);
}
.detail {
  display: inline-block;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 16px;
}
</style>
