package com.livenews.app

import android.app.Activity
import android.os.Bundle
import androidx.glance.action.ActionParameters
import kotlinx.coroutines.launch

/** A headline tapped on the widget lands here: mark it read, open it, and get out of the way. */
class OpenLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LiveNewsApp
        val url = intent?.getStringExtra(EXTRA_URL)
        val articleId = intent?.getStringExtra(EXTRA_ARTICLE_ID)
        if (url != null) {
            if (articleId != null) app.appScope.launch { app.repository.markRead(articleId) }
            LinkOpener.open(this, url, app.settings.settings.value.openInApp)
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ARTICLE_ID = "articleId"
        val UrlKey = ActionParameters.Key<String>(EXTRA_URL)
        val ArticleIdKey = ActionParameters.Key<String>(EXTRA_ARTICLE_ID)
    }
}
