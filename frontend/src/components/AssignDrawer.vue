<script setup lang="ts">
/**
 * 分配 / 转交处理人抽屉。
 * 处理人候选来自 GET /api/users?assignable=true，提交走 POST /assign 或 /transfer，
 * 由后端状态机与权限校验，不再使用 PUT 全量更新或自由文本。
 */
import { computed, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import PriorityTag from './PriorityTag.vue'
import StatusTag from './StatusTag.vue'
import { assignTicket, transferTicket, type Ticket } from '../api/tickets'
import { listAssignableUsers, type UserOption } from '../api/users'

const props = defineProps<{ open: boolean; ticket: Ticket | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

const assignee = ref('')
const saving = ref(false)
const loading = ref(false)
const errorMessage = ref('')
const users = ref<UserOption[]>([])

const canTransfer = computed(() => props.ticket?.allowedActions.includes('TRANSFER') ?? false)
const canAssign = computed(() => props.ticket?.allowedActions.includes('ASSIGN') ?? false)
const action = computed<'TRANSFER' | 'ASSIGN' | null>(() => {
  if (canTransfer.value) return 'TRANSFER'
  if (canAssign.value) return 'ASSIGN'
  return null
})
const title = computed(() => (action.value === 'TRANSFER' ? '转交工单' : '分配工单'))

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    assignee.value = props.ticket?.assignee ?? ''
    errorMessage.value = ''
    loading.value = true
    try {
      users.value = await listAssignableUsers()
    } catch (e) {
      errorMessage.value = e instanceof Error ? e.message : '用户列表加载失败'
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

async function submit() {
  if (!props.ticket || !action.value) return
  const name = assignee.value.trim()
  if (!name) {
    errorMessage.value = '请选择处理人'
    return
  }
  if (name === props.ticket.assignee && action.value === 'TRANSFER') {
    errorMessage.value = '新处理人与当前处理人相同'
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (action.value === 'TRANSFER') {
      await transferTicket(props.ticket.id, name)
    } else {
      await assignTicket(props.ticket.id, name)
    }
    emit('saved')
    emit('close')
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="open" class="mask" @click.self="emit('close')">
    <aside class="drawer" role="dialog" aria-modal="true">
      <header class="head">
        <div>
          <h3>{{ title }}</h3>
          <p class="sub">{{ ticket?.ticketNo ?? '-' }}</p>
        </div>
        <button class="close" title="关闭" @click="emit('close')">
          <AppIcon name="close" :size="16" />
        </button>
      </header>

      <div v-if="ticket" class="body">
        <div class="ticket-brief">
          <p class="t-title">{{ ticket.title }}</p>
          <div class="t-tags">
            <StatusTag :status="ticket.status" />
            <PriorityTag :priority="ticket.priority" />
          </div>
          <p class="current">当前处理人：{{ ticket.assignee ?? '未分配' }}</p>
        </div>

        <div v-if="action" class="field-block">
          <span class="label">选择处理人</span>
          <div class="chips">
            <button
              v-for="u in users"
              :key="u.id"
              class="chip"
              :class="{ active: assignee === u.name }"
              :disabled="loading || saving"
              @click="assignee = u.name"
            >
              <b>{{ u.name }}</b>
              <em>{{ u.username }}</em>
            </button>
            <span v-if="!loading && users.length === 0" class="muted">暂无启用用户</span>
          </div>
        </div>

        <p v-if="action" class="note">
          {{ action === 'TRANSFER' ? '转交后工单回到已分配，需新处理人重新接单。' : '分配后工单进入已分配，等待处理人接单。' }}
          提交由后端状态机校验，处理人必须是启用账号。
        </p>
        <p v-else class="note warn">当前状态或权限不允许分配/转交。</p>

        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      </div>

      <footer class="foot">
        <span class="spacer" />
        <button class="btn" @click="emit('close')">取消</button>
        <button class="btn btn-primary" :disabled="saving || loading || !action" @click="submit">
          {{ saving ? '提交中…' : action === 'TRANSFER' ? '确认转交' : '确认分配' }}
        </button>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 110;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  justify-content: flex-end;
  animation: fade 0.15s ease;
}
.drawer {
  width: 420px;
  max-width: 92vw;
  height: 100%;
  background: #fff;
  display: flex;
  flex-direction: column;
  box-shadow: -18px 0 48px rgba(15, 23, 42, 0.18);
  animation: slide 0.2s ease;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 22px 14px;
  border-bottom: 1px solid var(--line);
}
.head h3 {
  font-size: 16px;
  font-weight: 600;
}
.sub {
  margin-top: 3px;
  font-family: Consolas, monospace;
  font-size: 12px;
  color: var(--ink-4);
}
.close {
  width: 28px;
  height: 28px;
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
.body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.ticket-brief {
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  background: #fafbfd;
}
.t-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
  line-height: 1.5;
}
.t-tags {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.current {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-4);
}
.field-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.label {
  font-size: 12px;
  color: var(--ink-4);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
}
.chip b {
  font-weight: 600;
}
.chip em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-4);
}
.chip:hover {
  border-color: #c7d2fe;
  color: var(--brand);
}
.chip.active {
  border-color: var(--brand);
  background: var(--brand-weak);
  color: var(--brand);
}
.chip:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.note {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #e0e7ff;
  background: #f5f7ff;
  font-size: 11px;
  color: #4338ca;
  line-height: 1.7;
}
.note.warn {
  border-color: #fde68a;
  background: #fffbeb;
  color: #b45309;
}
.error {
  font-size: 12px;
  color: var(--rose);
}
.foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 22px 18px;
  border-top: 1px solid var(--line);
  background: #fcfdfe;
}
.spacer {
  flex: 1;
}
.muted {
  font-size: 12px;
  color: var(--ink-4);
}

@keyframes fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes slide {
  from { transform: translateX(24px); opacity: 0.6; }
  to { transform: translateX(0); opacity: 1; }
}
</style>
