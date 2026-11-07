package com.dangbomcare.app.data.health

import android.content.Context
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Health Connect 데이터를 CSV 파일로 추출 — 사용자가 직접 확인하거나 의료진과 공유.
 *
 * 추출 파일:
 * - steps_{startDate}_{endDate}.csv — 시간대별 raw 걸음 레코드
 * - sleep_{startDate}_{endDate}.csv — 수면 세션 + 단계
 * - heartrate_{startDate}_{endDate}.csv — 심박 샘플
 * - spo2_{startDate}_{endDate}.csv — 산소포화도
 * - summary_{startDate}_{endDate}.csv — 일별 통합 요약
 *
 * 저장 위치: 앱 내부 storage/Downloads (사용자가 SAF로 공유 가능)
 */
object HealthDataCsvExporter {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class ExportResult(
        val success: Boolean,
        val outputDir: File,
        val fileNames: List<String>,
        val totalRecords: Int,
        val error: String? = null,
    )

    /**
     * 지정 기간(startDate~endDate)의 모든 Health Connect 데이터를 CSV로 추출.
     * 결과는 앱 캐시 디렉토리에 저장 → 공유 인텐트로 외부 전송 가능.
     */
    suspend fun exportToCsv(
        context: Context,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ExportResult {
        val outputDir = File(context.cacheDir, "csv_export_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val fileNames = mutableListOf<String>()
        var totalRecords = 0

        try {
            val repo = HealthConnectRepository(context)
            if (!repo.isSdkAvailable()) {
                return ExportResult(false, outputDir, emptyList(), 0, "Health Connect 미설치")
            }
            val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
            val zone = ZoneId.systemDefault()
            val rangeStart = startDate.atStartOfDay(zone).toInstant()
            val rangeEnd = endDate.plusDays(1).atStartOfDay(zone).toInstant()
            val filter = TimeRangeFilter.between(rangeStart, rangeEnd)

            // 1. 걸음
            try {
                val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, filter)).records
                val fn = "steps_${startDate}_${endDate}.csv"
                File(outputDir, fn).writeCsv(
                    headers = listOf("date", "start_time", "end_time", "duration_min", "count", "source"),
                    rows = steps.map { r ->
                        val s = r.startTime.atZone(zone)
                        val e = r.endTime.atZone(zone)
                        val durMin = (r.endTime.epochSecond - r.startTime.epochSecond) / 60
                        listOf(
                            s.toLocalDate().format(DATE_FMT),
                            s.toLocalDateTime().format(DATETIME_FMT),
                            e.toLocalDateTime().format(DATETIME_FMT),
                            durMin.toString(),
                            r.count.toString(),
                            r.metadata.dataOrigin.packageName
                        )
                    }
                )
                fileNames.add(fn)
                totalRecords += steps.size
            } catch (_: Exception) { /* skip */ }

            // 2. 수면
            try {
                val sleepRangeStart = startDate.atStartOfDay(zone).minusHours(12).toInstant()
                val sleepRangeEnd = endDate.plusDays(1).atStartOfDay(zone).plusHours(12).toInstant()
                val sleepFilter = TimeRangeFilter.between(sleepRangeStart, sleepRangeEnd)
                val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepFilter)).records
                val fn = "sleep_${startDate}_${endDate}.csv"

                // 세션 단위 + 단계 단위 둘 다
                val rows = mutableListOf<List<String>>()
                sessions.forEach { s ->
                    val sZ = s.startTime.atZone(zone)
                    val eZ = s.endTime.atZone(zone)
                    val durMin = (s.endTime.epochSecond - s.startTime.epochSecond) / 60
                    rows.add(listOf(
                        "SESSION",
                        sZ.toLocalDate().format(DATE_FMT),
                        sZ.toLocalDateTime().format(DATETIME_FMT),
                        eZ.toLocalDateTime().format(DATETIME_FMT),
                        durMin.toString(),
                        "",
                        s.metadata.dataOrigin.packageName
                    ))
                    s.stages.forEach { st ->
                        val ssZ = st.startTime.atZone(zone)
                        val seZ = st.endTime.atZone(zone)
                        val mins = (st.endTime.epochSecond - st.startTime.epochSecond) / 60
                        val stageName = stageName(st.stage)
                        rows.add(listOf(
                            "STAGE",
                            ssZ.toLocalDate().format(DATE_FMT),
                            ssZ.toLocalDateTime().format(DATETIME_FMT),
                            seZ.toLocalDateTime().format(DATETIME_FMT),
                            mins.toString(),
                            stageName,
                            s.metadata.dataOrigin.packageName
                        ))
                    }
                }
                File(outputDir, fn).writeCsv(
                    headers = listOf("type", "date", "start_time", "end_time", "duration_min", "stage", "source"),
                    rows = rows
                )
                fileNames.add(fn)
                totalRecords += sessions.sumOf { 1 + it.stages.size }
            } catch (_: Exception) { /* skip */ }

