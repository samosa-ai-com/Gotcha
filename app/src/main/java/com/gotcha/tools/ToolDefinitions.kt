package com.gotcha.tools

import com.gotcha.llm.FunctionDefinition
import com.gotcha.llm.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * Declarative JSON schemas for every tool in the catalog (PRD §4).
 * Schemas are decoupled from execution: [ToolExecutor] handles side effects.
 */
object ToolDefinitions {

    private fun schema(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            block()
        }

    private fun tool(name: String, description: String, parameters: JsonObject) =
        ToolDefinition(function = FunctionDefinition(name, description, parameters))

    val dialNumber = tool(
        "dial_number",
        "Open the dialer pre-filled with a number WITHOUT calling — the user must tap call " +
            "themselves. Only use this when the user explicitly wants to review the number first " +
            "(e.g. 'open the dialer with…', 'let me press call'). For a normal 'call X' request, " +
            "use call_number instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Phone number to dial, e.g. +49123456789")
                }
            }
            putJsonArray("required") { add("number") }
        }
    )

    val getStorageInfo = tool(
        "get_storage_info",
        "Report total, used and free internal storage of the device.",
        schema { putJsonObject("properties") {} }
    )

    val getBatteryInfo = tool(
        "get_battery_info",
        "Report battery percentage and charging state.",
        schema { putJsonObject("properties") {} }
    )

    val listFiles = tool(
        "list_files",
        "List files in an allowed directory. Allowed roots: 'files' (app files), 'cache' (app cache), " +
            "'external' (app external files), 'downloads', 'pictures', 'dcim', 'documents', and " +
            "'storage' (the whole shared-storage tree, needs All-files access). " +
            "An optional relative sub-path may be appended, e.g. 'downloads/reports'.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Directory to list: one of the allowed roots optionally followed by a relative sub-path."
                    )
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val readFile = tool(
        "read_file",
        "Read a text file from an allowed directory (same roots as list_files, including 'storage' " +
            "with All-files access). Output is capped.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path rooted at an allowed root, e.g. 'files/notes.txt'.")
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val writeFile = tool(
        "write_file",
        "Write or append a text file. Roots 'files', 'cache', 'external' work in the app sandbox; " +
            "the 'storage' root writes anywhere in shared storage but needs All-files access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path rooted at a sandbox root, e.g. 'files/notes.txt'.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Text content to write.")
                }
                putJsonObject("append") {
                    put("type", "boolean")
                    put("description", "Append instead of overwrite. Default false.")
                }
            }
            putJsonArray("required") {
                add("path")
                add("content")
            }
        }
    )

    val clearAppCache = tool(
        "clear_app_cache",
        "Clear this app's own cache directory and report how much space was freed.",
        schema { putJsonObject("properties") {} }
    )

    val openApp = tool(
        "open_app",
        "Launch an installed app by its package name (e.g. com.android.settings) or by a human name " +
            "the executor will try to resolve.",
        schema {
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Android package name, e.g. com.google.android.youtube")
                }
            }
            putJsonArray("required") { add("package_name") }
        }
    )

    val toggleDarkMode = tool(
        "toggle_dark_mode",
        "Enable or disable dark theme for this app (and the system where supported).",
        schema {
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true for dark, false for light")
                }
            }
            putJsonArray("required") { add("enabled") }
        }
    )

    val setBrightness = tool(
        "set_brightness",
        "Set the screen brightness to a percentage between 0 and 100. Requires the 'Modify system settings' special access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("percent") {
                    put("type", "integer")
                    put("description", "Brightness 0-100")
                }
            }
            putJsonArray("required") { add("percent") }
        }
    )

    val toggleWifi = tool(
        "toggle_wifi",
        "Open the system Wi-Fi settings panel so the user can toggle Wi-Fi (apps cannot toggle it directly on modern Android).",
        schema { putJsonObject("properties") {} }
    )

    val setWallpaper = tool(
        "set_wallpaper",
        "Set the home-screen wallpaper from an image URL, or a random image if no URL is given.",
        schema {
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Direct image URL. Omit for a random wallpaper.")
                }
            }
        }
    )

    val runCommand = tool(
        "run_command",
        "Run a shell command as the unprivileged app user and return stdout/stderr/exit code. " +
            "Useful commands: ls, cat, getprop, pm list packages, df, uptime, date, id, ps. " +
            "No root; many system paths are unreadable. Output is capped and the command times out.",
        schema {
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command line, e.g. 'getprop ro.build.version.release'")
                }
            }
            putJsonArray("required") { add("command") }
        }
    )

    // ---- Tier 0–2 additions ----

    val callNumber = tool(
        "call_number",
        "Place a phone call directly — the call is dialed immediately. This is the DEFAULT tool for " +
            "any 'call X' / 'phone X' / 'ring X' request. Resolve names to numbers with find_contact " +
            "first if needed. Needs the Phone permission. Only fall back to dial_number if the user " +
            "explicitly wants to press call themselves.",
        schema {
            putJsonObject("properties") {
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Phone number to call, e.g. +49123456789")
                }
            }
            putJsonArray("required") { add("number") }
        }
    )

    val readCallLog = tool(
        "read_call_log",
        "Read the most recent call-log entries (incoming/outgoing/missed) with names, times and duration.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent calls to return (1-50). Default 10.")
                }
            }
        }
    )

    val findContact = tool(
        "find_contact",
        "Look up a saved contact by name (partial match) and return their phone number(s). " +
            "Use this to resolve requests like 'call mom' before dial_number/call_number.",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Contact name or partial name to search for.")
                }
            }
            putJsonArray("required") { add("name") }
        }
    )

    val addContact = tool(
        "add_contact",
        "Create a new contact with a name and phone number.",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Full display name for the contact.")
                }
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Phone number for the contact.")
                }
            }
            putJsonArray("required") {
                add("name")
                add("number")
            }
        }
    )

    val sendSms = tool(
        "send_sms",
        "Send a text (SMS) message directly to a number. The message is sent immediately. Needs the SMS permission.",
        schema {
            putJsonObject("properties") {
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Recipient phone number.")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Text body to send.")
                }
            }
            putJsonArray("required") {
                add("number")
                add("message")
            }
        }
    )

    val readRecentSms = tool(
        "read_recent_sms",
        "Read the most recent received SMS messages from the inbox (sender, time, body).",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent messages to return (1-50). Default 10.")
                }
            }
        }
    )

    val listCalendarEvents = tool(
        "list_calendar_events",
        "List upcoming calendar events within a number of days from now.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days_ahead") {
                    put("type", "integer")
                    put("description", "How many days ahead to include (1-365). Default 7.")
                }
            }
        }
    )

    val createCalendarEvent = tool(
        "create_calendar_event",
        "Add an event to the device's primary calendar.",
        schema {
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Event title.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "Start time as 'yyyy-MM-dd HH:mm' (device local time) or epoch milliseconds.")
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("description", "End time, same format as start. Defaults to one hour after start.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "Optional event location.")
                }
            }
            putJsonArray("required") {
                add("title")
                add("start")
            }
        }
    )

    val setAlarm = tool(
        "set_alarm",
        "Set a clock alarm at a given hour and minute via the device clock app.",
        schema {
            putJsonObject("properties") {
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "Hour in 24h format (0-23).")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "Minute (0-59).")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Optional alarm label.")
                }
            }
            putJsonArray("required") {
                add("hour")
                add("minute")
            }
        }
    )

    val setTimer = tool(
        "set_timer",
        "Start a countdown timer for a number of seconds via the device clock app.",
        schema {
            putJsonObject("properties") {
                putJsonObject("seconds") {
                    put("type", "integer")
                    put("description", "Timer length in seconds.")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Optional timer label.")
                }
            }
            putJsonArray("required") { add("seconds") }
        }
    )

    val toggleTorch = tool(
        "toggle_torch",
        "Turn the camera flashlight (torch) on or off.",
        schema {
            putJsonObject("properties") {
                putJsonObject("on") {
                    put("type", "boolean")
                    put("description", "true to turn the flashlight on, false to turn it off.")
                }
            }
            putJsonArray("required") { add("on") }
        }
    )

    val setVolume = tool(
        "set_volume",
        "Set a volume stream to a percentage. Streams: media, ring, alarm, notification, call.",
        schema {
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "Which stream: media, ring, alarm, notification, or call.")
                }
                putJsonObject("percent") {
                    put("type", "integer")
                    put("description", "Target volume 0-100.")
                }
            }
            putJsonArray("required") {
                add("stream")
                add("percent")
            }
        }
    )

    val setRingerMode = tool(
        "set_ringer_mode",
        "Set the ringer mode: normal, vibrate, or silent. Silent/vibrate need Do Not Disturb access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("mode") {
                    put("type", "string")
                    put("description", "One of: normal, vibrate, silent.")
                }
            }
            putJsonArray("required") { add("mode") }
        }
    )

    val vibrate = tool(
        "vibrate",
        "Vibrate the device for a short duration.",
        schema {
            putJsonObject("properties") {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("description", "Vibration length in milliseconds (1-5000). Default 500.")
                }
            }
        }
    )

    val setDnd = tool(
        "set_dnd",
        "Turn Do Not Disturb on or off. Needs Do Not Disturb (notification-policy) access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to enable Do Not Disturb, false to disable.")
                }
            }
            putJsonArray("required") { add("enabled") }
        }
    )

    val getLocation = tool(
        "get_location",
        "Report the device's last-known location (latitude/longitude and a nearby address if available). " +
            "Needs the Location permission.",
        schema { putJsonObject("properties") {} }
    )

    val listInstalledApps = tool(
        "list_installed_apps",
        "List the launchable apps installed on the device with their package names.",
        schema { putJsonObject("properties") {} }
    )

    val uninstallApp = tool(
        "uninstall_app",
        "Open the system uninstall dialog for an app package. The user confirms removal.",
        schema {
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Package name to uninstall, e.g. com.example.app")
                }
            }
            putJsonArray("required") { add("package_name") }
        }
    )

    val getAppUsage = tool(
        "get_app_usage",
        "Report per-app screen time (top apps) over a recent window. Needs Usage-access special access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "Look-back window in days (1-90). Default 7.")
                }
            }
        }
    )

    val getDataUsage = tool(
        "get_data_usage",
        "Report total mobile and Wi-Fi data used over a recent window. Needs Usage-access special access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "Look-back window in days (1-90). Default 30.")
                }
            }
        }
    )

    val getClipboard = tool(
        "get_clipboard",
        "Read the current text on the clipboard.",
        schema { putJsonObject("properties") {} }
    )

    val setClipboard = tool(
        "set_clipboard",
        "Copy text onto the clipboard.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to place on the clipboard.")
                }
            }
            putJsonArray("required") { add("text") }
        }
    )

    val takePhoto = tool(
        "take_photo",
        "Open the camera app to capture a photo; the image is saved into the app's storage. " +
            "Returns the file path where the photo will be saved.",
        schema { putJsonObject("properties") {} }
    )

    val startAudioRecording = tool(
        "start_audio_recording",
        "Start recording audio from the microphone to a file. Call stop_audio_recording to finish. " +
            "Needs the Microphone permission.",
        schema { putJsonObject("properties") {} }
    )

    val stopAudioRecording = tool(
        "stop_audio_recording",
        "Stop the in-progress audio recording and report the saved file path.",
        schema { putJsonObject("properties") {} }
    )

    // ---- Tier 3 additions (component-based / device-wide access) ----

    val readScreen = tool(
        "read_screen",
        "Read the visible text of whatever app is currently on screen, using the accessibility " +
            "service. Use this to 'see' the foreground app before tapping or typing. Needs the " +
            "Gotcha accessibility service to be enabled.",
        schema { putJsonObject("properties") {} }
    )

    val tap = tool(
        "tap",
        "Tap the screen via the accessibility service — either an on-screen element matching " +
            "some text/label, or absolute pixel coordinates. Prefer matching by text. Needs the " +
            "accessibility service enabled.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Visible text/label of the element to tap (case-insensitive, partial match).")
                }
                putJsonObject("x") {
                    put("type", "integer")
                    put("description", "Absolute X pixel coordinate (use with y instead of text).")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Absolute Y pixel coordinate (use with x instead of text).")
                }
            }
        }
    )

    val swipe = tool(
        "swipe",
        "Swipe the screen via the accessibility service — a named direction (up/down/left/right) " +
            "or explicit start/end coordinates. Use for scrolling and navigation. Needs the " +
            "accessibility service enabled.",
        schema {
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "One of: up, down, left, right.")
                }
                putJsonObject("x1") { put("type", "integer"); put("description", "Start X (with y1,x2,y2).") }
                putJsonObject("y1") { put("type", "integer"); put("description", "Start Y.") }
                putJsonObject("x2") { put("type", "integer"); put("description", "End X.") }
                putJsonObject("y2") { put("type", "integer"); put("description", "End Y.") }
            }
        }
    )

    val inputText = tool(
        "input_text",
        "Type text into the currently focused input field via the accessibility service. Tap a " +
            "text field first so it is focused. Needs the accessibility service enabled.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to type into the focused field.")
                }
            }
            putJsonArray("required") { add("text") }
        }
    )

    val globalAction = tool(
        "global_action",
        "Perform a device-wide navigation gesture via the accessibility service: back, home, " +
            "recents, notifications, quick_settings, or lock_screen. Needs the accessibility service enabled.",
        schema {
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "One of: back, home, recents, notifications, quick_settings, lock_screen.")
                }
            }
            putJsonArray("required") { add("action") }
        }
    )

    val readNotifications = tool(
        "read_notifications",
        "Read the currently active notifications from all apps (app, time, title/text, and a " +
            "dismiss key). Needs Notification access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent notifications to return (1-50). Default 15.")
                }
            }
        }
    )

    val dismissNotifications = tool(
        "dismiss_notifications",
        "Dismiss a notification by its key (from read_notifications), or all notifications if no " +
            "key is given. Needs Notification access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", "string")
                    put("description", "The notification key to dismiss. Omit to dismiss all.")
                }
            }
        }
    )

    val mediaControl = tool(
        "media_control",
        "Control the currently playing media session: play, pause, next, previous, or stop. " +
            "Needs Notification access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "One of: play, pause, next, previous, stop.")
                }
            }
            putJsonArray("required") { add("action") }
        }
    )

    val showOverlay = tool(
        "show_overlay",
        "Display a floating text banner on top of other apps. Needs the 'Display over other apps' permission.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to display in the overlay.")
                }
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("description", "How long to show it, in milliseconds (0 = until hidden). Default 4000.")
                }
            }
            putJsonArray("required") { add("text") }
        }
    )

    val hideOverlay = tool(
        "hide_overlay",
        "Remove the floating overlay shown by show_overlay.",
        schema { putJsonObject("properties") {} }
    )

    val lockScreen = tool(
        "lock_screen",
        "Immediately lock the device screen. Needs Gotcha to be an active device administrator.",
        schema { putJsonObject("properties") {} }
    )

    val disableCamera = tool(
        "disable_camera",
        "Disable or re-enable all device cameras via device-admin policy. Needs Gotcha to be " +
            "an active device administrator.",
        schema {
            putJsonObject("properties") {
                putJsonObject("disabled") {
                    put("type", "boolean")
                    put("description", "true to disable the camera, false to re-enable it.")
                }
            }
            putJsonArray("required") { add("disabled") }
        }
    )

    val setPasswordPolicy = tool(
        "set_password_policy",
        "Enforce a minimum unlock-password length via device-admin policy. Needs Gotcha to be " +
            "an active device administrator.",
        schema {
            putJsonObject("properties") {
                putJsonObject("min_length") {
                    put("type", "integer")
                    put("description", "Minimum password length to enforce (0-16). 0 clears the requirement.")
                }
            }
            putJsonArray("required") { add("min_length") }
        }
    )

    val all: List<ToolDefinition> = listOf(
        dialNumber, getStorageInfo, getBatteryInfo, listFiles, readFile, writeFile,
        clearAppCache, openApp, toggleDarkMode, setBrightness, toggleWifi,
        setWallpaper, runCommand,
        // Tier 0–2 additions
        callNumber, readCallLog, findContact, addContact, sendSms, readRecentSms,
        listCalendarEvents, createCalendarEvent, setAlarm, setTimer,
        toggleTorch, setVolume, setRingerMode, vibrate, setDnd,
        getLocation, listInstalledApps, uninstallApp, getAppUsage, getDataUsage,
        getClipboard, setClipboard, takePhoto, startAudioRecording, stopAudioRecording,
        // Tier 3 additions
        readScreen, tap, swipe, inputText, globalAction,
        readNotifications, dismissNotifications, mediaControl,
        showOverlay, hideOverlay,
        lockScreen, disableCamera, setPasswordPolicy
    )
}
