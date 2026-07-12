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
        "List files and directories at the given path. Accepts absolute paths like " +
            "'/storage/emulated/0/Download' or paths relative to the working directory. " +
            "Supports recursive listing, sorting, and filtering.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute or relative directory path to list.")
                }
                putJsonObject("recursive") {
                    put("type", "boolean")
                    put("description", "List recursively (with depth limit). Default false.")
                }
                putJsonObject("sort_by") {
                    put("type", "string")
                    put("description", "Sort by: 'name' (default), 'date', or 'size'.")
                }
                putJsonObject("include") {
                    put("type", "string")
                    put("description", "Only show entries whose name contains this substring (case-insensitive).")
                }
                putJsonObject("exclude") {
                    put("type", "string")
                    put("description", "Exclude entries whose name contains this substring (case-insensitive).")
                }
                putJsonObject("max_depth") {
                    put("type", "integer")
                    put("description", "Max directory depth for recursive listing (1-20). Default 10.")
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val readFile = tool(
        "read_file",
        "Read any file — text, image, archive, or binary — at the given path. Accepts absolute paths " +
            "like '/storage/emulated/0/notes.txt' or paths relative to the working directory. " +
            "Automatically detects file type:\n" +
            "- Text files: returns content line-by-line with offset/limit support\n" +
            "- Images: feeds visual content to the vision model so you can 'see' them\n" +
            "- Archives (zip/apk/aar/jar/war): lists contents; supports reading entries via 'archive.zip/path'\n" +
            "- PDF & other binaries: returns base64 data\n" +
            "This tool replaces read_image — use read_file for ALL file reading needs.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute or relative path to the file or directory to read.")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "1-indexed line number to start reading from (text files only).")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max number of lines to return (text files only, default 2000).")
                }
                putJsonObject("encoding") {
                    put("type", "string")
                    put("description", "Text encoding (UTF-8, ISO-8859-1, UTF-16). Default UTF-8.")
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val writeFile = tool(
        "write_file",
        "Write or append content to a file at the given path. Accepts absolute paths like " +
            "'/storage/emulated/0/notes.txt' or paths relative to the working directory. " +
            "Supports both text and binary content. Parent directories are created automatically.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute or relative file path to write to.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Text content to write (or base64-encoded content when binary=true).")
                }
                putJsonObject("append") {
                    put("type", "boolean")
                    put("description", "Append instead of overwrite. Default false.")
                }
                putJsonObject("binary") {
                    put("type", "boolean")
                    put("description", "If true, content is base64-encoded binary data. Default false.")
                }
            }
            putJsonArray("required") {
                add("path")
                add("content")
            }
        }
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
        "Turn Wi-Fi on or off directly (on Android 13+) or open the Wi-Fi settings panel for the " +
            "user to toggle it (older Android). Reports whether Wi-Fi is currently enabled.",
        schema {
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to turn Wi-Fi on, false to turn it off")
                }
            }
            putJsonArray("required") { add("enabled") }
        }
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
            "Useful commands: ls, cat, find, getprop, pm list packages, df, uptime, date, id, ps. " +
            "No root; many system paths are unreadable. Output is capped and commands time out.",
        schema {
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command to execute, e.g. 'ls -la /storage/emulated/0'")
                }
                putJsonObject("working_dir") {
                    put("type", "string")
                    put("description", "Working directory for the command (absolute path). Defaults to the app's working directory.")
                }
                putJsonObject("timeout_seconds") {
                    put("type", "integer")
                    put("description", "Timeout in seconds (1-120). Default 15.")
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
            "explicitly wants to press call themselves. Supports speakerphone toggle and SIM selection.",
        schema {
            putJsonObject("properties") {
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Phone number to call, e.g. +49123456789")
                }
                putJsonObject("speakerphone") {
                    put("type", "boolean")
                    put("description", "Enable speakerphone for the call. Default false.")
                }
                putJsonObject("sim_slot") {
                    put("type", "string")
                    put("description", "SIM slot to use: 'sim1' or 'sim2'. Only needed on dual-SIM devices. Default is the system default.")
                }
            }
            putJsonArray("required") { add("number") }
        }
    )

    val readCallLog = tool(
        "read_call_log",
        "Read the most recent call-log entries (incoming/outgoing/missed) with names, times and duration. " +
            "Supports filtering by call type, contact name/number, and date range.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent calls to return (1-50). Default 10.")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "Filter by call type: 'incoming', 'outgoing', 'missed', or 'rejected'. Omit for all.")
                }
                putJsonObject("contact") {
                    put("type", "string")
                    put("description", "Filter by contact name or number (partial match).")
                }
                putJsonObject("from_date") {
                    put("type", "string")
                    put("description", "Start date, e.g. '2026-01-01'. Only calls on or after this date.")
                }
                putJsonObject("to_date") {
                    put("type", "string")
                    put("description", "End date, e.g. '2026-01-31'. Only calls on or before this date.")
                }
            }
        }
    )

    val findContact = tool(
        "find_contact",
        "Look up a saved contact by name or phone number (partial match). Returns matching contacts with " +
            "their phone numbers (with type labels like mobile/home/work), email, and organization. " +
            "Use this to resolve requests like 'call mom' before dial_number/call_number, " +
            "or to identify who called from a number.",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Contact name or partial name to search for.")
                }
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Phone number to reverse-lookup (find who owns this number).")
                }
            }
        }
    )

    val addContact = tool(
        "add_contact",
        "Create a new contact with a name, phone number, and optional fields. " +
            "Automatically detects and warns about duplicate contacts before creating. " +
            "Supports adding email and organization alongside the phone number.",
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
                putJsonObject("phone_type") {
                    put("type", "string")
                    put("description", "Phone number label: 'mobile' (default), 'home', 'work', 'main', 'fax', or 'pager'.")
                }
                putJsonObject("email") {
                    put("type", "string")
                    put("description", "Optional email address for the contact.")
                }
                putJsonObject("organization") {
                    put("type", "string")
                    put("description", "Optional company or organization name.")
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
        "Send a text (SMS) message directly to a number. The message is sent immediately. " +
            "Supports delivery confirmation, auto-detects GSM 7-bit vs UCS-2 encoding, " +
            "reports segment count, shows recent conversation context after sending, " +
            "and can schedule future delivery. Needs the SMS permission.",
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
                putJsonObject("delivery_report") {
                    put("type", "boolean")
                    put("description", "Request a delivery confirmation (reports Sent/Failed). Default false.")
                }
                putJsonObject("send_at") {
                    put("type", "string")
                    put("description", "Schedule future delivery: ISO-8601 timestamp like '2026-01-15T14:30:00' or epoch millis. Omit for immediate send.")
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
        "Read SMS messages from the inbox (and optionally sent folder) with filters. " +
            "Supports filtering by sender, date range, unread status, and body keyword search. " +
            "Contact names are resolved automatically when available.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many messages to return (1-50). Default 10.")
                }
                putJsonObject("from_address") {
                    put("type", "string")
                    put("description", "Filter by sender/recipient address (partial match, e.g. a contact name or phone number).")
                }
                putJsonObject("from_date") {
                    put("type", "string")
                    put("description", "Start date, e.g. '2026-01-01'. Only messages on or after this date.")
                }
                putJsonObject("to_date") {
                    put("type", "string")
                    put("description", "End date, e.g. '2026-01-31'. Only messages on or before this date.")
                }
                putJsonObject("unread_only") {
                    put("type", "boolean")
                    put("description", "Only show unread messages. Default false.")
                }
                putJsonObject("search") {
                    put("type", "string")
                    put("description", "Filter messages whose body contains this text (case-insensitive).")
                }
                putJsonObject("include_sent") {
                    put("type", "boolean")
                    put("description", "Also include sent messages alongside inbox messages. Default false.")
                }
            }
        }
    )

    val listCalendarEvents = tool(
        "list_calendar_events",
        "List calendar events within a date range. Supports looking ahead (days_ahead) or explicit " +
            "from/to dates, title keyword search, and shows status, description preview, and calendar name.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days_ahead") {
                    put("type", "integer")
                    put("description", "How many days ahead to include (1-365). Default 7 when from_date/to_date not set.")
                }
                putJsonObject("from_date") {
                    put("type", "string")
                    put("description", "Start date, e.g. '2026-01-01'. Overrides days_ahead.")
                }
                putJsonObject("to_date") {
                    put("type", "string")
                    put("description", "End date, e.g. '2026-01-31'. Defaults to one day after from_date.")
                }
                putJsonObject("search") {
                    put("type", "string")
                    put("description", "Filter events whose title contains this text (case-insensitive).")
                }
            }
        }
    )

    val createCalendarEvent = tool(
        "create_calendar_event",
        "Add an event to a calendar. Supports optional description, all-day flag, reminder, " +
            "and calendar selection. Defaults to the primary writable calendar.",
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
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "Optional notes or description for the event.")
                }
                putJsonObject("all_day") {
                    put("type", "boolean")
                    put("description", "Set as an all-day event. Default false.")
                }
                putJsonObject("reminder_minutes") {
                    put("type", "integer")
                    put("description", "Reminder before the event, in minutes. Default is no custom reminder.")
                }
                putJsonObject("calendar_name") {
                    put("type", "string")
                    put("description", "Calendar account name to add the event to (e.g. 'Work', 'Personal'). Defaults to the primary writable calendar.")
                }
            }
            putJsonArray("required") {
                add("title")
                add("start")
            }
        }
    )

    val editCalendarEvent = tool(
        "edit_calendar_event",
        "Update an existing calendar event. Only the fields you provide will be changed. " +
            "Get the event ID from list_calendar_events first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "integer")
                    put("description", "Event ID from list_calendar_events.")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "New title. Omit to keep current.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "New start time as 'yyyy-MM-dd HH:mm' or epoch millis. Omit to keep current.")
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("description", "New end time. Omit to keep current.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "New location. Omit to keep current.")
                }
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "New description. Omit to keep current.")
                }
                putJsonObject("all_day") {
                    put("type", "boolean")
                    put("description", "Set or unset all-day. Omit to keep current.")
                }
                putJsonObject("reminder_minutes") {
                    put("type", "integer")
                    put("description", "New reminder before event in minutes. -1 to remove reminder. Omit to keep current.")
                }
            }
            putJsonArray("required") { add("event_id") }
        }
    )

    val deleteCalendarEvent = tool(
        "delete_calendar_event",
        "Permanently delete a calendar event. Requires explicit user confirmation (destructive action). " +
            "Get the event ID from list_calendar_events first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "integer")
                    put("description", "Event ID from list_calendar_events.")
                }
            }
            putJsonArray("required") { add("event_id") }
        }
    )

    val setAlarm = tool(
        "set_alarm",
        "Set an alarm at a given hour and minute. Supports repeating by day of week and silent mode. " +
            "The alarm is created in the system clock app when one is available (visible in its alarm " +
            "list, rings with the full alarm UI); otherwise it falls back to an in-app alarm that rings " +
            "as a notification. Returns an alarm ID that can be used to edit or delete it later. " +
            "List alarms with list_alarms. Edit with edit_alarm. Delete with delete_alarm.",
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
                putJsonObject("days") {
                    put("type", "array")
                    put("description", "Repeating days: e.g. ['mon','wed','fri'] or ['weekdays']. Omit for one-time alarm.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("vibrate") {
                    put("type", "boolean")
                    put("description", "Whether the alarm should vibrate. Default true.")
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
        "Start a countdown timer. Timers are managed in-app (a notification fires when done), not in " +
            "the system clock app. Returns a timer ID that can be used to delete it later. " +
            "List timers with list_timers. Delete with delete_timer.",
        schema {
            putJsonObject("properties") {
                putJsonObject("seconds") {
                    put("type", "integer")
                    put("description", "Timer length in seconds. Combine with hours/minutes for longer durations.")
                }
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("description", "Additional minutes (e.g. 5 for '5 minutes').")
                }
                putJsonObject("hours") {
                    put("type", "integer")
                    put("description", "Additional hours.")
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
        "Turn the camera flashlight (torch) on or off. If the flashlight is already in the " +
            "requested state, reports that without error.",
        schema {
            putJsonObject("properties") {
                putJsonObject("on") {
                    put("type", "boolean")
                    put("description", "true to turn the flashlight on, false to turn it off.")
                }
                putJsonObject("duration_seconds") {
                    put("type", "integer")
                    put("description", "Optional: auto-turn off after this many seconds (1-300). Only applies when turning on.")
                }
            }
            putJsonArray("required") { add("on") }
        }
    )

    val setVolume = tool(
        "set_volume",
        "Set a volume stream to a percentage (0-100). Use 0 to mute or 100 for max volume. " +
            "Streams: media, ring, alarm, notification, call. Reports the previous and new volume levels.",
        schema {
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "Which stream: media, ring, alarm, notification, or call.")
                }
                putJsonObject("percent") {
                    put("type", "integer")
                    put("description", "Target volume 0-100 (0 = mute, 100 = max).")
                }
                putJsonObject("show_ui") {
                    put("type", "boolean")
                    put("description", "Show the volume slider briefly. Default false.")
                }
            }
            putJsonArray("required") {
                add("stream")
                add("percent")
            }
        }
    )

    val getVolume = tool(
        "get_volume",
        "Read the current volume level (0-100) of a stream, or all streams if none is specified. " +
            "Streams: media, ring, alarm, notification, call. Reports each stream's current percentage.",
        schema {
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "Which stream to read: media, ring, alarm, notification, call. If omitted, all streams are reported.")
                }
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
        "Vibrate the device. Supports custom intensity and predefined patterns. " +
            "Use for haptic feedback, notifications, alerts, or attention-getting.",
        schema {
            putJsonObject("properties") {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("description", "Vibration length in milliseconds (1-5000). Default 500. Ignored when pattern is set.")
                }
                putJsonObject("intensity") {
                    put("type", "integer")
                    put("description", "Vibration strength 0-100 (0 = none, 100 = max). Default 100.")
                }
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Predefined pattern: short (100ms), long (1s), double (two quick buzzes), sos (...---...). Overrides duration_ms.")
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
        "Report the device's location (latitude/longitude, accuracy, altitude, speed, bearing, " +
            "and a nearby address if available). Also returns a Google Maps link. " +
            "Can request a fresh GPS fix instead of the last-known location. Needs the Location permission.",
        schema {
            putJsonObject("properties") {
                putJsonObject("fresh") {
                    put("type", "boolean")
                    put("description", "If true, request a fresh GPS fix (takes a few seconds). Default false (uses last-known location).")
                }
            }
        }
    )

    val listInstalledApps = tool(
        "list_installed_apps",
        "List installed apps optionally filtered by name or package. Returns a summary of " +
            "how many apps are installed and, when a search is provided, the matching apps. " +
            "Use this before uninstall_app to find the exact package name.",
        schema {
            putJsonObject("properties") {
                putJsonObject("search") {
                    put("type", "string")
                    put("description", "Optional: filter to apps whose name or package contains this text.")
                }
            }
        }
    )

    val uninstallApp = tool(
        "uninstall_app",
        "Uninstall an app by its name or package name. Accepts both exact package names " +
            "(e.g. com.example.app) and human-readable app names (e.g. YouTube). " +
            "When a name is given, the tool resolves it to the matching package automatically. " +
            "The user will be asked to confirm before the uninstall proceeds.",
        schema {
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "App name or package name to uninstall, e.g. com.example.app or YouTube.")
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
        "Capture a photo automatically using the device camera. No camera app is opened — " +
            "the photo is taken in the background and saved to the app's storage. " +
            "Needs the Camera permission. Returns the file path of the captured photo.",
        schema {
            putJsonObject("properties") {
                putJsonObject("camera") {
                    put("type", "string")
                    put("description", "Which camera to use: 'back' (default) or 'front'.")
                }
            }
        }
    )

    val startAudioRecording = tool(
        "start_audio_recording",
        "Start recording audio from the microphone to a file. Supports configurable source, " +
            "quality, max duration, and custom output path. Call stop_audio_recording to finish. " +
            "Needs the Microphone permission.",
        schema {
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put("description", "Audio source: 'mic' (default), 'voice' (optimized for speech), or 'camcorder' (wider range).")
                }
                putJsonObject("max_duration_seconds") {
                    put("type", "integer")
                    put("description", "Auto-stop after this many seconds. 0 or omit for no limit.")
                }
                putJsonObject("output_path") {
                    put("type", "string")
                    put("description", "Custom file path to save the recording. Defaults to Recordings/recording_{timestamp}.m4a.")
                }
                putJsonObject("quality") {
                    put("type", "string")
                    put("description", "Recording quality: 'low' (16kHz, 16kbps), 'medium' (44.1kHz, 64kbps), or 'high' (44.1kHz, 192kbps). Default medium.")
                }
            }
        }
    )

    val stopAudioRecording = tool(
        "stop_audio_recording",
        "Stop the in-progress audio recording and report the saved file path and duration.",
        schema { putJsonObject("properties") {} }
    )

    val getAudioRecordingStatus = tool(
        "get_audio_recording_status",
        "Check whether a recording is active, and if so report its duration and file path.",
        schema { putJsonObject("properties") {} }
    )

    val pauseAudioRecording = tool(
        "pause_audio_recording",
        "Pause the in-progress recording without stopping it. Resume with resume_audio_recording. " +
            "Requires Android 7.0+.",
        schema { putJsonObject("properties") {} }
    )

    val resumeAudioRecording = tool(
        "resume_audio_recording",
        "Resume a paused recording. Only works if pause_audio_recording was called first. " +
            "Requires Android 7.0+.",
        schema { putJsonObject("properties") {} }
    )

    val listAlarms = tool(
        "list_alarms",
        "List all active alarms set by set_alarm, with their IDs, times, labels, and recurrence, plus " +
            "the next alarm scheduled system-wide by any app. Alarms the user created manually in the " +
            "clock app cannot be enumerated (Android exposes only the single next one); tell the user " +
            "to check the clock app if they ask about alarms not in this list.",
        schema { putJsonObject("properties") {} }
    )

    val listTimers = tool(
        "list_timers",
        "List all running timers set by set_timer, with their IDs, labels, and remaining time.",
        schema { putJsonObject("properties") {} }
    )

    val editAlarm = tool(
        "edit_alarm",
        "Edit an existing alarm by ID. Only the fields you provide will be changed. " +
            "Get the alarm ID from list_alarms. For alarms living in the system clock app this is " +
            "done by dismissing the old alarm and creating a new one, which is best-effort — relay " +
            "any caveat in the result to the user.",
        schema {
            putJsonObject("properties") {
                putJsonObject("alarm_id") {
                    put("type", "integer")
                    put("description", "Alarm ID from list_alarms.")
                }
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "New hour (0-23). Omit to keep current.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "New minute (0-59). Omit to keep current.")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "New label. Omit to keep current.")
                }
                putJsonObject("days") {
                    put("type", "array")
                    put("description", "New repeating days. Omit to keep current. Pass empty array for one-time.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("vibrate") {
                    put("type", "boolean")
                    put("description", "New vibrate setting. Omit to keep current.")
                }
            }
            putJsonArray("required") { add("alarm_id") }
        }
    )

    val deleteAlarm = tool(
        "delete_alarm",
        "Permanently delete an alarm by ID. Requires explicit user confirmation (destructive action). " +
            "Get the alarm ID from list_alarms. For alarms living in the system clock app the delete " +
            "is best-effort (some clock apps only disable the alarm or skip its next occurrence) — " +
            "relay any caveat in the result to the user.",
        schema {
            putJsonObject("properties") {
                putJsonObject("alarm_id") {
                    put("type", "integer")
                    put("description", "Alarm ID from list_alarms.")
                }
            }
            putJsonArray("required") { add("alarm_id") }
        }
    )

    val deleteTimer = tool(
        "delete_timer",
        "Cancel and remove a running timer by ID. Requires explicit user confirmation (destructive action). " +
            "Get the timer ID from list_timers.",
        schema {
            putJsonObject("properties") {
                putJsonObject("timer_id") {
                    put("type", "integer")
                    put("description", "Timer ID from list_timers.")
                }
            }
            putJsonArray("required") { add("timer_id") }
        }
    )

    val edit = tool(
        "edit",
        "Replace exact text in a file. Use this for surgical edits — changing specific " +
            "lines, variables, or values — without rewriting the entire file. " +
            "The oldString must match the existing text exactly, including whitespace and " +
            "indentation. Set replaceAll=true to replace every occurrence. " +
            "Only available in the app sandbox (files, cache, external) or 'storage' root.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path rooted at a writable root, e.g. 'files/notes.txt'.")
                }
                putJsonObject("oldString") {
                    put("type", "string")
                    put("description", "Exact text to replace (must match existing content exactly).")
                }
                putJsonObject("newString") {
                    put("type", "string")
                    put("description", "Replacement text.")
                }
                putJsonObject("replaceAll") {
                    put("type", "boolean")
                    put("description", "Replace all occurrences instead of just the first. Default false.")
                }
            }
            putJsonArray("required") { add("path"); add("oldString"); add("newString") }
        }
    )

    val question = tool(
        "question",
        "Ask the user a question when you need clarification, a decision, or more information " +
            "to proceed. Provide clear, concise options when possible. " +
            "Use this instead of guessing the user's intent.",
        schema {
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "The question to ask the user.")
                }
                putJsonObject("options") {
                    put("type", "array")
                    put("description", "Optional predefined answer choices (max 10).")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("allowCustom") {
                    put("type", "boolean")
                    put("description", "Allow the user to type a custom answer. Default true.")
                }
            }
            putJsonArray("required") { add("question") }
        }
    )

    val todowrite = tool(
        "todowrite",
        "Create and maintain a structured task list for the current session. " +
            "Use this to plan multi-step tasks, track progress, and check off completed items. " +
            "Each call replaces the entire list. Status values: pending, in_progress, completed, cancelled. " +
            "Priority values (optional): high, medium, low.",
        schema {
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    put("description", "List of tasks to track.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("content") { put("type", "string"); put("description", "Task description.") }
                            putJsonObject("status") { put("type", "string"); put("description", "One of: pending, in_progress, completed, cancelled.") }
                            putJsonObject("priority") { put("type", "string"); put("description", "Optional: high, medium, low.") }
                        }
                        putJsonArray("required") { add("content"); add("status") }
                    }
                }
            }
            putJsonArray("required") { add("items") }
        }
    )

    val sleep = tool(
        "sleep",
        "Pause execution for a given duration, then automatically resume. " +
            "Use this to wait before the next action — e.g. let a timer finish, " +
            "wait for a download, or delay between operations. " +
            "Can be cancelled anytime via the Stop button. " +
            "Available to both Monitor and Operator modes.",
        schema {
            putJsonObject("properties") {
                putJsonObject("duration_seconds") {
                    put("type", "integer")
                    put("description", "How long to sleep, in seconds (1-86400).")
                }
            }
            putJsonArray("required") { add("duration_seconds") }
        }
    )

    val task = tool(
        "task",
        "Delegate a multi-step task to a sub-agent. The sub-agent runs independently " +
            "with full access to all Operator tools and returns a final report. " +
            "Use this when the work involves many steps whose intermediate details are " +
            "not important to you — only the final result matters. " +
            "Available sub-agents: GENERAL — a general-purpose agent that can perform " +
            "any multi-step operation using all available device tools. " +
            "Cannot be called from within a sub-agent (no recursive delegation). " +
            "Only available to Operator mode.",
        schema {
            putJsonObject("properties") {
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "Brief description of what the sub-agent should do " +
                        "(shown in the UI while it runs).")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "Detailed instructions for the sub-agent. " +
                        "Include all context the sub-agent needs to complete the task.")
                }
                putJsonObject("subagent_type") {
                    put("type", "string")
                    put("description", "Which sub-agent to use. Default: 'general'. " +
                        "Available: general.")
                }
            }
            putJsonArray("required") { add("description"); add("prompt") }
        }
    )

    val ask_final_answer = tool(
        "ask_final_answer",
        "Signal that the delegated task is complete and provide the final result. " +
            "Only available to General sub-agent. " +
            "Call this once all required steps have been executed and you have the " +
            "complete answer to report back. " +
            "Do NOT call this before the work is actually done.",
        schema {
            putJsonObject("properties") {
                putJsonObject("answer") {
                    put("type", "string")
                    put("description", "The complete final result to report back.")
                }
            }
            putJsonArray("required") { add("answer") }
        }
    )

    val glob = tool(
        "glob",
        "Find files matching a glob pattern within an allowed directory. " +
            "Use * to match any non-/ characters, ** to match any path recursively, " +
            "and ? for a single character. Examples: '**/*.txt', 'downloads/*.jpg', " +
            "'files/**/*.kt'. Returns matching file paths relative to the search root.",
        schema {
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Glob pattern, e.g. '**/*.txt' or 'downloads/*.jpg'.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Root and optional sub-path to search, e.g. 'files' or 'downloads/reports'.")
                }
            }
            putJsonArray("required") { add("pattern"); add("path") }
        }
    )

    val grep = tool(
        "grep",
        "Search file contents by regular expression within an allowed directory. " +
            "Use a path root (e.g. 'files', 'downloads') to narrow the search, " +
            "and include to filter files by name pattern (e.g. '*.txt', '*.{kt,java}'). " +
            "Returns matching file path, line number, and line content. " +
            "Uses case-insensitive matching.",
        schema {
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Regular expression pattern to search for (case-insensitive).")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Root and optional sub-path to search, e.g. 'files' or 'downloads/reports'.")
                }
                putJsonObject("include") {
                    put("type", "string")
                    put("description", "Optional file-name filter glob, e.g. '*.txt', '*.kt', '*.{json,xml}'.")
                }
            }
            putJsonArray("required") { add("pattern"); add("path") }
        }
    )

    val webSearch = tool(
        "websearch",
        "Search the web for information using a text query. Returns up to 10 results with titles, URLs, and snippets. " +
            "Use this to find current information, documentation, tutorials, or anything online. " +
            "The results are from DuckDuckGo — no API key needed. " +
            "If you need the full content of a specific result, use webfetch on its URL.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query (natural language or keywords).")
                }
                putJsonObject("numResults") {
                    put("type", "integer")
                    put("description", "Number of results to return (1-10). Default 5.")
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    val webFetch = tool(
        "webfetch",
        "Fetch content from an HTTP or HTTPS URL and return it as text. " +
            "Use this to look up documentation, read articles, or gather information from the web. " +
            "Only text responses (HTML, JSON, XML) are supported; binary content is rejected.",
        schema {
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "HTTP or HTTPS URL to fetch.")
                }
                putJsonObject("format") {
                    put("type", "string")
                    put("description", "Optional output format: 'text' (default) or 'markdown'. Currently always returns text.")
                }
            }
            putJsonArray("required") { add("url") }
        }
    )

    val readImage = tool(
        "read_image",
        "DEPRECATED — use read_file instead which handles images, text, archives, and all other formats. " +
            "Reads an image file and makes it visible to the vision model. " +
            "This tool is kept for backward compatibility; new code should call read_file.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute or relative path to the image file.")
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    // ---- Tier 3 additions (component-based / device-wide access) ----

    val readScreen = tool(
        "read_screen",
        "Read the visible text of whatever app is currently on screen, using the accessibility " +
            "service. Use this to 'see' the foreground app before tapping or typing. Needs the " +
            "Gotcha accessibility service to be enabled.",
        schema { putJsonObject("properties") {} }
    )

    val readScreenRaw = tool(
        "read_screen_raw",
        "Read the visible text AND capture a full-resolution screenshot of whatever app " +
            "is currently on screen. Use this instead of read_screen when you need the " +
            "highest quality visual detail (e.g. for reading small text, icons, or " +
            "images). Prefer read_screen for most cases to save context.",
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
                    put("description", "X coordinate. If normalized=true, use [0,1000] space; else absolute pixels.")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Y coordinate. If normalized=true, use [0,1000] space; else absolute pixels.")
                }
                putJsonObject("normalized") {
                    put("type", "boolean")
                    put("description", "If true, x and y are in [0, 1000] normalized space. Default false.")
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
                putJsonObject("normalized") {
                    put("type", "boolean")
                    put("description", "If true, coordinate params are in [0, 1000] normalized space. Default false.")
                }
                putJsonObject("distance") {
                    put("type", "integer")
                    put("description", "Scroll distance in pixels. Default 0 = full screen (70% of dimension). Ignored when using explicit coordinates.")
                }
            }
        }
    )

    val tapIndex = tool(
        "tap_index",
        "Tap a UI element from the numbered elements list shown in the screen observation. " +
            "The index corresponds to the number shown before each element. " +
            "Prefer this over raw coordinate tap when possible for precision.",
        schema {
            putJsonObject("properties") {
                putJsonObject("index") {
                    put("type", "integer")
                    put("description", "Index of the element to tap (from the ── UI Elements ── list).")
                }
            }
            putJsonArray("required") { add("index") }
        }
    )

    val pressKey = tool(
        "press_key",
        "Press a system key or perform a navigation action. " +
            "For entering text, use input_text instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", "string")
                    put("description", "One of: enter, back, home, recents, notifications, quick_settings, lock_screen, delete, tab, escape.")
                }
            }
            putJsonArray("required") { add("key") }
        }
    )

    val navigateApp = tool(
        "navigate_app",
        "Open apps and navigate them step by step. Provide a detailed description of " +
            "what to do — the App Navigator will look at the screen, tap, swipe, type, " +
            "and scroll until the task is done. " +
            "Only available to Operator mode. Cannot be called from within a sub-agent.",
        schema {
            putJsonObject("properties") {
                putJsonObject("task") {
                    put("type", "string")
                    put("description", "Detailed step-by-step instructions of what to do in the app. " +
                        "Include the app name, search terms, what to look for, and what the final " +
                        "summary should contain. " +
                        "Example: 'Open Google Maps, search for restaurants near me, scroll through " +
                        "results, and tell me the top 5 with ratings and distances.'")
                }
            }
            putJsonArray("required") { add("task") }
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

    // ---- Tier 3 addition: VpnService (local traffic firewall) ----

    val setFirewall = tool(
        "set_firewall",
        "Enable or disable a local VPN that blocks ALL device network traffic — an on-device " +
            "internet kill-switch. Nothing is inspected or sent anywhere; while it is on, every " +
            "packet is dropped so no app can reach the network. Enabling needs a one-time system " +
            "VPN consent the first time.",
        schema {
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to block all network traffic, false to restore connectivity.")
                }
            }
            putJsonArray("required") { add("enabled") }
        }
    )

    val getFirewallStatus = tool(
        "get_firewall_status",
        "Report whether the local traffic-blocking VPN firewall is currently on or off.",
        schema { putJsonObject("properties") {} }
    )

    // ---- Tier 4 additions (privileged / rooted execution) ----

    val checkRoot = tool(
        "check_root",
        "Check whether this device is rooted (a working `su` shell is available). Use this before " +
            "attempting run_root_command or write_secure_settings so you can tell the user if root is missing.",
        schema { putJsonObject("properties") {} }
    )

    val runRootCommand = tool(
        "run_root_command",
        "Run a shell command as ROOT via `su` and return its output. Only works on a rooted device; " +
            "on a normal phone this fails with a clear 'not rooted' message. This is the Tier 4 escape " +
            "hatch for privileged operations an ordinary app can't do — e.g. silent `pm install`, reading " +
            "other apps' data, `settings put secure …`. A few irreversible device-wiping commands are blocked.",
        schema {
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command line to run as root, e.g. 'pm list packages -s'.")
                }
            }
            putJsonArray("required") { add("command") }
        }
    )

    val writeSecureSettings = tool(
        "write_secure_settings",
        "Write an Android setting in the system/secure/global namespace (the WRITE_SECURE_SETTINGS " +
            "capability, e.g. Settings.Secure). Uses root under the hood, so it needs a rooted device. " +
            "Example: namespace='secure', key='location_mode', value='3'.",
        schema {
            putJsonObject("properties") {
                putJsonObject("namespace") {
                    put("type", "string")
                    put("description", "One of: system, secure, global.")
                }
                putJsonObject("key") {
                    put("type", "string")
                    put("description", "Setting key, e.g. 'location_mode'.")
                }
                putJsonObject("value") {
                    put("type", "string")
                    put("description", "Value to write.")
                }
            }
            putJsonArray("required") {
                add("namespace")
                add("key")
                add("value")
            }
        }
    )

    val all: List<ToolDefinition> = listOf(
        dialNumber, getStorageInfo, getBatteryInfo, listFiles, readFile, writeFile,
        openApp, setBrightness, toggleWifi,
        setWallpaper, runCommand,
        // Tier 0–2 additions
        callNumber, readCallLog, findContact, addContact, sendSms, readRecentSms,
        listCalendarEvents, createCalendarEvent, editCalendarEvent, deleteCalendarEvent,
        setAlarm, setTimer, listAlarms, listTimers, editAlarm, deleteAlarm, deleteTimer,
        toggleTorch, setVolume, getVolume, setRingerMode, vibrate, setDnd,
        getLocation, listInstalledApps, uninstallApp, getAppUsage, getDataUsage,
        getClipboard, setClipboard, takePhoto, startAudioRecording, stopAudioRecording,
        getAudioRecordingStatus, pauseAudioRecording, resumeAudioRecording,
        // User interaction
        question,
        // Sleep / delay utility (available to both agents)
        sleep,
        // Sub-agent delegation (Operator only)
        task,
        ask_final_answer,
        // Task tracking
        todowrite,
        // Surgical file editing (Operator only)
        edit,
        // Content search and file discovery
        glob, grep,
        // Web search + fetch
        webSearch, webFetch,
        // DEPRECATED: readImage excluded from the active catalog — use read_file instead.
        // Tier 3 additions
        readScreen, readScreenRaw, tap, swipe, tapIndex, pressKey, inputText, globalAction,
        navigateApp,
        readNotifications, dismissNotifications, mediaControl,
        showOverlay, hideOverlay,
        lockScreen, disableCamera, setPasswordPolicy,
        // Tier 3 addition: VpnService firewall
        setFirewall, getFirewallStatus,
        // Tier 4 additions: privileged / rooted execution
        checkRoot, runRootCommand, writeSecureSettings
    )
}
