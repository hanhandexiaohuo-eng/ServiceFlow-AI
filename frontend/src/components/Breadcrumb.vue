<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import type { CrumbItem } from '../types/ui'

defineProps<{ items: CrumbItem[] }>()
</script>

<template>
  <nav class="crumbs" aria-label="面包屑">
    <template v-for="(item, index) in items" :key="item.label">
      <router-link v-if="item.to && index < items.length - 1" :to="item.to" class="link">{{ item.label }}</router-link>
      <span v-else class="current">{{ item.label }}</span>
      <AppIcon v-if="index < items.length - 1" name="chevronRight" :size="12" class="sep" />
    </template>
  </nav>
</template>

<style scoped>
.crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-4);
  min-width: 0;
}
.link {
  color: var(--ink-4);
  transition: color 0.15s ease;
}
.link:hover {
  color: var(--brand);
}
.current {
  color: var(--ink-3);
}
.sep {
  color: #cbd5e1;
  flex: none;
  margin: 0 1px;
}
</style>
