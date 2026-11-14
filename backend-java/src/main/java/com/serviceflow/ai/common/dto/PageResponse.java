package com.serviceflow.ai.common.dto;

import java.util.List;

/**
 * 分页响应：list 当前页数据，total 总条数（前端算总页数），page/size 回显查询条件。
 */
public record PageResponse<T>(List<T> list, long total, int page, int size) {
}
