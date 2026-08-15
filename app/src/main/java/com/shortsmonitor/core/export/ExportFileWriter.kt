package com.shortsmonitor.core.export

import android.content.Context
import android.net.Uri
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.ShortsError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 내보내기 파일 작성기 (N단계).
 *
 * 시스템 파일 선택기(CreateDocument)가 반환한 content URI에 문자열을 쓴다.
 * 내용을 메모리에 먼저 완성한 뒤 한 번에 쓰므로, 쓰기 도중 앱이 종료되어도
 * 기존 데이터(DB)는 손상되지 않는다. 실패 원인은 [ShortsError.Export]로 반환한다.
 */
object ExportFileWriter {

    /**
     * [uri]에 [content]를 쓴다.
     * @return 성공 시 null, 실패 시 실패 원인 (화면에서 표시)
     */
    suspend fun write(
        context: Context,
        uri: Uri,
        content: String,
    ): ShortsError.Export? = withContext(Dispatchers.IO) {
        try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: return@withContext ShortsError.Export("Cannot open output stream")
            output.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            ShortsLog.d("Export written: $uri (${content.length} chars)")
            null
        } catch (e: IOException) {
            ShortsLog.e("Export failed: $uri", e)
            ShortsError.Export(e.message ?: "Write failed", e)
        } catch (e: SecurityException) {
            ShortsLog.e("Export failed (security): $uri", e)
            ShortsError.Export(e.message ?: "Permission denied", e)
        }
    }
}
