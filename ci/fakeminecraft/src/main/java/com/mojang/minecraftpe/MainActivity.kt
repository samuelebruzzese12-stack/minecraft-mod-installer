package com.mojang.minecraftpe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.TextView
import java.io.File

/**
 * Finto Minecraft per la prova in CI: legge il file che l'app gli consegna (Intent ACTION_VIEW con URI di contenuto),
 * lo salva in files/received/ e lo scrive nel log con il tag FAKE_MC. Non importa nulla.
 */
class MainActivity : Activity() {

    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this)
        label.textSize = 18f
        label.setPadding(48, 120, 48, 48)
        label.text = "FINTO MINECRAFT (solo prova)\nIn attesa di un file…"
        setContentView(label)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        try {
            var name = uri.lastPathSegment ?: "ricevuto.bin"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
            val dir = File(filesDir, "received")
            dir.mkdirs()
            val out = File(dir, name)
            var bytes = 0L
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("nessun flusso per $uri")
            input.use { src -> out.outputStream().use { dst -> bytes = src.copyTo(dst) } }
            Log.i(TAG, "RECEIVED name=$name bytes=$bytes type=${intent.type} scheme=${uri.scheme}")
            label.text = "FINTO MINECRAFT (solo prova)\nRicevuto: $name\n$bytes byte"
        } catch (e: Throwable) {
            Log.e(TAG, "ERRORE ${e.javaClass.simpleName}: ${e.message}")
            label.text = "FINTO MINECRAFT (solo prova)\nERRORE: ${e.message}"
        }
    }

    private companion object {
        const val TAG = "FAKE_MC"
    }
}
