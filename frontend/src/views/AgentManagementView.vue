<script setup lang="ts">
/** 人员管理：账号与技能编辑仅管理员；处理人员只读，仅可调整自己的工作状态 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import { AVAILABILITY_LABEL, AVAILABILITY_OPTIONS, PROFICIENCY_OPTIONS } from '../constants/dispatch'
import { ROLE_LABEL, formatDateTime } from '../constants/ticket'
import {
  createAgent,
  listAgents,
  listSkills,
  replaceAgentSkills,
  updateAgent,
  updateAgentAvailability,
  type Agent,
  type AgentCreateBody,
  type AgentSkillInput,
  type Skill,
} from '../api/agents'
import { getCurrentUser } from '../api/request'
import type { TableColumn } from '../types/ui'

const currentUser = getCurrentUser()
const isAdmin = currentUser?.role === 'ADMIN'
const isAgent = currentUser?.role === 'AGENT'
const canView = isAdmin || isAgent

const agents = ref<Agent[]>([])
const skills = ref<Skill[]>([])
const loading = ref(false)
const errorMessage = ref('')
const notice = ref('')
const busyId = ref<number | null>(null)

const filters = reactive({ keyword: '', availability: '', skillCode: '' })
const applied = reactive({ keyword: '', availability: '', skillCode: '' })

async function loadAgents() {
  if (!canView) return
  loading.value = true
  errorMessage.value = ''
  try {
    agents.value = await listAgents({
      keyword: applied.keyword.trim() || undefined,
      availability: applied.availability || undefined,
      skillCode: applied.skillCode || undefined,
    })
  } catch (e) {
    agents.value = []
    errorMessage.value = e instanceof Error ? e.message : '人员列表加载失败'
  } finally {
    loading.value = false
  }
}

async function loadSkillCatalog() {
  try {
    skills.value = await listSkills()
  } catch (e) {
    skills.value = []
    errorMessage.value = e instanceof Error ? e.message : '技能字典加载失败'
  }
}

function applyFilters() {
  Object.assign(applied, {
    keyword: filters.keyword,
    availability: filters.availability,
    skillCode: filters.skillCode,
  })
  loadAgents()
}

function resetFilters() {
  Object.assign(filters, { keyword: '', availability: '', skillCode: '' })
  Object.assign(applied, { keyword: '', availability: '', skillCode: '' })
  loadAgents()
}

/* ---------------- 状态快捷调整 ---------------- */

function canEditAvailability(agent: Agent): boolean {
  if (isAdmin) return true
  return isAgent && currentUser?.id === agent.id
}

function availabilityChoices(agent: Agent): string[] {
  if (isAdmin) return [...AVAILABILITY_OPTIONS]
  return AVAILABILITY_OPTIONS.filter((item) => item !== 'DISABLED')
}

async function onQuickAvailability(agent: Agent, event: Event) {
  const target = event.target as HTMLSelectElement | null
  if (!target) return
  const next = target.value
  if (next === agent.availability) return
  busyId.value = agent.id
  errorMessage.value = ''
  notice.value = ''
  try {
    await updateAgentAvailability(agent.id, next)
    await loadAgents()
    notice.value = `${agent.name} 的工作状态已更新为 ${AVAILABILITY_LABEL[next] ?? next}`
  } catch (e) {
    target.value = agent.availability
    errorMessage.value = e instanceof Error ? e.message : '状态更新失败'
  } finally {
    busyId.value = null
  }
}

/* ---------------- 技能草稿 ---------------- */

function isSkillSelected(list: AgentSkillInput[], skillId: number): boolean {
  return list.some((item) => item.skillId === skillId)
}

function toggleSkill(list: AgentSkillInput[], skillId: number) {
  const index = list.findIndex((item) => item.skillId === skillId)
  if (index >= 0) list.splice(index, 1)
  else list.push({ skillId, proficiencyLevel: 2 })
}

function levelOf(list: AgentSkillInput[], skillId: number): number {
  return list.find((item) => item.skillId === skillId)?.proficiencyLevel ?? 2
}

