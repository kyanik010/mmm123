package com.streamvault.app.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.app.BuildConfig
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

private enum class ActivationState { CHECKING, ACTIVATING, NOT_REGISTERED, ACTIVE, SUSPENDED, EXPIRED, ERROR }

@Composable
fun ActivationGate(
    providerRepository: ProviderRepository,
    validateAndAddProvider: ValidateAndAddProvider,
    configStore: ActivationConfigStore,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activationId = remember { readActivationId(context) }
    var state by remember { mutableStateOf(ActivationState.CHECKING) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun check() {
        state = ActivationState.CHECKING
        errorText = null
        runCatching { withContext(Dispatchers.IO) { requestActivation(activationId) } }
            .onSuccess { response ->
                if (!response.activated) {
                    state = when (response.status) {
                        "expired" -> ActivationState.EXPIRED
                        "suspended" -> ActivationState.SUSPENDED
                        else -> ActivationState.NOT_REGISTERED
                    }
                    return@onSuccess
                }
                val config = response.config
                if (config?.video == null) {
                    state = ActivationState.ERROR
                    errorText = "بيانات التفعيل غير مكتملة"
                    return@onSuccess
                }
                configStore.set(config)
                state = ActivationState.ACTIVATING
                val video = config.video
                val existing = providerRepository.getProviders().first().firstOrNull {
                    it.type == ProviderType.XTREAM_CODES &&
                        it.serverUrl.trimEnd('/') == video.serverUrl.trimEnd('/') &&
                        it.username == video.username &&
                        it.password == video.password
                }
                if (existing == null) {
                    val addResult = withContext(Dispatchers.IO) {
                        validateAndAddProvider.loginXtream(
                            XtreamProviderSetupCommand(
                                serverUrl = video.serverUrl,
                                username = video.username,
                                password = video.password,
                                name = "Live TV",
                                xtreamFastSyncEnabled = true
                            )
                        )
                    }
                    if (addResult !is ValidateAndAddProviderResult.Success &&
                        addResult !is ValidateAndAddProviderResult.SavedWithWarning) {
                        state = ActivationState.ERROR
                        errorText = "تعذر تحميل خدمة الفيديو"
                        return@onSuccess
                    }
                }
                config.audio?.m3uUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
                    context.getSharedPreferences("streamvault_audio_source", Context.MODE_PRIVATE)
                        .edit().putString("m3u_url", audioUrl).apply()
                }
                state = ActivationState.ACTIVE
            }
            .onFailure {
                state = ActivationState.ERROR
                errorText = "تعذر الاتصال بخادم التفعيل"
            }
    }

    LaunchedEffect(activationId) {
        check()
        while (isActive) {
            delay(30_000)
            if (state != ActivationState.ACTIVE) check()
        }
    }

    if (state == ActivationState.ACTIVE) content()
    else ActivationScreen(activationId, state, errorText) { scope.launch { check() } }
}

// ---- Activation screen UI (ticket-style pass). UI only: no logic lives below this line until requestActivation. ----

private val PassBg = Color(0xFF0B0F1A)
private val PassMid = Color(0xFF0E1422)
private val PassLight = Color(0xFF1B273F)
private val PassBrass = Color(0xFFC8A45A)
private val PassBrassSoft = Color(0xFFE6C982)
private val PassText = Color(0xFFEEF1F7)
private val PassMuted = Color(0xFF94A3BF)
private val PassDash = Color(0xFF3A4A6A)

