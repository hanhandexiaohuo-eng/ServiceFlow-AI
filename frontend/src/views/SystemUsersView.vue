<script setup lang="ts">
/** 用户管理：仅管理员；列表服务端分页，所有写操作二次确认并落审计 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import { ROLE_LABEL, formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import {
  createAdminUser,
  listAdminUsers,
  resetAdminUserPassword,
  updateAdminUserProfile,
  updateAdminUserRole,
  updateAdminUserStatus,
  type AdminUser,
} from '../api/admin'
import type { TableColumn } from '../types/ui'

const currentUser = getCurrentUser()
const isAdmin = currentUser?.role === 'ADMIN'

const rows = ref<AdminUser[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const notice = ref('')

const filters = reactive({ keyword: '', role: '', status: '' })
const applied = reactive({ keyword: '', role: '', status: '' })

const roleOptions = ['ADMIN', 'AGENT', 'USER']
const statusLabel = (status: number) => (status === 1 ? '启用' : '停用')

const pageCount = computed(() => Math.max(Math.ceil(total.value / size), 1))

async function load() {
  if (!isAdmin) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminUsers({
      keyword: applied.keyword.trim() || undefined,
      role: applied.role || undefined,
      status: applied.status === '' ? undefined : Number(applied.status),
      page: page.value,
      size,
    })
    rows.value = result.list
    total.value = result.total
  } catch (e) {
    rows.value = []
    total.value = 0
    errorMessage.value = e instanceof Error ? e.message : '用户列表加载失败'
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
  Object.assign(filters, { keyword: '', role: '', status: '' })
  Object.assign(applied, { keyword: '', role: '', status: '' })
  page.value = 1
  load()
}

function goPage(next: number) {
  if (next < 1 || next > pageCount.value || next === page.value) return
  page.value = next
  load()
}

/* ---------------- 创建 ---------------- */
const createOpen = ref(false)
const createError = ref('')
const createForm = reactive({ username: '', name: '', role: 'AGENT', department: '', password: '' })

function openCreate() {
  Object.assign(createForm, { username: '', name: '', role: 'AGENT', department: '', password: '' })
  createError.value = ''
  createOpen.value = true
}

function randomPassword(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
  const bytes = new Uint32Array(16)
  crypto.getRandomValues(bytes)
  let text = ''
  bytes.forEach((value) => {
    text += chars[value % chars.length]
  })
  return `${text}A1`
}

async function submitCreate() {
  if (!createForm.username.trim() || !createForm.name.trim() || !createForm.password) {
    createError.value = '用户名、姓名与初始密码不能为空'
    return
  }
  busy.value = true
  createError.value = ''
  try {
    await createAdminUser({
      username: createForm.username.trim(),
      name: createForm.name.trim(),
      role: createForm.role,
      department: createForm.department.trim() || undefined,
      password: createForm.password,
    })
    notice.value = `用户 ${createForm.username.trim()} 已创建，首次登录必须修改密码`
    createOpen.value = false
    await load()
  } catch (e) {
    createError.value = e instanceof Error ? e.message : '创建失败'
  } finally {
    busy.value = false
  }
}

/* ---------------- 编辑资料 ---------------- */
const editOpen = ref(false)
const editError = ref('')
const editing = ref<AdminUser | null>(null)
const editForm = reactive({ name: '', department: '' })

function openEdit(row: AdminUser) {
  editing.value = row
  editForm.name = row.name
  editForm.department = row.department ?? ''
  editError.value = ''
  editOpen.value = true
}

async function submitEdit() {
  if (!editing.value) return
  if (!editForm.name.trim()) {
    editError.value = '姓名不能为空'
    return
  }
  busy.value = true
  editError.value = ''
  try {
    await updateAdminUserProfile(editing.value.id, {
      name: editForm.name.trim(),
      department: editForm.department.trim() || undefined,
    })
    notice.value = `${editing.value.username} 的资料已更新`
    editOpen.value = false
    await load()
  } catch (e) {
    editError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    busy.value = false
  }
}

/* ---------------- 角色 / 状态：二次确认 ---------------- */
const roleOpen = ref(false)
const roleTarget = ref<AdminUser | null>(null)
const roleValue = ref('USER')

