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

/** Executes [DeviceCommand]s via Android framework intents. Returns a user-facing result message. */
class DeviceController(private val context: Context) {

    fun execute(command: DeviceCommand): String = when (command) {
        is DeviceCommand.OpenApp -> launchApp(command.appName)
        is DeviceCommand.WebSearch -> webSearch(command.query)
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

    private fun launchApp(query: String): String {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val q = query.trim().lowercase(Locale.US)

        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.US) == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.US).contains(q) }

        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null && start(launch)) {
                return "Opening ${match.loadLabel(pm)}."
            }
        }
        return "I couldn't find an app called \"$query\"."
    }

    private fun webSearch(query: String): String {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, query) }
        if (start(search)) return "Searching the web for \"$query\"."
        val url = "https://www.google.com/search?q=" + Uri.encode(query)
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            "Searching the web for \"$query\"."
        } else {
            "I couldn't start a web search."
        }
    }

    private fun dial(number: String): String {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        return if (start(intent)) "Dialing $number." else "I couldn't open the dialer."
    }

    private fun navigate(destination: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
        return if (start(intent)) "Getting directions to $destination." else "I couldn't open maps."
    }

    private fun setAlarm(hour: Int, minute: Int, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        val hhmm = String.format(Locale.US, "%02d:%02d", hour, minute)
        return if (start(intent)) "Setting an alarm for $hhmm." else "I couldn't set the alarm."
    }

    private fun setTimer(seconds: Int, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return if (start(intent)) "Setting a timer for $seconds seconds." else "I couldn't set the timer."
    }

    private fun openCamera(): String {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        return if (start(intent)) "Opening the camera." else "I couldn't open the camera."
    }

    private fun openSettings(): String {
        val intent = Intent(Settings.ACTION_SETTINGS)
        return if (start(intent)) "Opening settings." else "I couldn't open settings."
    }
}
