package com.serviceflow.ai.dispatch.entity;

/** 工单类型所需技能规则，required=1 必需，0 加分项。 */
public class TicketTypeSkillRule {

    private Long id;
    private String ticketType;
    private Long skillId;
    private Integer required;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTicketType() {
        return ticketType;
    }

    public void setTicketType(String ticketType) {
        this.ticketType = ticketType;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public Integer getRequired() {
        return required;
    }

    public void setRequired(Integer required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required != null && required == 1;
    }
}
