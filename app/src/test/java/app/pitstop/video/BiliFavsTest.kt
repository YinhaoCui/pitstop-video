package app.pitstop.video

import app.pitstop.video.bilibili.BiliFavs
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.SourceId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signed-in user's Bilibili favourites as folder rows (fav/folder created + collected lists). */
class BiliFavsTest {
    private val count: (Int) -> String = { "$it videos" }

    @Test fun createdFoldersBecomeFolderRows() {
        val data = JSONObject(
            """{"count":2,"list":[
              {"id":1052622027,"fid":10526220,"mid":27,"attr":1,"title":"默认收藏夹","fav_state":0,"media_count":42},
              {"id":2001,"fid":20,"mid":27,"attr":22,"title":"<em>Car</em> songs","media_count":7},
              {"id":0,"title":"broken"}
            ]}""",
        )
        val rows = BiliFavs.created(data, SourceId.BILIBILI, count)
        assertEquals(listOf("默认收藏夹", "Car songs"), rows.map { it.title })
        assertEquals("42 videos", rows[0].author)
        assertTrue(rows.all { it.id.startsWith(COLLECTION_PREFIX) })
        assertEquals(BiliFavs.Ref.Folder(1052622027), BiliFavs.parse(rows[0].id))
    }

    @Test fun collectedListKeepsFoldersAndSeasons() {
        val data = JSONObject(
            """{"count":3,"has_more":false,"list":[
              {"id":555,"type":11,"title":"Someone's folder","cover":"http://i0.hdslb.com/a.jpg","media_count":3,"upper":{"mid":9,"name":"Ann"}},
              {"id":777,"type":21,"title":"合集 A","cover":"","media_count":12,"upper":{"mid":42,"name":"Bob"}},
              {"id":888,"type":99,"title":"unknown kind","media_count":1}
            ]}""",
        )
        val rows = BiliFavs.collected(data, SourceId.BILI_MUSIC, count)
        assertEquals(2, rows.size)
        assertEquals(BiliFavs.Ref.Folder(555), BiliFavs.parse(rows[0].id))
        assertEquals(BiliFavs.Ref.Season(42, 777), BiliFavs.parse(rows[1].id))
        assertEquals("Bob · 12 videos", rows[1].author)
        assertEquals(SourceId.BILI_MUSIC, rows[1].source)
    }

    @Test fun parseRejectsOtherIds() {
        assertNull(BiliFavs.parse("BV1xx411c7mD"))
        assertNull(BiliFavs.parse(COLLECTION_PREFIX + "123"))
        assertNull(BiliFavs.parse(BiliFavs.folderId(5).dropLast(1) + "x"))
        assertNull(BiliFavs.parse(COLLECTION_PREFIX + "season:1"))
    }

    @Test fun emptyOrMissingData() {
        assertTrue(BiliFavs.created(null, SourceId.BILIBILI, count).isEmpty())
        assertTrue(BiliFavs.collected(JSONObject("{\"list\":null}"), SourceId.BILIBILI, count).isEmpty())
    }
}
