/** 通用 UI 类型（放在独立的 .ts 中，避免在 <script setup> 内使用 export） */

export interface CrumbItem {
  label: string
  to?: string
}

export interface TableColumn {
  key: string
  label: string
  width?: string
  align?: 'left' | 'right' | 'center'
  nowrap?: boolean
  /** 是否可点击表头排序（需配合 DataTable 的 sort 事件） */
  sortable?: boolean
}
