<script setup lang="ts">
import AppIcon from './AppIcon.vue'

withDefaults(
  defineProps<{
    label: string
    value: number | string
    icon?: string
    desc?: string
    tone?: 'brand' | 'cyan' | 'amber' | 'emerald' | 'rose' | 'violet'
    pending?: boolean
    loading?: boolean
  }>(),
  { icon: 'tickets', tone: 'brand', pending: false, loading: false }
)
</script>

<template>
  <div class="stat" :class="[`tone-${tone}`, { 'is-pending': pending }]">
    <div class="stat-head">
      <span class="label">{{ label }}</span>
      <span class="icon"><AppIcon :name="icon" :size="17" /></span>
    </div>

    <div v-if="loading" class="skeleton" />
    <div v-else-if="pending" class="value pending">待接入</div>
    <div v-else class="value num">{{ value }}</div>

    <p class="desc">{{ desc }}</p>
  </div>
</template>

<style scoped>
.stat {
  position: relative;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: 14px 16px 16px;
  min-width: 0;
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}
.stat:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
  border-color: #dbe1ea;
}
/* 卡片底部细色条：用于区分指标类别，避免整卡着色 */
.stat::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 3px;
  background: var(--tone);
  opacity: 0.85;
}

.stat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.icon {
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--tone);
  background: color-mix(in srgb, var(--tone) 11%, #fff);
}
.value {
  margin-top: 8px;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
}
.value.pending {
  font-size: 18px;
  font-weight: 600;
  color: var(--ink-3);
  padding: 5px 0 4px;
}
.desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.skeleton {
  margin: 10px 0 8px;
  height: 26px;
  width: 62%;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef2f7 25%, #f7f9fc 50%, #eef2f7 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}
@keyframes shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}

.tone-brand { --tone: var(--brand); }
.tone-cyan { --tone: var(--cyan); }
.tone-amber { --tone: var(--amber); }
.tone-emerald { --tone: var(--emerald); }
.tone-rose { --tone: var(--rose); }
.tone-violet { --tone: var(--violet); }
</style>
