package org.fossify.clock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.clock.helpers.RelaxItem
import org.fossify.clock.helpers.RelaxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the cross-platform urlKey dedup (must stay verbatim-aligned with
 * the website's urlKey() in cyberSleepCommunity/index.html) and the merge
 * behavior built on top of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = App::class)
class RelaxStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun urlKey_trailingSlash_isEquivalent() {
        assertEquals(
            RelaxStore.urlKey("https://mynoise.net/"),
            RelaxStore.urlKey("https://mynoise.net")
        )
    }

    @Test
    fun urlKey_wwwPrefix_isEquivalent() {
        assertEquals(
            RelaxStore.urlKey("https://www.noisli.com/"),
            RelaxStore.urlKey("https://noisli.com/")
        )
    }

    @Test
    fun urlKey_hostCase_isEquivalent() {
        assertEquals(
            RelaxStore.urlKey("https://LIBRIVOX.ORG/search"),
            RelaxStore.urlKey("https://librivox.org/search")
        )
    }

    @Test
    fun urlKey_queryParticipates() {
        assertTrue(
            RelaxStore.urlKey("https://m.bilibili.com/search?keyword=rain") !=
                RelaxStore.urlKey("https://m.bilibili.com/search?keyword=ocean")
        )
    }

    @Test
    fun urlKey_queryCaseAndOrderMatter_likeWebsite() {
        // the JS key keeps the raw query verbatim - so do we
        assertTrue(
            RelaxStore.urlKey("https://x.com/a?B=1&A=2") !=
                RelaxStore.urlKey("https://x.com/a?a=2&b=1")
        )
    }

    @Test
    fun urlKey_fragmentIsDropped() {
        assertEquals(
            RelaxStore.urlKey("https://x.com/a#section"),
            RelaxStore.urlKey("https://x.com/a")
        )
    }

    @Test
    fun urlKey_rootDomain_noPath() {
        assertEquals("bilibili.com", RelaxStore.urlKey("https://www.bilibili.com/"))
    }

    @Test
    fun urlKey_nonHttpScheme_stillNormalized() {
        assertEquals(
            RelaxStore.urlKey("bilibili.com/watch"),
            RelaxStore.urlKey("https://bilibili.com/watch")
        )
    }

    @Test
    fun mergeCustomItems_dedupsWwwAndSlashVariants() {
        RelaxStore.addCustomItem(context, "rain", "https://www.noisli.com/")
        val imported = listOf(
            RelaxItem(id = 1, title = "rain dup", url = "https://noisli.com"),
            RelaxItem(id = 2, title = "fresh", url = "https://mynoise.net/")
        )
        val added = RelaxStore.mergeCustomItems(context, imported)
        assertEquals(1, added)
        assertTrue(RelaxStore.getCustomItems(context).none { it.title == "rain dup" })
        RelaxStore.removeCustomItem(context, 2)
    }

    @Test
    fun isUrlFavorited_usesUrlKey() {
        assertFalse(RelaxStore.isUrlFavorited(context, "https://no-such-host.example/"))
        RelaxStore.addCustomItem(context, "x", "https://www.noisli.com/")
        assertTrue(RelaxStore.isUrlFavorited(context, "https://noisli.com"))
        RelaxStore.getCustomItems(context)
            .filter { RelaxStore.urlKey(it.url) == "noisli.com" }
            .forEach { RelaxStore.removeCustomItem(context, it.id) }
    }
}