function changeLevel(list: AgentSkillInput[], skillId: number, event: Event) {
  const target = event.target as HTMLSelectElement | null
  if (!target) return
  const item = list.find((entry) => entry.skillId === skillId)
  if (item) item.proficiencyLevel = Number(target.value)
}

/* ---------------- 创建 / 提升 ---------------- */

type FormMode = 'CREATE' | 'PROMOTE'

const formOpen = ref(false)
const formMode = ref<FormMode>('CREATE')
const formSaving = ref(false)
const formError = ref('')

const form = reactive({
  userId: '',
  username: '',
  name: '',
  password: '',
  maxConcurrent: 5,
  availability: 'AVAILABLE',
  skills: [] as AgentSkillInput[],
})

function openCreate() {
  formMode.value = 'CREATE'
  form.userId = ''
  form.username = ''
  form.name = ''
  form.password = ''
  form.maxConcurrent = 5
  form.availability = 'AVAILABLE'
  form.skills.splice(0, form.skills.length)
  formError.value = ''
  formOpen.value = true
}

function switchMode(mode: FormMode) {
  formMode.value = mode
  formError.value = ''
}

function openPromote() {
  openCreate()
  formMode.value = 'PROMOTE'
}

function validateForm(): string {
  if (!Number.isInteger(form.maxConcurrent) || form.maxConcurrent < 1 || form.maxConcurrent > 50) {
    return '最大并发需为 1-50 的整数'
  }
  if (formMode.value === 'PROMOTE') {
    const id = Number(form.userId)
    if (!Number.isInteger(id) || id <= 0) return '请填写有效的用户 ID（正整数）'
    return ''
  }
  if (!form.username.trim()) return '请填写用户名'
  if (!form.name.trim()) return '请填写姓名'
  if (form.password.length < 6 || form.password.length > 72) return '密码长度需为 6-72 个字符'
  return ''
}

async function saveForm() {
  const invalid = validateForm()
  if (invalid) {
    formError.value = invalid
    return
  }
  formSaving.value = true
  formError.value = ''
  notice.value = ''
  const body: AgentCreateBody = {
    maxConcurrent: form.maxConcurrent,
    availability: form.availability,
    skills: form.skills.length ? form.skills.map((item) => ({ ...item })) : undefined,
  }
  if (formMode.value === 'PROMOTE') {
    body.userId = Number(form.userId)
  } else {
    body.username = form.username.trim()
    body.name = form.name.trim()
    body.password = form.password
  }
  try {
    await createAgent(body)
    notice.value = formMode.value === 'PROMOTE' ? '已有用户已提升为处理人员' : '人员创建成功'
    formOpen.value = false
    await loadAgents()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    formSaving.value = false
  }
}

/* ---------------- 编辑并发与状态 ---------------- */

const editOpen = ref(false)
const editing = ref<Agent | null>(null)
const editSaving = ref(false)
const editError = ref('')
const editForm = reactive({ maxConcurrent: 1, availability: 'AVAILABLE' })

function openEdit(agent: Agent) {
  editing.value = agent
  editForm.maxConcurrent = agent.maxConcurrent
  editForm.availability = agent.availability
  editError.value = ''
  editOpen.value = true
}

async function saveEdit() {
  if (!editing.value) return
  if (!Number.isInteger(editForm.maxConcurrent) || editForm.maxConcurrent < 1 || editForm.maxConcurrent > 50) {
    editError.value = '最大并发需为 1-50 的整数'
    return
  }
  editSaving.value = true
  editError.value = ''
  notice.value = ''
  try {
    await updateAgent(editing.value.id, {
      maxConcurrent: editForm.maxConcurrent,
      availability: editForm.availability,
    })
    notice.value = `${editing.value.name} 的并发与状态已更新`
    editOpen.value = false
    await loadAgents()
  } catch (e) {
    editError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    editSaving.value = false
  }
}

/* ---------------- 编辑技能 ---------------- */

const skillOpen = ref(false)
const skillAgent = ref<Agent | null>(null)
const skillDraft = ref<AgentSkillInput[]>([])
const skillSaving = ref(false)
const skillError = ref('')

