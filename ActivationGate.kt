package com.streamvault.app.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class ActivationState {
    CHECKING, TRIAL, ACTIVATING, NOT_REGISTERED, ACTIVE, SUSPENDED, EXPIRED, ERROR
}

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
    var expiresAt by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun check() {
        val previousState = state
        if (previousState != ActivationState.ACTIVE && previousState != ActivationState.TRIAL) {
            state = ActivationState.CHECKING
        }
        errorText = null

        runCatching { withContext(Dispatchers.IO) { requestActivation(activationId) } }
            .onSuccess { response ->
                expiresAt = response.expiresAt
                if (response.activated) {
                    retryPendingRegistration(context, activationId)
                }

                if (!response.activated) {
                    showForm = false
                    state = when (response.status) {
                        "expired" -> ActivationState.EXPIRED
                        "suspended" -> ActivationState.SUSPENDED
                        else -> ActivationState.NOT_REGISTERED
                    }
                    return@onSuccess
                }

                if (response.status == "trial" && response.config == null) {
                    state = ActivationState.TRIAL
                    val hasProvider = providerRepository.getProviders().first().any {
                        it.type == ProviderType.XTREAM_CODES
                    }
                    showForm = false
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
                showForm = false
                state = ActivationState.ACTIVE
            }
            .onFailure {
                if (previousState != ActivationState.TRIAL && previousState != ActivationState.ACTIVE) {
                    state = ActivationState.ERROR
                    errorText = "تعذر الاتصال بخادم التفعيل"
                }
            }
    }

    suspend fun saveTrialCredentials(host: String, username: String, password: String): Boolean {
        state = ActivationState.ACTIVATING
        errorText = null
        val cleanHost = host.trim()
        val cleanUsername = username.trim()
        val result = withContext(Dispatchers.IO) {
            validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = cleanHost,
                    username = cleanUsername,
                    password = password,
                    name = "Live TV",
                    xtreamFastSyncEnabled = true
                )
            )
        }
        return if (result is ValidateAndAddProviderResult.Success ||
            result is ValidateAndAddProviderResult.SavedWithWarning
        ) {
            context.getSharedPreferences("streamvault_trial_registration", Context.MODE_PRIVATE)
                .edit()
                .putString("host", cleanHost)
                .putString("username", cleanUsername)
                .putString("password", password)
                .apply()

            val registration = withContext(Dispatchers.IO) {
                registerTrialCredentials(
                    activationId = activationId,
                    host = cleanHost,
                    username = cleanUsername,
                    password = password
                )
            }
            if (!registration) {
                state = ActivationState.TRIAL
                errorText = "تم حفظ الاشتراك محلياً، لكن تعذر تسجيله في لوحة الإدارة."
                return@saveTrialCredentials false
            }
            context.getSharedPreferences("streamvault_trial_registration", Context.MODE_PRIVATE)
                .edit().clear().apply()
            showForm = false
            state = ActivationState.TRIAL
            true
        } else {
            state = ActivationState.TRIAL
            errorText = "تعذر حفظ بيانات الاشتراك. تأكد من Host واسم المستخدم وكلمة المرور."
            false
        }
    }

    LaunchedEffect(activationId) {
        check()
        while (isActive) {
            delay(60_000)
            check()
        }
    }

    val hasTrialProvider by produceState(initialValue = false, state) {
        value = providerRepository.getProviders().first().any {
            it.type == ProviderType.XTREAM_CODES
        }
    }

    if ((state == ActivationState.ACTIVE || (state == ActivationState.TRIAL && hasTrialProvider)) && !showForm) {
        content()
    } else {
        ActivationScreen(
            activationId = activationId,
            state = state,
            expiresAt = expiresAt,
            errorText = errorText,
            showForm = showForm,
            onStart = { showForm = true },
            onSave = { host, username, password ->
                scope.launch { saveTrialCredentials(host, username, password) }
            },
            onRetry = { scope.launch { check() } }
        )
    }
}

// ===== Activation screen UI: Eagle X2 "pass" design. UI only, no activation logic in this section. =====

private val PassBg = Color(0xFF0B0F1A)
private val PassMid = Color(0xFF0E1422)
private val PassLight = Color(0xFF1B273F)
private val PassCtaTop = Color(0xFF2A3A5E)
private val PassBrass = Color(0xFFC8A45A)
private val PassBrassSoft = Color(0xFFE6C982)
private val PassText = Color(0xFFEEF1F7)
private val PassMuted = Color(0xFF94A3BF)
private val PassDash = Color(0xFF3A4A6A)

