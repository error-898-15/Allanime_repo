package com.uchiharepo.animahd

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimaHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimaHDProvider())
    }

    override fun openSettings(context: Context) {
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                cornerRadius = 24f
                setStroke(3, Color.parseColor("#E63946"))
            }
            background = bg
        }

        val titleView = TextView(context).apply {
            text = "AnimaHD Provider"
            textSize = 20f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }

        val subtitleView = TextView(context).apply {
            text = "Official Multi-Audio & Hindi Anime Source"
            textSize = 13f
            setTextColor(Color.parseColor("#E63946"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        val creditCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1F1315"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#E63946"))
            }
            background = cardBg
        }

        val creditView = TextView(context).apply {
            text = "made by Jihad"
            textSize = 16f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }

        val descView = TextView(context).apply {
            text = "Fast Cloudflare Worker CDN stream playback with Google Drive fallback support."
            textSize = 12f
            setTextColor(Color.parseColor("#BBBBBB"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        creditCard.addView(creditView)
        creditCard.addView(descView)

        rootLayout.addView(titleView)
        rootLayout.addView(subtitleView)
        rootLayout.addView(creditCard)

        AlertDialog.Builder(context)
            .setView(rootLayout)
            .setPositiveButton("OK", null)
            .show()
    }
}
