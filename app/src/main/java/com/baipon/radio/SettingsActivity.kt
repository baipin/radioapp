package com.baipon.radio

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.view.WindowInsetsControllerCompat
import android.content.res.Configuration
import android.content.Context

class SettingsActivity : AppCompatActivity() {

    private val mainScope = MainScope()
    // 更新配置地址
    private val updateJsonUrl = "https://radio.baipon.com/android.json"

    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = LocaleHelper.getSavedLanguage(newBase)
        val languageCode = savedLanguage ?: LocaleHelper.getCurrentLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, languageCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 在 setContentView 之后调用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val windowController = WindowInsetsControllerCompat(window, window.decorView)
            // 根据当前是否是深色模式切换图标颜色
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // 如果不是深色模式（即白天模式），则将状态栏文字设为黑色（true）
            windowController.isAppearanceLightStatusBars = !isNightMode
        }

        // 设置 Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)
            toolbar.setNavigationOnClickListener { finish() }
        }

        // 显示版本号
        val versionText = findViewById<TextView>(R.id.tv_version)
        versionText?.text = getAppVersionName()

        // 更新并显示缓存大小
        updateCacheSize()

        // 设置当前语言显示
        updateLanguageDisplay()

        // --- 核心交互部分 ---

        // 关于按钮
        findViewById<LinearLayout>(R.id.btn_about)?.setOnClickListener {
            showAboutDialog()
        }

        // 语言设置按钮
        findViewById<LinearLayout>(R.id.btn_language)?.setOnClickListener {
            showLanguageDialog()
        }

        // 隐私政策按钮
        findViewById<LinearLayout>(R.id.btn_privacy_policy)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, "https://radio.baipon.com/privacy_policy".toUri())
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
            }
        }

        // 检查更新按钮 (独立运作)
        findViewById<LinearLayout>(R.id.btn_check_update)?.setOnClickListener {
            checkUpdateIndependent()
        }

        // 清除缓存按钮
        findViewById<LinearLayout>(R.id.btn_clear_cache)?.setOnClickListener {
            showClearCacheDialog()
        }
    }

    /**
     * 更新语言显示
     */
    private fun updateLanguageDisplay() {
        val languageText = findViewById<TextView>(R.id.tv_language_value)
        val currentLang = LocaleHelper.getCurrentLanguage(this)
        val languageName = when (currentLang) {
            "zh-CN" -> getString(R.string.language_simplified)
            "zh-TW" -> getString(R.string.language_traditional)
            "en" -> getString(R.string.language_english)
            else -> getString(R.string.language_simplified)
        }
        languageText?.text = languageName
    }

    /**
     * 显示语言选择对话框
     */
    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.language_simplified),
            getString(R.string.language_traditional),
            getString(R.string.language_english)
        )

        val languageCodes = arrayOf("zh-CN", "zh-TW", "en")
        val currentLang = LocaleHelper.getCurrentLanguage(this)
        var checkedIndex = 0
        for (i in languageCodes.indices) {
            if (languageCodes[i] == currentLang) {
                checkedIndex = i
                break
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.language_switch))
            .setSingleChoiceItems(languages, checkedIndex) { dialog, which ->
                val newLanguage = languageCodes[which]
                if (newLanguage != currentLang) {
                    // 保存语言设置
                    LocaleHelper.saveLanguage(this, newLanguage)

                    // 显示提示信息
                    Toast.makeText(this, getString(R.string.language_changed_restart), Toast.LENGTH_SHORT).show()

                    // 更新配置（立即生效）
                    val config = resources.configuration
                    val locale = LocaleHelper.getLocaleFromCode(newLanguage)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        config.setLocale(locale)
                    } else {
                        @Suppress("DEPRECATION")
                        config.locale = locale
                    }
                    resources.updateConfiguration(config, resources.displayMetrics)

                    // 【修复】清空任务栈，重新启动 MainActivity，并传递重新加载标志
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("reload_webview", true)
                    }
                    startActivity(intent)
                    finish()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * 独立运作的版本更新逻辑
     */
    private fun checkUpdateIndependent() {
        Toast.makeText(this, getString(R.string.checking_update), Toast.LENGTH_SHORT).show()

        // 获取当前版本号
        val currentCode = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
        } catch (_: Exception) { 1L }

        // 使用协程进行异步联网
        mainScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection = URL(updateJsonUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    val data = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    JSONObject(data)
                }

                val serverCode = result.getLong("versionCode")
                val serverName = result.getString("versionName")

                val downloadUrl = result.getString("downloadUrl")

                // 获取多语言更新日志
                val updateLog = getUpdateLogByLanguage(result)

                if (serverCode > currentCode) {
                    showUpdateDialog(serverName, updateLog, downloadUrl)
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.already_latest), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SettingsActivity, getString(R.string.update_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 根据当前语言获取对应的更新日志
     */
    private fun getUpdateLogByLanguage(jsonObject: JSONObject): String {
        return try {
            // 检查 updateLog 是否是 JSONObject
            if (jsonObject.has("updateLog")) {
                val updateLogObj = jsonObject.getJSONObject("updateLog")

                // 获取当前语言
                val currentLang = LocaleHelper.getCurrentLanguage(this)

                // 根据当前语言选择对应的日志
                val logText = when (currentLang) {
                    "zh-CN" -> updateLogObj.optString("zh-CN", "")
                    "zh-TW" -> updateLogObj.optString("zh-TW", "")
                    "zh-HK" -> updateLogObj.optString("zh-HK", "")
                    "en" -> updateLogObj.optString("en", "")
                    else -> updateLogObj.optString("en", "")
                }

                // 如果对应语言的日志为空，尝试使用英文或简体中文
                if (logText.isEmpty()) {
                    val fallback = updateLogObj.optString("en", "")
                    if (fallback.isNotEmpty()) fallback else updateLogObj.optString("zh-CN", getString(R.string.new_version_found))
                } else {
                    logText
                }
            } else {
                // 兼容旧版本 JSON（纯文本格式）
                jsonObject.getString("updateLog")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            jsonObject.optString("updateLog", getString(R.string.new_version_found))
        }
    }

    private fun showUpdateDialog(newName: String, log: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_version_found) + ": $newName")
            .setMessage(log)
            .setPositiveButton(getString(R.string.download_now)) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

    private fun getAppVersionName(): String = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        packageInfo.versionName ?: "1.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "1.0"
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message, getAppVersionName()))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun clearCache() {
        mainScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        // 注意：不要删除 WebStorage！这会清除 localStorage
                        // WebStorage.getInstance().deleteAllData()  // 注释掉这行！

                        // 只清除 cookies
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.CookieManager.getInstance().flush()
                    }
                    // 清除应用缓存文件
                    deleteDir(cacheDir)
                    if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                        deleteDir(externalCacheDir)
                    }
                }
                updateCacheSize()
                Toast.makeText(this@SettingsActivity, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.clear_failed) + ": ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            dir.list()?.forEach { deleteDir(File(dir, it)) }
        }
        return dir?.delete() ?: false
    }

    private fun updateCacheSize() {
        mainScope.launch {
            val totalSize = withContext(Dispatchers.IO) {
                var size = getFolderSize(cacheDir)
                externalCacheDir?.let { size += getFolderSize(it) }
                size
            }
            findViewById<TextView>(R.id.tv_cache_size)?.text = formatSize(totalSize)
        }
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { size += getFolderSize(it) }
        } else {
            size = file.length()
        }
        return size
    }

    private fun formatSize(size: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var sizeDouble = size.toDouble()
        var unitIndex = 0
        while (sizeDouble >= 1024 && unitIndex < units.size - 1) {
            sizeDouble /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", sizeDouble, units[unitIndex])
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_cache))
            .setMessage(getString(R.string.clear_cache_confirm))
            .setPositiveButton(getString(R.string.ok)) { _, _ -> clearCache() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}