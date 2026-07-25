package com.gotcha.tools

import com.gotcha.llm.FunctionDefinition
import com.gotcha.llm.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
                    put(
                        "description",
                        "Working directory for the command (absolute path). Defaults to the app's working directory."
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
                    put(
                        "description",
                        "SIM slot to use: 'sim1' or 'sim2'. Only needed on dual-SIM devices. Default is the system default."
                    )
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
                    put(
                        "description",
                        "Schedule future delivery: ISO-8601 timestamp like '2026-01-15T14:30:00' or epoch millis. Omit for immediate send."
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
        "List calendar events within a date range. Supports looking ahead (days_ahead) or explicit " +
            "from/to dates, title keyword search, and shows status, description preview, and calendar name. " +
            "Reads the phone's own calendars by default; set source to read a connected Google or " +
            "Outlook account instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put(
                        "description",
                        "Which calendar to read: 'device' (default, the phone's own calendars), " +
                            "'google' or 'microsoft' (needs that connector in Settings)."
                    )
                }
                putJsonObject("days_ahead") {
                    put("type", "integer")
                    put(
                        "description",
                        "How many days ahead to include (1-365). Default 7 when from_date/to_date not set."
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
        "Add an event to a calendar. Supports optional description, all-day flag, reminder, " +
            "and calendar selection. Writes to the phone's primary writable calendar by default; " +
            "set source to write to a connected Google or Outlook account instead.",
        schema {
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put(
                        "description",
                        "Where to create it: 'device' (default), 'google' or 'microsoft' " +
                            "(needs that connector in Settings). all_day, reminder_minutes and " +
                            "recurrence apply to source='device' only."
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
                        "Calendar account name to add the event to (e.g. 'Work', 'Personal'). Defaults to the primary writable calendar."
                    )
                }
                putJsonObject("attendees") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "Optional attendee email addresses. On a synced Google calendar this sends real invites."
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
        "Find when the user is busy and free in a date range, using the free/busy data of a " +
            "connected Google Calendar or Outlook account. This is the only way to get real " +
            "availability — the phone's own calendar copy cannot answer it. Returns merged busy " +
            "blocks plus free slots of at least duration_minutes. Requires a connected account; " +
            "for on-device calendars fall back to list_calendar_events.",
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
        "Update an existing calendar event on the phone's own calendar. Only the fields you " +
            "provide will be changed. Get the event ID from list_calendar_events first — this " +
            "works on bare numeric device ids only, not on 'gcal:'/'ms:' ids from a connected " +
            "account.",
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
                    put(
                        "description",
                        "New reminder before event in minutes. -1 to remove reminder. Omit to keep current."
                    )
                }
                putJsonObject("attendees") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Replace the attendee list with these email addresses. Omit to keep current.")
                }
                putJsonObject("recurrence") {
                    put("type", "string")
                    put("description", "New repeat frequency: daily, weekly, monthly, or yearly. Omit to keep current.")
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
        "Start a countdown timer. By default it's managed in-app (a notification fires when done), " +
            "not in the system clock app. Returns a timer ID that can be used to delete it later. " +
            "List timers with list_timers. Delete with delete_timer (dismiss a ringing system timer " +
            "with dismiss_timer instead).",
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
                putJsonObject("system") {
                    put("type", "boolean")
                    put(
                        "description",
                        "If true, start the timer in the system clock app instead of in-app. " +
                            "Default false."
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
            putJsonArray("required") {
                add("path")
                add("oldString")
                add("newString")
            }
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
                    put(
                        "description",
                        "Brief description of what the sub-agent should do " +
                            "(shown in the UI while it runs)."
                    )
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put(
                        "description",
                        "Detailed instructions for the sub-agent. " +
                            "Include all context the sub-agent needs to complete the task."
                    )
                }
                putJsonObject("subagent_type") {
                    put("type", "string")
                    put(
                        "description",
                        "Which sub-agent to use. Default: 'general'. " +
                            "Available: general."
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
            putJsonArray("required") {
                add("pattern")
                add("path")
            }
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
            putJsonArray("required") {
                add("pattern")
                add("path")
            }
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
                    put("description", "If true, x and y are in [0, 1000] normalized space. Default true.")
                }
            }
        }
    )

    val swipe = tool(
        "swipe",
        "Swipe/scroll the screen or a specific element. " +
            "If using a direction, 'down' means scroll the content down to see lower items. " +
            "'up' means scroll the content up to see higher items. " +
            "Needs the accessibility service enabled.",
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
                    put("description", "If true, coordinate params are in [0, 1000] normalized space. Default true.")
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
                    put("description", "X coordinate. If normalized=true, use [0,1000] space; else absolute pixels.")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Y coordinate. If normalized=true, use [0,1000] space; else absolute pixels.")
                }
                putJsonObject("normalized") {
                    put("type", "boolean")
                    put("description", "If true, x and y are in [0, 1000] normalized space. Default true.")
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
        "Report what every app with an active media session is currently playing: app, playback " +
            "state, title, artist, album, and position/duration. Use this to answer 'what song is " +
            "this?' or to pick the right 'app' value for media_control. Needs Notification access.",
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
                        "The package name, app name, or operation to search for (e.g. 'com.whatsapp', 'whatsapp', 'settings_search')."
                    )
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    // ---- Email connector tools (menu ▸ Connectors: Gmail BYO-OAuth or IMAP) ----

    val listEmails = tool(
        "list_emails",
        "List emails from the connected account's inbox (Gmail or IMAP connector). " +
            "Returns one row per message: [id] read-state | date | from | subject + snippet. " +
            "Use the returned id with read_email / mark_email_read. Requires an email " +
            "connector in Settings; if none is connected, use compose_email or the Gmail app.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional search text. With Gmail connected this is full Gmail query " +
                            "syntax (e.g. 'from:alice is:unread newer_than:7d'); with IMAP it " +
                            "matches subject/from/body."
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
        "Send a plain-text email from the connected account (Gmail or IMAP connector). " +
            "The user always sees a confirmation dialog with recipient, subject and a body " +
            "preview before anything is sent. If no email connector is connected, use " +
            "compose_email instead.",
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
        "Open the user's email app with a pre-filled draft (recipient/subject/body) that they " +
            "review and send themselves. Works without any connector — use this when no email " +
            "account is connected, or when the user wants to review before sending.",
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
        "List the user's real, persistent to-do items from their connected Microsoft To Do " +
            "account. These sync to the user's other devices — this is NOT the same as " +
            "todowrite, which is only your own scratch plan for the current conversation. " +
            "Returns rows of [id] state | title | due date; use the id with complete_task.",
        schema {
            putJsonObject("properties") {
                putJsonObject("list") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional task-list name (e.g. 'Groceries'). Defaults to the account's main list."
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
        "Search the user's Notion workspace for pages and databases. Only pages that have been " +
            "explicitly shared with the integration are visible — an empty result usually means " +
            "the page was never shared, not that it does not exist. Returns [id] rows; use the " +
            "id with notion_read_page or notion_append_to_page.",
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
        "Read a Notion page's title and full text content as Markdown, using a page id from " +
            "notion_search.",
        schema {
            putJsonObject("properties") {
                putJsonObject("page_id") {
                    put("type", "string")
                    put("description", "Page id from notion_search.")
                }
            }
            putJsonArray("required") { add("page_id") }
        }
    )

    val notionCreatePage = tool(
        "notion_create_page",
        "Create a new Notion page underneath an existing one. Notion has no workspace root an " +
            "integration can write to, so a parent_page_id is always required — find one with " +
            "notion_search first. Content accepts Markdown (headings, bullets, numbered lists, " +
            "to-dos, quotes).",
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
        dialNumber, getStorageInfo, getBatteryInfo, listFiles, readFile, writeFile,
        openApp, setBrightness, toggleWifi,
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
        readScreen, readScreenRaw, tap, longPress, swipe, tapIndex, longPressIndex, pressKey, inputText, globalAction,
        navigateApp,
        readNotifications, dismissNotifications, mediaControl, getNowPlaying,
        showOverlay, hideOverlay,
        lockScreen, disableCamera, setPasswordPolicy,

        // Tier 4 additions: privileged / rooted execution
        checkRoot, runRootCommand, writeSecureSettings,

        searchSkills,

        // Email connector tools
        listEmails, readEmail, sendEmail, markEmailRead, composeEmail,

        // Persistent to-do connector tools
        listTasks, createTask, completeTask,

        // Notion connector tools
        notionSearch, notionReadPage, notionCreatePage, notionAppendToPage,

        // Health Connect (on-device, read-only)
        getHealthSummary, getHealthRecords,

        // Clock enhancements
        showAlarms, snoozeAlarm, dismissTimer
    )
}
