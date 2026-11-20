"""模型输出的结构化 Schema：严格校验，禁止额外字段。"""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

TicketType = Literal["INCIDENT", "SERVICE_REQUEST", "CHANGE", "CONSULT"]
Priority = Literal["LOW", "MEDIUM", "HIGH", "URGENT"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


def _check_item_length(values: list[str], limit: int, field: str) -> list[str]:
    for item in values:
        if len(item) > limit:
            raise ValueError(f"{field} 单条内容超过 {limit} 字符")
    return values


class TicketAnalysisResult(StrictModel):
    recommendedType: TicketType
    typeConfidence: float = Field(ge=0, le=1)
    problemCategory: str = Field(min_length=1, max_length=64)
    summary: str = Field(min_length=10, max_length=500)
    keyFacts: list[str] = Field(min_length=1, max_length=10)
    technicalDomains: list[str] = Field(max_length=10)
    requiredSkills: list[str] = Field(max_length=10)
    impactScope: str = Field(min_length=10, max_length=300)
    recommendedPriority: Priority
    priorityConfidence: float = Field(ge=0, le=1)
    keywords: list[str] = Field(min_length=1, max_length=10)
    reasons: list[str] = Field(min_length=1, max_length=8)
    confidence: float = Field(ge=0, le=1)

    @field_validator("keyFacts")
    @classmethod
    def validate_key_facts(cls, values: list[str]) -> list[str]:
        return _check_item_length(values, 200, "keyFacts")

    @field_validator("keywords")
    @classmethod
    def validate_keywords(cls, values: list[str]) -> list[str]:
        return _check_item_length(values, 32, "keywords")


class PriorityResult(StrictModel):
    recommendedPriority: Priority
    reasons: list[str] = Field(min_length=1, max_length=8)
    risks: list[str] = Field(max_length=8)
    slaImpact: str = Field(min_length=20, max_length=500)
    suggestedResponseHours: int
    confidence: float = Field(ge=0, le=1)

    @field_validator("suggestedResponseHours")
    @classmethod
    def validate_hours(cls, value: int) -> int:
        if value not in (4, 8, 24, 72):
            raise ValueError("suggestedResponseHours 只能是 4/8/24/72")
        return value


class SolutionResult(StrictModel):
    problemAnalysis: str = Field(min_length=30, max_length=1500)
    possibleCauses: list[str] = Field(min_length=1, max_length=10)
    diagnosticSteps: list[str] = Field(min_length=1, max_length=15)
    recommendedSolution: list[str] = Field(min_length=1, max_length=15)
    tools: list[str] = Field(max_length=10)
    risks: list[str] = Field(max_length=10)
    verification: list[str] = Field(min_length=1, max_length=10)
    escalationRecommended: bool
    escalationReason: str = Field(max_length=600)
    preconditions: list[str] = Field(max_length=10)
    missingInformation: list[str] = Field(max_length=10)
    confidence: float = Field(ge=0, le=1)

    @field_validator("possibleCauses", "diagnosticSteps", "recommendedSolution", "verification")
    @classmethod
    def validate_items(cls, values: list[str]) -> list[str]:
        return _check_item_length(values, 400, "solution")

    @field_validator("escalationReason")
    @classmethod
    def validate_escalation(cls, value: str, info) -> str:
        if info.data.get("escalationRecommended") and not value:
            raise ValueError("建议升级时必须填写 escalationReason")
        return value


class KnowledgeItem(StrictModel):
    articleId: int = Field(ge=1)
    relevance: int = Field(ge=0, le=100)
    reason: str = Field(min_length=5, max_length=200)


class KnowledgeResult(StrictModel):
    recommendations: list[KnowledgeItem] = Field(max_length=5)
    keywords: list[str] = Field(max_length=10)


class DispatchCandidateAssist(StrictModel):
    agentId: int = Field(ge=1)
    fit: str = Field(min_length=10, max_length=200)
    advantages: list[str] = Field(max_length=6)
    gaps: list[str] = Field(max_length=6)
    risk: str = Field(max_length=300)


class DispatchAssistResult(StrictModel):
    summary: str = Field(min_length=10, max_length=500)
    recommendedAgentId: int | None = None
    candidates: list[DispatchCandidateAssist] = Field(min_length=1, max_length=5)
    confidence: float = Field(ge=0, le=1)


class ChatResult(StrictModel):
    answer: str = Field(min_length=1, max_length=2000)
    referencedFacts: list[str] = Field(max_length=10)
    followUpQuestions: list[str] = Field(max_length=5)
    confidence: float = Field(ge=0, le=1)


ANALYSIS_SCHEMAS: dict[str, tuple[str, type[StrictModel], str]] = {
    "TICKET_ANALYSIS": ("ticket_analysis", TicketAnalysisResult, "ticket_analysis"),
    "PRIORITY": ("priority_recommendation", PriorityResult, "priority_recommendation"),
    "SOLUTION": ("solution_generation", SolutionResult, "solution_generation"),
    "KNOWLEDGE": ("knowledge_recommendation", KnowledgeResult, "knowledge_recommendation"),
    "DISPATCH": ("dispatch_assistance", DispatchAssistResult, "dispatch_assistance"),
    "CHAT": ("ticket_qa", ChatResult, "ticket_qa"),
}

SLA_HOURS = {"URGENT": 4, "HIGH": 8, "MEDIUM": 24, "LOW": 72}
