package app.okoklibre.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/** Profile photos, downscaled and kept in private app storage. They are never uploaded. */
class AvatarStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "avatars").apply { mkdirs() }

    /** Copies a picked image in as a small square-bounded JPEG and returns its path. */
    fun import(roleId: Long, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { longest / (it * 2) < MAX_SIDE_PX }
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("cannot decode $uri")
        val bitmap = upright(decoded, uri)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return store(roleId, out.toByteArray())
    }

    /** Phone cameras record rotation as metadata, and re-encoding would drop it. */
    private fun upright(bitmap: Bitmap, uri: Uri): Bitmap {
        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun encoded(role: RoleEntity): String? =
        role.avatarPath?.let(::File)?.takeIf { it.exists() }
            ?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }

    fun write(roleId: Long, encoded: String): String = store(roleId, Base64.decode(encoded, Base64.NO_WRAP))

    fun delete(role: RoleEntity) {
        role.avatarPath?.let(::File)?.delete()
    }

    private fun store(roleId: Long, bytes: ByteArray): String =
        File(dir, "$roleId.jpg").apply { writeBytes(bytes) }.absolutePath

    private companion object {
        const val MAX_SIDE_PX = 512
        const val JPEG_QUALITY = 85
    }
}
