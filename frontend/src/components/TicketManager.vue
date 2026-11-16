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
import { getCurrentUser } from '../api/request'

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
const form = reactive<TicketForm>({ title: '', description: '', type: 'INCIDENT', priority: 'MEDIUM', status: 'OPEN', assignee: '' })

// 删除按钮仅管理员可见；真正的权限校验在后端 Service 层，前端隐藏只是体验优化
const isAdmin = getCurrentUser()?.role === 'ADMIN'
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
  Object.assign(form, { title: '', description: '', type: 'INCIDENT', priority: 'MEDIUM', status: 'OPEN', assignee: '' })
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
  <div class="page-card">
    <div class="toolbar">
      <div class="filters">
        <select v-model="filterStatus" @change="applyFilter">
          <option value="">全部状态</option>
          <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
        </select>
        <select v-model="filterPriority" @change="applyFilter">
          <option value="">全部优先级</option>
          <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
        </select>
      </div>
      <button class="primary" @click="openCreate">+ 新建工单</button>
    </div>

    <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

    <div v-if="loading" class="state muted">加载中…</div>

    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>编号</th><th>标题</th><th>类型</th><th>优先级</th><th>状态</th>
            <th>创建人</th><th>处理人</th><th>创建时间</th><th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tickets" :key="t.id">
            <td class="mono">{{ t.ticketNo }}</td>
            <td class="col-title">{{ t.title }}</td>
            <td class="muted-text">{{ TYPE_LABEL[t.type] ?? t.type }}</td>
            <td><span class="tag" :class="'p-' + t.priority">{{ PRIORITY_LABEL[t.priority] ?? t.priority }}</span></td>
            <td><span class="tag" :class="'s-' + t.status">{{ STATUS_LABEL[t.status] ?? t.status }}</span></td>
            <td>{{ t.creator }}</td>
            <td>{{ t.assignee ?? '-' }}</td>
            <td class="mono muted-text">{{ t.createdAt?.replace('T', ' ').slice(0, 16) }}</td>
            <td class="col-actions">
              <button class="link" @click="openEdit(t)">编辑</button>
              <button v-if="isAdmin" class="link danger" @click="remove(t)">删除</button>
            </td>
          </tr>
          <tr v-if="tickets.length === 0">
            <td colspan="9" class="empty">暂无工单，点击右上角「新建工单」创建第一条</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pagination">
      <span class="muted-text">共 {{ total }} 条</span>
      <div class="pager">
        <button :disabled="page <= 1" @click="page--; load()">上一页</button>
        <span class="page-no">{{ page }} / {{ totalPages() }}</span>
        <button :disabled="page >= totalPages()" @click="page++; load()">下一页</button>
      </div>
    </div>
  </div>

  <!-- 新建/编辑弹窗 -->
  <div v-if="editing" class="modal-mask" @click.self="editing = null">
    <div class="modal">
      <div class="modal-head">
        <h3>{{ editing === 'create' ? '新建工单' : `编辑工单 ${(editing as Ticket).ticketNo}` }}</h3>
        <button class="close" @click="editing = null">×</button>
      </div>

      <div class="modal-body">
        <label>标题 <i class="req">*</i><input v-model="form.title" maxlength="200" placeholder="简要描述问题或需求" /></label>
        <label>描述<textarea v-model="form.description" rows="3" placeholder="补充详细信息（选填）" /></label>
        <div class="form-row">
          <label>类型 <i class="req">*</i>
            <select v-model="form.type">
              <option v-for="tp in TYPE_OPTIONS" :key="tp" :value="tp">{{ TYPE_LABEL[tp] }}</option>
            </select>
          </label>
          <label>优先级
            <select v-model="form.priority">
              <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
            </select>
          </label>
        </div>
        <div class="form-row">
          <label v-if="editing !== 'create'">状态
            <select v-model="form.status">
              <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
            </select>
          </label>
          <label>处理人<input v-model="form.assignee" maxlength="64" placeholder="选填" /></label>
        </div>
        <p v-if="formError" class="error">{{ formError }}</p>
      </div>

      <div class="modal-actions">
        <button class="ghost" @click="editing = null">取消</button>
        <button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  padding: 20px 24px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.filters {
  display: flex;
  gap: 10px;
}

select, input, textarea {
  padding: 7px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  background: var(--bg-card);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
select:focus, input:focus, textarea:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-light);
}
select:hover, input:hover, textarea:hover {
  border-color: #cbd5e1;
}

button {
  padding: 7px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.primary {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
}
.primary:hover:not(:disabled) {
  background: var(--primary-hover);
}
.ghost:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
}
.link {
  border: none;
  background: none;
  color: var(--primary);
  padding: 2px 6px;
  font-size: 13px;
}
.link:hover {
  text-decoration: underline;
}
.link.danger {
  color: var(--danger);
}

.error {
  color: var(--danger);
  font-size: 13px;
  margin: 8px 0;
}
.muted { color: var(--text-muted); }
.muted-text { color: var(--text-sub); }
.mono { font-family: Consolas, monospace; font-size: 13px; }
.state { padding: 40px 0; text-align: center; }

.table-wrap {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th, td {
  padding: 10px 12px;
  text-align: left;
  font-size: 14px;
  white-space: nowrap;
}
th {
  background: #f8fafc;
  color: var(--text-sub);
  font-size: 12px;
  font-weight: 600;
  border-bottom: 1px solid var(--border);
}
td {
  border-bottom: 1px solid #f1f5f9;
}
tbody tr:last-child td {
  border-bottom: none;
}
tbody tr:hover {
  background: #f8fafc;
}
.col-title {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.col-actions {
  text-align: right;
}
.empty {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
}

.tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid transparent;
}
.p-LOW { background: #f1f5f9; color: #64748b; border-color: #e2e8f0; }
.p-MEDIUM { background: #dbeafe; color: #1d4ed8; border-color: #bfdbfe; }
.p-HIGH { background: #fef3c7; color: #b45309; border-color: #fde68a; }
.p-URGENT { background: #fee2e2; color: #b91c1c; border-color: #fecaca; }
.s-OPEN { background: #fee2e2; color: #b91c1c; border-color: #fecaca; }
.s-IN_PROGRESS { background: #fef3c7; color: #b45309; border-color: #fde68a; }
.s-RESOLVED { background: #d1fae5; color: #047857; border-color: #a7f3d0; }
.s-CLOSED { background: #f1f5f9; color: #64748b; border-color: #e2e8f0; }

.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  font-size: 13px;
}
.pager {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pager button {
  padding: 5px 12px;
  font-size: 13px;
}
.page-no {
  color: var(--text-sub);
}

.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: fade-in 0.15s ease;
}
.modal {
  background: var(--bg-card);
  border-radius: 14px;
  box-shadow: var(--shadow-lg);
  width: 460px;
  max-width: calc(100vw - 48px);
  animation: slide-up 0.18s ease;
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid var(--border);
}
.modal-head h3 {
  font-size: 16px;
}
.close {
  border: none;
  background: none;
  font-size: 20px;
  line-height: 1;
  color: var(--text-muted);
  padding: 2px 6px;
}
.close:hover {
  color: var(--text-main);
}
.modal-body {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.modal-body label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: var(--text-sub);
  flex: 1;
}
.req {
  color: var(--danger);
  font-style: normal;
}
.form-row {
  display: flex;
  gap: 12px;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 24px 18px;
  border-top: 1px solid var(--border);
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes slide-up {
  from { transform: translateY(12px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}
</style>