@Composable
private fun ActivationScreen(
    activationId: String,
    state: ActivationState,
    errorText: String?,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val statusText = when (state) {
        ActivationState.CHECKING -> "جاري التحقق من الجهاز..."
        ActivationState.ACTIVATING -> "جاري تجهيز الاشتراك..."
        ActivationState.NOT_REGISTERED -> "هذا الجهاز غير مفعّل"
        ActivationState.SUSPENDED -> "هذا الجهاز موقوف"
        ActivationState.EXPIRED -> "انتهى تفعيل هذا الجهاز"
        ActivationState.ERROR -> errorText ?: "تعذر التحقق من الجهاز"
        ActivationState.ACTIVE -> ""
    }
    val retryEnabled = state != ActivationState.CHECKING && state != ActivationState.ACTIVATING
    val onCopy = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MAC Address", activationId))
        Toast.makeText(context, "تم نسخ العنوان", Toast.LENGTH_SHORT).show()
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(Modifier.fillMaxSize().background(PassBg)) {
            val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val passShape = RoundedCornerShape(26.dp)
                val passModifier = Modifier
                    .widthIn(max = if (landscape) 900.dp else 380.dp)
                    .fillMaxWidth()
                    .clip(passShape)
                    .border(1.dp, PassBrass.copy(alpha = 0.22f), passShape)
                if (landscape) {
                    Row(passModifier.height(IntrinsicSize.Min)) {
                        PassHead(activationId, onCopy, Modifier.weight(1.05f).fillMaxHeight())
                        PassBody(statusText, retryEnabled, onRetry, Modifier.weight(1.15f).fillMaxHeight())
                        PassStub(landscape = true, modifier = Modifier.weight(0.9f).fillMaxHeight())
                    }
                } else {
                    Column(passModifier) {
                        PassHead(activationId, onCopy, Modifier.fillMaxWidth())
                        PassBody(statusText, retryEnabled, onRetry, Modifier.fillMaxWidth())
                        PassStub(landscape = false, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PassHead(activationId: String, onCopy: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(PassLight, PassMid, PassBg)))
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painterResource(com.streamvault.app.R.drawable.eagle_x_activation_logo),
            "Eagle X",
            Modifier.size(140.dp)
        )
        Text(
            "Eagle X",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = PassText
        )
        Spacer(Modifier.height(6.dp))
        Text("عنوان MAC", fontSize = 11.sp, color = PassMuted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .border(1.dp, PassBrass.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                activationId,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = PassBrassSoft,
                maxLines = 1
            )
            OutlinedButton(
                onClick = onCopy,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, PassBrass.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PassBrassSoft),
                modifier = Modifier.height(30.dp)
            ) { Text("نسخ", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PassBody(statusText: String, retryEnabled: Boolean, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(PassMid)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 1.6.dp.toPx()
            drawCircle(PassBrassSoft, style = Stroke(width = stroke))
            val outer = size.minDimension * 0.30f
            val inner = outer * 0.42f
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outer else inner
                val angle = Math.toRadians((-90 + i * 36).toDouble())
                val x = center.x + (r * cos(angle)).toFloat()
                val y = center.y + (r * sin(angle)).toFloat()
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            drawPath(star, PassBrassSoft, style = Stroke(width = stroke, join = StrokeJoin.Round))
        }
        Text(
            statusText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = PassText,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp
        )
        Button(
            onClick = onRetry,
            enabled = retryEnabled,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, PassBrass.copy(alpha = 0.45f)),
            colors = ButtonDefaults.buttonColors(
                containerColor = PassLight,
                contentColor = PassBrassSoft,
                disabledContainerColor = PassLight.copy(alpha = 0.5f),
                disabledContentColor = PassBrassSoft.copy(alpha = 0.5f)
            )
        ) { Text("إعادة التحقق", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PassStub(landscape: Boolean, modifier: Modifier) {
    Column(
        modifier
            .background(PassLight)
            .drawBehind {
                val notch = 11.dp.toPx()
                val inset = 16.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
                val width = 2.dp.toPx()
                if (landscape) {
                    drawLine(PassDash, Offset(size.width, inset), Offset(size.width, size.height - inset), strokeWidth = width, pathEffect = dash)
                    drawCircle(PassBg, notch, Offset(size.width, 0f))
                    drawCircle(PassBg, notch, Offset(size.width, size.height))
                } else {
                    drawLine(PassDash, Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = width, pathEffect = dash)
                    drawCircle(PassBg, notch, Offset(0f, 0f))
                    drawCircle(PassBg, notch, Offset(size.width, 0f))
                }
            }
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
    ) {
        Text(
            "للتفعيل أو الحصول على اشتراك IPTV\nتواصل مع الدعم عبر مسح رمز QR",
            fontSize = 12.5.sp,
            lineHeight = 22.sp,
            color = PassMuted,
            textAlign = TextAlign.Center
        )
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, PassBrass)
        ) {
            Image(
                painterResource(com.streamvault.app.R.drawable.eagle_x_support_qr),
                "Support QR Code",
                Modifier.size(124.dp).padding(6.dp)
            )
        }
    }
}

private data class ActivationResponse(val activated: Boolean, val status: String?, val config: ManagedActivationConfig?)

private fun requestActivation(activationId: String): ActivationResponse {
    val connection = (URL(BuildConfig.DEVICE_ACTIVATION_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
    }
    try {
        connection.outputStream.use { it.write(JSONObject().put("mac_address", activationId).toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val json = JSONObject(body)
        return ActivationResponse(json.optBoolean("activated", false), json.optString("status").takeIf(String::isNotBlank),
            json.optJSONObject("config")?.let { c ->
                ManagedActivationConfig(
                    c.optString("expires_at").takeIf(String::isNotBlank),
                    c.optJSONObject("video")?.let { ManagedVideoConfig(it.optString("server_url"), it.optString("username"), it.optString("password")) },
                    c.optJSONObject("audio")?.let { ManagedAudioConfig(it.optString("m3u_url").takeIf(String::isNotBlank)) }
                )
            })
    } finally { connection.disconnect() }
}

private fun readActivationId(context: Context): String {
    for (name in listOf("wlan0", "eth0", "en0")) {
        val mac = runCatching { NetworkInterface.getByName(name)?.hardwareAddress?.toMac() }.getOrNull()
        if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") return mac
    }
    val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
    for (networkInterface in interfaces) {
        val mac = runCatching { networkInterface.hardwareAddress?.toMac() }.getOrNull()
        if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") return mac
    }
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.FINGERPRINT
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(("aeriotv-android:" + androidId).toByteArray(Charsets.UTF_8))
    return digest.copyOf(6).also { it[0] = (it[0].toInt() and 0xFC or 0x02).toByte() }
        .joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
}
private fun ByteArray.toMac(): String = joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
