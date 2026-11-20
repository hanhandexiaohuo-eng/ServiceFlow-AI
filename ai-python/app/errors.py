"""统一错误类型：对外只暴露错误码与脱敏信息。"""


class AiError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        status_code: int = 503,
        detail: str | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.detail = detail
        self.retryable = retryable

    def to_dict(self, request_id: str) -> dict:
        return {
            "error": {
                "code": self.code,
                "message": self.message,
                "detail": self.detail,
                "requestId": request_id,
                "retryable": self.retryable,
            }
        }
