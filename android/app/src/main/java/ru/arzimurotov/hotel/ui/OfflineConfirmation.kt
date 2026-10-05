package ru.arzimurotov.hotel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebView только локальный HTML, без JS/сети/доступа к файлам. FileProvider отдаёт выбранному
 * почтовому приложению временный read URI. Результат chooser не выдаётся за доставку письма.
 */
@Composable
fun OfflineConfirmation(bytes: ByteArray?, consumed: () -> Unit, email: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<ByteArray?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var rendered by remember { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri
            ->
            if (uri != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.write(requireNotNull(document))
                            } ?: error("Файл недоступен")
                        }
                        notice = "HTML-документ сохранён"
                    } catch (_: Exception) {
                        notice = "Не удалось сохранить документ"
                    }
                }
        }
    LaunchedEffect(bytes) {
        if (bytes != null) {
            document = bytes
            consumed()
        }
    }
    document?.let { html ->
        SectionTitle("Подтверждение готово")
        AndroidView(
            factory = {
                android.webkit.WebView(it).apply {
                    webViewClient =
                        object : android.webkit.WebViewClient() {
                            override fun onPageFinished(
                                view: android.webkit.WebView?,
                                url: String?,
                            ) {
                                rendered = true
                            }
                        }
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                }
            },
            update = {
                if (it.tag != html.contentHashCode()) {
                    it.tag = html.contentHashCode()
                    it.loadDataWithBaseURL(
                        null,
                        html.toString(Charsets.UTF_8),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxWidth().height(460.dp).testTag("html_confirmation"),
        )
        if (rendered)
            Text(
                "Документ открыт без сети",
                Modifier.testTag("html_rendered"),
                style = MaterialTheme.typography.bodySmall,
            )
        Button(
            { save.launch("Гостиница_подтверждение.html") },
            modifier = Modifier.testTag("html_save"),
        ) {
            Text("Сохранить HTML")
        }
        OutlinedButton(
            {
                scope.launch {
                    try {
                        val file =
                            withContext(Dispatchers.IO) {
                                File(context.cacheDir, "confirmations")
                                    .apply { mkdirs() }
                                    .let { dir ->
                                        File(
                                                dir,
                                                "Гостиница_" +
                                                    java.util.UUID.randomUUID() +
                                                    ".html",
                                            )
                                            .apply { writeBytes(html) }
                                    }
                            }
                        val uri =
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                context.packageName + ".documents",
                                file,
                            )
                        val mailQuery =
                            android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse("mailto:"),
                            )
                        val clients =
                            context.packageManager
                                .queryIntentActivities(mailQuery, 0)
                                .map { it.activityInfo.packageName }
                                .distinct()
                        if (clients.isEmpty()) {
                            notice =
                                "Почтового приложения нет. Документ сохранён в кабинете; установите Gmail/Mail.ru или сохраните HTML."
                            return@launch
                        }
                        val intents = clients.map { pkg ->
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/html"
                                setPackage(pkg)
                                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(email))
                                putExtra(
                                    android.content.Intent.EXTRA_SUBJECT,
                                    "Гостиница — учебное подтверждение",
                                )
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "Учебное подтверждение во вложении. Не является фискальным чеком. Деньги не списываются.",
                                )
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData =
                                    android.content.ClipData.newUri(
                                        context.contentResolver,
                                        "Подтверждение",
                                        uri,
                                    )
                            }
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(
                                    intents.first(),
                                    "Отправить на почту",
                                )
                                .putExtra(
                                    android.content.Intent.EXTRA_INITIAL_INTENTS,
                                    intents.drop(1).toTypedArray(),
                                )
                        )
                        notice =
                            "Письмо подготовлено в почтовом приложении. Для отправки нужен интернет; доставка не подтверждена."
                    } catch (_: Exception) {
                        notice = "Не удалось открыть почту. Сохраните HTML и отправьте позже."
                    }
                }
            },
            modifier = Modifier.testTag("html_mail"),
        ) {
            Text("Отправить на почту")
        }
        notice?.let { Text(it) }
        TextButton({ document = null }, Modifier.testTag("html_close")) {
            Text("Закрыть подтверждение")
        }
    }
}
