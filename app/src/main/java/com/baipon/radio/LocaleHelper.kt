package com.baipon.radio

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {

    // 根据语言代码获取 Locale 对象
    fun getLocaleFromCode(languageCode: String): Locale {
        return when (languageCode) {
            "zh-CN" -> Locale("zh", "CN")
            "zh-TW" -> Locale("zh", "TW")
            "en" -> Locale("en")
            else -> Locale("zh", "CN")
        }
    }

    // 设置语言
    fun setLocale(context: Context, languageCode: String): Context {
        val locale = getLocaleFromCode(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                config.setLocale(locale)
                config.setLayoutDirection(locale)
            }
            else -> {
                @Suppress("DEPRECATION")
                config.locale = locale
                config.setLayoutDirection(locale)
            }
        }

        return context.createConfigurationContext(config)
    }

    // 更新 Context 的语言
    fun updateResources(context: Context, languageCode: String): Context {
        return setLocale(context, languageCode)
    }

    // 获取当前语言
    fun getCurrentLanguage(context: Context): String {
        val config = context.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }

        return when (locale.language) {
            "zh" -> {
                if (locale.country == "TW" || locale.country == "HK" || locale.country == "MO") {
                    "zh-TW"
                } else {
                    "zh-CN"
                }
            }
            "en" -> "en"
            else -> "zh-CN"
        }
    }

    // 保存语言设置
    fun saveLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("language", languageCode).apply()
    }

    // 获取保存的语言设置
    fun getSavedLanguage(context: Context): String? {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString("language", null)
    }

    // 【可选】检查是否首次启动（没有任何保存的语言）
    fun isFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return !prefs.contains("language")
    }
}