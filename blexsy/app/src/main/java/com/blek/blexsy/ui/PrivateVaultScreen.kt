package com.blek.blexsy.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local credential & protected document entry descriptor.
 */
@Serializable
data class VaultDocumentEntry(
    val id: String,
    val fileName: String,
    @kotlinx.serialization.SerialName("album") val group: String = "Utama",
    val addedAtMs: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L
)

/**
 * AES-256-GCM encrypted local keystore & protected document container.
 * Encryption keys are derived at runtime from the user's PIN and never persisted on disk.
 */
private object PrivateVaultStorage {
    private const val PREFS_NAME = "blexsy_private_vault"
    private const val KEY_PIN_HASH = "vault_pin_hash"
    private const val KEY_KDF_SALT = "vault_kdf_salt"
    private const val KEY_WRAPPED_MASTER_DEK = "vault_wrapped_dek"
    private const val KEY_FAILED_ATTEMPTS = "vault_failed_attempts"
    private const val KEY_ITEMS_JSON = "vault_items_json"
    private const val KEY_GROUPS_JSON = "vault_albums_json"
    private const val VAULT_DIR_NAME = "private_vault"
    private const val PIN_VERIFY_SALT = "blexsy-vault-verify-v1:"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 12_000
    private val MAGIC_HEADER = byteArrayOf('B'.code.toByte(), 'X'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val MAX_FAILED_ATTEMPTS = 5

    private val secureRandom = SecureRandom()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val itemsSerializer = ListSerializer(VaultDocumentEntry.serializer())
    private val groupsSerializer = ListSerializer(String.serializer())

    // Session-scoped RAM cache for decrypted buffers; cleared when leaving the screen.
    private val ramCache = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    fun clearSessionMemory() {
        ramCache.evictAll()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun vaultDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, VAULT_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val marker = File(dir, ".nomedia")
        if (!marker.exists()) {
            runCatching { marker.createNewFile() }
        }
        return dir
    }

    fun hasPin(context: Context): Boolean =
        !prefs(context).getString(KEY_PIN_HASH, null).isNullOrBlank()

    private fun getOrCreateKdfSalt(context: Context): ByteArray {
        val p = prefs(context)
        val existing = p.getString(KEY_KDF_SALT, null)
        if (!existing.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(existing, Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size >= 16) return decoded
        }
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        p.edit().putString(KEY_KDF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
        return salt
    }

    /**
     * Derives a 256-bit AES key directly from [rawPin] using PBKDF2-HMAC-SHA256.
     * The resulting key is only held in RAM while the screen is unlocked.
     */
    fun deriveAesKey(context: Context, rawPin: String): SecretKeySpec {
        val salt = getOrCreateKdfSalt(context)
        val keyBytes = runCatching {
            val spec = PBEKeySpec(rawPin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        }.getOrElse {
            val digest = MessageDigest.getInstance("SHA-256")
            var acc = (rawPin + Base64.encodeToString(salt, Base64.NO_WRAP)).toByteArray(Charsets.UTF_8)
            repeat(1024) {
                digest.reset()
                digest.update(salt)
                acc = digest.digest(acc)
            }
            acc
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    fun setPin(context: Context, rawPin: String, existingMasterDek: SecretKeySpec? = null): SecretKeySpec {
        val salt = getOrCreateKdfSalt(context)
        val pinKek = deriveAesKey(context, rawPin)
        val masterDek = existingMasterDek ?: run {
            val randomDek = ByteArray(32)
            secureRandom.nextBytes(randomDek)
            SecretKeySpec(randomDek, "AES")
        }
        val wrappedDekBytes = encryptBytes(masterDek.encoded, pinKek)
        prefs(context).edit()
            .putString(KEY_PIN_HASH, hashPinForVerification(rawPin, salt))
            .putString(KEY_WRAPPED_MASTER_DEK, Base64.encodeToString(wrappedDekBytes, Base64.NO_WRAP))
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()
        return masterDek
    }

    /**
     * Re-wraps the 256-bit Master Data Encryption Key (DEK) with the new PIN's KEK.
     * Because individual `.vault` files are encrypted with the Master DEK, changing the PIN
     * is atomic, instant, and never risks corrupting or partially rewriting files on disk.
     */
    suspend fun changePinAndReEncrypt(
        context: Context,
        currentMasterDek: SecretKeySpec,
        newPin: String
    ): SecretKeySpec = withContext(Dispatchers.IO) {
        setPin(context, newPin, existingMasterDek = currentMasterDek)
    }

    fun getFailedAttempts(context: Context): Int =
        prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0)

    /**
     * Validates the user passcode, unwraps the Master DEK into RAM, or flushes the store on 5th failed attempt.
     */
    fun verifyPinOrFlush(context: Context, rawPin: String): Pair<SecretKeySpec?, Int> {
        val p = prefs(context)
        val savedHash = p.getString(KEY_PIN_HASH, null) ?: return null to 0
        val salt = getOrCreateKdfSalt(context)
        val matchesCurrent = hashPinForVerification(rawPin, salt) == savedHash
        val matchesLegacy = !matchesCurrent && legacyHashPin(rawPin) == savedHash

        if (matchesCurrent || matchesLegacy) {
            val pinKek = deriveAesKey(context, rawPin)
            val wrappedDekB64 = p.getString(KEY_WRAPPED_MASTER_DEK, null)
            val masterDek: SecretKeySpec = if (!wrappedDekB64.isNullOrBlank()) {
                val wrappedBytes = runCatching { Base64.decode(wrappedDekB64, Base64.NO_WRAP) }.getOrNull()
                val unwrapped = wrappedBytes?.let { runCatching { decryptBytes(it, pinKek) }.getOrNull() }
                if (unwrapped != null && unwrapped.size == 32) {
                    SecretKeySpec(unwrapped, "AES")
                } else {
                    pinKek
                }
            } else {
                // Smooth migration: use existing PIN-derived key as the Master DEK and wrap it now
                val wrapped = encryptBytes(pinKek.encoded, pinKek)
                p.edit().putString(KEY_WRAPPED_MASTER_DEK, Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
                pinKek
            }

            val editor = p.edit().putInt(KEY_FAILED_ATTEMPTS, 0)
            if (matchesLegacy) {
                editor.putString(KEY_PIN_HASH, hashPinForVerification(rawPin, salt))
            }
            editor.apply()
            return masterDek to 0
        }
        val nextFailed = (p.getInt(KEY_FAILED_ATTEMPTS, 0) + 1)
        if (nextFailed >= MAX_FAILED_ATTEMPTS) {
            purgeVaultStore(context)
            return null to MAX_FAILED_ATTEMPTS
        } else {
            p.edit().putInt(KEY_FAILED_ATTEMPTS, nextFailed).apply()
            return null to nextFailed
        }
    }

    /**
     * Purges all cached document records and resets the local passcode state.
     */
    fun purgeVaultStore(context: Context) {
        clearSessionMemory()
        runCatching {
            val dir = File(context.applicationContext.filesDir, VAULT_DIR_NAME)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }
                dir.deleteRecursively()
            }
        }
        prefs(context).edit().clear().apply()
    }

    private fun isEncryptedPayload(bytes: ByteArray): Boolean {
        if (bytes.size <= MAGIC_HEADER.size + GCM_IV_LENGTH + 16) return false
        for (i in MAGIC_HEADER.indices) {
            if (bytes[i] != MAGIC_HEADER[i]) return false
        }
        return true
    }

    private fun encryptBytes(plainBytes: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainBytes)

        val out = ByteArray(MAGIC_HEADER.size + GCM_IV_LENGTH + cipherBytes.size)
        System.arraycopy(MAGIC_HEADER, 0, out, 0, MAGIC_HEADER.size)
        System.arraycopy(iv, 0, out, MAGIC_HEADER.size, GCM_IV_LENGTH)
        System.arraycopy(cipherBytes, 0, out, MAGIC_HEADER.size + GCM_IV_LENGTH, cipherBytes.size)
        return out
    }

    private fun decryptBytes(rawBytes: ByteArray, key: SecretKeySpec): ByteArray {
        val ivStart = MAGIC_HEADER.size
        val cipherStart = ivStart + GCM_IV_LENGTH
        val iv = rawBytes.copyOfRange(ivStart, cipherStart)
        val cipherBytes = rawBytes.copyOfRange(cipherStart, rawBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    /**
     * Reads and decrypts [file] in memory using [key].
     * Transparently upgrades any pre-encryption legacy `.vault` file to AES-256-GCM on disk.
     */
    suspend fun readAndDecryptFile(
        file: File,
        key: SecretKeySpec
    ): ByteArray? = withContext(Dispatchers.IO) {
        ramCache.get(file.name)?.let { return@withContext it }
        if (!file.exists()) return@withContext null
        runCatching {
            val raw = file.readBytes()
            val plain = if (isEncryptedPayload(raw)) {
                decryptBytes(raw, key)
            } else {
                // Auto-upgrade legacy unencrypted file on disk to AES-256-GCM
                val encrypted = encryptBytes(raw, key)
                runCatching { file.writeBytes(encrypted) }
                raw
            }
            ramCache.put(file.name, plain)
            plain
        }.getOrNull()
    }

    fun readEntries(context: Context): List<VaultDocumentEntry> {
        val raw = prefs(context).getString(KEY_ITEMS_JSON, null) ?: return emptyList()
        val dir = vaultDir(context)
        return runCatching {
            json.decodeFromString(itemsSerializer, raw)
                .filter { File(dir, it.fileName).exists() }
                .sortedByDescending { it.addedAtMs }
        }.getOrDefault(emptyList())
    }

    fun writeEntries(context: Context, items: List<VaultDocumentEntry>) {
        prefs(context).edit()
            .putString(KEY_ITEMS_JSON, json.encodeToString(itemsSerializer, items))
            .apply()
    }

    fun readGroups(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_GROUPS_JSON, null)
        val saved = if (!raw.isNullOrBlank()) {
            runCatching { json.decodeFromString(groupsSerializer, raw) }.getOrDefault(emptyList())
        } else emptyList()
        return (listOf("Utama") + saved).distinct()
    }

    fun writeGroups(context: Context, groups: List<String>) {
        val cleaned = (listOf("Utama") + groups.map { it.trim() }.filter { it.isNotBlank() }).distinct()
        prefs(context).edit()
            .putString(KEY_GROUPS_JSON, json.encodeToString(groupsSerializer, cleaned))
            .apply()
    }

    suspend fun importDocuments(
        context: Context,
        uris: List<Uri>,
        targetGroup: String,
        key: SecretKeySpec
    ): List<VaultDocumentEntry> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext readEntries(context)
        val dir = vaultDir(context)
        val current = readEntries(context).toMutableList()
        val resolver = context.contentResolver

        for (uri in uris) {
            runCatching {
                val plainBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                if (plainBytes.isEmpty()) return@runCatching

                val id = UUID.randomUUID().toString()
                val fileName = "doc_$id.vault"
                val destFile = File(dir, fileName)
                val encryptedBytes = encryptBytes(plainBytes, key)
                destFile.writeBytes(encryptedBytes)

                if (destFile.exists() && destFile.length() > 0L) {
                    ramCache.put(fileName, plainBytes)
                    current.add(
                        0,
                        VaultDocumentEntry(
                            id = id,
                            fileName = fileName,
                            group = targetGroup.ifBlank { "Utama" },
                            addedAtMs = System.currentTimeMillis(),
                            sizeBytes = plainBytes.size.toLong()
                        )
                    )
                } else {
                    destFile.delete()
                }
            }
        }
        val sorted = current.sortedByDescending { it.addedAtMs }
        writeEntries(context, sorted)
        sorted
    }

    suspend fun deleteDocuments(
        context: Context,
        idsToDelete: Set<String>
    ): List<VaultDocumentEntry> = withContext(Dispatchers.IO) {
        val dir = vaultDir(context)
        val current = readEntries(context)
        val remaining = mutableListOf<VaultDocumentEntry>()
        for (item in current) {
            if (item.id in idsToDelete) {
                ramCache.remove(item.fileName)
                runCatching { File(dir, item.fileName).delete() }
            } else {
                remaining.add(item)
            }
        }
        writeEntries(context, remaining)
        remaining
    }

    suspend fun exportDocuments(
        context: Context,
        itemsToExport: List<VaultDocumentEntry>,
        key: SecretKeySpec
    ): Int = withContext(Dispatchers.IO) {
        val dir = vaultDir(context)
        val resolver = context.contentResolver
        var exportedCount = 0

        for (item in itemsToExport) {
            val srcFile = File(dir, item.fileName)
            if (!srcFile.exists()) continue
            runCatching {
                val plainBytes = readAndDecryptFile(srcFile, key) ?: return@runCatching
                val displayName = "BLEXSY_${item.addedAtMs}_${item.id.take(6)}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Blexsy")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(plainBytes)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                }
                exportedCount++
            }
        }
        exportedCount
    }

