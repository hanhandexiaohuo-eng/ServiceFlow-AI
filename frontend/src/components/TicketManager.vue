<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createTicket,
  deleteTicket,
  listTickets,
  updateTicket,
  type Ticket,
  type TicketForm,
} from '../api/tickets'

const STATUS_OPTIONS = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED']
const PRIORITY_OPTIONS = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']
const TYPE_OPTIONS = ['INCIDENT', 'SERVICE_REQUEST', 'CHANGE', 'CONSULT']

const STATUS_LABEL: Record<string, string> = {
  OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭',
}
const PRIORITY_LABEL: Record<string, string> = {
  LOW: '低', MEDIUM: '中', HIGH: '高', URGENT: '紧急',
}
const TYPE_LABEL: Record<string, string> = {
  INCIDENT: '故障申报', SERVICE_REQUEST: '服务请求', CHANGE: '变更申请', CONSULT: '业务咨询',
}

const tickets = ref<Ticket[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const filterStatus = ref('')
const filterPriority = ref('')
const loading = ref(false)
const errorMessage = ref('')

// 弹窗状态：null 关闭；'create' 新建；否则为正在编辑的工单
const editing = ref<Ticket | 'create' | null>(null)
const form = reactive<TicketForm>({ title: '', description: '', type: 'INCIDENT', priority: 'MEDIUM', status: 'OPEN', creator: '', assignee: '' })
const formError = ref('')
const saving = ref(false)

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await listTickets({
      status: filterStatus.value || undefined,
      priority: filterPriority.value || undefined,
      page: page.value,
      size,
    })
    tickets.value = data.list
    total.value = data.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  page.value = 1
  load()
}

const totalPages = () => Math.max(1, Math.ceil(total.value / size))

function openCreate() {
  Object.assign(form, { title: '', description: '', type: 'INCIDENT', priority: 'MEDIUM', status: 'OPEN', creator: '', assignee: '' })
  formError.value = ''
  editing.value = 'create'
}

function openEdit(t: Ticket) {
  Object.assign(form, {
    title: t.title, description: t.description ?? '', type: t.type,
    priority: t.priority, status: t.status, assignee: t.assignee ?? '',
  })
  formError.value = ''
  editing.value = t
}

async function save() {
  saving.value = true
  formError.value = ''
  try {
    if (editing.value === 'create') {
      await createTicket(form)
    } else if (editing.value) {
      await updateTicket(editing.value.id, form)
    }
    editing.value = null
    await load()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(t: Ticket) {
  if (!confirm(`确认删除工单 ${t.ticketNo}？`)) return
  try {
    await deleteTicket(t.id)
    // 删除后若当前页空了且不是第一页，回退一页
    if (tickets.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="toolbar">
    <select v-model="filterStatus" @change="applyFilter">
      <option value="">全部状态</option>
      <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
    </select>
    <select v-model="filterPriority" @change="applyFilter">
      <option value="">全部优先级</option>
      <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
    </select>
    <button class="primary" @click="openCreate">新建工单</button>
  </div>

  <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
  <p v-if="loading" class="muted">加载中…</p>

  <table v-else>
    <thead>
      <tr>
        <th>编号</th><th>标题</th><th>类型</th><th>优先级</th><th>状态</th>
        <th>创建人</th><th>处理人</th><th>创建时间</th><th>操作</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="t in tickets" :key="t.id">
        <td class="mono">{{ t.ticketNo }}</td>
        <td>{{ t.title }}</td>
        <td>{{ TYPE_LABEL[t.type] ?? t.type }}</td>
        <td><span class="tag" :class="'p-' + t.priority">{{ PRIORITY_LABEL[t.priority] ?? t.priority }}</span></td>
        <td><span class="tag" :class="'s-' + t.status">{{ STATUS_LABEL[t.status] ?? t.status }}</span></td>
        <td>{{ t.creator }}</td>
        <td>{{ t.assignee ?? '-' }}</td>
        <td class="mono">{{ t.createdAt?.replace('T', ' ').slice(0, 16) }}</td>
        <td>
          <button class="link" @click="openEdit(t)">编辑</button>
          <button class="link danger" @click="remove(t)">删除</button>
        </td>
      </tr>
      <tr v-if="tickets.length === 0">
        <td colspan="9" class="muted center">暂无工单</td>
      </tr>
    </tbody>
  </table>

  <div class="pagination">
    <button :disabled="page <= 1" @click="page--; load()">上一页</button>
    <span>{{ page }} / {{ totalPages() }} 页（共 {{ total }} 条）</span>
    <button :disabled="page >= totalPages()" @click="page++; load()">下一页</button>
  </div>

  <!-- 新建/编辑弹窗 -->
  <div v-if="editing" class="modal-mask" @click.self="editing = null">
    <div class="modal">
      <h3>{{ editing === 'create' ? '新建工单' : `编辑工单 ${(editing as Ticket).ticketNo}` }}</h3>
      <label>标题 *<input v-model="form.title" maxlength="200" /></label>
      <label>描述<textarea v-model="form.description" rows="3" /></label>
      <label>类型 *
        <select v-model="form.type">
          <option v-for="tp in TYPE_OPTIONS" :key="tp" :value="tp">{{ TYPE_LABEL[tp] }}</option>
        </select>
      </label>
      <label>优先级
        <select v-model="form.priority">
          <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
        </select>
      </label>
      <label v-if="editing !== 'create'">状态
        <select v-model="form.status">
          <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
        </select>
      </label>
      <label v-if="editing === 'create'">创建人 *<input v-model="form.creator" maxlength="64" /></label>
      <label>处理人<input v-model="form.assignee" maxlength="64" /></label>
      <p v-if="formError" class="error">{{ formError }}</p>
      <div class="modal-actions">
        <button @click="editing = null">取消</button>
        <button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
select, input, textarea {
  padding: 6px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px;
}
button { padding: 6px 14px; border: 1px solid #d0d7de; border-radius: 6px; background: #fff; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.link { border: none; background: none; color: #3b82f6; padding: 2px 6px; }
.link.danger { color: #dc2626; }
.error { color: #dc2626; margin: 8px 0; }
.muted { color: #7a8a99; }
.center { text-align: center; }
.mono { font-family: Consolas, monospace; font-size: 13px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #e4e9ef; padding: 8px 10px; text-align: left; font-size: 14px; }
th { background: #f8fafc; }
.tag { padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.p-LOW { background: #f1f5f9; } .p-MEDIUM { background: #dbeafe; }
.p-HIGH { background: #fef3c7; } .p-URGENT { background: #fee2e2; color: #b91c1c; }
.s-OPEN { background: #fee2e2; } .s-IN_PROGRESS { background: #fef3c7; }
.s-RESOLVED { background: #d1fae5; } .s-CLOSED { background: #f1f5f9; }
.pagination { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.modal-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center;
}
.modal {
  background: #fff; border-radius: 10px; padding: 24px; width: 420px;
  display: flex; flex-direction: column; gap: 10px;
}
.modal label { display: flex; flex-direction: column; gap: 4px; font-size: 14px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
</style>
