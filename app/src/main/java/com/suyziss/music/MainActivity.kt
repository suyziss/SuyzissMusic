package com.suyziss.music

import android.Manifest
import android.content.ContentUris
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class Track(val id: Long, val title: String, val artist: String, val uri: Uri, val artwork: Uri? = null)
private data class PlayerPrefs(
    val theme: String = "system", val accent: String = "purple",
    val dynamic: Boolean = true, val amoled: Boolean = false, val language: String = "ru"
)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controllerFuture = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        setContent { SuyzissApp() }
    }
    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

private fun loadPrefs(context: Context): PlayerPrefs {
    val p = context.getSharedPreferences("suyziss_music", Context.MODE_PRIVATE)
    return PlayerPrefs(p.getString("theme", "system") ?: "system",
        p.getString("accent", "purple") ?: "purple", p.getBoolean("dynamic", true),
        p.getBoolean("amoled", false), p.getString("language", "ru") ?: "ru")
}
private fun savePrefs(context: Context, prefs: PlayerPrefs) {
    context.getSharedPreferences("suyziss_music", Context.MODE_PRIVATE).edit()
        .putString("theme", prefs.theme).putString("accent", prefs.accent)
        .putBoolean("dynamic", prefs.dynamic).putBoolean("amoled", prefs.amoled).putString("language", prefs.language).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuyzissApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var prefs by remember { mutableStateOf(loadPrefs(context)) }
    fun updatePrefs(next: PlayerPrefs) { prefs = next; savePrefs(context, next) }
    val accent = when (prefs.accent) {
        "blue" -> Color(0xFFB8C8FF); "green" -> Color(0xFFB6DCC2)
        "pink" -> Color(0xFFFFB1C8); else -> Color(0xFFD0BCFF)
    }
    val dark = when (prefs.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val scheme = when {
        prefs.dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        prefs.dynamic && Build.VERSION.SDK_INT >= 31 && !dark -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = accent, onPrimary = Color(0xFF381E72), secondary = accent.copy(alpha = .78f), surface = if (prefs.amoled) Color.Black else Color(0xFF141218), background = if (prefs.amoled) Color.Black else Color(0xFF141218), surfaceVariant = if (prefs.amoled) Color(0xFF101010) else Color(0xFF29242F))
        else -> lightColorScheme(primary = Color(0xFF6750A4), secondary = accent,
            background = Color(0xFFFFF7FF), surface = Color(0xFFFFF7FF))
    }
    val ru = prefs.language == "ru"
    fun tr(en: String, russian: String) = if (ru) russian else en
    var tab by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var selected by remember { mutableStateOf<Track?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var playbackDuration by remember { mutableLongStateOf(0L) }
    val controllerFuture = remember(context) { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener({ controller = runCatching { controllerFuture.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }
    DisposableEffect(controller) {
        val player = controller
        if (player != null) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val uri = mediaItem?.localConfiguration?.uri
                    if (uri != null) {
                        selected = Track(mediaItem.mediaId.toLongOrNull() ?: -1L,
                            mediaItem.mediaMetadata.title?.toString() ?: "Unknown track",
                            mediaItem.mediaMetadata.artist?.toString() ?: "Unknown artist", uri, mediaItem.mediaMetadata.artworkUri)
                    }
                    playbackDuration = player.duration.coerceAtLeast(0L)
                    playbackPosition = player.currentPosition.coerceAtLeast(0L)
                }
                override fun onPlaybackStateChanged(state: Int) { playbackDuration = player.duration.coerceAtLeast(0L) }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        } else onDispose { }
    }
    LaunchedEffect(controller, isPlaying) {
        while (isActive) {
            controller?.let { playbackPosition = it.currentPosition.coerceAtLeast(0L); playbackDuration = it.duration.coerceAtLeast(0L) }
            delay(400)
        }
    }
    fun startTrack(index: Int) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val queue = tracks.map { track -> MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setArtworkUri(track.artwork).build()).build() }
        selected = tracks[index]
        player.setMediaItems(queue, index, C.TIME_UNSET)
        player.prepare()
        player.play()
        showFullPlayer = true
    }
    var showEqualizer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showVisualizer by remember { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scanLibrary() {
        val result = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID)
        context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += Track(id, cursor.getString(titleCol) ?: "Unknown track",
                    cursor.getString(artistCol) ?: "Unknown artist", ContentUris.withAppendedId(collection, id),
                    Uri.parse("content://media/external/audio/albumart/${cursor.getLong(albumCol)}"))
            }
        }
        tracks = result
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanLibrary()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            val name = it.lastPathSegment?.substringAfterLast('/') ?: "Imported audio"
            Toast.makeText(context, tr("Selected: $name", "Выбран файл: $name"), Toast.LENGTH_SHORT).show()
            controller?.let { player ->
                selected = Track(-1L, name, "Imported audio", it)
                val item = MediaItem.Builder().setUri(it).setMediaMetadata(MediaMetadata.Builder().setTitle(name).setArtist("Imported audio").build()).build()
                player.setMediaItem(item); player.prepare(); player.play(); showFullPlayer = true
            }
        }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) scanLibrary()
        else permissionLauncher.launch(permission)
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("Suyziss Music", fontWeight = FontWeight.Bold)
                        Text(tr("Your music, your atmosphere", "Твоя музыка, твоя атмосфера"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, actions = {
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = tr("Import audio", "Импорт аудио"))
                    }
                })
            },
            bottomBar = {
                Column {
                    AnimatedVisibility(visible = selected != null, enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()) { selected?.let { track -> MiniPlayer(track, isPlaying, { showFullPlayer = true }, { if (isPlaying) controller?.pause() else controller?.play() }) } }
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text(tr("Library", "Музыка")) })
                        NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Telegram") })
                        NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.GraphicEq, null) }, label = { Text(tr("Studio", "Студия")) })
                        NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(tr("Settings", "Настройки")) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (tab) {
                    0 -> {
                        Text(tr("Your library", "Моя музыка"), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                        Text(tr("${tracks.size} tracks found", "Найдено треков: ${tracks.size}"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        if (tracks.isEmpty()) EmptyState(tr("No music found", "Музыка не найдена"),
                            tr("Add MP3 files to your device or import audio using +.", "Добавь MP3 на устройство или импортируй аудио кнопкой +."))
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(tracks, key = { it.id }) { track -> TrackRow(track, selected?.id == track.id, isPlaying && selected?.id == track.id) { startTrack(tracks.indexOf(track)) } }
                        }
                    }
                    1 -> {
                        Text(tr("Telegram Music", "Музыка из Telegram"), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
                        EmptyState(tr("Import Telegram audio", "Импорт аудио из Telegram"),
                            tr("Choose audio shared or saved from Telegram. Private Telegram cache is not scanned.",
                                "Выбери аудио, сохранённое или отправленное из Telegram. Закрытый кэш Telegram не сканируется."))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Choose audio file", "Выбрать аудиофайл"))
                        }
                    }
                    2 -> {
                        Text(tr("Studio", "Студия"), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
                        FeatureCard(tr("Equalizer", "Эквалайзер"), tr("Tune bass, mids and treble", "Настройка низких, средних и высоких частот"), Icons.Default.Equalizer) { showEqualizer = true }
                        FeatureCard(tr("Lyrics", "Тексты песен"), tr("Lyrics for the current track", "Текст текущей песни"), Icons.Default.Lyrics) { showLyrics = true }
                        FeatureCard(tr("Visualizer", "Визуализатор"), tr("Audio-reactive visual experience", "Визуальные эффекты под музыку"), Icons.Default.GraphicEq) { showVisualizer = true }
                    }
                    else -> SettingsScreen(prefs, ::updatePrefs, ::tr)
                }
                            }
        }
        AnimatedVisibility(visible = showFullPlayer && selected != null, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(220)), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(180))) {
            selected?.let { currentTrack ->
                BackHandler { showFullPlayer = false }
                FullPlayerScreen(track = currentTrack, controller = controller, playing = isPlaying, position = playbackPosition, duration = playbackDuration,
                    onClose = { showFullPlayer = false }, onToggle = { if (isPlaying) controller?.pause() else controller?.play() },
                    onPrevious = { controller?.seekToPreviousMediaItem() }, onNext = { controller?.seekToNextMediaItem() },
                    onSeek = { controller?.seekTo(it) }, onLyrics = { showLyrics = true })
            }
        }
        if (showEqualizer) FeatureDialog(tr("Equalizer", "Эквалайзер"),
            tr("Equalizer controls will be connected to the audio session in a later build.",
                "Управление эквалайзером будет подключено к аудиосессии в следующей версии.")) { showEqualizer = false }
        if (showLyrics) FeatureDialog(tr("Lyrics", "Тексты песен"),
            tr("Embedded lyrics and timed LRC synchronization are planned.", "Поддержка встроенных текстов и синхронизация LRC запланированы.")) { showLyrics = false }
        if (showVisualizer) FeatureDialog(tr("Visualizer", "Визуализатор"),
            tr("Audio-reactive visualizer rendering is planned.", "Аудиореактивный визуализатор запланирован.")) { showVisualizer = false }
    }
}