function openRole(row: AdminUser) {
  roleTarget.value = row
  roleValue.value = row.role
  roleOpen.value = true
}

async function submitRole() {
  if (!roleTarget.value) return
  if (roleValue.value === roleTarget.value.role) {
    roleOpen.value = false
    return
  }
  busy.value = true
  try {
    await updateAdminUserRole(roleTarget.value.id, roleValue.value)
    notice.value = `${roleTarget.value.username} 的角色已改为 ${ROLE_LABEL[roleValue.value] ?? roleValue.value}`
    roleOpen.value = false
    await load()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '角色修改失败'
  } finally {
    busy.value = false
  }
}

const confirmOpen = ref(false)
const confirmTarget = ref<AdminUser | null>(null)

function openToggle(row: AdminUser) {
  confirmTarget.value = row
  confirmOpen.value = true
}

async function submitToggle() {
  if (!confirmTarget.value) return
  const target = confirmTarget.value
  const next = target.status === 1 ? 0 : 1
  busy.value = true
  try {
    await updateAdminUserStatus(target.id, next)
    notice.value = `${target.username} 已${next === 1 ? '启用' : '停用'}`
    confirmOpen.value = false
    await load()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '状态修改失败'
  } finally {
    busy.value = false
  }
}

/* ---------------- 重置密码 ---------------- */
const resetOpen = ref(false)
const resetError = ref('')
const resetTarget = ref<AdminUser | null>(null)
const resetForm = reactive({ password: '' })

function openReset(row: AdminUser) {
  resetTarget.value = row
  resetForm.password = ''
  resetError.value = ''
  resetOpen.value = true
}

async function submitReset() {
  if (!resetTarget.value) return
  if (!resetForm.password) {
    resetError.value = '请输入临时密码'
    return
  }
  busy.value = true
  resetError.value = ''
  try {
    await resetAdminUserPassword(resetTarget.value.id, resetForm.password)
    notice.value = `${resetTarget.value.username} 的密码已重置，旧会话已失效，下次登录须改密`
    resetOpen.value = false
    await load()
  } catch (e) {
    resetError.value = e instanceof Error ? e.message : '重置失败'
  } finally {
    busy.value = false
  }
}