            // 3. 심박
            try {
                val hrRecords = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, filter)).records
                val fn = "heartrate_${startDate}_${endDate}.csv"
                val rows = hrRecords.flatMap { r ->
                    r.samples.map { s ->
                        val z = s.time.atZone(zone)
                        listOf(
                            z.toLocalDate().format(DATE_FMT),
                            z.toLocalDateTime().format(DATETIME_FMT),
                            s.beatsPerMinute.toString(),
                            r.metadata.dataOrigin.packageName
                        )
                    }
                }
                File(outputDir, fn).writeCsv(
                    headers = listOf("date", "time", "bpm", "source"),
                    rows = rows
                )
                fileNames.add(fn)
                totalRecords += rows.size
            } catch (_: Exception) { /* skip */ }

            // 4. SpO2
            try {
                val spo2 = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, filter)).records
                val fn = "spo2_${startDate}_${endDate}.csv"
                File(outputDir, fn).writeCsv(
                    headers = listOf("date", "time", "percentage", "source"),
                    rows = spo2.map { r ->
                        val z = r.time.atZone(zone)
                        listOf(
                            z.toLocalDate().format(DATE_FMT),
                            z.toLocalDateTime().format(DATETIME_FMT),
                            "%.1f".format(r.percentage.value),
                            r.metadata.dataOrigin.packageName
                        )
                    }
                )
                fileNames.add(fn)
                totalRecords += spo2.size
            } catch (_: Exception) { /* skip */ }

            // 5. HRV
            try {
                val hrv = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, filter)).records
                val fn = "hrv_${startDate}_${endDate}.csv"
                File(outputDir, fn).writeCsv(
                    headers = listOf("date", "time", "hrv_ms", "source"),
                    rows = hrv.map { r ->
                        val z = r.time.atZone(zone)
                        listOf(
                            z.toLocalDate().format(DATE_FMT),
                            z.toLocalDateTime().format(DATETIME_FMT),
                            "%.1f".format(r.heartRateVariabilityMillis),
                            r.metadata.dataOrigin.packageName
                        )
                    }
                )
                fileNames.add(fn)
                totalRecords += hrv.size
            } catch (_: Exception) { /* skip */ }

            // 6. 일별 요약 (fetchWatchData 기반)
            try {
                val fn = "summary_${startDate}_${endDate}.csv"
                val rows = mutableListOf<List<String>>()
                var d = startDate
                while (!d.isAfter(endDate)) {
                    val data = repo.fetchWatchData(d)
                    rows.add(listOf(
                        d.format(DATE_FMT),
                        (data.steps ?: 0).toString(),
                        (data.sleepTotalMinutes ?: 0).toString(),
                        (data.sleepDeepMinutes ?: 0).toString(),
                        (data.sleepLightMinutes ?: 0).toString(),
                        (data.sleepRemMinutes ?: 0).toString(),
                        (data.sleepAwakeMinutes ?: 0).toString(),
                        (data.avgHeartRate ?: 0).toString(),
                        (data.minHeartRate ?: 0).toString(),
                        (data.maxHeartRate ?: 0).toString(),
                        data.spo2Avg?.let { "%.1f".format(it) } ?: "",
                    ))
                    d = d.plusDays(1)
                }
                File(outputDir, fn).writeCsv(
                    headers = listOf(
                        "date", "steps", "sleep_total_min",
                        "deep_min", "light_min", "rem_min", "awake_min",
                        "hr_avg", "hr_min", "hr_max", "spo2_avg"
                    ),
                    rows = rows
                )
                fileNames.add(fn)
                totalRecords += rows.size
            } catch (_: Exception) { /* skip */ }

            return ExportResult(true, outputDir, fileNames, totalRecords)
        } catch (e: Exception) {
            return ExportResult(false, outputDir, fileNames, totalRecords, e.message)
        }
    }

    private fun File.writeCsv(headers: List<String>, rows: List<List<String>>) {
        // [Codex 보안 검토 H7] Windows CRLF (\r\n) 로 줄바꿈 — Excel 호환 + Notepad 호환
        java.io.OutputStreamWriter(this.outputStream(), Charsets.UTF_8).use { w ->
            // UTF-8 BOM (Excel 한글 깨짐 방지)
            w.write("﻿")
            w.write(headers.joinToString(",") { escapeCsv(it) })
            w.write("\r\n")
            rows.forEach { row ->
                w.write(row.joinToString(",") { escapeCsv(it) })
                w.write("\r\n")
            }
        }
    }

    /**
     * CSV 셀 이스케이프 + Formula Injection 방어.
     *
     * [Codex 보안 검토 H7] Excel/Numbers/LibreOffice에서
     * `=`, `+`, `-`, `@`, `\t`, `\r` 로 시작하는 셀은 수식으로 실행됨.
     * 의료진이 CSV 열 때 안전하도록 위험 접두어 앞에 `'` prefix 추가.
     */
    private fun escapeCsv(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in listOf('=', '+', '-', '@', '\t', '\r')) {
            "'$s"
        } else {
            s
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        return safe
    }

    /** 공식 SleepSessionRecord.StageType 상수 → 사람이 읽을 수 있는 이름 */
    private fun stageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING(deprecated)"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE_IN_BED"
        else -> "UNKNOWN($stage)"
    }
}
