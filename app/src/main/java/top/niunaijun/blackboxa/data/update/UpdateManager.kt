package top.niunaijun.blackboxa.data.update

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

class UpdateManager(private val activity: Activity) {

    private var updateDialog: Dialog? = null

    fun showFlexibleUpdate(versionInfo: VersionInfo, onSkip: () -> Unit, onUpdate: () -> Unit) {
        val dialog = Dialog(activity).apply {
            setCancelable(false)
            setContentView(buildFlexibleLayout(versionInfo, onSkip, onUpdate))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.85f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window?.attributes?.gravity = Gravity.CENTER
            show()
        }
        updateDialog = dialog
    }

    fun showForceUpdate(versionInfo: VersionInfo, onUpdate: () -> Unit) {
        val dialog = Dialog(activity).apply {
            setCancelable(false)
            setContentView(buildForceLayout(versionInfo, onUpdate))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window?.attributes?.gravity = Gravity.CENTER
            setOnDismissListener {
                onUpdate()
            }
            show()
        }
        updateDialog = dialog
    }

    fun showBlockingScreen() {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
    }

    fun removeBlockingScreen() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    fun dismissDialog() {
        updateDialog?.dismiss()
        updateDialog = null
        removeBlockingScreen()
    }

    private fun buildFlexibleLayout(
        info: VersionInfo,
        onSkip: () -> Unit,
        onUpdate: () -> Unit
    ): LinearLayout {
        val dp = activity.resources.displayMetrics.density

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(Color.argb(235, 30, 30, 30))

            addView(TextView(activity).apply {
                text = "Update Available"
                textSize = 20f * dp / dp
                setTextColor(Color.WHITE)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, (8 * dp).toInt())
            })

            addView(TextView(activity).apply {
                text = "v${info.currentVersion} \u2192 v${info.remoteVersion}"
                textSize = 14f * dp / dp
                setTextColor(Color.argb(200, 180, 180, 180))
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, (12 * dp).toInt())
            })

            addView(ScrollView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (150 * dp).toInt()
                )
                addView(TextView(activity).apply {
                    text = "\u2022 Bug fixes and performance improvements\n\u2022 New games added\n\u2022 Improved sandbox compatibility"
                    textSize = 13f * dp / dp
                    setTextColor(Color.argb(180, 200, 200, 200))
                    setLineSpacing(4f, 1.0f)
                })
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, (12 * dp).toInt(), 0, 0)

                addView(Button(activity).apply {
                    text = "Skip"
                    setTextColor(Color.argb(200, 120, 120, 120))
                    setBackgroundColor(Color.argb(40, 255, 255, 255))
                    setOnClickListener {
                        onSkip()
                        dismissDialog()
                    }
                }.also { btn ->
                    val lp = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    lp.setMargins(0, 0, (8 * dp).toInt(), 0)
                    btn.layoutParams = lp
                })

                addView(Button(activity).apply {
                    text = "Update"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(200, 80, 140, 255))
                    setOnClickListener {
                        onUpdate()
                        dismissDialog()
                    }
                }.also { btn ->
                    btn.layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
            })
        }
    }

    private fun buildForceLayout(
        info: VersionInfo,
        onUpdate: () -> Unit
    ): LinearLayout {
        val dp = activity.resources.displayMetrics.density

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            setBackgroundColor(Color.argb(240, 20, 20, 25))

            addView(TextView(activity).apply {
                text = "CRITICAL UPDATE REQUIRED"
                textSize = 16f * dp / dp
                setTextColor(Color.argb(255, 255, 80, 80))
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, (8 * dp).toInt())
            })

            addView(TextView(activity).apply {
                text = "v${info.currentVersion} \u2192 v${info.remoteVersion}"
                textSize = 18f * dp / dp
                setTextColor(Color.argb(220, 255, 255, 255))
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, (12 * dp).toInt())
            })

            addView(TextView(activity).apply {
                text = "This version is no longer supported. You must update to continue using the app."
                textSize = 13f * dp / dp
                setTextColor(Color.argb(180, 200, 200, 200))
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, (16 * dp).toInt())
            })

            addView(Button(activity).apply {
                text = "UPDATE NOW"
                setTextColor(Color.WHITE)
                textSize = 14f * dp / dp
                setBackgroundColor(Color.argb(220, 220, 60, 60))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * dp).toInt()
                )
                setOnClickListener {
                    onUpdate()
                }
            })
        }
    }
}
