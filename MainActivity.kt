package com.example.mediabridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            status.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val host = EditText(this).apply {
            hint = "PCのIP:ポート 例 192.168.0.10:8765"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(p.getString("host", ""))
        }
        val token = EditText(this).apply {
            hint = "トークン(PC側 --token と同じ。無しなら空)"
            setText(p.getString("token", ""))
        }
        val save = Button(this).apply {
            text = "保存して接続"
            setOnClickListener {
                p.edit().putString("host", host.text.toString().trim())
                    .putString("token", token.text.toString().trim()).apply()
            }
        }
        val perm = Button(this).apply {
            text = "① 通知へのアクセスを許可(設定を開く)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        val battery = Button(this).apply {
            text = "② 電池の最適化を除外(設定を開く)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        status = TextView(this).apply { setPadding(0, pad, 0, 0) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            listOf(perm, battery, host, token, save, status).forEach { addView(it) }
        })
    }

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        status.removeCallbacks(tick)
    }

    private fun refresh() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val granted = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        status.text = "通知アクセス: ${if (granted) "許可済み" else "未許可"}\n" +
            "状態: ${p.getString("status", "-")}"
    }
}