const columns: TableColumn[] = [
  { key: 'user', label: '姓名 / 用户名', width: '180px' },
  { key: 'role', label: '角色', width: '110px' },
  { key: 'department', label: '部门 / 团队', width: '130px' },
  { key: 'status', label: '状态', width: '90px', nowrap: true },
  { key: 'passwordChangeRequired', label: '首登改密', width: '96px', nowrap: true },
  { key: 'lastLoginAt', label: '最近登录', width: '150px', nowrap: true },
  { key: 'createdAt', label: '创建时间', width: '150px', nowrap: true },
  { key: 'actions', label: '操作', width: '196px', align: 'right' },
]

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>用户管理</h1>
        <p class="head-sub">仅管理员可管理账号；停用保留历史关联，写操作全部记录审计</p>
      </div>
      <div v-if="isAdmin" class="head-right">
        <button class="btn" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button class="btn btn-primary" @click="openCreate">
          <AppIcon name="plus" :size="15" />创建用户
        </button>
      </div>
    </section>

    <template v-if="!isAdmin">
      <section class="card gate">
        <EmptyState icon="alert" title="仅管理员可访问" desc="当前账号没有用户管理权限。" />
      </section>
    </template>

    <template v-else>
      <p v-if="notice" class="notice"><AppIcon name="check" :size="15" />{{ notice }}</p>
      <p v-if="errorMessage" class="alert"><AppIcon name="alert" :size="15" />{{ errorMessage }}</p>

      <section class="filters-card">
        <div class="filter-grid">
          <label class="f-field">
            <span>关键字</span>
            <input v-model="filters.keyword" placeholder="用户名或姓名" @keyup.enter="applyFilters" />
          </label>
          <label class="f-field">
            <span>角色</span>
            <select v-model="filters.role">
              <option value="">全部角色</option>
              <option v-for="role in roleOptions" :key="role" :value="role">
                {{ ROLE_LABEL[role] ?? role }}
              </option>
            </select>
          </label>
          <label class="f-field">
            <span>状态</span>
            <select v-model="filters.status">
              <option value="">全部状态</option>
              <option value="1">启用</option>
              <option value="0">停用</option>
            </select>
          </label>
          <div class="filter-actions">
            <button class="btn btn-sm" @click="resetFilters">重置</button>
            <button class="btn btn-sm btn-primary" @click="applyFilters">
              <AppIcon name="search" :size="13" />查询
            </button>
          </div>
        </div>
      </section>

      <section class="card">
        <header class="card-head">
          <h2>账号列表</h2>
          <span class="meta">共 <b class="num">{{ total }}</b> 个账号 · 第 {{ page }} / {{ pageCount }} 页</span>
        </header>

        <DataTable
          :columns="columns"
          :rows="rows"
          row-key="id"
          :loading="loading && rows.length === 0"
          empty-text="没有符合条件的用户"
        >
          <template #cell-user="{ row }">
            <div class="who">
              <strong>{{ row.name }}</strong>
              <span class="uname">{{ row.username }}</span>
            </div>
          </template>
          <template #cell-role="{ row }">
            <span class="role">{{ ROLE_LABEL[row.role] ?? row.role }}</span>
          </template>
          <template #cell-department="{ row }">
            <span>{{ row.department || '—' }}</span>
          </template>
          <template #cell-status="{ row }">
            <span class="acct" :class="{ off: row.status !== 1 }">{{ statusLabel(row.status) }}</span>
          </template>
          <template #cell-passwordChangeRequired="{ row }">
            <span v-if="row.passwordChangeRequired" class="pill-warn">待改密</span>
            <span v-else class="muted">已完成</span>
          </template>
          <template #cell-lastLoginAt="{ row }">
            <span class="time">{{ row.lastLoginAt ? formatDateTime(row.lastLoginAt) : '从未登录' }}</span>
          </template>
          <template #cell-createdAt="{ row }">
            <span class="time">{{ row.createdAt ? formatDateTime(row.createdAt) : '—' }}</span>
          </template>
          <template #cell-actions="{ row }">
            <div class="row-actions">
              <button class="link" @click="openEdit(row as AdminUser)">编辑</button>
              <button class="link" @click="openRole(row as AdminUser)">角色</button>
              <button class="link" @click="openReset(row as AdminUser)">重置密码</button>
              <button class="link" @click="openToggle(row as AdminUser)">
                {{ row.status === 1 ? '停用' : '启用' }}
              </button>
            </div>
          </template>
        </DataTable>

        <div class="pager">
          <button class="btn btn-sm" :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
          <span class="muted">{{ page }} / {{ pageCount }}</span>
          <button class="btn btn-sm" :disabled="page >= pageCount || loading" @click="goPage(page + 1)">下一页</button>
        </div>
      </section>
    </template>

    <!-- 创建 -->
    <div v-if="createOpen" class="mask" @click.self="createOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>创建用户</h3>
            <p class="modal-sub">初始密码只写入，不在任何接口或日志中回显；首次登录强制改密</p>
          </div>
          <button class="close" title="关闭" @click="createOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>
        <div class="modal-body">
          <p v-if="createError" class="alert">{{ createError }}</p>
          <div class="row">
            <label class="field">
              <span>用户名 <i>*</i></span>
              <input v-model="createForm.username" maxlength="32" placeholder="字母、数字、. _ -" />
            </label>
            <label class="field">
              <span>姓名 <i>*</i></span>
              <input v-model="createForm.name" maxlength="64" placeholder="显示名称" />
            </label>
          </div>
          <div class="row">
            <label class="field">
              <span>角色 <i>*</i></span>
              <select v-model="createForm.role">
                <option v-for="role in roleOptions" :key="role" :value="role">
                  {{ ROLE_LABEL[role] ?? role }}
                </option>
              </select>
            </label>
            <label class="field">
              <span>部门 / 团队</span>
              <input v-model="createForm.department" maxlength="64" placeholder="可选" />
            </label>
          </div>
          <label class="field">
            <span>初始密码 <i>*</i></span>
            <div class="secret-row">
              <input v-model="createForm.password" type="password" placeholder="至少 12 位，含字母和数字" />
              <button class="btn btn-sm" @click="createForm.password = randomPassword()">随机生成</button>
            </div>
          </label>
        </div>
        <footer class="modal-foot">
          <button class="btn" @click="createOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitCreate">确认创建</button>
        </footer>
      </div>
    </div>

    <!-- 编辑资料 -->
    <div v-if="editOpen" class="mask" @click.self="editOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>编辑资料</h3>
            <p class="modal-sub">{{ editing?.username }}</p>
          </div>
          <button class="close" title="关闭" @click="editOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>
        <div class="modal-body">
          <p v-if="editError" class="alert">{{ editError }}</p>
          <label class="field">
            <span>姓名 <i>*</i></span>
            <input v-model="editForm.name" maxlength="64" />
          </label>
          <label class="field">
            <span>部门 / 团队</span>
            <input v-model="editForm.department" maxlength="64" placeholder="可选" />
          </label>
        </div>
        <footer class="modal-foot">
          <button class="btn" @click="editOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitEdit">保存</button>
        </footer>
      </div>
    </div>

    <!-- 角色调整 -->
    <div v-if="roleOpen" class="mask" @click.self="roleOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>调整角色</h3>
            <p class="modal-sub">{{ roleTarget?.username }} · 变更后旧会话立即失效</p>
          </div>
          <button class="close" title="关闭" @click="roleOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>
        <div class="modal-body">
          <label class="field">
            <span>角色</span>
            <select v-model="roleValue">
              <option v-for="role in roleOptions" :key="role" :value="role">
                {{ ROLE_LABEL[role] ?? role }}
              </option>
            </select>
          </label>
          <p class="muted">确认后该用户会被强制重新登录，并记录审计日志。</p>
        </div>
        <footer class="modal-foot">
          <button class="btn" @click="roleOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitRole">确认调整</button>
        </footer>
      </div>
    </div>

    <!-- 停用 / 启用确认 -->
    <div v-if="confirmOpen" class="mask" @click.self="confirmOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>{{ confirmTarget?.status === 1 ? '停用账号' : '启用账号' }}</h3>
            <p class="modal-sub">此操作会记录审计日志，停用后该账号立即无法登录</p>
          </div>
          <button class="close" title="关闭" @click="confirmOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>
        <div class="modal-body">
          <p>确认{{ confirmTarget?.status === 1 ? '停用' : '启用' }}
            <strong>{{ confirmTarget?.name }}（{{ confirmTarget?.username }}）</strong> 吗？</p>
        </div>
        <footer class="modal-foot">
          <button class="btn" @click="confirmOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitToggle">确认</button>
        </footer>
      </div>
    </div>

    <!-- 重置密码 -->
    <div v-if="resetOpen" class="mask" @click.self="resetOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>重置密码</h3>
            <p class="modal-sub">{{ resetTarget?.username }} · 旧会话立即失效，下次登录强制改密</p>
          </div>
          <button class="close" title="关闭" @click="resetOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>
        <div class="modal-body">
          <p v-if="resetError" class="alert">{{ resetError }}</p>
          <label class="field">
            <span>临时密码 <i>*</i></span>
            <div class="secret-row">
              <input v-model="resetForm.password" type="password" placeholder="至少 12 位，含字母和数字" />
              <button class="btn btn-sm" @click="resetForm.password = randomPassword()">随机生成</button>
            </div>
          </label>
          <p class="muted">临时密码只在本次输入中可见，提交后无法再查看，请当面或安全渠道转交。</p>
        </div>
        <footer class="modal-foot">
          <button class="btn" @click="resetOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitReset">确认重置</button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.who {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.uname {
  color: var(--text-muted);
  font-size: 12px;
}
.acct {
  color: var(--success);
  font-size: 12px;
}
.acct.off {
  color: var(--danger);
}
.pill-warn {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(245, 158, 11, 0.14);
  color: var(--warning, #d97706);
  font-size: 12px;
}
.row-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-wrap: wrap;
}
.secret-row {
  display: flex;
  gap: 8px;
}
.secret-row input {
  flex: 1;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 16px;
}
</style>
