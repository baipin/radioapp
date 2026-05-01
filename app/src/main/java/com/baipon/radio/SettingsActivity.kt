package com.baipon.radio

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.WebStorage
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

class SettingsActivity : AppCompatActivity() {

    private val mainScope = MainScope()
    // 更新配置地址
    private val updateJsonUrl = "https://radio.baipon.com/android.json"

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

        // --- 核心交互部分 ---

        // 关于按钮
        findViewById<LinearLayout>(R.id.btn_about)?.setOnClickListener {
            showAboutDialog()
        }

        // 隐私政策按钮（新增）
        findViewById<LinearLayout>(R.id.btn_privacy_policy)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, "https://radio.baipon.com/privacy_policy".toUri())
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
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
     * 独立运作的版本更新逻辑
     * 不再返回 MainActivity，直接在此处完成 联网 -> 解析 -> 弹窗
     */
    private fun checkUpdateIndependent() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()

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
                val updateLog = result.getString("updateLog")

                if (serverCode > currentCode) {
                    showUpdateDialog(serverName, updateLog, downloadUrl)
                } else {
                    Toast.makeText(this@SettingsActivity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "检查失败: 网络异常", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(newName: String, log: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本: $newName")
            .setMessage(log)
            .setPositiveButton("立即下载") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("以后再说", null)
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
            .setTitle("关于百品电台")
            .setMessage("版本: ${getAppVersionName()}\n\n致力于提供最纯净的收听体验。\n\n百品电台是一款提供自动全球服务器加速的免费、轻量电台服务软件，覆盖中国大陆、港澳台新等华语地区和部分英语广播，提供随时随地的、稳定性堪比调频广播的收音体验。")
            .setPositiveButton("确定", null)
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
                Toast.makeText(this@SettingsActivity, "缓存已清除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            .setTitle("清除缓存")
            .setMessage("确定要清除所有缓存吗？")
            .setPositiveButton("确定") { _, _ -> clearCache() }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}