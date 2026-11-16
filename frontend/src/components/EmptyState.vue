<script setup lang="ts">
import AppIcon from './AppIcon.vue'

withDefaults(
  defineProps<{ icon?: string; title?: string; desc?: string; compact?: boolean }>(),
  { icon: 'inbox', title: '暂无数据', desc: '', compact: false }
)
</script>

<template>
  <div class="empty" :class="{ compact }">
    <span class="icon"><AppIcon :name="icon" :size="compact ? 18 : 22" /></span>
    <p class="title">{{ title }}</p>
    <p v-if="desc" class="desc">{{ desc }}</p>
    <div class="action"><slot /></div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 44px 16px;
  text-align: center;
}
.empty.compact {
  padding: 26px 12px;
}
.icon {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--line-soft);
  color: var(--ink-4);
  margin-bottom: 4px;
}
.compact .icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
}
.title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
}
.desc {
  font-size: 12px;
  color: var(--ink-4);
  max-width: 320px;
  line-height: 1.6;
}
.action:not(:empty) {
  margin-top: 8px;
}
</style>