@Composable
private fun MiniPlayer(track: Track, playing: Boolean, open: () -> Unit, toggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clickable(onClick = open)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (track.artwork != null) AsyncImage(model = track.artwork, contentDescription = "Album art", modifier = Modifier.size(42.dp), contentScale = ContentScale.Crop) else Icon(Icons.Default.Album, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                Text(track.artist, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = toggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
            IconButton(onClick = open) { Icon(Icons.Default.OpenInFull, "Open player") }
        }
    }
}

@Composable
private fun FullPlayerScreen(track: Track, controller: MediaController?, playing: Boolean, position: Long, duration: Long,
    onClose: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onLyrics: () -> Unit) {
    var slider by remember(track.uri) { mutableFloatStateOf(0f) }
    LaunchedEffect(position, duration) { if (duration > 0L) slider = position.toFloat().coerceIn(0f, duration.toFloat()) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player", modifier = Modifier.size(32.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Suyziss Music", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onLyrics) { LyricsGlyph(Modifier.size(23.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") }
        }
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .42f), MaterialTheme.colorScheme.tertiary.copy(alpha = .18f), MaterialTheme.colorScheme.surfaceVariant))), contentAlignment = Alignment.Center) {
                if (track.artwork != null) AsyncImage(model = track.artwork, contentDescription = "Album art", modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Crop) else Icon(Icons.Default.Album, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(116.dp))
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(20.dp))
            Slider(value = slider, onValueChange = { slider = it }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), onValueChangeFinished = { onSeek(slider.toLong()) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(slider.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (controller?.shuffleModeEnabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(38.dp)) }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(76.dp)) { androidx.compose.animation.AnimatedContent(targetState = playing, label = "play_pause_icon") { active -> Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Pause" else "Play", modifier = Modifier.size(38.dp)) } }
            IconButton(onClick = onNext, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(38.dp)) }
            IconButton(onClick = { controller?.repeatMode = when (controller?.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) {
                Icon(Icons.Default.Repeat, "Repeat", tint = if (controller?.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

@Composable
private fun SettingsScreen(prefs: PlayerPrefs, update: (PlayerPrefs) -> Unit, tr: (String, String) -> String) {
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Text(tr("Appearance & settings", "Внешний вид и настройки"), style = MaterialTheme.typography.headlineMedium)
        Text(tr("Make the player yours", "Настрой плеер под себя"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        Text(tr("Theme", "Тема"), style = MaterialTheme.typography.titleMedium)
        SettingChoice(listOf("system" to tr("System", "Системная"), "dark" to tr("Dark", "Тёмная"),
            "light" to tr("Light", "Светлая")), prefs.theme) {
            update(prefs.copy(theme = it, dynamic = if (it == "system") prefs.dynamic else false))
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("Color", "Цвет"), style = MaterialTheme.typography.titleMedium)
        SettingChoice(listOf("purple" to tr("Purple", "Фиолетовый"), "blue" to tr("Blue", "Голубой"),
            "green" to tr("Green", "Зелёный"), "pink" to tr("Pink", "Розовый")), prefs.accent) {
            update(prefs.copy(accent = it, dynamic = false))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Dynamic system colors", "Динамические цвета Android"))
                Text(tr("Use your device wallpaper palette", "Использовать палитру обоев устройства"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = prefs.dynamic, onCheckedChange = { update(prefs.copy(dynamic = it, theme = "system")) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AMOLED black")
                Text(tr("Pure black background for OLED screens", "Чёрный фон для OLED-экрана"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = prefs.amoled, onCheckedChange = { update(prefs.copy(amoled = it, dynamic = false, theme = "dark")) })
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("Language", "Язык"), style = MaterialTheme.typography.titleMedium)
        SettingChoice(listOf("ru" to "Русский", "en" to "English"), prefs.language) {
            update(prefs.copy(language = it))
        }
        Spacer(Modifier.height(18.dp))
        Text("Suyziss Music", fontWeight = FontWeight.SemiBold)
        Text("Version 0.1.0 · Material 3", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingChoice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            FilterChip(selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun FeatureDialog(title: String, message: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = dismiss) { Text("OK") } })
}

private fun playUri(context: Context, uri: Uri, title: String, artist: String) {
    val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    future.addListener({
        val controller = future.get()
        controller.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).build()).build())
        controller.prepare(); controller.play()
    }, ContextCompat.getMainExecutor(context))
}
private fun pausePlayback(context: Context) {
    val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    future.addListener({ future.get().pause() }, ContextCompat.getMainExecutor(context))
}

@Composable
private fun TrackRow(track: Track, selected: Boolean, playing: Boolean, onClick: () -> Unit) {
    val rowColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, label = "track_row_color")
    Surface(shape = RoundedCornerShape(18.dp), color = rowColor,
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(52.dp)) {
                if (track.artwork != null) AsyncImage(model = track.artwork, contentDescription = "Album art", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, fontWeight = FontWeight.Medium)
                Text(track.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.animation.AnimatedContent(targetState = playing, label = "track_play_state") { active -> Icon(if (active) Icons.Default.Equalizer else Icons.Default.PlayArrow, contentDescription = "Play") }
        }
    }
}
@Composable
private fun EmptyState(title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun FeatureCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null)
        }
    }
}


@Composable
private fun LyricsGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.075f).coerceAtLeast(1.5f)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.22f, h * 0.12f)
            lineTo(w * 0.64f, h * 0.12f)
            lineTo(w * 0.80f, h * 0.28f)
            lineTo(w * 0.80f, h * 0.88f)
            lineTo(w * 0.22f, h * 0.88f)
            close()
            moveTo(w * 0.64f, h * 0.12f)
            lineTo(w * 0.64f, h * 0.28f)
            lineTo(w * 0.80f, h * 0.28f)
        }
        drawPath(path, tint, style = stroke)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.48f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.48f), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.64f), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
