<script setup lang="ts">
/** 知识库管理：MySQL 关键词检索（非向量检索），支持新增/编辑与工单关联 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
import {
  KNOWLEDGE_CATEGORIES,
  KNOWLEDGE_CATEGORY_LABEL,
  KNOWLEDGE_STATUS_LABEL
} from '../constants/ai'
import { formatDateTime } from '../constants/ticket'
import { getCurrentUser } from '../api/request'
import {
  createKnowledge,
  getKnowledge,
  linkArticleToTicket,
  listArticleTickets,
  listKnowledge,
  listKnowledgeCategories,
  unlinkArticleFromTicket,
  updateKnowledge,
  type KnowledgeArticle,
  type KnowledgeCategory,
  type KnowledgeForm,
  type KnowledgeSummary,
  type LinkedTicket
} from '../api/knowledge'

const isAdmin = computed(() => getCurrentUser()?.role === 'ADMIN')

const list = ref<KnowledgeSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const category = ref('')
const categories = ref<KnowledgeCategory[]>([])
const loading = ref(true)
const detailLoading = ref(false)
const errorMessage = ref('')
const noticeMessage = ref('')

const detail = ref<KnowledgeArticle | null>(null)
const linked = ref<LinkedTicket[]>([])
const linkTicketId = ref<number | null>(null)
const linkNote = ref('')
const busy = ref(false)

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<KnowledgeForm>({
  title: '',
  category: 'NETWORK',
  problem: '',
  solution: '',
  techStack: '',
  keywords: '',
  sourceTicketId: null,
  status: 'PUBLISHED'
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listKnowledge({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      category: category.value || undefined
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    list.value = []
    total.value = 0
    errorMessage.value = error instanceof Error ? error.message : '知识库加载失败'
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await listKnowledgeCategories()
  } catch {
    categories.value = []
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

async function openDetail(id: number) {
  detailLoading.value = true
  errorMessage.value = ''
  linkTicketId.value = null
  linkNote.value = ''
  try {
    detail.value = await getKnowledge(id)
    linked.value = await listArticleTickets(id)
  } catch (error) {
    detail.value = null
    errorMessage.value = error instanceof Error ? error.message : '知识详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    title: '',
    category: 'NETWORK',
    problem: '',
    solution: '',
    techStack: '',
    keywords: '',
    sourceTicketId: null,
    status: 'PUBLISHED'
  })
}

function openCreate() {
  editingId.value = null
  resetForm()
  showForm.value = true
}

function openEdit() {
  if (!detail.value) return
  editingId.value = detail.value.id
  Object.assign(form, {
    title: detail.value.title,
    category: detail.value.category,
    problem: detail.value.problem,
    solution: detail.value.solution,
    techStack: detail.value.techStack ?? '',
    keywords: detail.value.keywords ?? '',
    sourceTicketId: detail.value.sourceTicketId,
    status: detail.value.status
  })
  showForm.value = true
}

async function save() {
  if (!form.title.trim() || !form.problem.trim() || !form.solution.trim()) {
    errorMessage.value = '标题、问题描述与解决方案为必填项'
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    const payload: KnowledgeForm = { ...form, keywords: form.keywords.trim() }
    const saved = editingId.value
      ? await updateKnowledge(editingId.value, payload)
      : await createKnowledge(payload)
    detail.value = saved
    showForm.value = false
    noticeMessage.value = editingId.value ? '知识已更新' : '知识已创建'
    await Promise.all([load(), loadCategories()])
    await openDetail(saved.id)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存失败'
  } finally {
    busy.value = false
  }
}

async function link() {
  if (!detail.value || !linkTicketId.value) {
    errorMessage.value = '请输入要关联的工单 ID'
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    await linkArticleToTicket(detail.value.id, {
      ticketId: Number(linkTicketId.value),
      note: linkNote.value.trim() || undefined
    })
    linked.value = await listArticleTickets(detail.value.id)
    linkTicketId.value = null
    linkNote.value = ''
    noticeMessage.value = '已建立知识-工单关联'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '关联失败'
  } finally {
    busy.value = false
  }
}

async function unlink(ticketId: number) {
  if (!detail.value) return
  busy.value = true
  try {
    await unlinkArticleFromTicket(detail.value.id, ticketId)
    linked.value = await listArticleTickets(detail.value.id)
    noticeMessage.value = '已解除关联'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '解除关联失败'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadCategories()])
})
</script>

<template>
  <div class="page kb-page">
    <div class="head">
      <div>
        <h2>知识库</h2>
        <p>基于 MySQL 关键词检索的真实知识条目；AI 推荐会标明来源，不编造文章。</p>
      </div>
      <button v-if="isAdmin" class="btn btn-primary" @click="openCreate">
        <AppIcon name="plus" :size="14" /> 新增知识
      </button>
    </div>

    <div class="toolbar">
      <div class="search-box">
        <AppIcon name="search" :size="14" />
        <input
          v-model="keyword"
          placeholder="按标题、问题、方案或关键词搜索"
          @keydown.enter="search"
        />
      </div>
      <select v-model="category" @change="search">
        <option value="">全部分类</option>
        <option v-for="item in KNOWLEDGE_CATEGORIES" :key="item" :value="item">
          {{ KNOWLEDGE_CATEGORY_LABEL[item] }}
        </option>
      </select>
      <button class="btn btn-sm" @click="search">
        <AppIcon name="filter" :size="13" /> 筛选
      </button>
      <span class="cat-hint">
        分类统计：{{ categories.map((c) => `${KNOWLEDGE_CATEGORY_LABEL[c.category] ?? c.category} ${c.count}`).join(' / ') || '-' }}
      </span>
    </div>

    <div v-if="errorMessage" class="banner banner-error">
      <AppIcon name="alert" :size="15" /> {{ errorMessage }}
    </div>
    <div v-if="noticeMessage" class="banner banner-ok">
      <AppIcon name="check" :size="15" /> {{ noticeMessage }}
    </div>

    <div class="kb-grid">
      <div class="list-card">
        <div v-if="loading" class="state"><LoadingState text="正在加载知识库" /></div>
        <div v-else-if="!list.length" class="state">
          <EmptyState title="没有匹配的知识" description="换个关键词试试，或由管理员新增知识条目。" />
        </div>
        <template v-else>
          <ul class="kb-list">
            <li
              v-for="item in list"
              :key="item.id"
              :class="{ active: detail?.id === item.id }"
              @click="openDetail(item.id)"
            >
              <div class="kb-top">
                <b>{{ item.title }}</b>
                <span class="kb-state">{{ KNOWLEDGE_STATUS_LABEL[item.status] ?? item.status }}</span>
              </div>
              <p class="kb-meta">
                {{ KNOWLEDGE_CATEGORY_LABEL[item.category] ?? item.category }}
                <template v-if="item.keywords"> · {{ item.keywords }}</template>
              </p>
              <p class="kb-sub">更新 {{ formatDateTime(item.updatedAt) }} · {{ item.updatedByName || '-' }}</p>
            </li>
          </ul>
          <footer class="pager">
            <span>共 {{ total }} 条 · 第 {{ page }} / {{ totalPages }} 页</span>
            <div>
              <button class="btn btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
              <button class="btn btn-sm" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
            </div>
          </footer>
        </template>
      </div>

      <aside class="detail-card">
        <div v-if="detailLoading" class="state"><LoadingState text="加载知识详情" /></div>
        <div v-else-if="!detail" class="state">
          <EmptyState title="选择一条知识" description="点击左侧条目查看完整问题与解决方案。" />
        </div>
        <template v-else>
          <header class="detail-head">
            <div>
              <h3>{{ detail.title }}</h3>
              <p>
                {{ KNOWLEDGE_CATEGORY_LABEL[detail.category] ?? detail.category }}
                · {{ KNOWLEDGE_STATUS_LABEL[detail.status] ?? detail.status }}
                · 更新 {{ formatDateTime(detail.updatedAt) }}
              </p>
            </div>
            <button v-if="isAdmin" class="btn btn-sm" @click="openEdit">编辑</button>
          </header>

          <section class="block">
            <h4>问题描述</h4>
            <p>{{ detail.problem }}</p>
          </section>
          <section class="block">
            <h4>解决方案</h4>
            <p>{{ detail.solution }}</p>
          </section>
          <section class="block">
            <h4>适用技术</h4>
            <p>{{ detail.techStack || '-' }}</p>
            <h4 class="mt">关键词</h4>
            <div class="chips">
              <span v-for="word in (detail.keywords || '').split(/[,，;；\s]+/).filter(Boolean)" :key="word" class="chip">
                {{ word }}
              </span>
              <span v-if="!detail.keywords" class="muted">未设置</span>
            </div>
          </section>

          <section class="block">
            <h4>关联工单（{{ linked.length }}）</h4>
            <ul v-if="linked.length" class="linked">
              <li v-for="item in linked" :key="item.ticketId">
                <div>
                  <b>{{ item.ticketNo }}</b>
                  <span>{{ item.title }}</span>
                  <em>{{ item.status }} · {{ formatDateTime(item.linkedAt) }} · {{ item.linkedByName || '-' }}</em>
                </div>
                <button v-if="isAdmin" class="btn btn-sm" :disabled="busy" @click="unlink(item.ticketId)">
                  解除
                </button>
              </li>
            </ul>
            <p v-else class="muted">尚未关联工单</p>
            <div v-if="isAdmin" class="link-form">
              <input v-model.number="linkTicketId" type="number" min="1" placeholder="工单 ID" />
              <input v-model="linkNote" placeholder="关联备注（可选）" />
              <button class="btn btn-sm" :disabled="busy" @click="link">关联工单</button>
            </div>
          </section>
        </template>
      </aside>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal">
        <header class="modal-head">
          <h3>{{ editingId ? '编辑知识' : '新增知识' }}</h3>
          <button class="close" @click="showForm = false"><AppIcon name="close" :size="15" /></button>
        </header>
        <div class="modal-body">
          <label class="field"><span>标题</span><input v-model="form.title" maxlength="160" /></label>
          <div class="row">
            <label class="field">
              <span>分类</span>
              <select v-model="form.category">
                <option v-for="item in KNOWLEDGE_CATEGORIES" :key="item" :value="item">
                  {{ KNOWLEDGE_CATEGORY_LABEL[item] }}
                </option>
              </select>
            </label>
            <label class="field">
              <span>状态</span>
              <select v-model="form.status">
                <option value="PUBLISHED">已发布</option>
                <option value="DRAFT">草稿</option>
                <option value="ARCHIVED">已归档</option>
              </select>
            </label>
          </div>
          <label class="field"><span>问题描述</span><textarea v-model="form.problem" rows="3" /></label>
          <label class="field"><span>解决方案</span><textarea v-model="form.solution" rows="5" /></label>
          <div class="row">
            <label class="field"><span>适用技术</span><input v-model="form.techStack" /></label>
            <label class="field"><span>关键词（逗号分隔）</span><input v-model="form.keywords" /></label>
          </div>
        </div>
        <footer class="modal-foot">
          <button class="btn btn-sm" @click="showForm = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="save">
            {{ busy ? '保存中…' : '保存' }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb-page {
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
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.search-box {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  padding: 0 10px;
  height: 32px;
  flex: 1;
  min-width: 240px;
  max-width: 420px;
  color: var(--ink-4);
}
.search-box input {
  border: none;
  outline: none;
  background: transparent;
  flex: 1;
  font-size: 12px;
  color: var(--ink-1);
}
.toolbar select {
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  font-size: 12px;
  color: var(--ink-2);
  padding: 0 8px;
  outline: none;
}
.cat-hint {
  font-size: 11px;
  color: var(--ink-4);
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
.kb-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: 14px;
  align-items: start;
}
.list-card,
.detail-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow-card);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.state {
  padding: 20px;
}
.kb-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.kb-list li {
  padding: 11px 14px;
  border-bottom: 1px solid var(--line-soft);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.kb-list li:hover {
  background: #fafbff;
}
.kb-list li.active {
  background: var(--brand-weak);
  border-left: 2px solid var(--brand);
}
.kb-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: baseline;
}
.kb-top b {
  font-size: 13px;
  color: var(--ink-1);
}
.kb-state {
  font-size: 10px;
  color: var(--ink-4);
  white-space: nowrap;
}
.kb-meta {
  font-size: 11px;
  color: var(--ink-3);
}
.kb-sub {
  font-size: 10px;
  color: var(--ink-4);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  font-size: 11px;
  color: var(--ink-4);
  border-top: 1px solid var(--line-soft);
}
.pager div {
  display: flex;
  gap: 8px;
}
.detail-card {
  padding: 16px;
  gap: 12px;
  max-height: 720px;
  overflow: auto;
}
.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  border-bottom: 1px solid var(--line-soft);
  padding-bottom: 10px;
}
.detail-head h3 {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
}
.detail-head p {
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-4);
}
.block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.block h4 {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
}
.block p {
  font-size: 12.5px;
  color: var(--ink-2);
  line-height: 1.7;
  white-space: pre-wrap;
}
.mt {
  margin-top: 6px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  font-size: 10.5px;
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--brand-weak);
  color: #4338ca;
}
.linked {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.linked li {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  padding: 7px 9px;
}
.linked li div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.linked b {
  font-size: 11.5px;
  color: var(--ink-1);
}
.linked span {
  font-size: 11px;
  color: var(--ink-3);
}
.linked em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-4);
}
.link-form {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr) auto;
  gap: 6px;
  margin-top: 4px;
}
.link-form input {
  height: 30px;
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 0 8px;
  font-size: 11.5px;
  outline: none;
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.32);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
  padding: 20px;
}
.modal {
  width: min(680px, 100%);
  max-height: 88vh;
  overflow: auto;
  background: var(--panel);
  border-radius: 14px;
  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.2);
  display: flex;
  flex-direction: column;
}
.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}
.modal-head h3 {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
}
.close {
  border: none;
  background: none;
  color: var(--ink-4);
  cursor: pointer;
  display: inline-flex;
}
.modal-body {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.modal-body .field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.modal-body .field > span {
  font-size: 11px;
  color: var(--ink-4);
}
.modal-body input,
.modal-body select,
.modal-body textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 9px;
  font-size: 12.5px;
  font-family: inherit;
  color: var(--ink-1);
  outline: none;
  resize: vertical;
}
.modal-body input:focus,
.modal-body select:focus,
.modal-body textarea:focus {
  border-color: #c7d2fe;
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.08);
}
.row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--line);
}
.muted {
  font-size: 11px;
  color: var(--ink-4);
}
</style>
