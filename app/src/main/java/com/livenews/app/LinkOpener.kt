package com.livenews.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.livenews.app.data.Links

object LinkOpener {

    /** Opens an article. Feed content is untrusted, so anything that is not a web address is refused. */
    fun open(context: Context, url: String, inApp: Boolean) {
        if (!Links.isWeb(url)) return
        val uri = Uri.parse(url)
        try {
            if (inApp) {
                val tab = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                    .build()
                if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(context, uri)
            } else {
                val view = Intent(Intent.ACTION_VIEW, uri)
                if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(view)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser is installed to open this.", Toast.LENGTH_LONG).show()
        }
    }

    fun share(context: Context, title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (e: ActivityNotFoundException) {
        }
    }
}