function openSkills(agent: Agent) {
  skillAgent.value = agent
  skillDraft.value = agent.skills.map((item) => ({
    skillId: item.skillId,
    proficiencyLevel: item.proficiencyLevel,
  }))
  skillError.value = ''
  skillOpen.value = true
}

async function saveSkills() {
  if (!skillAgent.value) return
  skillSaving.value = true
  skillError.value = ''
  notice.value = ''
  try {
    await replaceAgentSkills(
      skillAgent.value.id,
      skillDraft.value.map((item) => ({ ...item }))
    )
    notice.value = `${skillAgent.value.name} 的技能已更新`
    skillOpen.value = false
    await loadAgents()
  } catch (e) {
    skillError.value = e instanceof Error ? e.message : '技能保存失败'
  } finally {
    skillSaving.value = false
  }
}

const columns: TableColumn[] = [
  { key: 'name', label: '姓名 / 用户名', width: '170px' },
  { key: 'role', label: '角色', width: '96px', nowrap: true },
  { key: 'availability', label: '可用状态', width: '188px', nowrap: true },
  { key: 'maxConcurrent', label: '最大并发', width: '88px', align: 'center' },
  { key: 'skills', label: '技能', width: '300px' },
  { key: 'accountStatus', label: '账号状态', width: '96px', nowrap: true },
  { key: 'updatedAt', label: '更新时间', width: '146px', nowrap: true },
  { key: 'actions', label: '操作', width: '132px', align: 'right' },
]

const skillCount = computed(() => agents.value.length)

