<script setup lang="ts">
/** 智能分析详情：完整结构化结果 + 建议确认 */
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AiResultPanel from '../components/AiResultPanel.vue'
import LoadingState from '../components/LoadingState.vue'
import EmptyState from '../components/EmptyState.vue'
import {
  acceptSuggestion,
  getAnalysis,
  rejectSuggestion,
  type AiAnalysis,
  type AiSuggestion
} from '../api/ai'
import { ANALYSIS_TYPE_LABEL } from '../constants/ai'
import { formatDateTime } from '../constants/ticket'

const route = useRoute()
const router = useRouter()

const analysis = ref<AiAnalysis | null>(null)
const loading = ref(true)
const busySuggestionId = ref<number | null>(null)
const errorMessage = ref('')
const noticeMessage = ref('')

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    analysis.value = await getAnalysis(Number(route.params.id))
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '分析详情加载失败'
  } finally {
    loading.value = false
  }
}

async function decide(suggestion: AiSuggestion, accept: boolean) {
  if (!analysis.value) return
  busySuggestionId.value = suggestion.id
  errorMessage.value = ''
  try {
    if (accept) {
      await acceptSuggestion(suggestion.id)
      noticeMessage.value = '建议已采纳，业务数据已通过既有接口更新'
    } else {
      await rejectSuggestion(suggestion.id)
      noticeMessage.value = '建议已拒绝'
    }
    analysis.value = await getAnalysis(analysis.value.id)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '操作失败'
  } finally {
    busySuggestionId.value = null
  }
}

function openTicket(ticketId: number) {
  void router.push(`/tickets/${ticketId}`)
}

onMounted(load)
</script>

<template>
  <div class="page detail-page">
    <div class="detail-head">
      <button class="back" @click="router.back()">
        <AppIcon name="chevronLeft" :size="15" /> 返回
      </button>
      <div class="head-main">
        <h2>{{ analysis ? `${ANALYSIS_TYPE_LABEL[analysis.type] ?? analysis.type}详情` : '分析详情' }}</h2>
        <p v-if="analysis">
          工单
          <button class="link" @click="openTicket(analysis.ticketId)">{{ analysis.ticketNo }}</button>
          · 发起人 {{ analysis.requestedByName || '-' }} · {{ formatDateTime(analysis.createdAt) }}
        </p>
      </div>
    </div>

    <div v-if="errorMessage" class="banner banner-error">
      <AppIcon name="alert" :size="15" /> {{ errorMessage }}
    </div>
    <div v-if="noticeMessage" class="banner banner-ok">
      <AppIcon name="check" :size="15" /> {{ noticeMessage }}
    </div>

    <div v-if="loading" class="card"><LoadingState text="正在加载分析详情" /></div>
    <div v-else-if="!analysis" class="card">
      <EmptyState title="分析记录不存在" description="请从 AI 分析历史进入，或确认当前账号有访问权限。" />
    </div>
    <AiResultPanel
      v-else
      :analysis="analysis"
      :busy-id="busySuggestionId"
      @decide="decide"
      @open-ticket="openTicket"
    />
  </div>
</template>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1080px;
}
.detail-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.back {
  border: 1px solid var(--line);
  background: var(--panel);
  border-radius: 8px;
  height: 30px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
  flex: none;
}
.head-main h2 {
  font-size: 19px;
  font-weight: 600;
  color: var(--ink-1);
}
.head-main p {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-4);
}
.link {
  border: none;
  background: none;
  padding: 0;
  color: var(--brand);
  cursor: pointer;
  font-size: inherit;
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
.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 20px;
  box-shadow: var(--shadow-card);
}
</style>
