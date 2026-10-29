package dev.personal.doomblocker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.createBitmap
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.R
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.platform.QuickSurfaceModels
import dev.personal.doomblocker.service.RuntimeStatus
import dev.personal.doomblocker.ui.MainActivity

class DoomWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext as BlockerApplication
        update(context, manager, appWidgetIds, app.repository.state.value.snapshot, app.serviceStatus.state.value)
    }

    companion object {
        fun updateAll(context: Context, snapshot: ProtectionSnapshot?, runtime: RuntimeStatus) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DoomWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) update(context, manager, ids, snapshot, runtime)
        }

        private fun update(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            snapshot: ProtectionSnapshot?,
            runtime: RuntimeStatus,
        ) {
            val app = context.applicationContext as BlockerApplication
            val now = app.clock.now()
            val presentation = QuickSurfaceModels.widget(snapshot, runtime, now)
            val open = PendingIntent.getActivity(
                context,
                20,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_doom_blocker)
                views.setOnClickPendingIntent(R.id.widget_root, open)
                views.setTextViewText(R.id.widget_status, presentation.status)
                val rows = listOf(
                    Triple(R.id.widget_row_1, R.id.widget_icon_1, R.id.widget_name_1 to R.id.widget_time_1),
                    Triple(R.id.widget_row_2, R.id.widget_icon_2, R.id.widget_name_2 to R.id.widget_time_2),
                    Triple(R.id.widget_row_3, R.id.widget_icon_3, R.id.widget_name_3 to R.id.widget_time_3),
                )
                rows.forEachIndexed { index, (rowId, iconId, texts) ->
                    val target = presentation.targets.getOrNull(index)
                    views.setViewVisibility(rowId, if (target == null) View.GONE else View.VISIBLE)
                    if (target != null) {
                        views.setTextViewText(texts.first, target.label)
                        views.setTextViewText(texts.second, target.status)
                        runCatching { context.packageManager.getApplicationIcon(target.packageName) }
                            .getOrNull()?.let { views.setImageViewBitmap(iconId, it.bitmap(44)) }
                    }
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun android.graphics.drawable.Drawable.bitmap(sizeDp: Int): Bitmap {
            val density = android.content.res.Resources.getSystem().displayMetrics.density
            val pixels = (sizeDp * density).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(pixels, pixels)
            val canvas = Canvas(bitmap)
            setBounds(0, 0, pixels, pixels)
            draw(canvas)
            return bitmap
        }
    }
}
