/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.data.PluginDataStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件沙箱
 *
 * 使用 QuickJS 在隔离环境中执行插件代码。
 *
 * 与上游的区别：上游基于 com.whl.quickjs.wrapper.QuickJSContext，本分支使用项目已有的
 * com.dokar.quickjs（quickjs-kt）。dokar3 的 evaluate / 部分宿主回调是 suspend 的，
 * 因此本类的所有入口都必须在 PluginLoader 的单线程 pluginDispatcher 上调用，
 * 以保证 QuickJS 单线程模型下的串行语义与上游一致。
 */
class PluginSandbox(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val dataStore: PluginDataStore? = null,
) {
    companion object {
        private const val TAG = "PluginSandbox"
        private const val FETCH_TIMEOUT_SECONDS = 15L
        private const val MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
        private const val MAX_STACK_BYTES = 256L * 1024
        private const val EVALUATION_TIMEOUT_MILLIS = 30_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 插件允许访问的网络域名白名单。
     * 由宿主在加载插件时根据 manifest.allowedHosts 注入。
     * 空列表表示禁止所有外部网络请求。
     */
    var allowedHosts: List<String> = emptyList()

    private var quickJs: QuickJs? = null

    private val exportedFunctionNames = mutableSetOf<String>()

    /**
     * 初始化沙箱
     *
     * 必须在 pluginDispatcher 上下文内调用（PluginLoader.loadPlugin 已保证）。
     */
    suspend fun initialize() {
        if (quickJs != null) return

        Log.d(TAG, "Initializing QuickJS sandbox")

        val runtime = QuickJs.create(Dispatchers.Default).apply {
            memoryLimit = MEMORY_LIMIT_BYTES
            maxStackSize = MAX_STACK_BYTES
            evaluationTimeoutMillis = EVALUATION_TIMEOUT_MILLIS
        }

        // 原生桥接函数（宿主回调）
        runtime.function("__nativeFetch") { args ->
            val url = args.getOrNull(0) as? String ?: ""
            val optionsJson = args.getOrNull(1) as? String ?: "{}"
            try {
                nativeFetch(url, optionsJson)
            } catch (e: Exception) {
                Log.e(TAG, "Native fetch error: url=$url", e)
                errorJson(e.message ?: "Unknown error")
            }
        }

        runtime.function("__dataStoreBridge") { args ->
            val action = args.getOrNull(0) as? String ?: ""
            val paramsJson = args.getOrNull(1) as? String ?: "{}"
            try {
                nativeDataStoreBridge(action, paramsJson)
            } catch (e: Exception) {
                Log.e(TAG, "DataStore bridge error: action=$action, params=$paramsJson", e)
                errorJson(e.message ?: "Unknown error")
            }
        }

        runtime.function("__musicPlayerBridge") { args ->
            val action = args.getOrNull(0) as? String ?: ""
            val paramsJson = args.getOrNull(1) as? String ?: "{}"
            try {
                nativeMusicPlayerBridge(action, paramsJson)
            } catch (e: Exception) {
                Log.e(TAG, "MusicPlayer bridge error: action=$action", e)
                errorJson(e.message ?: "Unknown error")
            }
        }

        runtime.function("__consoleLog") { args ->
            val level = args.getOrNull(0) as? String ?: "log"
            val message = args.getOrNull(1) as? String ?: ""
            when (level) {
                "error" -> Log.e(TAG, "JS $message")
                "warn" -> Log.w(TAG, "JS $message")
                else -> Log.d(TAG, "JS $message")
            }
            ""
        }

        runtime.evaluate<Unit>(BOOTSTRAP_JS + "\nvoid 0;")

        quickJs = runtime
        Log.d(TAG, "QuickJS sandbox initialized")
    }

    /**
     * 执行 JS 文件
     */
    suspend fun evaluateFile(file: File) {
        val runtime = requireNotNull(quickJs) { "Sandbox not initialized" }
        Log.d(TAG, "Evaluating JS file: ${file.name}")

        var code = file.readText()

        // 插件按同步风格书写：把 async function / await 预处理掉，保持与上游一致
        val asyncRegex = Regex("""\basync\s+function\b""")
        if (asyncRegex.containsMatchIn(code)) {
            Log.d(TAG, "Preprocessing: converting async functions to sync functions")
            code = asyncRegex.replace(code, "function")
        }

        val awaitRegex = Regex("""\bawait\s+""")
        if (awaitRegex.containsMatchIn(code)) {
            Log.d(TAG, "Preprocessing: removing await keywords")
            code = awaitRegex.replace(code, "")
        }

        // 文件最后一句可能是对象字面量等有返回值的表达式，直接按 Unit 取会触发
        // 「No such type converter ... to kotlin.Unit」。追加 void 0 把完成值置为 undefined。
        runtime.evaluate<Unit>(code + "\n;void 0;", file.name)

        refreshExportedFunctionNames(runtime)
        Log.d(TAG, "Exported functions: $exportedFunctionNames")
    }

    private suspend fun refreshExportedFunctionNames(runtime: QuickJs) {
        exportedFunctionNames.clear()
        val raw = try {
            runtime.evaluate<String?>("JSON.stringify(Object.keys(globalThis.exports || {}))") ?: "[]"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Object.keys(exports)", e)
            return
        }

        try {
            val parsed = json.parseToJsonElement(raw)
            if (parsed is JsonArray) {
                parsed.forEach { element ->
                    (element as? JsonPrimitive)?.contentOrNull?.let { key ->
                        exportedFunctionNames.add(key)
                        Log.d(TAG, "Found exported key: $key")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse exported keys: $raw", e)
        }
    }

    /**
     * 调用导出的函数
     *
     * 注意：必须在 PluginLoader 的 pluginDispatcher 上调用
     */
    suspend fun callFunction(name: String, params: JsonElement): JsonElement {
        val runtime = requireNotNull(quickJs) { "Sandbox not initialized" }

        if (!exportedFunctionNames.contains(name)) {
            throw IllegalArgumentException(
                "Function '$name' not found in exports. Available: $exportedFunctionNames"
            )
        }

        Log.d(TAG, "Calling function: $name with params: $params")

        return try {
            val paramsJson = json.encodeToString(JsonElement.serializer(), params)

            val callCode = """
                (function() {
                    try {
                        var __ret = globalThis.exports['$name']($paramsJson);
                        if (__ret && typeof __ret.then === 'function') {
                            var __resolved = null;
                            var __rejected = null;
                            __ret.then(function(v) { __resolved = v; }).catch(function(e) { __rejected = e; });
                            if (__rejected) {
                                return JSON.stringify({success: false, error: __rejected.message || String(__rejected)});
                            }
                            return JSON.stringify(__resolved);
                        }
                        var __json = JSON.stringify(__ret);
                        return typeof __json === 'undefined' ? 'null' : __json;
                    } catch(e) {
                        return JSON.stringify({success: false, error: e.message || String(e)});
                    }
                })()
            """.trimIndent()

            val result = runtime.evaluate<String?>(callCode, "$name.js")
            when {
                result == null -> JsonNull
                else -> try {
                    json.parseToJsonElement(result)
                } catch (e: Exception) {
                    JsonPrimitive(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call function '$name'", e)
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 使用 OkHttp 执行同步 HTTP 请求
     */
    private fun nativeFetch(url: String, optionsJson: String): String {
        Log.d(TAG, "nativeFetch: $url")
        return try {
            // 域名白名单检查
            if (allowedHosts.isNotEmpty() && !allowedHosts.contains("*")) {
                val host = java.net.URL(url).host
                val isAllowed = allowedHosts.any { allowed ->
                    host == allowed || host.endsWith(".$allowed")
                }
                if (!isAllowed) {
                    Log.w(TAG, "nativeFetch blocked: host='$host' not in allowedHosts=$allowedHosts")
                    return errorJson(
                        "Network request to '$host' is not allowed. Please add it to manifest.allowedHosts."
                    )
                }
            }

            val options = json.parseToJsonElement(optionsJson) as? JsonObject ?: JsonObject(emptyMap())
            val method = (options["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
            val headers = options["headers"] as? JsonObject
            val body = options["body"] as? JsonPrimitive

            val requestBuilder = Request.Builder().url(url)

            headers?.forEach { (key, value) ->
                val headerValue = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                requestBuilder.addHeader(key, headerValue)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post(
                    okhttp3.RequestBody.create(null, body?.contentOrNull ?: "")
                )
                "PUT" -> requestBuilder.put(
                    okhttp3.RequestBody.create(null, body?.contentOrNull ?: "")
                )
                "DELETE" -> {
                    val requestBody = body?.contentOrNull?.let {
                        okhttp3.RequestBody.create(null, it)
                    }
                    if (requestBody != null) requestBuilder.delete(requestBody)
                    else requestBuilder.delete()
                }
                else -> requestBuilder.get()
            }

            val fetchClient = okHttpClient.newBuilder()
                .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val response = fetchClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            val responseHeaders = response.headers

            val headersJson = responseHeaders.names().associateWith { name ->
                responseHeaders.values(name).joinToString(", ")
            }

            val result = buildString {
                append("{\"success\":true,")
                append("\"status\":$statusCode,")
                append("\"ok\":${statusCode in 200..299},")
                append(
                    "\"headers\":" + json.encodeToString(
                        JsonObject.serializer(),
                        JsonObject(headersJson.mapValues { JsonPrimitive(it.value) })
                    ) + ","
                )
                append("\"body\":" + json.encodeToString(JsonPrimitive(responseBody)))
                append("}")
            }
            Log.d(TAG, "nativeFetch response: status=$statusCode, bodyLength=${responseBody.length}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "nativeFetch failed: url=$url", e)
            errorJson(e.message ?: "Unknown error")
        }
    }

    /**
     * MusicPlayer 桥接 - 由 JS 插件调用，同步控制音乐播放
     *
     * 真正的播放实现见 [me.rerere.rikkahub.plugin.webview.MusicPlayerService]，
     * 该服务在插件 WebView 能力接入后由本方法转发。
     */
    private fun nativeMusicPlayerBridge(action: String, paramsJson: String): String {
        return errorJson("musicPlayer bridge is not available yet")
    }

    /**
     * PluginDataStore 桥接 - 由 JS 插件调用，同步操作数据存储
     */
    private fun nativeDataStoreBridge(action: String, paramsJson: String): String {
        val store = dataStore
        if (store == null) {
            Log.w(TAG, "DataStore bridge called but dataStore is null, action=$action")
            return errorJson("dataStore not available for this plugin")
        }
        return try {
            val params = JSONObject(paramsJson)
            when (action) {
                "set" -> {
                    val key = params.optString("key", "")
                    val value = params.optString("value", "")
                    if (key.isBlank()) return errorJson("key is required")
                    store.setData(key, value)
                    """{"success":true}"""
                }
                "get" -> {
                    val key = params.optString("key", "")
                    if (key.isBlank()) return errorJson("key is required")
                    val value = store.getData(key)
                    if (value != null) {
                        """{"success":true,"value":${escapeJson(value)}}"""
                    } else {
                        errorJson("key not found: $key")
                    }
                }
                "delete" -> {
                    val key = params.optString("key", "")
                    if (key.isBlank()) return errorJson("key is required")
                    store.deleteData(key)
                    """{"success":true}"""
                }
                "list" -> {
                    val prefix = params.optString("prefix", "")
                    val keys = store.listData().filter { it.startsWith(prefix) }
                    val keysJson = keys.joinToString(",", "[", "]") { escapeJson(it) }
                    """{"success":true,"keys":$keysJson}"""
                }
                else -> {
                    Log.w(TAG, "DataStore bridge unknown action: $action")
                    errorJson("unknown action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DataStore bridge action='$action' failed, params=$paramsJson", e)
            errorJson(e.message ?: "Unknown error")
        }
    }

    /**
     * 转义 JSON 字符串
     *
     * 使用 kotlinx.serialization 的 JsonPrimitive 序列化器，完整、正确、符合 JSON 规范地转义，
     * 不会漏掉 \u0000-\u001F 范围内的任何控制字符。
     */
    private fun escapeJson(str: String): String {
        return json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(str))
    }

    private fun errorJson(message: String): String {
        return buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", JsonPrimitive(message))
        }.toString()
    }

    /**
     * 注入配置
     */
    suspend fun injectConfig(config: Map<String, JsonElement>) {
        val runtime = quickJs ?: return
        val configJson = json.encodeToString(JsonObject.serializer(), JsonObject(config))
        runtime.evaluate<Unit>("globalThis.config = $configJson; void 0;")
    }

    fun hasFunction(name: String): Boolean = exportedFunctionNames.contains(name)

    fun getExportedFunctionNames(): Set<String> = exportedFunctionNames.toSet()

    fun destroy() {
        exportedFunctionNames.clear()
        try {
            quickJs?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close QuickJS runtime", e)
        }
        quickJs = null
        Log.d(TAG, "Sandbox destroyed")
    }
}

/**
 * 插件运行环境的自举脚本
 *
 * 在加载插件入口文件之前注入，提供 polyfill、exports 容器与原生桥接对象。
 * 与上游 PluginSandbox 保持同一份语义，只把宿主函数换成 dokar3 的绑定名。
 */
private val BOOTSTRAP_JS = """
// TextEncoder polyfill - UTF-8 encoding
function TextEncoder() {}
TextEncoder.prototype.encode = function(str) {
    str = str || '';
    var bytes = [];
    for (var i = 0; i < str.length; ) {
        var codePoint = str.codePointAt(i);
        if (codePoint < 0x80) {
            bytes.push(codePoint);
        } else if (codePoint < 0x800) {
            bytes.push(0xC0 | (codePoint >> 6));
            bytes.push(0x80 | (codePoint & 0x3F));
        } else if (codePoint < 0x10000) {
            bytes.push(0xE0 | (codePoint >> 12));
            bytes.push(0x80 | ((codePoint >> 6) & 0x3F));
            bytes.push(0x80 | (codePoint & 0x3F));
        } else {
            bytes.push(0xF0 | (codePoint >> 18));
            bytes.push(0x80 | ((codePoint >> 12) & 0x3F));
            bytes.push(0x80 | ((codePoint >> 6) & 0x3F));
            bytes.push(0x80 | (codePoint & 0x3F));
        }
        i += codePoint > 0xFFFF ? 2 : 1;
    }
    return new Uint8Array(bytes);
};

// TextDecoder polyfill - UTF-8 decoding
function TextDecoder(encoding) {
    this.encoding = encoding || 'utf-8';
    this.fatal = false;
    this.ignoreBOM = false;
}
TextDecoder.prototype.decode = function(input) {
    if (!input) return '';
    var bytes;
    if (input instanceof Uint8Array) {
        bytes = input;
    } else if (input instanceof ArrayBuffer) {
        bytes = new Uint8Array(input);
    } else {
        return '';
    }
    var result = '';
    var i = 0;
    while (i < bytes.length) {
        var byte1 = bytes[i++];
        if (byte1 < 0x80) {
            result += String.fromCodePoint(byte1);
        } else if ((byte1 & 0xE0) === 0xC0) {
            var byte2 = bytes[i++];
            result += String.fromCodePoint(((byte1 & 0x1F) << 6) | (byte2 & 0x3F));
        } else if ((byte1 & 0xF0) === 0xE0) {
            var byte2 = bytes[i++];
            var byte3 = bytes[i++];
            result += String.fromCodePoint(((byte1 & 0x0F) << 12) | ((byte2 & 0x3F) << 6) | (byte3 & 0x3F));
        } else if ((byte1 & 0xF8) === 0xF0) {
            var byte2 = bytes[i++];
            var byte3 = bytes[i++];
            var byte4 = bytes[i++];
            result += String.fromCodePoint(((byte1 & 0x07) << 18) | ((byte2 & 0x3F) << 12) | ((byte3 & 0x3F) << 6) | (byte4 & 0x3F));
        }
    }
    return result;
};

// btoa polyfill
var btoa = function(str) {
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var result = '';
    for (var i = 0; i < str.length; i += 3) {
        var b1 = str.charCodeAt(i);
        var b2 = (i + 1 < str.length) ? str.charCodeAt(i + 1) : 0;
        var b3 = (i + 2 < str.length) ? str.charCodeAt(i + 2) : 0;
        result += chars[(b1 >> 2) & 0x3F];
        result += chars[((b1 << 4) | (b2 >> 4)) & 0x3F];
        result += (i + 1 < str.length) ? chars[((b2 << 2) | (b3 >> 6)) & 0x3F] : '=';
        result += (i + 2 < str.length) ? chars[b3 & 0x3F] : '=';
    }
    return result;
};

// atob polyfill
var atob = function(str) {
    str = str.replace(/\s/g, '');
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var result = '';
    for (var i = 0; i < str.length; i += 4) {
        var c1 = chars.indexOf(str[i]);
        var c2 = chars.indexOf(str[i + 1]);
        var c3 = chars.indexOf(str[i + 2]);
        var c4 = chars.indexOf(str[i + 3]);
        result += String.fromCharCode((c1 << 2) | (c2 >> 4));
        if (c3 !== -1) result += String.fromCharCode(((c2 << 4) | (c3 >> 2)) & 0xFF);
        if (c4 !== -1) result += String.fromCharCode(((c3 << 6) | c4) & 0xFF);
    }
    return result;
};

// 插件导出容器：全局唯一，跨 evaluate 调用保持存在
if (typeof globalThis.exports === 'undefined') {
    globalThis.exports = {};
}
var exports = globalThis.exports;

// 原生桥接变量（由 Android 注入）
var __nativeFetch = globalThis.__nativeFetch || null;
var __dataStoreBridge = globalThis.__dataStoreBridge || null;
var __musicPlayerBridge = globalThis.__musicPlayerBridge || null;

// musicPlayer 桥接对象 - 插件可直接调用
var musicPlayer = {
    play: function(filePath, title, artist) {
        if (!__musicPlayerBridge) throw new Error('musicPlayer bridge not available');
        return JSON.parse(__musicPlayerBridge('play', JSON.stringify({filePath: filePath, title: title || '', artist: artist || ''})));
    },
    pause: function() {
        if (!__musicPlayerBridge) throw new Error('musicPlayer bridge not available');
        return JSON.parse(__musicPlayerBridge('pause', '{}'));
    },
    resume: function() {
        if (!__musicPlayerBridge) throw new Error('musicPlayer bridge not available');
        return JSON.parse(__musicPlayerBridge('resume', '{}'));
    },
    stop: function() {
        if (!__musicPlayerBridge) throw new Error('musicPlayer bridge not available');
        return JSON.parse(__musicPlayerBridge('stop', '{}'));
    },
    getStatus: function() {
        if (!__musicPlayerBridge) return {state: 'stopped', title: '', artist: ''};
        return JSON.parse(__musicPlayerBridge('getStatus', '{}'));
    }
};

// dataStore 桥接对象 - 插件可直接调用
var dataStore = {
    set: function(key, value) {
        if (!__dataStoreBridge) throw new Error('dataStore bridge not available');
        var r = JSON.parse(__dataStoreBridge('set', JSON.stringify({key: key, value: value})));
        if (!r.success) throw new Error(r.error || 'dataStore.set failed: ' + key);
        return true;
    },
    get: function(key) {
        if (!__dataStoreBridge) return null;
        var r = JSON.parse(__dataStoreBridge('get', JSON.stringify({key: key})));
        if (!r.success) return null;
        return r.value;
    },
    del: function(key) {
        if (!__dataStoreBridge) return false;
        var r = JSON.parse(__dataStoreBridge('delete', JSON.stringify({key: key})));
        return r.success === true;
    },
    list: function(prefix) {
        if (!__dataStoreBridge) return [];
        var r = JSON.parse(__dataStoreBridge('list', JSON.stringify({prefix: prefix || ''})));
        if (!r.success) return [];
        return r.keys || [];
    }
};

// fetch 同步包装
function fetch(url, options) {
    if (!__nativeFetch) {
        throw new Error('fetch is not available: native fetch not injected');
    }
    var optsJson = options ? JSON.stringify(options) : '{}';
    var resultJson = __nativeFetch(url, optsJson);
    var result = JSON.parse(resultJson);
    if (!result.success) {
        throw new Error(result.error || 'fetch failed');
    }
    return {
        ok: result.ok,
        status: result.status,
        headers: result.headers,
        body: result.body,
        text: function() { return result.body; },
        json: function() { return JSON.parse(result.body); }
    };
}

// console polyfill - 插件里常见调试输出，缺失会直接抛 ReferenceError
// 输出转发到 Android logcat（tag = PluginSandbox）
var __consoleLog = globalThis.__consoleLog || null;
function __consoleFormat(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
        var v = args[i];
        if (typeof v === 'string') {
            parts.push(v);
        } else if (typeof v === 'undefined') {
            parts.push('undefined');
        } else if (v === null) {
            parts.push('null');
        } else {
            try {
                parts.push(JSON.stringify(v));
            } catch (e) {
                parts.push(String(v));
            }
        }
    }
    return parts.join(' ');
}
function __consoleSend(level, args) {
    try {
        if (typeof __consoleLog === 'function') {
            __consoleLog(level, __consoleFormat(args));
        }
    } catch (e) {
        // 日志失败绝不能影响插件本身
    }
}
globalThis.console = {
    log: function() { __consoleSend('log', arguments); },
    info: function() { __consoleSend('info', arguments); },
    warn: function() { __consoleSend('warn', arguments); },
    error: function() { __consoleSend('error', arguments); },
    debug: function() { __consoleSend('debug', arguments); }
};
"""
