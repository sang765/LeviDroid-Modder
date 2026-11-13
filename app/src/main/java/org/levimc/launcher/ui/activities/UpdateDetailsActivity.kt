package org.levimc.launcher.ui.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import org.levimc.launcher.R
import org.levimc.launcher.ui.GithubAlertsPlugin
import org.levimc.launcher.util.GithubReleaseUpdater
import org.levimc.launcher.util.PersonalizationManager
import org.levimc.launcher.util.PermissionsHandler
import org.levimc.launcher.util.TextTranslator
import java.io.File
import java.util.Locale

class UpdateDetailsActivity : ComponentActivity() {

    companion object {
        private const val OWNER = "sang765"
        private const val REPO = "LeviDroid-Modder"
        private const val EXTRA_ASK_DIALOG = "ask_dialog"
        private const val EXTRA_RELEASE_TITLE = "release_title"
        private const val EXTRA_NOTES = "notes"
        private const val EXTRA_URL = "url"
        private const val EXTRA_APK_SIZE = "apk_size"

        @JvmStatic
        fun start(context: Context, releaseTitle: String, releaseNotes: String,
                  downloadUrl: String, apkSize: Long, showAskDialog: Boolean) {
            val intent = Intent(context, UpdateDetailsActivity::class.java)
                .putExtra(EXTRA_ASK_DIALOG, showAskDialog)
                .putExtra(EXTRA_RELEASE_TITLE, releaseTitle)
                .putExtra(EXTRA_NOTES, releaseNotes)
                .putExtra(EXTRA_URL, downloadUrl)
                .putExtra(EXTRA_APK_SIZE, apkSize)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val misans = FontFamily(Font(R.font.misans))
    private lateinit var updater: GithubReleaseUpdater
    private lateinit var markwon: Markwon
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var permissionResultLauncher: ActivityResultLauncher<Intent>? = null

    private var apkUrl = ""
    private var apkSize = -1L
    private var releaseTitle = ""
    private var originalNotes = ""
    private var translatedNotes: String? = null
    private val showDetails = mutableStateOf(false)
    private val note = mutableStateOf("")
    private val translating = mutableStateOf(false)
    private val showingTranslation = mutableStateOf(false)
    private val downloading = mutableStateOf(false)
    private val downloadDone = mutableStateOf(false)
    private val downloaded = mutableLongStateOf(0L)
    private val totalBytes = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { PermissionsHandler.getInstance().onActivityResult(it.resultCode, it.data) }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { enqueueDownload() }
        updater = GithubReleaseUpdater(this, OWNER, REPO, permissionResultLauncher)
        markwon = Markwon.builder(this).usePlugin(GithubAlertsPlugin(this)).build()
        val extras = intent.extras
        originalNotes = extras?.getString(EXTRA_NOTES).orEmpty()
        note.value = originalNotes
        apkUrl = extras?.getString(EXTRA_URL).orEmpty()
        apkSize = extras?.getLong(EXTRA_APK_SIZE, -1L) ?: -1L
        releaseTitle = extras?.getString(EXTRA_RELEASE_TITLE).orEmpty()
        val askDialog = extras?.getBoolean(EXTRA_ASK_DIALOG, false) ?: false
        showDetails.value = !askDialog
        setContent { UpdateRoot(releaseTitle) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            updater.cancelDownload()
        }
        GithubReleaseUpdater.releaseUpdateFlow()
    }

    private fun startDownload() {
        if (downloading.value || downloadDone.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enqueueDownload()
    }

    private fun enqueueDownload() {
        if (downloading.value || downloadDone.value) return
        downloading.value = true
        downloaded.longValue = 0L
        totalBytes.longValue = -1L
        updater.downloadAndInstall(apkUrl, releaseTitle, object : GithubReleaseUpdater.DownloadListener {
            override fun onProgress(progress: Long, total: Long) {
                downloaded.longValue = progress
                totalBytes.longValue = total
            }

            override fun onComplete(apkFile: File) {
                downloading.value = false
                downloadDone.value = true
            }

            override fun onError(message: String) {
                downloading.value = false
                Toast.makeText(this@UpdateDetailsActivity, getString(R.string.update_failed, message), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun toggleTranslate() {
        if (translating.value) return
        if (originalNotes.isBlank()) {
            Toast.makeText(this, R.string.changelog_translate_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (translatedNotes == null) {
            translating.value = true
            val lines = originalNotes.split("\n")
            TextTranslator.translate(lines, object : TextTranslator.Callback {
                override fun onDone(translatedLines: List<String>) {
                    if (isFinishing || isDestroyed) return
                    translating.value = false
                    if (translatedLines.size != lines.size) {
                        translateFailed()
                        return
                    }
                    translatedNotes = translatedLines.joinToString("\n")
                    note.value = translatedNotes.orEmpty()
                    showingTranslation.value = true
                }

                override fun onError() {
                    if (isFinishing || isDestroyed) return
                    translateFailed()
                }

                private fun translateFailed() {
                    translating.value = false
                    Toast.makeText(this@UpdateDetailsActivity, R.string.changelog_translate_failed, Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            showingTranslation.value = !showingTranslation.value
            note.value = if (showingTranslation.value) translatedNotes.orEmpty() else originalNotes
        }
    }

    private fun buildColorScheme(): ColorScheme {
        fun color(id: Int) = Color(getColor(id))
        val accent = PersonalizationManager(this).accentColor
        val primary = if (accent != 0) Color(accent) else color(R.color.primary)
        return lightColorScheme(
            primary = primary,
            onPrimary = color(R.color.on_primary),
            secondary = primary,
            onSecondary = color(R.color.on_secondary),
            tertiary = primary,
            onTertiary = color(R.color.on_tertiary),
            surface = color(R.color.surface),
            onSurface = color(R.color.on_surface),
            surfaceVariant = color(R.color.surface_variant),
            onSurfaceVariant = color(R.color.on_surface_variant),
            background = color(R.color.background),
            onBackground = color(R.color.on_background),
        )
    }

    @Composable
    private fun UpdateRoot(releaseTitle: String) {
        val baseTypography = MaterialTheme.typography
        val typography = remember(baseTypography) {
            baseTypography.copy(
                labelLarge = baseTypography.labelLarge.copy(fontFamily = misans),
                bodyLarge = baseTypography.bodyLarge.copy(fontFamily = misans),
                bodyMedium = baseTypography.bodyMedium.copy(fontFamily = misans),
            )
        }
        MaterialTheme(
            colorScheme = remember { buildColorScheme() },
            typography = typography,
        ) {
            Crossfade(targetState = showDetails.value, label = "update-phase") { details ->
                if (details) DetailsContent(releaseTitle) else AskCard()
            }
        }
    }

    @Composable
    private fun AskCard() {
        val colorScheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { finish() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .background(colorScheme.surface, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconMobileArrowDown(26.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.update_dialog_title, stringResource(R.string.app_name)),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = misans,
                        color = colorScheme.onSurface,
                    ),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { showDetails.value = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_now),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = misans),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { finish() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.surfaceVariant,
                        contentColor = colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = misans),
                    )
                }
            }
        }
    }

    @Composable
    private fun DetailsContent(releaseTitle: String) {
        val colorScheme = MaterialTheme.colorScheme
        val cardShape = RoundedCornerShape(28.dp)
        val pill = RoundedCornerShape(50)
        val enter = remember { Animatable(0.94f) }
        LaunchedEffect(Unit) {
            enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.36f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = enter.value
                            scaleY = enter.value
                        }
                        .background(colorScheme.surfaceVariant, cardShape)
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .background(colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconMobileArrowDown(30.dp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.update_detail_title),
                        style = TextStyle(
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = misans,
                            color = colorScheme.onSurface,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    UpdateChip(stringResource(R.string.update_detail_version, releaseTitle))
                    if (apkSize > 0) {
                        Spacer(Modifier.height(6.dp))
                        UpdateChip(stringResource(R.string.update_apk_size, GithubReleaseUpdater.formatBytes(apkSize)))
                    }
                    if (downloading.value || downloadDone.value) {
                        Spacer(Modifier.height(18.dp))
                        DownloadProgress()
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(0.64f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = enter.value
                            scaleY = enter.value
                        }
                        .background(colorScheme.surfaceVariant, cardShape)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                TextView(ctx).apply {
                                    movementMethod = LinkMovementMethod.getInstance()
                                    setTextIsSelectable(true)
                                    textSize = 14f
                                }
                            },
                            update = { view ->
                                view.setTextColor(colorScheme.onSurface.copy(alpha = 0.9f).toArgb())
                                markwon.setMarkdown(view, note.value)
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { finish() },
                    shape = pill,
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.onSurface),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = misans),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (Locale.getDefault().language != "en") {
                    TextButton(
                        onClick = { toggleTranslate() },
                        enabled = !translating.value,
                        shape = pill,
                        colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.primary),
                    ) {
                        Text(
                            text = stringResource(translateLabelId()),
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = misans),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { startDownload() },
                    enabled = !downloading.value && !downloadDone.value,
                    shape = pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                    ),
                    modifier = Modifier.height(50.dp).widthIn(min = 148.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_now),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = misans),
                    )
                }
            }
        }
    }

    @Composable
    private fun UpdateChip(text: String) {
        val colorScheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .background(colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = misans,
                    color = colorScheme.onSurface.copy(alpha = 0.72f),
                ),
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun DownloadProgress() {
        val colorScheme = MaterialTheme.colorScheme
        val total = totalBytes.longValue
        val known = total > 0
        val percent = if (known) (downloaded.longValue * 100 / total) else 0
        Column(
            modifier = Modifier.fillMaxWidth(0.86f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.downloading_update),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = misans,
                    color = colorScheme.onSurface.copy(alpha = 0.7f),
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            if (known) {
                LinearProgressIndicator(progress = { (percent / 100f).coerceIn(0f, 1f) })
            } else {
                LinearProgressIndicator()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (known) {
                    stringResource(
                        R.string.update_download_progress,
                        percent,
                        GithubReleaseUpdater.formatBytes(downloaded.longValue),
                        GithubReleaseUpdater.formatBytes(total),
                    )
                } else {
                    GithubReleaseUpdater.formatBytes(downloaded.longValue)
                },
                style = TextStyle(
                    fontSize = 12.sp,
                    fontFamily = misans,
                    color = colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun IconMobileArrowDown(sizeDp: Dp) {
        Icon(
            painter = painterResource(R.drawable.ic_mobile_arrow_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(sizeDp),
        )
    }

    private fun translateLabelId(): Int = when {
        translating.value -> R.string.changelog_translating
        showingTranslation.value -> R.string.changelog_show_original
        else -> R.string.changelog_translate
    }
}
