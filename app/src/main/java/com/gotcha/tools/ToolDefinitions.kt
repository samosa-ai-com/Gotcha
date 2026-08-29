package com.gotcha.tools

import com.gotcha.llm.FunctionDefinition
import com.gotcha.llm.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Declarative JSON schemas for every tool in the catalog (PRD §4).
 * Schemas are decoupled from execution: [ToolExecutor] handles side effects.
 */
// Fixed catalog of all tool schemas in one object by design; size is inherent.
@Suppress("LargeClass")
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

    val aboutSamosaAi = tool(
        "about_samosa_ai",
        "Get information about Samosa AI, the company that makes Gotcha: its mission and " +
            "values, its other products (Chanakya, Personal Guru, Voice Typing and more), " +
            "pricing, the developers and how to contact them, and a summary of Gotcha's terms, " +
            "disclaimer and data-retention policy. Call this for ANY question about Samosa AI, " +
            "its products, its terms or privacy policy, who built this app, or how to reach " +
            "them — do not guess at these from memory.",
        schema { putJsonObject("properties") {} }
    )

    val updateUserProfile = tool(
        "update_user_profile",
        "Update the user's stored personal profile in Settings (occupation, background, " +
            "reply style) with a durable fact they just revealed — a new job or role, a lasting " +
            "background detail, or an explicit preference for how replies are written. " +
            "Only call this for genuinely new, durable information, never for transient " +
            "statements. For each field you change, pass the COMPLETE updated value, preserving " +
            "everything already stored (modify and extend, never erase) and removing only facts " +
            "the user has clearly outgrown or contradicted. Keep background under 250 words and " +
            "reply style under 50 words. Omit any field you are not changing.",
        schema {
            putJsonObject("properties") {
                putJsonObject("occupation") {
                    put("type", "string")
                    put("description", "The user's current occupation or role, e.g. 'Backend engineer'.")
                }
                putJsonObject("background") {
                    put("type", "string")
                    put(
                        "description",
                        "The complete background text — preserve prior facts, add the new ones, " +
                            "and keep it under 250 words."
                    )
                }
                putJsonObject("reply_style") {
                    put("type", "string")
                    put(
                        "description",
                        "The complete reply-style preference — preserve prior preferences, add " +
                            "the new one, and keep it under 50 words."
                    )
                }
            }
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
        "List files and directories at a path, optionally recursive, sorted and filtered.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Directory path to list.")
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
        "Read any file, detecting its type: text line-by-line (offset/limit), images fed to " +
            "the vision model so you can see them, archives (zip/apk/jar) listed and readable " +
            "entry-wise via 'archive.zip/path', other binaries as base64.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Path to the file or directory to read.")
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
        "Write or append text or binary content to a file. Parent directories are created.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path to write to.")
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
        "Turn Wi-Fi on or off. Android 10 and newer forbid apps from toggling the radio, so on " +
            "any current device this opens the Wi-Fi panel for the user to flip instead — say so " +
            "rather than claiming the radio changed. Direct toggling only works below Android 10.",
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

    val openSetting = tool(
        "open_setting",
        "Open an Android settings screen directly, for settings this app is not allowed to " +
            "change on its own. Much more reliable than opening the Settings app and searching. " +
            "Use it before navigate_app for anything it covers, then tap the control it " +
            "describes. Valid values for 'setting': " +
            "wifi, internet, mobile_data, nfc, bluetooth, location, airplane_mode, " +
            "battery_saver, display, sound, date_time, language, input_method, " +
            "storage, accessibility, default_apps, cast, developer_options, lock_screen, vpn, " +
            "device_admin. For anything not listed, use navigate_app instead. " +
            "Prefer the direct tools where they exist — set_volume, set_brightness, set_dnd, " +
            "set_ringer_mode and toggle_torch change those without leaving the app.",
        schema {
            putJsonObject("properties") {
                putJsonObject("setting") {
                    put("type", "string")
                    put("description", "Which settings screen to open, e.g. 'location' or 'bluetooth'.")
                }
                putJsonObject("confirmed") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Set true only after the user has explicitly agreed. Required for the " +
                            "security-relevant screens (developer_options, lock_screen, vpn, " +
                            "device_admin), which otherwise refuse to open."
                    )
                }
            }
            putJsonArray("required") { add("setting") }
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
        "Run a shell command as the unprivileged app user, returning stdout/stderr/exit code. " +
            "No root, so many system paths are unreadable. Output is capped; commands time out.",
        schema {
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command to execute, e.g. 'ls -la /storage/emulated/0'")
                }
                putJsonObject("working_dir") {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute working directory. Defaults to the app's working directory."
                    )
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
        "Dial a number immediately. The default tool for any 'call X' request — resolve names " +
            "with find_contact first. Use dial_number only if the user wants to press call.",
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
                    put(
                        "description",
                        "'sim1' or 'sim2', for dual-SIM devices. Defaults to the system default."
                    )
                }
            }
            putJsonArray("required") { add("number") }
        }
    )

    val readCallLog = tool(
        "read_call_log",
        "Read recent call-log entries with names, times and duration.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent calls to return (1-50). Default 10.")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put(
                        "description",
                        "Filter by call type: 'incoming', 'outgoing', 'missed', or 'rejected'. Omit for all."
                    )
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
        "Look up a saved contact by name or number (partial match), returning numbers, email " +
            "and organization. Resolves 'call mom' before call_number, or identifies a caller.",
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
        "Create a contact. Warns about likely duplicates before creating.",
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
                    put(
                        "description",
                        "Phone number label: 'mobile' (default), 'home', 'work', 'main', 'fax', or 'pager'."
                    )
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
        "Send an SMS, immediately or scheduled. Reports segment count and recent " +
            "conversation context after sending.",
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
                    put(
                        "description",
                        "Schedule delivery: ISO-8601 like '2026-01-15T14:30:00' or epoch millis."
                    )
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
        "Read SMS messages from the inbox, with contact names resolved where known.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many messages to return (1-50). Default 10.")
                }
                putJsonObject("from_address") {
                    put("type", "string")
                    put(
                        "description",
                        "Filter by sender/recipient address (partial match, e.g. a contact name or phone number)."
                    )
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
        "List calendar events in a date range, with optional title search.",
        schema {
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put(
                        "description",
                        "'device' (default, the phone's own calendars), 'google' or 'microsoft'."
                    )
                }
                putJsonObject("days_ahead") {
                    put("type", "integer")
                    put(
                        "description",
                        "Days ahead to include (1-365). Default 7 when from_date/to_date are unset."
                    )
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
        "Add an event to a calendar. Defaults to the phone's primary writable calendar.",
        schema {
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put(
                        "description",
                        "'device' (default), 'google' or 'microsoft'. all_day, " +
                            "reminder_minutes and recurrence apply to 'device' only."
                    )
                }
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
                    put(
                        "description",
                        "Calendar account name, e.g. 'Work'. Defaults to the primary writable one."
                    )
                }
                putJsonObject("attendees") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "Attendee email addresses. On a synced Google calendar this sends real invites."
                    )
                }
                putJsonObject("recurrence") {
                    put("type", "string")
                    put("description", "Optional repeat frequency: daily, weekly, monthly, or yearly.")
                }
                putJsonObject("recurrence_count") {
                    put("type", "integer")
                    put(
                        "description",
                        "Number of occurrences (with recurrence). Omit for recurrence_until or indefinite."
                    )
                }
                putJsonObject("recurrence_until") {
                    put("type", "string")
                    put(
                        "description",
                        "Last occurrence date (with recurrence), same format as start. Omit for recurrence_count or indefinite."
                    )
                }
            }
            putJsonArray("required") {
                add("title")
                add("start")
            }
        }
    )

    val checkAvailability = tool(
        "check_availability",
        "Real free/busy availability from the connected Google or Outlook account. Returns " +
            "merged busy blocks plus free slots of at least duration_minutes.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days_ahead") {
                    put("type", "integer")
                    put("description", "How many days ahead to check (1-365). Default 7.")
                }
                putJsonObject("from_date") {
                    put("type", "string")
                    put("description", "Start date, e.g. '2026-01-01'. Overrides days_ahead.")
                }
                putJsonObject("to_date") {
                    put("type", "string")
                    put("description", "End date, e.g. '2026-01-31'. Defaults to one day after from_date.")
                }
                putJsonObject("duration_minutes") {
                    put("type", "integer")
                    put(
                        "description",
                        "Minimum length of a usable free slot, in minutes. Default 30."
                    )
                }
            }
        }
    )

    val editCalendarEvent = tool(
        "edit_calendar_event",
        "Update an event on the phone's own calendar; omit a field to keep it. Needs a bare " +
            "numeric event_id from list_calendar_events, not a 'gcal:'/'ms:' id.",
        schema {
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "integer")
                    put("description", "Event ID from list_calendar_events.")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "New title.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "New start as 'yyyy-MM-dd HH:mm' or epoch millis.")
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("description", "New end time.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "New location.")
                }
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "New description.")
                }
                putJsonObject("all_day") {
                    put("type", "boolean")
                    put("description", "Set or unset all-day.")
                }
                putJsonObject("reminder_minutes") {
                    put("type", "integer")
                    put("description", "New reminder in minutes before the event; -1 removes it.")
                }
                putJsonObject("attendees") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Replaces the attendee list with these email addresses.")
                }
                putJsonObject("recurrence") {
                    put("type", "string")
                    put("description", "New repeat frequency: daily, weekly, monthly, or yearly.")
                }
                putJsonObject("recurrence_count") {
                    put("type", "integer")
                    put("description", "Number of occurrences (with recurrence).")
                }
                putJsonObject("recurrence_until") {
                    put("type", "string")
                    put("description", "Last occurrence date (with recurrence), same format as start.")
                }
            }
            putJsonArray("required") { add("event_id") }
        }
    )

    val deleteCalendarEvent = tool(
        "delete_calendar_event",
        "Permanently delete an event from the phone's own calendar. Requires explicit user " +
            "confirmation (destructive action). Get the event ID from list_calendar_events first — " +
            "bare numeric device ids only, not 'gcal:'/'ms:' ids from a connected account.",
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
        "Set an alarm, in the system clock app when one is available and in-app otherwise. " +
            "Returns an alarm ID for edit_alarm/delete_alarm.",
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
                    put(
                        "description",
                        "Repeating days: e.g. ['mon','wed','fri'] or ['weekdays']. Omit for one-time alarm."
                    )
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
        "Start a countdown timer, in-app by default (a notification fires when done). " +
            "Returns a timer ID for delete_timer; dismiss_timer stops a ringing one.",
        schema {
            putJsonObject("properties") {
                putJsonObject("seconds") {
                    put("type", "integer")
                    put("description", "Seconds; combine with minutes/hours for longer durations.")
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
                putJsonObject("system") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Start it in the system clock app instead of in-app. Default false."
                    )
                }
            }
            putJsonArray("required") { add("seconds") }
        }
    )

    val showAlarms = tool(
        "show_alarms",
        "Open the system clock app's alarms list so the user can see all alarms, including ones " +
            "not created by this assistant.",
        schema { putJsonObject("properties") {} }
    )

    val snoozeAlarm = tool(
        "snooze_alarm",
        "Snooze the currently ringing alarm. Only has an effect if an alarm is actively ringing " +
            "in the clock app right now.",
        schema {
            putJsonObject("properties") {
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("description", "Snooze duration in minutes. Omit to use the clock app's default.")
                }
            }
        }
    )

    val dismissTimer = tool(
        "dismiss_timer",
        "Dismiss a currently ringing system-clock-app timer. Only has an effect if a timer is " +
            "actively ringing there right now; support varies by clock app. Distinct from " +
            "delete_timer, which only removes a timer this assistant is tracking.",
        schema { putJsonObject("properties") {} }
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
                    put(
                        "description",
                        "Optional: auto-turn off after this many seconds (1-300). Only applies when turning on."
                    )
                }
            }
            putJsonArray("required") { add("on") }
        }
    )

    val setVolume = tool(
        "set_volume",
        "Set a volume stream to a percentage, reporting the previous and new levels.",
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
        "Read a stream's current volume percentage, or every stream's if none is given.",
        schema {
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put(
                        "description",
                        "Which stream to read: media, ring, alarm, notification, call. If omitted, all streams are reported."
                    )
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
                    put(
                        "description",
                        "Vibration length in milliseconds (1-5000). Default 500. Ignored when pattern is set."
                    )
                }
                putJsonObject("intensity") {
                    put("type", "integer")
                    put("description", "Vibration strength 0-100 (0 = none, 100 = max). Default 100.")
                }
                putJsonObject("pattern") {
                    put("type", "string")
                    put(
                        "description",
                        "Predefined pattern: short (100ms), long (1s), double (two quick buzzes), sos (...---...). Overrides duration_ms."
                    )
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
                    put(
                        "description",
                        "If true, request a fresh GPS fix (takes a few seconds). Default false (uses last-known location)."
                    )
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
                    put(
                        "description",
                        "Audio source: 'mic' (default), 'voice' (optimized for speech), or 'camcorder' (wider range)."
                    )
                }
                putJsonObject("max_duration_seconds") {
                    put("type", "integer")
                    put("description", "Auto-stop after this many seconds. 0 or omit for no limit.")
                }
                putJsonObject("output_path") {
                    put("type", "string")
                    put(
                        "description",
                        "Custom file path to save the recording. Defaults to Recordings/recording_{timestamp}.m4a."
                    )
                }
                putJsonObject("quality") {
                    put("type", "string")
                    put(
                        "description",
                        "Recording quality: 'low' (16kHz, 16kbps), 'medium' (44.1kHz, 64kbps), " +
                            "or 'high' (44.1kHz, 192kbps). Default medium."
                    )
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
        "Edit an alarm by ID from list_alarms; omit a field to keep it. For system-clock " +
            "alarms this is best-effort — relay any caveat in the result to the user.",
        schema {
            putJsonObject("properties") {
                putJsonObject("alarm_id") {
                    put("type", "integer")
                    put("description", "Alarm ID from list_alarms.")
                }
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "New hour (0-23).")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "New minute (0-59).")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "New label.")
                }
                putJsonObject("days") {
                    put("type", "array")
                    put("description", "New repeating days; empty array makes it one-time.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("vibrate") {
                    put("type", "boolean")
                    put("description", "New vibrate setting.")
                }
            }
            putJsonArray("required") { add("alarm_id") }
        }
    )

    val deleteAlarm = tool(
        "delete_alarm",
        "Delete an alarm by ID from list_alarms. Destructive — confirm with the user first. " +
            "Best-effort for system-clock alarms (some only disable it); relay any caveat.",
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
        "Cancel a running timer by ID from list_timers. Destructive — confirm with the user first.",
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

    val pdfEdit = tool(
        "pdf_edit",
        "Edit a PDF's page structure on-device: merge several PDFs into one, split one into " +
            "single-page files, extract a page range into a new PDF, delete pages, or rotate pages. " +
            "Also reports page count and page size with operation='info' — call that first when you " +
            "need to know how many pages a file has before choosing a range. Every operation writes a " +
            "NEW file and never modifies the input unless you point 'output' at the same path with " +
            "overwrite=true. It CANNOT edit the text or images inside a page: PDF stores glyphs at " +
            "fixed coordinates, so there is nothing to reflow — to read a PDF's text use read_file " +
            "instead, and tell the user plainly that rewriting page content is not possible. Editing a " +
            "password-protected PDF strips the protection from the copy, so it is refused until you have " +
            "warned the user and pass confirmed=true.",
        schema {
            putJsonObject("properties") {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") { PdfTool.OPERATIONS.forEach { add(it) } }
                    put(
                        "description",
                        "info = page count and size; merge = join 'inputs' in order; split = one file per " +
                            "page; extract_pages = keep 'pages'; delete_pages = drop 'pages'; " +
                            "rotate_pages = turn 'pages' by 'degrees'."
                    )
                }
                putJsonObject("input") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the source PDF. Required by every operation except merge, e.g. " +
                            "'/sdcard/Download/statement.pdf'."
                    )
                }
                putJsonObject("inputs") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "merge only: two or more PDF paths, in the order they should be joined."
                    )
                }
                putJsonObject("output") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the PDF to write. Required by every operation except info and split; " +
                            "for split it is the output DIRECTORY and defaults to the input's folder."
                    )
                }
                putJsonObject("pages") {
                    put("type", "string")
                    put(
                        "description",
                        "1-based page selection: '3', '1-3,7', '9-' (9 to the end) or 'all'. Required by " +
                            "extract_pages and delete_pages; optional for rotate_pages (defaults to all)."
                    )
                }
                putJsonObject("degrees") {
                    put("type", "integer")
                    putJsonArray("enum") {
                        add(90)
                        add(180)
                        add(270)
                    }
                    put("description", "rotate_pages only: clockwise rotation to add, 90, 180 or 270.")
                }
                putJsonObject("password") {
                    put("type", "string")
                    put(
                        "description",
                        "Password for an encrypted PDF. Ask the user for it — never guess. The output is " +
                            "written unencrypted."
                    )
                }
                putJsonObject("confirmed") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Set to true ONLY after telling the user that editing their password-protected PDF " +
                            "writes an unprotected copy, and they agreed. Editing an encrypted PDF fails " +
                            "without it. Never set it pre-emptively."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") { add("operation") }
        }
    )

    val mediaEdit = tool(
        "media_edit",
        "Edit an audio or video file on-device: trim a window out of it, pull its audio track into a " +
            "separate file, strip the audio from a video, shrink it, change its playback speed, or join " +
            "several files end to end. Also reports duration, resolution, tracks and codecs with " +
            "operation='info' — call that FIRST whenever you need a timecode or a resolution, so you are " +
            "working against real numbers rather than a guess. Works the same on video and on audio-only " +
            "files. Every operation writes a NEW file and never modifies the input; the output cannot be the " +
            "input, because the encoder writes it while still reading from it. trim, extract_audio and " +
            "remove_audio copy the streams rather than re-encoding, so they are fast and lossless; compress, " +
            "speed and concat re-encode, so they take much longer and lose a little quality. It cannot add " +
            "music, overlay text or a watermark, blur or remove anything inside a frame, or open " +
            "DRM-protected media at all — say so plainly instead of attempting a workaround.",
        schema {
            putJsonObject("properties") {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") { MediaEditTool.OPERATIONS.forEach { add(it) } }
                    put(
                        "description",
                        "info = duration, tracks, resolution and codecs; trim = keep the window between " +
                            "'start' and 'end'; extract_audio = write the audio track as its own file; " +
                            "remove_audio = write the video with its audio dropped; compress = re-encode " +
                            "smaller at 'height'; speed = re-time by 'speed'; concat = join 'inputs' in order."
                    )
                }
                putJsonObject("input") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the source audio or video file, e.g. '/sdcard/DCIM/Camera/VID_0001.mp4'. " +
                            "Required by every operation except concat, which takes 'inputs' instead."
                    )
                }
                putJsonObject("output") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the file to write. Required by every operation except info. Must end in .mp4 " +
                            "(video) or .m4a (audio only) — the muxer always writes an MP4 container. Cannot be " +
                            "the same path as 'input'."
                    )
                }
                putJsonObject("start") {
                    put("type", "string")
                    put(
                        "description",
                        "trim only: where the kept window begins. Timecodes are '1:23' (m:ss), '1:02:03' " +
                            "(h:mm:ss), '90s', '1m30s' or plain seconds like '12.5'. Defaults to the start of " +
                            "the file."
                    )
                }
                putJsonObject("end") {
                    put("type", "string")
                    put(
                        "description",
                        "trim only: where the kept window ends, in the same timecode formats, or 'end' for the " +
                            "end of the file. Defaults to the end of the file. Give at least one of 'start' " +
                            "and 'end'."
                    )
                }
                putJsonObject("inputs") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "concat only: two or more media paths, in the order they should be joined. They must " +
                            "all be video or all be audio-only."
                    )
                }
                putJsonObject("height") {
                    put("type", "integer")
                    put(
                        "description",
                        "compress only: target height in pixels, e.g. 720 or 480. Defaults to 720. Width " +
                            "follows the original aspect ratio. Compressing something already this small is " +
                            "refused."
                    )
                }
                putJsonObject("speed") {
                    put("type", "number")
                    put(
                        "description",
                        "speed only: playback multiplier between 0.25 and 4.0. 2.0 plays twice as fast and " +
                            "halves the duration; 0.5 plays at half speed. Audio and video are re-timed " +
                            "together."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") { add("operation") }
        }
    )

    val mediaConvert = tool(
        "media_convert",
        "Convert an audio file from one format to another on-device, using Termux's ffmpeg. This is the ONLY " +
            "way to produce an MP3: Android ships no MP3 encoder, so media_edit physically cannot write one. " +
            "Targets: .mp3, .m4a, .aac, .ogg, .opus, .wav, .flac — the format is taken from the output " +
            "extension. The file never leaves the device. Needs Termux installed with the ffmpeg package; if " +
            "it is missing the error says exactly what to install. For everything other than format — " +
            "trimming, muting, compressing, changing speed, joining — use media_edit, which needs no setup. " +
            "This does not convert video containers.",
        schema {
            putJsonObject("properties") {
                putJsonObject("input") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the source audio or video file to take the audio from, e.g. " +
                            "'/sdcard/Music/recording.m4a'. Must be on shared storage — Termux cannot see " +
                            "Gotcha's private folders."
                    )
                }
                putJsonObject("output") {
                    put("type", "string")
                    put(
                        "description",
                        "Path to write, its extension choosing the format: '.mp3', '.m4a', '.aac', '.ogg', " +
                            "'.opus', '.wav' or '.flac'. Must be on shared storage and cannot be the input."
                    )
                }
                putJsonObject("bitrate") {
                    put("type", "string")
                    put(
                        "description",
                        "Audio bitrate as kbps, e.g. '192k' or '320k'. Defaults to 192k (128k for Opus). " +
                            "Ignored for .wav and .flac, which are lossless."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") {
                add("input")
                add("output")
            }
        }
    )

    val synthesizePodcast = tool(
        "synthesize_podcast",
        "Turn a written narration script into a spoken audio file — a single-voice podcast — saved under " +
            "Gotcha/Podcasts on shared storage, where the Files app can see it. Write the script yourself " +
            "first as plain prose (no markdown, no code; it will be read aloud verbatim), then pass it here. " +
            "Long scripts are synthesized in segments and joined on-device; ~20 minutes of speech is the " +
            "ceiling. Output is .m4a by default; format='mp3' converts through Termux's ffmpeg when Termux " +
            "is available and quietly falls back to .m4a when it is not. Needs an API-based TTS provider " +
            "(Samosa AI or External API) in Settings → Speech — Android's built-in TTS cannot write files, " +
            "and the error will say so.",
        schema {
            putJsonObject("properties") {
                putJsonObject("script") {
                    put("type", "string")
                    put(
                        "description",
                        "The full narration text, exactly as it should be spoken. Plain prose only — write " +
                            "for the ear, not the eye."
                    )
                }
                putJsonObject("output_name") {
                    put("type", "string")
                    put(
                        "description",
                        "Base name for the file, e.g. 'morning-news-digest'. It is slugified and written " +
                            "under Gotcha/Podcasts; any directory part or extension is ignored except that " +
                            "a '.mp3' extension selects the mp3 format."
                    )
                }
                putJsonObject("model") {
                    put("type", "string")
                    put(
                        "description",
                        "TTS model id to use. Defaults to the model configured in Settings → Speech."
                    )
                }
                putJsonObject("voice") {
                    put("type", "string")
                    put(
                        "description",
                        "Voice id from the TTS model's voice list. Defaults to the configured voice, then " +
                            "to the model's own default."
                    )
                }
                putJsonObject("format") {
                    put("type", "string")
                    put(
                        "description",
                        "'m4a' (default, works with no setup) or 'mp3' (needs Termux's ffmpeg; degrades to " +
                            ".m4a with a note when unavailable)."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") {
                add("script")
                add("output_name")
            }
        }
    )

    val synthesizePodcastDialogue = tool(
        "synthesize_podcast_dialogue",
        "Turn a two-host dialogue script into a spoken audio file — a conversational podcast in the " +
            "NotebookLM style — saved under Gotcha/Podcasts on shared storage. Write the dialogue yourself " +
            "first: an array of turns, each with speaker 'A' or 'B' and the exact words to speak, as plain " +
            "prose. The hosts get distinct voices (configured in Settings → Speech, overridable per call, " +
            "and defaulting to two different voices from the model's own list). You also direct the pacing, " +
            "and should: give every turn its own pause_ms for the silence that follows it, varying them the " +
            "way a real conversation does — an interjection lands almost on top of the previous line, a " +
            "revelation earns a beat first. gap_ms is only the fallback for turns you leave unpaced, so a " +
            "script with no pause_ms at all comes out evenly spaced and lifeless. Length ceiling, output " +
            "formats and the TTS provider requirement are the same as " +
            "synthesize_podcast: ~20 minutes, .m4a by default or format='mp3' via Termux's ffmpeg, and an " +
            "API-based TTS provider — Android's built-in TTS cannot write files. Turns whose text is blank " +
            "after sanitization (e.g. only markdown or emoji) are dropped rather than synthesized as silence.",
        schema {
            putJsonObject("properties") {
                putJsonObject("lines") {
                    put("type", "array")
                    put(
                        "description",
                        "The dialogue in order. Each item is one turn: " +
                            "{\"speaker\": \"A\" or \"B\", \"text\": \"the words to speak\", " +
                            "\"pause_ms\": the silence after it}. Turns whose text sanitizes to blank " +
                            "(e.g. only markdown or emoji) are dropped rather than synthesized as silence."
                    )
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("speaker") {
                                put("type", "string")
                                put("description", "'A' or 'B' — which host speaks this turn.")
                            }
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "What this host says, spoken verbatim. Plain prose only.")
                            }
                            putJsonObject("pause_ms") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Silence AFTER this turn, in milliseconds. Set it on every turn: this " +
                                        "rhythm is most of what separates a real conversation from two " +
                                        "voices taking turns, and an episode where every gap is the same " +
                                        "length sounds like a machine reading a transcript. Choose each " +
                                        "number from what just happened and what comes next — 0-150 when " +
                                        "the other host cuts in or finishes the thought, 200-300 for " +
                                        "ordinary back-and-forth, 400-600 after a question, a joke, or a " +
                                        "point that needs a moment to land, 700-1200 at a topic change or " +
                                        "something genuinely weighty. Vary it: several turns in a row at " +
                                        "the same value means you are not really pacing. Clamped to " +
                                        "0-2000, and ignored on the final turn, where it would only be " +
                                        "trailing silence."
                                )
                            }
                        }
                        // pause_ms is required of the *model* — asking politely in the description was
                        // not enough, and an unpaced script is the flat-sounding failure this field
                        // exists to prevent. The executor still tolerates its absence and falls back to
                        // gap_ms, so a provider that ignores `required` degrades rather than breaks.
                        putJsonArray("required") {
                            add("speaker")
                            add("text")
                            add("pause_ms")
                        }
                    }
                }
                putJsonObject("gap_ms") {
                    put("type", "integer")
                    put(
                        "description",
                        "Fallback silence between turns, in milliseconds (0-2000, default 300), used only " +
                            "for turns that carry no pause_ms of their own. It is a safety net, not the " +
                            "way to pace an episode — set pause_ms per turn instead."
                    )
                }
                putJsonObject("output_name") {
                    put("type", "string")
                    put(
                        "description",
                        "Base name for the file, e.g. 'ai-news-roundtable'. Slugified and written under " +
                            "Gotcha/Podcasts; a '.mp3' extension selects the mp3 format."
                    )
                }
                putJsonObject("host_a_voice") {
                    put("type", "string")
                    put("description", "Voice id for host A. Defaults to the configured podcast host A voice.")
                }
                putJsonObject("host_b_voice") {
                    put("type", "string")
                    put(
                        "description",
                        "Voice id for host B. Defaults to the configured podcast host B voice, then to the " +
                            "first voice in the model's list that differs from host A."
                    )
                }
                putJsonObject("host_a_model") {
                    put("type", "string")
                    put("description", "TTS model id for host A. Defaults to the configured TTS model.")
                }
                putJsonObject("host_b_model") {
                    put("type", "string")
                    put("description", "TTS model id for host B. Defaults to host A's model.")
                }
                putJsonObject("format") {
                    put("type", "string")
                    put(
                        "description",
                        "'m4a' (default, works with no setup) or 'mp3' (needs Termux's ffmpeg; degrades to " +
                            ".m4a with a note when unavailable)."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") {
                add("lines")
                add("output_name")
            }
        }
    )

    val transcribeFile = tool(
        "transcribe_file",
        "Transcribe an audio file already on disk to text, using the configured Speech-to-Text API. The " +
            "file is read and uploaded to the STT endpoint but never modified or deleted. Accepts .m4a, " +
            ".mp3, .wav, .ogg, .opus, .flac, .aac, .mp4 and .webm up to 25MB — trim or split longer " +
            "recordings with media_edit first. This is how a saved voice memo becomes text: record with " +
            "start_audio_recording using an explicit output_path (without one the recording lands behind a " +
            "MediaStore URI this tool cannot reach), stop it, then transcribe the file. Needs an API-based " +
            "STT provider (Samosa AI or External API) in Settings → Speech — Android's SpeechRecognizer " +
            "only listens to the microphone.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the audio file to transcribe, e.g. '/sdcard/Gotcha/Recordings/memo.m4a'."
                    )
                }
                putJsonObject("model") {
                    put("type", "string")
                    put(
                        "description",
                        "STT model id to use. Defaults to the model configured in Settings → Speech, then " +
                            "to the first STT model the API advertises."
                    )
                }
                putJsonObject("language") {
                    put("type", "string")
                    put(
                        "description",
                        "ISO language hint for the recording, e.g. 'en' or 'hi'. Defaults to the " +
                            "configured STT language; leave unset to let the model detect it."
                    )
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val sharePodcast = tool(
        "share_podcast",
        "Open the system share sheet for an audio file under the Gotcha folder — a generated podcast, a " +
            "recording, a converted track — so the user can send it through any app they choose. Nothing is " +
            "sent by this call itself: it only presents the chooser, and the user picks (or dismisses) the " +
            "destination. Accepts .m4a, .mp3, .wav, .ogg, .opus, .flac, .aac and .mp4 audio. The file must " +
            "live under the Gotcha folder on shared storage (where synthesize_podcast writes); files " +
            "elsewhere must be copied there first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the audio file to share, e.g. '/sdcard/Gotcha/Podcasts/morning-digest.m4a'."
                    )
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val edit = tool(
        "edit",
        "Replace exact text in a file, for surgical edits without rewriting it. oldString " +
            "must match exactly, whitespace included. Writable roots only (app sandbox, storage).",
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
            putJsonArray("required") {
                add("path")
                add("oldString")
                add("newString")
            }
        }
    )

    val question = tool(
        "question",
        "Ask the user for a decision or clarification instead of guessing their intent. " +
            "Offer concise options where you can.",
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
        "Track your own plan for this session; each call replaces the whole list. " +
            "status: pending, in_progress, completed, cancelled. priority: high, medium, low.",
        schema {
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    put("description", "List of tasks to track.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Task description.")
                            }
                            putJsonObject(
                                "status"
                            ) {
                                put(
                                    "type",
                                    "string"
                                )
                                put("description", "One of: pending, in_progress, completed, cancelled.")
                            }
                            putJsonObject(
                                "priority"
                            ) {
                                put("type", "string")
                                put("description", "Optional: high, medium, low.")
                            }
                        }
                        putJsonArray("required") {
                            add("content")
                            add("status")
                        }
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
        "Delegate a multi-step task to the General sub-agent, which runs independently with " +
            "the Operator tools and returns a final report. No recursive delegation.",
        schema {
            putJsonObject("properties") {
                putJsonObject("description") {
                    put("type", "string")
                    put(
                        "description",
                        "Brief description of the task, shown in the UI while it runs."
                    )
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put(
                        "description",
                        "Full instructions, including all context the sub-agent needs."
                    )
                }
                putJsonObject("subagent_type") {
                    put("type", "string")
                    put(
                        "description",
                        "Which sub-agent to use. Only 'general' (the default) exists."
                    )
                }
            }
            putJsonArray("required") {
                add("description")
                add("prompt")
            }
        }
    )

    val ask_final_answer = tool(
        "ask_final_answer",
        "Report the final result of the delegated task. Call it only once every step is " +
            "actually done, never before.",
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

    val finishTask = tool(
        "finish_task",
        "End the turn and report the outcome to the user. This is the only way to be sure the " +
            "user actually hears the result: it stops the tool loop, shows your summary, and " +
            "reads it aloud when the reply is spoken. Call it as soon as the work is done — " +
            "especially after task or navigate_app comes back successful, instead of delegating " +
            "again to double-check. Use it for failures too, saying what went wrong.",
        schema {
            putJsonObject("properties") {
                putJsonObject("summary") {
                    put("type", "string")
                    put(
                        "description",
                        "What to tell the user: what was done or what went wrong. One or two " +
                            "sentences, phrased to be read aloud."
                    )
                }
            }
            putJsonArray("required") { add("summary") }
        }
    )

    val glob = tool(
        "glob",
        "Find files by glob pattern (* non-/, ** recursive, ? one char), returning paths " +
            "relative to the search root.",
        schema {
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Glob pattern, e.g. '**/*.txt' or 'downloads/*.jpg'.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Root and optional sub-path, e.g. 'files' or 'downloads/reports'.")
                }
            }
            putJsonArray("required") {
                add("pattern")
                add("path")
            }
        }
    )

    val grep = tool(
        "grep",
        "Search file contents by case-insensitive regex, returning path, line number and " +
            "line content.",
        schema {
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Regular expression pattern to search for (case-insensitive).")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Root and optional sub-path, e.g. 'files' or 'downloads/reports'.")
                }
                putJsonObject("include") {
                    put("type", "string")
                    put("description", "Optional file-name filter glob, e.g. '*.txt', '*.kt', '*.{json,xml}'.")
                }
            }
            putJsonArray("required") {
                add("pattern")
                add("path")
            }
        }
    )

    val webSearch = tool(
        "websearch",
        "Search the web (DuckDuckGo), returning up to 10 titles, URLs and snippets. " +
            "For a result's full content, follow up with webfetch on its URL.",
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
                    put(
                        "description",
                        "Optional output format: 'text' (default) or 'markdown'. Currently always returns text."
                    )
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
        "Tap an on-screen element by its text/label, or by coordinates. Prefer text.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Visible text/label of the element to tap (case-insensitive, partial match).")
                }
                putJsonObject("x") {
                    put("type", "integer")
                    put("description", "X coordinate, in [0,1000] space unless normalized=false.")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Y coordinate, in [0,1000] space unless normalized=false.")
                }
                putJsonObject("normalized") {
                    put("type", "boolean")
                    put("description", "x and y are in [0,1000] space. Default true.")
                }
            }
        }
    )

    val swipe = tool(
        "swipe",
        "Swipe or scroll the screen or one element. 'down' reveals lower content, " +
            "'up' reveals higher content.",
        schema {
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "One of: up, down, left, right.")
                }
                putJsonObject("index") {
                    put("type", "integer")
                    put("description", "Optional index of the UI element to swipe on.")
                }
                putJsonObject("x1") {
                    put("type", "integer")
                    put("description", "Start X (with y1,x2,y2).")
                }
                putJsonObject("y1") {
                    put("type", "integer")
                    put("description", "Start Y.")
                }
                putJsonObject("x2") {
                    put("type", "integer")
                    put("description", "End X.")
                }
                putJsonObject("y2") {
                    put("type", "integer")
                    put("description", "End Y.")
                }
                putJsonObject("normalized") {
                    put("type", "boolean")
                    put("description", "Coordinates are in [0,1000] space. Default true.")
                }
            }
        }
    )

    val tapIndex = tool(
        "tap_index",
        "Tap a UI element by its number in the screen observation's element list. " +
            "More precise than a coordinate tap — prefer it.",
        schema {
            putJsonObject("properties") {
                putJsonObject("index") {
                    put("type", "integer")
                    put("description", "Element index from the UI Elements list.")
                }
            }
            putJsonArray("required") { add("index") }
        }
    )

    val longPress = tool(
        "long_press",
        "Perform a long-press gesture on a specific on-screen UI element or explicit coordinates.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Visible text/label of the element to long press.")
                }
                putJsonObject("x") {
                    put("type", "integer")
                    put("description", "X coordinate, in [0,1000] space unless normalized=false.")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Y coordinate, in [0,1000] space unless normalized=false.")
                }
                putJsonObject("normalized") {
                    put("type", "boolean")
                    put("description", "x and y are in [0,1000] space. Default true.")
                }
            }
        }
    )

    val longPressIndex = tool(
        "long_press_index",
        "Perform a long-press gesture on a UI element from the numbered elements list shown in the screen observation.",
        schema {
            putJsonObject("properties") {
                putJsonObject("index") {
                    put("type", "integer")
                    put("description", "Index of the element to long press (from the ── UI Elements ── list).")
                }
            }
            putJsonArray("required") { add("index") }
        }
    )

    val pressKey = tool(
        "press_key",
        "Press a system key or perform a navigation action. " +
            "Note: 'enter' may not successfully submit forms or searches on all apps. It is much " +
            "more reliable to use tap or tap_index to click the on-screen 'Search', 'Submit', " +
            "or 'Go' button instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", "string")
                    put(
                        "description",
                        "One of: enter, back, home, recents, notifications, quick_settings, lock_screen."
                    )
                }
            }
            putJsonArray("required") { add("key") }
        }
    )

    val navigateApp = tool(
        "navigate_app",
        "Navigate inside apps step by step — open the app, tap, swipe, type, and scroll " +
            "until the task is done. Provide clear numbered steps as the task description. " +
            "Only available to Operator mode. Cannot be called from within a sub-agent.",
        schema {
            putJsonObject("properties") {
                putJsonObject("task") {
                    put("type", "string")
                    put(
                        "description",
                        "Step-by-step instructions of what to do. Include the app name, what to tap, " +
                            "what to type, and what to report back. Using numbered steps improves accuracy.\n\n" +
                            "Example:\n" +
                            "1. Open the Google Play Store app\n" +
                            "2. Tap on the search bar at the top\n" +
                            "3. Type 'Spotify' and press enter\n" +
                            "4. Tap on the Spotify result\n" +
                            "5. Scroll down to see the rating\n" +
                            "6. Report the app rating and number of downloads"
                    )
                }
            }
            putJsonArray("required") { add("task") }
        }
    )

    val inputText = tool(
        "input_text",
        "Type text into a text field via the accessibility service. You can optionally target an " +
            "element directly by providing its index. If no index is provided, it types into the currently " +
            "focused field. Needs the accessibility service enabled.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to type into the focused field.")
                }
                putJsonObject("index") {
                    put("type", "integer")
                    put("description", "Optional index of the text field to type into.")
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
        "Read the currently active notifications from all apps (app, time, title/text/expanded " +
            "text, and a dismiss key). Needs Notification access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent notifications to return (1-50). Default 15.")
                }
                putJsonObject("app") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional filter: only notifications from this app (matches package name " +
                            "or app label, e.g. 'gmail' or 'com.google.android.gm')."
                    )
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
        "Control a media session in whichever app is playing — Spotify, YouTube Music, a " +
            "podcast player, anything with a media notification. Works without any per-service " +
            "API or account. When several apps hold a session, the one actually playing is " +
            "chosen; pass 'app' to target a specific one. Needs Notification access. " +
            "Use get_now_playing first if you need to know what is playing.",
        schema {
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put(
                        "description",
                        "One of: play, pause, toggle, next, previous, stop, seek, fast_forward, rewind."
                    )
                }
                putJsonObject("app") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional: target this app specifically (matches package name or app " +
                            "label, e.g. 'spotify'). Omit to use whichever app is playing."
                    )
                }
                putJsonObject("position_seconds") {
                    put("type", "integer")
                    put("description", "Required for action='seek': position to jump to, in seconds.")
                }
            }
            putJsonArray("required") { add("action") }
        }
    )

    val getNowPlaying = tool(
        "get_now_playing",
        "Report what each app with an active media session is playing: app, state, title, " +
            "artist, album, position/duration. Also gives the 'app' value for media_control.",
        schema { putJsonObject("properties") {} }
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

    // ---- Tier 4 additions (privileged / rooted execution) ----

    val checkRoot = tool(
        "check_root",
        "Check whether this device is rooted (a working `su` shell is available). Use this before " +
            "attempting run_root_command or write_secure_settings so you can tell the user if root is missing.",
        schema { putJsonObject("properties") {} }
    )

    val runRootCommand = tool(
        "run_root_command",
        "Run a shell command as root via `su`, for privileged operations an ordinary app " +
            "cannot do. Rooted devices only; device-wiping commands are blocked.",
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

    val runTermuxCommand = tool(
        "run_termux_command",
        "Run a shell command inside Termux, which has a real Linux user-space — `pkg install`/`apt`, " +
            "python3, git, ssh, compilers. Use this for anything that needs a package installed or a " +
            "non-trivial script; it runs headlessly, with no terminal opening in front of the user. " +
            "Only offered when Termux is installed.\n" +
            "Each call is a SEPARATE shell: `cd`, `export`, and activated virtualenvs do NOT carry " +
            "over to your next call, which starts again in Termux's home. Chain dependent steps in " +
            "one command with '&&' (e.g. 'cd proj && ./build.sh') rather than splitting them. " +
            "For scripts use a single heredoc/stdin payload (e.g. `python3 - << 'PY'\\n...\\nPY` or " +
            "`sh -s << 'EOF'\\n...\\nEOF`) via the stdin tool param — prefer stdin over " +
            "write_file to /sdcard for payloads <100KB.\n" +
            "Termux runs under its own user id, so Gotcha's working directory and app files are NOT " +
            "visible here — use run_command for those. Shared storage (/sdcard) is only reachable if " +
            "the user has run `termux-setup-storage` in Termux; if a /sdcard path fails, say so " +
            "rather than retrying.\n" +
            "\nRealistic limits to factor in:\n" +
            "- No interactive prompts — there is no terminal here, so any command that waits for typed input " +
            "(pkg install without -y, ssh password, read, mysql>) blocks until the 600s timeout. Always " +
            "pass -y / --yes / -q where the tool supports them.\n" +
            "- Output is capped at 32KB by Gotcha and ~100KB by Termux. For larger output, redirect to a " +
            "file inside Termux (e.g. `... > /sdcard/Download/out.txt`) and read it back via read_file.\n" +
            "- You cannot kill a command after submitting it. A timed-out command keeps running under " +
            "Termux's UID. There is no kill from here. To stop a stuck one, open the notifications shade " +
            "(global_action or press_key 'notifications') and tap the Exit action on the Termux " +
            "notification, which ends every Termux session. If you cannot reach that, ask the user to tap " +
            "Exit on the Termux notification.\n" +
            "- Lock contention: an `exit 100` whose error names `lock-frontend` / `Could not get lock` / " +
            "`held by process <pid>` means ANOTHER pkg/apt is already running and holds the package lock — " +
            "this is NOT a mirror problem. Wait for it or tap Exit on the Termux notification to end all " +
            "sessions; never delete the lock files or kill -9 the process, which corrupts the package " +
            "database without releasing the lock.\n" +
            "- Interactive prompts: if output shows `Configuration file '...'` with `Y/I/N/O/D/Z`, a " +
            "package manager is waiting for an answer no terminal can provide and will block until the " +
            "timeout. Package operations are run non-interactively by default; if a bare `dpkg " +
            "--configure` still asks, supply the answer via the stdin param (`echo N | dpkg --configure " +
            "<pkg>`).\n" +
            "- Hard ceiling: timeout_seconds <= 600. `pkg install` of ffmpeg, chromium, rust, golang, or " +
            "texlive can hit this — raise timeout_seconds to 600 and warn the user before starting one.\n" +
            "- 4 commands in flight, process-wide. Sub-agents share the same semaphore.\n" +
            "- Android 12+ foreground-service rule: if Gotcha is in the background and the assistive ball " +
            "is off, this tool fails with 'Not allowed to start service'. Tell the user to bring Gotcha to " +
            "the foreground (or switch the assistive ball on).\n" +
            "\nIf `pkg install` hangs at \"0% [Connecting to packages.termux.dev]\" or fails with NO_PUBKEY, " +
            "the default mirror is slow or blocked on the user's network. Tell the user to open Termux and " +
            "run `termux-change-repo` once, then retry. The full mirror list and the non-interactive " +
            "sources.list swap are in the termux_repositories skill.\n" +
            "\nFor long-running processes (servers, watchers, daemons), the foreground process is orphaned " +
            "the moment Gotcha returns. The persistence pattern (`nohup ... > log 2>&1 < /dev/null & echo $! " +
            "> pid; disown`) and the termux-wake-lock requirement are in the termux_background skill. For " +
            "full Debian/Ubuntu via proot-distro (when Termux's bionic/busybox is not enough), see the " +
            "termux_proot skill.",
        schema {
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put(
                        "description",
                        "Shell command line to run in Termux, e.g. 'pkg install python -y' or " +
                            "'python3 -c \"print(1+1)\"'. Pipes and multiple statements are fine."
                    )
                }
                putJsonObject("working_dir") {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path inside Termux's filesystem. Defaults to Termux's home. " +
                            "Applies to this call only — it is not remembered for the next one."
                    )
                }
                putJsonObject("timeout_seconds") {
                    put("type", "integer")
                    put(
                        "description",
                        "Timeout in seconds (1-600). Default 60 — raise it for package installs and builds."
                    )
                }
                putJsonObject("stdin") {
                    put("type", "string")
                    put(
                        "description",
                        "Text piped to the command's standard input. Supply this for anything that would " +
                            "otherwise wait for typed input — there is no terminal to type into, so an " +
                            "unanswered prompt just runs until the timeout. Prefer non-interactive flags " +
                            "(e.g. 'pkg install -y') where they exist."
                    )
                }
            }
            putJsonArray("required") { add("command") }
        }
    )

    val writeSecureSettings = tool(
        "write_secure_settings",
        "Write an Android system/secure/global setting, e.g. secure/location_mode=3. " +
            "Uses root, so it needs a rooted device.",
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

    val searchSkills = tool(
        "search_skills",
        "Search the skills registry for contextual instructions on how to optimally " +
            "interact with a given app or perform a system operation.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put(
                        "description",
                        "The package name, app name, or operation to search for (e.g. 'com.whatsapp', 'whatsapp', 'settings_operations')."
                    )
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    // ---- Email connector tools (menu ▸ Connectors: Gmail BYO-OAuth or IMAP) ----

    val listEmails = tool(
        "list_emails",
        "List inbox emails as [id] read-state | date | from | subject + snippet. " +
            "Use the id with read_email / mark_email_read.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put(
                        "description",
                        "Search text. Full Gmail query syntax when Gmail is the backend " +
                            "(e.g. 'from:alice is:unread'); IMAP matches subject/from/body."
                    )
                }
                putJsonObject("unread_only") {
                    put("type", "boolean")
                    put("description", "Only return unread messages. Default false.")
                }
                putJsonObject("max") {
                    put("type", "integer")
                    put("description", "Max messages to return (1-50). Default 10.")
                }
            }
        }
    )

    val readEmail = tool(
        "read_email",
        "Read one email's full headers and plain-text body by the id returned from list_emails " +
            "(e.g. 'gmail:18c...' or 'imap:INBOX:42'). Does not change the unread state.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Message id from list_emails.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    val sendEmail = tool(
        "send_email",
        "Send a plain-text email from the connected account. The user always sees a " +
            "confirmation dialog before anything is sent, so don't ask separately.",
        schema {
            putJsonObject("properties") {
                putJsonObject("to") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Recipient email addresses.")
                }
                putJsonObject("cc") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Optional CC addresses.")
                }
                putJsonObject("bcc") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Optional BCC addresses.")
                }
                putJsonObject("subject") {
                    put("type", "string")
                    put("description", "Subject line.")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "Plain-text message body.")
                }
            }
            putJsonArray("required") {
                add("to")
                add("subject")
                add("body")
            }
        }
    )

    val markEmailRead = tool(
        "mark_email_read",
        "Mark an email as read or unread by its id from list_emails.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Message id from list_emails.")
                }
                putJsonObject("read") {
                    put("type", "boolean")
                    put("description", "true = mark read (default), false = mark unread.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    val composeEmail = tool(
        "compose_email",
        "Open the user's email app with a pre-filled draft they review and send themselves. " +
            "Only a fallback: whenever send_email is in your tool list, use that instead — " +
            "it sends directly, and it confirms with the user anyway.",
        schema {
            putJsonObject("properties") {
                putJsonObject("to") {
                    put("type", "string")
                    put("description", "Recipient address (optional).")
                }
                putJsonObject("subject") {
                    put("type", "string")
                    put("description", "Subject line (optional).")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "Message body (optional).")
                }
            }
        }
    )

    // ---- Persistent to-do tools (Microsoft To Do connector) ----

    val listTasks = tool(
        "list_tasks",
        "List the user's real Microsoft To Do items, which sync to their other devices — not " +
            "todowrite, your own scratch plan. Rows are [id] state | title | due date.",
        schema {
            putJsonObject("properties") {
                putJsonObject("list") {
                    put("type", "string")
                    put(
                        "description",
                        "Task-list name, e.g. 'Groceries'. Defaults to the account's main list."
                    )
                }
                putJsonObject("include_completed") {
                    put("type", "boolean")
                    put("description", "Include already-completed tasks. Default false.")
                }
                putJsonObject("max") {
                    put("type", "integer")
                    put("description", "Max tasks to return (1-100). Default 25.")
                }
            }
        }
    )

    val createTask = tool(
        "create_task",
        "Add a persistent to-do item to the user's connected Microsoft To Do account, so it " +
            "survives this conversation and syncs to their other devices. Use todowrite instead " +
            "when you only need to track your own steps for the current task.",
        schema {
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "What the task is, e.g. 'Renew passport'.")
                }
                putJsonObject("notes") {
                    put("type", "string")
                    put("description", "Optional longer description.")
                }
                putJsonObject("due_date") {
                    put("type", "string")
                    put("description", "Optional due date as YYYY-MM-DD.")
                }
                putJsonObject("list") {
                    put("type", "string")
                    put("description", "Optional task-list name. Defaults to the account's main list.")
                }
            }
            putJsonArray("required") { add("title") }
        }
    )

    val completeTask = tool(
        "complete_task",
        "Mark a to-do item complete (or reopen it) by the id returned from list_tasks, " +
            "e.g. 'ms:<listId>:<taskId>'.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Task id from list_tasks.")
                }
                putJsonObject("completed") {
                    put("type", "boolean")
                    put("description", "true = mark complete (default), false = reopen.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    // ---- Notion connector tools ----

    val notionSearch = tool(
        "notion_search",
        "Search Notion for pages and databases, returning [id] rows for the other notion " +
            "tools. Only pages shared with the integration are visible, so an empty result " +
            "usually means unshared rather than nonexistent.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Text to match against page titles. Omit to list everything shared.")
                }
                putJsonObject("max") {
                    put("type", "integer")
                    put("description", "Max results to return (1-100). Default 20.")
                }
            }
        }
    )

    val notionReadPage = tool(
        "notion_read_page",
        "Read a Notion page's or database's title and full text content as Markdown, using a " +
            "page or database id from notion_search.",
        schema {
            putJsonObject("properties") {
                putJsonObject("page_id") {
                    put("type", "string")
                    put("description", "Page or database id from notion_search.")
                }
            }
            putJsonArray("required") { add("page_id") }
        }
    )

    val notionCreatePage = tool(
        "notion_create_page",
        "Create a Notion page under an existing one; parent_page_id is always required " +
            "(find it with notion_search). Content accepts Markdown.",
        schema {
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Title of the new page.")
                }
                putJsonObject("parent_page_id") {
                    put("type", "string")
                    put("description", "Id of the page to create this one under, from notion_search.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Optional Markdown body for the new page.")
                }
            }
            putJsonArray("required") {
                add("title")
                add("parent_page_id")
            }
        }
    )

    val notionAppendToPage = tool(
        "notion_append_to_page",
        "Append Markdown content to the end of an existing Notion page. Adds to the page — it " +
            "never replaces or deletes what is already there.",
        schema {
            putJsonObject("properties") {
                putJsonObject("page_id") {
                    put("type", "string")
                    put("description", "Page id from notion_search.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Markdown content to append.")
                }
            }
            putJsonArray("required") {
                add("page_id")
                add("content")
            }
        }
    )

    val notionUpdatePage = tool(
        "notion_update_page",
        "Update a Notion page's — or a database row's — properties. Use for todo lists: mark a " +
            "row done, set its status, rename it, etc. Pass 'properties' as a JSON object of " +
            "column name to a simple value, e.g. {\"Done\": true}, {\"Status\": \"In progress\"}, " +
            "{\"Name\": \"New name\"}. Column names appear in the 'Columns:' line of " +
            "notion_read_page. Use notion_mark_todo for a to_do block on a page instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("page_id") {
                    put("type", "string")
                    put("description", "Page or database-row id from notion_read_page (the id after [row-]).")
                }
                putJsonObject("properties") {
                    put("type", "object")
                    put("description", "JSON object mapping column names to new values, e.g. {\"Done\": true}.")
                }
            }
            putJsonArray("required") {
                add("page_id")
                add("properties")
            }
        }
    )

    val notionMarkTodo = tool(
        "notion_mark_todo",
        "Mark a Notion to_do block as done or not done. Use the id shown after [block-] in " +
            "notion_read_page output. For a todo item stored as a database row, use " +
            "notion_update_page instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("block_id") {
                    put("type", "string")
                    put("description", "Block id from notion_read_page (the id after [block-]).")
                }
                putJsonObject("checked") {
                    put("type", "boolean")
                    put("description", "true to mark done, false to reopen.")
                }
            }
            putJsonArray("required") {
                add("block_id")
                add("checked")
            }
        }
    )

    val notionDeleteItem = tool(
        "notion_delete_item",
        "Delete a Notion item. item_type 'page' moves a page or database row to the trash " +
            "(recoverable; Notion auto-purges trash after ~30 days, permanent deletion is done " +
            "in the Notion app); item_type 'block' permanently deletes a block and its children. " +
            "Use the id after [row-] or [block-] in notion_read_page output.",
        schema {
            putJsonObject("properties") {
                putJsonObject("item_id") {
                    put("type", "string")
                    put("description", "Row or block id from notion_read_page (after [row-] or [block-]).")
                }
                putJsonObject("item_type") {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("page")
                            add("block")
                        }
                    )
                    put("description", "'page' trashes a page/row; 'block' permanently deletes a block.")
                }
            }
            putJsonArray("required") {
                add("item_id")
                add("item_type")
            }
        }
    )

    // ---- Health Connect tools (on-device, read-only) ----

    val getHealthSummary = tool(
        "get_health_summary",
        "Summarise the user's health and fitness data from Health Connect over the last N days: " +
            "steps, distance, active calories, sleep, average/peak/resting heart rate and weight. " +
            "Reads on-device data only — nothing is uploaded and nothing is ever written. Metrics " +
            "with no data are omitted rather than reported as zero.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "How many days back to summarise (1-365). Default 7.")
                }
            }
        }
    )

    val getHealthRecords = tool(
        "get_health_records",
        "List individual Health Connect records of one type, for questions the summary cannot " +
            "answer (e.g. 'which days did I walk over 10000 steps?', 'when did I work out?'). " +
            "Read-only.",
        schema {
            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    put(
                        "description",
                        "Record type: " + HealthTool.RECORD_TYPE_NAMES.joinToString(", ") + "."
                    )
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "How many days back to read (1-365). Default 7.")
                }
            }
            putJsonArray("required") { add("type") }
        }
    )

    val all: List<ToolDefinition> = listOf(
        aboutSamosaAi,
        updateUserProfile,
        dialNumber, getStorageInfo, getBatteryInfo, listFiles, readFile, writeFile,
        openApp, setBrightness, toggleWifi, openSetting,
        setWallpaper, runCommand,
        // Tier 0–2 additions
        callNumber, readCallLog, findContact, addContact, sendSms, readRecentSms,
        listCalendarEvents, createCalendarEvent, editCalendarEvent, deleteCalendarEvent,
        checkAvailability,
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
        // Terminal signal for the top-level agent (see AgentEngine's FINISH_TASK: marker)
        finishTask,
        // Task tracking
        todowrite,
        // Surgical file editing (Operator only)
        edit,
        // PDF page-structure editing (Operator only)
        pdfEdit,
        // Audio/video editing (Operator only)
        mediaEdit,
        // Audio format conversion via Termux ffmpeg (Operator only)
        mediaConvert,
        // Script-to-speech podcast synthesis (Operator only)
        synthesizePodcast, synthesizePodcastDialogue, sharePodcast,
        // Audio-file transcription via the STT API (Operator only)
        transcribeFile,
        // Content search and file discovery
        glob, grep,
        // Web search + fetch
        webSearch, webFetch,
        // DEPRECATED: readImage excluded from the active catalog — use read_file instead.
        // Tier 3 additions
        readScreen, readScreenRaw, tap, longPress, swipe, tapIndex, longPressIndex, pressKey, inputText, globalAction,
        navigateApp,
        readNotifications, dismissNotifications, mediaControl, getNowPlaying,
        showOverlay, hideOverlay,
        lockScreen, disableCamera, setPasswordPolicy,

        // Tier 4 additions: privileged / rooted execution
        checkRoot, runRootCommand, runTermuxCommand, writeSecureSettings,

        searchSkills,

        // Email connector tools
        listEmails, readEmail, sendEmail, markEmailRead, composeEmail,

        // Persistent to-do connector tools
        listTasks, createTask, completeTask,

        // Notion connector tools
        notionSearch, notionReadPage, notionCreatePage, notionAppendToPage,
        notionUpdatePage, notionMarkTodo, notionDeleteItem,

        // Health Connect (on-device, read-only)
        getHealthSummary, getHealthRecords,

        // Clock enhancements
        showAlarms, snoozeAlarm, dismissTimer
    )
}
