package com.serviceflow.ai.knowledge.entity;

import java.time.LocalDateTime;

/** 知识与工单的关联记录。 */
public class KbTicketLink {

    private Long id;
    private Long articleId;
    private Long ticketId;
    private Long linkedBy;
    private String note;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getLinkedBy() {
        return linkedBy;
    }

    public void setLinkedBy(Long linkedBy) {
        this.linkedBy = linkedBy;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
