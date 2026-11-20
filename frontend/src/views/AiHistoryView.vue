<script setup lang="ts">
/** AI 分析历史：管理员查看全部，普通用户仅本人发起 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import { ANALYSIS_STATUS_LABEL, ANALYSIS_TYPE_LABEL } from '../constants/ai'
import { formatDateTime } from '../constants/ticket'
import { listAnalyses, type AiAnalysisBrief } from '../api/ai'

const router = useRouter()

const list = ref<AiAnalysisBrief[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const type = ref('')
const status = ref('')
const loading = ref(true)
const errorMessage = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAnalyses({
      page: page.value,
      size: size.value,
      type: type.value || undefined,
      status: status.value || undefined
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    list.value = []
    total.value = 0
    errorMessage.value = error instanceof Error ? error.message : '分析历史加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function changePage(next: number) {
  const target = Math.min(Math.max(1, next), totalPages.value)
  if (target === page.value) return
  page.value = target
  void load()
}

function openDetail(id: number) {
  void router.push(`/ai/analyses/${id}`)
}

onMounted(load)
</script>

<template>
  <div class="page history-page">
    <div class="head">
      <div>
        <h2>AI 分析历史</h2>
        <p>记录每次分析的类型、状态、发起人与结果摘要；失败记录会保留错误码便于排查。</p>
      </div>
      <div class="filters">
        <select v-model="type" @change="search">
          <option value="">全部类型</option>
          <option value="TICKET_ANALYSIS">智能分析</option>
          <option value="PRIORITY">优先级建议</option>
          <option value="SOLUTION">处理方案</option>
          <option value="DISPATCH">调度辅助</option>
          <option value="CHAT">会话追问</option>
        </select>
        <select v-model="status" @change="search">
          <option value="">全部状态</option>
          <option value="SUCCESS">已完成</option>
          <option value="FAILED">失败</option>
          <option value="PENDING">分析中</option>
        </select>
        <button class="btn btn-sm" @click="load">
          <AppIcon name="refresh" :size="13" /> 刷新
        </button>
      </div>
    </div>

    <div v-if="errorMessage" class="banner">
      <AppIcon name="alert" :size="15" /> {{ errorMessage }}
    </div>

    <div class="table-card">
      <div v-if="loading" class="state"><LoadingState text="正在加载分析记录" /></div>
      <div v-else-if="!list.length" class="state">
        <EmptyState title="暂无分析记录" description="在 AI 工作空间对工单发起一次分析后，记录会出现在这里。" />
      </div>
      <table v-else>
        <thead>
          <tr>
            <th>分析时间</th>
            <th>工单编号</th>
            <th>分析类型</th>
            <th>状态</th>
            <th>发起用户</th>
            <th>结果摘要</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in list" :key="item.id" @click="openDetail(item.id)">
            <td class="time">{{ formatDateTime(item.createdAt) }}</td>
            <td class="mono">{{ item.ticketNo }}</td>
            <td>
              <span class="type-chip">{{ ANALYSIS_TYPE_LABEL[item.type] ?? item.type }}</span>
            </td>
            <td>
              <span class="state-chip" :class="'is-' + item.status">
                {{ ANALYSIS_STATUS_LABEL[item.status] ?? item.status }}
              </span>
            </td>
            <td>{{ item.requestedByName || '-' }}</td>
            <td class="summary">
              <span v-if="item.status === 'FAILED'" class="fail-text">
                {{ item.errorCode || 'AI 分析失败' }}
              </span>
              <span v-else>{{ item.summary }}</span>
            </td>
            <td class="right">
              <button class="link" @click.stop="openDetail(item.id)">
                查看详情 <AppIcon name="chevronRight" :size="12" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <footer v-if="!loading && list.length" class="pager">
        <span>共 {{ total }} 条 · 第 {{ page }} / {{ totalPages }} 页</span>
        <div>
          <button class="btn btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
          <button class="btn btn-sm" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
        </div>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.history-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.head h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-1);
}
.head p {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
}
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
.filters select {
  height: 30px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  font-size: 12px;
  color: var(--ink-2);
  padding: 0 8px;
  outline: none;
}
.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 12px;
}
.table-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.state {
  padding: 20px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
th {
  text-align: left;
  padding: 10px 14px;
  background: #f8fafc;
  color: var(--ink-4);
  font-weight: 500;
  font-size: 11px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
td {
  padding: 11px 14px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink-2);
  vertical-align: middle;
}
tbody tr {
  cursor: pointer;
}
tbody tr:hover {
  background: #fafbff;
}
.time {
  color: var(--ink-3);
  white-space: nowrap;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  color: var(--ink-1);
  white-space: nowrap;
}
.type-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  background: var(--brand-weak);
  color: #4338ca;
  font-size: 11px;
  white-space: nowrap;
}
.state-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 11px;
  border: 1px solid var(--line);
  background: #f8fafc;
  color: var(--ink-3);
  white-space: nowrap;
}
.state-chip.is-SUCCESS {
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
}
.state-chip.is-FAILED {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}
.state-chip.is-PENDING {
  background: #fffbeb;
  color: #b45309;
  border-color: #fde68a;
}
.summary {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-3);
}
.fail-text {
  color: #b91c1c;
  font-size: 11px;
}
.right {
  text-align: right;
}
.link {
  border: none;
  background: none;
  color: var(--brand);
  font-size: 11.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  font-size: 11px;
  color: var(--ink-4);
}
.pager div {
  display: flex;
  gap: 8px;
}
</style>
