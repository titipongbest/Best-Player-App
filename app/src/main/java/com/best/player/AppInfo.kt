package com.best.player

import android.content.Context

object AppInfo {
    fun versionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    /** "Best Player v1.0 · By NorthXbar" */
    fun credit(context: Context): String =
        context.getString(R.string.credit, versionName(context))
}
