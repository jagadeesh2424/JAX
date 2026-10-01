package com.jax.assistant.device

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.util.Locale
import java.time.ZonedDateTime
import com.jax.assistant.ai.agent.tools.DateTimeTool

/** Outcome of a device action. `launched` is true only when Android accepted the intent. */
data class DeviceActionResult(val launched: Boolean, val message: String)

/** Executes [DeviceCommand]s via Android framework intents. */
class DeviceController(private val context: Context) {

    fun execute(command: DeviceCommand): String = run(command).message

    fun run(command: DeviceCommand): DeviceActionResult = when (command) {
        is DeviceCommand.CurrentDateTime ->
            DeviceActionResult(true, DateTimeTool.describe(ZonedDateTime.now(), command.includeDate, command.includeTime))
        is DeviceCommand.OpenApp -> launchApp(command.appName)
        is DeviceCommand.WebSearch -> webSearch(command.query)
        is DeviceCommand.Weather -> webSearch(weatherQuery(command.location))
        is DeviceCommand.Dial -> dial(command.number)
        is DeviceCommand.Navigate -> navigate(command.destination)
        is DeviceCommand.SetAlarm -> setAlarm(command.hour, command.minute, command.label)
        is DeviceCommand.SetTimer -> setTimer(command.seconds, command.label)
        DeviceCommand.OpenCamera -> openCamera()
        DeviceCommand.OpenSettings -> openSettings()
    }

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun outcome(launched: Boolean, success: String, failure: String) =
        DeviceActionResult(launched, if (launched) success else failure)

    private fun launchApp(query: String): DeviceActionResult {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val q = query.trim().lowercase(Locale.US)

        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.US) == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.US).contains(q) }

        val launch = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        val launched = launch != null && start(launch)
        return outcome(launched, "Opening ${match?.loadLabel(pm)}.", "I couldn't find an app called \"$query\".")
    }

    private fun webSearch(query: String): DeviceActionResult {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, query) }
        val launched = start(search) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        return outcome(launched, "Searching the web for \"$query\".", "I couldn't start a web search.")
    }

    private fun weatherQuery(location: String?): String {
        val place = location?.trim().orEmpty()
        return if (place.isBlank()) "current weather today" else "current weather today in $place"
    }

    private fun dial(number: String): DeviceActionResult =
        outcome(start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))), "Dialing $number.", "I couldn't open the dialer.")

    private fun navigate(destination: String): DeviceActionResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
        return outcome(start(intent), "Getting directions to $destination.", "I couldn't open maps.")
    }

    private fun setAlarm(hour: Int, minute: Int, label: String?): DeviceActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        val hhmm = String.format(Locale.US, "%02d:%02d", hour, minute)
        return outcome(start(intent), "Setting an alarm for $hhmm.", "I couldn't set the alarm.")
    }

    private fun setTimer(seconds: Int, label: String?): DeviceActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return outcome(start(intent), "Setting a timer for $seconds seconds.", "I couldn't set the timer.")
    }

    private fun openCamera(): DeviceActionResult =
        outcome(start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "Opening the camera.", "I couldn't open the camera.")

    private fun openSettings(): DeviceActionResult =
        outcome(start(Intent(Settings.ACTION_SETTINGS)), "Opening settings.", "I couldn't open settings.")
}
