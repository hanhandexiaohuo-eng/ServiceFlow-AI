<script setup lang="ts">
import EmptyState from './EmptyState.vue'
import LoadingState from './LoadingState.vue'
import type { TableColumn } from '../types/ui'

const props = withDefaults(
  defineProps<{
    columns: TableColumn[]
    rows: Record<string, any>[]
    rowKey?: string
    loading?: boolean
    emptyText?: string
    clickable?: boolean
    dense?: boolean
    /** 当前排序列（配合列上的 sortable 使用） */
    sortKey?: string
    sortDir?: 'asc' | 'desc'
    /** 行附加 class：用于 SLA 超时等整行提示 */
    rowClass?: (row: Record<string, any>) => string
  }>(),
  {
    rowKey: 'id',
    loading: false,
    emptyText: '暂无数据',
    clickable: false,
    dense: false,
    sortKey: '',
    sortDir: 'desc',
  }
)

const emit = defineEmits<{
  (e: 'row-click', row: Record<string, any>): void
  (e: 'sort', key: string): void
}>()

function onClick(row: Record<string, any>) {
  emit('row-click', row)
}

function onSort(col: TableColumn) {
  if (!col.sortable) return
  emit('sort', col.key)
}

function classOf(row: Record<string, any>): string[] {
  return [props.rowClass ? props.rowClass(row) : '', props.clickable ? 'clickable' : '']
}
</script>

<template>
  <div class="table-wrap">
    <table :class="{ dense }">
      <colgroup>
        <col v-for="col in columns" :key="col.key" :style="col.width ? { width: col.width } : undefined" />
      </colgroup>
      <thead>
        <tr>
          <th
            v-for="col in columns"
            :key="col.key"
            :style="{ textAlign: col.align ?? 'left' }"
            :class="{ sortable: col.sortable, sorted: col.sortable && sortKey === col.key }"
            :title="col.sortable ? '点击排序' : undefined"
            @click="onSort(col)"
          >
            <span class="th-inner" :style="{ justifyContent: col.align === 'right' ? 'flex-end' : col.align === 'center' ? 'center' : 'flex-start' }">
              {{ col.label }}
              <span v-if="col.sortable" class="sort-ind">
                {{ sortKey === col.key ? (sortDir === 'asc' ? '↑' : '↓') : '⇅' }}
              </span>
            </span>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length"><LoadingState :height="80" /></td>
        </tr>
        <tr v-else-if="rows.length === 0">
          <td :colspan="columns.length"><EmptyState compact :title="emptyText" /></td>
        </tr>
        <tr
          v-for="row in rows"
          v-else
          :key="String(row[rowKey])"
          :class="classOf(row)"
          @click="clickable && onClick(row)"
        >
          <td
            v-for="col in columns"
            :key="col.key"
            :style="{ textAlign: col.align ?? 'left' }"
            :class="{ nowrap: col.nowrap }"
          >
            <slot :name="`cell-${col.key}`" :row="row">{{ row[col.key] }}</slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-wrap {
  width: 100%;
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  table-layout: auto;
}
th {
  height: 38px;
  padding: 0 14px;
  background: #fafbfd;
  border-bottom: 1px solid var(--line);
  color: var(--ink-4);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  white-space: nowrap;
  text-align: left;
}
.th-inner {
  display: flex;
  align-items: center;
  gap: 4px;
}
th.sortable {
  cursor: pointer;
  user-select: none;
}
th.sortable:hover {
  color: var(--ink-2);
  background: #f4f6fa;
}
th.sorted {
  color: var(--brand);
}
.sort-ind {
  font-size: 10px;
  line-height: 1;
  opacity: 0.75;
}
td {
  padding: 11px 14px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 13px;
  color: var(--ink-2);
  vertical-align: middle;
}
.dense td {
  padding: 8px 14px;
}
tbody tr:last-child td {
  border-bottom: none;
}
tbody tr {
  transition: background 0.12s ease;
}
tbody tr:hover {
  background: #fafbfd;
}
tbody tr.clickable {
  cursor: pointer;
}
td.nowrap {
  white-space: nowrap;
}
</style>
