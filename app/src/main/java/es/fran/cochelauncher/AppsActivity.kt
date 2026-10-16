package es.fran.cochelauncher

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Cajón con todas las apps, con el mismo estilo que la pantalla principal. */
class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Theme.palette(this)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val apps = Apps.all(this, refresh = true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Backdrop(p.dark)
            setPadding(dp(20), dp(14), dp(20), 0)
        }
        root.addView(screenHeader(p, "Apps") { finish() })

        val grid = GridView(this).apply {
            numColumns = 6
            verticalSpacing = dp(12)
            horizontalSpacing = dp(12)
            setPadding(0, dp(14), 0, dp(20))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0)
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = apps[position]
                val box = LinearLayout(this@AppsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = cardBg(p, 18)
                    setPadding(dp(6), dp(12), dp(6), dp(10))
                    layoutParams = ViewGroup.LayoutParams(-1, dp(118))
                }
                box.addView(ImageView(this@AppsActivity).apply { setImageDrawable(app.icon(this@AppsActivity)) },
                    LinearLayout.LayoutParams(dp(52), dp(52)))
                box.addView(TextView(this@AppsActivity).apply {
                    text = app.label
                    setTextColor(p.text)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                return box
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            Apps.launch(this, apps[position])
            finish()
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
