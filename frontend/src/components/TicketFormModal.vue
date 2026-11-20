<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { createTicket, updateTicket, type Ticket, type TicketForm } from '../api/tickets'
import { listUsers, type UserOption } from '../api/users'
import {
  PRIORITY_LABEL,
  PRIORITY_OPTIONS,
  TYPE_LABEL,
  TYPE_OPTIONS,
} from '../constants/ticket'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ open: boolean; ticket: Ticket | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

/** 状态与处理人不在编辑表单内：状态由工作流接口变更，处理人走分配/转交 */
const EMPTY: TicketForm = {
  title: '',
  description: '',
  type: 'INCIDENT',
  priority: 'MEDIUM',
  assignee: '',
}

const form = reactive<TicketForm>({ ...EMPTY })
const saving = ref(false)
const errorMessage = ref('')
const users = ref<UserOption[]>([])

function reset(ticket: Ticket | null) {
  Object.assign(form, {
    title: ticket?.title ?? '',
    description: ticket?.description ?? '',
    type: ticket?.type ?? 'INCIDENT',
    priority: ticket?.priority ?? 'MEDIUM',
    assignee: '',
  })
  errorMessage.value = ''
}

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    reset(props.ticket)
    if (!props.ticket) {
      try {
        users.value = await listUsers()
      } catch {
        users.value = []
      }
    }
  },
  { immediate: true }
)

async function save() {
  if (!form.title.trim()) {
    errorMessage.value = '请填写工单标题'
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (props.ticket) {
      // 编辑只提交内容字段
      await updateTicket(props.ticket.id, { ...form, title: form.title.trim() })
    } else {
      await createTicket({ ...form, title: form.title.trim() })
    }
    emit('saved')
    emit('close')
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="open" class="mask" @click.self="emit('close')">
    <div class="modal" role="dialog" aria-modal="true">
      <header class="head">
        <div>
          <h3>{{ ticket ? '编辑工单' : '新建工单' }}</h3>
          <p class="sub">
            {{ ticket ? ticket.ticketNo : '填写工单基本信息，创建后进入待分配队列' }}
          </p>
        </div>
        <button class="close" title="关闭" @click="emit('close')">
          <AppIcon name="close" :size="16" />
        </button>
      </header>

      <div class="body">
        <label class="field">
          <span>标题 <i>*</i></span>
          <input v-model="form.title" maxlength="200" placeholder="一句话描述问题或需求" />
        </label>

        <label class="field">
          <span>描述</span>
          <textarea v-model="form.description" rows="3" placeholder="补充影响范围、复现步骤等（选填）" />
        </label>

        <div class="row">
          <label class="field">
            <span>类型 <i>*</i></span>
            <select v-model="form.type">
              <option v-for="t in TYPE_OPTIONS" :key="t" :value="t">{{ TYPE_LABEL[t] }}</option>
            </select>
          </label>
          <label class="field">
            <span>优先级</span>
            <select v-model="form.priority">
              <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ PRIORITY_LABEL[p] }}</option>
            </select>
          </label>
        </div>

        <label v-if="!ticket" class="field">
          <span>处理人（选填）</span>
          <select v-model="form.assignee">
            <option value="">暂不分配，进入待分配</option>
            <option v-for="u in users" :key="u.id" :value="u.name">
              {{ u.name }}（{{ u.username }}）
            </option>
          </select>
        </label>

        <p v-else class="hint">
          状态与处理人已由工作流托管：请在详情页使用接单、开始处理、解决、关闭、转交等操作。
        </p>

        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      </div>

      <footer class="foot">
        <button class="btn" @click="emit('close')">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  animation: fade 0.15s ease;
}
.modal {
  width: 540px;
  max-width: 100%;
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.28);
  animation: rise 0.18s ease;
  overflow: hidden;
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
.body {
  padding: 18px 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
}
.body i {
  color: var(--rose);
  font-style: normal;
}
.row {
  display: flex;
  gap: 12px;
}
.row .field {
  flex: 1;
  min-width: 0;
}
.hint {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #e0e7ff;
  background: #f5f7ff;
  font-size: 12px;
  color: #4338ca;
  line-height: 1.7;
}
.error {
  font-size: 12px;
  color: var(--rose);
}
.foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 22px 18px;
  border-top: 1px solid var(--line);
  background: #fcfdfe;
}

@keyframes fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes rise {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 560px) {
  .row {
    flex-direction: column;
  }
}
</style>
