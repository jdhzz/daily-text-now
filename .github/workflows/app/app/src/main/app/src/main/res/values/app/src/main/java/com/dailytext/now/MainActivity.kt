package com.dailytext.now

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showDailyNotification()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        setContent {
            MaterialTheme {
                DailyTextScreen()
            }
        }
    }

    @Composable
    private fun DailyTextScreen() {
        var dailyText by remember { mutableStateOf("오늘의 성구를 불러오는 중...") }
        var reference by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf(false) }

        val scope = rememberCoroutineScope()
        val today = LocalDate.now()

        val koreanDays = listOf(
            "월요일", "화요일", "수요일", "목요일",
            "금요일", "토요일", "일요일"
        )

        val dateTitle =
            "${today.monthValue}월 ${today.dayOfMonth}일 " +
                    koreanDays[today.dayOfWeek.value - 1]

        val wolUrl =
            "https://wol.jw.org/ko/wol/h/r8/lp-ko/" +
                    "${today.year}/${today.monthValue}/${today.dayOfMonth}"

        fun refresh() {
            scope.launch {
                loading = true
                error = false

                try {
                    val result = fetchDailyText(wolUrl, today)

                    dailyText = result.first
                    reference = result.second

                    saveCache(dailyText, reference)

                    requestNotification()

                } catch (e: Exception) {

                    val cached = loadCache()

                    if (cached != null) {
                        dailyText = cached.first
                        reference = cached.second
                    } else {
                        dailyText = "오늘의 성구를 불러오지 못했습니다."
                        reference = "인터넷 연결을 확인한 후 다시 시도해 주세요."
                        error = true
                    }
                }

                loading = false
            }
        }

        LaunchedEffect(Unit) {
            refresh()
        }

        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "오늘의 성구",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = dateTitle,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (loading) {

                    CircularProgressIndicator()

                } else {

                    Text(
                        text = dailyText,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = reference,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                Button(
                    onClick = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(wolUrl)
                            )
                        )
                    }
                ) {
                    Text("WOL에서 전체 내용 읽기")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { refresh() }
                ) {
                    Text(if (error) "다시 시도" else "새로고침")
                }
            }
        }
    }

    private suspend fun fetchDailyText(
        url: String,
        today: LocalDate
    ): Pair<String, String> = withContext(Dispatchers.IO) {

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(15000)
            .get()

        val target =
            "${today.monthValue}월 ${today.dayOfMonth}일"

        val heading = doc.select("h1, h2, h3")
            .firstOrNull {
                it.text().replace(" ", "").contains(
                    target.replace(" ", "")
                )
            } ?: throw Exception("오늘 날짜를 찾을 수 없음")

        var element = heading.nextElementSibling()
        var verseLine: String? = null

        while (element != null) {

            if (
                element.tagName() in listOf("h1", "h2", "h3") &&
                element !== heading
            ) {
                break
            }

            val candidates =
                if (element.tagName() == "p")
                    listOf(element)
                else
                    element.select("p")

            for (p in candidates) {
                val text = p.text().trim()

                if (
                    text.contains("—") &&
                    text.length in 10..500
                ) {
                    verseLine = text
                    break
                }
            }

            if (verseLine != null) break

            element = element.nextElementSibling()
        }

        val line = verseLine
            ?: throw Exception("성구를 찾을 수 없음")

        val splitIndex = line.lastIndexOf('—')

        if (splitIndex == -1) {
            return@withContext Pair(line, "")
        }

        val verse =
            line.substring(0, splitIndex)
                .trim()
                .trimEnd('.', ' ', '—')

        val reference =
            line.substring(splitIndex + 1)
                .trim()

        Pair(verse, reference)
    }

    private fun requestNotification() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            showDailyNotification()
        }
    }

    private fun showDailyNotification() {

        val cached = loadCache() ?: return

        val notification =
            NotificationCompat.Builder(this, "daily_text")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("오늘의 성구 · ${cached.second}")
                .setContentText(cached.first)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(cached.first)
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .build()

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(1001, notification)
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "daily_text",
                "오늘의 성구",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "매일 오늘의 성구를 표시합니다."
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun saveCache(
        text: String,
        reference: String
    ) {
        getSharedPreferences("daily_text", MODE_PRIVATE)
            .edit()
            .putString("text", text)
            .putString("reference", reference)
            .apply()
    }

    private fun loadCache(): Pair<String, String>? {

        val prefs =
            getSharedPreferences("daily_text", MODE_PRIVATE)

        val text =
            prefs.getString("text", null) ?: return null

        val reference =
            prefs.getString("reference", "") ?: ""

        return Pair(text, reference)
    }
}
