@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dangbomcare.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangbomcare.app.data.ServiceLocator
import com.dangbomcare.app.data.repo.ApiResult
import com.dangbomcare.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 내 데이터 내보내기 — 환자가 자신의 데이터를 CSV로 받아 의료진과 공유.
 *
 * 두 가지 소스:
 *  1) Health Connect 워치 측정 원본 (HealthDataCsvExporter, 로컬)
 *  2) 당봄케어 가공 데이터 (백엔드 /api/csv/files API)
 *
 * 받은 CSV는 카카오톡, 이메일 등으로 공유 가능 (FileProvider 인텐트).
 */
@Composable
fun ExportDataScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BgSecondary,
        topBar = {
            TopAppBar(
                title = { Text("내 데이터 내보내기", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSecondary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 안내 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = LimeLight.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("📋 내 데이터를 받아보세요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "당봄케어에 보관된 환자분의 건강 데이터를 CSV 파일로 받아\n" +
                                "한의원 / 병원과 공유하거나 개인 백업으로 보관하실 수 있어요.",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "📲 카카오톡, 이메일, Gmail 등으로 바로 공유 가능합니다.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            // ── ⭐ 전체 통합 1파일 (추천) ──
            UnifiedCsvExportCard()

            // ── 워치 측정 원본 (Health Connect) ──
            HealthConnectExportCard()

            // ── (상세) 타입별 파일 ──
            BackendCsvExportCard()

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * ⭐ 전체 통합 CSV — 모든 데이터를 하루 1행으로 합쳐 한 파일로 받기 (의료진 공유용).
 *   DB에서 즉석 생성 → 캐시 저장 → 공유 인텐트.
 */
@Composable
private fun UnifiedCsvExportCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.06f)),
        border = BorderStroke(1.5.dp, Primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📑", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("전체 데이터 한 파일로 (추천)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Text("하루 1행 — 공복혈당·회고·워치·점수·AI 전부", fontSize = 11.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "의료진이 한눈에 볼 수 있는 통합 엑셀(.xlsx) 파일로 '내 파일 > 다운로드'에 저장됩니다. 상단에 환자 정보·현재 목표 포함.",
                fontSize = 12.sp, color = TextPrimary, lineHeight = 17.sp
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    downloading = true
                    resultMsg = null
                    scope.launch {
                        when (val res = ServiceLocator.csvExportRepository.downloadFullXlsx()) {
                            is ApiResult.Success -> {
                                val filename = "당봄케어_전체_${LocalDate.now()}.xlsx"
                                val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                val uri = withContext(Dispatchers.IO) {
                                    saveToDownloads(context, filename, mime, res.data)
                                }
                                if (uri != null) {
                                    resultMsg = "✅ '내 파일 > 다운로드'에 저장됐어요 ($filename).\n공유 화면도 떴어요 — 카톡 등으로 바로 보낼 수 있어요."
                                    shareUri(context, uri, mime)
                                } else {
                                    resultMsg = "⚠️ 저장 실패 — 다시 시도해주세요."
                                }
                            }
                            is ApiResult.Error -> resultMsg = "⚠️ 실패: ${res.message}"
                        }
                        downloading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !downloading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("만드는 중...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("📑 전체 데이터 엑셀로 받기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            resultMsg?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(msg, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Health Connect 원본 CSV 추출 (로컬, 즉시 가능).
 */
@Composable
private fun HealthConnectExportCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var rangeDays by remember { mutableStateOf(7) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // 헤더
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌚", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "워치 측정 원본",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Health Connect 기록 그대로",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // 포함 항목
            Text(
                "포함: 걸음 · 수면 · 심박 · SpO2 · HRV · 일별 요약",
                fontSize = 11.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))

            // 기간 선택
            Text("기간 선택", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "최근 7일", 31 to "최근 31일", 365 to "최근 1년").forEach { (days, label) ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { rangeDays = days },
                        color = if (rangeDays == days) Primary else BgCardSoft,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (rangeDays == days) Color.White else TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // 추출 버튼
            Button(
                onClick = {
                    exporting = true
                    resultMsg = null
                    scope.launch {
                        try {
                            val end = LocalDate.now()
                            val start = end.minusDays((rangeDays - 1).toLong())
                            val result = com.dangbomcare.app.data.health.HealthDataCsvExporter.exportToCsv(
                                context, start, end
                            )
                            if (result.success && result.fileNames.isNotEmpty()) {
                                shareCsvFilesFromDir(context, result.outputDir, result.fileNames)
                                resultMsg = "✅ ${result.fileNames.size}개 파일 (${result.totalRecords}건) 추출 완료. 공유 화면이 떴어요."
                            } else {
                                resultMsg = "⚠️ 추출 실패: ${result.error ?: "Health Connect 데이터가 없거나 권한이 없어요"}"
                            }
                        } catch (e: Exception) {
                            resultMsg = "⚠️ 오류: ${e.message ?: "알 수 없는 오류"}"
                        }
                        exporting = false
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !exporting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("추출 중...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("📥 추출 + 공유", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            resultMsg?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(
                    msg,
                    fontSize = 12.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 백엔드 CSV 다운로드 — 당봄케어가 가공한 데이터 (점수, 회고, AI 피드백 등).
 */
@Composable
private fun BackendCsvExportCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<com.dangbomcare.app.data.api.CsvFileItem>>(emptyList()) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var listLoaded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Divider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // 헤더
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📊", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "당봄케어 종합 데이터",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "점수 · 회고 · AI 피드백 포함",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Text(
                "포함: 점수 breakdown · 공복혈당 · 음주/저녁 회고 · AI 피드백 · 스트레스",
                fontSize = 11.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))

            if (!listLoaded) {
                // 첫 진입 — 목록 불러오기 버튼
                Button(
                    onClick = {
                        loading = true
                        resultMsg = null
                        scope.launch {
                            when (val res = ServiceLocator.csvExportRepository.listFiles()) {
                                is ApiResult.Success -> {
                                    files = res.data.files
                                    listLoaded = true
                                    if (files.isEmpty()) {
                                        resultMsg = "아직 저장된 데이터가 없어요.\n공복혈당이나 워치 데이터를 입력하면 자동 저장됩니다."
                                    }
                                }
                                is ApiResult.Error -> {
                                    resultMsg = "⚠️ 목록 조회 실패: ${res.message}"
                                }
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !loading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("불러오는 중...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("📋 저장된 파일 목록 보기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (files.isEmpty()) {
                // 빈 상태
                Text(
                    resultMsg ?: "저장된 파일이 없어요",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            } else {
                // 파일 목록
                Text(
                    "총 ${files.size}개 파일 — 받고 싶은 항목을 누르세요",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                files.forEach { file ->
                    CsvFileRow(file = file, scope = scope, context = context, onResult = { resultMsg = it })
                    Spacer(Modifier.height(6.dp))
                }
            }

            resultMsg?.let { msg ->
                if (files.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CsvFileRow(
    file: com.dangbomcare.app.data.api.CsvFileItem,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onResult: (String) -> Unit,
) {
    var downloading by remember { mutableStateOf(false) }
    val sizeStr = formatBytes(file.sizeBytes)
    val typeLabel = when (file.type) {
        "watch" -> "워치 데이터"
        "log" -> "공복혈당 + 회고"
        "score" -> "생활 점수"
        "stress" -> "스트레스 추정"
        "ai" -> "AI 피드백"
        else -> file.type
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    when (val res = ServiceLocator.csvExportRepository.downloadFile(file.name)) {
                        is ApiResult.Success -> {
                            // 임시 파일로 저장 후 공유
                            val saved = withContext(Dispatchers.IO) {
                                val outDir = File(context.cacheDir, "csv_backend").apply { mkdirs() }
                                val outFile = File(outDir, file.name)
                                outFile.writeBytes(res.data)
                                outFile
                            }
                            shareSingleFile(context, saved)
                            onResult("✅ ${file.name} 다운로드 완료. 공유 화면이 떴어요.")
                        }
                        is ApiResult.Error -> {
                            onResult("⚠️ 다운로드 실패: ${res.message}")
                        }
                    }
                    downloading = false
                }
            },
        color = BgCardSoft,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(typeLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "${file.month ?: "전체"} · $sizeStr",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            if (downloading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Primary, strokeWidth = 2.dp)
            } else {
                Text("📥", fontSize = 18.sp)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }
}

private fun shareCsvFilesFromDir(
    context: android.content.Context,
    dir: java.io.File,
    fileNames: List<String>,
) {
    try {
        val uris = fileNames.map { fn ->
            val file = java.io.File(dir, fn)
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "CSV 공유").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) { /* ignore */ }
}

private fun shareSingleFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "CSV 공유").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) { /* ignore */ }
}

/** [우노] 다운로드 폴더(내 파일)에 저장 — MediaStore(Q+) 또는 앱 캐시 fallback. 저장된 content uri 반환. */
private fun saveToDownloads(
    context: android.content.Context,
    filename: String,
    mime: String,
    bytes: ByteArray
): android.net.Uri? {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // API 28: 앱 캐시에 저장 후 FileProvider uri (Downloads 직접 쓰기는 권한 필요)
            val dir = java.io.File(context.cacheDir, "csv_backend").apply { mkdirs() }
            val f = java.io.File(dir, filename)
            f.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
    } catch (e: Exception) {
        null
    }
}

/** content uri 공유 인텐트. */
private fun shareUri(context: android.content.Context, uri: android.net.Uri, mime: String) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "파일 공유").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) { }
}
