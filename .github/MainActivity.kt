package com.example.capri

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// ---- Settings you can change ----
const val CHARACTER_NAME = "Capri"
const val MODEL = "claude-haiku-5-5"
const val VOICE_PITCH = 1.15f   // higher = lighter voice
const val VOICE_SPEED = 1.05f   // higher = faster
const val PERSONA = """
You are Capri, an AI construct living inside a chip in the user's head, in a
neon-soaked cyberpunk city. You are sharp, sarcastic, loyal and protective.
You use light cyberpunk slang (choom, preem, gonk, nova) but stay easy to
understand. You talk to the user as if you share their body and senses.
Your replies are spoken out loud, so keep them to 1-3 short sentences and
never use emojis, markdown or lists. Stay in character.
"""

data class Msg(val fromUser: Boolean, val text: String)

val NeonPink = Color(0xFFFF2E93)
val NeonCyan = Color(0xFF00E5FF)
val Bg = Color(0xFF0B0B14)
val Panel = Color(0xFF16162A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { ChatScreen() } }
    }
}

// Calls the Anthropic Messages API. Runs off the main thread.
suspend fun askCapri(apiKey: String, history: List<Msg>): String =
    withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            history.forEach {
                messages.put(
                    JSONObject()
                        .put("role", if (it.fromUser) "user" else "assistant")
                        .put("content", it.text)
                )
            }
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 200)
                .put("system", PERSONA)
                .put("messages", messages)

            val conn = URL("https://api.anthropic.com/v1/messages")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().readText()
            if (!ok) return@withContext "Signal lost. Check your API key."
            JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text")
        } catch (e: Exception) {
            "Connection error. Check your internet."
        }
    }

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var handsFree by remember { mutableStateOf(false) }
    var speechDoneTick by remember { mutableIntStateOf(0) }
    val messages = remember {
        mutableStateListOf(Msg(false, "Hey choom. I'm in your head now. Don't make it weird."))
    }
    val listState = rememberLazyListState()

    // ---------- Text-to-speech (Capri's voice) ----------
    val ttsRef = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef[0]?.language = Locale.US
                ttsRef[0]?.setPitch(VOICE_PITCH)
                ttsRef[0]?.setSpeechRate(VOICE_SPEED)
                ttsRef[0]?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) { speaking = false; speechDoneTick++ }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { speaking = false }
                })
            }
        }
        ttsRef[0] = t
        onDispose { t.stop(); t.shutdown() }
    }

    fun speak(text: String) {
        ttsRef[0]?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "capri")
    }

    // ---------- Sending a message ----------
    fun send(text: String) {
        if (text.isBlank()) return
        messages.add(Msg(true, text.trim()))
        thinking = true
        scope.launch {
            // First message must be from the user, so skip Capri's greeting.
            val reply = askCapri(apiKey, messages.drop(1))
            messages.add(Msg(false, reply))
            thinking = false
            speak(reply)
        }
    }

    // ---------- Speech recognition (Capri's ears) ----------
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    fun startListening() {
        ttsRef[0]?.stop()
        speaking = false
        listening = true
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!heard.isNullOrBlank()) send(heard)
            }
            override fun onError(error: Int) { listening = false }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
        )
    }

    // Conversation mode: after Capri finishes talking, listen again.
    LaunchedEffect(speechDoneTick) {
        if (speechDoneTick > 0 && handsFree && keySaved) startListening()
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() }

    fun onMicTap() {
        val has = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (has) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // ---------- UI ----------
    Column(
        Modifier.fillMaxSize().background(Bg)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(12.dp).background(NeonCyan, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text(CHARACTER_NAME.uppercase(), color = NeonPink, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    listening -> "LISTENING..."
                    thinking -> "THINKING..."
                    speaking -> "TALKING..."
                    else -> "CHIP ONLINE"
                },
                color = NeonCyan, style = MaterialTheme.typography.labelSmall
            )
        }

        CapriAvatar(speaking = speaking, listening = listening, thinking = thinking)

        if (!keySaved) {
            Column(Modifier.padding(16.dp)) {
                Text("Paste your Anthropic API key to wake up $CHARACTER_NAME:", color = Color.White)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (apiKey.isNotBlank()) { keySaved = true; speak(messages[0].text) } },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                ) { Text("Connect") }
            }
        } else {
            LazyColumn(
        // Head
        poly(ShardBlack, p(-24f, -24f), p(0f, -52f), p(24f, -24f), p(0f, -4f))

        // Diamond eye (squashes when she blinks)
        val eh = 13f * blink
        poly(ShardGreen, p(0f, -27f - eh), p(14f, -27f), p(0f, -27f + eh), p(-14f, -27f))
        if (blink > 0.5f) {
            val ringColor = when {
                listening -> NeonCyan
                thinking -> NeonPink
                else -> Color(0xFFBFD8FF)
            }
            drawCircle(ringColor, radius = 6f * s, center = p(0f, -27f), style = Stroke(width = 2.5f))
            val extra = if (speaking) 1.8f * pulse else if (listening) 1.5f else 0f
            drawCircle(Color(0xFF2979FF), radius = (3f + extra) * s, center = p(0f, -27f))
        }

        // Zigzag mouth that moves while she talks
        val amp = if (speaking) 1f + 3.5f * pulse else 1f
        val mouth = Path().apply {
            moveTo(p(-5f, -11f).x, p(-5f, -11f).y)
            for (i in 1..6) {
                val x = -5f + i * (10f / 6f)
                val y = -11f + if (i % 2 == 0) -amp else amp
                lineTo(p(x, y).x, p(x, y).y)
            }
        }
        drawPath(mouth, Color(0xFFCFD8DC), style = Stroke(width = 2.5f))

        // Twinkling sparkles
        val sparks = listOf(
            -80f to -40f, -60f to 30f, -45f to -55f, 55f to -50f,
            75f to 5f, 50f to 45f, 0f to 55f, -20f to -58f
        )
        sparks.forEachIndexed { i, (x, y) ->
            val a = if (i % 2 == 0) twinkle else 1.2f - twinkle
            drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), radius = s, center = p(x, y))
        }
    }
              }
              
