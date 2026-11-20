package com.serviceflow.ai.intelligence.enums;

/** 分析记录状态。先 PENDING 落库，再按调用结果更新。 */
public enum AnalysisStatus {
    PENDING,
    SUCCESS,
    FAILED
}
