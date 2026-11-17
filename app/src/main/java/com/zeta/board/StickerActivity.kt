package com.zeta.board

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible helper screen for the sticker maker. It opens the system photo picker for the keyboard,
 * and it is also the "Share -> Zeta Sticker" target for photos shared from the gallery or other apps.
 * It closes by itself as soon as the photo is read, so the user lands back where they were typing.
 */
class StickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = sharedUri()
            if (uri == null) {
                finish()
                return
            }
            Toast.makeText(this, "Making sticker…", Toast.LENGTH_SHORT).show()
            handle(uri)
            return
        }
        if (savedInstanceState != null) return // picker is already open, wait for its result
        try {
            val pick = Intent(Intent.ACTION_GET_CONTENT)
            pick.type = "image/*"
            pick.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(pick, REQ)
        } catch (e: Exception) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_LONG).show()
            StickerHub.cancel()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ || resultCode != RESULT_OK || uri == null) {
            StickerHub.cancel()
            finish()
            return
        }
        handle(uri)
    }

    /** Reads the photo while the permission is still valid, then hands it to the sticker engine and closes. */
    private fun handle(uri: Uri) {
        val app = applicationContext
        StickerHub.begin("Reading photo…")
        Thread {
            try {
                val img = StickerMaker.prepare(app, uri)
                StickerHub.submitImg(app, img)
            } catch (e: Throwable) {
                StickerHub.fail("Couldn't read that image")
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }

    private companion object {
        const val REQ = 41
    }
}
