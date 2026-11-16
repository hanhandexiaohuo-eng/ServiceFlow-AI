<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    hint?: string
    loading?: boolean
    bodyClass?: string
  }>(),
  { loading: false, bodyClass: '' }
)
</script>

<template>
  <section class="chart-card">
    <header class="head">
      <div class="titles">
        <h3>{{ title }}</h3>
        <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
      </div>
      <div class="actions">
        <slot name="actions" />
      </div>
    </header>

    <div class="body" :class="bodyClass">
      <div v-if="loading" class="skeleton" />
      <slot v-else />
    </div>

    <p v-if="hint" class="hint">{{ hint }}</p>
  </section>
</template>

<style scoped>
.chart-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 0;
}
.titles h3 {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
}
.subtitle {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-4);
}
.actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.body {
  padding: 12px 18px 16px;
  min-width: 0;
  flex: 1;
}
/* 表格类内容紧贴卡片边缘 */
.body.flush {
  padding: 12px 0 0;
}
.hint {
  padding: 0 18px 14px;
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.6;
}
.skeleton {
  height: 240px;
  border-radius: 10px;
  background: linear-gradient(90deg, #eef2f7 25%, #f7f9fc 50%, #eef2f7 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}
@keyframes shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}
</style>
