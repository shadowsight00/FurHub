package com.unifiedart.app.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ArtworkTest {
    private fun art(source: Source, id: String, time: Long) = Artwork(source, id, "", emptyList(), emptyList(), time, "")

    @Test fun sourceIdsDoNotCollideAndNewestItemsComeFirst() {
        val first = art(Source.E621, "7", 10)
        val second = art(Source.INKBUNNY, "7", 20)
        assertEquals(listOf(second, first), mergeArtwork(listOf(first), listOf(second, first)))
    }

    @Test fun inkbunnyAndIsoOffsetsRepresentSameMoment() {
        val expected = timestamp("2026-11-16T15:00:00Z")
        assertTrue(expected > 0)
        assertEquals(expected, timestamp("2026-11-16 10:00:00-05:00"))
        assertEquals(expected, timestamp("2026-11-16 10:00:00-0500"))
        assertEquals(expected, timestamp("2026-11-16 10:00:00-05"))
    }

    @Test fun accountBlacklistRespectsAndOrNegationAndRatings() {
        val post = art(Source.E621, "7", 10).copy(tags = listOf("wolf", "blue_eyes"), filterFields = mapOf("rating" to "e", "score" to "15"))
        assertTrue(blacklisted(post, "wolf -red_eyes"))
        assertFalse(blacklisted(post, "wolf -blue_eyes"))
        assertTrue(blacklisted(post, "wolf ~green_eyes ~blue_eyes"))
        assertTrue(blacklisted(post, "rating:explicit score:10..20"))
        assertTrue(blacklisted(post, "blue_* # user comment"))
        assertFalse(blacklisted(post, "# disabled rule\ncat"))
        assertFalse(blacklisted(post.copy(source = Source.INKBUNNY), "wolf"))
    }

    @Test fun sessionsAreOnlyAttachedToPrivateInkbunnyAssets() {
        assertEquals("https://inkbunny.net/private_files/full/a.png?sid=new", mediaSessionUrl("https://inkbunny.net/private_files/full/a.png?sid=old", "new"))
        assertEquals("https://inkbunny.net/files/a.png", mediaSessionUrl("https://inkbunny.net/files/a.png?sid=old", "new"))
        assertEquals("https://example.com/private_files/a.png", mediaSessionUrl("https://example.com/private_files/a.png", "secret"))
        assertEquals("https://inkbunny.net/private_files/a.png", mediaSessionUrl("https://inkbunny.net/private_files/a.png?sid=old", ""))
    }

    @Test fun cachedArtworkKeepsOrderAndMetadataButNeverSessionTokens() {
        val source = art(Source.INKBUNNY, "8", 33).copy(files = listOf(MediaFile("https://inkbunny.net/private_files/full/a.png?sid=secret", "image/png")))
        val json = source.toJson()
        assertFalse(json.contains("secret"))
        val cached = artworkFromJson(json)
        assertEquals(source.key, cached.key)
        assertEquals(source.uploadedAt, cached.uploadedAt)
        assertEquals("https://inkbunny.net/private_files/full/a.png", cached.files.first().url)
    }

    @Test fun freshMetadataReplacesCachedMetadataForTheSameSourceId() {
        val cached = art(Source.E621, "3", 10).copy(needsVerification = true)
        assertFalse(mergeArtwork(listOf(cached), listOf(cached.copy(needsVerification = false))).single().needsVerification)
    }

    @Test fun e621NullMediaNeverBecomesALiteralNullUrl() {
        val art = E621Source().parse(JSONObject("""{"id":8,"file":{"url":null},"preview":{"url":null},"tags":{"artist":["example"]},"pools":[1,2]}"""))
        assertTrue(art.files.isEmpty())
        assertEquals("", art.thumbnail)
        assertEquals(listOf("1", "2"), art.pools)
        assertEquals(listOf("example"), art.artists)
    }

    @Test fun inkbunnyFilesUseSubmissionOrderAndPreserveBlocking() {
        val art = InkbunnySource().parse(JSONObject("""{"submission_id":"8","hidden":"t","files":[
            {"submission_file_order":1,"file_url_full":"https://example.com/2.png","mimetype":"image/png"},
            {"submission_file_order":0,"file_url_full":"https://example.com/1.png","mimetype":"image/png"}]}"""))
        assertTrue(art.blocked)
        assertEquals("https://example.com/1.png", art.files.first().url)
    }

    @Test fun inkbunnySearchUsesNoncustomThumbnailWhenCustomIsAbsent() {
        val post = JSONObject("""{"submission_id":"9","thumbnail_url_large_noncustom":"https://nl1.ib.metapix.net/thumbnails/large/9.jpg"}""")
        assertEquals("https://nl1.ib.metapix.net/thumbnails/large/9.jpg", InkbunnySource().parse(post).thumbnail)
        post.put("thumbnail_url_large", "https://nl1.ib.metapix.net/thumbnails/large/custom.jpg")
        assertEquals("https://nl1.ib.metapix.net/thumbnails/large/custom.jpg", InkbunnySource().parse(post).thumbnail)
    }

    @Test fun inkbunnyThumbnailFallsBackPastMissingNullAndEmptyFields() {
        val post = JSONObject("""{"submission_id":"9","thumbnail_url_large":null,"thumbnail_url_large_noncustom":"","thumbnail_url_medium_noncustom":"https://inkbunny.net/private_thumbnails/medium/9.jpg?sid=secret"}""")
        assertEquals("https://inkbunny.net/private_thumbnails/medium/9.jpg", InkbunnySource().parse(post).thumbnail)
        assertEquals("", InkbunnySource().parse(JSONObject("""{"submission_id":"10"}""")).thumbnail)
    }

    @Test fun blockedArtworkIsRemovedFromBothSourcesAndCachedEntries() {
        val allowed = art(Source.E621, "1", 1)
        val blocked = art(Source.E621, "2", 2).copy(blocked = true)
        val inkBlocked = art(Source.INKBUNNY, "3", 3).copy(blocked = true)
        assertEquals(listOf(allowed), mergeArtwork(listOf(blocked, inkBlocked), listOf(allowed)))
        assertTrue(mergeArtwork(listOf(allowed), listOf(allowed.copy(blocked = true))).isEmpty())
    }
}
