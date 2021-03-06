package io.homeassistant.companion.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.dagger.GraphComponentAccessor
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.database.widget.StaticWidgetDao
import io.homeassistant.companion.android.database.widget.StaticWidgetEntity
import io.homeassistant.companion.android.domain.integration.IntegrationUseCase
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class StaticWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "StaticWidget"
        internal const val RECEIVE_DATA =
            "io.homeassistant.companion.android.widgets.StaticWidget.RECEIVE_DATA"
        internal const val UPDATE_ENTITY =
            "io.homeassistant.companion.android.widgets.StaticWidget.UPDATE_ENTITY"

        internal const val EXTRA_ENTITY_ID = "EXTRA_ENTITY_ID"
        internal const val EXTRA_ATTRIBUTE_IDS = "EXTRA_ATTRIBUTE_IDS"
        internal const val EXTRA_LABEL = "EXTRA_LABEL"
        internal const val EXTRA_TEXT_SIZE = "EXTRA_TEXT_SIZE"
        internal const val EXTRA_STATE_SEPARATOR = "EXTRA_STATE_SEPARATOR"
        internal const val EXTRA_ATTRIBUTE_SEPARATOR = "EXTRA_ATTRIBUTE_SEPARATOR"
    }

    @Inject
    lateinit var integrationUseCase: IntegrationUseCase

    private lateinit var staticWidgetDao: StaticWidgetDao

    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        staticWidgetDao = AppDatabase.getInstance(context).staticWidgetDao()
        // There may be multiple widgets active, so update all of them
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(
                context,
                appWidgetId,
                appWidgetManager
            )
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetId: Int,
        appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)
    ) {
        mainScope.launch {
            val views = getWidgetRemoteViews(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private suspend fun getWidgetRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
        val intent = Intent(context, StaticWidget::class.java).apply {
            action = UPDATE_ENTITY
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

        val views = RemoteViews(context.packageName, R.layout.widget_static).apply {
            val widget = staticWidgetDao.get(appWidgetId)
            if (widget != null) {
                val entityId: String = widget.entityId
                val attributeIds: String? = widget.attributeIds
                val label: String? = widget.label
                val textSize: Float = widget.textSize
                val stateSeparator: String = widget.stateSeparator
                val attributeSeparator: String = widget.attributeSeparator
                setTextViewTextSize(
                    R.id.widgetText,
                    TypedValue.COMPLEX_UNIT_SP,
                    textSize
                )
                setTextViewText(
                    R.id.widgetText,
                    resolveTextToShow(entityId, attributeIds, stateSeparator, attributeSeparator)
                )
                setTextViewText(
                    R.id.widgetLabel,
                    label ?: entityId
                )
                setOnClickPendingIntent(
                    R.id.widgetTextLayout,
                    PendingIntent.getBroadcast(
                        context,
                        appWidgetId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
        }

        return views
    }

    private suspend fun resolveTextToShow(
        entityId: String?,
        attributeIds: String?,
        stateSeparator: String,
        attributeSeparator: String
    ): CharSequence? {
        val entity = integrationUseCase.getEntities().find { e -> e.entityId.equals(entityId) }

        if (attributeIds == null) return entity?.state

        val fetchedAttributes = entity?.attributes as Map<*, *>
        val attributeValues = attributeIds.split(",").map { id -> fetchedAttributes.get(id)?.toString() }
        return entity.state.plus(if (attributeValues.isNotEmpty()) stateSeparator else "").plus(attributeValues.joinToString(attributeSeparator))
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

        Log.d(
            TAG, "Broadcast received: " + System.lineSeparator() +
                    "Broadcast action: " + action + System.lineSeparator() +
                    "AppWidgetId: " + appWidgetId
        )

        ensureInjected(context)

        staticWidgetDao = AppDatabase.getInstance(context).staticWidgetDao()

        super.onReceive(context, intent)

        when (action) {
            RECEIVE_DATA -> saveEntityConfiguration(context, intent.extras, appWidgetId)
            UPDATE_ENTITY -> updateAppWidget(context, appWidgetId)
        }
    }

    private fun saveEntityConfiguration(context: Context, extras: Bundle?, appWidgetId: Int) {
        if (extras == null) return

        val entitySelection: String? = extras.getString(EXTRA_ENTITY_ID)
        val attributeSelection: ArrayList<String>? = extras.getStringArrayList(EXTRA_ATTRIBUTE_IDS)
        val labelSelection: String? = extras.getString(EXTRA_LABEL)
        val textSizeSelection: String? = extras.getString(EXTRA_TEXT_SIZE)
        val stateSeparatorSelection: String? = extras.getString(EXTRA_STATE_SEPARATOR)
        val attributeSeparatorSelection: String? = extras.getString(EXTRA_ATTRIBUTE_SEPARATOR)

        if (entitySelection == null) {
            Log.e(TAG, "Did not receive complete service call data")
            return
        }

        mainScope.launch {
            Log.d(
                TAG, "Saving service call config data:" + System.lineSeparator() +
                "entity id: " + entitySelection + System.lineSeparator() +
                "attribute: " + attributeSelection ?: "N/A"
            )
            staticWidgetDao.add(StaticWidgetEntity(
                appWidgetId,
                entitySelection,
                attributeSelection?.joinToString(","),
                labelSelection,
                textSizeSelection?.toFloatOrNull() ?: 30F,
                stateSeparatorSelection ?: "",
                attributeSeparatorSelection ?: ""
            ))

            onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(appWidgetId))
        }
    }

    private fun ensureInjected(context: Context) {
        if (context.applicationContext is GraphComponentAccessor) {
            DaggerProviderComponent.builder()
                .appComponent((context.applicationContext as GraphComponentAccessor).appComponent)
                .build()
                .inject(this)
        } else {
            throw Exception("Application Context passed is not of our application!")
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        staticWidgetDao = AppDatabase.getInstance(context).staticWidgetDao()
        appWidgetIds.forEach { appWidgetId ->
            staticWidgetDao.delete(appWidgetId)
        }
    }
}
