package com.scottibyte.multiview

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val defaultServerUrl = "https://multiview-server.scottibyte.com"
    private val prefsName = "scottibyte_multiview_tv"

    private lateinit var serverUrlEdit: EditText
    private lateinit var setupPanel: LinearLayout
    private lateinit var cameraPanel: LinearLayout
    private lateinit var cameraList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var pairingCodeText: TextView
    private lateinit var pairingHelpText: TextView
    private lateinit var pairButton: Button
    private lateinit var refreshButton: Button
    private lateinit var resetButton: Button
    private lateinit var helpButton: Button
    private lateinit var multiViewButton: Button
    private lateinit var playerPanel: View
    private lateinit var playerView: PlayerView
    private lateinit var closePlayerButton: Button
    private lateinit var rotationPauseOverlay: TextView
    private lateinit var multiPlayerPanel: View
    private lateinit var multiPlayerViews: List<PlayerView>

    private val handler = Handler(Looper.getMainLooper())
    private var pollingCode: String? = null
    private var player: ExoPlayer? = null
    private val multiPlayers = mutableListOf<ExoPlayer>()
    private val selectedCameraIds = linkedSetOf<String>()
    private var currentCameras: List<Camera> = emptyList()
    private var currentCameraIndex: Int = -1
    private var focusedCameraId: String? = null
    private var reorderMode = false
    private var reorderCameraId: String? = null
    private var reorderOriginalOrder: List<Camera> = emptyList()
    private val cameraTileViews = mutableMapOf<String, View>()
    private val cameraThumbnailViews = mutableMapOf<String, ImageView>()
    private var thumbnailRefreshRunnable: Runnable? = null
    private val thumbnailRefreshIntervalMs = 60000L
    private val matrixColumns = 4
    private var rotationActive = false
    private var rotationPaused = false
    private var rotationCameras: List<Camera> = emptyList()
    private var rotationRunnable: Runnable? = null
    private fun rotationIntervalMs(): Long {
        return prefs().getLong("rotationIntervalMs", 10000L)
    }

    private var rotationGeneration = 0
    private var selectPauseHandled = false
    private var selectLongPressHandled = false

    data class Camera(
        val id: String,
        val name: String,
        val group: String,
        val hlsUrl: String,
        val thumbnailUrl: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        setContentView(R.layout.activity_main)

        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        setupPanel = findViewById(R.id.setupPanel)
        cameraPanel = findViewById(R.id.cameraPanel)
        cameraList = findViewById(R.id.cameraList)
        statusText = findViewById(R.id.statusText)
        pairingCodeText = findViewById(R.id.pairingCodeText)
        pairingHelpText = findViewById(R.id.pairingHelpText)
        pairButton = findViewById(R.id.pairButton)
        refreshButton = findViewById(R.id.refreshButton)
        resetButton = findViewById(R.id.resetButton)
        helpButton = findViewById(R.id.helpButton)
        multiViewButton = findViewById(R.id.multiViewButton)
        playerPanel = findViewById(R.id.playerPanel)
        playerView = findViewById(R.id.playerView)
        closePlayerButton = findViewById(R.id.closePlayerButton)
        rotationPauseOverlay = findViewById(R.id.rotationPauseOverlay)
        multiPlayerPanel = findViewById(R.id.multiPlayerPanel)
        multiPlayerViews = listOf(
            findViewById(R.id.multiPlayerView1),
            findViewById(R.id.multiPlayerView2)
        )

        val savedServer = prefs().getString("serverUrl", defaultServerUrl) ?: defaultServerUrl
        serverUrlEdit.setText(savedServer)

        pairButton.setOnClickListener { requestPairing() }
        refreshButton.setOnClickListener { clearRotationSelection() }
        resetButton.setOnClickListener { showOptionsMenu() }
        helpButton.setOnClickListener { showHelpMenu() }
        multiViewButton.setOnClickListener { openMultiView() }
        closePlayerButton.setOnClickListener { closePlayer() }

        applyTvButtonFocus(pairButton)
        updatePairButtonVisibility()
        applyTvButtonFocus(refreshButton)
        applyTvButtonFocus(resetButton)
        applyTvButtonFocus(helpButton)
        applyTvButtonFocus(multiViewButton)
        updateRotationButtonLabel()

        val token = prefs().getString("token", null)
        if (token.isNullOrBlank()) {
            showSetup("Enter the server URL and pair this TV.")
        } else {
            showCameras("Stored pairing token found. Loading cameras...")
            fetchConfig()
        }
    }

    private fun prefs() = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun normalizedServerUrl(): String {
        return serverUrlEdit.text.toString().trim().trimEnd('/').ifBlank { defaultServerUrl }
    }

    private fun showSetup(message: String) {
        setupPanel.visibility = View.VISIBLE
        cameraPanel.visibility = View.GONE
        statusText.text = message
    }

    private fun showCameras(message: String) {
        setupPanel.visibility = View.GONE
        cameraPanel.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun updatePairButtonVisibility() {
        val token = prefs().getString("token", null)
        pairButton.visibility = if (token.isNullOrBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setBusy(button: Button, busy: Boolean, text: String) {
        button.isEnabled = !busy
        button.text = text
    }

    private fun bubbleBackground(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(if (focused) 24 else 22).toFloat()
            setColor(if (focused) {
                android.graphics.Color.argb(235, 56, 189, 248)
            } else {
                android.graphics.Color.argb(120, 37, 99, 235)
            })
            setStroke(dp(if (focused) 3 else 1), if (focused) {
                android.graphics.Color.rgb(224, 242, 254)
            } else {
                android.graphics.Color.argb(150, 147, 197, 253)
            })
        }
    }

    private fun applyTvButtonFocus(button: Button) {
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.isAllCaps = false
        button.setTextColor(android.graphics.Color.WHITE)
        button.background = bubbleBackground(false)
        button.minHeight = dp(46)
        button.setPadding(dp(18), dp(8), dp(18), dp(8))

        button.setOnFocusChangeListener { view, hasFocus ->
            val b = view as Button
            if (hasFocus) {
                b.background = bubbleBackground(true)
                b.setTextColor(android.graphics.Color.rgb(2, 6, 23))
                b.scaleX = 1.07f
                b.scaleY = 1.07f
                b.elevation = dp(12).toFloat()
            } else {
                b.background = bubbleBackground(false)
                b.setTextColor(android.graphics.Color.WHITE)
                b.scaleX = 1.0f
                b.scaleY = 1.0f
                b.elevation = 0f
            }
        }
    }

    private fun requestPairing() {
        val serverUrl = normalizedServerUrl()
        prefs().edit().putString("serverUrl", serverUrl).apply()

        pairingCodeText.text = ""
        pairingHelpText.text = ""
        statusText.text = "Requesting pairing code..."
        setBusy(pairButton, true, "Requesting...")

        Thread {
            try {
                val response = httpPostJson(
                    "$serverUrl/api/tv/pairing/request",
                    JSONObject().put("clientName", android.os.Build.MODEL ?: "Android TV Client")
                )

                val displayCode = response.optString("displayCode", response.optString("pairingCode"))
                val rawCode = response.optString("pairingCode", displayCode).replace(Regex("\\D"), "")

                runOnUiThread {
                    setBusy(pairButton, false, "Pair This TV")
                    pairingCodeText.text = displayCode
                    pairingHelpText.text = "Open MultiView Server → TV Clients → authorize this code."
                    statusText.text = "Waiting for approval..."
                    pollingCode = rawCode
                    pollPairingStatus(rawCode)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(pairButton, false, "Pair This TV")
                    statusText.text = "Pairing request failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun pollPairingStatus(code: String) {
        handler.postDelayed({
            if (pollingCode != code) return@postDelayed

            val serverUrl = normalizedServerUrl()

            Thread {
                try {
                    val response = httpGetJson("$serverUrl/api/tv/pairing/status?pairingCode=$code", null)

                    if (response.optBoolean("authorized", false)) {
                        val token = response.optString("token")
                        if (token.isBlank()) {
                            throw RuntimeException("Server approved client but did not return token")
                        }

                        prefs().edit()
                            .putString("serverUrl", serverUrl)
                            .putString("token", token)
                            .apply()

                        pollingCode = null

                        runOnUiThread {
                            pairingCodeText.text = ""
                            pairingHelpText.text = ""
                            showCameras("Paired successfully. Loading cameras...")
                            fetchConfig()
                        }
                    } else {
                        runOnUiThread {
                            statusText.text = "Waiting for approval..."
                            pollPairingStatus(code)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "Still waiting or pairing not ready: ${e.message}"
                        pollPairingStatus(code)
                    }
                }
            }.start()
        }, 2500)
    }

    private fun fetchConfig() {
        val serverUrl = normalizedServerUrl()
        val token = prefs().getString("token", null)

        if (token.isNullOrBlank()) {
            showSetup("No TV client token stored. Pair this TV first.")
            return
        }

        statusText.text = "Loading camera configuration..."

        Thread {
            try {
                val response = httpGetJson("$serverUrl/api/tv/config", token)
                val cameras = parseCameras(response)

                runOnUiThread {
                    renderCameraList(cameras)
                    showCameras("Loaded ${cameras.size} cameras.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Config load failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun parseCameras(root: JSONObject): List<Camera> {
        val arr: JSONArray = root.optJSONArray("cameras")
            ?: root.optJSONObject("config")?.optJSONArray("cameras")
            ?: JSONArray()

        val result = mutableListOf<Camera>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue

            val id = item.optString("id")
            val name = item.optString("name", id)
            val group = item.optString("group", "Default")
            val streams = item.optJSONObject("streams")
            val images = item.optJSONObject("images")
            val hlsUrl = item.optString("hlsUrl",
                streams?.optString("hls") ?: item.optString("url",
                    item.optString("streamUrl", "")
                )
            )
            val thumbnailUrl = images?.optString("thumbnail") ?: ""

            if (id.isNotBlank() && hlsUrl.isNotBlank()) {
                result.add(Camera(id, name, group, hlsUrl, thumbnailUrl))
            }
        }

        return result.sortedWith(compareBy<Camera> { it.group }.thenBy { it.name })
    }

    private fun orderedCameras(cameras: List<Camera>): List<Camera> {
        val order = prefs()
            .getString("cameraOrder", "")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        if (order.isEmpty()) return cameras

        val byId = cameras.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] }
        val missing = cameras.filter { it.id !in order }

        return ordered + missing
    }

    private fun saveCameraOrder(cameras: List<Camera>) {
        prefs().edit()
            .putString("cameraOrder", cameras.joinToString(",") { it.id })
            .apply()
    }

    private fun updateRotationButtonLabel() {
        multiViewButton.text = if (selectedCameraIds.isEmpty()) {
            "Rotate All"
        } else {
            "Rotate ${selectedCameraIds.size} Selected"
        }
    }

    private fun clearRotationSelection() {
        selectedCameraIds.clear()
        updateRotationButtonLabel()
        statusText.text = "Selected cameras cleared."
        renderCameraList(currentCameras)
    }

    private fun moveFocusedCamera(delta: Int) {
        val id = focusedCameraId
        if (id.isNullOrBlank()) {
            statusText.text = "Focus a camera tile first, then open Options."
            return
        }

        val list = currentCameras.toMutableList()
        val index = list.indexOfFirst { it.id == id }

        if (index < 0) {
            statusText.text = "Focused camera was not found."
            return
        }

        val newIndex = (index + delta).coerceIn(0, list.lastIndex)
        if (newIndex == index) {
            statusText.text = "Camera is already at that edge."
            return
        }

        val camera = list.removeAt(index)
        list.add(newIndex, camera)

        currentCameras = list
        saveCameraOrder(list)
        statusText.text = "Moved ${camera.name}."
        renderCameraList(list)
        focusedCameraId = camera.id
    }

    private fun moveFocusedCameraToStartOrEnd(toStart: Boolean) {
        val id = focusedCameraId
        if (id.isNullOrBlank()) {
            statusText.text = "Focus a camera tile first, then open Options."
            return
        }

        val list = currentCameras.toMutableList()
        val index = list.indexOfFirst { it.id == id }

        if (index < 0) {
            statusText.text = "Focused camera was not found."
            return
        }

        val camera = list.removeAt(index)
        if (toStart) {
            list.add(0, camera)
        } else {
            list.add(camera)
        }

        currentCameras = list
        saveCameraOrder(list)
        statusText.text = if (toStart) {
            "Moved ${camera.name} to top."
        } else {
            "Moved ${camera.name} to bottom."
        }
        renderCameraList(list)
        focusedCameraId = camera.id
    }

    private fun resetCameraOrder() {
        prefs().edit().remove("cameraOrder").apply()
        statusText.text = "Camera order reset."
        fetchConfig()
    }

    private fun requestFocusOnCamera(cameraId: String?) {
        if (cameraId.isNullOrBlank()) return
        cameraTileViews[cameraId]?.post {
            cameraTileViews[cameraId]?.requestFocus()
        }
    }

    private fun enterReorderMode() {
        val id = focusedCameraId
        if (id.isNullOrBlank()) {
            statusText.text = "Focus a camera tile first."
            return
        }

        reorderMode = true
        reorderCameraId = id
        reorderOriginalOrder = currentCameras.toList()

        val camera = currentCameras.firstOrNull { it.id == id }
        statusText.text = if (camera != null) {
            "Reorder Mode: move ${camera.name} with arrows. Press Menu to save, Back to cancel."
        } else {
            "Reorder Mode: move tile with arrows. Press Menu to save, Back to cancel."
        }

        renderCameraList(currentCameras)
        requestFocusOnCamera(id)
    }

    private fun commitReorderMode() {
        if (!reorderMode) return

        reorderMode = false
        val id = reorderCameraId
        reorderCameraId = null
        reorderOriginalOrder = emptyList()

        saveCameraOrder(currentCameras)
        statusText.text = "Camera order saved. Focus a tile and press Menu to reorder another camera."

        renderCameraList(currentCameras)
        requestFocusOnCamera(id)
    }

    private fun cancelReorderMode() {
        if (!reorderMode) return

        val id = reorderCameraId
        val restored = reorderOriginalOrder

        reorderMode = false
        reorderCameraId = null
        reorderOriginalOrder = emptyList()

        if (restored.isNotEmpty()) {
            currentCameras = restored
            statusText.text = "Camera reorder canceled."
            renderCameraList(restored)
            requestFocusOnCamera(id)
        }
    }

    private fun moveReorderTile(delta: Int) {
        if (!reorderMode) return

        val id = reorderCameraId ?: return
        val list = currentCameras.toMutableList()
        val index = list.indexOfFirst { it.id == id }

        if (index < 0) return

        val newIndex = (index + delta).coerceIn(0, list.lastIndex)
        if (newIndex == index) return

        val camera = list.removeAt(index)
        list.add(newIndex, camera)

        currentCameras = list
        statusText.text = "Moving ${camera.name}: position ${newIndex + 1} of ${list.size}. Press Menu to save, Back to cancel."
        renderCameraList(list)
        requestFocusOnCamera(id)
    }

    private fun roundedCard(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthDp: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun tileCardBackground(camera: Camera, focused: Boolean): GradientDrawable {
        val isSelected = selectedCameraIds.contains(camera.id)
        val isReorderTile = reorderMode && reorderCameraId == camera.id

        return when {
            focused && isReorderTile -> roundedCard(
                android.graphics.Color.argb(235, 88, 28, 135),
                android.graphics.Color.rgb(250, 204, 21),
                3,
                24
            )

            focused -> roundedCard(
                android.graphics.Color.argb(225, 37, 99, 235),
                android.graphics.Color.rgb(186, 230, 253),
                3,
                24
            )

            isReorderTile -> roundedCard(
                android.graphics.Color.argb(205, 88, 28, 135),
                android.graphics.Color.rgb(250, 204, 21),
                2,
                22
            )

            isSelected -> roundedCard(
                android.graphics.Color.argb(205, 22, 101, 52),
                android.graphics.Color.rgb(134, 239, 172),
                2,
                22
            )

            else -> roundedCard(
                android.graphics.Color.argb(155, 15, 23, 42),
                android.graphics.Color.argb(130, 148, 163, 184),
                1,
                22
            )
        }
    }

    private fun applyTileStyle(tile: View, camera: Camera, focused: Boolean) {
        tile.background = tileCardBackground(camera, focused)

        if (focused) {
            tile.scaleX = 1.035f
            tile.scaleY = 1.035f
            tile.elevation = dp(12).toFloat()
            tile.alpha = 1.0f
        } else {
            tile.scaleX = 1.0f
            tile.scaleY = 1.0f
            tile.elevation = 0f
            tile.alpha = if (reorderMode && reorderCameraId != camera.id) 0.72f else 1.0f
        }
    }

    private fun messageBubbleBackground(): GradientDrawable {
        return roundedCard(
            android.graphics.Color.argb(230, 15, 23, 42),
            android.graphics.Color.argb(210, 125, 211, 252),
            2,
            28
        )
    }

    private fun renderCameraList(cameras: List<Camera>) {
        updatePairButtonVisibility()
        updateRotationButtonLabel()
        cameraList.removeAllViews()
        cameraTileViews.clear()
        cameraThumbnailViews.clear()
        val displayCameras = if (reorderMode) {
            cameras
        } else {
            orderedCameras(cameras)
        }
        currentCameras = displayCameras
        currentCameraIndex = -1

        if (displayCameras.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No cameras returned by server."
            empty.setTextColor(android.graphics.Color.rgb(203, 213, 225))
            empty.textSize = 18f
            cameraList.addView(empty)
            return
        }

        val columns = matrixColumns
        var firstTile: LinearLayout? = null

        displayCameras.chunked(columns).forEach { rowCameras ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            rowCameras.forEach { camera ->
                val tile = LinearLayout(this)
                tile.orientation = LinearLayout.VERTICAL
                tile.setPadding(12, 12, 12, 12)
                cameraTileViews[camera.id] = tile
                tile.isFocusable = true
                tile.isFocusableInTouchMode = true
                tile.isClickable = true
                applyTileStyle(tile, camera, false)
                tile.setOnClickListener {
                    if (!reorderMode) {
                        openSingleCamera(camera)
                    }
                }
                tile.setOnLongClickListener {
                    if (!reorderMode) {
                        toggleMultiViewSelection(camera, tile)
                    }
                    true
                }
                tile.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        focusedCameraId = camera.id
                    }
                    applyTileStyle(view, camera, hasFocus)
                }

                val thumb = ImageView(this)
                thumb.background = roundedCard(android.graphics.Color.rgb(2, 6, 23), android.graphics.Color.argb(90, 148, 163, 184), 1, 14)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.contentDescription = "${camera.name} thumbnail"
            cameraThumbnailViews[camera.id] = thumb

                tile.addView(thumb, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(128)
                ))

                if (camera.thumbnailUrl.isNotBlank()) {
                    loadThumbnail(thumb, camera.thumbnailUrl)
                }

                val nameText = TextView(this)
                nameText.text = camera.name
                nameText.setTextColor(android.graphics.Color.rgb(248, 250, 252))
                nameText.textSize = 18f
                nameText.setTypeface(null, Typeface.BOLD)
                nameText.maxLines = 1
                nameText.setPadding(2, 10, 2, 0)

                val detail = TextView(this)
                detail.text = camera.group + if (selectedCameraIds.contains(camera.id)) "  •  rotation selected" else ""
                detail.setTextColor(android.graphics.Color.rgb(203, 213, 225))
                detail.textSize = 13f
                detail.maxLines = 1
                detail.setPadding(2, 4, 2, 0)

                tile.addView(nameText)
                tile.addView(detail)

                val tileLp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                tileLp.setMargins(8, 8, 8, 14)
                row.addView(tile, tileLp)

                if (firstTile == null) {
                    firstTile = tile
                }
            }

            val missing = columns - rowCameras.size
            repeat(missing) {
                val spacer = View(this)
                row.addView(spacer, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ))
            }

            cameraList.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        firstTile?.post {
            firstTile?.requestFocus()
        }

        // Keep the camera matrix thumbnails fresh while the app is idling on this screen.
        scheduleThumbnailRefresh()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun cancelThumbnailRefresh() {
        thumbnailRefreshRunnable?.let { handler.removeCallbacks(it) }
        thumbnailRefreshRunnable = null
    }

    private fun refreshVisibleThumbnails() {
    if (playerPanel.visibility == View.VISIBLE) return
    if (currentCameras.isEmpty()) return

    currentCameras.forEach { camera ->
        val view = cameraThumbnailViews[camera.id]
        if (view != null && camera.thumbnailUrl.isNotBlank()) {
            view.setImageDrawable(null)
            loadThumbnail(view, camera.thumbnailUrl)
        }
    }
}

    private fun scheduleThumbnailRefresh() {
    cancelThumbnailRefresh()

    if (playerPanel.visibility == View.VISIBLE || currentCameras.isEmpty()) {
        return
    }

    val runnable = object : Runnable {
        override fun run() {
            if (thumbnailRefreshRunnable !== this) return

            refreshVisibleThumbnails()

            if (playerPanel.visibility != View.VISIBLE && currentCameras.isNotEmpty()) {
                handler.postDelayed(this, thumbnailRefreshIntervalMs)
            }
        }
    }

    thumbnailRefreshRunnable = runnable
    handler.postDelayed(runnable, thumbnailRefreshIntervalMs)
}

    private fun loadThumbnail(imageView: ImageView, imageUrl: String) {
    val separator = if (imageUrl.contains("?")) "&" else "?"
    val freshUrl = imageUrl + separator + "ts=" + System.currentTimeMillis()

    imageView.tag = freshUrl

    Thread {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(freshUrl).openConnection() as HttpURLConnection
            conn.useCaches = false
            conn.defaultUseCaches = false
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "image/jpeg,image/*")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            conn.setRequestProperty("Pragma", "no-cache")
            conn.setRequestProperty("Expires", "0")

            conn.inputStream.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                runOnUiThread {
                    if (imageView.tag == freshUrl && bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        } catch (_: Exception) {
            // Leave the dark placeholder if thumbnail loading fails.
        } finally {
            conn?.disconnect()
        }
    }.start()
}

    private fun toggleMultiViewSelection(camera: Camera, tile: View) {
        if (selectedCameraIds.contains(camera.id)) {
            selectedCameraIds.remove(camera.id)
            applyTileStyle(tile, camera, true)
            updateRotationButtonLabel()
            statusText.text = "Removed ${camera.name} from rotation."
        } else {
            selectedCameraIds.add(camera.id)
            applyTileStyle(tile, camera, true)
            updateRotationButtonLabel()
            statusText.text = "Selected ${camera.name} for rotation (${selectedCameraIds.size} selected)."
        }
    }

    private fun makePlayer(): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("ScottiBYTE-MultiView-TV")

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    private fun openMultiView() {
        closePlayer()
        closeMultiView()

        val selected = if (selectedCameraIds.isNotEmpty()) {
            val byId = currentCameras.associateBy { it.id }
            selectedCameraIds.mapNotNull { byId[it] }
        } else {
            currentCameras
        }

        if (selected.isEmpty()) {
            statusText.text = "No cameras available for rotation."
            return
        }

        rotationGeneration += 1
        rotationCameras = selected
        rotationActive = true
        rotationPaused = false
        currentCameraIndex = currentCameras.indexOfFirst { it.id == selected.first().id }

        statusText.text = "Starting camera rotation with ${selected.size} camera(s)."
        openCamera(selected.first())
        scheduleNextRotation()
    }

    private fun closeMultiView() {
        multiPlayerViews.forEach { it.player = null }
        multiPlayers.forEach { it.release() }
        multiPlayers.clear()
        multiPlayerPanel.visibility = View.GONE
    }

    private fun openSingleCamera(camera: Camera) {
        rotationActive = false
        rotationPaused = false
        rotationGeneration += 1
        rotationRunnable?.let { handler.removeCallbacks(it) }
        rotationRunnable = null
        rotationCameras = emptyList()
        openCamera(camera)
    }

    private fun openCamera(camera: Camera) {
        val foundIndex = currentCameras.indexOfFirst { it.id == camera.id }
        if (foundIndex >= 0) {
            currentCameraIndex = foundIndex
        }

        cancelThumbnailRefresh()
        statusText.text = "Playing ${camera.name}"

        playerView.player = null
        player?.release()
        player = null

        val exo = makePlayer()

        player = exo
        playerView.useController = false
        playerView.player = exo

        exo.setMediaItem(MediaItem.fromUri(Uri.parse(camera.hlsUrl)))
        exo.prepare()
        exo.playWhenReady = true

        playerPanel.visibility = View.VISIBLE
        playerView.requestFocus()
    }

    private fun playAdjacentCamera(direction: Int) {
        val list = if (rotationActive && rotationCameras.isNotEmpty()) {
            rotationCameras
        } else {
            currentCameras
        }

        if (list.isEmpty()) return

        val currentId = currentCameras.getOrNull(currentCameraIndex)?.id
        val baseIndex = list.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val nextIndex = (baseIndex + direction + list.size) % list.size
        val nextCamera = list[nextIndex]

        currentCameraIndex = currentCameras.indexOfFirst { it.id == nextCamera.id }
        openCamera(nextCamera)

        if (rotationActive && !rotationPaused) {
            scheduleNextRotation()
        }
    }

    private fun toggleRotationPause() {
        if (!rotationActive || rotationCameras.size <= 1) {
            showRotationOverlay("Rotation is not active")
            return
        }

        rotationPaused = !rotationPaused
        rotationGeneration += 1

        rotationRunnable?.let { handler.removeCallbacks(it) }
        rotationRunnable = null

        if (rotationPaused) {
            statusText.text = "Rotation paused"
            showRotationOverlay("Rotation Paused")
        } else {
            statusText.text = "Rotation resumed"
            showRotationOverlay("Rotation Resumed")
            scheduleNextRotation()
        }
    }

    private fun showRotationOverlay(message: String) {
        rotationPauseOverlay.animate().cancel()
        rotationPauseOverlay.alpha = 0.0f
        rotationPauseOverlay.text = message
        rotationPauseOverlay.background = messageBubbleBackground()
        rotationPauseOverlay.elevation = dp(18).toFloat()
        rotationPauseOverlay.visibility = View.VISIBLE

        rotationPauseOverlay.animate()
            .alpha(1.0f)
            .setDuration(180L)
            .start()

        handler.postDelayed({
            rotationPauseOverlay.animate()
                .alpha(0.0f)
                .setDuration(450L)
                .withEndAction {
                    rotationPauseOverlay.visibility = View.GONE
                    rotationPauseOverlay.alpha = 1.0f
                }
                .start()
        }, 1250L)
    }

    private fun scheduleNextRotation() {
        rotationRunnable?.let { handler.removeCallbacks(it) }
        rotationRunnable = null

        if (!rotationActive || rotationPaused || rotationCameras.size <= 1) {
            return
        }

        val scheduledGeneration = rotationGeneration

        val runnable = Runnable {
            if (
                rotationActive &&
                !rotationPaused &&
                scheduledGeneration == rotationGeneration &&
                playerPanel.visibility == View.VISIBLE
            ) {
                playAdjacentCamera(1)
            }
        }

        rotationRunnable = runnable
        handler.postDelayed(runnable, rotationIntervalMs())
    }

    private fun closePlayer() {
        rotationActive = false
        rotationPaused = false
        rotationGeneration += 1

        rotationRunnable?.let { handler.removeCallbacks(it) }
        rotationRunnable = null
        selectPauseHandled = false

        rotationPauseOverlay.animate().cancel()
        rotationPauseOverlay.visibility = View.GONE
        rotationPauseOverlay.alpha = 1.0f

        playerView.player = null
        player?.release()
        player = null
        playerPanel.visibility = View.GONE

        refreshVisibleThumbnails()
        scheduleThumbnailRefresh()
    }

    private fun showRotationSpeedMenu() {
        val labels = arrayOf(
            "5 seconds",
            "10 seconds",
            "15 seconds",
            "30 seconds"
        )

        val values = longArrayOf(
            5000L,
            10000L,
            15000L,
            30000L
        )

        val current = rotationIntervalMs()
        val checked = values.indexOf(current).takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(this)
            .setTitle("Rotation Speed")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs().edit()
                    .putLong("rotationIntervalMs", values[which])
                    .apply()

                statusText.text = "Rotation speed set to ${labels[which]}."
                dialog.dismiss()

                if (rotationActive && !rotationPaused) {
                    scheduleNextRotation()
                }
            }
            .show()
    }

    private fun helpHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(56, 189, 248))
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun helpBody(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.rgb(248, 250, 252))
            setLineSpacing(0f, 1.10f)
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun helpPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(18))
            background = roundedCard(
                Color.rgb(15, 23, 42),
                Color.argb(185, 125, 211, 252),
                1,
                24
            )
        }
    }

    private fun showHelpMenu() {
        val choices = arrayOf(
            "Controls",
            "About ScottiBYTE MultiView",
            "Donate"
        )

        AlertDialog.Builder(this)
            .setTitle("Help")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> showControlsHelp()
                    1 -> showAboutDialog()
                    2 -> showDonateDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showControlsHelp() {
        val panel = helpPanel()

        panel.addView(helpHeader("Camera Matrix"))
        panel.addView(helpBody("D-pad moves focus. Select opens a camera. Long-press Select adds or removes a camera from rotation."))

        panel.addView(helpHeader("Rotation"))
        panel.addView(helpBody("Start Rotation uses selected cameras in the order selected. If none are selected, all cameras rotate in matrix order."))

        panel.addView(helpHeader("Live View"))
        panel.addView(helpBody("Left / Right changes cameras. Long-press Select or Play/Pause pauses and resumes rotation. Back returns to the matrix."))

        panel.addView(helpHeader("Reorder"))
        panel.addView(helpBody("Press Menu on a camera tile, move it with arrows, then press Menu again to save. Back cancels the move."))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Controls")
            .setView(panel)
            .setPositiveButton("OK", null)
            .create()

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.78).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun currentServerUrlForAbout(): String {
        val defaultUrl = "https://multiview-server.scottibyte.com"

        return try {
            val field = this::class.java.declaredFields.firstOrNull {
                it.name.contains("server", ignoreCase = true) &&
                it.name.contains("url", ignoreCase = true)
            }

            field?.isAccessible = true
            val value = field?.get(this)

            val textValue = when (value) {
                is TextView -> value.text?.toString()?.trim()
                is String -> value.trim()
                else -> null
            }

            textValue?.takeIf { it.isNotBlank() } ?: defaultUrl
        } catch (_: Exception) {
            defaultUrl
        }
    }

    private fun serverModeForAbout(serverUrl: String): String {
        val lower = serverUrl.lowercase()

        return when {
            lower.contains("multiview-server.scottibyte.com") ->
                "NPM / reverse-proxy hosted"

            lower.startsWith("http://192.168.") ||
            lower.startsWith("https://192.168.") ||
            lower.startsWith("http://10.") ||
            lower.startsWith("https://10.") ||
            lower.startsWith("http://172.16.") ||
            lower.startsWith("https://172.16.") ||
            lower.contains(".local") ->
                "Local / LAN server"

            else ->
                "Custom server"
        }
    }

    private fun showAboutDialog() {
        val panel = helpPanel()
        panel.gravity = Gravity.CENTER_HORIZONTAL

        val serverUrl = currentServerUrlForAbout()
        val serverMode = serverModeForAbout(serverUrl)

        val brandIcon = ImageView(this).apply {
            setImageResource(R.drawable.scottibyte_multiview_icon_master)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(2), 0, dp(8))
        }

        val brandTitle = TextView(this).apply {
            text = "ScottiBYTE MultiView"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(56, 189, 248))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val brandSubTitle = TextView(this).apply {
            text = "Native Android / Fire TV Camera Viewer"
            textSize = 15f
            setTextColor(Color.rgb(203, 213, 225))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }

        panel.addView(brandIcon, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        panel.addView(brandTitle)
        panel.addView(brandSubTitle)

        panel.addView(helpHeader("Application"))
        panel.addView(helpBody(
            "Version: v1.0.0\n" +
            "Platform: Android / Fire TV\n" +
            "Native camera viewer for ScottiBYTE MultiView Server."
        ))

        panel.addView(helpHeader("Current Server"))
        panel.addView(helpBody(
            "$serverUrl\n" +
            "Mode: $serverMode"
        ))

        panel.addView(helpHeader("ScottiBYTE"))
        panel.addView(helpBody(
            "YouTube: https://youtube.com/@scottibyte\n" +
            "Chat: https://chat.scottibyte.com\n" +
            "Website: https://scottibyte.com"
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("About")
            .setView(panel)
            .setPositiveButton("OK", null)
            .create()

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.72).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showDonateDialog() {
        val panel = helpPanel()
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(dp(28), dp(16), dp(28), dp(14))

        val title = TextView(this).apply {
            text = "Support ScottiBYTE"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(250, 204, 21))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        val body = TextView(this).apply {
            text = "Scan the QR code to donate."
            textSize = 15f
            setTextColor(Color.rgb(248, 250, 252))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

        val qr = ImageView(this).apply {
            setImageResource(R.drawable.paypal_qr)
            adjustViewBounds = false
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        panel.addView(title)
        panel.addView(body)
        panel.addView(qr, LinearLayout.LayoutParams(dp(300), dp(300)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Donate")
            .setView(panel)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.42).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showOptionsMenu() {
        val choices = arrayOf(
            "Refresh Cameras",
            "Rotation Speed",
            "Reset Camera Order",
            "Reset Pairing"
        )

        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> fetchConfig()
                    1 -> showRotationSpeedMenu()
                    2 -> resetCameraOrder()
                    3 -> resetPairing()
                }
            }
            .show()
    }

    private fun resetPairing() {
        pollingCode = null
        closePlayer()
        closeMultiView()
        prefs().edit().remove("token").apply()
        pairButton.visibility = View.VISIBLE
        cameraList.removeAllViews()
        showSetup("Pairing reset. Pair this TV again.")
    }

    private fun httpGetJson(url: String, bearerToken: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        }

        return readJsonResponse(conn)
    }

    private fun httpPostJson(url: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        return readJsonResponse(conn)
    }

    private fun readJsonResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(stream.reader()).use { it.readText() }

        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: $text")
        }

        return JSONObject(text)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playerPanel.visibility == View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        playAdjacentCamera(-1)
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        playAdjacentCamera(1)
                    }
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        toggleRotationPause()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount >= 6 && !selectPauseHandled) {
                            selectPauseHandled = true
                            toggleRotationPause()
                        }
                        return true
                    }

                    if (event.action == KeyEvent.ACTION_UP) {
                        val heldMs = event.eventTime - event.downTime
                        if (heldMs >= 650L && !selectPauseHandled) {
                            toggleRotationPause()
                        }
                        selectPauseHandled = false
                        return true
                    }
                }
            }
        } else {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        if (reorderMode) {
                            commitReorderMode()
                        } else {
                            enterReorderMode()
                        }
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (reorderMode && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        moveReorderTile(-1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (reorderMode && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        moveReorderTile(1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (reorderMode && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        moveReorderTile(-matrixColumns)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (reorderMode && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        moveReorderTile(matrixColumns)
                        return true
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (playerPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (event?.repeatCount == 0) {
                        playAdjacentCamera(-1)
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (event?.repeatCount == 0) {
                        playAdjacentCamera(1)
                    }
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event?.repeatCount == 0) {
                        toggleRotationPause()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val repeat = event?.repeatCount ?: 0
                    if (repeat >= 8 && !selectLongPressHandled) {
                        selectLongPressHandled = true
                        toggleRotationPause()
                    }
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (playerPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!selectLongPressHandled) {
                        selectLongPressHandled = true
                        toggleRotationPause()
                    }
                    return true
                }
            }
        }

        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (playerPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    selectLongPressHandled = false
                    return true
                }
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        if (playerPanel.visibility == View.VISIBLE) {
            closePlayer()
        } else if (multiPlayerPanel.visibility == View.VISIBLE) {
            closeMultiView()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        closePlayer()
        closeMultiView()
    }
}
