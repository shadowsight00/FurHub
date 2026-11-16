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
}
