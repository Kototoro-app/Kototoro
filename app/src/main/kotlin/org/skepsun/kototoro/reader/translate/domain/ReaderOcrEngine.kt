package org.skepsun.kototoro.reader.translate.domain

interface ReaderOcrService {

    suspend fun recognize(request: OcrRequest): List<OcrTextBlock>
}
