class ServiceError(Exception):
    def __init__(self, code, message, status=400, retryable=False, details=None, retry_after=None):
        self.code, self.message, self.status = code, message, status
        self.retryable, self.details, self.retry_after = retryable, details or {}, retry_after
        super().__init__(code)

    def payload(self, request_id=""):
        return dict(code=self.code, message=self.message, retryable=self.retryable,
                    details=self.details, request_id=request_id)


class Cancelled(Exception):
    pass
