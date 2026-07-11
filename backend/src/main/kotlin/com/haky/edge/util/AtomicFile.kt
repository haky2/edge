package com.haky.edge.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * writeText의 원자적 버전. 임시 파일에 쓴 뒤 ATOMIC_MOVE로 교체한다.
 * 쓰는 도중 프로세스가 종료돼도 기존 파일이 반파손되지 않는다(signal_state.json 등 보호).
 */
fun File.writeTextAtomic(text: String) {
    val tmp = File(parentFile ?: File("."), ".${name}.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
