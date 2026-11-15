package com.panellog.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** Сжатие и безопасное чтение фотографий с учётом поворота EXIF. */
object PhotoUtil {

    private fun exifMatrix(path: String): Matrix {
        val m = Matrix()
        val orient = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        return m
    }

    /** Декодирует фото с уменьшением до maxPx по длинной стороне и поворотом по EXIF. */
    fun loadBitmap(path: String, maxPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val m = exifMatrix(path)
            val longSide = maxOf(raw.width, raw.height)
            if (longSide > maxPx) {
                val scale = maxPx.toFloat() / longSide
                m.postScale(scale, scale)
            }
            if (m.isIdentity) return raw
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out !== raw) raw.recycle()
            out
        } catch (_: Throwable) { null }
    }

    /**
     * Сжимает фото на диске в JPEG (до maxPx по длинной стороне, качество quality),
     * применяя EXIF-поворот к пикселям. Уже компактные и ровно ориентированные файлы не трогает.
     */
    fun compressJpeg(file: File, maxPx: Int = 1920, quality: Int = 85, skipBytes: Long = 400 * 1024) {
        if (!file.exists() || file.length() == 0L) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return
            val m = exifMatrix(file.absolutePath)
            val oversized = maxOf(o.outWidth, o.outHeight) > maxPx
            if (!oversized && m.isIdentity && file.length() <= skipBytes) return

            val bmp = loadBitmap(file.absolutePath, maxPx) ?: return
            try {
                FileOutputStream(tmp).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, out) }
            } finally {
                bmp.recycle()
            }
            if (tmp.exists() && tmp.length() > 0L && (oversized || !m.isIdentity || tmp.length() < file.length())) {
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            } else {
                tmp.delete()
            }
        } catch (_: Throwable) {
            tmp.delete()
        }
    }
}
