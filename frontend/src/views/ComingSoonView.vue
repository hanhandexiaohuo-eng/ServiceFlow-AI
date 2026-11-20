<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'

const route = useRoute()
const router = useRouter()

const title = computed(() => route.meta.title ?? '待接入功能')
const group = computed(() => route.meta.group ?? '')
const desc = computed(() => route.meta.desc ?? '该页面尚未接入后端能力。')
const points = computed(() => route.meta.points ?? [])
</script>

<template>
  <div class="page">
    <section class="card">
      <div class="badge">功能未启用</div>
      <span class="icon"><AppIcon name="layers" :size="24" /></span>
      <h1>{{ title }}</h1>
      <p v-if="group" class="group">{{ group }}</p>
      <p class="desc">{{ desc }}</p>

      <div v-if="points.length" class="points">
        <p class="points-title">接入后提供</p>
        <ul>
          <li v-for="p in points" :key="p">
            <span class="dot" />
            {{ p }}
          </li>
        </ul>
      </div>

      <div class="actions">
        <button class="btn btn-primary" @click="router.push('/')">返回数据驾驶舱</button>
        <button class="btn" @click="router.push('/tickets')">查看工单列表</button>
      </div>

      <p class="footnote">
        该功能尚未启用（功能开关关闭），不展示任何模拟数据或伪造界面。菜单已隐藏该入口，直接访问仅显示本说明页。
      </p>
    </section>
  </div>
</template>

<style scoped>
.page {
  min-height: calc(100vh - var(--topbar-h));
  justify-content: center;
  align-items: center;
}
.card {
  position: relative;
  width: min(640px, 100%);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: var(--shadow-card);
  padding: 36px 34px 28px;
  text-align: center;
}
.badge {
  position: absolute;
  top: 18px;
  right: 18px;
  font-size: 11px;
  color: #92400e;
  background: #fef3c7;
  border: 1px solid #fde68a;
  padding: 2px 8px;
  border-radius: 999px;
}
.icon {
  width: 52px;
  height: 52px;
  margin: 0 auto 14px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-weak);
  color: var(--brand);
}
h1 {
  font-size: 20px;
  font-weight: 700;
}
.group {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-4);
}
.desc {
  margin-top: 10px;
  font-size: 13px;
  color: var(--ink-3);
  line-height: 1.75;
}
.points {
  margin: 20px auto 0;
  max-width: 380px;
  text-align: left;
  background: #fafbfd;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  padding: 14px 16px;
}
.points-title {
  font-size: 11px;
  letter-spacing: 0.05em;
  color: var(--ink-4);
  margin-bottom: 8px;
}
.points ul {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.points li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-2);
}
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand);
  flex: none;
}
.actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-top: 24px;
}
.footnote {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
  font-size: 11px;
  color: var(--ink-4);
  line-height: 1.7;
}
</style>