onMounted(async () => {
  if (!canView) return
  await Promise.all([loadSkillCatalog(), loadAgents()])
})
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>人员管理</h1>
        <p class="head-sub">
          <template v-if="isAdmin">
            管理员可创建人员、提升已有用户、编辑并发 / 状态与技能；技能字典来自后端
          </template>
          <template v-else>只读视图 · 仅可调整自己的工作状态，不能设为已禁用</template>
        </p>
      </div>
      <div v-if="isAdmin" class="head-right">
        <button class="btn" :disabled="loading" @click="loadAgents">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button class="btn btn-primary" @click="openCreate">
          <AppIcon name="plus" :size="15" />创建人员
        </button>
        <button class="btn" @click="openPromote">
          <AppIcon name="arrowRight" :size="14" />提升已有用户
        </button>
      </div>
    </section>

    <template v-if="!canView">
      <section class="card gate">
        <EmptyState
          icon="alert"
          title="仅管理员与处理人员可查看人员管理"
          desc="当前账号没有人员管理权限。"
        />
      </section>
    </template>

    <template v-else>
      <p v-if="notice" class="notice"><AppIcon name="check" :size="15" />{{ notice }}</p>
      <p v-if="errorMessage" class="alert"><AppIcon name="alert" :size="15" />{{ errorMessage }}</p>

      <section class="filters-card">
        <div class="filter-grid">
          <label class="f-field">
            <span>关键字</span>
            <input
              v-model="filters.keyword"
              placeholder="姓名或用户名"
              @keyup.enter="applyFilters"
            />
          </label>
          <label class="f-field">
            <span>可用状态</span>
            <select v-model="filters.availability">
              <option value="">全部状态</option>
              <option v-for="opt in AVAILABILITY_OPTIONS" :key="opt" :value="opt">
                {{ AVAILABILITY_LABEL[opt] }}
              </option>
            </select>
          </label>
          <label class="f-field">
            <span>技能</span>
            <select v-model="filters.skillCode">
              <option value="">全部技能</option>
              <option v-for="s in skills" :key="s.id" :value="s.skillCode">{{ s.skillName }}</option>
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
          <h2>处理人员</h2>
          <span class="meta">
            共 <b class="num">{{ skillCount }}</b> 人 · 账号禁用时状态强制显示为「已禁用」
          </span>
        </header>

        <DataTable
          :columns="columns"
          :rows="agents"
          row-key="id"
          :loading="loading && agents.length === 0"
          empty-text="没有符合条件的人员"
        >
          <template #cell-name="{ row }">
            <div class="who">
              <strong>{{ row.name }}</strong>
              <span class="uname">{{ row.username }}</span>
            </div>
          </template>
          <template #cell-role="{ row }">
            <span class="role">{{ ROLE_LABEL[row.role] ?? row.role }}</span>
          </template>
          <template #cell-availability="{ row }">
            <div class="avail-cell">
              <span class="avail" :class="`is-${row.effectiveAvailability}`">
                {{ AVAILABILITY_LABEL[row.effectiveAvailability] ?? row.effectiveAvailability }}
              </span>
              <select
                v-if="canEditAvailability(row as Agent)"
                class="mini-select"
                :value="row.availability"
                :disabled="busyId === row.id"
                title="快捷调整工作状态"
                @change="onQuickAvailability(row as Agent, $event)"
              >
                <option v-for="opt in availabilityChoices(row as Agent)" :key="opt" :value="opt">
                  {{ AVAILABILITY_LABEL[opt] }}
                </option>
              </select>
            </div>
          </template>
          <template #cell-maxConcurrent="{ row }">
            <span class="num">{{ row.maxConcurrent }}</span>
          </template>
          <template #cell-skills="{ row }">
            <div v-if="row.skills.length" class="skill-tags">
              <span v-for="s in row.skills" :key="s.skillId" class="skill-tag">
                {{ s.skillName }}<em>{{ s.proficiencyLabel }}</em>
              </span>
            </div>
            <span v-else class="muted">未配置</span>
          </template>
          <template #cell-accountStatus="{ row }">
            <span class="acct" :class="{ off: row.accountStatus !== 1 }">
              {{ row.accountStatus === 1 ? '正常' : '已禁用' }}
            </span>
          </template>
          <template #cell-updatedAt="{ row }">
            <span class="time">{{ formatDateTime(row.updatedAt) }}</span>
          </template>
          <template #cell-actions="{ row }">
            <div v-if="isAdmin" class="row-actions">
              <button class="link" @click="openEdit(row as Agent)">编辑</button>
              <button class="link" @click="openSkills(row as Agent)">技能</button>
            </div>
            <span v-else class="muted">—</span>
          </template>
        </DataTable>
      </section>
    </template>

    <!-- 创建 / 提升 -->
    <div v-if="formOpen" class="mask" @click.self="formOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>添加处理人员</h3>
            <p class="modal-sub">新建账号或把已有用户提升为处理人员</p>
          </div>
          <button class="close" title="关闭" @click="formOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>

        <div class="modal-body">
          <div class="mode-tabs">
            <button
              class="mode-tab"
              :class="{ active: formMode === 'CREATE' }"
              @click="switchMode('CREATE')"
            >
              新建账号
            </button>
            <button
              class="mode-tab"
              :class="{ active: formMode === 'PROMOTE' }"
              @click="switchMode('PROMOTE')"
            >
              提升已有用户
            </button>
          </div>

          <template v-if="formMode === 'CREATE'">
            <div class="row">
              <label class="field">
                <span>用户名 <i>*</i></span>
                <input v-model="form.username" maxlength="32" placeholder="登录用户名" />
              </label>
              <label class="field">
                <span>姓名 <i>*</i></span>
                <input v-model="form.name" maxlength="64" placeholder="唯一显示名" />
              </label>
            </div>
            <label class="field">
              <span>初始密码 <i>*</i></span>
              <input v-model="form.password" type="password" placeholder="6-72 个字符" />
            </label>
          </template>

          <label v-else class="field">
            <span>已有用户 ID <i>*</i></span>
            <input v-model="form.userId" inputmode="numeric" placeholder="users 表中的用户 ID" />
            <small class="field-hint">该用户需为启用状态的 USER 或 AGENT，管理员账号无需提升</small>
          </label>

          <div class="row">
            <label class="field">
              <span>最大并发 <i>*</i></span>
              <input v-model.number="form.maxConcurrent" type="number" min="1" max="50" />
            </label>
            <label class="field">
              <span>工作状态</span>
              <select v-model="form.availability">
                <option v-for="opt in AVAILABILITY_OPTIONS" :key="opt" :value="opt">
                  {{ AVAILABILITY_LABEL[opt] }}
                </option>
              </select>
            </label>
          </div>

          <div class="field">
            <span>技能（可选）</span>
            <div v-if="skills.length === 0" class="muted">技能字典为空，可稍后再编辑</div>
            <div v-else class="skill-picker">
              <div v-for="s in skills" :key="s.id" class="skill-option">
                <label class="skill-check">
                  <input
                    type="checkbox"
                    :checked="isSkillSelected(form.skills, s.id)"
                    @change="toggleSkill(form.skills, s.id)"
                  />
                  <span>{{ s.skillName }}</span>
                </label>
                <select
                  v-if="isSkillSelected(form.skills, s.id)"
                  class="mini-select"
                  :value="levelOf(form.skills, s.id)"
                  @change="changeLevel(form.skills, s.id, $event)"
                >
                  <option v-for="p in PROFICIENCY_OPTIONS" :key="p.value" :value="p.value">
                    {{ p.label }}
                  </option>
                </select>
              </div>
            </div>
          </div>

          <p v-if="formError" class="form-error">{{ formError }}</p>
        </div>

        <footer class="modal-foot">
          <button class="btn" @click="formOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="formSaving" @click="saveForm">
            {{ formSaving ? '保存中…' : formMode === 'PROMOTE' ? '提升为处理人员' : '创建' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- 编辑并发与状态 -->
    <div v-if="editOpen && editing" class="mask" @click.self="editOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>编辑人员</h3>
            <p class="modal-sub">{{ editing.name }}（{{ editing.username }}）</p>
          </div>
          <button class="close" title="关闭" @click="editOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>

        <div class="modal-body">
          <div class="row">
            <label class="field">
              <span>最大并发 <i>*</i></span>
              <input v-model.number="editForm.maxConcurrent" type="number" min="1" max="50" />
            </label>
            <label class="field">
              <span>工作状态</span>
              <select v-model="editForm.availability">
                <option v-for="opt in AVAILABILITY_OPTIONS" :key="opt" :value="opt">
                  {{ AVAILABILITY_LABEL[opt] }}
                </option>
              </select>
            </label>
          </div>
          <p v-if="editError" class="form-error">{{ editError }}</p>
        </div>

        <footer class="modal-foot">
          <button class="btn" @click="editOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="editSaving" @click="saveEdit">
            {{ editSaving ? '保存中…' : '保存' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- 编辑技能 -->
    <div v-if="skillOpen && skillAgent" class="mask" @click.self="skillOpen = false">
      <div class="modal" role="dialog" aria-modal="true">
        <header class="modal-head">
          <div>
            <h3>编辑技能</h3>
            <p class="modal-sub">{{ skillAgent.name }}（{{ skillAgent.username }}）</p>
          </div>
          <button class="close" title="关闭" @click="skillOpen = false">
            <AppIcon name="close" :size="16" />
          </button>
        </header>

        <div class="modal-body">
          <div v-if="skills.length === 0" class="muted">技能字典为空，请先在后端补充技能数据</div>
          <div v-else class="skill-picker">
            <div v-for="s in skills" :key="s.id" class="skill-option">
              <label class="skill-check">
                <input
                  type="checkbox"
                  :checked="isSkillSelected(skillDraft, s.id)"
                  @change="toggleSkill(skillDraft, s.id)"
                />
                <span>{{ s.skillName }}</span>
              </label>
              <select
                v-if="isSkillSelected(skillDraft, s.id)"
                class="mini-select"
                :value="levelOf(skillDraft, s.id)"
                @change="changeLevel(skillDraft, s.id, $event)"
              >
                <option v-for="p in PROFICIENCY_OPTIONS" :key="p.value" :value="p.value">
                  {{ p.label }}
                </option>
              </select>
            </div>
          </div>
          <p v-if="skillError" class="form-error">{{ skillError }}</p>
        </div>

        <footer class="modal-foot">
          <button class="btn" @click="skillOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="skillSaving" @click="saveSkills">
            {{ skillSaving ? '保存中…' : '保存技能' }}
          </button>
        </footer>
      </div>
    </div>
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
.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
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
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line-soft);
  flex-wrap: wrap;
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

/* 筛选 */
.filters-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: 14px 16px;
}
.filter-grid {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.f-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 190px;
}
.f-field > span {
  font-size: 11px;
  color: var(--ink-4);
  font-weight: 600;
  letter-spacing: 0.03em;
}
.f-field input,
.f-field select {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fafbfd;
  font-size: 12px;
  color: var(--ink-1);
}
.f-field input:focus,
.f-field select:focus {
  outline: none;
  background: #fff;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

/* 表格单元 */
.who {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.who strong {
  font-size: 13px;
  color: var(--ink-1);
  font-weight: 600;
}
.uname {
  font-size: 11px;
  color: var(--ink-4);
}
.role {
  font-size: 12px;
  color: var(--ink-2);
}
.avail-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
.avail {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 11px;
  white-space: nowrap;
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
.mini-select {
  height: 24px;
  padding: 0 4px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  font-size: 11px;
  color: var(--ink-3);
  max-width: 96px;
}
.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.skill-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 21px;
  padding: 0 7px;
  border-radius: 5px;
  background: var(--brand-weak);
  color: #4338ca;
  font-size: 11px;
  white-space: nowrap;
}
.skill-tag em {
  font-style: normal;
  font-size: 10px;
  color: #6366f1;
}
.acct {
  font-size: 11px;
  color: #047857;
}
.acct.off {
  color: #b91c1c;
  font-weight: 700;
}
.time {
  font-size: 12px;
  color: var(--ink-4);
  font-variant-numeric: tabular-nums;
}
.muted {
  font-size: 12px;
  color: var(--ink-4);
}
.row-actions {
  display: inline-flex;
  gap: 2px;
  justify-content: flex-end;
}
.link {
  border: none;
  background: none;
  padding: 2px 4px;
  font-size: 12px;
  color: var(--brand);
  cursor: pointer;
  white-space: nowrap;
}
.link:hover {
  text-decoration: underline;
}

/* 弹窗 */
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.modal {
  width: 560px;
  max-width: 100%;
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.28);
  overflow: hidden;
}
.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 22px 14px;
  border-bottom: 1px solid var(--line);
}
.modal-head h3 {
  font-size: 16px;
  font-weight: 600;
}
.modal-sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.close {
  width: 28px;
  height: 28px;
  flex: none;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-4);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.close:hover {
  background: var(--line-soft);
  color: var(--ink-1);
}
.modal-body {
  padding: 16px 22px;
  display: flex;
  flex-direction: column;
  gap: 13px;
  overflow-y: auto;
}
.mode-tabs {
  display: flex;
  gap: 6px;
  padding: 3px;
  border-radius: 8px;
  background: var(--line-soft);
}
.mode-tab {
  flex: 1;
  height: 30px;
  border: none;
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  color: var(--ink-3);
  cursor: pointer;
}
.mode-tab.active {
  background: #fff;
  color: var(--brand);
  font-weight: 600;
  box-shadow: var(--shadow-card);
}
.row {
  display: flex;
  gap: 12px;
}
.row .field {
  flex: 1;
  min-width: 0;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-3);
}
.field > span {
  font-weight: 600;
}
.field i {
  color: var(--rose);
  font-style: normal;
}
.field > input,
.field > select {
  height: 34px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctrl);
  background: #fff;
  font-size: 13px;
  color: var(--ink-1);
}
.field > input:focus,
.field > select:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-weak);
}
.field-hint {
  font-size: 11px;
  color: var(--ink-4);
}
.skill-picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 220px;
  overflow-y: auto;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fafbfd;
}
.skill-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.skill-check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
}
.skill-check input {
  width: 14px;
  height: 14px;
  accent-color: var(--brand);
}
.form-error {
  font-size: 12px;
  color: var(--rose);
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 22px 18px;
  border-top: 1px solid var(--line);
  background: #fcfdfe;
}

@media (max-width: 640px) {
  .row {
    flex-direction: column;
  }
}
</style>