    private fun hashPinForVerification(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val bytes = digest.digest((PIN_VERIFY_SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun legacyHashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(("blexsy-vault-salt-v1:" + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

@Composable
private fun VaultEncryptedImage(
    file: File,
    sessionKey: SecretKeySpec,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val decryptedBytes by produceState<ByteArray?>(initialValue = null, key1 = file.absolutePath, key2 = sessionKey) {
        value = PrivateVaultStorage.readAndDecryptFile(file, sessionKey)
    }
    val payload = decryptedBytes
    if (payload != null) {
        AsyncImage(
            model = payload,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.background(BlexsyColors.SurfaceContainerHigh))
    }
}

private enum class PinFlowStage {
    CREATE_NEW,
    CONFIRM_NEW,
    UNLOCK,
    CHANGE_NEW,
    CHANGE_CONFIRM
}

/**
 * Protected document & credential manager screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PrivateVaultScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptic = rememberBlexsyHaptic()

    // Secure window flag + wipe decrypted RAM cache on exit
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val hadSecure = (window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            PrivateVaultStorage.clearSessionMemory()
            if (!hadSecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    var sessionKey by remember { mutableStateOf<SecretKeySpec?>(null) }
    val isUnlocked = sessionKey != null
    var pinStage by remember {
        mutableStateOf(
            if (PrivateVaultStorage.hasPin(context)) PinFlowStage.UNLOCK else PinFlowStage.CREATE_NEW
        )
    }
    var enteredPin by remember { mutableStateOf("") }
    var pendingNewPin by remember { mutableStateOf("") }
    var pinErrorText by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(PrivateVaultStorage.getFailedAttempts(context)) }
    var showLockoutAlert by remember { mutableStateOf(false) }

    var entries by remember { mutableStateOf<List<VaultDocumentEntry>>(emptyList()) }
    var groups by remember { mutableStateOf<List<String>>(listOf("Utama")) }
    var selectedGroup by remember { mutableStateOf("Semua") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeEntryId by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var isPickingDocument by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isPickingDocument) {
                sessionKey = null
                activeEntryId = null
                selectedIds = emptySet()
                enteredPin = ""
                pendingNewPin = ""
                pinErrorText = null
                pinStage = if (PrivateVaultStorage.hasPin(context)) PinFlowStage.UNLOCK else PinFlowStage.CREATE_NEW
                PrivateVaultStorage.clearSessionMemory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun reloadVaultContent() {
        entries = PrivateVaultStorage.readEntries(context)
        groups = PrivateVaultStorage.readGroups(context)
    }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            reloadVaultContent()
        }
    }

    val targetImportGroup = if (selectedGroup == "Semua") "Utama" else selectedGroup
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        isPickingDocument = false
        val key = sessionKey ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) {
            scope.launch {
                isImporting = true
                entries = PrivateVaultStorage.importDocuments(context, uris, targetImportGroup, key)
                isImporting = false
                Toast.makeText(context, "${uris.size} dokumen dienkripsi ke Vault", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filteredEntries = remember(entries, selectedGroup) {
        if (selectedGroup == "Semua") entries else entries.filter { it.group == selectedGroup }
    }

    BackHandler {
        when {
            activeEntryId != null -> activeEntryId = null
            selectedIds.isNotEmpty() -> selectedIds = emptySet()
            pinStage == PinFlowStage.CHANGE_NEW || pinStage == PinFlowStage.CHANGE_CONFIRM -> {
                pinStage = PinFlowStage.UNLOCK
                enteredPin = ""
                pendingNewPin = ""
                pinErrorText = null
            }
            else -> onBack()
        }
    }

    fun handlePinCompleted(pin: String) {
        when (pinStage) {
            PinFlowStage.CREATE_NEW -> {
                pendingNewPin = pin
                enteredPin = ""
                pinErrorText = null
                pinStage = PinFlowStage.CONFIRM_NEW
            }
            PinFlowStage.CONFIRM_NEW -> {
                if (pin == pendingNewPin) {
                    val key = PrivateVaultStorage.setPin(context, pin)
                    enteredPin = ""
                    pendingNewPin = ""
                    pinErrorText = null
                    failedAttempts = 0
                    sessionKey = key
                } else {
                    enteredPin = ""
                    pendingNewPin = ""
                    pinErrorText = "Konfirmasi PIN tidak cocok. Silakan buat ulang PIN."
                    pinStage = PinFlowStage.CREATE_NEW
                }
            }
            PinFlowStage.UNLOCK -> {
                val (derivedKey, failCount) = PrivateVaultStorage.verifyPinOrFlush(context, pin)
                if (derivedKey != null) {
                    enteredPin = ""
                    pinErrorText = null
                    failedAttempts = 0
                    sessionKey = derivedKey
                } else {
                    enteredPin = ""
                    failedAttempts = failCount
                    if (failCount >= PrivateVaultStorage.MAX_FAILED_ATTEMPTS) {
                        entries = emptyList()
                        groups = listOf("Utama")
                        sessionKey = null
                        showLockoutAlert = true
                    } else {
                        val remaining = PrivateVaultStorage.MAX_FAILED_ATTEMPTS - failCount
                        pinErrorText = "PIN salah ($failCount/${PrivateVaultStorage.MAX_FAILED_ATTEMPTS}). Sisa $remaining percobaan sebelum semua data dihapus permanen!"
                    }
                }
            }
            PinFlowStage.CHANGE_NEW -> {
                pendingNewPin = pin
                enteredPin = ""
                pinErrorText = null
                pinStage = PinFlowStage.CHANGE_CONFIRM
            }
            PinFlowStage.CHANGE_CONFIRM -> {
                if (pin == pendingNewPin) {
                    val currentKey = sessionKey
                    enteredPin = ""
                    pendingNewPin = ""
                    pinErrorText = null
                    if (currentKey != null) {
                        scope.launch {
                            isImporting = true
                            sessionKey = PrivateVaultStorage.changePinAndReEncrypt(context, currentKey, pin)
                            isImporting = false
                            pinStage = PinFlowStage.UNLOCK
                            Toast.makeText(context, "PIN & kunci enkripsi AES-256 berhasil diperbarui", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        sessionKey = PrivateVaultStorage.setPin(context, pin)
                        pinStage = PinFlowStage.UNLOCK
                    }
                } else {
                    enteredPin = ""
                    pendingNewPin = ""
                    pinErrorText = "Konfirmasi PIN baru tidak cocok. Ulangi PIN baru."
                    pinStage = PinFlowStage.CHANGE_NEW
                }
            }
        }
    }

    if (showLockoutAlert) {
        AlertDialog(
            onDismissRequest = {
                showLockoutAlert = false
                onBack()
            },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            title = {
                Text(
                    text = "Data Terhapus Permanen",
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Anda telah salah memasukkan PIN sebanyak 5 kali. Demi keamanan privasi, seluruh dokumen dan data di dalam Vault telah dihapus secara permanen.",
                    style = BlexsyTypography.Body.copy(color = BlexsyColors.TextSecondary)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLockoutAlert = false
                        onBack()
                    }
                ) {
                    Text("Tutup", color = BlexsyColors.Primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    val activeKey = sessionKey
    val isShowingPinPad = activeKey == null || pinStage == PinFlowStage.CHANGE_NEW || pinStage == PinFlowStage.CHANGE_CONFIRM

    if (isShowingPinPad) {
        VaultPinPadScreen(
            stage = pinStage,
            enteredPin = enteredPin,
            errorText = pinErrorText,
            failedAttempts = failedAttempts,
            onDigit = { digit ->
                if (enteredPin.length < 4) {
                    haptic.click()
                    val next = enteredPin + digit
                    enteredPin = next
                    if (next.length == 4) {
                        handlePinCompleted(next)
                    }
                }
            },
            onBackspace = {
                if (enteredPin.isNotEmpty()) {
                    haptic.tick()
                    enteredPin = enteredPin.dropLast(1)
                }
            },
            onBack = {
                if (pinStage == PinFlowStage.CHANGE_NEW || pinStage == PinFlowStage.CHANGE_CONFIRM) {
                    pinStage = PinFlowStage.UNLOCK
                    enteredPin = ""
                    pendingNewPin = ""
                    pinErrorText = null
                } else {
                    onBack()
                }
            }
        )
        return
    }

    val isSelectionMode = selectedIds.isNotEmpty()
    val vaultDirectory = remember(context) { PrivateVaultStorage.vaultDir(context) }

    Scaffold(
        containerColor = BlexsyColors.Background,
        topBar = {
            DetailAppBar(
                title = if (isSelectionMode) "${selectedIds.size} dipilih" else "PRIVATE VAULT",
                onBack = {
                    if (isSelectionMode) {
                        selectedIds = emptySet()
                    } else {
                        onBack()
                    }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelectionMode) {
                            TextButton(
                                onClick = {
                                    val itemsToExport = entries.filter { it.id in selectedIds }
                                    scope.launch {
                                        val count = PrivateVaultStorage.exportDocuments(context, itemsToExport, activeKey)
                                        selectedIds = emptySet()
                                        Toast.makeText(
                                            context,
                                            "$count dokumen diekspor",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text("Export", color = BlexsyColors.Primary, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = IcTrashOutline,
                                    contentDescription = "Hapus",
                                    tint = BlexsyColors.Danger,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    enteredPin = ""
                                    pendingNewPin = ""
                                    pinErrorText = null
                                    pinStage = PinFlowStage.CHANGE_NEW
                                }
                            ) {
                                Text(
                                    text = "Ganti PIN",
                                    color = BlexsyColors.TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(
                                onClick = {
                                    isPickingDocument = true
                                    documentPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = IcPlus,
                                    contentDescription = "Tambah Dokumen",
                                    tint = BlexsyColors.Primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = {
                        isPickingDocument = true
                        documentPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    containerColor = BlexsyColors.Primary,
                    contentColor = Color.Black,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = IcPlus,
                        contentDescription = "Tambah Dokumen",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val allGroupTabs = remember(groups) { listOf("Semua") + groups }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(allGroupTabs) { groupName ->
                    val isSelected = selectedGroup == groupName
                    val count = if (groupName == "Semua") entries.size else entries.count { it.group == groupName }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isSelected) BlexsyColors.Primary else BlexsyColors.SurfaceContainerHigh
                            )
                            .clickable { selectedGroup = groupName }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "$groupName ($count)",
                            style = BlexsyTypography.Caption.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.Black else BlexsyColors.TextPrimary
                            )
                        )
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, BlexsyColors.Border, CircleShape)
                            .clickable {
                                newGroupName = ""
                                showCreateGroupDialog = true
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "+ Kategori",
                            style = BlexsyTypography.Caption.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BlexsyColors.Primary
                            )
                        )
                    }
                }
            }

            if (isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = BlexsyColors.Primary,
                    trackColor = BlexsyColors.SurfaceContainerHigh
                )
            }

            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(BlexsyColors.SurfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = IcPlus,
                                contentDescription = null,
                                tint = BlexsyColors.Primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Vault Masih Kosong",
                            style = BlexsyTypography.SectionTitle
                        )
                        Text(
                            text = "Dokumen dan arsip yang disimpan di sini dienkripsi dengan AES-256 berbasis PIN dan tidak dapat dibuka di luar aplikasi.",
                            style = BlexsyTypography.BodyMuted.copy(fontSize = 13.sp),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 320.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        BlexsyButton(
                            text = "Tambah Dokumen",
                            leadingIcon = IcPlus,
                            onClick = {
                                isPickingDocument = true
                                documentPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            variant = ButtonVariant.Primary,
                            size = ButtonSize.Md
                        )
                    }
                }
            } else {
                val layout = rememberBlexsyLayoutSpec()
                val columns = if (layout.isExpanded) 5 else if (layout.isTablet) 4 else 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.5.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(filteredEntries, key = { _, item -> item.id }) { _, entry ->
                        val file = remember(entry.fileName) { File(vaultDirectory, entry.fileName) }
                        val isSelected = entry.id in selectedIds
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(BlexsyColors.SurfaceContainerHigh)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedIds = if (isSelected) selectedIds - entry.id else selectedIds + entry.id
                                        } else {
                                            activeEntryId = entry.id
                                        }
                                    },
                                    onLongClick = {
                                        haptic.click()
                                        selectedIds = if (isSelected) selectedIds - entry.id else selectedIds + entry.id
                                    }
                                )
                        ) {
                            VaultEncryptedImage(
                                file = file,
                                sessionKey = activeKey,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isSelectionMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (isSelected) Color.Black.copy(alpha = 0.38f)
                                            else Color.Transparent
                                        )
                                )
                                Icon(
                                    imageVector = if (isSelected) IcCheckCircleFilled else IcPlusCircleOutline,
                                    contentDescription = null,
                                    tint = if (isSelected) BlexsyColors.Primary else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            title = {
                Text(
                    text = "Kategori Baru",
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold),
                    color = BlexsyColors.TextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    placeholder = { Text("Nama kategori") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlexsyColors.Primary,
                        cursorColor = BlexsyColors.Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newGroupName.trim()
                        if (trimmed.isNotBlank() && !trimmed.equals("Semua", ignoreCase = true)) {
                            val updated = (groups + trimmed).distinct()
                            PrivateVaultStorage.writeGroups(context, updated)
                            groups = updated
                            selectedGroup = trimmed
                        }
                        showCreateGroupDialog = false
                    }
                ) {
                    Text("Buat", color = BlexsyColors.Primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text("Batal", color = BlexsyColors.TextSecondary)
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            title = {
                Text(
                    text = "Hapus ${selectedIds.size} Item?",
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold),
                    color = BlexsyColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = "Dokumen yang dihapus dari Private Vault akan dihapus permanen dan tidak bisa dikembalikan.",
                    style = BlexsyTypography.Body.copy(color = BlexsyColors.TextSecondary)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toRemove = selectedIds
                        showDeleteConfirmDialog = false
                        selectedIds = emptySet()
                        scope.launch {
                            entries = PrivateVaultStorage.deleteDocuments(context, toRemove)
                        }
                    }
                ) {
                    Text("Hapus Permanen", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Batal", color = BlexsyColors.TextSecondary)
                }
            }
        )
    }

    val currentPreviewId = activeEntryId
    if (currentPreviewId != null && filteredEntries.isNotEmpty()) {
        val initialIdx = filteredEntries.indexOfFirst { it.id == currentPreviewId }.coerceAtLeast(0)
        VaultDocumentPreviewDialog(
            entries = filteredEntries,
            initialIndex = initialIdx,
            vaultDirectory = vaultDirectory,
            sessionKey = activeKey,
            onDismiss = { activeEntryId = null },
            onExport = { item ->
                scope.launch {
                    val count = PrivateVaultStorage.exportDocuments(context, listOf(item), activeKey)
                    if (count > 0) {
                        Toast.makeText(context, "Dokumen berhasil diekspor", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDelete = { item ->
                scope.launch {
                    val updated = PrivateVaultStorage.deleteDocuments(context, setOf(item.id))
                    entries = updated
                    if (updated.none { selectedGroup == "Semua" || it.group == selectedGroup }) {
                        activeEntryId = null
                    }
                }
            }
        )
    }
}

@Composable
private fun VaultPinPadScreen(
    stage: PinFlowStage,
    enteredPin: String,
    errorText: String?,
    failedAttempts: Int,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit
) {
    val title = when (stage) {
        PinFlowStage.CREATE_NEW -> "Buat PIN Vault"
        PinFlowStage.CONFIRM_NEW -> "Konfirmasi PIN"
        PinFlowStage.UNLOCK -> "Private Vault"
        PinFlowStage.CHANGE_NEW -> "Buat PIN Baru"
        PinFlowStage.CHANGE_CONFIRM -> "Konfirmasi PIN Baru"
    }
    val subtitle = when (stage) {
        PinFlowStage.CREATE_NEW -> "Masukkan 4 digit PIN untuk mengamankan brankas dokumen"
        PinFlowStage.CONFIRM_NEW -> "Masukkan ulang 4 digit PIN yang baru kamu buat"
        PinFlowStage.UNLOCK -> "Masukkan 4 digit PIN. Salah 5 kali akan menghapus seluruh isi brankas."
        PinFlowStage.CHANGE_NEW -> "Masukkan 4 digit PIN baru"
        PinFlowStage.CHANGE_CONFIRM -> "Masukkan ulang 4 digit PIN baru"
    }

    Scaffold(
        containerColor = BlexsyColors.Background,
        topBar = {
            DetailAppBar(
                title = "",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                Text(
                    text = title,
                    style = BlexsyTypography.Display.copy(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = BlexsyColors.TextPrimary
                    ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = BlexsyTypography.BodyMuted.copy(fontSize = 13.sp),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val filled = index < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) BlexsyColors.Primary else Color.Transparent
                                )
                                .border(
                                    width = 2.dp,
                                    color = if (filled) BlexsyColors.Primary else BlexsyColors.TextMuted,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = BlexsyTypography.Caption.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                } else if (stage == PinFlowStage.UNLOCK && failedAttempts > 0) {
                    val remaining = PrivateVaultStorage.MAX_FAILED_ATTEMPTS - failedAttempts
                    Text(
                        text = "Sisa percobaan: $remaining kali",
                        style = BlexsyTypography.Caption.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val digits = remember(stage, failedAttempts) {
                    val base = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                    if (stage == PinFlowStage.UNLOCK) base.shuffled() else base
                }
                val rows = listOf(
                    digits.subList(0, 3),
                    digits.subList(3, 6),
                    digits.subList(6, 9),
                    listOf("", digits[9], "⌫")
                )
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (key in row) {
                            if (key.isEmpty()) {
                                Spacer(modifier = Modifier.size(74.dp))
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(74.dp)
                                        .clip(CircleShape)
                                        .background(BlexsyColors.SurfaceContainerHigh)
                                        .clickable {
                                            if (key == "⌫") onBackspace() else onDigit(key)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key,
                                        style = BlexsyTypography.Display.copy(
                                            fontFamily = MonoFont,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BlexsyColors.TextPrimary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultDocumentPreviewDialog(
    entries: List<VaultDocumentEntry>,
    initialIndex: Int,
    vaultDirectory: File,
    sessionKey: SecretKeySpec,
    onDismiss: () -> Unit,
    onExport: (VaultDocumentEntry) -> Unit,
    onDelete: (VaultDocumentEntry) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
        pageCount = { entries.size }
    )
    var showOverlayControls by remember { mutableStateOf(true) }
    val currentEntry = entries.getOrNull(pagerState.currentPage.coerceIn(0, (entries.size - 1).coerceAtLeast(0)))
        ?: return

    val dateFormatter = remember {
        SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { idx -> entries[idx].id }
            ) { page ->
                val item = entries[page]
                val file = remember(item.fileName) { File(vaultDirectory, item.fileName) }
                var scale by remember(item.id) { mutableFloatStateOf(1f) }
                var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

                LaunchedEffect(pagerState.settledPage) {
                    if (pagerState.settledPage != page) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(item.id) {
                            detectTapGestures(
                                onTap = { showOverlayControls = !showOverlayControls },
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .pointerInput(item.id) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2 || scale > 1.02f) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                        scale = newScale
                                        if (newScale > 1.02f) {
                                            val maxX = (size.width * (newScale - 1f)) / 2f
                                            val maxY = (size.height * (newScale - 1f)) / 2f
                                            offset = Offset(
                                                x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                                y = (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                            )
                                            event.changes.forEach { change ->
                                                if (change.position != change.previousPosition) {
                                                    change.consume()
                                                }
                                            }
                                        } else {
                                            offset = Offset.Zero
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    VaultEncryptedImage(
                        file = file,
                        sessionKey = sessionKey,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                }
            }

            AnimatedVisibility(
                visible = showOverlayControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = IcArrowLeft,
                            contentDescription = "Tutup",
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${entries.size} • ${currentEntry.group}",
                            style = BlexsyTypography.Title.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = dateFormatter.format(Date(currentEntry.addedAtMs)),
                            style = BlexsyTypography.Caption.copy(
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        )
                    }
                    TextButton(onClick = { onExport(currentEntry) }) {
                        Text("Export", color = BlexsyColors.Primary, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { onDelete(currentEntry) }) {
                        Icon(
                            imageVector = IcTrashOutline,
                            contentDescription = "Hapus",
                            tint = BlexsyColors.Danger
                        )
                    }
                }
            }
        }
    }
}