@Composable
internal fun ActivationScreen(
    activationId: String,
    state: ActivationState,
    expiresAt: String?,
    errorText: String?,
    showForm: Boolean,
    onStart: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onRetry: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val daysRemaining = remember(expiresAt) {
        expiresAt?.let {
            runCatching {
                maxOf(0L, ChronoUnit.DAYS.between(Instant.now(), Instant.parse(it)) + 1)
            }.getOrNull()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(PassBg)) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        Box(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val passShape = RoundedCornerShape(26.dp)
                val passModifier = Modifier
                    .widthIn(max = if (landscape) 900.dp else 380.dp)
                    .fillMaxWidth()
                    .clip(passShape)
                    .border(1.dp, PassBrass.copy(alpha = 0.22f), passShape)

                val bodyContent: @Composable ColumnScope.() -> Unit = {
                    when {
                        state == ActivationState.EXPIRED -> {
                            Text(
                                "عذراً، لقد انتهت فترتك التجريبية",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PassText,
                                textAlign = TextAlign.Center,
                                lineHeight = 28.sp
                            )
                        }
                        showForm -> {
                            Text(
                                "إضافة الاشتراك",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PassText
                            )
                            Text(
                                "أدخل بيانات Xtream Codes للبدء",
                                fontSize = 13.5.sp,
                                color = PassMuted,
                                textAlign = TextAlign.Center
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Host") }
                                )
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Username") }
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Password") },
                                    visualTransformation = PasswordVisualTransformation()
                                )
                            }
                            PassCtaButton(
                                text = "حفظ",
                                onClick = { onSave(host, username, password) },
                                enabled = host.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
                                    state != ActivationState.ACTIVATING
                            )
                        }
                        state == ActivationState.TRIAL -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PassStarIcon()
                                Text(
                                    "مرحباً بك",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = PassText
                                )
                            }
                            Text(
                                "لديك فترة تجريبية مجانية لمدة 7 أيام",
                                fontSize = 13.5.sp,
                                color = PassMuted,
                                textAlign = TextAlign.Center
                            )
                            if (daysRemaining != null) {
                                Text(
                                    "متبقي $daysRemaining أيام",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PassBrassSoft
                                )
                            }
                            PassCtaButton(text = "اضغط هنا لتبدأ", onClick = onStart)
                        }
                        else -> {
                            val statusText = when (state) {
                                ActivationState.CHECKING -> "جاري التحقق..."
                                ActivationState.ACTIVATING -> "جاري تجهيز الاشتراك..."
                                ActivationState.NOT_REGISTERED -> "جهاز جديد"
                                ActivationState.SUSPENDED -> "هذا الجهاز موقوف"
                                ActivationState.ERROR -> errorText ?: "تعذر التحقق من الجهاز"
                                else -> ""
                            }
                            Text(
                                statusText,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PassText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (errorText != null && state != ActivationState.EXPIRED) {
                        Text(
                            errorText,
                            fontSize = 13.sp,
                            color = Color(0xFFFF9B9B),
                            textAlign = TextAlign.Center
                        )
                    }

                    if (state == ActivationState.EXPIRED || state == ActivationState.ERROR ||
                        state == ActivationState.NOT_REGISTERED || state == ActivationState.SUSPENDED
                    ) {
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, PassBrass.copy(alpha = 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PassBrassSoft)
                        ) {
                            Text("إعادة التحقق", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (landscape) {
                    Row(passModifier.height(IntrinsicSize.Min)) {
                        PassHead(activationId, Modifier.weight(1.05f).fillMaxHeight())
                        Column(
                            Modifier.weight(1.15f).fillMaxHeight()
                                .background(PassMid)
                                .padding(horizontal = 24.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                            content = bodyContent
                        )
                        PassStub(landscape = true, modifier = Modifier.weight(0.9f).fillMaxHeight())
                    }
                } else {
                    Column(passModifier) {
                        PassHead(activationId, Modifier.fillMaxWidth())
                        Column(
                            Modifier.fillMaxWidth()
                                .background(PassMid)
                                .padding(horizontal = 24.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                            content = bodyContent
                        )
                        PassStub(landscape = false, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PassHead(activationId: String, modifier: Modifier) {
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(PassLight, PassMid, PassBg)))
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painterResource(com.streamvault.app.R.drawable.eagle_x_activation_logo),
            "Eagle X2",
            Modifier.size(140.dp)
        )
        Text(
            "Eagle X2",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = PassText
        )
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                "ID : $activationId",
                modifier = Modifier
                    .border(1.dp, PassBrass.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                color = PassBrassSoft,
                maxLines = 1
            )
        }
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
                val lineWidth = 2.dp.toPx()
                if (landscape) {
                    drawLine(PassDash, Offset(size.width, inset), Offset(size.width, size.height - inset), strokeWidth = lineWidth, pathEffect = dash)
                    drawCircle(PassBg, notch, Offset(size.width, 0f))
                    drawCircle(PassBg, notch, Offset(size.width, size.height))
                } else {
                    drawLine(PassDash, Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = lineWidth, pathEffect = dash)
                    drawCircle(PassBg, notch, Offset(0f, 0f))
                    drawCircle(PassBg, notch, Offset(size.width, 0f))
                }
            }
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
    ) {
        Text(
            "لتفعيل التطبيق أو الحصول على اشتراك IPTV\nتواصل مع الدعم عبر مسح رمز QR",
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

@Composable
private fun PassCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(PassCtaTop, PassLight)), shape)
            .border(1.dp, PassBrass.copy(alpha = if (enabled) 0.45f else 0.2f), shape),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = PassBrassSoft,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = PassBrassSoft.copy(alpha = 0.45f)
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun PassStarIcon() {
    Canvas(Modifier.size(22.dp)) {
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
}

// ===== End of UI section. Everything below is unchanged activation / trial / device-id logic. =====

private data class ActivationResponse(
    val activated: Boolean,
    val status: String?,
    val expiresAt: String?,
    val config: ManagedActivationConfig?
)

private suspend fun retryPendingRegistration(
    context: Context,
    activationId: String
): Boolean {
    val prefs = context.getSharedPreferences("streamvault_trial_registration", Context.MODE_PRIVATE)
    val host = prefs.getString("host", null)?.trim().orEmpty()
    val username = prefs.getString("username", null).orEmpty()
    val password = prefs.getString("password", null).orEmpty()
    if (host.isBlank() || username.isBlank() || password.isBlank()) return true

    val registered = withContext(Dispatchers.IO) {
        registerTrialCredentials(
            activationId = activationId,
            host = host,
            username = username,
            password = password
        )
    }
    if (registered) {
        prefs.edit().clear().apply()
    }
    return registered
}

private fun registerTrialCredentials(
    activationId: String,
    host: String,
    username: String,
    password: String
): Boolean {
    val connection = (URL(BuildConfig.TRIAL_REGISTER_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }
    return try {
        connection.outputStream.use {
            it.write(
                JSONObject()
                    .put("device_id", activationId)
                    .put("host", host)
                    .put("username", username)
                    .put("password", password)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) return false
        JSONObject(body).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    } finally {
        connection.disconnect()
    }
}

private fun requestActivation(activationId: String): ActivationResponse {
    val connection = (URL(BuildConfig.DEVICE_ACTIVATION_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }
    try {
        connection.outputStream.use {
            it.write(
                JSONObject().put("device_id", activationId)
                    .toString().toByteArray(Charsets.UTF_8)
            )
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val json = JSONObject(body)
        return ActivationResponse(
            activated = json.optBoolean("activated", false),
            status = json.optString("status").takeIf(String::isNotBlank),
            expiresAt = json.optString("expires_at").takeIf(String::isNotBlank),
            config = json.optJSONObject("config")?.let { c ->
                ManagedActivationConfig(
                    c.optString("expires_at").takeIf(String::isNotBlank),
                    c.optJSONObject("video")?.let {
                        ManagedVideoConfig(
                            it.optString("server_url"),
                            it.optString("username"),
                            it.optString("password")
                        )
                    },
                    c.optJSONObject("audio")?.let {
                        ManagedAudioConfig(it.optString("m3u_url").takeIf(String::isNotBlank))
                    }
                )
            }
        )
    } finally {
        connection.disconnect()
    }
}

private fun readActivationId(context: Context): String {
    val prefs = context.getSharedPreferences("streamvault_device_identity", Context.MODE_PRIVATE)
    val stored = prefs.getString("device_id", null)
    if (!stored.isNullOrBlank()) return stored

    // Android ID is scoped to the device/user and normally survives app
    // uninstall/reinstall. It is preferable to Wi-Fi MAC, which Android
    // restricts/randomizes on modern releases.
    val androidId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: Build.FINGERPRINT

    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(("streamvault-device:" + androidId).toByteArray(Charsets.UTF_8))

    val token = digest.take(9).joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
    val deviceId = "EV-" + token.chunked(3).joinToString("")

    prefs.edit().putString("device_id", deviceId).apply()
    return deviceId
}

private fun ByteArray.toMac(): String =
    joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
