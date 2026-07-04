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
        "Open the phone dialer pre-filled with a phone number. The user still has to press call.",
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
            "'external' (app external files), 'downloads', 'pictures', 'dcim', 'documents'. " +
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
        "Read a text file from an allowed directory (same roots as list_files). Output is capped.",
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
        "Write or append a text file inside the app sandbox (roots 'files', 'cache', 'external' only).",
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

    val all: List<ToolDefinition> = listOf(
        dialNumber, getStorageInfo, getBatteryInfo, listFiles, readFile, writeFile,
        clearAppCache, openApp, toggleDarkMode, setBrightness, toggleWifi,
        setWallpaper, runCommand
    )
}
