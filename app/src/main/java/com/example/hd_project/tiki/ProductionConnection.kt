package com.example.hd_project.tiki

/** A device service connection failure is recoverable without discarding completed analysis blocks. */
class ProductionConnectionException(val reason: String) : java.io.IOException("음성 인식 서비스 연결을 확인해 주세요. 원본 녹음은 보관되어 있어요.")

object ProductionConnection {
    // Transport failures, not unsupported-language/permission/model errors or ordinary analysis time slices.
    fun isTransportFailure(reason: String?) = reason in setOf("DeadObjectException", "DeadSystemException", "RemoteException", "NETWORK_ERROR")
}
